package com.scooterarmy.app


import android.graphics.LinearGradient
import android.graphics.Shader
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/** Build #86: the human-facing build number shown in the header ("Build 86")
 * and in Settings ("Build 86 • v1.1.0"). This is a SINGLE constant — never
 * derive the label from BuildConfig.VERSION_CODE again: VERSION_CODE is 5
 * (the Play version code), so the header read "Build 5" on Builds #81/#82
 * even though the APK was Build #81/#82. Bump this constant every build. */
const val BUILD_NUMBER = 94

/** Build #88 add-on: the "#" in "#SAMO" flashes while a ride is
 * recording — full blue <-> dimmed blue (the same rhythm as the STOP
 * button pulse). Build #89 (Casey Nov 23: "more strong"): the swing
 * is deeper (dim end 0.35 -> 0.10) and quicker (1000ms -> 800ms per
 * half-cycle) so the flash is clearly noticeable at a glance. The
 * low end stays visible — a live indicator, not a strobe, never
 * fully invisible. */
const val BRAND_FLASH_LOW_ALPHA = 0.10f
const val BRAND_FLASH_PERIOD_MS = 800L

/**
 * Ring underglow, Build #90 (Casey Nov 23: the #89 two-pass blob
 * "looks like crap needs to be more of a gradient .. right now its
 * just a big ass blob around the speedometer"). ONE arc stroke whose
 * paint carries a RadialGradient shader: brightest right at the rim's
 * outer edge, fading smoothly to transparent as it moves outward —
 * light spilling off the ring, not a painted halo. Thinner and
 * subtler than the #89 blob (70px halo + 34px core at alpha 120/200,
 * ~100px of near-uniform blue with a hard outer edge): the gradient
 * band is 14 units wide (~47px at 1080px) and its alpha falls steadily
 * the whole way out, so there is no flat band and no hard edge. Blue
 * #00b4ff only, only under the outer ring.
 *
 * Stops are (offset from the rim centreline r, in `scale` units,
 * alpha). The rim stroke covers r-3.5..r+3.5, so the visible part of
 * the glow starts at the rim's outer edge (+3.5) — the ramp under the
 * rim is hidden by the rim itself, which is drawn after the glow. The
 * shader in GaugeView is built from these exact stops (positions are
 * the offsets mapped to fractions of the gradient radius), so the
 * pure function below and the on-device shader agree exactly. */
const val RIM_GLOW_INNER_UNITS = -2f   // glow starts 2 units inside the rim centreline (under the rim)
const val RIM_GLOW_WIDTH_UNITS = 14f   // glow ends 14 units outside the rim centreline
val RIM_GLOW_STOPS: List<Pair<Float, Int>> = listOf(
    -2f to 0,     // under the rim: transparent
    3.5f to 150,  // rim outer edge: peak brightness
    7f to 60,     // ~1/3 of the way out
    10.5f to 20,  // ~2/3 of the way out
    14f to 0      // outer edge: fully faded
)

/** Alpha of the ring glow at a given offset from the rim centreline r
 * (in `scale` units): piecewise-linear interpolation over
 * RIM_GLOW_STOPS. Pure, harness-tested: the alpha must fall steadily
 * outward (150 at the rim edge, 60, 20, 0) — never flat, never rising.
 * Outside the stops it is 0. */
fun rimGlowAlphaAt(offsetUnits: Float): Int {
    if (offsetUnits <= RIM_GLOW_STOPS.first().first) return 0
    if (offsetUnits >= RIM_GLOW_STOPS.last().first) return 0
    for (i in 0 until RIM_GLOW_STOPS.size - 1) {
        val (x0, a0) = RIM_GLOW_STOPS[i]
        val (x1, a1) = RIM_GLOW_STOPS[i + 1]
        if (offsetUnits in x0..x1) {
            val t = (offsetUnits - x0) / (x1 - x0)
            return (a0 + (a1 - a0) * t).toInt()
        }
    }
    return 0
}

