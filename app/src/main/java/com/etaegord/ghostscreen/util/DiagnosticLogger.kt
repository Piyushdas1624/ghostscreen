package com.etaegord.ghostscreen.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import com.etaegord.ghostscreen.BuildConfig
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * DiagnosticLogger — In-memory ring-buffer logger and AI Bug Packet generator.
 *
 * Implements the GhostScreen Diagnostic Logging Architecture (see LOGGING.md):
 * - Thread-safe ring buffer capturing the last 50 state events.
 * - Strict privacy sanitization (no raw third-party package names, zero PII, zero keystrokes).
 * - Direct "One-Tap AI Bug Packet" generation formatted with an architectural .agents context header.
 */
object DiagnosticLogger {

    enum class Category(val tag: String) {
        OVERLAY_ENGINE("OVERLAY_ENGINE"),
        LIFECYCLE_PERM("LIFECYCLE_PERM"),
        UI_STATE("UI_STATE"),
        DEVICE_ENV("DEVICE_ENV")
    }

    enum class Level {
        DEBUG, INFO, WARN, ERROR
    }

    data class LogEvent(
        val timestamp: Long,
        val category: Category,
        val level: Level,
        val message: String,
        val details: Map<String, Any>? = null
    ) {
        fun format(dateFormat: SimpleDateFormat): String {
            val dateStr = dateFormat.format(Date(timestamp))
            val detailStr = if (!details.isNullOrEmpty()) {
                " | " + details.entries.joinToString(", ") { "${it.key}=${it.value}" }
            } else ""
            return "$dateStr [${category.tag}] [${level.name}] $message$detailStr"
        }
    }

    private const val MAX_RING_BUFFER_SIZE = 50
    private val ringBuffer = ArrayDeque<LogEvent>(MAX_RING_BUFFER_SIZE)
    private val lock = Any()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    // Allowed system packages that can appear in plain text
    private val SYSTEM_PACKAGE_ALLOWLIST = setOf(
        "android",
        "com.android.systemui",
        "com.google.android.inputmethod.latin",
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher3",
        "com.etaegord.ghostscreen"
    )

    fun log(category: Category, level: Level, message: String, details: Map<String, Any>? = null) {
        val event = LogEvent(
            timestamp = System.currentTimeMillis(),
            category = category,
            level = level,
            message = sanitizeText(message),
            details = details?.mapValues { (_, value) -> sanitizeValue(value) }
        )

        synchronized(lock) {
            if (ringBuffer.size >= MAX_RING_BUFFER_SIZE) {
                ringBuffer.pollFirst()
            }
            ringBuffer.addLast(event)
        }
    }

    fun logOverlay(message: String, details: Map<String, Any>? = null) {
        log(Category.OVERLAY_ENGINE, Level.INFO, message, details)
    }

    fun logLifecycle(message: String, details: Map<String, Any>? = null) {
        log(Category.LIFECYCLE_PERM, Level.INFO, message, details)
    }

    fun logUi(message: String, details: Map<String, Any>? = null) {
        log(Category.UI_STATE, Level.INFO, message, details)
    }

    fun logError(message: String, throwable: Throwable? = null) {
        val details = throwable?.let {
            mapOf("exception" to (it.javaClass.simpleName), "cause" to (it.message ?: "none"))
        }
        log(Category.OVERLAY_ENGINE, Level.ERROR, message, details)
    }

    /**
     * Sanitizes strings to prevent leaking user PII or sensitive package identifiers.
     */
    fun sanitizePackage(packageName: String): String {
        if (packageName.isBlank()) return "none"
        if (SYSTEM_PACKAGE_ALLOWLIST.contains(packageName.lowercase())) {
            return packageName
        }
        // Hash third-party packages to preserve correlation while hiding identities
        val hash = sha256Prefix(packageName, 6)
        return "app_$hash"
    }

    private fun sanitizeText(text: String): String {
        // Strip out email addresses or URLs if any
        return text.replace(Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,6}"), "[REDACTED_EMAIL]")
    }

    private fun sanitizeValue(value: Any): Any {
        return when (value) {
            is String -> sanitizeText(value)
            else -> value
        }
    }

    private fun sha256Prefix(input: String, length: Int): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }.take(length)
        } catch (_: Exception) {
            "masked"
        }
    }

    /**
     * Generates a complete, pre-formatted Markdown AI Bug Packet.
     */
    fun generateAiBugPacket(context: Context): String {
        val appContext = context.applicationContext
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm?.defaultDisplay?.getRealMetrics(metrics)

        val hasOverlayPermission = Settings.canDrawOverlays(appContext)
        val isHardwareBlurSupported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { wm?.isCrossWindowBlurEnabled ?: false } catch (_: Exception) { false }
        } else false

        val eventsCopy = synchronized(lock) {
            ringBuffer.toList()
        }

        return buildString {
            appendLine("<!-- GHOSTSCREEN_AI_BUG_PACKET_V1 -->")
            appendLine("### Architectural Index (.agents context header)")
            appendLine("- Overlay Engine: `app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenOverlay.kt`")
            appendLine("- Accessibility Interceptor: `app/src/main/java/com/etaegord/ghostscreen/service/GhostScreenAccessibilityService.kt`")
            appendLine("- Preferences & State: `app/src/main/java/com/etaegord/ghostscreen/data/PreferencesManager.kt`")
            appendLine("- Invariants & OEM Quirks: `DECISIONS.md`")
            appendLine()
            appendLine("### System Environment")
            appendLine("- GhostScreen Version: v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
            appendLine("- Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT})")
            appendLine("- Android OS: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("- Display: ${metrics.widthPixels}x${metrics.heightPixels} @ ${metrics.density}x (Density DPI: ${metrics.densityDpi})")
            appendLine("- Cross-Window Blur Supported: $isHardwareBlurSupported")
            appendLine("- Overlay Permission (SYSTEM_ALERT_WINDOW): $hasOverlayPermission")
            appendLine()
            appendLine("### Recent State Trace (Ring Buffer - Last ${eventsCopy.size} Events)")
            if (eventsCopy.isEmpty()) {
                appendLine("*(No state transitions recorded in current session)*")
            } else {
                appendLine("```text")
                eventsCopy.forEach { event ->
                    appendLine(event.format(dateFormat))
                }
                appendLine("```")
            }
            appendLine("<!-- END_GHOSTSCREEN_AI_BUG_PACKET -->")
        }
    }

    /**
     * Copies the AI Bug Packet directly to the Android Clipboard.
     */
    fun copyBugPacketToClipboard(context: Context): Boolean {
        return try {
            val packet = generateAiBugPacket(context)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("GhostScreen Diagnostic Report", packet)
            clipboard?.setPrimaryClip(clip)
            log(Category.UI_STATE, Level.INFO, "Diagnostic bug packet copied to clipboard")
            true
        } catch (e: Exception) {
            logError("Failed to copy diagnostic bug packet to clipboard", e)
            false
        }
    }
}
