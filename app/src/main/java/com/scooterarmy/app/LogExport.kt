package com.scooterarmy.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Log export content builders — pure functions, no Android UI.
 * The text produced here is exactly what the old MainActivity export wrote
 * (same headers, same escaping, same GPX shape), so a file exported from
 * the logs page is byte-identical to one exported from the old main screen
 * for the same log data. Bulk and per-entry exports both go through here:
 * a per-entry export is just a one-element list.
 */
object LogExport {

    fun csvEscape(v: String): String =
        "\"" + v.replace("\"", "\"\"") + "\""

    fun escapeXml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun fileDate(date: Date = Date()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)

    fun rideCsv(rides: List<RideRecord>): String {
        val rows = mutableListOf("Date,Duration,Distance,Top Speed")
        for (s in rides) {
            rows += listOf(s.name, s.dur, s.dist, s.topSpd)
                .joinToString(",") { csvEscape(it) }
        }
        return rows.joinToString("\n")
    }

    fun rideGpx(rides: List<RideRecord>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        sb.append("<gpx version=\"1.1\" creator=\"#SAMO GPS\" xmlns=\"http://www.topografix.com/GPX/1/1\">")
        // One formatter for the whole export — building one per route
        // point (up to 2000 per ride) was pure waste.
        val gpxTimeFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        for (s in rides) {
            if (s.route.isEmpty()) continue
            sb.append("<trk><name>").append(escapeXml(s.name)).append("</name><trkseg>")
            for (p in s.route) {
                sb.append("<trkpt lat=\"").append(p.lat).append("\" lon=\"").append(p.lon)
                    .append("\"><time>")
                    .append(gpxTimeFmt.format(Date(p.t)))
                    .append("</time></trkpt>")
            }
            sb.append("</trkseg></trk>")
        }
        sb.append("</gpx>")
        return sb.toString()
    }

    fun runCsv(runs: List<RunRecord>): String {
        val rows = mutableListOf("Date,Run,Time (s),Best")
        for (r in runs) {
            rows += listOf(r.date, r.runLbl, r.time, if (r.isBest) "YES" else "")
                .joinToString(",") { csvEscape(it) }
        }
        return rows.joinToString("\n")
    }
}
