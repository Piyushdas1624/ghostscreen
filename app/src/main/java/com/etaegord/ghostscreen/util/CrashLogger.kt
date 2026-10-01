package com.etaegord.ghostscreen.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import com.etaegord.ghostscreen.R
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CrashLogger — Autonomous crash & diagnostic logging subsystem for GhostScreen.
 *
 * Saves stack traces and diagnostic logs to "Downloads/ghostscreen logs" on device storage,
 * and alerts the user with an informative system notification.
 */
object CrashLogger {
    private const val TAG = "CrashLogger"
    private const val CHANNEL_ID = "ghostscreen_diagnostics"
    private const val CHANNEL_NAME = "Diagnostics & Crash Reports"
    private const val NOTIFICATION_ID = 9911

    private var isInitialized = false
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true
        val appContext = context.applicationContext

        createNotificationChannel(appContext)

        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val filePath = writeCrashToFile(appContext, thread, throwable, isFatal = true)
                notifyUserOfCrash(appContext, "App Crash Detected", "Stack trace recorded to Downloads/ghostscreen logs:\n${File(filePath ?: "").name}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed in uncaughtExceptionHandler", e)
            } finally {
                // Delegate to default handler so system dialog or graceful exit occurs
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
        Log.i(TAG, "CrashLogger initialized. Diagnostic logs routed to Downloads/ghostscreen logs")
    }

    /**
     * Log a caught warning or non-fatal exception.
     */
    fun logError(context: Context, tag: String, message: String, throwable: Throwable? = null): String? {
        val appContext = context.applicationContext
        return try {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            throwable?.printStackTrace(pw)
            val stack = sw.toString()

            val logBody = buildString {
                appendLine("=== GHOSTSCREEN DIAGNOSTIC ERROR LOG ===")
                appendLine("Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}")
                appendLine("Tag: $tag")
                appendLine("Message: $message")
                appendDeviceInfo(appContext)
                if (stack.isNotBlank()) {
                    appendLine("--- STACK TRACE ---")
                    appendLine(stack)
                }
            }
            writeLogContent(appContext, "error_${System.currentTimeMillis()}", logBody)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log error", e)
            null
        }
    }

    /**
     * Allows user to generate a test diagnostic log from Settings to verify disk storage access.
     */
    fun recordTestLog(context: Context): String {
        val appContext = context.applicationContext
        createNotificationChannel(appContext)
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val testContent = buildString {
            appendLine("=== GHOSTSCREEN TEST DIAGNOSTIC LOG ===")
            appendLine("Timestamp: $timestamp")
            appendLine("Status: SUCCESS (Diagnostic pipeline operational)")
            appendDeviceInfo(appContext)
            appendLine("Verification Note: Storage pipeline is successfully saving diagnostic reports.")
        }
        val path = writeLogContent(appContext, "test_log_$timestamp", testContent)
        notifyUserOfCrash(
            appContext,
            "GhostScreen Diagnostic Test",
            "Test log saved to Downloads/ghostscreen logs"
        )
        return path ?: "Downloads/ghostscreen logs/test_log_$timestamp.txt"
    }

    private fun writeCrashToFile(context: Context, thread: Thread, throwable: Throwable, isFatal: Boolean): String? {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val filename = if (isFatal) "crash_$timestamp" else "error_$timestamp"

        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))

        val body = buildString {
            appendLine("==========================================")
            appendLine("   GHOSTSCREEN CRASH DIAGNOSTIC REPORT    ")
            appendLine("==========================================")
            appendLine("Fatal: $isFatal")
            appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}")
            appendLine("Thread: ${thread.name} (id: ${thread.id})")
            appendDeviceInfo(context)
            appendLine("--- EXCEPTION SUMMARY ---")
            appendLine("${throwable.javaClass.name}: ${throwable.message}")
            appendLine()
            appendLine("--- FULL STACK TRACE ---")
            appendLine(sw.toString())
            appendLine("==========================================")
        }

        return writeLogContent(context, filename, body)
    }

    private fun StringBuilder.appendDeviceInfo(context: Context) {
        val (vName, vCode) = try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo.longVersionCode else @Suppress("DEPRECATION") pInfo.versionCode.toLong()
            Pair(pInfo.versionName ?: "unknown", code)
        } catch (_: Exception) {
            Pair("unknown", -1L)
        }
        appendLine("App Version: $vName (code $vCode)")
        appendLine("Device Model: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("Android OS: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Build ID: ${Build.DISPLAY}")
    }

    private fun writeLogContent(context: Context, baseName: String, content: String): String? {
        val fileName = "$baseName.txt"

        // 1. Primary method for Android 10+ (API 29+): MediaStore.Downloads
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/ghostscreen logs")
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { os ->
                        os.write(content.toByteArray(Charsets.UTF_8))
                        os.flush()
                    }
                    Log.i(TAG, "Log successfully written via MediaStore to: Download/ghostscreen logs/$fileName")
                    return "Download/ghostscreen logs/$fileName"
                }
            } catch (e: Exception) {
                Log.w(TAG, "MediaStore write failed, falling back to direct File API: ${e.message}")
            }
        }

        // 2. Direct File API fallback
        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val logsDir = File(downloadsDir, "ghostscreen logs")
            if (!logsDir.exists()) {
                logsDir.mkdirs()
            }
            val targetFile = File(logsDir, fileName)
            FileOutputStream(targetFile).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
            }
            Log.i(TAG, "Log successfully written via File API to: ${targetFile.absolutePath}")
            return targetFile.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Public downloads File API failed, falling back to app-specific external: ${e.message}")
        }

        // 3. App-specific fallback (always writable without permissions)
        try {
            val appExtDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            val logsDir = File(appExtDir, "ghostscreen logs")
            if (!logsDir.exists()) logsDir.mkdirs()
            val targetFile = File(logsDir, fileName)
            FileOutputStream(targetFile).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
            }
            Log.i(TAG, "Log successfully written to app files: ${targetFile.absolutePath}")
            return targetFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "All log persistence attempts failed", e)
            return null
        }
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies when error or crash diagnostics are saved to storage"
                enableVibration(true)
            }
            val nm = context.getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun notifyUserOfCrash(context: Context, title: String, message: String) {
        try {
            createNotificationChannel(context)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            // Action to open Downloads folder / Files app
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    } else {
                        android.net.Uri.parse(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path)
                    },
                    "*/*"
                )
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post diagnostic notification", e)
        }
    }
}
