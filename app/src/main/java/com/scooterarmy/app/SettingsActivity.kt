package com.scooterarmy.app

import android.app.Activity
import android.os.Bundle
import android.widget.SeekBar
import com.scooterarmy.app.databinding.ActivitySettingsBinding
import kotlin.math.roundToInt

/**
 * Settings — Speed Response, GPS, Timer, Ride, Alerts, Display, Advanced.
 *
 * Every change saves the moment it is made (SharedPreferences via
 * AppSettings) and MainActivity picks it up live on return — no restart.
 * Defaults are the exact values the app used before Settings existed, so
 * a user who never opens this screen gets a byte-identical app.
 * Rotation cannot lose anything: values live in prefs, not in the UI.
 */
class SettingsActivity : Activity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: AppSettings

    /** True while loadAll() is pushing stored values into the UI — listeners
     *  must not treat those as user edits. */
    private var updatingUi = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Same photographic background as the main screen (Build #79).
        applyPhotoBackground(binding.root)
        settings = AppSettings(this)

        // ── Speed response preset ──
        binding.presetGroup.setOnCheckedChangeListener { _, checkedId ->
            if (updatingUi) return@setOnCheckedChangeListener
            val preset = when (checkedId) {
                R.id.presetResponsive -> AppSettings.PRESET_RESPONSIVE
                R.id.presetOff -> AppSettings.PRESET_OFF
                R.id.presetCustom -> AppSettings.PRESET_CUSTOM
                else -> AppSettings.PRESET_BIKE
            }
            settings.preset = preset
            // A preset sets the sliders to its values (Off greys them out).
            val p = settings.bridgeParams
            setSliderValues(p.deadband, p.alpha, p.clamp)
            setBridgeSlidersEnabled(preset != AppSettings.PRESET_OFF)
        }

        // ── Bridge sliders: moving one makes the preset Custom ──
        binding.deadbandSeek.setOnSeekBarChangeListener(sliderListener {
            settings.deadband = deadbandFromProgress(it)
            settings.preset = AppSettings.PRESET_CUSTOM
            checkCustomRadio()
            binding.deadbandVal.text = fmtDeadband(settings.deadband)
        })
        binding.alphaSeek.setOnSeekBarChangeListener(sliderListener {
            settings.alpha = alphaFromProgress(it)
            settings.preset = AppSettings.PRESET_CUSTOM
            checkCustomRadio()
            binding.alphaVal.text = fmtAlpha(settings.alpha)
        })
        binding.clampSeek.setOnSeekBarChangeListener(sliderListener {
            settings.clamp = clampFromProgress(it)
            settings.preset = AppSettings.PRESET_CUSTOM
            checkCustomRadio()
            binding.clampVal.text = fmtClamp(settings.clamp)
        })

        // ── GPS ──
        binding.lockGroup.setOnCheckedChangeListener { _, checkedId ->
            if (updatingUi) return@setOnCheckedChangeListener
            settings.lockGate = when (checkedId) {
                R.id.lock5 -> 5.0
                R.id.lock20 -> 20.0
                else -> 10.0
            }
        }
        binding.rejectGroup.setOnCheckedChangeListener { _, checkedId ->
            if (updatingUi) return@setOnCheckedChangeListener
            settings.spdReject = when (checkedId) {
                R.id.reject05 -> 0.5
                R.id.reject20 -> 2.0
                else -> 1.0
            }
        }
        binding.spikeSwitch.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) settings.spikeGuard = checked
        }

        // ── Timer ──
        binding.armSeek.setOnSeekBarChangeListener(sliderListener {
            settings.armMph = armFromProgress(it)
            binding.armVal.text = fmtMph(settings.armMph)
        })
        binding.targetSeek.setOnSeekBarChangeListener(sliderListener {
            settings.defaultTarget = targetFromProgress(it)
            binding.targetVal.text = fmtMph(settings.defaultTarget)
        })

        // ── Ride ──
        binding.idleGroup.setOnCheckedChangeListener { _, checkedId ->
            if (updatingUi) return@setOnCheckedChangeListener
            settings.idleMin = when (checkedId) {
                R.id.idle1 -> 1
                R.id.idle10 -> 10
                R.id.idleOff -> 0
                else -> 5
            }
        }

        // ── Alerts ──
        binding.beepSwitch.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) settings.alertBeep = checked
        }

        // ── Display ──
        binding.gaugeGroup.setOnCheckedChangeListener { _, checkedId ->
            if (updatingUi) return@setOnCheckedChangeListener
            settings.gaugeMax = when (checkedId) {
                R.id.gauge140 -> 140
                R.id.gauge180 -> 180
                else -> 100
            }
        }

        // ── Advanced ──
        binding.diagSwitch.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) settings.showDiagnostics = checked
        }

        binding.resetBtn.setOnClickListener {
            settings.resetAll()
            loadAll()
        }
        binding.doneBtn.setOnClickListener { finish() }

        loadAll()
    }

    /** Push every stored value into the UI. */
    private fun loadAll() {
        updatingUi = true
        when (settings.preset) {
            AppSettings.PRESET_RESPONSIVE -> binding.presetGroup.check(R.id.presetResponsive)
            AppSettings.PRESET_OFF -> binding.presetGroup.check(R.id.presetOff)
            AppSettings.PRESET_CUSTOM -> binding.presetGroup.check(R.id.presetCustom)
            else -> binding.presetGroup.check(R.id.presetBike)
        }
        setSliderValues(settings.deadband, settings.alpha, settings.clamp)
        setBridgeSlidersEnabled(settings.preset != AppSettings.PRESET_OFF)
        binding.deadbandVal.text = fmtDeadband(settings.deadband)
        binding.alphaVal.text = fmtAlpha(settings.alpha)
        binding.clampVal.text = fmtClamp(settings.clamp)

        when (settings.lockGate) {
            5.0 -> binding.lockGroup.check(R.id.lock5)
            20.0 -> binding.lockGroup.check(R.id.lock20)
            else -> binding.lockGroup.check(R.id.lock10)
        }
        when (settings.spdReject) {
            0.5 -> binding.rejectGroup.check(R.id.reject05)
            2.0 -> binding.rejectGroup.check(R.id.reject20)
            else -> binding.rejectGroup.check(R.id.reject10)
        }
        binding.spikeSwitch.isChecked = settings.spikeGuard

        binding.armSeek.progress = progressFromArm(settings.armMph)
        binding.armVal.text = fmtMph(settings.armMph)
        binding.targetSeek.progress = progressFromTarget(settings.defaultTarget)
        binding.targetVal.text = fmtMph(settings.defaultTarget)

        when (settings.idleMin) {
            1 -> binding.idleGroup.check(R.id.idle1)
            10 -> binding.idleGroup.check(R.id.idle10)
            0 -> binding.idleGroup.check(R.id.idleOff)
            else -> binding.idleGroup.check(R.id.idle5)
        }

        binding.beepSwitch.isChecked = settings.alertBeep

        when (settings.gaugeMax) {
            140 -> binding.gaugeGroup.check(R.id.gauge140)
            180 -> binding.gaugeGroup.check(R.id.gauge180)
            else -> binding.gaugeGroup.check(R.id.gauge100)
        }

        binding.diagSwitch.isChecked = settings.showDiagnostics
        updatingUi = false
    }

    private fun checkCustomRadio() {
        if (binding.presetGroup.checkedRadioButtonId != R.id.presetCustom) {
            updatingUi = true
            binding.presetGroup.check(R.id.presetCustom)
            updatingUi = false
        }
    }

    private fun setBridgeSlidersEnabled(enabled: Boolean) {
        binding.deadbandSeek.isEnabled = enabled
        binding.alphaSeek.isEnabled = enabled
        binding.clampSeek.isEnabled = enabled
        val alpha = if (enabled) 1f else 0.35f
        binding.deadbandSeek.alpha = alpha
        binding.alphaSeek.alpha = alpha
        binding.clampSeek.alpha = alpha
    }

    private fun setSliderValues(deadband: Double, alpha: Double, clamp: Double) {
        binding.deadbandSeek.progress = progressFromDeadband(deadband)
        binding.alphaSeek.progress = progressFromAlpha(alpha)
        binding.clampSeek.progress = progressFromClamp(clamp)
        binding.deadbandVal.text = fmtDeadband(deadband)
        binding.alphaVal.text = fmtAlpha(alpha)
        binding.clampVal.text = fmtClamp(clamp)
    }

    private fun sliderListener(onChange: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !updatingUi) onChange(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }

    private fun fmtDeadband(v: Double) = String.format("%.2f m/s²", v)
    private fun fmtAlpha(v: Double) = String.format("%.2f", v)
    private fun fmtClamp(v: Double) = String.format("±%.1f m/s", v)
    private fun fmtMph(v: Double) =
        if (v == v.toLong().toDouble()) "${v.toLong()} mph" else "$v mph"

    companion object {
        // ── Slider mappings (pure — exercised by the test harness) ──
        // deadband 0.05–0.50 step 0.01 → progress 0..45
        fun deadbandFromProgress(p: Int) = 0.05 + p * 0.01
        fun progressFromDeadband(v: Double) = ((v - 0.05) / 0.01).roundToInt().coerceIn(0, 45)
        // alpha 0.15–0.60 step 0.01 → progress 0..45
        fun alphaFromProgress(p: Int) = 0.15 + p * 0.01
        fun progressFromAlpha(v: Double) = ((v - 0.15) / 0.01).roundToInt().coerceIn(0, 45)
        // clamp 1.0–5.0 step 0.5 → progress 0..8
        fun clampFromProgress(p: Int) = 1.0 + p * 0.5
        fun progressFromClamp(v: Double) = ((v - 1.0) / 0.5).roundToInt().coerceIn(0, 8)
        // arm 2–10 mph step 0.5 → progress 0..16
        fun armFromProgress(p: Int) = 2.0 + p * 0.5
        fun progressFromArm(v: Double) = ((v - 2.0) / 0.5).roundToInt().coerceIn(0, 16)
        // target 10–120 mph step 5 → progress 0..22
        fun targetFromProgress(p: Int) = 10.0 + p * 5.0
        fun progressFromTarget(v: Double) = ((v - 10.0) / 5.0).roundToInt().coerceIn(0, 22)
    }
}
