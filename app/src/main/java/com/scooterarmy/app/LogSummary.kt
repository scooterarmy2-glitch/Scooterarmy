package com.scooterarmy.app

/**
 * Logs-page summary math — pure functions, no Android UI.
 * Parses the same display strings the logs store ("3.42 mi", "12:34",
 * "1:02:34", legacy "km" distances) with the same rules as
 * rideAvgSpeedRow() in LogDetailDialog.kt, so the summary card always
 * agrees with the detail popup.
 */
data class LogSummary(
    val totalDistMi: Double,
    val totalMovingSec: Long,
    val rideCount: Int,
    val runCount: Int
)

/** Miles from a stored distance string ("3.42 mi", legacy "12.0 km"). Null if unparseable. */
fun parseDistMi(dist: String): Double? {
    val dm = Regex("""([\d.]+)\s*(mi|km)""").find(dist) ?: return null
    val v = dm.groupValues[1].toDoubleOrNull() ?: return null
    return if (dm.groupValues[2] == "km") v * 0.621371 else v
}

/** Seconds from a stored duration ("12:34" or "1:02:34"). Null if unparseable. */
fun parseDurSec(dur: String): Long? {
    val parts = dur.split(":").mapNotNull { it.toDoubleOrNull() }
    if (parts.isEmpty()) return null
    val totalSec = when (parts.size) {
        1 -> parts[0]
        2 -> parts[0] * 60 + parts[1]
        else -> parts[0] * 3600 + parts[1] * 60 + parts[2]
    }
    return if (totalSec <= 0) null else totalSec.toLong()
}

fun summarizeLogs(rides: List<RideRecord>, runs: List<RunRecord>): LogSummary {
    var dist = 0.0
    var secs = 0L
    for (r in rides) {
        parseDistMi(r.dist)?.let { dist += it }
        parseDurSec(r.dur)?.let { secs += it }
    }
    return LogSummary(dist, secs, rides.size, runs.size)
}

/** Summary-card distance: 2 decimals under 10 mi (like a single ride), 1 decimal above. */
fun formatTotalDist(mi: Double): String =
    if (mi >= 10) String.format("%.1f mi", mi) else String.format("%.2f mi", mi)
