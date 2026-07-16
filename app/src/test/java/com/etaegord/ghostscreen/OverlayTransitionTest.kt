package com.etaegord.ghostscreen

import com.etaegord.ghostscreen.util.OverlayTransition
import com.etaegord.ghostscreen.util.OverlayTransition.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GhostScreen must not flicker the mask when a user moves between a protected
 * app and transient system surfaces (notification shade / System UI, Gboard,
 * input methods). The decision for the *current* foreground package depends on
 * whether the previously-known app was protected.
 *
 * Rules:
 *  - If current is a transient surface (systemui/keyboard):
 *      keep whatever the last effective app wanted (STAY).
 *  - If current is a real app and protected: SHOW for it.
 *  - If current is a real app and NOT protected: HIDE.
 *  - Manual override wins over everything: SHOW.
 *  - Disarmed (not armed, not manual): HIDE.
 */
class OverlayTransitionTest {

    @Test
    fun manual_override_always_shows() {
        val d = OverlayTransition.decide(
            currentPackage = "com.whatever",
            isTransient = false,
            isProtected = false,
            lastEffectiveProtected = null,
            armed = true,
            manualOverride = true
        )
        assertEquals(Decision.SHOW, d)
    }

    @Test
    fun disarmed_hides_even_if_protected() {
        val d = OverlayTransition.decide(
            currentPackage = "com.bank",
            isTransient = false,
            isProtected = true,
            lastEffectiveProtected = "com.bank",
            armed = false,
            manualOverride = false
        )
        assertEquals(Decision.HIDE, d)
    }

    @Test
    fun protected_real_app_shows() {
        val d = OverlayTransition.decide(
            currentPackage = "com.bank",
            isTransient = false,
            isProtected = true,
            lastEffectiveProtected = null,
            armed = true,
            manualOverride = false
        )
        assertEquals(Decision.SHOW, d)
    }

    @Test
    fun unprotected_real_app_hides() {
        val d = OverlayTransition.decide(
            currentPackage = "com.calculator",
            isTransient = false,
            isProtected = false,
            lastEffectiveProtected = null,
            armed = true,
            manualOverride = false
        )
        assertEquals(Decision.HIDE, d)
    }

    @Test
    fun transient_surface_after_protected_app_stays() {
        // User pulls down the shade over WhatsApp -> mask must NOT flicker off.
        val d = OverlayTransition.decide(
            currentPackage = "com.android.systemui",
            isTransient = true,
            isProtected = false,
            lastEffectiveProtected = "com.whatsapp",
            armed = true,
            manualOverride = false
        )
        assertEquals(Decision.STAY, d)
    }

    @Test
    fun transient_surface_after_unprotected_app_stays_hidden() {
        val d = OverlayTransition.decide(
            currentPackage = "com.google.android.inputmethod.latin",
            isTransient = true,
            isProtected = false,
            lastEffectiveProtected = null,
            armed = true,
            manualOverride = false
        )
        assertEquals(Decision.HIDE, d)
    }

    @Test
    fun transient_with_no_history_and_armed_does_not_show() {
        // Boot or unknown state — never show a mask for an unknown keyboard event.
        val d = OverlayTransition.decide(
            currentPackage = "com.android.systemui",
            isTransient = true,
            isProtected = false,
            lastEffectiveProtected = null,
            armed = true,
            manualOverride = false
        )
        assertEquals(Decision.HIDE, d)
    }

    @Test
    fun transient_classification_by_package_name() {
        assertTrue(OverlayTransition.isTransient("com.android.systemui"))
        assertTrue(OverlayTransition.isTransient("com.miui.systemui"))
        assertTrue(OverlayTransition.isTransient("com.google.android.inputmethod.latin"))
        assertTrue(OverlayTransition.isTransient("com.samsung.android.honeyboard"))
        assertTrue(OverlayTransition.isTransient("android"))
        assertTrue(!OverlayTransition.isTransient("com.whatsapp"))
    }
}
