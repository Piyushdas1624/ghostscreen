package com.etaegord.ghostscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Pure JVM unit tests for next-generation Privacy Suite algorithms:
 *  - 6-DoF Tilt Blackout hysteresis state logic
 *  - Jerk-vector (da/dt) Panic Double-Shake differentiation
 *  - AI Sensitive Field regex validators (Cards, OTPs, Balances)
 */
class PrivacySuiteLogicTest {

    // ── 1. Tilt Blackout Hysteresis ──────────────────────────────────
    @Test
    fun tiltHysteresis_triggersAtOrAboveThreshold() {
        val threshold = 40f
        val hysteresis = 10f
        val restoreLimit = threshold - hysteresis // 30f

        var isBlackout = false

        fun evaluateTilt(roll: Float) {
            if (!isBlackout && roll >= threshold) {
                isBlackout = true
            } else if (isBlackout && roll < restoreLimit) {
                isBlackout = false
            }
        }

        // Initially upright
        evaluateTilt(15f)
        assertFalse(isBlackout)

        // Tilting, nearing threshold
        evaluateTilt(38f)
        assertFalse(isBlackout)

        // Threshold crossed: shoulder-surfer peek detected!
        evaluateTilt(42f)
        assertTrue(isBlackout)

        // Slight recovery within deadband (35°): must STAY in blackout!
        evaluateTilt(35f)
        assertTrue("Deadband should prevent flutter at 35°", isBlackout)

        // Further recovery to 31°: still in deadband
        evaluateTilt(31f)
        assertTrue(isBlackout)

        // Phone brought upright below restore limit (28°): restores screen!
        evaluateTilt(28f)
        assertFalse("Should restore when below 30°", isBlackout)
    }

    // ── 2. Jerk Vector Differentiation ──────────────────────────────
    @Test
    fun jerkVector_calculatesCorrectMagnitude() {
        val lastX = 0f
        val lastY = 9.8f
        val lastZ = 0f

        // Rapid violent shake
        val currX = 5.0f
        val currY = 9.8f
        val currZ = 12.0f
        val dt = 0.05f // 50ms

        val jx = (currX - lastX) / dt // 100
        val jy = (currY - lastY) / dt // 0
        val jz = (currZ - lastZ) / dt // 240

        val jerkMag = sqrt((jx * jx + jy * jy + jz * jz).toDouble()).toFloat()

        assertEquals(260.0f, jerkMag, 0.01f)
        assertTrue("Violent shake exceeds 14 m/s³ threshold", jerkMag > 14f)
    }

    // ── 3. AI Sensitive Field Regex Pattern Matching ────────────────
    private val cardRegex = Regex("""\b(?:\d[ -]*?){13,19}\b""")
    private val otpRegex = Regex("""(?i)\b(?:otp|code|verification|pin)\b.{0,15}\b\d{4,8}\b""")
    private val balanceRegex = Regex("""(?i)(?:[$€£₹]|rs\.?|inr|usd)\s*[\d,]+(?:\.\d{1,2})?""")

    @Test
    fun aiSensitivePatterns_detectCreditCards() {
        assertTrue(cardRegex.containsMatchIn("Card: 4532 7512 8934 1029"))
        assertTrue(cardRegex.containsMatchIn("4111111111111111"))
        assertFalse(cardRegex.containsMatchIn("Short order #12345"))
    }

    @Test
    fun aiSensitivePatterns_detectOtps() {
        assertTrue(otpRegex.containsMatchIn("Your verification code is 482910"))
        assertTrue(otpRegex.containsMatchIn("OTP: 9182"))
        assertTrue(otpRegex.containsMatchIn("Security PIN 5521"))
        assertFalse(otpRegex.containsMatchIn("Meeting at 5:00 PM"))
    }

    @Test
    fun aiSensitivePatterns_detectBalances() {
        assertTrue(balanceRegex.containsMatchIn("Available Balance: $1,250.00"))
        assertTrue(balanceRegex.containsMatchIn("Total: ₹45,000"))
        assertTrue(balanceRegex.containsMatchIn("€89.50 remaining"))
        assertTrue(balanceRegex.containsMatchIn("Rs. 500"))
        assertTrue(balanceRegex.containsMatchIn("USD 1200"))
        assertFalse(balanceRegex.containsMatchIn("The price of freedom"))
    }
}
