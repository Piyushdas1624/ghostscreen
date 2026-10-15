package com.etaegord.ghostscreen.updater

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

/**
 * Service to query GitHub REST API for the latest release.
 */
object GitHubReleaseChecker {

    private const val TAG = "GitHubReleaseChecker"
    const val DEFAULT_OWNER = "Piyushdas1624"
    const val DEFAULT_REPO = "ghostscreen"

    private val SHA256_BODY_REGEX = Pattern.compile(
        "(?i)(?:sha-?256|checksum)[\"':`*=\\s]+([a-fA-F0-9]{64})"
    )
    private val STANDALONE_SHA256_REGEX = Pattern.compile(
        "\\b([a-fA-F0-9]{64})\\b"
    )

    /**
     * Fetches the latest release info from GitHub API.
     */
    suspend fun fetchLatestRelease(
        owner: String = DEFAULT_OWNER,
        repo: String = DEFAULT_REPO,
        customUrl: String? = null,
        context: android.content.Context? = null,
        authToken: String? = null
    ): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        if (customUrl == "mock" || customUrl == "test") {
            val sourceApk = context?.let { java.io.File(it.applicationInfo.sourceDir) }
            val realSha256 = if (sourceApk != null && sourceApk.exists()) {
                ChecksumVerifier.calculateSha256(sourceApk)
            } else {
                "4b227777d4dd1fc61c6f884f48641d02b4d121d3fd328cb08b5531fcacdabf8a"
            }
            val apkSize = if (sourceApk != null && sourceApk.exists()) sourceApk.length() else 18456123L

            val mockJson = """
            {
              "tag_name": "v1.1.0",
              "name": "GhostScreen v1.1.0 - Next-Gen Privacy Shield",
              "body": "## What's New in v1.1.0\n- **Tilt Sensor Shield**: 6-DoF sensor fusion automatic OLED blackout\n- **In-App Updater**: Automated GitHub releases updater with SHA-256 verification\n- **Panic Double-Shake**: Rapid shake detector\n- **Wi-Fi Only Toggle**: Save mobile data on large downloads\n\n**Checksums**:\nSHA-256: $realSha256",
              "published_at": "2026-10-15T18:00:00Z",
              "assets": [
                {
                  "name": "GhostScreen_v1.1.0_c10100_release.apk",
                  "size": $apkSize,
                  "browser_download_url": "mock://ghostscreen/v1.1.0/GhostScreen_v1.1.0.apk"
                }
              ]
            }
            """.trimIndent()
            return@withContext Result.success(parseReleaseJson(mockJson))
        }

        val targetUrl = customUrl ?: "https://api.github.com/repos/$owner/$repo/releases/latest"
        var connection: HttpURLConnection? = null
        try {
            val url = URL(targetUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "GhostScreen-Android-App")
                if (!authToken.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $authToken")
                }
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                return@withContext Result.failure(
                    IllegalStateException("No releases found on repository $owner/$repo (or repo is private)")
                )
            } else if (responseCode == 403) {
                return@withContext Result.failure(
                    IllegalStateException("GitHub API rate limit exceeded. Please try again later.")
                )
            } else if (responseCode !in 200..299) {
                return@withContext Result.failure(
                    IllegalStateException("GitHub API request failed with HTTP $responseCode")
                )
            }

            val responseBody = connection.inputStream.bufferedReader().use(BufferedReader::readText)
            var updateInfo = parseReleaseJson(responseBody)

            // If SHA-256 hash was not in release body, but a checksum asset exists, fetch it
            if (updateInfo.sha256 == null && updateInfo.sha256AssetUrl != null) {
                val fetchedHash = fetchChecksumFromUrl(updateInfo.sha256AssetUrl!!, updateInfo.assetName, authToken)
                if (fetchedHash != null) {
                    updateInfo = updateInfo.copy(sha256 = fetchedHash)
                }
            }

            Result.success(updateInfo)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch GitHub release from $targetUrl", e)
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Downloads and parses a remote checksum file (e.g. .sha256 or checksums.txt)
     */
    suspend fun fetchChecksumFromUrl(
        assetUrl: String,
        assetName: String = "",
        authToken: String? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL(assetUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("User-Agent", "GhostScreen-Android-App")
                if (!authToken.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $authToken")
                }
            }
            if (conn.responseCode in 200..299) {
                val content = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                ChecksumVerifier.extractSha256ForAsset(content, assetName)
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch checksum from asset URL: $assetUrl", e)
            null
        }
    }

    /**
     * Parses the GitHub release JSON payload.
     * Exposed as a pure function for reliable unit testing.
     */
    fun parseReleaseJson(jsonStr: String): UpdateInfo {
        val json = JSONObject(jsonStr)

        val tagName = json.optString("tag_name", "").trim()
        val title = json.optString("name", tagName)
        val body = json.optString("body", "No changelog provided.")
        val publishedAt = json.optString("published_at", "")

        val versionName = if (tagName.startsWith("v", ignoreCase = true)) {
            tagName.substring(1)
        } else {
            tagName
        }

        // Parse assets array to locate the APK and checksum assets
        val assets = json.optJSONArray("assets")
        var downloadUrl = ""
        var apkSize = 0L
        var assetName = ""
        var sha256AssetUrl: String? = null

        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                val url = asset.optString("browser_download_url", "")
                val size = asset.optLong("size", 0L)

                if (name.endsWith(".apk", ignoreCase = true)) {
                    downloadUrl = url
                    apkSize = size
                    assetName = name
                } else if (name.endsWith(".sha256", ignoreCase = true) ||
                    name.endsWith(".sha256sum", ignoreCase = true) ||
                    name.equals("checksums.txt", ignoreCase = true) ||
                    name.contains("sha256", ignoreCase = true)) {
                    sha256AssetUrl = url
                }
            }
        }

        // If no asset URL was found in assets, fallback to HTML url
        if (downloadUrl.isEmpty()) {
            val htmlUrl = json.optString("html_url", "")
            downloadUrl = htmlUrl
        }

        // Extract SHA-256 hash from body:
        // 1. Try matching "SHA256: <64-hex>" in the release body
        var sha256: String? = null
        val bodyMatcher = SHA256_BODY_REGEX.matcher(body)
        if (bodyMatcher.find()) {
            sha256 = bodyMatcher.group(1)?.lowercase()
        } else {
            // Check if there's any standalone 64-char hex in the body
            val standaloneMatcher = STANDALONE_SHA256_REGEX.matcher(body)
            if (standaloneMatcher.find()) {
                sha256 = standaloneMatcher.group(1)?.lowercase()
            }
        }

        // Extract versionCode from assetName, tag, or title
        val versionCode = VersionComparator.extractVersionCode(assetName)
            ?: VersionComparator.extractVersionCode(tagName)
            ?: VersionComparator.extractVersionCode(title)

        return UpdateInfo(
            tagName = tagName,
            versionName = versionName,
            title = title,
            changelog = body,
            downloadUrl = downloadUrl,
            apkSize = apkSize,
            publishedAt = publishedAt,
            sha256 = sha256,
            assetName = assetName,
            versionCode = versionCode,
            sha256AssetUrl = sha256AssetUrl
        )
    }
}
