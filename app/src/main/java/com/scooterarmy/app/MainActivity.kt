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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
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
import java.util.Date
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

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
    private lateinit var settings: AppSettings
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
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {
            gpsReady = false
            setGpsStatus("ERROR", "#ff5533")
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
    private var state = RunState.IDLE
    private var currentSpd = 0.0        // mph, raw GPS
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
    private var lastBearingDeg: Float? = null
    private var lastAlt: Double? = null

    // ── Live map (Esri dark tiles, no key) ──────────────────────
    private var liveMapInit = false
    private var liveRoute = mutableListOf<GeoPoint>()
    private var liveLine: Polyline? = null
    private var liveStartMarker: Marker? = null
    private var liveEndMarker: Marker? = null
    private var mapShown = false
    private var liveCentered = false

    // ── Pages: gauge (home) / map (swipe right) / stats (swipe left) ──
    private enum class Page { GAUGE, MAP, STATS }
    private var currentPage = Page.GAUGE

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
                if (binding.landPanel.visibility == View.VISIBLE) {
                    binding.landRunTimer.text = binding.timerBig.text
                }
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

    // Same idea for the Settings screen: opening it pauses us, but the
    // run/ride continues and Settings applies live on return.
    private var openingSettings = false
    // Same again for the Logs page: opening it pauses us, but the ride
    // underneath keeps recording — the logs page holds no session state.
    // (Export moved to the logs page with it, and LogsActivity carries
    // its own exporting flag for the file picker.)
    private var openingLogs = false
    // Diagnostics: fix timestamps in the last 5 s + last fix interval.
    private val fixTimes = ArrayDeque<Long>()
    private var lastFixIntervalMs = 0L

    companion object {
        const val ACC_REJECT = 50.0      // reject fixes worse than this (metres)
        const val SIG_GOOD = 3           // minimum signal bars (of 6) before unlock
        // Lock gate + speed-accuracy reject + spike guard now live in
        // AppSettings (defaults 10.0 m / 1.0 m/s / on) — same values as
        // the old ACC_GOOD / SPD_ACC_REJECT constants.
        const val REQ_LOCATION = 1
    }

    // ── Lifecycle ───────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = LogStore(this)
        settings = AppSettings(this)
        imuBridge = ImuBridge(this)
        imuBridge.paramsProvider = { settings.bridgeParams }
        RouteMapDialog.configure(this)

        alertMph = store.alertMph
        rides = store.rides
        runs = store.runs

        applyTexturedBackground()
        // Ember glows (density-scaled so they render the same on every screen).
        val density = resources.displayMetrics.density
        binding.spdNum.setShadowLayer(20f * density, 0f, 0f, Color.parseColor("#ff4400"))
        binding.timerBig.setShadowLayer(15f * density, 0f, 0f, Color.parseColor("#59ff6400"))
        binding.tgtSuf.text = "MPH"
        if (alertMph > 0) {
            binding.alertInp.setText(trimNum(alertMph))
            binding.alertHint.text = "on — over ${trimNum(alertMph)} mph"
        }

        binding.sessBtn.setOnClickListener { toggleSession() }
        binding.armBtn.setOnClickListener { armRun() }
        binding.resetBtn.setOnClickListener { resetRun() }
        // Landscape trip panel STOP — same action as the portrait STOP RIDE.
        binding.landStopBtn.setOnClickListener { toggleSession() }
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
        // Gear (Settings, top-center in the chips row) + LOGS link
        // (bottom-left) — they do not dominate the full-screen gauge,
        // per the mockup-v4 brief.
        binding.gearBtn.setOnClickListener {
            openingSettings = true
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.logsLink.setOnClickListener {
            openingLogs = true
            startActivity(Intent(this, LogsActivity::class.java))
        }
        binding.statsBackLink.setOnClickListener { showPage(Page.GAUGE) }
        binding.mapBackLink.setOnClickListener { showPage(Page.GAUGE) }
        // Swipe between the three full screens: RIGHT → live map,
        // LEFT → stats; swipe back the other way to return to the gauge.
        // Vertical scrolls (stats page) and map pan/zoom are untouched —
        // only a mostly-horizontal fling switches pages.
        val pageGesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float
            ): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) > 120 && abs(vx) > 200 && abs(dx) > abs(dy)) {
                    when (currentPage) {
                        Page.GAUGE -> showPage(if (dx > 0) Page.MAP else Page.STATS)
                        Page.MAP -> if (dx < 0) showPage(Page.GAUGE)
                        Page.STATS -> if (dx > 0) showPage(Page.GAUGE)
                    }
                    return true
                }
                return false
            }
        })
        binding.rootLayout.setOnTouchListener { _, event ->
            pageGesture.onTouchEvent(event)
        }
        setupLiveMap()
        // Timed-run target starts at the Settings default (45 mph out of the box).
        binding.tgtInp.setText(trimNum(settings.defaultTarget))

        val gesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleFullscreen()
                return true
            }
        })
        binding.gaugeWrap.setOnTouchListener { _, event ->
            gesture.onTouchEvent(event)
        }

        renderBvL()
        updateSpeedDisplay()
        applySettings()
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
        openingSettings = false
        openingLogs = false
        applySettings()
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
        // Fresh read, exactly what is stored — the Best-vs-Last card and
        // the landscape panel's last-ride line always show the truth.
        rides = store.rides
        runs = store.runs
        renderBvL()
        updateLandPanel()
        updateStatCards()
        if (liveMapInit) binding.liveMap.onResume()
    }

    override fun onPause() {
        super.onPause()
        // Same as the web app hiding: a ride ends when the app leaves the screen.
        // Except the export file picker and the Settings screen — those also
        // pause us, and in the web app exporting was a plain download that
        // never ended a ride; Settings returns to the same run/ride.
        if (pauseEndsRide()) stopSession()
        stopLocation()
        imuBridge.stop()
        if (liveMapInit) binding.liveMap.onPause()
        uiHandler.removeCallbacks(timerTick)
        uiHandler.removeCallbacks(fixAgeTick)
        uiHandler.removeCallbacks(liveTick)
        uiHandler.removeCallbacks(idleStop)
        uiHandler.removeCallbacks(displayTick)
    }

    override fun onDestroy() {
        super.onDestroy()
        // The three pulse/flash animators repeat forever; if any is still
        // running it holds its View (and the Activity) past destroy.
        flashAnimator?.cancel()
        alertFlashAnimator?.cancel()
        stopPulseAnimator?.cancel()
        flashAnimator = null
        alertFlashAnimator = null
        stopPulseAnimator = null
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
                setGpsStatus("ERROR", "#ff5533")
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
        // Landscape (not fullscreen): gauge on the LEFT, live trip panel
        // on the RIGHT — same gauge-frame style, just rearranged.
        // Fullscreen stays pure dial. Both force the gauge page.
        val landscapePanel = isLandscape() && !fullscreen
        val gone = View.GONE
        val vis = View.VISIBLE
        showPage(currentPage)
        binding.chipsRow1.visibility = if (fullscreen) gone else vis
        binding.chipsRow2.visibility = if (fullscreen) gone else vis
        binding.bottomCards.visibility = if (fullscreen) gone else vis
        binding.fsHint.visibility = if (fullscreen) vis else gone
        binding.fsInfoRow.visibility = if (fullscreen) vis else gone
        binding.gpsWait.visibility =
            if (fullscreen) gone else if (gpsReady) gone else vis
        binding.landPanel.visibility = if (landscapePanel) vis else gone
        binding.odometer.visibility = if (landscapePanel) vis else gone
        binding.gaugeRow.orientation =
            if (landscapePanel) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        binding.gaugeRow.gravity =
            if (landscapePanel) Gravity.CENTER_VERTICAL else Gravity.CENTER

        // Gauge size: the dial fills the display like frame-11 — nearly
        // full width in portrait, square to the short side in landscape.
        val dm = resources.displayMetrics
        val wPx = dm.widthPixels
        val hPx = dm.heightPixels
        val (gw, gh) = when {
            fullscreen && !isLandscape() ->
                (min((wPx * 0.96).toInt(), (hPx * 0.80).toInt()) to
                    min((wPx * 0.96).toInt(), (hPx * 0.80).toInt()))
            landscapePanel -> {
                val sq = min(wPx, hPx) - (32 * dm.density).toInt()
                sq to sq
            }
            speedoOnly -> {
                val s = min(wPx, hPx) - (16 * dm.density).toInt()
                s to s
            }
            else -> {
                val s = min((wPx * 0.94).toInt(), (hPx * 0.58).toInt())
                s to s
            }
        }
        val lp = binding.gaugeWrap.layoutParams as LinearLayout.LayoutParams
        lp.width = gw
        lp.height = gh
        lp.gravity =
            if (landscapePanel) Gravity.CENTER_VERTICAL else Gravity.CENTER_HORIZONTAL
        binding.gaugeWrap.layoutParams = lp
        val landLp = binding.landPanel.layoutParams as LinearLayout.LayoutParams
        landLp.width =
            if (landscapePanel) 0 else LinearLayout.LayoutParams.MATCH_PARENT
        landLp.weight = if (landscapePanel) 1f else 0f
        landLp.height =
            if (landscapePanel) LinearLayout.LayoutParams.MATCH_PARENT
            else LinearLayout.LayoutParams.WRAP_CONTENT
        binding.landPanel.layoutParams = landLp

        // Speed number + dial overlay scale with the gauge: the heading
        // sits near the top inside the dial, "mph" centered just below
        // the digits. Seven-segment digits at 150sp portrait:
        // the mockup's bigger digits only work because its numbers are
        // bunched at the top (AI) — on a true dial 150sp is the largest
        // size whose corners clear every tick number (0/40/100/140).
        binding.spdNum.setTextSize(
            TypedValue.COMPLEX_UNIT_SP, if (speedoOnly) 155f else 150f
        )
        binding.gaugeWrap.post {
            val h = binding.gaugeWrap.height
            if (h > 0) {
                val headLp =
                    binding.dialHeading.layoutParams as android.widget.FrameLayout.LayoutParams
                headLp.topMargin = (h * 0.165).toInt()
                binding.dialHeading.layoutParams = headLp
                positionUnit()
            }
        }
        if (fullscreen) updateFsInfo()
        updateLandPanel()
    }

    private var lastUnitDigits = -1

    /** "mph" sits centered just below the seven-segment digits: on a
     *  true dial the 140 number sits exactly where a right-aligned
     *  mph would go. Re-run whenever the digit count changes. */
    private fun positionUnit() {
        val h = binding.gaugeWrap.height
        val digitW = binding.spdNum.width
        val digitH = binding.spdNum.height
        val unitW = binding.spdUnit.width
        if (h <= 0 || digitW <= 0 || digitH <= 0 || unitW <= 0) return
        val unitLp =
            binding.spdUnit.layoutParams as android.widget.FrameLayout.LayoutParams
        unitLp.leftMargin = 0
        unitLp.topMargin =
            (h + digitH) / 2 + (4 * resources.displayMetrics.density).toInt()
        binding.spdUnit.layoutParams = unitLp
        lastUnitDigits = binding.spdNum.text.length
    }

    /** Landscape trip panel (Speedo-style): live ride numbers beside the
     *  gauge while a ride runs; top speed + GPS status + last ride when
     *  no ride is running; timed-run target + timer when one is armed or
     *  running. No-op unless the panel is actually showing. */
    private fun updateLandPanel() {
        if (!::binding.isInitialized) return
        if (binding.landPanel.visibility != View.VISIBLE) return
        if (!isLandscape() || fullscreen) return
        if (sessionActive) {
            binding.landTitle.text = "RIDE LIVE"
            binding.landTitle.setTextColor(Color.parseColor("#39ff14"))
            val start = sessionStart
            val durSec = if (start != null)
                Math.round((Date().time - start.time) / 1000.0).toInt() else 0
            val distMi = sessionDist / 1609.34
            binding.landDist.text = String.format("%.2f mi", distMi)
            binding.landTime.text = fmtDur(durSec)
            binding.landTopSpd.text = "${sessionTopSpd.roundToInt()} mph"
            val hrs = durSec / 3600.0
            binding.landAvgSpd.text =
                if (hrs > 0) String.format("%.1f mph", distMi / hrs) else "0.0 mph"
            binding.landStopBtn.visibility = View.VISIBLE
            binding.landLastRide.visibility = View.GONE
        } else {
            binding.landTitle.text = "TRIP"
            binding.landTitle.setTextColor(Color.parseColor("#cccccc"))
            binding.landDist.text = "—"
            binding.landTime.text = "—"
            binding.landTopSpd.text = "${allTimeTopSpd.roundToInt()} mph"
            val last = rides.firstOrNull()
            binding.landAvgSpd.text =
                last?.let { rideAvgSpeedRow(it)?.second } ?: "—"
            binding.landStopBtn.visibility = View.GONE
            if (last != null) {
                binding.landLastRide.visibility = View.VISIBLE
                binding.landLastRide.text =
                    "LAST RIDE — ${last.dist} · ${last.dur} · TOP ${last.topSpd}"
            } else {
                binding.landLastRide.visibility = View.GONE
            }
        }
        // GPS line: fix quality + satellites + accuracy + quality label.
        val quality = when {
            !gpsReady -> "—"
            lastAcc <= 5 -> "EXCELLENT"
            lastAcc <= 10 -> "GOOD"
            else -> "FAIR"
        }
        val status = when {
            lastSig >= 3 -> "LOCKED"
            lastSig >= 1 -> "WEAK"
            else -> "POOR"
        }
        val sats = if (satUsed >= 0) "$satUsed SATS" else "— SATS"
        val acc = if (lastAcc < 900) "±${lastAcc.roundToInt()}m" else "±—"
        binding.landGps.text = "$status · $sats · $acc · $quality"
        // Timed run: target + timer state when armed, running, or done.
        if (state == RunState.IDLE) {
            binding.landRunRow.visibility = View.GONE
        } else {
            binding.landRunRow.visibility = View.VISIBLE
            binding.landRunLbl.text = "0 → ${trimNum(tgtVal())} mph"
            binding.landRunTimer.text = when (state) {
                RunState.ARMED -> "ARMED — WAITING"
                RunState.RUNNING ->
                    if (runStartT != null) fmtTimer(max(0L, nowT() - runStartT!!))
                    else "0.000"
                else -> binding.timerBig.text.toString()
            }
        }
        // Landscape odometer under the dial: trip distance, car-odometer style.
        val odoMi = if (sessionActive) sessionDist / 1609.34
            else rides.firstOrNull()?.let { parseDistMi(it.dist) } ?: 0.0
        binding.odometer.text = String.format("%07.1f", odoMi)
    }

    /** Root background: ember glow + carbon-fiber weave covering the WHOLE
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
        // Carbon-fiber weave tile (12x12dp, 2x2 twill like the mockup):
        // alternating horizontal- and vertical-sheen cells, dark gaps.
        // The mockup's weave is fine — 24dp cells read too coarse.
        val w = (12f * density).toInt().coerceAtLeast(2)
        val h = (12f * density).toInt().coerceAtLeast(2)
        val tile = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(tile)
        val cell = w / 2f
        val horiz = LinearGradient(
            0f, 0f, 0f, cell,
            Color.parseColor("#191919"), Color.parseColor("#0b0b0b"),
            Shader.TileMode.CLAMP
        )
        val vert = LinearGradient(
            0f, 0f, cell, 0f,
            Color.parseColor("#191919"), Color.parseColor("#0b0b0b"),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, cell, cell, Paint().apply { shader = horiz })
        canvas.drawRect(cell, 0f, w.toFloat(), cell, Paint().apply { shader = vert })
        canvas.drawRect(0f, cell, cell, h.toFloat(), Paint().apply { shader = vert })
        canvas.drawRect(cell, cell, w.toFloat(), h.toFloat(), Paint().apply { shader = horiz })
        val gridPaint = Paint().apply {
            color = Color.parseColor("#050505")
            strokeWidth = 1f * density
        }
        canvas.drawLine(cell, 0f, cell, h.toFloat(), gridPaint)
        canvas.drawLine(0f, cell, w.toFloat(), cell, gridPaint)
        val tiled = BitmapDrawable(resources, tile).apply {
            tileModeX = Shader.TileMode.REPEAT
            tileModeY = Shader.TileMode.REPEAT
        }
        // Wide ember glow, bottom center — reaches ~85% up the screen.
        val wideGlow = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setGradientCenter(0.5f, 1.0f)
            gradientRadius = screenH * 0.85f
            colors = intArrayOf(Color.parseColor("#28c83c00"), Color.TRANSPARENT)
        }
        // Hotter orange core, bottom center.
        val coreGlow = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setGradientCenter(0.5f, 1.0f)
            gradientRadius = screenH * 0.52f
            colors = intArrayOf(Color.parseColor("#18ff6400"), Color.TRANSPARENT)
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

    /** A pause ends a ride — unless the pause is the Settings screen or
     *  the Logs page, both of which return to the same run/ride. */
    private fun pauseEndsRide(): Boolean =
        sessionActive && !openingSettings && !openingLogs

    /** Apply everything Settings can change, live — no restart needed. */
    private fun applySettings() {
        // NOTE (frame-11, Nov 23 01:26): the Reel dial scale is FIXED at
        // 0–170 mph — settings.gaugeMax (100/140/180) does NOT rescale
        // it. The setting is still stored and shown in Settings, but it
        // is inert for this dial; changing it must not move the frame's
        // numbers. (The old gauge-scale assignment was deleted here.)
        binding.diagRow.visibility = if (settings.showDiagnostics) View.VISIBLE else View.GONE
        if (settings.showDiagnostics) updateFixAge()
    }

    // ── Unit conversion: mph only ───────────────────────────────
    // GPS works in m/s internally; everything the user sees is mph.
    private fun toMps(mph: Double): Double = mph / 2.23694

    private fun fromMps(mps: Double): Double = mps * 2.23694

    // (The old scale helper was removed Nov 23 01:26 — the Reel dial
    // scale is fixed at 170 mph per frame-11; settings.gaugeMax no
    // longer drives it.)

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
                setGpsStatus("ERROR", "#ff5533")
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
                bearingDeg = if (l.hasBearing()) l.bearing else null,
                alt = if (l.hasAltitude()) l.altitude else null
            )
        )
    }

    /** Network (cell/Wi-Fi) fix: coarse position while GPS is still locking. */
    private fun onNetworkFix(l: Location) {
        if (gpsReady) return
        val acc = if (l.hasAccuracy()) l.accuracy else 999f
        setGpsStatus("ASSISTED", "#cccccc")
        binding.chipAcc.text = "Accuracy ${acc.roundToInt()}m"
        binding.chipAcc.setTextColor(Color.parseColor("#ffb347"))
    }

    /** Fused fix: fast lock assist only. Never speed, never run timing. */
    private fun onFusedFix(l: Location) {
        if (gpsReady) return
        val acc = if (l.hasAccuracy()) l.accuracy else 999f
        setGpsStatus("ASSISTED", "#cccccc")
        binding.chipAcc.text = "Accuracy ${acc.roundToInt()}m"
        binding.chipAcc.setTextColor(Color.parseColor("#ffb347"))
    }

    private fun handleFix(f: FixData) {
        val perf = SystemClock.elapsedRealtime()
        val acc = f.acc.toDouble()
        if (fixPrev != null && f.t <= fixPrev!!.t) return // duplicate / out-of-order

        // Diagnostics: fix rate + last interval (shown when enabled in Settings).
        if (fixPrev != null) lastFixIntervalMs = f.t - fixPrev!!.t
        fixTimes.addLast(perf)
        while (fixTimes.isNotEmpty() && fixTimes.first() < perf - 5000L) fixTimes.removeFirst()

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
        val badSpd = f.speedAcc != null && f.speedAcc > settings.spdReject
        // Spike guard: >12 m/s (~27 mph) in <1.5 s is implausible — GPS glitch.
        // Can be switched off in Settings; on is the default.
        val spike = settings.spikeGuard && fixPrev != null && mps >= 0 &&
            abs(mps - fixPrev!!.mps) > 12 && (f.t - fixPrev!!.t) < 1500
        val usable = mps >= 0 && acc <= ACC_REJECT && !badSpd && !spike

        // During a run, drop unusable fixes without touching timing references.
        if (state == RunState.RUNNING && !usable) {
            updateSignalDisplay(acc)
            return
        }

        lastFixPerf = perf
        lastFixT = f.t

        if (acc <= settings.lockGate && sig >= SIG_GOOD) gpsReady = true
        if (gpsReady && usable) {
            rawMps = mps
            currentSpd = fromMps(mps)
            f.bearingDeg?.let {
                imuBridge.setBearingDeg(it)
                lastBearingDeg = it
            }
            f.alt?.let { lastAlt = it }
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

            if (state == RunState.ARMED && mps > toMps(settings.armMph)) {
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

        if (sessionActive) updateLiveMap(f.lat, f.lon)
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

        binding.spdNum.text = disp.roundToInt().toString()
        if (binding.spdNum.text.length != lastUnitDigits) {
            binding.spdUnit.post { positionUnit() }
        }
        // Always ember orange like the frame — the huge speed number is
        // the dial's hero, not a warning scale.
        binding.spdNum.setTextColor(Color.parseColor("#ff6a00"))
        binding.spdNum.setShadowLayer(20f * resources.displayMetrics.density, 0f, 0f, Color.parseColor("#ff4400"))
        binding.gauge.speed = disp
        updateHeading()
        if (mapShown) {
            binding.liveMapSpeed.text = "${disp.roundToInt()} mph"
        }

        if (gpsReady && currentSpd > allTimeTopSpd) {
            allTimeTopSpd = currentSpd
        }
        binding.gpsWait.visibility =
            if (gpsReady || fullscreen) View.GONE else View.VISIBLE

        checkSpeedAlert(currentSpd)
        if (fullscreen) updateFsInfo()
    }

    private fun updateFsInfo() {
        binding.fsTopVal.text = "${allTimeTopSpd.roundToInt()} MPH"
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
        binding.chipAcc.text = "Accuracy ${acc.roundToInt()}m"
        updateGpsPanel(acc, sig)
    }

    private fun updateGpsPanel(acc: Double, sig: Int) {
        when {
            sig >= 3 -> setGpsStatus("LOCKED", "#39ff14")
            sig >= 1 -> setGpsStatus("WEAK", "#ffb347")
            else -> setGpsStatus("POOR", "#ff5533")
        }
        val accEl = binding.chipAcc
        accEl.setTextColor(
            when {
                acc < 5 -> Color.parseColor("#39ff14")
                acc < 20 -> Color.parseColor("#ffb347")
                else -> Color.parseColor("#ff5533")
            }
        )
        updateAccSub()
        updateLandPanel()
    }

    /** Accuracy sub-label: fix quality, with the dual-band badge when L1+L5. */
    private fun updateAccSub() {
        val quality = when {
            !gpsReady -> "metres"
            lastAcc <= 5 -> "EXCELLENT"
            lastAcc <= 10 -> "GOOD"
            else -> "FAIR"
        }
        binding.diagQuality.text = if (dualBand && gpsReady) "L1+L5 · $quality" else quality
    }

    /** GPS status (LOCKED / WEAK / POOR / ASSISTED / ERROR) — lives on
     *  the stats diagnostics card now, not on the main screen (mockup v4
     *  shows GPS + bars only up top). */
    private fun setGpsStatus(text: String, colorHex: String) {
        binding.diagStatus.text = text
        binding.diagStatus.setTextColor(Color.parseColor(colorHex))
    }

    /** Compass heading in the dial top — the ONLY compass on the main
     *  screen (mockup v4 dedupe: no heading chip, no heading card). */
    private fun updateHeading() {
        val pt = compassPoint(lastBearingDeg)
        binding.dialHeading.text = pt
    }

    /** Stat cards under the dial: the live ride while recording, else the
     *  last saved ride, else dashes. Altitude is metres, like the frame. */
    private fun updateStatCards() {
        if (sessionActive) {
            val start = sessionStart
            val durSec = if (start != null)
                Math.round((Date().time - start.time) / 1000.0).toInt() else 0
            val distMi = sessionDist / 1609.34
            binding.statDist.text = String.format("%.2f mi", distMi)
            binding.statDur.text = fmtDur(durSec)
            val hrs = durSec / 3600.0
            binding.statAvg.text =
                if (hrs > 0) String.format("%.1f mph", distMi / hrs) else "0.0 mph"
        } else {
            val last = rides.firstOrNull()
            if (last != null) {
                binding.statDist.text = last.dist
                binding.statDur.text = last.dur
                binding.statAvg.text = rideAvgSpeedRow(last)?.second ?: "—"
            } else {
                binding.statDist.text = "—"
                binding.statDur.text = "—"
                binding.statAvg.text = "—"
            }
        }
        binding.statAlt.text = lastAlt?.let { "${it.roundToInt()}m" } ?: "—"
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
        // Diagnostics row (Settings → Advanced): fix rate + last interval.
        if (settings.showDiagnostics) {
            binding.diagRate.text = String.format("%.1f/s", fixTimes.size / 5.0)
            binding.diagInterval.text = String.format("%.2f s", lastFixIntervalMs / 1000.0)
        }
    }

    // ── Live map (MAP tab) ──────────────────────────────────────
    /** Esri dark tiles, no key — see EsriDarkTiles. Blue route, green
     *  Start pin, red End pin, like the map frame. */
    private fun setupLiveMap() {
        val map = binding.liveMap
        map.setTileSource(EsriDarkTiles.source())
        map.setMultiTouchControls(true)
        map.setUseDataConnection(true)
        map.controller.setZoom(15.0)
        // Glasgow, KY until the first fix arrives.
        map.controller.setCenter(GeoPoint(36.9959, -85.9118))
        liveLine = Polyline().apply {
            color = Color.parseColor("#00b4ff")
            width = 4f * resources.displayMetrics.density
        }
        map.overlays.add(liveLine)
        liveMapInit = true
    }

    /** The three full screens — gauge (home), live map, stats. Landscape
     *  and fullscreen are gauge-only (dial + trip panel / pure dial). */
    private fun showPage(page: Page) {
        val target = if (isLandscape() || fullscreen) Page.GAUGE else page
        currentPage = target
        mapShown = target == Page.MAP
        binding.gaugePage.visibility = if (target == Page.GAUGE) View.VISIBLE else View.GONE
        binding.statsPage.visibility = if (target == Page.STATS) View.VISIBLE else View.GONE
        binding.mapPage.visibility = if (target == Page.MAP) View.VISIBLE else View.GONE
        binding.gearBtn.visibility =
            if (target == Page.GAUGE && !fullscreen) View.VISIBLE else View.GONE
        binding.logsLink.visibility =
            if (target == Page.GAUGE && !fullscreen) View.VISIBLE else View.GONE
        if (target == Page.MAP && liveMapInit) {
            liveCentered = false
            binding.liveMap.onResume()
            binding.liveMap.invalidate()
        }
    }

    /** Draws the live route while a ride runs; centers on the rider.
     *  When no ride runs, the map stays where it is (Glasgow by default,
     *  or the last fix once the tab has been opened). */
    private fun updateLiveMap(lat: Double, lon: Double) {
        if (!liveMapInit) return
        val map = binding.liveMap
        val pt = GeoPoint(lat, lon)
        if (!sessionActive) {
            if (mapShown && !liveCentered) {
                map.controller.setCenter(pt)
                liveCentered = true
            }
            return
        }
        val lastPt = liveRoute.lastOrNull()
        if (lastPt == null ||
            haversineM(lastPt.latitude, lastPt.longitude, lat, lon) > 10
        ) {
            liveRoute.add(pt)
            if (liveRoute.size > 2000) liveRoute.removeAt(0)
            liveLine?.setPoints(liveRoute)
            if (liveStartMarker == null && liveRoute.isNotEmpty()) {
                liveStartMarker = RouteMapDialog.pinMarker(
                    map, liveRoute.first(), Color.parseColor("#39ff14"), "Start"
                ).also { map.overlays.add(it) }
            }
            liveEndMarker?.let { map.overlays.remove(it) }
            liveEndMarker = RouteMapDialog.pinMarker(
                map, pt, Color.parseColor("#ff2200"), "End"
            ).also { map.overlays.add(it) }
            binding.liveMapHint.text = "LIVE — ${liveRoute.size} PTS"
        }
        map.controller.setCenter(pt)
        map.invalidate()
    }

    private fun clearLiveMap() {
        if (!liveMapInit) return
        val map = binding.liveMap
        liveLine?.let { map.overlays.remove(it) }
        liveStartMarker?.let { map.overlays.remove(it) }
        liveEndMarker?.let { map.overlays.remove(it) }
        liveLine = null
        liveStartMarker = null
        liveEndMarker = null
        liveRoute = mutableListOf()
        liveCentered = false
        binding.liveMapHint.text = "START A RIDE TO DRAW YOUR ROUTE"
        map.invalidate()
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
        updateLandPanel()
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
        updateLandPanel()
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
        val runLbl = "0 → ${trimNum(tgtVal())} mph"
        saveRecord(elapsedMs, runLbl)
        renderBvL()
        updateLandPanel()
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
        updateLandPanel()
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
        clearLiveMap()

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

        val dist = String.format("%.2f mi", distSnap / 1609.34)
        val record = RideRecord(
            id = System.currentTimeMillis(),
            name = rideName(startSnap),
            topSpd = "${topSpdSnap.roundToInt()} mph",
            topSpdNum = topSpdSnap,
            dist = dist,
            dur = fmtDur(durSec),
            ts = isoNow(startSnap),
            route = sessionRoute.toList()
        )
        // Save first, then re-read immediately — the ride is in the store
        // the moment STOP is hit, so the logs page shows it on open.
        rides.add(0, record)
        if (rides.size > 100) rides = rides.subList(0, 100)
        store.rides = rides
        rides = store.rides
        updateStatCards()
        Toast.makeText(
            this, "Ride saved ✓ — ${rides.size} ride${if (rides.size == 1) "" else "s"}",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateSessionLive() {
        val start = sessionStart ?: return
        val durSec = Math.round((Date().time - start.time) / 1000.0).toInt()
        val dist = String.format("%.2f mi", sessionDist / 1609.34)
        binding.liveTopSpd.text = "${sessionTopSpd.roundToInt()} mph"
        binding.liveDist.text = dist
        binding.liveDur.text = fmtDur(durSec)
        updateLandPanel()
        updateStatCards()
    }

    private fun resetIdleTimer() {
        uiHandler.removeCallbacks(idleStop)
        // Idle auto-stop from Settings (1/5/10 min, or 0 = off).
        val mins = settings.idleMin
        if (mins > 0) uiHandler.postDelayed(idleStop, mins * 60 * 1000L)
    }

    private fun renderBvL() {
        val card = binding.bvlCard
        if (runs.isEmpty()) {
            card.visibility = View.GONE
            return
        }
        // The stats page is its own full screen now — the card shows
        // whenever there are runs to compare.
        card.visibility = View.VISIBLE
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
    private fun checkSpeedAlert(spdMph: Double) {
        val banner = binding.alertBanner
        if (alertMph <= 0) {
            banner.visibility = View.GONE
            alertFlashAnimator?.cancel()
            alertOn = false
            return
        }
        val limit = alertMph
        val over = spdMph >= limit
        if (over && !alertOn) {
            alertOn = true
            banner.text = "⚠ OVER ${trimNum(alertMph)} MPH"
            banner.visibility = View.VISIBLE
            alertFlashAnimator?.cancel()
            alertFlashAnimator = android.animation.ObjectAnimator.ofFloat(banner, "alpha", 1f, 0.55f).apply {
                duration = 500
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                start()
            }
            beep()
        } else if (!over && spdMph < limit - 2) {
            alertOn = false
            alertFlashAnimator?.cancel()
            banner.alpha = 1f
            banner.visibility = View.GONE
        }
    }

    private fun beep() {
        if (!settings.alertBeep) return // beep can be switched off in Settings
        try {
            if (toneGen == null) {
                toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            }
            toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, 250)
        } catch (_: Exception) {
        }
    }

}
