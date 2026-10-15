package com.etaegord.ghostscreen.updater

import java.util.regex.Pattern

/**
 * Utility for semantic version comparison between local app version and GitHub releases.
 */
object VersionComparator {

    private val VERSION_TOKEN_REGEX = Pattern.compile("(\\d+|[a-zA-Z]+)")
    private val VERSION_CODE_REGEX = Pattern.compile(
        "(?:_c|\\+|\\(b?|build|code)[_\\-\\s:]*(\\d+)",
        Pattern.CASE_INSENSITIVE
    )

    /**
     * Extracts an optional versionCode or build number from a release tag, title, or asset name.
     */
    fun extractVersionCode(text: String): Long? {
        if (text.isBlank()) return null
        val matcher = VERSION_CODE_REGEX.matcher(text)
        if (matcher.find()) {
            return matcher.group(1)?.toLongOrNull()
        }
        return null
    }

    /**
     * Returns true if [remoteVersion] is strictly newer than [currentVersion].
     * Supports both semantic version name comparisons and Android [versionCode].
     */
    fun isNewer(
        remoteVersion: String,
        currentVersion: String,
        remoteVersionCode: Long? = null,
        currentVersionCode: Long = 0L
    ): Boolean {
        val comp = compare(remoteVersion, currentVersion)
        if (comp > 0) return true
        if (comp < 0) return false

        // Version names are equal: check versionCode if available
        val effectiveRemoteCode = remoteVersionCode ?: extractVersionCode(remoteVersion)
        if (effectiveRemoteCode != null && effectiveRemoteCode > 0 && currentVersionCode > 0) {
            return effectiveRemoteCode > currentVersionCode
        }

        return false
    }

    /**
     * Compares two version strings.
     * Returns:
     *   > 0 if v1 > v2
     *   < 0 if v1 < v2
     *   0 if v1 == v2
     */
    fun compare(v1: String, v2: String): Int {
        val clean1 = cleanVersion(v1)
        val clean2 = cleanVersion(v2)

        val parts1 = parseComponents(clean1)
        val parts2 = parseComponents(clean2)

        val maxLength = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLength) {
            val part1 = parts1.getOrElse(i) { Component.Number(0) }
            val part2 = parts2.getOrElse(i) { Component.Number(0) }

            val comp = part1.compareTo(part2)
            if (comp != 0) {
                return comp
            }
        }

        return 0
    }

    private fun cleanVersion(raw: String): String {
        var str = raw.trim()
        if (str.startsWith("v", ignoreCase = true)) {
            str = str.substring(1)
        }
        return str.trim()
    }

    private sealed class Component : Comparable<Component> {
        data class Number(val value: Long) : Component() {
            override fun compareTo(other: Component): Int = when (other) {
                is Number -> value.compareTo(other.value)
                is Text -> 1 // Numbers have higher precedence than prerelease text
            }
        }

        data class Text(val value: String) : Component() {
            override fun compareTo(other: Component): Int = when (other) {
                is Number -> -1 // Text has lower precedence than numbers
                is Text -> value.compareTo(other.value, ignoreCase = true)
            }
        }
    }

    private fun parseComponents(versionStr: String): List<Component> {
        val result = mutableListOf<Component>()
        // Split on standard delimiters (dots, dashes, underscores, plus)
        val tokens = versionStr.split(Regex("[.\\-_+]"))
        for (token in tokens) {
            val trimmed = token.trim()
            if (trimmed.isEmpty()) continue

            // Check if token has subparts like "b2" or "rc1"
            val matcher = VERSION_TOKEN_REGEX.matcher(trimmed)
            while (matcher.find()) {
                val match = matcher.group()
                val num = match.toLongOrNull()
                if (num != null) {
                    result.add(Component.Number(num))
                } else {
                    result.add(Component.Text(match))
                }
            }
        }
        return result
    }
}
