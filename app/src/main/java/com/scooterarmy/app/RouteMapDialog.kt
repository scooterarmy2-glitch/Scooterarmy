package com.scooterarmy.app

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.view.LayoutInflater
import com.scooterarmy.app.databinding.DialogRouteBinding
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

/**
 * VIEW ROUTE: an OpenStreetMap map (no API key) with the recorded ride as an
 * orange line, green start dot, red end dot. Tiles need internet to view;
 * recording itself is fully offline. Tile cache lives in the app's private
 * files dir so no storage permission is needed.
 */
object RouteMapDialog {

    fun configure(context: Context) {
        val config = Configuration.getInstance()
        config.userAgentValue = "scooterarmy-gps/1.1.0"
        config.osmdroidBasePath = File(context.filesDir, "osmdroid")
        config.osmdroidTileCache = File(context.filesDir, "osmdroid/tiles")
    }

    fun show(context: Context, ride: RideRecord) {
        if (ride.route.size < 2) return
        val binding = DialogRouteBinding.inflate(LayoutInflater.from(context))
        binding.routeTitle.text = "ROUTE — ${ride.name}"
        binding.routeSub.text = ride.dist

        val dialog = Dialog(context)
        dialog.setContentView(binding.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val map: MapView = binding.routeMap
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(15.0)

        val points = ride.route.map { GeoPoint(it.lat, it.lon) }
        val line = Polyline().apply {
            setPoints(points)
            color = Color.parseColor("#ff6600")
            width = 4f * context.resources.displayMetrics.density
        }
        map.overlays.add(line)
        map.overlays.add(marker(map, points.first(), Color.parseColor("#39ff14"), "Start"))
        map.overlays.add(marker(map, points.last(), Color.parseColor("#ff2200"), "End"))
        // Fit bounds after layout, when the map knows its size.
        map.post { map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), false, 40) }
        map.onResume()

        binding.routeClose.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { map.onPause() }
        dialog.show()
        dialog.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.92).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun marker(map: MapView, point: GeoPoint, color: Int, title: String): Marker {
        val size = (14 * map.context.resources.displayMetrics.density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.FILL
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 1, paint)
        return Marker(map).apply {
            position = point
            icon = BitmapDrawable(map.context.resources, bmp)
            this.title = title
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        }
    }
}
