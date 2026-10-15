package com.etaegord.ghostscreen.updater

import java.io.File

/**
 * Data model representing a release fetched from GitHub Releases API.
 */
data class UpdateInfo(
    val tagName: String,
    val versionName: String,
    val title: String,
    val changelog: String,
    val downloadUrl: String,
    val apkSize: Long,
    val publishedAt: String,
    val sha256: String? = null,
    val assetName: String = "",
    val versionCode: Long? = null,
    val sha256AssetUrl: String? = null
) {
    /**
     * Formatted file size string (e.g. "12.4 MB")
     */
    val formattedSize: String
        get() {
            if (apkSize <= 0) return "Unknown size"
            val mb = apkSize.toDouble() / (1024 * 1024)
            return if (mb >= 1.0) {
                String.format(java.util.Locale.US, "%.1f MB", mb)
            } else {
                val kb = apkSize.toDouble() / 1024
                String.format(java.util.Locale.US, "%.1f KB", kb)
            }
        }

    /**
     * Formatted publish date string (e.g. "Oct 15, 2026")
     */
    val formattedDate: String
        get() {
            if (publishedAt.isBlank()) return ""
            return try {
                val instant = java.time.Instant.parse(publishedAt)
                val formatter = java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy")
                    .withZone(java.time.ZoneId.systemDefault())
                formatter.format(instant)
            } catch (_: Exception) {
                try {
                    val rawDate = publishedAt.substringBefore("T")
                    val parts = rawDate.split("-")
                    if (parts.size == 3) {
                        val year = parts[0]
                        val month = parts[1].toIntOrNull() ?: 1
                        val day = parts[2].toIntOrNull() ?: 1
                        val monthName = java.text.DateFormatSymbols().shortMonths.getOrElse(month - 1) { "$month" }
                        "$monthName $day, $year"
                    } else rawDate
                } catch (_: Exception) {
                    publishedAt
                }
            }
        }
}

/**
 * Download progress data class.
 */
data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val progress: Float,
    val isCompleted: Boolean = false,
    val error: String? = null
)

/**
 * Represents the current state of the in-app update workflow.
 */
sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class UpdateAvailable(val updateInfo: UpdateInfo, val isManualCheck: Boolean = false) : UpdateState()
    data class UpToDate(val currentVersion: String, val isManualCheck: Boolean = false) : UpdateState()
    data class Downloading(
        val updateInfo: UpdateInfo,
        val progress: Float,
        val bytesDownloaded: Long,
        val totalBytes: Long
    ) : UpdateState()
    data class ReadyToInstall(val updateInfo: UpdateInfo, val apkFile: File) : UpdateState()
    data class Error(val message: String, val isManualCheck: Boolean = false) : UpdateState()
}
