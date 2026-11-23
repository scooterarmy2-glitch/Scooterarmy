package com.scooterarmy.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
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
import android.util.Log
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
    private lateinit var liveMap: LiveMap
    /** Live-map page root (page_live_map inflated as a direct child of
     *  rootLayout, Build #81) — GONE while the speedometer shows. */
    private lateinit var liveRoot: View
    /** True while the live map is the visible page. */
    private var liveMapVisible = false

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
    // Last GPS bearing, for the dial heading below the 50 (look-pro).
    // Only set from a usable fix; cleared when the lock is lost, so a
    // stale bearing never shows as a heading.
    private var lastBearingDeg: Float? = null

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

    // Build #84 diagnostics: last gauge-size inputs, reported by the overlay.
    private var lastWinH = 0
    private var lastGw = 0
    private var lastGh = 0
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

        applyChromeGradient(binding.brandText)
        applyEmberGradient(binding.brandHash)
        // Shared photographic background (Backgrounds.kt, Build #79) —
        // real carbon-fiber photo behind everything.
        applyPhotoBackground(binding.rootLayout)
        // Ember glows (density-scaled so they render the same on every screen).
        // Build #81: vivid LED orange, not the muted amber — the brand hash,
        // the speed digit and the timer all glow LED orange.
        val density = resources.displayMetrics.density
        binding.brandHash.setShadowLayer(8f * density, 0f, 0f, Color.parseColor("#ff6a00"))
        binding.spdNum.setShadowLayer(16f * density, 0f, 0f, Color.parseColor("#ff6a00"))
        binding.timerBig.setShadowLayer(16f * density, 0f, 0f, Color.parseColor("#40ff6a00"))
        binding.spdUnit.text = "MPH"
        binding.tgtSuf.text = "MPH"
        binding.topSpdUnit.text = "MPH"
        if (alertMph > 0) {
            binding.alertInp.setText(trimNum(alertMph))
            binding.alertHint.text = "on — over ${trimNum(alertMph)} mph"
        }

        binding.sessBtn.setOnClickListener { toggleSession() }
        binding.armBtn.setOnClickListener { armRun() }
        binding.resetBtn.setOnClickListener { resetRun() }
        binding.logsBtn.setOnClickListener {
            openingLogs = true
            startActivity(Intent(this, LogsActivity::class.java))
        }
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
        binding.settingsBtn.setOnClickListener {
            openingSettings = true
            startActivity(Intent(this, SettingsActivity::class.java))
        }
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
        // Build #81: NO ViewPager2, NO fit listener. Builds #78–#80 put
        // the ScrollView inside a ViewPager2 page (RecyclerView) and sized
        // the gauge from measured heights — and on Casey's phone the main
        // screen still showed Build #79's broken layout (header centred,
        // gauge at the 320px floor, boxes missing, weave only in a band)
        // even though Build #80's code was installed (Settings proved it:
        // the Background row was there and the weave rendered full-screen
        // behind Settings, which has NO pager). The pager + measured-height
        // fit are the only Main-specific layers over the root background,
        // and no static analysis of the #80 code can produce that screen —
        // so both mechanisms are removed instead of reordered again. The
        // ScrollView stays a direct child of rootLayout (as before #77);
        // the live map is a second direct child, GONE until swiped to;
        // the gauge size is a pure function of the window metrics
        // (gaugeSizeForScreen, Models.kt) and is never re-measured.
        // Build tag in the header (Build #81): the build number is visible
        // on the main screen itself, so any screenshot proves which build
        // is actually running — no more "is it installed?" guessing.
        binding.buildTag.text = "Build $BUILD_NUMBER"
        setupLiveMapPage()
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

    // ── Live map page (Build #81 — replaces the ViewPager2) ─────
    // The live map is a second direct child of rootLayout, GONE until
    // shown. The ScrollView is never reparented (it stays a direct child,
    // as before Build #77), so there is no RecyclerView measuring the
    // speedo page and no pager state to restore. Swipe-right on the
    // speedo shows the map; swipe-left on the map (or the back chevron)
    // returns. The map is created once and stays live, so it is ready
    // the moment you swipe to it — same as the pager's
    // offscreenPageLimit = 1, without the pager.
    private fun setupLiveMapPage() {
        liveRoot = layoutInflater.inflate(R.layout.page_live_map, binding.rootLayout, false)
        liveRoot.visibility = View.GONE
        liveMap = LiveMap(liveRoot, onBack = { showMainPage() }) {
            settings.idleMin
        }
        binding.rootLayout.addView(liveRoot)
    }

    /** Show the live map (swipe-right from the speedo). */
    private fun showLiveMapPage() {
        if (liveMapVisible) return
        liveMapVisible = true
        binding.scrollView.visibility = View.GONE
        liveRoot.visibility = View.VISIBLE
        // Same refresh the pager did when page 1 was swiped into view:
        // force the tiles to re-request + redraw so the "Map data not yet
        // available" placeholders never sit there while real tiles are
        // one request away.
        liveMap.refresh()
    }

    /** Show the speedometer (swipe-left from the map, or back chevron). */
    private fun showMainPage() {
        if (!liveMapVisible) return
        liveMapVisible = false
        liveRoot.visibility = View.GONE
        binding.scrollView.visibility = View.VISIBLE
        // The chrome pass re-asserts the portrait layout on return, so a
        // rotation or fullscreen toggle while the map was up cannot leave
        // the speedo in a stale state.
        applyChromeVisibility()
    }

    // Swipe navigation (Build #81): a deliberate horizontal fling moves
    // between the speedo and the live map. The detector runs here, in
    // dispatchTouchEvent, so it sees the gesture before any child — the
    // gauge double-tap listener and the ScrollView's vertical scroll are
    // untouched, and the event always flows on to the children (the
    // return value is super's, never the detector's). classifyFling
    // (Models.kt) is the gate: vertical scrolls and taps return NONE.
    // Build #82: LAZY — an eager navGesture built with `this` runs during
    // Activity construction, before attach() sets the base context, and
    // GestureDetector's constructor touches ViewConfiguration/Resources
    // through that null context: NPE, instant crash on launch (#81).
    // First use is dispatchTouchEvent, which only runs after onCreate.
    private val navGesture by lazy {
        GestureDetector(
        this,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                return when (classifyFling(e2.x - e1.x, e2.y - e1.y, velocityX)) {
                    FlingDir.RIGHT -> {
                        if (!liveMapVisible) showLiveMapPage()
                        true
                    }
                    FlingDir.LEFT -> {
                        if (liveMapVisible) showMainPage()
                        true
                    }
                    FlingDir.NONE -> false
                }
            }
        }
        )
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        navGesture.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    /** Seconds since the current ride started (0 when not recording). */
    private fun sessionSeconds(): Int {
        val start = sessionStart ?: return 0
        return Math.round((Date().time - start.time) / 1000.0).toInt()
    }

    override fun onResume() {
        super.onResume()
        setImmersive()
        openingSettings = false
        openingLogs = false
        applySettings()
        // Build #80: re-assert the chrome state on every resume — the
        // header must be top-anchored and the boxes visible in portrait,
        // and a stale layout state (from a rotation, a fullscreen
        // double-tap or a backgrounded transient) is healed here instead
        // of surviving until the next ride start/stop. The background
        // photo is re-applied too, so a Background change made in
        // Settings shows live on return (no restart).
        applyChromeVisibility()
        applyPhotoBackground(binding.rootLayout)
        startLocation()
        imuBridge.start()
        if (::liveMap.isInitialized) liveMap.onResume()
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
        if (::liveMap.isInitialized) liveMap.onPause()
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
        if (::liveMap.isInitialized) liveMap.onDestroy()
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
        // Landscape (not fullscreen): gauge on the LEFT, live trip panel
        // on the RIGHT — Speedo-style. Fullscreen stays speedo-only.
        val landscapePanel = isLandscape() && !fullscreen
        val gone = View.GONE
        val vis = View.VISIBLE
        binding.headerRow.visibility = if (speedoOnly) gone else vis
        binding.headerDivider.visibility = if (speedoOnly) gone else vis
        binding.gpsInfo.visibility = if (speedoOnly) gone else vis
        binding.rideBox.visibility = if (speedoOnly) gone else vis
        binding.runBox.visibility = if (speedoOnly) gone else vis
        binding.bvlCard.visibility =
            if (speedoOnly || runs.isEmpty()) gone else vis
        binding.sessLive.visibility =
            if (speedoOnly) gone else if (sessionActive) vis else gone
        binding.fsHint.visibility = if (fullscreen) vis else gone
        binding.fsInfoRow.visibility = if (fullscreen) vis else gone
        binding.topSpdBox.visibility = if (fullscreen) gone else vis
        binding.landPanel.visibility = if (landscapePanel) vis else gone
        binding.gaugeRow.orientation =
            if (landscapePanel) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        binding.gaugeRow.gravity =
            if (landscapePanel) Gravity.CENTER_VERTICAL else Gravity.CENTER_HORIZONTAL

        // Build #80: reset the column gravity directly, here, in
        // portrait — layoutGaugeSize does it too, but Build #79 left
        // the column vertically centred on Casey's phone (header ~21%
        // down the screen) with no code path in portrait that sets
        // CENTER_VERTICAL, so the reset is stated twice on purpose:
        // whatever transient state the column is in, portrait is TOP.
        if (!speedoOnly) binding.contentCol.gravity = Gravity.TOP
        // Build #83: fillViewport stretches contentCol to the full viewport
        // height, creating slack that the column gravity can mis-distribute
        // (Builds #79–#82 show the whole main screen displaced downward as
        // if centred, header ~410px down, despite gravity=TOP being set
        // twice per pass). In portrait there is no centering to do, so the
        // content must be exactly its natural height — no slack, no
        // displacement possible. fillViewport stays ON only for speedo-only
        // (landscape/fullscreen), where CENTER_VERTICAL genuinely needs the
        // stretch to centre the dial.
        binding.scrollView.isFillViewport = speedoOnly
        // Build #83: guarantee the ScrollView itself is at the top.
        binding.scrollView.scrollTo(0, 0)

        // Gauge size (Build #81): a pure function of the window metrics
        // (gaugeSizeForScreen, Models.kt) — never of a measured view
        // height, and never re-measured by a layout listener. The
        // Build #78 posted fit and the Build #80 layout listener both
        // sized the gauge from heights read during layout passes, and on
        // Casey's phone the gauge still ended up at the 320px floor with
        // the column centred and the boxes missing — even with Build #80
        // installed. The size is now computed once per chrome pass from
        // displayMetrics and stays put until the next chrome pass
        // (rotation, fullscreen toggle, resume).
        val dm = resources.displayMetrics
        // Build #83: prefer the ScrollView's ACTUALLY MEASURED height (the
        // real window) over displayMetrics.heightPixels, which is the display
        // height as seen at onCreate and can be stale (Build #82's gauge
        // measured 996px on-device vs the 1091px displayMetrics dictates).
        // Before the first layout pass the ScrollView is 0 tall, so fall
        // back to displayMetrics then; onResume re-asserts with the real
        // height, so the final size is always from the real window.
        val winH = if (binding.scrollView.height > 0) binding.scrollView.height else dm.heightPixels
        val (gw, gh) = gaugeSizeForScreen(
            dm.widthPixels, winH, dm.density,
            isLandscape(), fullscreen
        )
        layoutGaugeSize(gw, gh, speedoOnly)
        // Build #84 diagnostics: remember the gauge-size inputs for the overlay.
        lastWinH = winH
        lastGw = gw
        lastGh = gh
        // TOP SPEED chip hardening (Build #78): the chip is in the
        // layout and VISIBLE in portrait, but it did not appear in
        // Casey's Build #77 screenshot. No code path sets it GONE in
        // portrait, and the installed APK contains it (verified via
        // aapt2 dump xmltree). Bring it to the front of the gauge stack
        // and force VISIBLE here; its value is updated on every fix in
        // updateSpeedDisplay(). If it still does not show on-device,
        // that points at the phone's renderer, not this code.
        if (!speedoOnly) {
            binding.topSpdBox.bringToFront()
            binding.topSpdBox.visibility = View.VISIBLE
        }
        if (fullscreen) updateFsInfo()
        updateLandPanel()
        // Build #84 diagnostics overlay: refresh after this layout pass
        // and again once settled (500ms), so the screenshot shows both
        // first-pass and settled values. Runs on every chrome pass and
        // onResume (which calls applyChromeVisibility).
        binding.scrollView.post { updateDiagOverlay() }
        binding.scrollView.postDelayed({ updateDiagOverlay() }, 500)
    }

    /** Build #84 TEMPORARY diagnostics overlay: shows the phone's real
     * screen metrics and view-tree measurements on the main screen, so a
     * screenshot proves what the device is actually doing. Same lines go
     * to logcat (tag SAMO_DIAG). Remove when the bug is found. */
    private fun updateDiagOverlay() {
        val dm = resources.displayMetrics
        val cfg = resources.configuration
        val orient = if (cfg.orientation == Configuration.ORIENTATION_LANDSCAPE) "land" else "port"
        // Build #84: every measurement in BOTH px and dp — Casey: not all
        // phones share his screen size, so dp is what generalizes. Nothing
        // here (or in any fix that follows) is hardcoded to 1080x2340 / 2.77.
        val dp: (Int) -> Int = { px -> (px / dm.density).toInt() }
        val lines = listOf(
            "DIAG Build $BUILD_NUMBER",
            "dm ${dm.widthPixels}x${dm.heightPixels} d${dm.density} dpi${dm.densityDpi} sd${dm.scaledDensity} x${dm.xdpi.toInt()} y${dm.ydpi.toInt()}",
            "cfg $orient ${cfg.screenWidthDp}x${cfg.screenHeightDp} sw${cfg.smallestScreenWidthDp} dpi${cfg.densityDpi}",
            "sv h${binding.scrollView.height}=${dp(binding.scrollView.height)}dp y${binding.scrollView.scrollY} fv=${binding.scrollView.isFillViewport}",
            "col h${binding.contentCol.height}=${dp(binding.contentCol.height)}dp top${binding.contentCol.top}=${dp(binding.contentCol.top)}dp",
            "gw ${binding.gaugeWrap.width}x${binding.gaugeWrap.height}=${dp(binding.gaugeWrap.width)}x${dp(binding.gaugeWrap.height)}dp top${binding.gaugeWrap.top}=${dp(binding.gaugeWrap.top)}dp",
            "chip x${binding.topSpdBox.x.toInt()}=${dp(binding.topSpdBox.x.toInt())}dp y${binding.topSpdBox.y.toInt()}=${dp(binding.topSpdBox.y.toInt())}dp hdr top${binding.headerRow.top}=${dp(binding.headerRow.top)}dp",
            "strip top${binding.gpsInfo.top}=${dp(binding.gpsInfo.top)}dp",
            "gIn dmH${dm.heightPixels} winH$lastWinH gw$lastGw=${dp(lastGw)}dp gh$lastGh=${dp(lastGh)}dp"
        )
        val text = lines.joinToString("\n")
        binding.diagOverlay.text = text
        Log.d("SAMO_DIAG", text)
    }

    /** Gauge layout params + the speed-digit scale that tracks them.
     * The digit keeps its exact relative spot on the dial at any gauge
     * size (72sp and a 50dp bottom margin at the old 320dp gauge).
     * Build #81: the Build #80 layout-listener fit is DELETED (the
     * listener + its fields are gone, not just uncalled) — the gauge
     * size now comes only from gaugeSizeForScreen() in
     * applyChromeVisibility, a pure function of the window metrics.
     * fitGaugeForViewport() stays in Models.kt (pure, harness-tested)
     * as the documented one-screen-fit math, but nothing in the
     * activity calls it anymore. */
    private fun layoutGaugeSize(gw: Int, gh: Int, speedoOnly: Boolean) {
        val dm = resources.displayMetrics
        val landscapePanel = isLandscape() && !fullscreen
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
        binding.contentCol.gravity =
            if (speedoOnly) Gravity.CENTER_VERTICAL else Gravity.TOP

        // Speed number + overlay position scale with the gauge.
        val gaugeScale = gw / (320f * dm.density)
        binding.spdNum.setTextSize(
            TypedValue.COMPLEX_UNIT_SP, if (speedoOnly) 110f else 72f * gaugeScale
        )
        // Build #80: set the overlay margin SYNCHRONOUSLY. The old code
        // posted it (binding.gaugeWrap.post { ... }), and like the old
        // posted fit it could land after a reparent/re-layout and leave
        // the speed digit misplaced or invisible — Casey's Build #79
        // screenshot shows no speed digit at all, though the digit is a
        // plain TextView set to 27sp at the 320px floor and should have
        // been plainly visible. The portrait margin is a pure function
        // of gaugeScale; the speedo-only margin uses the intended height
        // gh, not the currently-measured height (which may still be the
        // old size when this runs).
        val overlayLp = binding.spdOverlay.layoutParams as android.widget.FrameLayout.LayoutParams
        overlayLp.bottomMargin =
            if (speedoOnly) (gh * 0.14).toInt()
            else (50f * gaugeScale * dm.density).toInt()
        binding.spdOverlay.layoutParams = overlayLp
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
                        Color.parseColor("#ffb347"),
                        Color.parseColor("#ff8c1a"),
                        Color.parseColor("#ff6a00")
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
                tv.invalidate()
            }
        }
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
        binding.gauge.maxSpd = settings.gaugeMax.toDouble()
        binding.diagRow.visibility = if (settings.showDiagnostics) View.VISIBLE else View.GONE
        if (settings.showDiagnostics) updateFixAge()
    }

    // ── Unit conversion: mph only ───────────────────────────────
    // GPS works in m/s internally; everything the user sees is mph.
    private fun toMps(mph: Double): Double = mph / 2.23694

    private fun fromMps(mps: Double): Double = mps * 2.23694

    private fun maxSpd(): Double = settings.gaugeMax.toDouble()

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
        binding.giAcc.setTextColor(Color.parseColor("#ff8c1a"))
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
        binding.giAcc.setTextColor(Color.parseColor("#ff8c1a"))
        binding.acctxt.text = "±${acc.roundToInt()}m"
        setSdot("warn")
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
            lastBearingDeg = f.bearingDeg
            f.bearingDeg?.let { imuBridge.setBearingDeg(it) }
            imuBridge.onGpsFix() // truth wins: snap display, reset the bridge
        } else if (!gpsReady) {
            currentSpd = 0.0
            rawMps = 0.0
            lastBearingDeg = null
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

        // Live map page: current speed/distance/time + the route as it grows.
        if (::liveMap.isInitialized) {
            liveMap.onFix(
                f, sessionActive, currentSpd, sessionDist / 1609.34,
                sessionSeconds(), sessionRoute
            )
        }

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
        // Build #81 LED: the speed digit is white at normal speeds and
        // turns LED orange, then LED red, as it nears the gauge max —
        // glowing lines, never a pale pastel fill. (The muted amber
        // (the muted-amber thresholds are gone.)
        binding.spdNum.setTextColor(
            when {
                ratio > 0.85 -> Color.parseColor("#ff2d1a")
                ratio > 0.6 -> Color.parseColor("#ff8c1a")
                else -> Color.WHITE
            }
        )
        // LED glow follows the speed color — vivid orange, red at the top.
        val glowColor = when {
            ratio > 0.85 -> Color.parseColor("#ff2d1a")
            ratio > 0.6 -> Color.parseColor("#ff6a00")
            else -> Color.parseColor("#ff6a00")
        }
        binding.spdNum.setShadowLayer(16f * resources.displayMetrics.density, 0f, 0f, glowColor)
        binding.gauge.speed = disp
        // Dial heading below the 50 — live bearing while moving, "—" stopped.
        binding.gauge.heading = headingLabel(lastBearingDeg, currentSpd)

        if (gpsReady && currentSpd > allTimeTopSpd) {
            allTimeTopSpd = currentSpd
        }
        // TOP SPEED chip (Build #78): the ride's real top speed so far,
        // updated on EVERY fix — not only when a new record is set — and
        // 0 when no ride is running (topSpeedChipValue, Models.kt).
        // Never a faked number.
        binding.topSpdVal.text =
            topSpeedChipValue(sessionActive, sessionTopSpd).roundToInt().toString()
        binding.topSpdUnit.text = "MPH"
        binding.gpsWait.visibility = if (gpsReady) View.GONE else View.VISIBLE

        checkSpeedAlert(currentSpd)
        if (fullscreen) updateFsInfo()
    }

    private fun updateFsInfo() {
        binding.fsTopVal.text = "${allTimeTopSpd.roundToInt()} MPH"
        val gpsEl = binding.fsGpsVal
        when {
            lastSig >= 3 -> { gpsEl.text = "LOCKED"; gpsEl.setTextColor(Color.parseColor("#39ff14")) }
            lastSig >= 1 -> { gpsEl.text = "WEAK"; gpsEl.setTextColor(Color.parseColor("#ff8c1a")) }
            else -> { gpsEl.text = "POOR"; gpsEl.setTextColor(Color.parseColor("#ff5533")) }
        }
        val accEl = binding.fsAccVal
        accEl.text = if (lastAcc < 900) "±${lastAcc.roundToInt()}m" else "—"
        accEl.setTextColor(
            when {
                lastAcc < 5 -> Color.parseColor("#39ff14")
                lastAcc < 20 -> Color.parseColor("#ff8c1a")
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
                statusEl.setTextColor(Color.parseColor("#ff8c1a"))
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
                acc < 20 -> Color.parseColor("#ff8c1a")
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
                age < 3 -> Color.parseColor("#ff8c1a")
                else -> Color.parseColor("#ff5533")
            }
        )
        // Diagnostics row (Settings → Advanced): fix rate + last interval.
        if (settings.showDiagnostics) {
            binding.diagRate.text = String.format("%.1f/s", fixTimes.size / 5.0)
            binding.diagInterval.text = String.format("%.2f s", lastFixIntervalMs / 1000.0)
        }
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

    // ── Timed run ───────────────────────────────────────────────
    private fun armRun() {
        val tgt = tgtVal()
        if (tgt <= 0) {
            Toast.makeText(this, "Enter a valid target speed", Toast.LENGTH_SHORT).show()
            return
        }
        state = RunState.ARMED
        binding.timerBig.text = "0.000"
        binding.timerBig.setTextColor(Color.parseColor("#ffffff"))
        binding.timerBig.setShadowLayer(12f * resources.displayMetrics.density, 0f, 0f, Color.parseColor("#ff6a00"))
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
        binding.timerBig.setTextColor(Color.parseColor("#ffffff"))
        binding.timerBig.setShadowLayer(12f * resources.displayMetrics.density, 0f, 0f, Color.parseColor("#ff6a00"))
        uiHandler.post(timerTick)
        updateLandPanel()
    }

    private fun finishRun(elapsedMs: Long) {
        state = RunState.DONE
        uiHandler.removeCallbacks(timerTick)
        binding.timerBig.text = fmtTimer(elapsedMs)
        binding.timerBig.setTextColor(Color.parseColor("#39ff14"))
        binding.timerBig.setShadowLayer(12f * resources.displayMetrics.density, 0f, 0f, Color.parseColor("#39ff14"))
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
        binding.timerBig.setTextColor(Color.parseColor("#ffffff"))
        binding.timerBig.setShadowLayer(12f * resources.displayMetrics.density, 0f, 0f, Color.parseColor("#40ff6a00"))
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

        binding.sessBtn.text = "STOP RIDE"
        binding.sessBtn.setBackgroundResource(R.drawable.btn_red_chamfer)
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
        if (::liveMap.isInitialized) liveMap.onSessionStart()
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
        if (::liveMap.isInitialized) liveMap.onSessionStop()
        stopPulseAnimator?.cancel()
        binding.sessBtn.alpha = 1f
        binding.sessBtn.text = "START RIDE"
        binding.sessBtn.setBackgroundResource(R.drawable.btn_amber_chamfer)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            binding.sessBtn.outlineSpotShadowColor = Color.parseColor("#ff6a00")
            binding.sessBtn.outlineAmbientShadowColor = Color.parseColor("#ff6a00")
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
        if (::liveMap.isInitialized) liveMap.tick(durSec)
        updateLandPanel()
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
