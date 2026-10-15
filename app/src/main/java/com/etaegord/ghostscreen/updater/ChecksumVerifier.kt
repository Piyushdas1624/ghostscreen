package com.etaegord.ghostscreen.updater

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.regex.Pattern

/**
 * Utility for computing and verifying SHA-256 checksums of downloaded update files.
 */
object ChecksumVerifier {

    private const val TAG = "ChecksumVerifier"

    /**
     * Computes the SHA-256 hash of [file] and formats it as a lowercase 64-char hex string.
     */
    fun calculateSha256(file: File): String {
        if (!file.exists() || !file.isFile) {
            throw IllegalArgumentException("File does not exist or is not a regular file: ${file.absolutePath}")
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)

        FileInputStream(file).use { fis ->
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }

        val hashBytes = digest.digest()
        val sb = StringBuilder(hashBytes.size * 2)
        for (b in hashBytes) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    private fun safeLog(priority: Int, tag: String, msg: String, tr: Throwable? = null) {
        try {
            when (priority) {
                Log.DEBUG -> Log.d(tag, msg)
                Log.WARN -> Log.w(tag, msg)
                Log.ERROR -> if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
            }
        } catch (_: Exception) {}
    }

    /**
     * Verifies that [file]'s SHA-256 hash matches [expectedHash].
     * If [expectedHash] is null or blank, verification is considered skipped/passed with a warning.
     * If [strict] is true and [expectedHash] is null or blank, verification fails.
     */
    fun verifySha256(file: File, expectedHash: String?, strict: Boolean = false): Boolean {
        if (expectedHash.isNullOrBlank()) {
            if (strict) {
                safeLog(Log.WARN, TAG, "SHA-256 verification failed: expected hash is missing in strict mode")
                return false
            }
            safeLog(Log.DEBUG, TAG, "No SHA-256 checksum provided; skipping checksum verification")
            return true
        }

        return try {
            val cleanExpected = cleanHash(expectedHash)
            val actual = calculateSha256(file)
            val matches = actual.equals(cleanExpected, ignoreCase = true)
            if (!matches) {
                safeLog(Log.ERROR, TAG, "SHA-256 mismatch! Expected: $cleanExpected, Actual: $actual")
            } else {
                safeLog(Log.DEBUG, TAG, "SHA-256 verified successfully: $actual")
            }
            matches
        } catch (e: Exception) {
            safeLog(Log.ERROR, TAG, "Error computing SHA-256 for file: ${file.name}", e)
            false
        }
    }

    private val SHA256_HEX_REGEX = Pattern.compile("(?i)\\b([a-f0-9]{64})\\b")

    /**
     * Extracts a 64-hex SHA-256 hash from a checksum file content (e.g. *.sha256, checksums.txt).
     * If [assetName] is non-empty and multiple lines exist, finds the line matching [assetName].
     */
    fun extractSha256ForAsset(content: String, assetName: String = ""): String? {
        if (content.isBlank()) return null
        val lines = content.lines()

        if (assetName.isNotBlank() && lines.size > 1) {
            val matchingLine = lines.firstOrNull { it.contains(assetName, ignoreCase = true) }
            if (matchingLine != null) {
                val m = SHA256_HEX_REGEX.matcher(matchingLine)
                if (m.find()) return m.group(1)?.lowercase()
            }
        }

        // Fallback: search for any 64-hex string across all lines
        for (line in lines) {
            val m = SHA256_HEX_REGEX.matcher(line)
            if (m.find()) return m.group(1)?.lowercase()
        }

        return null
    }

    private fun cleanHash(raw: String): String {
        var str = raw.trim()
        if (str.startsWith("sha256:", ignoreCase = true) || str.startsWith("sha-256:", ignoreCase = true)) {
            str = str.substringAfter(":")
        }
        val matcher = SHA256_HEX_REGEX.matcher(str)
        if (matcher.find()) {
            return matcher.group(1)!!.lowercase()
        }
        return str.trim().lowercase()
    }
}
