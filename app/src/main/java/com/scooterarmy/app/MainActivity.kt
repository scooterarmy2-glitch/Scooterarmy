package com.scooterarmy.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.scooterarmy.app.databinding.ActivityMainBinding
import com.scooterarmy.app.databinding.ItemRideBinding
import com.scooterarmy.app.databinding.ItemRunBinding
import java.util.Date
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * #SAMO GPS — fully native (v1.1.0).
 *
 * No WebView, no HTML/JS bridge: GPS fixes go straight from LocationManager
 * (and the Fused Location Provider for fast lock) into native Views.
 *
 * Accuracy model, in priority order:
 *  1. GPS_PROVIDER is ground truth. Speed comes from Doppler
 *     (Location.getSpeed) only — never from fused/network estimates.
 *  2. The Fused Location Provider gives a fast first lock and smooth
 *     position in town/trees; it never feeds speed or run timing.
 *  3. Between GPS fixes the IMU bridge (ImuBridge) interpolates the
 *     display from real measured acceleration — bounded, and snapped back
 *     to true GPS speed on every fresh fix. It never runs while stopped.
 *  4. Raw GNSS measurements flag dual-frequency (L1+L5) reception as a
 *     quality badge; phones without it just don't show the badge.
 */
class MainActivity : Activity() {

    enum class RunState { IDLE, ARMED, RUNNING, DONE }

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: LogStore
    private lateinit var imuBridge: ImuBridge

    // ── GPS engine ──────────────────────────────────────────────
    private var locMgr: LocationManager? = null
    private var listening = false
    private var gnssCallback: GnssStatus.Callback? = null
    private var gnssMeasCallback: GnssMeasurementsEvent.Callback? = null

    // Fused Location Provider (fast lock / position assist only).
    private var fusedClient: FusedLocationProviderClient? = null
    private var fusedAvailable = false
    private var fusedCallback: LocationCallback? = null

    private val locListener = object : LocationListener {
        override fun onLocationChanged(l: Location) { onGpsFix(l) }
        override fun onProviderEnabled(p: String) { setSdot(if (gpsReady) "active" else "warn") }
        override fun onProviderDisabled(p: String) {
            gpsReady = false
            setSdot("error")
            binding.giStatus.text = "ERROR"
            binding.giStatus.setTextColor(Color.parseColor("#ff5533"))
        }
        override fun onStatusChanged(p: String?, status: Int, extras: Bundle?) {}
    }

    private val netListener = object : LocationListener {
        override fun onLocationChanged(l: Location) { onNetworkFix(l) }
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
        override fun onStatusChanged(p: String?, status: Int, extras: Bundle?) {}
    }

    // ── State ───────────────────────────────────────────────────
    private var useMetric = false
    private var state = RunState.IDLE
    private var currentSpd = 0.0        // display units (mph or km/h), raw GPS
    private var rawMps = 0.0
    private var gpsReady = false
    private var allTimeTopSpd = 0.0
    private var lastFixPerf = 0L        // elapsedRealtime when last fix processed
    private var lastFixT = 0L           // fix-clock ms of last fix
    private var fixClockT = 0L
    private var fixClockPerf = 0L
    private var fixPrev: PrevFix? = null
    private var runStartT: Long? = null
    private var lastAcc = 999.0
    private var lastSig = 0
    private var satUsed = -1
    private var dualBand = false
    private var fullscreen = false

    private data class PrevFix(val t: Long, val mps: Double, val lat: Double, val lon: Double)

    // ── Session (ride) state ────────────────────────────────────
    private var sessionActive = false
    private var sessionStart: Date? = null
    private var sessionTopSpd = 0.0
    private var sessionDist = 0.0       // metres
    private var sessionLastPos: Pair<Double, Double>? = null
    private var sessionRoute = mutableListOf<RoutePoint>()

    // ── Logs ────────────────────────────────────────────────────
    private var rides = mutableListOf<RideRecord>()
    private var runs = mutableListOf<RunRecord>()
    private var alertMph = 0.0
    private var alertOn = false

    // ── Handlers / animators ────────────────────────────────────
    private val uiHandler = Handler(Looper.getMainLooper())
    private var flashAnimator: android.animation.ObjectAnimator? = null
    private var alertFlashAnimator: android.animation.ObjectAnimator? = null
    private var stopPulseAnimator: android.animation.ObjectAnimator? = null
    private var toneGen: ToneGenerator? = null

    private val timerTick = object : Runnable {
        override fun run() {
            if (state == RunState.RUNNING && runStartT != null) {
                binding.timerBig.text = fmtTimer(max(0L, nowT() - runStartT!!))
                setProg(min(currentSpd / tgtVal(), 1.0))
                uiHandler.postDelayed(this, 50)
            }
        }
    }

    private val fixAgeTick = object : Runnable {
        override fun run() {
            updateFixAge()
            uiHandler.postDelayed(this, 1000)
        }
    }

    private val liveTick = object : Runnable {
        override fun run() {
            if (sessionActive) {
                updateSessionLive()
                uiHandler.postDelayed(this, 1000)
            }
        }
    }

    // Refreshes the speedo ~10x/sec between GPS fixes. Without this the IMU
    // bridge never ran: updateSpeedDisplay() was only called on a fix, and
    // onGpsFix() zeroes the bridge delta right before it — so the display
    // only ever showed raw 1 Hz GPS. The tick lets the bridge interpolate
    // between fixes while GPS truth still wins on every fresh fix.
    private val displayTick = object : Runnable {
        override fun run() {
            updateSpeedDisplay()
            uiHandler.postDelayed(this, 100)
        }
    }

    private val idleStop = Runnable { if (sessionActive) stopSession() }

    // SAF export: content waiting for the user to pick a file.
    private var pendingExport: String? = null
    // True while the export file picker is open — opening it pauses the
    // activity, and a pause must not end a ride the way leaving the app does.
    private var exporting = false
    private var pendingExportMime = "text/csv"

    companion object {
        const val ACC_REJECT = 50.0      // reject fixes worse than this (metres)
        const val ACC_GOOD = 10.0        // must beat this before speedo unlocks
        const val SIG_GOOD = 3           // minimum signal bars (of 6) before unlock
        const val SPD_ACC_REJECT = 1.0   // m/s — reject fixes with worse speed accuracy
        const val REQ_LOCATION = 1
        const val REQ_EXPORT = 2
    }

    // ── Lifecycle ───────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = LogStore(this)
        imuBridge = ImuBridge(this)
        RouteMapDialog.configure(this)