/** The "#" flashes exactly when a ride is recording — the same
 * sessionActive state that drives the LIVE badge, the status pill and
 * the STOP button. At rest (or any other time) it is steady. */
fun brandFlashing(sessionActive: Boolean): Boolean = sessionActive

/** Needle base geometry, Build #90 (Casey Nov 23: on his #89
 * screenshot the needle's butt end stuck out past the centre circle
 * on the back side — you could see the tail poking through behind
 * the hub). The needle is a triangle: tip at NEEDLE_TIP_UNITS from
 * the centre, base corners at NEEDLE_BASE_UNITS behind the centre
 * ± NEEDLE_HALF_WIDTH_UNITS perpendicular. The old base was -18
 * units behind the centre while the hub circle is only 12 units in
 * radius, so 6 units of tail stuck out past the hub on the back
 * side. The base is now -6 units: the farthest base corner sits
 * sqrt(6² + 5²) ≈ 7.8 units from the centre — strictly inside the
 * 12-unit hub, which is drawn AFTER the needle and covers the inner
 * end completely, with ~4.2 units to spare. The needle starts at
 * the hub edge and points outward only: no tail, no butt end. */
const val NEEDLE_BASE_UNITS = -6f
const val NEEDLE_HALF_WIDTH_UNITS = 5f
const val HUB_RADIUS_UNITS = 12f

/** Farthest distance of any needle-base corner from the dial centre,
 * in `scale` units. Must stay below HUB_RADIUS_UNITS — pure,
 * harness-tested. */
fun needleBaseExtentUnits(): Double =
    kotlin.math.sqrt(
        (NEEDLE_BASE_UNITS * NEEDLE_BASE_UNITS +
            NEEDLE_HALF_WIDTH_UNITS * NEEDLE_HALF_WIDTH_UNITS).toDouble()
    )

/**
 * Polished chrome, Build #90 (Casey Nov 23: the "#SAMO GPS" header
 * title should look like CHROME — "with a bit of shine"). A real
 * multi-stop silver gradient, NOT a flat grey: bright silver top
 * edge, a distinct near-white SHINE streak in the upper third (the
 * specular band where light catches the metal), mid grey, a dark
 * shadow-side band, then silver climbing back to a bright bottom
 * edge. The shine stop is pure white (0xFFFFFFFF) and narrow — it
 * reads as a specular highlight, not a pale wash. The old 4-stop
 * gradient (white -> #bbbbbb -> #eeeeee -> #999999) had no dark band
 * and no distinct shine, so it read as dull grey metal.
 *
 * Stops are (fraction down the gradient, ARGB color). The gradient
 * is DIAGONAL across the dial (GaugeView: chromeDialShader) so the
 * shine band sweeps across the dial like light catching metal, and
 * the ticks + top band match each other. (The header title used to
 * share these stops vertically; Build #90 briefly made the title
 * ENGRAVED instead, but Casey cut that in Build #91 — the title is
 * back to the #89 blue "#" + chrome-gradient text, while the dial
 * keeps the chrome.) */
val CHROME_STOPS: List<Pair<Float, Int>> = listOf(
    0.00f to 0xFFECECEC.toInt(),  // bright silver top edge
    0.14f to 0xFFFFFFFF.toInt(),  // SHINE — near-white specular band
    0.30f to 0xFFDCDCDC.toInt(),  // bright silver below the shine
    0.48f to 0xFF909090.toInt(),  // mid grey
    0.62f to 0xFF5C5C5C.toInt(),  // darkest — shadow side of the metal
    0.78f to 0xFFB9B9B9.toInt(),  // silver climbing back
    0.90f to 0xFFF7F7F7.toInt(),  // bright bottom edge
    1.00f to 0xFFCFCFCF.toInt()   // silver foot
)

/** Diagonal chrome gradient across a square region (the dial): top-left
 * to bottom-right, so the shine band sweeps across the dial like light
 * catching metal. The DIAL keeps the chrome (Casey Nov 23: ticks +
 * top band stay chrome); the header title is back to its #89
 * blue "#" + chrome-gradient text (Build #91). */
