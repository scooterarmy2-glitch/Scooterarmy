package com.scooterarmy.app

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.view.LayoutInflater
import android.view.View
import com.scooterarmy.app.databinding.DialogRouteBinding
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

/**
 * VIEW ROUTE: a dark OpenStreetMap map (no API key) with the recorded ride
 * as a bright blue line, green start dot, red end dot — the reel frame
 * (frame-map-dark.jpg). Tiles need internet to view; recording itself is
 * fully offline. Tile cache lives in the app's private files dir so no
 * storage permission is needed.
 */
object RouteMapDialog {

    /** CARTO dark_matter tiles: same OSM data, dark style. Free with
     *  attribution (the copyright string shows in osmdroid's overlay). */
    fun darkTiles(): OnlineTileSourceBase = XYTileSource(
        "CartoDark", 0, 19, 256, ".png",
        arrayOf(
            "https://a.basemaps.cartocdn.com/dark_all/",
            "https://b.basemaps.cartocdn.com/dark_all/",
            "https://c.basemaps.cartocdn.com/dark_all/"
        ),
        "© OpenStreetMap contributors © CARTO"
    )

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
        map.setTileSource(darkTiles())
        map.setMultiTouchControls(true)
        map.setUseDataConnection(true)
        map.controller.setZoom(15.0)

        val points = ride.route.map { GeoPoint(it.lat, it.lon) }
        val line = Polyline().apply {
            setPoints(points)
            color = Color.parseColor("#2f8bff")
            width = 4f * context.resources.displayMetrics.density
        }
        map.overlays.add(line)
        map.overlays.add(marker(map, points.first(), Color.parseColor("#39ff14"), "Start"))
        map.overlays.add(marker(map, points.last(), Color.parseColor("#ff2200"), "End"))

        // Fit the route only once the map actually has a size. A dialog's
        // view is 0x0 until it is shown and laid out — fitting earlier
        // (map.post before show) feeds 0 into osmdroid's zoom math, which
        // computes a NaN zoom, so no tiles can ever be requested and the
        // map stays an empty grid. This listener fires on the first real
        // layout (the setLayout() resize below included) and removes
        // itself, so the fit always runs against the true map size.
        map.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
            ) {
                if (map.width > 0 && map.height > 0) {
                    map.removeOnLayoutChangeListener(this)
                    fitRoute(map, points)
                }
            }
        })

        binding.routeClose.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { map.onPause() }
        dialog.show()
        dialog.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.92).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        map.onResume()
    }

    private fun fitRoute(map: MapView, points: List<GeoPoint>) {
        val box = BoundingBox.fromGeoPoints(points)
        // A ride that barely moved has a near-zero bounding box; zooming
        // to fit it would pin the map to max zoom on one spot. Center on
        // the midpoint at a sensible zoom instead.
        val nw = GeoPoint(box.latNorth, box.lonWest)
        val se = GeoPoint(box.latSouth, box.lonEast)
        if (nw.distanceToAsDouble(se) < 100.0) {
            map.controller.setZoom(16.0)
            map.controller.setCenter(
                GeoPoint(
                    (box.latNorth + box.latSouth) / 2.0,
                    (box.lonEast + box.lonWest) / 2.0
                )
            )
        } else {
            map.zoomToBoundingBox(box, false, 40)
        }
        map.invalidate()
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
