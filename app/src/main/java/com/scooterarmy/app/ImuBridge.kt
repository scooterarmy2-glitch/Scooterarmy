package com.scooterarmy.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Between-fix speed bridging, done properly.
 *
 * GPS fixes arrive ~once a second. Between fixes the display would otherwise
 * freeze; the old web app "fixed" that with trend prediction, which kept
 * showing 8–9 mph after the bike had stopped. This bridge is different:
 *
 *  - It integrates REAL measured acceleration (linear-acceleration sensor,
 *    gravity already removed), projected onto the direction of travel using
 *    the rotation vector (which itself fuses accel + gyro + magnetometer).
 *  - It only runs while the GPS says the vehicle is already moving
 *    (last GPS speed >= 1 m/s). Stopped means stopped: the display is
 *    exactly the GPS speed, never a prediction.
 *  - The integrated delta is clamped (default ±2.5 m/s, adjustable in
 *    Settings) and dies if the GPS fix goes stale (> 2 s) — the display
 *    then holds the last true GPS speed.
 *  - EVERY accepted GPS fix snaps the display back to the true GPS speed
 *    and zeroes the integrator. GPS is truth; the IMU only interpolates.
 *
 * Degrades gracefully: if the phone has no linear-acceleration or rotation
 * sensor, the bridge stays off and the display is pure GPS (Build #48
 * behaviour).
 *
 * Responsiveness is settings-driven (see AppSettings): deadband, filter
 * alpha and clamp come from `paramsProvider` on every sensor event, so
 * changing Speed Response in Settings applies live with no restart. The
 * hard rules never change whatever the settings say: stopped means
 * stopped, a stale fix holds GPS speed, and every accepted fix snaps the
 * display to truth and zeroes the integrator.
 */
class ImuBridge(context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linearAccel: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val rotationVec: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    val available: Boolean get() = linearAccel != null && rotationVec != null

    /** Settings-driven parameters. Null = the original bike-tuned defaults. */
    var paramsProvider: (() -> BridgeParams)? = null

    private fun params(): BridgeParams =
        paramsProvider?.invoke() ?: BridgeParams(true, 0.30, 0.25, 2.5)

    /** Integrated speed delta (m/s) since the last GPS fix. */
    var deltaMps: Double = 0.0
        private set

    private var lastBearingRad: Double? = null
    private var rotationMatrix = FloatArray(9)
    private var haveRotation = false
    private var filteredParallel = 0.0
    private var lastAccelEventNs: Long = 0L
    private var running = false

    fun setBearingDeg(bearingDeg: Float?) {
        if (bearingDeg != null) lastBearingRad = Math.toRadians(bearingDeg.toDouble())
    }

    /** Call on every accepted GPS fix: truth wins, integrator resets. */
    fun onGpsFix() {
        deltaMps = 0.0
        filteredParallel = 0.0
        lastAccelEventNs = 0L
    }

    fun start() {
        if (!available || running) return
        sensorManager.registerListener(this, linearAccel, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this, rotationVec, SensorManager.SENSOR_DELAY_GAME)
        running = true
    }

    fun stop() {
        if (!running) return
        sensorManager.unregisterListener(this)
        running = false
        deltaMps = 0.0
        filteredParallel = 0.0
        haveRotation = false
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        val p = params()
        if (!p.enabled) return // "Off" preset: pure GPS, bridge does nothing
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                haveRotation = true
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                if (!haveRotation) return
                val bearing = lastBearingRad ?: return
                // Linear accel is in device coordinates; rotate it into world
                // coordinates (x = east, y = north, z = up).
                val ax = event.values[0]; val ay = event.values[1]; val az = event.values[2]
                val wx = rotationMatrix[0] * ax + rotationMatrix[1] * ay + rotationMatrix[2] * az
                val wy = rotationMatrix[3] * ax + rotationMatrix[4] * ay + rotationMatrix[5] * az
                // Project onto the direction of travel.
                val ux = sin(bearing); val uy = cos(bearing)
                val parallel = (wx * ux + wy * uy).toDouble()

                // Low-pass the (noisy, vibration-heavy) bike signal.
                // Alpha comes from Settings: higher = faster response.
                filteredParallel = p.alpha * parallel + (1 - p.alpha) * filteredParallel

                if (lastAccelEventNs != 0L) {
                    val dt = (event.timestamp - lastAccelEventNs) / 1e9
                    if (dt > 0 && dt < 0.5) {
                        // Deadband: ignore engine vibration and sensor noise.
                        val a = if (abs(filteredParallel) < p.deadband) 0.0 else filteredParallel
                        deltaMps = (deltaMps + a * dt).coerceIn(-p.clamp, p.clamp)
                    }
                }
                lastAccelEventNs = event.timestamp
            }
        }
    }

    /**
     * Display speed for the gauge/number: GPS truth plus the (bounded) IMU
     * interpolation, but only while moving. When the GPS says stopped — or
     * the fix is stale — the answer is the GPS speed, period.
     */
    fun displayMps(gpsMps: Double, fixAgeMs: Long): Double {
        if (!available) return gpsMps
        if (!params().enabled) return gpsMps // "Off" preset: pure GPS
        if (gpsMps < 1.0) return gpsMps          // stopped: no prediction, ever
        if (fixAgeMs > STALE_MS) return gpsMps  // stale fix: hold last truth
        return (gpsMps + deltaMps).coerceAtLeast(0.0)
    }

    companion object {
        const val STALE_MS = 2000L  // fix older than this: bridge off, hold GPS speed
    }
}