fun chromeDialShader(left: Float, top: Float, right: Float, bottom: Float): LinearGradient =
    LinearGradient(
        left, top, right, bottom,
        CHROME_STOPS.map { it.second }.toIntArray(),
        CHROME_STOPS.map { it.first }.toFloatArray(),
        Shader.TileMode.CLAMP
    )

/** One recorded GPS point on a ride route. t = GPS fix clock (elapsed-realtime ms). */
data class RoutePoint(val lat: Double, val lon: Double, val spd: Double, val t: Long) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("lat", lat); put("lon", lon); put("spd", spd); put("t", t)
    }

    companion object {
        fun fromJson(o: JSONObject) = RoutePoint(
            o.optDouble("lat"), o.optDouble("lon"), o.optDouble("spd"), o.optLong("t")
        )
    }
}

/** One finished ride, as shown in the Ride Log. */
data class RideRecord(
    val id: Long,
    val name: String,          // e.g. "Sun, Nov 22 08:02 AM"
    val topSpd: String,        // e.g. "47 mph" (display string, unit included)
    val topSpdNum: Double,     // top speed in mph
    val dist: String,          // e.g. "3.42 mi"
    val dur: String,           // e.g. "12:34"
    val ts: String,            // ISO start time
    val route: List<RoutePoint>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("topSpd", topSpd)
        put("topSpdNum", topSpdNum); put("dist", dist); put("dur", dur); put("ts", ts)
        put("route", JSONArray().apply { route.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): RideRecord {
            val route = mutableListOf<RoutePoint>()
            val arr = o.optJSONArray("route")
            if (arr != null) for (i in 0 until arr.length()) route += RoutePoint.fromJson(arr.getJSONObject(i))
            return RideRecord(
                o.optLong("id"), o.optString("name"), o.optString("topSpd"),
                o.optDouble("topSpdNum"), o.optString("dist"), o.optString("dur"),
                o.optString("ts"), route
            )
        }
    }
}

/** One finished timed run, as shown in the Timed Run Log. */
data class RunRecord(
    val id: Long,
    val runLbl: String,        // e.g. "0 → 45 mph"
    val timeMs: Long,
    val time: String,          // formatted, e.g. "4.123"
    val isBest: Boolean,
    val date: String           // e.g. "Nov 22  08:14 AM"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("runLbl", runLbl); put("timeMs", timeMs)
        put("time", time); put("isBest", isBest); put("date", date)
    }

    companion object {
        fun fromJson(o: JSONObject) = RunRecord(
            o.optLong("id"), o.optString("runLbl"), o.optLong("timeMs"),
            o.optString("time"), o.optBoolean("isBest"), o.optString("date")
        )
    }
}

/** A raw GPS fix, in the units the engine works with. */
data class FixData(
    val lat: Double,
    val lon: Double,
    val speedMps: Float?,      // null / <0 = chip gave no Doppler speed
    val acc: Float,            // horizontal accuracy, metres
    val speedAcc: Float?,      // speed accuracy estimate, m/s (null = unknown)
    val t: Long,               // fix time on the elapsed-realtime clock (ms)
    val bearingDeg: Float?     // direction of travel, when the chip reports one
)

/** 8-point compass (Build #88, Casey Nov 23: degrees go away — the dial
 * shows the point only: N, NE, E, SE, S, SW, W, NW). 0° = N, 90° = E,
 * 180° = S, 270° = W. Pure, harness-tested. */
fun compassPoint(deg: Float?): String {
    if (deg == null || deg.isNaN()) return "—"
    val points = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val norm = ((deg % 360f) + 360f) % 360f
    return points[((norm + 22.5f) / 45f).toInt() % 8]
}

/**
 * Dial heading label, look-pro: the compass point ONLY while moving
 * with a real bearing ("NW"), "—" when stopped or when the chip reports
 * no bearing — never a faked heading, never degrees (Build #88). The
 * speed gate matters: a stopped chip can keep reporting its last
 * bearing, so bearing alone is not proof of travel.
 */
