package com.scooterarmy.app

import android.content.Context
import android.content.SharedPreferences

/** Effective IMU bridge parameters after preset resolution. */
data class BridgeParams(val enabled: Boolean, val deadband: Double, val alpha: Double, val clamp: Double)

/**
 * All user settings live here (SharedPreferences "samo_gps", same file as
 * the logs — different keys, nothing leaves the phone).
 *
 * DEFAULTS ARE THE CURRENT BEHAVIOR. Every default below is the exact value
 * the app used before Settings existed (bike-tuned bridge, 10 m lock gate,
 * 1.0 m/s reject, spike guard on, 4 mph arm, 45 target, 5 min idle stop,
 * beep on, 100 mph dial, diagnostics off), so a user who never opens
 * Settings gets a byte-identical app. Getters validate on every read, so a
 * corrupt or out-of-range stored value silently falls back to its default
 * instead of ever crashing or misbehaving.
 */
class AppSettings(private val prefs: SharedPreferences) {

    /** Production entry point: the app's "samo_gps" preferences. */
    constructor(context: Context) : this(
        context.getSharedPreferences("samo_gps", Context.MODE_PRIVATE)
    )

    companion object {
        const val PRESET_BIKE = "bike"
        const val PRESET_RESPONSIVE = "responsive"
        const val PRESET_OFF = "off"
        const val PRESET_CUSTOM = "custom"

        const val DEF_PRESET = PRESET_BIKE
        const val DEF_DEADBAND = 0.30
        const val DEF_ALPHA = 0.25
        const val DEF_CLAMP = 2.5
        const val DEF_LOCK_GATE = 10.0
        const val DEF_SPD_REJECT = 1.0
        const val DEF_SPIKE_GUARD = true
        const val DEF_ARM_MPH = 4.0
        const val DEF_DEFAULT_TARGET = 45.0
        const val DEF_IDLE_MIN = 5
        const val DEF_ALERT_BEEP = true
        const val DEF_GAUGE_MAX = 100
        const val DEF_DIAGNOSTICS = false

        const val DEADBAND_MIN = 0.05
        const val DEADBAND_MAX = 0.50
        const val ALPHA_MIN = 0.15
        const val ALPHA_MAX = 0.60
        const val CLAMP_MIN = 1.0
        const val CLAMP_MAX = 5.0
        const val ARM_MIN = 2.0
        const val ARM_MAX = 10.0
        const val TARGET_MIN = 10.0
        const val TARGET_MAX = 120.0

        // ── Pure validation helpers — also exercised by the test harness ──
        fun validDouble(v: Double, def: Double, min: Double, max: Double): Double =
            if (v.isNaN() || v.isInfinite()) def else v.coerceIn(min, max)

        fun validOptionDouble(v: Double, options: DoubleArray, def: Double): Double =
            if (options.any { it == v }) v else def

        fun validOptionInt(v: Int, options: IntArray, def: Int): Int =
            if (options.contains(v)) v else def

        fun validPreset(v: String?): String = when (v) {
            PRESET_BIKE, PRESET_RESPONSIVE, PRESET_OFF, PRESET_CUSTOM -> v
            else -> DEF_PRESET
        }

        /** Preset → effective bridge parameters. "custom" uses the sliders. */
        fun resolveBridge(preset: String, deadband: Double, alpha: Double, clamp: Double): BridgeParams =
            when (validPreset(preset)) {
                PRESET_OFF -> BridgeParams(false, DEF_DEADBAND, DEF_ALPHA, DEF_CLAMP)
                PRESET_RESPONSIVE -> BridgeParams(true, 0.15, 0.45, DEF_CLAMP)
                PRESET_CUSTOM -> BridgeParams(
                    true,
                    validDouble(deadband, DEF_DEADBAND, DEADBAND_MIN, DEADBAND_MAX),
                    validDouble(alpha, DEF_ALPHA, ALPHA_MIN, ALPHA_MAX),
                    validDouble(clamp, DEF_CLAMP, CLAMP_MIN, CLAMP_MAX)
                )
                else -> BridgeParams(true, DEF_DEADBAND, DEF_ALPHA, DEF_CLAMP)
            }
    }

    var preset: String
        get() = validPreset(prefs.getString("set_preset", DEF_PRESET))
        set(v) = prefs.edit().putString("set_preset", validPreset(v)).apply()

    var deadband: Double
        get() = validDouble(
            prefs.getFloat("set_deadband", DEF_DEADBAND.toFloat()).toDouble(),
            DEF_DEADBAND, DEADBAND_MIN, DEADBAND_MAX
        )
        set(v) = prefs.edit().putFloat(
            "set_deadband",
            validDouble(v, DEF_DEADBAND, DEADBAND_MIN, DEADBAND_MAX).toFloat()
        ).apply()

