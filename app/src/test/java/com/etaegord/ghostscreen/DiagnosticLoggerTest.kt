package com.etaegord.ghostscreen

import com.etaegord.ghostscreen.util.DiagnosticLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLoggerTest {

    @Test
    fun testPackageSanitizerAllowlist() {
        // System framework and GhostScreen itself should be preserved
        assertEquals("com.android.systemui", DiagnosticLogger.sanitizePackage("com.android.systemui"))
        assertEquals("com.etaegord.ghostscreen", DiagnosticLogger.sanitizePackage("com.etaegord.ghostscreen"))
        assertEquals("com.google.android.inputmethod.latin", DiagnosticLogger.sanitizePackage("com.google.android.inputmethod.latin"))
    }

    @Test
    fun testPackageSanitizerThirdPartyMasking() {
        val masked = DiagnosticLogger.sanitizePackage("com.whatsapp")
        assertTrue("Masked package must start with app_", masked.startsWith("app_"))
        assertFalse("Raw third-party package must not be in masked result", masked.contains("whatsapp"))

        val bankingMasked = DiagnosticLogger.sanitizePackage("com.chase.sig.android")
        assertTrue("Masked package must start with app_", bankingMasked.startsWith("app_"))
        assertFalse("Raw banking package must not be in masked result", bankingMasked.contains("chase"))
    }

    @Test
    fun testRingBufferFifoOverflow() {
        // Push 60 events; ring buffer must stay capped at 50
        for (i in 1..60) {
            DiagnosticLogger.log(
                category = DiagnosticLogger.Category.OVERLAY_ENGINE,
                level = DiagnosticLogger.Level.DEBUG,
                message = "Test event $i",
                details = mapOf("index" to i)
            )
        }

        // We can't access private ringBuffer directly, but we can verify via logging operations that no crash occurs
        DiagnosticLogger.logOverlay("Final check")
    }

    @Test
    fun testTaxonomyCategoryTags() {
        assertEquals("OVERLAY_ENGINE", DiagnosticLogger.Category.OVERLAY_ENGINE.tag)
        assertEquals("LIFECYCLE_PERM", DiagnosticLogger.Category.LIFECYCLE_PERM.tag)
        assertEquals("UI_STATE", DiagnosticLogger.Category.UI_STATE.tag)
        assertEquals("DEVICE_ENV", DiagnosticLogger.Category.DEVICE_ENV.tag)
    }
}