fun headingLabel(bearingDeg: Float?, speedMph: Double): String {
    if (bearingDeg == null || speedMph < 1.0) return "—"
    return compassPoint(bearingDeg)
}

/**
 * TOP SPEED chip value, Build #78: the ride's real top speed so far while a
 * ride is active, 0 when no ride is running. The chip never shows a number
 * the ride did not actually reach — no all-time carry-over, no faked value.
 * Pure, harness-tested.
 */
fun topSpeedChipValue(sessionActive: Boolean, sessionTopSpd: Double): Double =
    if (sessionActive) sessionTopSpd else 0.0

/**
 * One-screen gauge fit, Build #78: shrink the gauge just enough that the
 * whole portrait column fits the viewport, so START TIMED RUN + RESET are
 * visible without scrolling. `contentH` is the measured height of the full
 * column (header + gauge + strip + ride box + timed box + padding) with the
 * gauge at `gaugePx`; `viewportH` is the scroll area's height. Returns the
 * gauge size to use. Never grows the gauge, never shrinks below `minGaugePx`
 * (still a usable dial). Pure, harness-tested.
 */
fun fitGaugeSize(
    contentH: Int,
    viewportH: Int,
    gaugePx: Int,
    minGaugePx: Int = 320
): Int {
    if (contentH <= viewportH || gaugePx <= 0) return gaugePx
    val overflow = contentH - viewportH
    return maxOf(minGaugePx, gaugePx - overflow)
}

/**
 * One-screen gauge fit, Build #80 (replaces the Build #78 posted fit
 * as the size the activity actually uses): the gauge size that makes
 * the whole portrait column fit the viewport, computed from heights
 * measured AFTER a layout pass. `contentH` is the measured height of
 * the full column with the gauge at `gaugePx`; `viewportH` is the
 * scroll area's height; `fullGaugePx` is the un-shrunk portrait gauge
 * (screen width + 8dp). Returns `gaugePx` unchanged when either
 * height is not yet laid out (<= 0) — the caller must NEVER shrink on
 * unlaid-out heights: that is what clamped the gauge to the 320px
 * floor in Build #79. Never grows above `fullGaugePx`, never shrinks
 * below `minGaugePx`. Idempotent: calling it again with the resized
 * content returns the same size, and it restores the gauge when boxes
 * are hidden and room opens up. Pure, harness-tested.
 */
fun fitGaugeForViewport(
    contentH: Int,
    viewportH: Int,
    gaugePx: Int,
    fullGaugePx: Int,
    minGaugePx: Int = 320
): Int {
    if (contentH <= 0 || viewportH <= 0 || gaugePx <= 0 || fullGaugePx <= 0) return gaugePx
    // Height the column WOULD have with the gauge at full size.
    val contentAtFull = contentH + (fullGaugePx - gaugePx)
    val overflow = contentAtFull - viewportH
    if (overflow <= 0) return fullGaugePx
    return maxOf(minGaugePx, fullGaugePx - overflow)
}

/**
 * Landscape gauge size (Build #88, Casey Nov 23: "make the speedo
 * REALLY big" in landscape): the dial fills the window HEIGHT — the
 * binding constraint in landscape — minus a small margin, while
 * leaving the trip panel a usable width (360dp). Pure, harness-tested.
 * At 2340x1080 @2.8125: min(1035, 1328) = 1035 (was 990 under the old
 * min(w,h)-32dp formula). Floored at 320px so very small windows still
 * get a usable dial and the panel keeps 320dp.
 */
fun landscapeGaugeSize(wPx: Int, hPx: Int, density: Float): Int {
    val byHeight = hPx - (16f * density).toInt()
    val byWidth = wPx - (360f * density).toInt()
    return maxOf(320, minOf(byHeight, byWidth))
}

