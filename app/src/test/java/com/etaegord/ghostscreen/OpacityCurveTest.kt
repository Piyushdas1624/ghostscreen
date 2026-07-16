package com.etaegord.ghostscreen

import com.etaegord.ghostscreen.util.OpacityCurve
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * GhostScreen shows NO visible mask from roughly 0–37% raw opacity (alpha too low).
 * To make the slider feel useful, we map the user-facing 0–100% scale onto a
 * "real" opacity range where:
 *   - 0%  -> off (0)
 *   - 1%  -> looks ~29% (the floor where a mask first becomes visible)
 *   - 100% -> fully opaque (255 alpha = 100%)
 * The mapping is piecewise-linear between known anchor points so it is smooth
 * and never produces a value below the floor once the slider leaves 0.
 */
class OpacityCurveTest {

    @Test
    fun zero_display_is_off() {
        assertEquals(0, OpacityCurve.displayToRealPct(0))
    }

    @Test
    fun one_percent_display_hits_visibility_floor_of_29() {
        // The whole point: 1% on the slider must already read as ~29% real.
        assertEquals(29, OpacityCurve.displayToRealPct(1))
    }

    @Test
    fun one_hundred_is_fully_opaque() {
        assertEquals(100, OpacityCurve.displayToRealPct(100))
    }

    @Test
    fun mapping_is_monotonically_increasing() {
        var prev = -1
        for (d in 0..100) {
            val real = OpacityCurve.displayToRealPct(d)
            assert(real >= prev) { "Not increasing at $d: $real < $prev" }
            prev = real
        }
    }

    @Test
    fun mid_value_is_between_floor_and_full() {
        val mid = OpacityCurve.displayToRealPct(50)
        assert(mid in 30..99) { "50% should map between floor and 100, got $mid" }
    }

    @Test
    fun clamps_out_of_range_input() {
        assertEquals(0, OpacityCurve.displayToRealPct(-5))
        assertEquals(100, OpacityCurve.displayToRealPct(150))
    }

    @Test
    fun real_pct_to_alpha_is_correct_at_extremes() {
        assertEquals(0, OpacityCurve.realPctToAlpha(0))
        assertEquals(255, OpacityCurve.realPctToAlpha(100))
    }
}
