package com.etaegord.ghostscreen.util

/**
 * Maps the user-facing opacity slider (0–100) onto a "real" opacity that is
 * actually visible. GhostScreen's mask is invisible below ~37% raw alpha, so a
 * naive 1:1 slider feels dead for its first third.
 *
 * Desired behaviour:
 *  - 0%   -> off (0)
 *  - 1%   -> the visibility floor (~29% real), so the first notch already reads
 *  - 100% -> fully opaque (100% real)
 *  - everything between is a smooth, monotonically-increasing piecewise curve
 *
 * Anchors are tuned by hand; the curve is linear between them. The displayed
 * percentage the user sees is untouched — only the *effective* alpha changes.
 * ponytail: ceiling is a fixed lookup, upgrade path = expose anchors in prefs.
 */
object OpacityCurve {

    // (displayPct, realPct) anchors. display is clamped to 0..100 first.
    private val ANCHORS = arrayOf(
        intArrayOf(0, 0),
        intArrayOf(1, 29),
        intArrayOf(10, 34),
        intArrayOf(25, 45),
        intArrayOf(50, 62),
        intArrayOf(75, 80),
        intArrayOf(100, 100)
    )

    /** Convert the slider/display percentage into the real opacity percentage. */
    fun displayToRealPct(display: Int): Int {
        val d = display.coerceIn(0, 100)
        if (d == 0) return 0
        if (d >= 100) return 100

        // Find the anchor segment [a0, a1] that contains d.
        for (i in 1 until ANCHORS.size) {
            val a0 = ANCHORS[i - 1]
            val a1 = ANCHORS[i]
            if (d <= a1[0]) {
                val span = (a1[0] - a0[0]).coerceAtLeast(1)
                val t = (d - a0[0]).toFloat() / span
                return (a0[1] + (a1[1] - a0[1]) * t).toInt()
            }
        }
        return 100
    }

    /** Real opacity percentage (0–100) -> alpha (0–255). */
    fun realPctToAlpha(realPct: Int): Int =
        (realPct.coerceIn(0, 100) / 100f * 255f).toInt()
}
