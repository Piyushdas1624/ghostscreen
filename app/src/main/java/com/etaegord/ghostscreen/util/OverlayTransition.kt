package com.etaegord.ghostscreen.util

/**
 * Pure decision logic for whether the privacy mask should be shown, hidden, or
 * left untouched for a given foreground-app transition. Extracted from the
 * accessibility service so it can be unit-tested without Android.
 *
 * The key fix this encodes: when the foreground is a *transient* surface
 * (System UI shade, IME/keyboard), the mask must inherit the protection state
 * of the last real app the user was in — never flicker off and back on.
 */
object OverlayTransition {

    enum class Decision { SHOW, HIDE, STAY }

    fun decide(
        currentPackage: String,
        isTransient: Boolean,
        isProtected: Boolean,
        lastEffectiveProtected: String?,
        armed: Boolean,
        manualOverride: Boolean
    ): Decision {
        // 1) Manual override wins over everything.
        if (manualOverride) return Decision.SHOW
        // 2) Fully disarmed -> never show.
        if (!armed) return Decision.HIDE

        // 3) Transient surface (shade / keyboard): inherit the last real app.
        if (isTransient) {
            // If we were protecting a real app, keep the mask up (no flicker).
            // Otherwise ensure it's down.
            return if (lastEffectiveProtected != null) Decision.STAY else Decision.HIDE
        }

        // 4) Real app: show iff protected.
        return if (isProtected) Decision.SHOW else Decision.HIDE
    }

    /** True for System UI / notification shade / IME (keyboard) packages. */
    fun isTransient(packageName: String): Boolean {
        val p = packageName.lowercase()
        return p == "android" ||
            p.contains("systemui") ||
            p.contains("inputmethod") ||
            p.contains("keyboard") ||
            p.contains("honeyboard") ||
            p.contains("swiftkey") ||
            p.contains("miinput") ||
            p.contains("baidu.input") ||
            p.contains("gokeyboard") ||
            p.contains("aitype") ||
            p.contains("ai.type") ||
            p.contains("gboard") ||
            p.contains("ime.")
    }
}
