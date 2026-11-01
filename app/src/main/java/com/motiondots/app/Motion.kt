package com.motiondots.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Turns the phone's sensors into the vehicle's acceleration, in the rider's own terms:
 * [forward] (speeding up +, braking −) and [right] (pushed by a left turn: the car accelerates
 * to the left, so right is −) in m/s². Works however the phone is held, because it uses the
 * horizontal plane (from gravity) and the way the rider faces (the way the screen faces away
 * from them, or the top of the phone when it lies flat).
 *
 * Fused sensors: linear acceleration (gravity already removed) and gravity. A slow-moving
 * baseline is subtracted so sensor bias and steady slopes don't push the dots off centre.
 */
class Motion(context: Context) : SensorEventListener {
    private val displays = context.getSystemService(android.hardware.display.DisplayManager::class.java)
    private var rotation = 0
    private var rotationCheckedAt = 0L
    private val sm = context.getSystemService(SensorManager::class.java)
    private val linear = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gravitySensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val gravity = floatArrayOf(0f, 0f, 9.81f)
    private var lastNanos = 0L
    private var baseF = 0.0
    private var baseR = 0.0

    /** Smoothed, de-biased vehicle acceleration, m/s². */
    @Volatile var forward = 0.0; private set
    @Volatile var right = 0.0; private set
    /** Recent amount of vehicle-like motion (for "hide when still"). */
    @Volatile var activity = 0.0; private set

    /** Seconds; higher = smoother and slower to follow. Set from the smoothing setting. */
    @Volatile var smoothSeconds = 0.25

    val available get() = linear != null || accel != null

    fun start() {
        lastNanos = 0L
        if (linear != null && gravitySensor != null) {
            sm.registerListener(this, gravitySensor, SensorManager.SENSOR_DELAY_GAME)
            sm.registerListener(this, linear, SensorManager.SENSOR_DELAY_GAME)
        } else if (accel != null) {
            sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() = sm.unregisterListener(this)

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GRAVITY -> { e.values.copyInto(gravity, endIndex = 3); return }
            Sensor.TYPE_LINEAR_ACCELERATION -> update(e.values[0], e.values[1], e.values[2], e.timestamp)
            Sensor.TYPE_ACCELEROMETER -> {
                // No fused sensors: split gravity off with a low-pass filter.
                for (i in 0..2) gravity[i] = 0.9f * gravity[i] + 0.1f * e.values[i]
                update(e.values[0] - gravity[0], e.values[1] - gravity[1], e.values[2] - gravity[2], e.timestamp)
            }
        }
    }

    private fun update(ax: Float, ay: Float, az: Float, nanos: Long) {
        val dt = if (lastNanos == 0L) 0.02 else ((nanos - lastNanos) / 1e9).coerceIn(0.001, 0.2)
        lastNanos = nanos
        // "Up", from gravity (the sensor reports the push against gravity, which points up).
        val gl = sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2]).toDouble()
        if (gl < 1.0) return
        val ux = gravity[0] / gl; val uy = gravity[1] / gl; val uz = gravity[2] / gl
        // The way the rider faces: out of the back of the phone (−Z) plus the top of the screen
        // as it's shown now (portrait or landscape), both flattened onto the horizontal plane.
        // Upright phone: −Z does it; flat phone: the screen's top does.
        if (nanos - rotationCheckedAt > 500_000_000L) {
            rotationCheckedAt = nanos
            rotation = displays?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation ?: 0
        }
        var fx: Double; var fy: Double; var fz = -1.0
        when (rotation) {
            android.view.Surface.ROTATION_90 -> { fx = 1.0; fy = 0.0 }
            android.view.Surface.ROTATION_180 -> { fx = 0.0; fy = -1.0 }
            android.view.Surface.ROTATION_270 -> { fx = -1.0; fy = 0.0 }
            else -> { fx = 0.0; fy = 1.0 }
        }
        val fu = fx * ux + fy * uy + fz * uz
        fx -= fu * ux; fy -= fu * uy; fz -= fu * uz
        val fl = sqrt(fx * fx + fy * fy + fz * fz)
        if (fl < 1e-3) return
        fx /= fl; fy /= fl; fz /= fl
        // Right = forward × up.
        val rx = fy * uz - fz * uy
        val ry = fz * ux - fx * uz
        val rz = fx * uy - fy * ux
        val rawF = ax * fx + ay * fy + az * fz
        val rawR = ax * rx + ay * ry + az * rz
        // Remove slow bias (about 6 s), then smooth.
        val b = 1 - exp(-dt / 6.0)
        baseF += (rawF - baseF) * b
        baseR += (rawR - baseR) * b
        val s = 1 - exp(-dt / smoothSeconds.coerceAtLeast(0.02))
        forward += (rawF - baseF - forward) * s
        right += (rawR - baseR - right) * s
        val mag = sqrt(forward * forward + right * right)
        activity += (mag - activity) * (1 - exp(-dt / 2.0))
    }
}
