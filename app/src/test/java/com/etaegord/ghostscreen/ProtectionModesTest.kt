package com.etaegord.ghostscreen

import com.etaegord.ghostscreen.util.ProtectionModes
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * GhostScreen has two independent protection booleans — Smart (armed) and
 * Manual/Instant. The Home "Arm" button must never leave Manual silently on,
 * otherwise a user who had the Tile active sees the whole screen masked and
 * believes Smart is masking globally ("Armed behaves like Manual Mask").
 *
 * Locked rule:
 *  - Arming ON  -> clears Manual.
 *  - Disarming  -> preserves Manual (Tile may still be wanted).
 *  - Turning Manual ON -> never disarms Smart (Manual is an ad-hoc override).
 */
class ProtectionModesTest {

    @Test
    fun arming_on_clears_manual_when_manual_was_on() {
        assertEquals(false, ProtectionModes.manualAfterArm(newArmed = true, currentManual = true))
    }

    @Test
    fun arming_on_keeps_manual_off_when_already_off() {
        assertEquals(false, ProtectionModes.manualAfterArm(newArmed = true, currentManual = false))
    }

    @Test
    fun disarming_preserves_manual_on() {
        // Disarming must NOT turn the Tile off — the user may still want Manual.
        assertEquals(true, ProtectionModes.manualAfterArm(newArmed = false, currentManual = true))
    }

    @Test
    fun disarming_preserves_manual_off() {
        assertEquals(false, ProtectionModes.manualAfterArm(newArmed = false, currentManual = false))
    }

    @Test
    fun turning_manual_on_never_disarms_smart() {
        // Manual is an ad-hoc override; it must not disturb the standing Smart config.
        assertEquals(true, ProtectionModes.armedAfterManual(newManual = true, currentArmed = true))
        assertEquals(false, ProtectionModes.armedAfterManual(newManual = true, currentArmed = false))
    }

    @Test
    fun turning_manual_off_never_changes_smart() {
        assertEquals(true, ProtectionModes.armedAfterManual(newManual = false, currentArmed = true))
        assertEquals(false, ProtectionModes.armedAfterManual(newManual = false, currentArmed = false))
    }
}
