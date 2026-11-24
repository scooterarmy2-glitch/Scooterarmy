package com.scooterarmy.app


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
const val BUILD_NUMBER = 88

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
 * Gauge size from the WINDOW metrics only, Build #81 (replaces the
 * Build #80 layout-listener fit as the size the activity actually
 * uses): the portrait gauge is the smaller of (screen width + 8dp,
 * so it bleeds slightly off the left/right edges) and (screen height
 * minus the fixed design height of the rest of the portrait column —
 * header, status strip, RIDE box, TIMED box, paddings — 460dp, measured
 * from the Build #85 layout simulator on the fixed natural heights:
 * non-gauge column 1274px at density 2.8125 = 453dp, and 912px at
 * density 2.0 = 456dp; 460dp covers the worst case plus font-metric
 * variance. (Build #80 measured 451dp on the same natural heights; the
 * reserve was re-measured after the bitmap-floor fix because the gauge
 * must now fit the real column, not the broken one.) It NEVER comes
 * from a measured view height:
 * measured heights are transient while the hierarchy settles (the
 * ViewPager2 reparent in Builds #78–#80), and a transient shrink once
 * stuck the gauge at the 320px floor on Casey's phone. The 320px floor
 * survives only as a safety net for very short screens, where the
 * ScrollView scrolls the overflow instead of shrinking the dial to
 * nothing. Fullscreen / landscape branches are the pre-#81 formulas,
 * unchanged. Pure, harness-tested.
 *
 * Build #88: `solo` is the double-tap speedo-only mode (Casey Nov 23) —
 * everything but the dial hidden, dial centered and big, in EITHER
 * orientation. Portrait solo is edge-to-edge like the normal portrait
 * gauge (width + 8dp) with no column reserve (the column is hidden);
 * landscape solo fills the height like fullscreen landscape.
 */
fun gaugeSizeForScreen(
    wPx: Int,
    hPx: Int,
    density: Float,
    landscape: Boolean,
    fullscreen: Boolean,
    solo: Boolean = false
): Pair<Int, Int> {
    val reservedPx = (460f * density).toInt()
    val full = wPx + (8 * density).toInt()
    return when {
        solo && !landscape -> {
            val s = maxOf(320, minOf(full, hPx - (32 * density).toInt()))
            s to s
        }
        solo && landscape -> {
            val s = maxOf(320, minOf(wPx, hPx) - (16 * density).toInt())
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
            val s = maxOf(320, minOf(full, hPx - reservedPx))
            s to s
        }
    }
}

/** Which way a fling goes, for the swipe navigation (Build #81). */
enum class FlingDir { NONE, RIGHT, LEFT }

/**
 * Swipe classifier for the main screen ↔ live map navigation (Build #81,
 * loosened Build #85): a deliberate horizontal fling, not a vertical
 * scroll and not a tap. `dx`/`dy` are the fling's total displacement in
 * px, `vx` its horizontal velocity in px/s. Thresholds (Build #85):
 * at least 60px horizontally, at least 1.5x as far horizontally as
 * vertically, and at least 400 px/s horizontally.
 *
 * WHY LOOSENED: the #81 gates (100px / 2:1 / 800 px/s) were too strict
 * for a real thumb swipe — a casual deliberate swipe is 400–700 px/s
 * and often drifts more than 26.5° off horizontal, so it failed ALL
 * THREE gates and Casey reported (Nov 23) that "swiping doesn't work
 * on latest build at all". 400 px/s is still ~3x the platform's own
 * fling minimum (~141 px/s at his 2.8125 density) and a tap moves
 * ~0–10px, so taps and vertical scrolls still return NONE. Slow drags
 * that never reach fling velocity are caught by classifySwipeRelease
 * (distance + direction only, no velocity gate). Pure, harness-tested —
 * the gesture detector in MainActivity only calls showLiveMapPage /
 * showMainPage when this returns RIGHT/LEFT.
 */
fun classifyFling(dx: Float, dy: Float, vx: Float): FlingDir {
    val adx = kotlin.math.abs(dx)
    val ady = kotlin.math.abs(dy)
    if (adx < 60f) return FlingDir.NONE
    if (adx < 1.5f * ady) return FlingDir.NONE
    if (kotlin.math.abs(vx) < 400f) return FlingDir.NONE
    return if (dx > 0) FlingDir.RIGHT else FlingDir.LEFT
}

/**
 * Drag-release classifier (Build #85): the slow-swipe half of the
 * navigation. A fling needs velocity; a deliberate drag does not — the
 * old ViewPager2 switched pages on ANY horizontal drag past its slop,
 * and a slow drag that never becomes a fling currently does NOTHING,
 * which also reads as "swiping doesn't work". On finger-up, if the
 * total displacement is at least 100px horizontally and at least 1.5x
 * as far horizontally as vertically, switch pages in that direction.
 * No velocity gate: the displacement + direction gates already reject
 * taps (near-zero displacement) and vertical scrolls (vertical-dominant).
 * Pure, harness-tested — MainActivity.dispatchTouchEvent calls this on
 * ACTION_UP for single-pointer gestures only (multi-touch cancels it,
 * so pinch-zoom on the map never switches pages).
 */
fun classifySwipeRelease(dx: Float, dy: Float): FlingDir {
    val adx = kotlin.math.abs(dx)
    val ady = kotlin.math.abs(dy)
    if (adx < 100f) return FlingDir.NONE
    if (adx < 1.5f * ady) return FlingDir.NONE
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