    var alpha: Double
        get() = validDouble(
            prefs.getFloat("set_alpha", DEF_ALPHA.toFloat()).toDouble(),
            DEF_ALPHA, ALPHA_MIN, ALPHA_MAX
        )
        set(v) = prefs.edit().putFloat(
            "set_alpha",
            validDouble(v, DEF_ALPHA, ALPHA_MIN, ALPHA_MAX).toFloat()
        ).apply()

    var clamp: Double
        get() = validDouble(
            prefs.getFloat("set_clamp", DEF_CLAMP.toFloat()).toDouble(),
            DEF_CLAMP, CLAMP_MIN, CLAMP_MAX
        )
        set(v) = prefs.edit().putFloat(
            "set_clamp",
            validDouble(v, DEF_CLAMP, CLAMP_MIN, CLAMP_MAX).toFloat()
        ).apply()

    /** Live bridge parameters: preset wins, "custom" reads the sliders. */
    val bridgeParams: BridgeParams get() = resolveBridge(preset, deadband, alpha, clamp)

    var lockGate: Double
        get() = validOptionDouble(
            prefs.getFloat("set_lock_gate", DEF_LOCK_GATE.toFloat()).toDouble(),
            doubleArrayOf(5.0, 10.0, 20.0), DEF_LOCK_GATE
        )
        set(v) = prefs.edit().putFloat(
            "set_lock_gate",
            validOptionDouble(v, doubleArrayOf(5.0, 10.0, 20.0), DEF_LOCK_GATE).toFloat()
        ).apply()

    var spdReject: Double
        get() = validOptionDouble(
            prefs.getFloat("set_spd_reject", DEF_SPD_REJECT.toFloat()).toDouble(),
            doubleArrayOf(0.5, 1.0, 2.0), DEF_SPD_REJECT
        )
        set(v) = prefs.edit().putFloat(
            "set_spd_reject",
            validOptionDouble(v, doubleArrayOf(0.5, 1.0, 2.0), DEF_SPD_REJECT).toFloat()
        ).apply()

    var spikeGuard: Boolean
        get() = prefs.getBoolean("set_spike_guard", DEF_SPIKE_GUARD)
        set(v) = prefs.edit().putBoolean("set_spike_guard", v).apply()

    var armMph: Double
        get() = validDouble(
            prefs.getFloat("set_arm_mph", DEF_ARM_MPH.toFloat()).toDouble(),
            DEF_ARM_MPH, ARM_MIN, ARM_MAX
        )
        set(v) = prefs.edit().putFloat(
            "set_arm_mph",
            validDouble(v, DEF_ARM_MPH, ARM_MIN, ARM_MAX).toFloat()
        ).apply()

    var defaultTarget: Double
        get() = validDouble(
            prefs.getFloat("set_default_target", DEF_DEFAULT_TARGET.toFloat()).toDouble(),
            DEF_DEFAULT_TARGET, TARGET_MIN, TARGET_MAX
        )
        set(v) = prefs.edit().putFloat(
            "set_default_target",
            validDouble(v, DEF_DEFAULT_TARGET, TARGET_MIN, TARGET_MAX).toFloat()
        ).apply()

    var idleMin: Int
        get() = validOptionInt(
            prefs.getInt("set_idle_min", DEF_IDLE_MIN),
            intArrayOf(0, 1, 5, 10), DEF_IDLE_MIN
        )
        set(v) = prefs.edit().putInt(
            "set_idle_min",
            validOptionInt(v, intArrayOf(0, 1, 5, 10), DEF_IDLE_MIN)
        ).apply()

    var alertBeep: Boolean
        get() = prefs.getBoolean("set_alert_beep", DEF_ALERT_BEEP)
        set(v) = prefs.edit().putBoolean("set_alert_beep", v).apply()

    var gaugeMax: Int
        get() = validOptionInt(
            prefs.getInt("set_gauge_max", DEF_GAUGE_MAX),
            intArrayOf(100, 140, 180), DEF_GAUGE_MAX
        )
        set(v) = prefs.edit().putInt(
            "set_gauge_max",
            validOptionInt(v, intArrayOf(100, 140, 180), DEF_GAUGE_MAX)
        ).apply()

    var showDiagnostics: Boolean
        get() = prefs.getBoolean("set_diagnostics", DEF_DIAGNOSTICS)
        set(v) = prefs.edit().putBoolean("set_diagnostics", v).apply()

    /** Wipe every settings key — logs, alert limit and everything else untouched. */
    fun resetAll() {
        prefs.edit()
            .remove("set_preset")
            .remove("set_deadband")
            .remove("set_alpha")
            .remove("set_clamp")
            .remove("set_lock_gate")
            .remove("set_spd_reject")
            .remove("set_spike_guard")
            .remove("set_arm_mph")
            .remove("set_default_target")
            .remove("set_idle_min")
            .remove("set_alert_beep")
            .remove("set_gauge_max")
            .remove("set_diagnostics")
            .apply()
    }
}
