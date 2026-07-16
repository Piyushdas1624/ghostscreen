package com.etaegord.ghostscreen.util

/**
 * Pure rules for reconciling GhostScreen's two independent protection booleans:
 *   - Smart (armed)  : automatic, masks only the apps the user selected
 *   - Manual/Instant : global, masks the whole screen on demand
 *
 * The two modes can legitimately coexist (Manual wins the overlay decision, see
 * [OverlayTransition.decide]), but the Home "Arm" button must never silently
 * leave Manual on — that is the root cause of "Armed behaves like Manual Mask":
 * a user who had Manual on (via the Tile/Shortcut) then taps Arm sees the whole
 * screen masked and believes Smart is masking globally.
 *
 * Decision (locked by product spec):
 *   - Turning Smart ON  -> clears Manual. Smart becomes the single active mode.
 *   - Turning Manual ON -> does NOT disarm Smart. Manual is an ad-hoc override
 *     that should not disturb the user's standing Smart configuration.
 *
 * Extracted as a pure object so the rule is unit-testable on the JVM and reused
 * by Home / Tile / Onboarding without duplication.
 */
object ProtectionModes {

    /**
     * The new Manual state to apply after [newArmed] is committed.
     * - Arming ON forces Manual off (Smart becomes the single active mode).
     * - Disarming preserves [currentManual] — the user may still want the Tile on.
     */
    fun manualAfterArm(newArmed: Boolean, currentManual: Boolean): Boolean =
        if (newArmed) false else currentManual

    /**
     * The new Smart state to apply after [newManual] is committed.
     * Turning Instant/Manual on never disarms Smart.
     */
    fun armedAfterManual(newManual: Boolean, currentArmed: Boolean): Boolean = currentArmed
}
