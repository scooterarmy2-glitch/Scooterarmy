package com.scooterarmy.app

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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
