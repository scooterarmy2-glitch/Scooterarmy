package com.scooterarmy.app

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.scooterarmy.app.databinding.DialogLogDetailBinding

/**
 * The tap-a-log popup: huge value on top, then labelled rows, CLOSE below.
 * For rides with a recorded route a VIEW ROUTE button is added above CLOSE.
 */
object LogDetailDialog {

    fun show(
        context: Context,
        title: String,
        big: String,
        bigLbl: String,
        rows: List<Pair<String, String>>,
        onViewRoute: (() -> Unit)? = null,
        onExportCsv: (() -> Unit)? = null,
        onExportGpx: (() -> Unit)? = null
    ) {
        val binding = DialogLogDetailBinding.inflate(LayoutInflater.from(context))
        binding.logTitle.text = title
        binding.logBig.text = big
        binding.logBigLbl.text = bigLbl

        binding.logRows.removeAllViews()
        val inflater = LayoutInflater.from(context)
        for ((label, value) in rows) {
            val row = inflater.inflate(R.layout.dialog_log_row, binding.logRows, false)
            row.findViewById<TextView>(R.id.rowLabel).text = label
            row.findViewById<TextView>(R.id.rowValue).text = value
            binding.logRows.addView(row)
        }

        val dialog = Dialog(context)
        dialog.setContentView(binding.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        if (onViewRoute != null) {
            binding.viewRouteBtn.visibility = View.VISIBLE
            binding.viewRouteBtn.setOnClickListener { onViewRoute() }
        }
        // Per-entry export: CSV for rides and runs, GPX for rides (route).
        // The caller owns the SAF picker; the dialog just fires the callback.
        if (onExportCsv != null) {
            binding.exportCsvBtn.visibility = View.VISIBLE
            binding.exportCsvBtn.setOnClickListener { onExportCsv() }
        }
        if (onExportGpx != null) {
            binding.exportGpxBtn.visibility = View.VISIBLE
            binding.exportGpxBtn.setOnClickListener { onExportGpx() }
        }
        binding.logClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
        dialog.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.92).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
}

/** Builds the average-speed row for a ride, when duration + distance parse. */
fun rideAvgSpeedRow(ride: RideRecord): Pair<String, String>? {
    val dm = Regex("""([\d.]+)\s*(mi|km)""").find(ride.dist) ?: return null
    // Duration is "m:ss" or "h:mm:ss" — parse every part. The old regex
    // matched only the first two numbers, so a 1:02:34 ride read as 1.03 min.
    val parts = ride.dur.split(":").mapNotNull { it.toDoubleOrNull() }
    if (parts.isEmpty()) return null
    val totalSec = when (parts.size) {
        1 -> parts[0]
        2 -> parts[0] * 60 + parts[1]
        else -> parts[0] * 3600 + parts[1] * 60 + parts[2]
    }
    if (totalSec <= 0) return null
    // Distances are stored in miles now. A legacy ride saved while the
    // metric toggle still existed may read "km" — convert it so the
    // average always shows in mph.
    val distVal = dm.groupValues[1].toDoubleOrNull() ?: return null
    val distMi = if (dm.groupValues[2] == "km") distVal * 0.621371 else distVal
    val avg = distMi / (totalSec / 3600.0)
    return "Avg Speed" to String.format("%.1f mph", avg)
}