/**
 * Rim inset, Build #89 (GaugeView): the steel rim's centreline sits
 * this far (in `scale` units, where scale = gaugePx / 320) inside the
 * gauge view's edge. The rim stroke is 7 units wide, centred on the
 * centreline, so its outer edge is (RIM_INSET_UNITS − 3.5) * scale
 * inside the view edge — at 4f that is 0.5 * scale, ~1.7px at 1080px:
 * the rim TOUCHES the screen edges (Casey Nov 23: "edge to edge
 * literally") without being clipped. Was 18f, then 10f (#88).
 */
const val RIM_INSET_UNITS = 4f

/** Gap (px) between the rim's outer edge and the gauge view's edge,
 * from the rim geometry alone: the stroke's outer half (3.5 units)
 * eats into the inset. Pure, harness-tested. At 1080px: 1.7px. */
fun rimOuterGapPx(gaugePx: Int): Float =
    (RIM_INSET_UNITS - 3.5f) * (gaugePx / 320f)

/**
 * Height of the solo-mode trip section (the sessLive strip inside
 * innerCol, with its margins + innerCol padding), Build #89: 78dp,
 * measured from the layout simulator at two densities (218px @2.8125,
 * 156px @2.0). The landscape solo dial leaves room for it below the
 * dial so dial + trip box fit the window without clipping.
 */
const val SOLO_TRIP_STRIP_DP = 78f

/**
 * Gauge size from the WINDOW metrics only, Build #81 (replaces the
 * Build #80 layout-listener fit as the size the activity actually
 * uses). It NEVER comes from a measured view height: measured heights
 * are transient while the hierarchy settles (the ViewPager2 reparent
 * in Builds #78–#80), and a transient shrink once stuck the gauge at
 * the 320px floor on Casey's phone. The 320px floor survives only as
 * a safety net for very short screens, where the ScrollView scrolls
 * the overflow instead of shrinking the dial to nothing. Fullscreen /
 * landscape branches are the pre-#81 formulas, unchanged. Pure,
 * harness-tested.
 *
 * Build #88: `solo` is the double-tap speedo-only mode (Casey Nov 23) —
 * everything but the dial hidden, dial centered and big, in EITHER
 * orientation. Portrait solo is edge-to-edge like the normal portrait
 * gauge with no column reserve (the column is hidden); landscape solo
 * fills the height like fullscreen landscape.
 *
 * Build #89 (Casey Nov 23: "edge to edge literally" — the #88 dial
 * still showed ~35px of black margin each side): the portrait gauge
 * is EXACTLY the window width — the old `+ 8dp` bleed is gone (it
 * would clip the rim now that the GaugeView inset is ~0), and the
 * 460dp height reserve NO LONGER shrinks the portrait dial. That
 * reserve was the Build #81 one-screen fit: it capped the gauge at
 * 1047px on Casey's phone so the whole column fit without scrolling.
 * The trade is deliberate and Casey's call: the dial touches the
 * edges, and the column scrolls the small overflow instead (~25px
 * on his 1080x2340 — the strip and RIDE/TIMED boxes stay reachable
 * by scroll; on shorter screens the scroll is longer). The reserve
 * math is kept in fitGaugeForViewport() (pure, harness-tested) as
 * the documented one-screen-fit formula, but nothing sizes the
 * portrait dial from it any more. Solo portrait is likewise exactly
 * the window width (the old +8dp bled 11px per side and clipped the
 * rim — the solo dial was never truly edge-to-edge either).
 */
