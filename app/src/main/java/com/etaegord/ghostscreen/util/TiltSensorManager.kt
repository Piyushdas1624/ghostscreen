package com.etaegord.ghostscreen.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.abs

/**
 * TiltSensorManager — 6-DoF shoulder-surfer tilt blackout detector.
 *
 * Uses Sensor.TYPE_GAME_ROTATION_VECTOR (gyroscope + accelerometer sensor fusion,
 * uncorrupted by magnetic anomalies or vehicle turns).
 *
 * Enforces a 10° hysteresis deadband to prevent flickering near the threshold:
 *  - Triggers blackout when roll >= threshold (e.g. 40°)
 *  - Restores normal screen when roll < threshold - hysteresis (e.g. 30°)
 */
class TiltSensorManager(
    private val context: Context,
    private val onTiltChanged: (isBlackout: Boolean) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private var rotationSensor: Sensor? = null
    private var isListening = false
    private var isCurrentlyBlackout = false

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    var thresholdDegrees: Int = 40
    var hysteresisDegrees: Int = 10
    var isEnabled: Boolean = false
        set(value) {
            field = value
            if (value && !isListening) {
                startListening()
            } else if (!value && isListening) {
                stopListening()
            }
        }

    init {
        // Prefer TYPE_GAME_ROTATION_VECTOR for pure gyro-accel fusion with centrifugal rejection
        rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    fun startListening() {
        if (isListening || !isEnabled || sensorManager == null || rotationSensor == null) return
        try {
            sensorManager.registerListener(
                this,
                rotationSensor,
                SensorManager.SENSOR_DELAY_UI
            )
            isListening = true
            Log.d(TAG, "TiltSensorManager started with sensor: ${rotationSensor?.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start tilt sensor", e)
        }
    }

    fun stopListening() {
        if (!isListening || sensorManager == null) return
        try {
            sensorManager.unregisterListener(this)
            isListening = false
            if (isCurrentlyBlackout) {
                isCurrentlyBlackout = false
                onTiltChanged(false)
            }
            Log.d(TAG, "TiltSensorManager stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop tilt sensor", e)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isEnabled) {
            if (isCurrentlyBlackout) {
                isCurrentlyBlackout = false
                onTiltChanged(false)
            }
            return
        }

        val rollDegrees = when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                // World up (0, 0, 1) in device coordinates: R^T * [0, 0, 1]^T = (R[6], R[7], R[8])
                val gx = rotationMatrix[6]
                val gy = rotationMatrix[7]
                val gz = rotationMatrix[8]
                abs(Math.toDegrees(kotlin.math.atan2(gx.toDouble(), kotlin.math.sqrt((gy * gy + gz * gz).toDouble())))).toFloat()
            }
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]
                val rollRad = kotlin.math.atan2(ax.toDouble(), kotlin.math.sqrt((ay * ay + az * az).toDouble()))
                abs(Math.toDegrees(rollRad)).toFloat()
            }
            else -> return
        }

        val triggerLimit = thresholdDegrees.toFloat()
        val restoreLimit = (thresholdDegrees - hysteresisDegrees).coerceAtLeast(10).toFloat()

        if (!isCurrentlyBlackout && rollDegrees >= triggerLimit) {
            isCurrentlyBlackout = true
            Log.d(TAG, "Tilt blackout TRIGGERED: roll=$rollDegrees° (limit=$triggerLimit°)")
            onTiltChanged(true)
        } else if (isCurrentlyBlackout && rollDegrees < restoreLimit) {
            isCurrentlyBlackout = false
            Log.d(TAG, "Tilt blackout RESTORED: roll=$rollDegrees° (restore=$restoreLimit°)")
            onTiltChanged(false)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        private const val TAG = "TiltSensorManager"
    }
}