        useMetric = store.useMetric
        alertMph = store.alertMph
        rides = store.rides
        runs = store.runs

        applyChromeGradient(binding.brandText)
        applyEmberGradient(binding.brandHash)
        applyChromeGradient(binding.rideLogTitle)
        applyChromeGradient(binding.runLogTitle)
        applyTexturedBackground()
        // Ember glows (density-scaled so they render the same on every screen).
        val density = resources.displayMetrics.density
        binding.brandHash.setShadowLayer(8f * density, 0f, 0f, Color.parseColor("#ff4400"))
        binding.spdNum.setShadowLayer(20f * density, 0f, 0f, Color.parseColor("#ff4400"))
        binding.timerBig.setShadowLayer(15f * density, 0f, 0f, Color.parseColor("#59ff6400"))
        binding.spdUnit.text = if (useMetric) "KM/H ⇄" else "MPH ⇄"
        binding.tgtSuf.text = if (useMetric) "KM/H" else "MPH"
        binding.topSpdUnit.text = if (useMetric) "KM/H" else "MPH"
        if (alertMph > 0) {
            binding.alertInp.setText(trimNum(alertMph))
            binding.alertHint.text = "on — over ${trimNum(alertMph)} mph"
        }

        binding.spdUnit.setOnClickListener { toggleUnit() }
        binding.sessBtn.setOnClickListener { toggleSession() }
        binding.armBtn.setOnClickListener { armRun() }
        binding.resetBtn.setOnClickListener { resetRun() }
        binding.exportRidesCsv.setOnClickListener { exportRides("csv") }
        binding.exportRidesGpx.setOnClickListener { exportRides("gpx") }
        binding.exportRunsCsv.setOnClickListener { exportRuns() }
        binding.clearSessions.setOnClickListener { clearSessions() }
        binding.clearHistory.setOnClickListener { clearHistory() }
        binding.alertInp.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveAlert()
                binding.alertInp.clearFocus()
                true
            } else false
        }
        binding.alertInp.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveAlert()
        }

        val gesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleFullscreen()
                return true
            }
        })
        binding.gaugeWrap.setOnTouchListener { _, event ->
            gesture.onTouchEvent(event)
        }

        renderSessions()
        renderHistory()
        renderBvL()
        updateSpeedDisplay()
        applyChromeVisibility()

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ), REQ_LOCATION
            )
        }
        setupFused()
    }

    override fun onResume() {
        super.onResume()
        setImmersive()
        startLocation()
        imuBridge.start()
        uiHandler.post(fixAgeTick)
        uiHandler.post(displayTick)
        // Resume anything the pause interrupted: a run's timer, the armed
        // flash, the ride's live strip and idle timer.
        if (state == RunState.RUNNING && runStartT != null) {
            binding.timerBig.text = fmtTimer(max(0L, nowT() - runStartT!!))
            uiHandler.post(timerTick)
        }
        if (state == RunState.ARMED) startArmFlash()
        if (sessionActive) {
            uiHandler.post(liveTick)
            resetIdleTimer()
        }
        // Fresh read, exactly what is stored — the log always shows the truth.
        rides = store.rides
        runs = store.runs
        renderSessions()
        renderHistory()
        renderBvL()
    }

    override fun onPause() {
        super.onPause()
        // Same as the web app hiding: a ride ends when the app leaves the screen.
        // Except the export file picker — that also pauses us, and in the web
        // app exporting was a plain download that never ended a ride.
        if (sessionActive && !exporting) stopSession()
        stopLocation()
        imuBridge.stop()
        uiHandler.removeCallbacks(timerTick)
        uiHandler.removeCallbacks(fixAgeTick)
        uiHandler.removeCallbacks(liveTick)
        uiHandler.removeCallbacks(idleStop)
        uiHandler.removeCallbacks(displayTick)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { toneGen?.release() } catch (_: Exception) {}
        toneGen = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyChromeVisibility()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocation()
            } else {
                binding.giStatus.text = "ERROR"
                binding.giStatus.setTextColor(Color.parseColor("#ff5533"))
                setSdot("error")
                Toast.makeText(this, "Location permission denied — GPS won't work", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setImmersive() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    // ── Fullscreen / landscape (speedo only) ────────────────────
    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        applyChromeVisibility()
    }

    private fun applyChromeVisibility() {
        val speedoOnly = isLandscape() || fullscreen
        val gone = View.GONE
        val vis = View.VISIBLE
        binding.headerRow.visibility = if (speedoOnly) gone else vis
        binding.headerDivider.visibility = if (speedoOnly) gone else vis
        binding.gpsInfo.visibility = if (speedoOnly) gone else vis
        binding.rideBox.visibility = if (speedoOnly) gone else vis
        binding.runBox.visibility = if (speedoOnly) gone else vis
        binding.bvlCard.visibility =
            if (speedoOnly || runs.isEmpty()) gone else vis
        binding.rideLogSection.visibility = if (speedoOnly) gone else vis
        binding.runLogSection.visibility = if (speedoOnly) gone else vis
        binding.sessLive.visibility =
            if (speedoOnly) gone else if (sessionActive) vis else gone
        binding.fsHint.visibility = if (fullscreen) vis else gone
        binding.fsInfoRow.visibility = if (fullscreen) vis else gone
        binding.topSpdBox.visibility = if (fullscreen) gone else vis

        // Gauge size: full-screen in speedo-only mode, 320dp otherwise.
        val dm = resources.displayMetrics
        val wPx = dm.widthPixels
        val hPx = dm.heightPixels
        val (gw, gh) = when {
            fullscreen && !isLandscape() ->
                (min((wPx * 0.92).toInt(), (hPx * 0.62).toInt()) to
                    min((wPx * 0.92).toInt(), (hPx * 0.62).toInt()))
            speedoOnly -> {
                val s = min(wPx, hPx) - (16 * dm.density).toInt()
                s to s
            }
            else -> {
                val s = (320 * dm.density).toInt()
                s to s
            }
        }
        val lp = binding.gaugeWrap.layoutParams as LinearLayout.LayoutParams
        lp.width = gw
        lp.height = gh
        lp.gravity = Gravity.CENTER_HORIZONTAL
        binding.gaugeWrap.layoutParams = lp
        binding.contentCol.gravity =
            if (speedoOnly) Gravity.CENTER_VERTICAL else Gravity.TOP

        // Speed number + overlay position scale with the gauge.
        binding.spdNum.setTextSize(
            TypedValue.COMPLEX_UNIT_SP, if (speedoOnly) 110f else 72f
        )
        binding.gaugeWrap.post {
            val overlayLp = binding.spdOverlay.layoutParams as android.widget.FrameLayout.LayoutParams
            overlayLp.bottomMargin =
                if (speedoOnly) (binding.gaugeWrap.height * 0.14).toInt()
                else (50 * dm.density).toInt()
            binding.spdOverlay.layoutParams = overlayLp
        }
        if (fullscreen) updateFsInfo()
    }

    private fun applyChromeGradient(tv: android.widget.TextView) {
        tv.post {
            if (tv.height > 0) {
                tv.paint.shader = LinearGradient(
                    0f, 0f, 0f, tv.height.toFloat(),
                    intArrayOf(
                        Color.WHITE, Color.parseColor("#bbbbbb"),
                        Color.parseColor("#eeeeee"), Color.parseColor("#999999")
                    ),
                    floatArrayOf(0f, 0.4f, 0.6f, 1f),
                    Shader.TileMode.CLAMP
                )
                tv.invalidate()
            }
        }
    }

    /** Red-orange gradient for the "#" in the brand, matching the web header. */
    private fun applyEmberGradient(tv: android.widget.TextView) {
        tv.post {
            if (tv.height > 0) {
                tv.paint.shader = LinearGradient(
                    0f, 0f, 0f, tv.height.toFloat(),
                    intArrayOf(
                        Color.parseColor("#ff6644"),
                        Color.parseColor("#cc1a00"),
                        Color.parseColor("#ff3300")
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
                tv.invalidate()
            }
        }
    }

    /** Root background: ember glow + diamond-plate texture covering the WHOLE
     *  screen, matching the old web app's body background. The web app's radial
     *  glows are sized relative to the viewport (ellipse at 50% 100%, fading at
     *  55% of the farthest-corner distance), so the native version sizes its
     *  radii from the actual screen height instead of fixed dp values — the old
     *  fixed 420dp/300dp radii died halfway up a ~900dp screen, which is why the
     *  glow only showed at the bottom. The texture is also stronger (10% white
     *  vs 4%) so the diamonds read everywhere, not just where the glow sits
     *  behind them. Visual only. */
    private fun applyTexturedBackground() {
        val dm = resources.displayMetrics
        val density = dm.density
        val screenH = dm.heightPixels.toFloat()
        // Diamond-plate texture tile (28x16dp, like the web app's 28px x 16px).
        val w = (28f * density).toInt().coerceAtLeast(2)
        val h = (16f * density).toInt().coerceAtLeast(2)
        val tile = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(tile)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.8f * density
            color = Color.parseColor("#1affffff")
        }
        val diamond = Path().apply {
            moveTo(w / 2f, 0f)
            lineTo(w.toFloat(), h / 2f)
            lineTo(w / 2f, h.toFloat())
            lineTo(0f, h / 2f)
            close()
        }
        canvas.drawPath(diamond, paint)
        val tiled = BitmapDrawable(resources, tile).apply {
            tileModeX = Shader.TileMode.REPEAT
            tileModeY = Shader.TileMode.REPEAT
        }
        // Wide ember glow, bottom center — reaches ~85% up the screen.
        val wideGlow = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setGradientCenter(0.5f, 1.0f)
            gradientRadius = screenH * 0.85f
            colors = intArrayOf(Color.parseColor("#40c83c00"), Color.TRANSPARENT)
        }
        // Hotter orange core, bottom center.
        val coreGlow = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setGradientCenter(0.5f, 1.0f)
            gradientRadius = screenH * 0.52f
            colors = intArrayOf(Color.parseColor("#26ff6400"), Color.TRANSPARENT)
        }
        // Dark vignette at the top (as in the web app).
        val vignette = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setGradientCenter(0.5f, 0.0f)
            gradientRadius = screenH * 0.7f
            colors = intArrayOf(Color.parseColor("#b3000000"), Color.TRANSPARENT)
        }
        // Bottom ember fade (web body::after, 160dp tall).
        val fadeH = (160f * density).toInt()
        val bottomFade = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(
                Color.parseColor("#2eb42800"),
                Color.parseColor("#14ff5000"),
                Color.TRANSPARENT
            )
        )
        val base = ColorDrawable(Color.parseColor("#080808"))
        val layers = LayerDrawable(arrayOf(base, wideGlow, coreGlow, vignette, bottomFade, tiled))
        // Confine the bottom fade to the bottom 160dp of the screen.
        val topInset = (screenH - fadeH).toInt().coerceAtLeast(0)
        layers.setLayerInset(4, 0, topInset, 0, 0)
        binding.rootLayout.background = layers
    }

    private fun setBtn(btn: Button, enabled: Boolean) {
        btn.isEnabled = enabled
        btn.alpha = if (enabled) 1f else 0.3f
    }

    private fun trimNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    // ── Unit conversion ─────────────────────────────────────────
    private fun toMps(displayVal: Double): Double =
        if (useMetric) displayVal / 3.6 else displayVal / 2.23694

    private fun fromMps(mps: Double): Double =
        if (useMetric) mps * 3.6 else mps * 2.23694

    private fun maxSpd(): Double = if (useMetric) 160.0 else 100.0

    private fun tgtVal(): Double =
        binding.tgtInp.text.toString().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0

    private fun nowT(): Long =
        fixClockT + (SystemClock.elapsedRealtime() - fixClockPerf)

    // ── GPS engine ──────────────────────────────────────────────
    private fun startLocation() {
        if (listening) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            locMgr = lm
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, locListener)
            try {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, netListener)
            } catch (_: Exception) {
            }
            gnssCallback = object : GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    var used = 0
                    val total = status.satelliteCount
                    for (i in 0 until total) if (status.usedInFix(i)) used++
                    satUsed = used
                    binding.satCount.text = used.toString()
                }
            }
            try {
                lm.registerGnssStatusCallback(gnssCallback!!)
            } catch (_: Exception) {
                gnssCallback = null
            }

            // Raw GNSS measurements: dual-frequency (L1+L5) detection.
            // Some chipsets never deliver these — the badge just stays off.
            try {
                gnssMeasCallback = object : GnssMeasurementsEvent.Callback() {
                    override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
                        var l1 = 0
                        var l5 = 0
                        for (m in event.measurements) {
                            val f = m.carrierFrequencyHz
                            if (f > 0) {
                                when {
                                    abs(f - 1176.45e6f) < 2e6f -> l5++
                                    abs(f - 1207.14e6f) < 2e6f -> l5++ // Galileo E5b
                                    abs(f - 1575.42e6f) < 2e6f -> l1++
                                }
                            }
                        }
                        val wasDual = dualBand
                        dualBand = l5 > 0 && l1 > 0
                        if (dualBand != wasDual) updateAccSub()
                    }

                    override fun onStatusChanged(status: Int) {}
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    lm.registerGnssMeasurementsCallback(mainExecutor, gnssMeasCallback!!)
                } else {
                    @Suppress("DEPRECATION")
                    lm.registerGnssMeasurementsCallback(gnssMeasCallback!!)
                }
            } catch (_: Exception) {
                gnssMeasCallback = null
            }

            // Fused provider: fast lock + position assist (never speed/timing).
            if (fusedAvailable && fusedClient != null) {
                try {
                    val request = LocationRequest.Builder(
                        Priority.PRIORITY_HIGH_ACCURACY, 500
                    ).setMinUpdateIntervalMillis(250).build()
                    fusedCallback = object : LocationCallback() {
                        override fun onLocationResult(result: LocationResult) {
                            result.lastLocation?.let { onFusedFix(it) }
                        }
                    }
                    fusedClient!!.requestLocationUpdates(
                        request, fusedCallback!!, Looper.getMainLooper()
                    )
                } catch (_: Exception) {
                }
            }

            listening = true
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                gpsReady = false
                setSdot("error")
                binding.giStatus.text = "ERROR"
                binding.giStatus.setTextColor(Color.parseColor("#ff5533"))
                Toast.makeText(this, "Location is off — turn on GPS", Toast.LENGTH_LONG).show()
            }
        } catch (_: SecurityException) {
            listening = false
        }
    }

    private fun stopLocation() {
        val lm = locMgr ?: return
        if (listening) {
            try { lm.removeUpdates(locListener) } catch (_: Exception) {}
            try { lm.removeUpdates(netListener) } catch (_: Exception) {}
            gnssCallback?.let { try { lm.unregisterGnssStatusCallback(it) } catch (_: Exception) {} }
            gnssMeasCallback?.let {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        lm.unregisterGnssMeasurementsCallback(it)
                    } else {
                        @Suppress("DEPRECATION")
                        lm.unregisterGnssMeasurementsCallback(it)
                    }
                } catch (_: Exception) {}
            }
            fusedCallback?.let { try { fusedClient?.removeLocationUpdates(it) } catch (_: Exception) {} }
            listening = false
        }
    }

    private fun setupFused() {
        try {
            val availability =
                GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this)
            if (availability != ConnectionResult.SUCCESS) {
                fusedAvailable = false
                return
            }
            fusedClient = LocationServices.getFusedLocationProviderClient(this)
            fusedAvailable = true
        } catch (_: Exception) {
            fusedAvailable = false
        }
    }

    /** Raw GPS-provider fix — the ground truth for speed and timing. */
    private fun onGpsFix(l: Location) {
        handleFix(
            FixData(
                lat = l.latitude,
                lon = l.longitude,
                speedMps = if (l.hasSpeed()) l.speed else null,
                acc = if (l.hasAccuracy()) l.accuracy else 999f,
                speedAcc = if (l.hasSpeedAccuracy()) l.speedAccuracyMetersPerSecond else null,
                t = l.elapsedRealtimeNanos / 1_000_000L,
                bearingDeg = if (l.hasBearing()) l.bearing else null
            )
        )
    }

    /** Network (cell/Wi-Fi) fix: coarse position while GPS is still locking. */
    private fun onNetworkFix(l: Location) {
        if (gpsReady) return
        val acc = if (l.hasAccuracy()) l.accuracy else 999f
        binding.giStatus.text = "ASSISTED"
        binding.giStatus.setTextColor(Color.parseColor("#cccccc"))
        binding.giAcc.text = acc.roundToInt().toString()
        binding.giAcc.setTextColor(Color.parseColor("#ffb347"))
        binding.acctxt.text = "±${acc.roundToInt()}m"
        setSdot("warn")
    }

    /** Fused fix: fast lock assist only. Never speed, never run timing. */
    private fun onFusedFix(l: Location) {
        if (gpsReady) return
        val acc = if (l.hasAccuracy()) l.accuracy else 999f
        binding.giStatus.text = "ASSISTED"
        binding.giStatus.setTextColor(Color.parseColor("#cccccc"))
        binding.giAcc.text = acc.roundToInt().toString()
        binding.giAcc.setTextColor(Color.parseColor("#ffb347"))
        binding.acctxt.text = "±${acc.roundToInt()}m"
        setSdot("warn")
    }

    private fun handleFix(f: FixData) {
        val perf = SystemClock.elapsedRealtime()
        val acc = f.acc.toDouble()
        if (fixPrev != null && f.t <= fixPrev!!.t) return // duplicate / out-of-order

        val sig = when {
            acc < 3 -> 6
            acc < 5 -> 5
            acc < 10 -> 4
            acc < 20 -> 3
            acc < 40 -> 2
            acc < 60 -> 1
            else -> 0
        }
        lastAcc = acc
        lastSig = sig

        // Speed straight from the chip (Doppler). Only derive from positions
        // if the chip gave none.
        var mps = if (f.speedMps != null && f.speedMps >= 0) f.speedMps.toDouble() else -1.0
        if (mps < 0 && fixPrev != null) {
            val dt = (f.t - fixPrev!!.t) / 1000.0
            if (dt > 0.2 && dt < 5) {
                mps = haversineM(fixPrev!!.lat, fixPrev!!.lon, f.lat, f.lon) / dt
            }
        }
        val badSpd = f.speedAcc != null && f.speedAcc > SPD_ACC_REJECT
        // Spike guard: >12 m/s (~27 mph) in <1.5 s is implausible — GPS glitch.
        val spike = fixPrev != null && mps >= 0 &&
            abs(mps - fixPrev!!.mps) > 12 && (f.t - fixPrev!!.t) < 1500
        val usable = mps >= 0 && acc <= ACC_REJECT && !badSpd && !spike

        // During a run, drop unusable fixes without touching timing references.
        if (state == RunState.RUNNING && !usable) {
            updateSignalDisplay(acc)
            return
        }

        lastFixPerf = perf
        lastFixT = f.t

        if (acc <= ACC_GOOD && sig >= SIG_GOOD) gpsReady = true
        if (gpsReady && usable) {
            rawMps = mps
            currentSpd = fromMps(mps)
            f.bearingDeg?.let { imuBridge.setBearingDeg(it) }
            imuBridge.onGpsFix() // truth wins: snap display, reset the bridge
        } else if (!gpsReady) {
            currentSpd = 0.0
            rawMps = 0.0
        }

        // ── Run logic on fix time, with interpolation between fixes ──
        if (gpsReady && usable) {
            val prev = fixPrev
            fixClockT = f.t
            fixClockPerf = perf

            if (state == RunState.ARMED && mps > toMps(if (useMetric) 6.0 else 4.0)) {
                runStartT = estimateStart(prev, mps, f.t)
                startRun()
            }
            if (state == RunState.RUNNING) {
                val tl = toMps(tgtVal())
                if (mps >= tl) {
                    finishRun(max(0L, crossT(prev, mps, f.t, tl) - (runStartT ?: f.t)))
                }
            }
            fixPrev = PrevFix(f.t, mps, f.lat, f.lon)
        }

        updateSignalDisplay(acc)
        updateGpsPanel(acc, sig)
        updateSpeedDisplay()

        // Session tracking — only when GPS is reliable.
        if (sessionActive && gpsReady) {
            val lastPt = sessionRoute.lastOrNull()
            if (lastPt == null || (f.t - lastPt.t > 3000) ||
                haversineM(lastPt.lat, lastPt.lon, f.lat, f.lon) > 10
            ) {
                sessionRoute.add(RoutePoint(f.lat, f.lon, (mps * 10).roundToInt() / 10.0, f.t))
                if (sessionRoute.size > 2000) sessionRoute.removeAt(0)
            }
            if (currentSpd > sessionTopSpd) sessionTopSpd = currentSpd
            sessionLastPos?.let { (la, lo) ->
                sessionDist += haversineM(la, lo, f.lat, f.lon)
            }
            if (rawMps > 0.5) resetIdleTimer()
        }
        sessionLastPos = f.lat to f.lon

        setSdot("active")
    }

    /** Moment speed crossed `level` (m/s) between previous fix and this one. */
    private fun crossT(p: PrevFix?, mps: Double, t: Long, level: Double): Long {
        if (p == null || mps <= p.mps) return t
        val frac = ((level - p.mps) / (mps - p.mps)).coerceIn(0.0, 1.0)
        return p.t + (frac * (t - p.t)).toLong()
    }

    /** Run start = where the speed line through the first two fixes meets 0 m/s. */
    private fun estimateStart(p: PrevFix?, mps: Double, t: Long): Long {
        if (p == null) return t
        val a = (mps - p.mps) / ((t - p.t) / 1000.0) // m/s²
        if (a <= 0.05) return p.t
        return min(t, max(t - (mps / a * 1000).toLong(), t - 4000))
    }

    // ── Display updates ─────────────────────────────────────────
    /** The speedo: GPS truth + the bounded IMU bridge while moving. */
    private fun updateSpeedDisplay() {
        val fixAgeMs = if (lastFixPerf == 0L) Long.MAX_VALUE
        else SystemClock.elapsedRealtime() - lastFixPerf
        val dispMps = imuBridge.displayMps(rawMps, fixAgeMs)
        val disp = fromMps(dispMps)
        val ratio = disp / maxSpd()

        binding.spdNum.text = disp.roundToInt().toString()
        binding.spdNum.setTextColor(
            when {
                ratio > 0.85 -> Color.parseColor("#ff5522")
                ratio > 0.6 -> Color.parseColor("#ffb347")
                else -> Color.WHITE
            }
        )
        // Ember glow follows the speed color (web .spd-num text-shadow).
        val glowColor = when {
            ratio > 0.85 -> Color.parseColor("#ff5522")
            ratio > 0.6 -> Color.parseColor("#ffb347")
            else -> Color.parseColor("#ff4400")
        }
        binding.spdNum.setShadowLayer(20f * resources.displayMetrics.density, 0f, 0f, glowColor)
        binding.gauge.speed = disp
        binding.gauge.useMetric = useMetric

        if (gpsReady && currentSpd > allTimeTopSpd) {
            allTimeTopSpd = currentSpd
            binding.topSpdVal.text = allTimeTopSpd.roundToInt().toString()
            binding.topSpdUnit.text = if (useMetric) "KM/H" else "MPH"
        }
        binding.gpsWait.visibility = if (gpsReady) View.GONE else View.VISIBLE

        checkSpeedAlert(currentSpd)
        if (fullscreen) updateFsInfo()
    }

    private fun updateFsInfo() {
        binding.fsTopVal.text =
            "${allTimeTopSpd.roundToInt()} ${if (useMetric) "KM/H" else "MPH"}"
        val gpsEl = binding.fsGpsVal
        when {
            lastSig >= 3 -> { gpsEl.text = "LOCKED"; gpsEl.setTextColor(Color.parseColor("#39ff14")) }
            lastSig >= 1 -> { gpsEl.text = "WEAK"; gpsEl.setTextColor(Color.parseColor("#ffb347")) }
            else -> { gpsEl.text = "POOR"; gpsEl.setTextColor(Color.parseColor("#ff5533")) }
        }
        val accEl = binding.fsAccVal
        accEl.text = if (lastAcc < 900) "±${lastAcc.roundToInt()}m" else "—"
        accEl.setTextColor(
            when {
                lastAcc < 5 -> Color.parseColor("#39ff14")
                lastAcc < 20 -> Color.parseColor("#ffb347")
                else -> Color.parseColor("#ff5533")
            }
        )
    }

    private fun updateSignalDisplay(acc: Double) {
        val sig = when {
            acc < 3 -> 6
            acc < 5 -> 5
            acc < 10 -> 4
            acc < 20 -> 3
            acc < 40 -> 2
            acc < 60 -> 1
            else -> 0
        }
        lastSig = sig
        val bars = listOf(
            binding.sb1, binding.sb2, binding.sb3,
            binding.sb4, binding.sb5, binding.sb6
        )
        bars.forEachIndexed { i, bar ->
            bar.setBackgroundColor(
                if (i < sig) Color.parseColor("#39ff14") else Color.parseColor("#333333")
            )
        }
        binding.acctxt.text = "±${acc.roundToInt()}m"
        updateGpsPanel(acc, sig)
    }

    private fun updateGpsPanel(acc: Double, sig: Int) {
        val statusEl = binding.giStatus
        when {
            sig >= 3 -> {
                statusEl.text = "LOCKED"
                statusEl.setTextColor(Color.parseColor("#39ff14"))
            }
            sig >= 1 -> {
                statusEl.text = "WEAK"
                statusEl.setTextColor(Color.parseColor("#ffb347"))
            }
            else -> {
                statusEl.text = "POOR"
                statusEl.setTextColor(Color.parseColor("#ff5533"))
            }
        }
        val accEl = binding.giAcc
        accEl.text = acc.roundToInt().toString()
        accEl.setTextColor(
            when {
                acc < 5 -> Color.parseColor("#39ff14")
                acc < 20 -> Color.parseColor("#ffb347")
                else -> Color.parseColor("#ff5533")
            }
        )
        updateAccSub()
    }

    /** Accuracy sub-label: fix quality, with the dual-band badge when L1+L5. */
    private fun updateAccSub() {
        val quality = when {
            !gpsReady -> "metres"
            lastAcc <= 5 -> "EXCELLENT"
            lastAcc <= 10 -> "GOOD"
            else -> "FAIR"
        }
        binding.giAccSub.text = if (dualBand && gpsReady) "L1+L5 · $quality" else quality
    }

    private fun updateFixAge() {
        val el = binding.giAge
        if (lastFixPerf == 0L) {
            el.text = "—"
            el.setTextColor(Color.parseColor("#f0f0f0"))
            return
        }
        val age = (SystemClock.elapsedRealtime() - lastFixPerf) / 1000.0
        el.text = String.format("%.1f", age)
        el.setTextColor(
            when {
                age < 1.5 -> Color.parseColor("#39ff14")
                age < 3 -> Color.parseColor("#ffb347")
                else -> Color.parseColor("#ff5533")
            }
        )
    }

    private fun setSdot(cls: String) {
        binding.sdot.setBackgroundResource(
            when (cls) {
                "active" -> R.drawable.dot_green
                "warn" -> R.drawable.dot_amber
                "error" -> R.drawable.dot_red
                else -> R.drawable.dot_gray
            }
        )
    }

    private fun toggleUnit() {
        useMetric = !useMetric
        store.useMetric = useMetric
        val u = if (useMetric) "KM/H" else "MPH"
        binding.spdUnit.text = "$u ⇄"
        binding.tgtSuf.text = u
        allTimeTopSpd = 0.0
        binding.topSpdVal.text = "0"
        binding.topSpdUnit.text = u
        binding.gauge.useMetric = useMetric
        resetRun()
    }

    // ── Timed run ───────────────────────────────────────────────
    private fun armRun() {
        val tgt = tgtVal()
        if (tgt <= 0) {
            Toast.makeText(this, "Enter a valid target speed", Toast.LENGTH_SHORT).show()
            return
        }
        state = RunState.ARMED
        binding.timerBig.text = "0.000"
        binding.timerBig.setTextColor(Color.parseColor("#ffb347"))
        binding.timerBig.setShadowLayer(20f * resources.displayMetrics.density, 0f, 0f, Color.parseColor("#ffb347"))
        startArmFlash()
        setProg(0.0)
        binding.progFill.setBackgroundResource(R.drawable.prog_fill)
        binding.armBtn.text = "ARMED"
        setBtn(binding.armBtn, false)
        setBtn(binding.resetBtn, true)
        binding.tgtInp.isEnabled = false
        binding.gauge.target = tgt
        binding.gauge.showTarget = true
    }

    /** Amber flash on the timer while a run is ARMED and waiting to start. */
    private fun startArmFlash() {
        flashAnimator?.cancel()
        binding.timerBig.alpha = 1f
        flashAnimator = android.animation.ObjectAnimator.ofFloat(binding.timerBig, "alpha", 1f, 0.2f).apply {
            duration = 500
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            start()
        }
    }

    private fun startRun() {
        state = RunState.RUNNING
        flashAnimator?.cancel()
        binding.timerBig.alpha = 1f
        binding.timerBig.setTextColor(Color.parseColor("#ffb347"))
        binding.timerBig.setShadowLayer(20f * resources.displayMetrics.density, 0f, 0f, Color.parseColor("#ffb347"))
        uiHandler.post(timerTick)
    }

    private fun finishRun(elapsedMs: Long) {
        state = RunState.DONE
        uiHandler.removeCallbacks(timerTick)
        binding.timerBig.text = fmtTimer(elapsedMs)
        binding.timerBig.setTextColor(Color.parseColor("#39ff14"))
        binding.timerBig.setShadowLayer(20f * resources.displayMetrics.density, 0f, 0f, Color.parseColor("#39ff14"))
        binding.progFill.setBackgroundResource(R.drawable.prog_fill_done)
        setProg(1.0)
        binding.armBtn.text = "START TIMED RUN"
        setBtn(binding.armBtn, true)
        binding.tgtInp.isEnabled = true
        binding.gauge.showTarget = false
        val u = if (useMetric) "km/h" else "mph"
        val runLbl = "0 → ${trimNum(tgtVal())} $u"
        saveRecord(elapsedMs, runLbl)
        renderHistory()
        renderBvL()
    }

    private fun resetRun() {
        state = RunState.IDLE
        runStartT = null
        uiHandler.removeCallbacks(timerTick)
        flashAnimator?.cancel()
        binding.timerBig.alpha = 1f
        binding.timerBig.text = "0.000"
        binding.timerBig.setTextColor(Color.WHITE)
        binding.timerBig.setShadowLayer(15f * resources.displayMetrics.density, 0f, 0f, Color.parseColor("#59ff6400"))
        setProg(0.0)
        binding.progFill.setBackgroundResource(R.drawable.prog_fill)
        binding.armBtn.text = "START TIMED RUN"
        setBtn(binding.armBtn, true)
        setBtn(binding.resetBtn, false)
        binding.tgtInp.isEnabled = true
        binding.gauge.showTarget = false
    }

    private fun setProg(ratio: Double) {
        val r = ratio.coerceIn(0.0, 1.0)
        val fillLp = binding.progFill.layoutParams as LinearLayout.LayoutParams
        val spacerLp = binding.progSpacer.layoutParams as LinearLayout.LayoutParams
        fillLp.weight = r.toFloat()
        spacerLp.weight = (1.0 - r).toFloat()
        binding.progFill.layoutParams = fillLp
        binding.progSpacer.layoutParams = spacerLp
    }

    private fun saveRecord(elapsedMs: Long, runLbl: String) {
        val now = Date()
        runs.add(
            0, RunRecord(
                id = System.currentTimeMillis(),
                runLbl = runLbl,
                timeMs = elapsedMs,
                time = fmtTimer(elapsedMs),
                isBest = false,
                date = runDate(now)
            )
        )
        if (runs.size > 50) runs = runs.subList(0, 50)
        // Best = fastest per label, flagged only when the label has 2+ runs.
        val bestMap = mutableMapOf<String, Long>()
        for (r in runs) {
            val cur = bestMap[r.runLbl]
            if (cur == null || r.timeMs < cur) bestMap[r.runLbl] = r.timeMs
        }
        runs = runs.map { r ->
            r.copy(
                isBest = r.timeMs == bestMap[r.runLbl] &&
                    runs.count { it.runLbl == r.runLbl } > 1
            )
        }.toMutableList()
        store.runs = runs
    }

    // ── Ride session ────────────────────────────────────────────
    private fun toggleSession() {
        if (!sessionActive) startSession() else stopSession()
    }

    private fun startSession() {
        sessionActive = true
        sessionStart = Date()
        sessionTopSpd = 0.0
        sessionDist = 0.0
        sessionLastPos = null
        sessionRoute = mutableListOf()

        binding.sessBtn.text = "STOP RIDE"
        binding.sessBtn.setBackgroundResource(R.drawable.btn_red)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            binding.sessBtn.outlineSpotShadowColor = Color.parseColor("#e53935")
            binding.sessBtn.outlineAmbientShadowColor = Color.parseColor("#e53935")
        }
        stopPulseAnimator?.cancel()
        stopPulseAnimator = android.animation.ObjectAnimator.ofFloat(binding.sessBtn, "alpha", 1f, 0.8f).apply {
            duration = 1000
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            start()
        }
        binding.sessLive.visibility = View.VISIBLE
        applyChromeVisibility()
        uiHandler.post(liveTick)
        resetIdleTimer()
    }

    private fun stopSession() {
        if (!sessionActive) return
        val endTime = Date()
        val startSnap = sessionStart ?: endTime
        val durSec = Math.round((endTime.time - startSnap.time) / 1000.0).toInt()
        val topSpdSnap = sessionTopSpd
        val distSnap = sessionDist

        sessionActive = false
        uiHandler.removeCallbacks(liveTick)
        uiHandler.removeCallbacks(idleStop)
        stopPulseAnimator?.cancel()
        binding.sessBtn.alpha = 1f
        binding.sessBtn.text = "START RIDE"
        binding.sessBtn.setBackgroundResource(R.drawable.btn_green)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            binding.sessBtn.outlineSpotShadowColor = Color.parseColor("#00c853")
            binding.sessBtn.outlineAmbientShadowColor = Color.parseColor("#00c853")
        }
        binding.sessLive.visibility = View.GONE
        applyChromeVisibility()

        if (durSec < 5) {
            Toast.makeText(this, "Ride too short — not saved", Toast.LENGTH_SHORT).show()
            return
        }

        val u = if (useMetric) "km/h" else "mph"
        val dist = if (useMetric) {
            String.format("%.2f km", distSnap / 1000.0)
        } else {
            String.format("%.2f mi", distSnap / 1609.34)
        }
        val record = RideRecord(
            id = System.currentTimeMillis(),
            name = rideName(startSnap),
            topSpd = "${topSpdSnap.roundToInt()} $u",
            topSpdNum = topSpdSnap,
            dist = dist,
            dur = fmtDur(durSec),
            ts = isoNow(startSnap),
            route = sessionRoute.toList()
        )
        // Save first, then re-read and render immediately — the log updates
        // live the moment STOP is hit, no restart needed.
        rides.add(0, record)
        if (rides.size > 100) rides = rides.subList(0, 100)
        store.rides = rides
        rides = store.rides
        renderSessions()
        binding.scrollView.post {
            binding.scrollView.scrollTo(
                0, binding.rideLogSection.top + binding.innerCol.top
            )
        }
        Toast.makeText(
            this, "Ride saved ✓ — ${rides.size} ride${if (rides.size == 1) "" else "s"}",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateSessionLive() {
        val start = sessionStart ?: return
        val durSec = Math.round((Date().time - start.time) / 1000.0).toInt()
        val u = if (useMetric) "km/h" else "mph"
        val dist = if (useMetric) {
            String.format("%.2f km", sessionDist / 1000.0)
        } else {
            String.format("%.2f mi", sessionDist / 1609.34)
        }
        binding.liveTopSpd.text = "${sessionTopSpd.roundToInt()} $u"
        binding.liveDist.text = dist
        binding.liveDur.text = fmtDur(durSec)
    }

    private fun resetIdleTimer() {
        uiHandler.removeCallbacks(idleStop)
        uiHandler.postDelayed(idleStop, 5 * 60 * 1000L)
    }

    // ── Logs ────────────────────────────────────────────────────
    private fun renderSessions() {
        binding.sessList.removeAllViews()
        binding.noSess.visibility = if (rides.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        for (ride in rides) {
            val row = ItemRideBinding.inflate(inflater, binding.sessList, false)
            row.rideName.text = ride.name
            row.rideMeta.text = "${ride.dur}   ${ride.dist}"
            row.rideTopSpd.text = ride.topSpd
            row.root.setOnClickListener { openRideDetail(ride) }
            row.rideDel.setOnClickListener {
                rides = rides.filter { it.id != ride.id }.toMutableList()
                store.rides = rides
                renderSessions()
            }
            binding.sessList.addView(row.root)
        }
    }

    private fun renderHistory() {
        binding.histList.removeAllViews()
        binding.noHist.visibility = if (runs.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        for (run in runs) {
            val row = ItemRunBinding.inflate(inflater, binding.histList, false)
            row.runLbl.text = run.runLbl
            row.runDate.text = run.date
            row.runTime.text = "${run.time}s"
            row.runBestBadge.visibility = if (run.isBest) View.VISIBLE else View.GONE
            row.root.setBackgroundResource(if (run.isBest) R.drawable.log_item_best_bg else R.drawable.log_item_bg)
            row.runTime.setTextColor(
                if (run.isBest) Color.parseColor("#ffb347") else Color.parseColor("#f0f0f0")
            )
            row.root.setOnClickListener { openRunDetail(run) }
            row.runDel.setOnClickListener {
                runs = runs.filter { it.id != run.id }.toMutableList()
                store.runs = runs
                renderHistory()
                renderBvL()
            }
            binding.histList.addView(row.root)
        }
    }

    private fun renderBvL() {
        val card = binding.bvlCard
        if (runs.isEmpty()) {
            card.visibility = View.GONE
            return
        }
        card.visibility = if (isLandscape() || fullscreen) View.GONE else View.VISIBLE
        val last = runs[0]
        val same = runs.filter { it.runLbl == last.runLbl }
        val best = same.minByOrNull { it.timeMs } ?: last
        binding.bvlLastRun.text = last.runLbl
        binding.bvlLastTime.text = "${last.time}s"
        binding.bvlBestRun.text = best.runLbl
        binding.bvlBestTime.text = "${best.time}s"
        val delta = binding.bvlDelta
        val diff = last.timeMs - best.timeMs
        when {
            abs(diff) < 10 -> {
                delta.text = "= SAME"
                delta.setTextColor(Color.parseColor("#cccccc"))
            }
            diff < 0 -> {
                delta.text = "▲ ${fmtTimer(abs(diff))}s FASTER"
                delta.setTextColor(Color.parseColor("#39ff14"))
            }
            else -> {
                delta.text = "▼ ${fmtTimer(diff)}s SLOWER"
                delta.setTextColor(Color.parseColor("#ff5533"))
            }
        }
    }

    private fun openRideDetail(ride: RideRecord) {
        val rows = mutableListOf(
            "Date" to ride.name,
            "Duration" to ride.dur,
            "Distance" to ride.dist,
            "Top Speed" to ride.topSpd
        )
        rideAvgSpeedRow(ride)?.let { rows += it }
        LogDetailDialog.show(
            this, "RIDE LOG", ride.topSpd, "TOP SPEED", rows,
            onViewRoute = if (ride.route.size > 1) {
                { RouteMapDialog.show(this, ride) }
            } else null
        )
    }

    private fun openRunDetail(run: RunRecord) {
        LogDetailDialog.show(
            this, "TIMED RUN", "${run.time}s", "0 → TARGET TIME",
            listOf(
                "Run" to run.runLbl,
                "Date" to run.date,
                "Result" to if (run.isBest) "★ BEST" else "—"
            )
        )
    }

    private fun clearSessions() {
        rides = mutableListOf()
        store.rides = rides
        renderSessions()
    }

    private fun clearHistory() {
        runs = mutableListOf()
        store.runs = runs
        renderHistory()
        renderBvL()
    }

    // ── Speed alert ─────────────────────────────────────────────
    private fun saveAlert() {
        val v = binding.alertInp.text.toString().toDoubleOrNull() ?: 0.0
        alertMph = if (v.isNaN()) 0.0 else v
        store.alertMph = alertMph
        binding.alertHint.text =
            if (alertMph > 0) "on — over ${trimNum(alertMph)} mph" else "off"
        checkSpeedAlert(currentSpd)
    }

    /** Alert fires on true GPS speed, never on the IMU interpolation. */
    private fun checkSpeedAlert(spdDisplayUnits: Double) {
        val banner = binding.alertBanner
        if (alertMph <= 0) {
            banner.visibility = View.GONE
            alertFlashAnimator?.cancel()
            alertOn = false
            return
        }
        val limit = if (useMetric) alertMph * 1.60934 else alertMph
        val over = spdDisplayUnits >= limit
        if (over && !alertOn) {
            alertOn = true
            banner.text = "⚠ OVER ${trimNum(alertMph)} ${if (useMetric) "KM/H" else "MPH"}"
            banner.visibility = View.VISIBLE
            alertFlashAnimator?.cancel()
            alertFlashAnimator = android.animation.ObjectAnimator.ofFloat(banner, "alpha", 1f, 0.55f).apply {
                duration = 500
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                start()
            }
            beep()
        } else if (!over && spdDisplayUnits < limit - 2) {
            alertOn = false
            alertFlashAnimator?.cancel()
            banner.alpha = 1f
            banner.visibility = View.GONE
        }
    }

    private fun beep() {
        try {
            if (toneGen == null) {
                toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            }
            toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, 250)
        } catch (_: Exception) {
        }
    }

    // ── Export (Storage Access Framework — user picks where it lands) ──
    private fun csvEscape(v: String): String =
        "\"" + v.replace("\"", "\"\"") + "\""

    private fun exportRides(format: String) {
        if (rides.isEmpty()) {
            Toast.makeText(this, "No rides to export", Toast.LENGTH_SHORT).show()
            return
        }
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(Date())
        if (format == "csv") {
            val rows = mutableListOf("Date,Duration,Distance,Top Speed")
            for (s in rides) {
                rows += listOf(s.name, s.dur, s.dist, s.topSpd)
                    .joinToString(",") { csvEscape(it) }
            }
            launchExport("samo-ride-log-$date.csv", "text/csv", rows.joinToString("\n"))
        } else {
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            sb.append("<gpx version=\"1.1\" creator=\"#SAMO GPS\" xmlns=\"http://www.topografix.com/GPX/1/1\">")
            for (s in rides) {
                if (s.route.isEmpty()) continue
                sb.append("<trk><name>").append(escapeXml(s.name)).append("</name><trkseg>")
                for (p in s.route) {
                    sb.append("<trkpt lat=\"").append(p.lat).append("\" lon=\"").append(p.lon)
                        .append("\"><time>")
                        .append(java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                            timeZone = java.util.TimeZone.getTimeZone("UTC")
                        }.format(Date(p.t)))
                        .append("</time></trkpt>")
                }
                sb.append("</trkseg></trk>")
            }
            sb.append("</gpx>")
            launchExport("samo-ride-log-$date.gpx", "application/gpx+xml", sb.toString())
        }
    }

    private fun exportRuns() {
        if (runs.isEmpty()) {
            Toast.makeText(this, "No runs to export", Toast.LENGTH_SHORT).show()
            return
        }
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(Date())
        val rows = mutableListOf("Date,Run,Time (s),Best")
        for (r in runs) {
            rows += listOf(r.date, r.runLbl, r.time, if (r.isBest) "YES" else "")
                .joinToString(",") { csvEscape(it) }
        }
        launchExport("samo-timed-runs-$date.csv", "text/csv", rows.joinToString("\n"))
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun launchExport(fileName: String, mime: String, content: String) {
        pendingExport = content
        pendingExportMime = mime
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
        try {
            exporting = true
            startActivityForResult(intent, REQ_EXPORT)
        } catch (_: Exception) {
            exporting = false
            pendingExport = null
            Toast.makeText(this, "No file app available to save with", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_EXPORT) return
        exporting = false
        if (resultCode != RESULT_OK) {
            pendingExport = null
            return
        }
        val uri: Uri? = data?.data
        val content = pendingExport
        pendingExport = null
        if (uri != null && content != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(this, "Export saved", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
