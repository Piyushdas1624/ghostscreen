package com.etaegord.ghostscreen.updater

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Robust coroutine-based APK file downloader with real-time progress reporting,
 * Wi-Fi network constraints, and automated redirect resolution.
 */
object ApkDownloader {

    private const val TAG = "ApkDownloader"
    private const val BUFFER_SIZE = 8192
    private const val MAX_REDIRECTS = 6

    /**
     * Checks whether the device is currently connected to an unmetered/Wi-Fi network.
     */
    fun isConnectedToWifi(context: Context): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities =
            connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * Downloads an APK from [downloadUrl] into the app's internal update cache directory.
     *
     * @param context Application context
     * @param downloadUrl Remote URL for the APK asset
     * @param targetFileName Name of destination file
     * @param expectedSize Total size if already known from release metadata
     * @param wifiOnly If true, download will fail immediately if not connected to Wi-Fi
     * @param onProgress Callback invoked on the background coroutine with progress updates
     * @return Result containing the downloaded File, or exception on failure/cancellation
     */
    suspend fun download(
        context: Context,
        downloadUrl: String,
        targetFileName: String,
        expectedSize: Long = 0L,
        wifiOnly: Boolean = false,
        onProgress: (bytesDownloaded: Long, totalBytes: Long, progress: Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        if (wifiOnly && !isConnectedToWifi(context)) {
            val errorMsg = "Download blocked: Wi-Fi Only is enabled, but Wi-Fi is not connected."
            Log.w(TAG, errorMsg)
            return@withContext Result.failure(IllegalStateException(errorMsg))
        }

        val updatesDir = File(context.cacheDir, "updates")
        if (!updatesDir.exists()) {
            updatesDir.mkdirs()
        }

        if (downloadUrl.startsWith("mock://")) {
            val targetFile = File(updatesDir, targetFileName)
            val sourceApk = File(context.applicationInfo.sourceDir)
            val totalBytes = if (sourceApk.exists()) sourceApk.length() else 18_456_123L
            val steps = 10
            for (step in 1..steps) {
                currentCoroutineContext().ensureActive()
                kotlinx.coroutines.delay(120)
                val downloaded = (totalBytes * step) / steps
                onProgress(downloaded, totalBytes, step.toFloat() / steps.toFloat())
            }
            if (sourceApk.exists()) {
                sourceApk.copyTo(targetFile, overwrite = true)
            } else {
                targetFile.writeText("Mock APK content")
            }
            onProgress(totalBytes, totalBytes, 1f)
            Log.d(TAG, "Mock download completed: ${targetFile.absolutePath}")
            return@withContext Result.success(targetFile)
        }

        val targetFile = File(updatesDir, targetFileName)
        val tempFile = File(updatesDir, "$targetFileName.part")

        // Clean up any stale partial files
        if (tempFile.exists()) {
            tempFile.delete()
        }

        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            var currentUrl = downloadUrl
            var redirectCount = 0

            // Follow HTTP redirects (GitHub releases assets redirect to AWS S3)
            while (redirectCount < MAX_REDIRECTS) {
                currentCoroutineContext().ensureActive()
                val url = URL(currentUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 20000
                    readTimeout = 25000
                    setRequestProperty("User-Agent", "GhostScreen-Android-App")
                    setRequestProperty("Accept", "application/octet-stream, application/vnd.android.package-archive, */*")
                }

                val status = conn.responseCode
                if (status in listOf(
                        HttpURLConnection.HTTP_MOVED_PERM,
                        HttpURLConnection.HTTP_MOVED_TEMP,
                        HttpURLConnection.HTTP_SEE_OTHER,
                        307,
                        308
                    )
                ) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location.isNullOrEmpty()) {
                        throw IllegalStateException("Redirected with empty Location header (HTTP $status)")
                    }
                    currentUrl = location
                    redirectCount++
                } else if (status in 200..299) {
                    connection = conn
                    break
                } else {
                    conn.disconnect()
                    throw IllegalStateException("Download server returned HTTP error: $status")
                }
            }

            val finalConn = connection
                ?: throw IllegalStateException("Too many redirects while downloading update")

            val contentLength = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                finalConn.contentLengthLong.takeIf { it > 0 } ?: expectedSize
            } else {
                finalConn.contentLength.toLong().takeIf { it > 0 } ?: expectedSize
            }

            inputStream = finalConn.inputStream
            outputStream = FileOutputStream(tempFile)

            val buffer = ByteArray(BUFFER_SIZE)
            var bytesDownloaded = 0L
            var lastReportTime = 0L
            var lastWifiCheckTime = System.currentTimeMillis()

            while (true) {
                currentCoroutineContext().ensureActive()
                val read = inputStream.read(buffer)
                if (read == -1) break

                outputStream.write(buffer, 0, read)
                bytesDownloaded += read

                val now = System.currentTimeMillis()

                // Enforce Wi-Fi only constraint mid-stream if connection changes
                if (wifiOnly && (now - lastWifiCheckTime >= 2000L)) {
                    if (!isConnectedToWifi(context)) {
                        throw IllegalStateException("Download aborted: Wi-Fi connection was lost and 'Wi-Fi only' is enabled.")
                    }
                    lastWifiCheckTime = now
                }

                // Throttle progress updates to at most once per 100ms or on completion
                if (now - lastReportTime >= 100L || (contentLength > 0 && bytesDownloaded >= contentLength)) {
                    val progressFraction = if (contentLength > 0) {
                        (bytesDownloaded.toFloat() / contentLength.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    onProgress(bytesDownloaded, contentLength, progressFraction)
                    lastReportTime = now
                }
            }

            outputStream.flush()
            outputStream.close()
            outputStream = null

            // Atomic rename to final target file
            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tempFile.renameTo(targetFile)) {
                // If rename fails, copy and delete
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            onProgress(bytesDownloaded, contentLength, 1f)
            Log.d(TAG, "Download completed successfully: ${targetFile.absolutePath} ($bytesDownloaded bytes)")
            Result.success(targetFile)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed or was canceled: ${e.message}", e)
            if (tempFile.exists()) {
                tempFile.delete()
            }
            Result.failure(e)
        } finally {
            try { inputStream?.close() } catch (_: Exception) {}
            try { outputStream?.close() } catch (_: Exception) {}
            connection?.disconnect()
        }
    }
}