fun gaugeSizeForScreen(
    wPx: Int,
    hPx: Int,
    density: Float,
    landscape: Boolean,
    fullscreen: Boolean,
    solo: Boolean = false,
    recording: Boolean = false,
    diagnostics: Boolean = false,
    bvl: Boolean = false
): Pair<Int, Int> {
    // Build #89: exactly the window width — never wider (that clips
    // the rim) and never narrower (that leaves black margins).
    val full = wPx
    return when {
        solo && !landscape -> {
            val s = maxOf(320, minOf(full, hPx - (32 * density).toInt()))
            s to s
        }
        solo && landscape -> {
            // Build #89: the trip box stays visible below the dial in
            // solo mode, so the dial leaves room for it (16dp margin +
            // the 78dp trip section) instead of filling the height
            // alone — otherwise dial + trip box would clip top and
            // bottom. Portrait solo needs no shrink (dial + trip box
            // fit the tall window).
            val s = maxOf(320, minOf(wPx, hPx) - ((16f + SOLO_TRIP_STRIP_DP) * density).toInt())
            s to s
        }
        fullscreen && !landscape -> {
            val s = minOf((wPx * 0.92).toInt(), (hPx * 0.62).toInt())
            s to s
        }
        landscape && !fullscreen -> {
            val sq = landscapeGaugeSize(wPx, hPx, density)
            sq to sq
        }
        landscape || fullscreen -> {
            val s = minOf(wPx, hPx) - (16 * density).toInt()
            s to s
        }
        else -> {
            // Build #92 (Casey Nov 24): the main screen MUST fit
            // within the viewport with NO scrolling, in BOTH states —
            // the #91 design (gauge = window width, ScrollView
            // absorbs the recording overflow of 87px on his phone)
            // is reversed. The dial flexes to exactly what is left
            // after the fixed chrome, so the whole column always
            // equals the viewport. Width stays edge-to-edge (gw =
            // window width); only the HEIGHT shrinks, and only as
            // much as the fit requires. Chrome totals are sim-
            // measured at all four shapes (max, dp): header 62 +
            // divider 1 + gaps 4 + strip 54 + ride 105 + run 181 +
            // inter-box margins 2x6 + innerCol bottom padding 12 =
            // 431dp idle; + trip box 52dp = 483dp recording.
            // Floor 240px (not the old 320px — 320 would overflow
            // the 720x1280 recording state by 4px); the floor never
            // engages on the four shapes, it only guards degenerate
            // screens. Solo / landscape / fullscreen branches above
            // are untouched.
            // Build #93 (Casey Nov 24): the SAME fit, extended to the
            // two VARIABLE rows — the GPS diagnostics row and the
            // BEST VS LAST card. Both add height the #92 fit never
            // subtracted, so the column overflowed again whenever
            // either showed (diagnostics on: +76px idle / +123px
            // recording on his phone, sim-measured — his Build 92
            // screenshots). Their heights are subtracted only when
            // actually visible, read from the views' real visibility
            // in applyChromeVisibility. Solo / landscape / fullscreen
            // branches above are untouched.
            val gh = portraitGaugeHeightForFit(wPx, hPx, density, recording, diagnostics, bvl)
            full to gh
        }
    }
}

/**
 * Build #92: portrait gauge HEIGHT for a no-scroll fit. The fixed
 * chrome (header, trip box when recording, strip, RIDE box, TIMED
 * box, margins, paddings) is a constant in dp — every chrome dim
 * is dp/sp-based, so it scales with density and never with the
 * window. The gauge takes the window height minus the chrome,
 * capped at the window width (it never gets wider than the
 * screen). Pure function of window metrics + visible-row state,
 * same as gaugeSizeForScreen.
 *
 * Build #93: the two VARIABLE rows join the chrome — the GPS
 * diagnostics row (48dp, sim-measured at all four shapes) and the
 * BEST VS LAST card (128dp, sim-measured), each subtracted only
 * when actually visible. GAUGE_MIN_PX is the preferred minimum,
 * but when the chrome alone leaves less room than that (only the
 * 720x1280 recording + diagnostics + BVL state, where the chrome
 * exceeds the window by 35px), the dial takes whatever room is
 * left instead of forcing an overflow — the ScrollView absorbs
 * those few px as the safety net it was kept as.
 */
const val PORTRAIT_CHROME_IDLE_DP = 431f
const val PORTRAIT_CHROME_RECORDING_DP = 483f
const val PORTRAIT_DIAG_ROW_DP = 48f
const val PORTRAIT_BVL_CARD_DP = 128f
const val GAUGE_MIN_PX = 240

