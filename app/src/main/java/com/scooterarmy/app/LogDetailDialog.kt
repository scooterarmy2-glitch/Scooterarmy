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
        onViewRoute: (() -> Unit)? = null
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
    val tm = Regex("""(?:(\d+):)?(\d+)""").find(ride.dur) ?: return null
    val mins = (tm.groupValues[1].toDoubleOrNull() ?: 0.0) + (tm.groupValues[2].toDoubleOrNull() ?: 0.0) / 60.0
    if (mins <= 0) return null
    val unit = if (dm.groupValues[2] == "km") "km/h" else "mph"
    val avg = dm.groupValues[1].toDoubleOrNull()?.div(mins / 60.0) ?: return null
    return "Avg Speed" to String.format("%.1f %s", avg, unit)
}
