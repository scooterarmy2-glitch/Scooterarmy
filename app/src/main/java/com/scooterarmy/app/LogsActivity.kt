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
 * Logs page (SpeedLog "Trips" shape, #SAMO style): the RIDES and TIMED
 * RUNS lists that used to live on the main screen. Build #90 (Casey
 * Nov 23): the totals/summary card is GONE — the screen starts with
 * RIDES. The header brand is ENGRAVED into the steel, matching the
 * main screen (ENGRAVED_* spec, Models.kt) — the chrome title was
 * cut by Casey the same day, before this build shipped.
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
        // Build #86: code-built brushed-steel backgrounds with zero
        // minimum size (Backgrounds.kt) — the old header_bg / panel_bg
        // XMLs floored the header and the summary card at 1000px tall
        // (the Build #79 bitmap-floor defect; Casey's Build #85 Logs
        // screenshot showed the giant steel panels). Build #90: the
        // summary card is GONE (Casey Nov 23), so only the header
        // gets a code-built background here.
        binding.headerRow.background =
            brushedPanelBackground(resources, PanelVariant.HEADER)
        // Build #90 revised: ENGRAVED header brand (Casey Nov 23 —
        // chrome title is OUT), matching the main screen (ENGRAVED_*
        // spec, Models.kt): dark cut body + a fine bright edge along
        // the bottom of each letter, cut into the brushed steel.
        val density = resources.displayMetrics.density
        applyEngravedTitle(binding.brandHash, density)
        applyEngravedTitle(binding.brandText, density)
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
        renderRides()
        renderRuns()
    }

    /** Build #90 revised: ENGRAVED title (Casey Nov 23 — the chrome
     * title is OUT). Dark cut body + one paint shadow: a fine bright
     * edge 1dp BELOW the glyphs, slightly softened — light catching
     * the bottom edge of the cut into the brushed steel. Same
     * treatment on "#" and "SAMO GPS", matching the main screen. */
    private fun applyEngravedTitle(tv: android.widget.TextView, density: Float) {
        tv.setTextColor(ENGRAVED_BODY_COLOR)
        tv.setShadowLayer(
            ENGRAVED_EDGE_RADIUS_DP * density, 0f,
            ENGRAVED_EDGE_DY_DP * density, ENGRAVED_EDGE_COLOR
        )
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
            // Build #86: code-built row background (zero minimum) — the
            // old log_item_bg XML floored every ride row at 1000px.
            row.root.background =
                brushedPanelBackground(resources, PanelVariant.LOG_ITEM)
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
            // Build #86: code-built row background (zero minimum) — the
            // old log_item_bg / log_item_best_bg XMLs floored every run
            // row at 1000px. Best rows keep the dark-gold border.
            row.root.background = brushedPanelBackground(
                resources,
                if (run.isBest) PanelVariant.LOG_ITEM_BEST else PanelVariant.LOG_ITEM
            )
            row.runTime.setTextColor(
                if (run.isBest) Color.parseColor("#00b4ff") else Color.parseColor("#f0f0f0")
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
