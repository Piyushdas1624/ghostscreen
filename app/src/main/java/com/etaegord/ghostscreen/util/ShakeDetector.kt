package com.etaegord.ghostscreen.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlin.math.sqrt

/**
 * ShakeDetector — Panic Double-Shake emergency hardening subsystem.
 *
 * Employs jerk-vector differentiation (da/dt) to detect intentional, violent double-shakes
 * while cleanly rejecting natural walking, running, or driving vibrations.
 */
class ShakeDetector(
    private val context: Context,
    private val onPanicTriggered: () -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var isListening = false
    var isEnabled: Boolean = false
        set(value) {
            field = value
            if (value && !isListening) {
                startListening()
            } else if (!value && isListening) {
                stopListening()
            }
        }

    // Jerk threshold in m/s³ (30 - 50 is ideal for deliberate double-shakes)
    var jerkThreshold: Float = 35.0f

    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var lastTimestampNs: Long = 0
    private var lastShakeTimeMs: Long = 0

    fun startListening() {
        if (isListening || !isEnabled || sensorManager == null || accelSensor == null) return
        try {
            sensorManager.registerListener(
                this,
                accelSensor,
                SensorManager.SENSOR_DELAY_GAME
            )
            isListening = true
            lastTimestampNs = 0
            Log.d(TAG, "ShakeDetector started listening")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register ShakeDetector", e)
        }
    }

    fun stopListening() {
        if (!isListening || sensorManager == null) return
        try {
            sensorManager.unregisterListener(this)
            isListening = false
            Log.d(TAG, "ShakeDetector stopped listening")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister ShakeDetector", e)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isEnabled || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val nowNs = event.timestamp
        if (lastTimestampNs == 0L) {
            lastTimestampNs = nowNs
            lastX = event.values[0]
            lastY = event.values[1]
            lastZ = event.values[2]
            return
        }

        val dtSec = (nowNs - lastTimestampNs) / 1_000_000_000f
        if (dtSec <= 0.001f) return // Guard against division by zero

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // Calculate Jerk Vector components: da / dt
        val jx = (x - lastX) / dtSec
        val jy = (y - lastY) / dtSec
        val jz = (z - lastZ) / dtSec
        val jerkMagnitude = sqrt((jx * jx + jy * jy + jz * jz).toDouble()).toFloat()

        lastX = x
        lastY = y
        lastZ = z
        lastTimestampNs = nowNs

        val nowMs = System.currentTimeMillis()
        if (jerkMagnitude > jerkThreshold) {
            val deltaMs = nowMs - lastShakeTimeMs
            if (deltaMs in 180..750) {
                // Detected 2nd shake pulse within valid double-shake window
                Log.w(TAG, "PANIC DOUBLE-SHAKE CONFIRMED! Jerk: $jerkMagnitude, Delta: ${deltaMs}ms")
                lastShakeTimeMs = 0
                executeEmergencyHardening()
                onPanicTriggered()
            } else if (deltaMs > 750) {
                // First shake pulse of a potential pair
                lastShakeTimeMs = nowMs
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun executeEmergencyHardening() {
        // 1. Triple urgent haptic pulse
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 80, 50, 80, 50, 120), -1))
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 80, 50, 80, 50, 120), -1))
            }
        } catch (_: Exception) {}

        // 2. Mute media volume to prevent audio peeking/embarrassment
        try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audio?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        } catch (_: Exception) {}

        // 3. Clear clipboard to purge sensitive copied passwords/OTPs
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    clipboard.clearPrimaryClip()
                } else {
                    clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
        } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "ShakeDetector"
    }
}