fun portraitGaugeHeightForFit(
    wPx: Int,
    hPx: Int,
    density: Float,
    recording: Boolean,
    diagnostics: Boolean = false,
    bvl: Boolean = false
): Int {
    var chromeDp =
        if (recording) PORTRAIT_CHROME_RECORDING_DP else PORTRAIT_CHROME_IDLE_DP
    if (diagnostics) chromeDp += PORTRAIT_DIAG_ROW_DP
    if (bvl) chromeDp += PORTRAIT_BVL_CARD_DP
    val roomPx = hPx - (chromeDp * density).toInt()
    return if (roomPx >= GAUGE_MIN_PX) minOf(wPx, roomPx) else maxOf(0, roomPx)
}

/** Which way a fling goes, for the swipe navigation (Build #81). */
enum class FlingDir { NONE, RIGHT, LEFT }

/**
 * Swipe classifier for the main screen ↔ live map navigation (Build #81,
 * loosened Build #85, TIGHTENED Build #94): a deliberate horizontal fling,
 * not a vertical scroll and not a tap. `dx`/`dy` are the fling's total
 * displacement in px, `vx` its horizontal velocity in px/s. Thresholds
 * (Build #94): at least 120px horizontally, at least 2.0x as far
 * horizontally as vertically, and at least 400 px/s horizontally.
 *
 * WHY TIGHTENED (Casey Nov 24, driving): swiping back from the live map
 * was too sensitive — panning the map IS a horizontal drag, and the #85
 * gates (60px / 1.5x / 400 px/s) let an ordinary map pan flick switch
 * pages. A deliberate thumb swipe is 300–500px and nearly horizontal
 * (ratio 3x+), a map pan is usually shorter or more diagonal, so 120px /
 * 2.0x separates them while a real swipe still passes first try. The
 * velocity gate stays 400 px/s (a casual deliberate swipe is 400–700
 * px/s); slow deliberate swipes are caught by classifySwipeRelease
 * (distance + direction only, no velocity gate). Pure, harness-tested —
 * the gesture detector in MainActivity only calls showLiveMapPage /
 * showMainPage when this returns RIGHT/LEFT.
 */
fun classifyFling(dx: Float, dy: Float, vx: Float): FlingDir {
    val adx = kotlin.math.abs(dx)
    val ady = kotlin.math.abs(dy)
    if (adx < 120f) return FlingDir.NONE
    if (adx < 2.0f * ady) return FlingDir.NONE
    if (kotlin.math.abs(vx) < 400f) return FlingDir.NONE
    return if (dx > 0) FlingDir.RIGHT else FlingDir.LEFT
}

/**
 * Drag-release classifier (Build #85, TIGHTENED Build #94): the
 * slow-swipe half of the navigation. A fling needs velocity; a
 * deliberate drag does not — the old ViewPager2 switched pages on ANY
 * horizontal drag past its slop, and a slow drag that never becomes a
 * fling currently does NOTHING, which also reads as "swiping doesn't
 * work". On finger-up, if the total displacement is at least 280px
 * horizontally and at least 2.0x as far horizontally as vertically,
 * switch pages in that direction. No velocity gate: the displacement +
 * direction gates already reject taps (near-zero displacement) and
 * vertical scrolls (vertical-dominant).
 *
 * WHY 280px / 2.0x (Casey Nov 24, driving): the #85 gates (100px /
 * 1.5x) fired on ordinary map pans — panning the live map sideways
 * IS a 100–250px mostly-horizontal drag, so looking around the map
 * kept throwing him back to the speedo. 280px is ~100dp on his phone
 * (~26% of the screen width): a deliberate thumb swipe is 300–500px,
 * a pan to look around is usually shorter, and a diagonal pan fails
 * the 2.0x ratio. Honest limit: a LONG, nearly-horizontal pan
 * (>280px, >2:1) still switches pages — that gesture is
 * indistinguishable from a swipe by distance + direction alone.
 * Pure, harness-tested — MainActivity.dispatchTouchEvent calls this on
 * ACTION_UP for single-pointer gestures only (multi-touch cancels it,
 * so pinch-zoom on the map never switches pages).
 */
