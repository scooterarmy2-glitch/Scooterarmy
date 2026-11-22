package com.scooterarmy.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * All persistence lives here: ride log, timed-run log, speed alert, unit choice.
 * SharedPreferences + JSON — no database, no accounts, nothing leaves the phone.
 * Writes are synchronous (commit) for the logs so a STOP RIDE is durable before
 * the list re-renders; that is what makes the log update live and reliably.
 */
class LogStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("samo_gps", Context.MODE_PRIVATE)

    var rides: MutableList<RideRecord>
        get() = loadRides()
        set(value) = saveRides(value)

    var runs: MutableList<RunRecord>
        get() = loadRuns()
        set(value) = saveRuns(value)

    var alertMph: Double
        get() = prefs.getString("sa_alert", "")?.toDoubleOrNull() ?: 0.0
        set(value) = prefs.edit().putString("sa_alert", if (value > 0) value.toString() else "").apply()

    var useMetric: Boolean
        get() = prefs.getBoolean("use_metric", false)
        set(value) = prefs.edit().putBoolean("use_metric", value).apply()

    private fun loadRides(): MutableList<RideRecord> {
        val out = mutableListOf<RideRecord>()
        try {
            val arr = JSONArray(prefs.getString("sa_sessions", "[]") ?: "[]")
            // Skip a corrupt entry instead of aborting the whole load —
            // one bad row used to hide every ride after it.
            for (i in 0 until arr.length()) {
                try { out += RideRecord.fromJson(arr.getJSONObject(i)) } catch (_: Exception) {}
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun saveRides(list: List<RideRecord>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("sa_sessions", arr.toString()).commit()
    }

    private fun loadRuns(): MutableList<RunRecord> {
        val out = mutableListOf<RunRecord>()
        try {
            val arr = JSONArray(prefs.getString("sa_v2_hist", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                try { out += RunRecord.fromJson(arr.getJSONObject(i)) } catch (_: Exception) {}
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun saveRuns(list: List<RunRecord>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("sa_v2_hist", arr.toString()).commit()
    }
}
