package com.scooterarmy.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import com.scooterarmy.app.databinding.ActivityLogsBinding
import com.scooterarmy.app.databinding.ItemRideBinding
import com.scooterarmy.app.databinding.ItemRunBinding

/**
 * Logs page (SpeedLog "Trips" shape, #SAMO style): summary card on top,
 * then the Rides and Timed Runs lists that used to live on the main screen.
 *
 * Reads the SAME LogStore / SharedPreferences as MainActivity — no
 * migration, no new keys, old rides and runs show up untouched.
 *
 * A ride can be in progress underneath while this page is open: this
 * activity holds no session state, so opening it (or the export picker,
 * or Settings from here) cannot disturb the ride or the timed run.
 * Per-entry and bulk exports go through the same SAF file-picker path
 * MainActivity used to use, with this activity's own `exporting` flag
 * and pending-export state.
 */
class LogsActivity : Activity() {

    private lateinit var binding: ActivityLogsBinding
    private lateinit var store: LogStore

    private var rides = mutableListOf<RideRecord>()
    private var runs = mutableListOf<RunRecord>()

    // SAF export: content waiting for the user to pick a file.
    private var pendingExport: String? = null
    private var pendingExportMime = "text/csv"
    private var exporting = false

    companion object {
        const val REQ_EXPORT = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Build #85: plain dark black background (@color/bg, the root's
        // XML background) — the carbon-weave photo is gone, so there is
        // nothing to apply and no scrim to fight.
        store = LogStore(this)
        RouteMapDialog.configure(this)

        rides = store.rides
        runs = store.runs

        binding.backBtn.setOnClickListener { finish() }
        binding.settingsBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.exportRidesCsv.setOnClickListener { exportRides("csv") }
        binding.exportRidesGpx.setOnClickListener { exportRides("gpx") }
        binding.exportRunsCsv.setOnClickListener { exportRuns() }
        binding.clearSessions.setOnClickListener { clearSessions() }
        binding.clearHistory.setOnClickListener { clearHistory() }

        renderAll()
    }

    override fun onResume() {
        super.onResume()
        // Fresh read, exactly what is stored — a ride finished while this
        // page was open (or a run saved) shows up on return.
        rides = store.rides
        runs = store.runs
        renderAll()
    }

    private fun renderAll() {
        renderSummary()
        renderRides()
        renderRuns()
    }

    private fun renderSummary() {
        val s = summarizeLogs(rides, runs)
        binding.sumDist.text = formatTotalDist(s.totalDistMi)
        binding.sumDistSub.text =
            "${s.rideCount} ride${if (s.rideCount == 1) "" else "s"}"
        binding.sumTime.text = fmtDur(s.totalMovingSec.toInt())
        binding.sumTimeSub.text =
            "${s.runCount} run${if (s.runCount == 1) "" else "s"}"
    }

    private fun renderRides() {
        binding.ridesList.removeAllViews()
        binding.noRides.visibility = if (rides.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        for (ride in rides) {
            val row = ItemRideBinding.inflate(inflater, binding.ridesList, false)
            row.rideName.text = ride.name
            row.rideMeta.text = "${ride.dur}   ${ride.dist}"
            row.rideTopSpd.text = ride.topSpd
            row.root.setOnClickListener { openRideDetail(ride) }
            row.rideDel.setOnClickListener {
                rides = rides.filter { it.id != ride.id }.toMutableList()
                store.rides = rides
                renderAll()
            }
            binding.ridesList.addView(row.root)
        }
    }

    private fun renderRuns() {
        binding.runsList.removeAllViews()
        binding.noRuns.visibility = if (runs.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        for (run in runs) {
            val row = ItemRunBinding.inflate(inflater, binding.runsList, false)
            row.runLbl.text = run.runLbl
            row.runDate.text = run.date
            row.runTime.text = "${run.time}s"
            row.runBestBadge.visibility = if (run.isBest) View.VISIBLE else View.GONE
            row.root.setBackgroundResource(if (run.isBest) R.drawable.log_item_best_bg else R.drawable.log_item_bg)
            row.runTime.setTextColor(
                if (run.isBest) Color.parseColor("#ff8c1a") else Color.parseColor("#f0f0f0")
            )
            row.root.setOnClickListener { openRunDetail(run) }
            row.runDel.setOnClickListener {
                runs = runs.filter { it.id != run.id }.toMutableList()
                store.runs = runs
                renderAll()
            }
            binding.runsList.addView(row.root)
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
                {
                    RouteMapDialog.show(
                        this, ride,
                        onExportGpx = { exportSingleRide(ride, "gpx") },
                        onExportCsv = { exportSingleRide(ride, "csv") }
                    )
                }
            } else null,
            onExportCsv = { exportSingleRide(ride, "csv") },
            onExportGpx = { exportSingleRide(ride, "gpx") }
        )
    }

    private fun openRunDetail(run: RunRecord) {
        LogDetailDialog.show(
            this, "TIMED RUN", "${run.time}s", "0 → TARGET TIME",
            listOf(
                "Run" to run.runLbl,
                "Date" to run.date,
                "Result" to if (run.isBest) "★ BEST" else "—"
            ),
            onExportCsv = { exportSingleRun(run) }
        )
    }

    private fun clearSessions() {
        rides = mutableListOf()
        store.rides = rides
        renderAll()
    }

    private fun clearHistory() {
        runs = mutableListOf()
        store.runs = runs
        renderAll()
    }

    // ── Export (Storage Access Framework — user picks where it lands) ──

    private fun exportRides(format: String) {
        if (rides.isEmpty()) {
            Toast.makeText(this, "No rides to export", Toast.LENGTH_SHORT).show()
            return
        }
        val date = LogExport.fileDate()
        if (format == "csv") {
            launchExport("samo-ride-log-$date.csv", "text/csv", LogExport.rideCsv(rides))
        } else {
            launchExport("samo-ride-log-$date.gpx", "application/gpx+xml", LogExport.rideGpx(rides))
        }
    }

    private fun exportRuns() {
        if (runs.isEmpty()) {
            Toast.makeText(this, "No runs to export", Toast.LENGTH_SHORT).show()
            return
        }
        val date = LogExport.fileDate()
        launchExport("samo-timed-runs-$date.csv", "text/csv", LogExport.runCsv(runs))
    }

    private fun exportSingleRide(ride: RideRecord, format: String) {
        val date = LogExport.fileDate()
        if (format == "csv") {
            launchExport(
                "samo-ride-$date-${ride.id}.csv", "text/csv",
                LogExport.rideCsv(listOf(ride))
            )
        } else {
            launchExport(
                "samo-ride-$date-${ride.id}.gpx", "application/gpx+xml",
                LogExport.rideGpx(listOf(ride))
            )
        }
    }

    private fun exportSingleRun(run: RunRecord) {
        val date = LogExport.fileDate()
        launchExport(
            "samo-run-$date-${run.id}.csv", "text/csv",
            LogExport.runCsv(listOf(run))
        )
    }

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