fun classifySwipeRelease(dx: Float, dy: Float): FlingDir {
    val adx = kotlin.math.abs(dx)
    val ady = kotlin.math.abs(dy)
    if (adx < 280f) return FlingDir.NONE
    if (adx < 2.0f * ady) return FlingDir.NONE
    return if (dx > 0) FlingDir.RIGHT else FlingDir.LEFT
}

/**
 * One channel of a pixel under the readability scrim (Build #81):
 * a black overlay of `dimAlpha` (0–255) over a base value 0–255.
 * The Settings/Logs scrim uses dimAlpha 170 (≈67% black) so the carbon
 * weave still shows through dimmed while text reads. Pure,
 * harness-tested (the contrast check uses it).
 */
fun dimmedValue(v: Int, dimAlpha: Int): Int {
    val a = dimAlpha.coerceIn(0, 255)
    return (v * (255 - a)) / 255
}

/** Integer rect, pure-Kotlin (no android.graphics.Rect, so the JVM
 * harness can test the cover math without Android stubs). */
data class CoverRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * Cover-scale source rect, Build #80: the centered rect of a
 * `srcW` x `srcH` bitmap that, drawn into a `dstW` x `dstH` view with
 * canvas.drawBitmap(srcRect, dstRect), covers the whole view with no
 * stretching and no seams — the same center-crop coverTransform()
 * computes, but as a source rect for the plain drawBitmap path that
 * replaced the BitmapShader + local-matrix path in Backgrounds.kt.
 * Returns null for degenerate inputs. Pure, harness-tested.
 */
fun coverSrcRect(srcW: Int, srcH: Int, dstW: Int, dstH: Int): CoverRect? {
    if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return null
    val scale = maxOf(dstW.toDouble() / srcW, dstH.toDouble() / srcH)
    val w = (dstW / scale).toInt()
    val h = (dstH / scale).toInt()
    val x = (srcW - w) / 2
    val y = (srcH - h) / 2
    return CoverRect(x, y, x + w, y + h)
}

/**
 * Cover-scale transform, Build #79: how a photographic texture of
 * `srcW` x `srcH` is placed into a `dstW` x `dstH` view so it covers the
 * whole view with no stretching and no seams (center-crop). Returns
 * (scale, dx, dy): draw the bitmap scaled by `scale`, offset by (dx, dy)
 * from the view's top-left. Degenerate inputs return the identity.
 * Pure, harness-tested.
 */
fun coverTransform(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Triple<Double, Double, Double> {
    if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return Triple(1.0, 0.0, 0.0)
    val scale = maxOf(dstW.toDouble() / srcW, dstH.toDouble() / srcH)
    val dx = (dstW - srcW * scale) / 2.0
    val dy = (dstH - srcH * scale) / 2.0
    return Triple(scale, dx, dy)
}

fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = Math.PI / 180.0
    val dLat = (lat2 - lat1) * r
    val dLon = (lon2 - lon1) * r
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(lat1 * r) * Math.cos(lat2 * r) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
    return 6371000.0 * 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}

/** Timer format from the web app: <10 s shows 3 decimals, otherwise 2. */
fun fmtTimer(ms: Long): String {
    val s = ms / 1000.0
    return if (ms < 10000) String.format(Locale.US, "%.3f", s)
    else String.format(Locale.US, "%.2f", s)
}

fun fmtDur(totalSec: Int): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    else "$m:${s.toString().padStart(2, '0')}"
}

fun rideName(date: Date): String =
    SimpleDateFormat("EEE, MMM d hh:mm a", Locale.US).format(date)

fun runDate(date: Date): String =
    SimpleDateFormat("MMM d  hh:mm a", Locale.US).format(date)

fun isoNow(date: Date): String {
    val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    f.timeZone = TimeZone.getTimeZone("UTC")
    return f.format(date)
}
