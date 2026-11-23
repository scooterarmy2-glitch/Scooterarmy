package com.scooterarmy.app

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

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

/** 16-point compass: 0° = N, 90° = E, 180° = S, 270° = W. Pure, harness-tested. */
fun compassPoint(deg: Float?): String {
    if (deg == null || deg.isNaN()) return "—"
    val points = arrayOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
    )
    val norm = ((deg % 360f) + 360f) % 360f
    return points[((norm + 11.25f) / 22.5f).toInt() % 16]
}

/**
 * Dial heading label, look-pro: "NW 315°" while moving with a real bearing,
 * "—" when stopped or when the chip reports no bearing — never a faked
 * heading. The speed gate matters: a stopped chip can keep reporting its
 * last bearing, so bearing alone is not proof of travel.
 */
fun headingLabel(bearingDeg: Float?, speedMph: Double): String {
    if (bearingDeg == null || speedMph < 1.0) return "—"
    val deg = ((bearingDeg.roundToInt() % 360) + 360) % 360
    return "${compassPoint(bearingDeg)} $deg°"
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
