package com.scooterarmy.app

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.view.LayoutInflater
import android.view.View
import com.scooterarmy.app.databinding.DialogRouteBinding
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

/**
 * VIEW ROUTE + live map tiles — Esri World Dark Gray Base.
 *
 * WHY NOT CARTO: the old dark tile source was CARTO dark_matter
 * (basemaps.cartocdn.com/dark_all), which used to be keyless. It is not
 * anymore — CARTO now returns HTTP 200 with a tile stamped "API KEY
 * REQUIRED" instead of map data, so every map in the app filled with
 * that message and showed nothing. That is exactly the "says it needs
 * api key" Casey saw. CARTO is gone from this build entirely.
 *
 * Esri's Canvas/World_Dark_Gray_Base endpoint is free, needs no key and
 * no account, serves real dark street tiles (verified live: HTTP 200,
 * JPEG 256x256, highway shields visible over Glasgow, KY), and matches
 * the map frame's dark look. One quirk: Esri's URL order is
 * {z}/{y}/{x} while osmdroid's built-in sources use {z}/{x}/{y}, so
 * this needs a custom OnlineTileSourceBase that swaps x and y.
 * No key string exists anywhere in this file or the app.
 */
object EsriDarkTiles {

    private const val BASE =
        "https://services.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/"

    /** Fresh source per map — osmdroid tile sources are cheap to build. */
    fun source(): OnlineTileSourceBase = object : OnlineTileSourceBase(
        "EsriDarkGray", 0, 19, 256, ".jpg", arrayOf(BASE)
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val z = MapTileIndex.getZoom(pMapTileIndex)
            val x = MapTileIndex.getX(pMapTileIndex)
            val y = MapTileIndex.getY(pMapTileIndex)
            // Esri order is z/y/x — NOT the usual z/x/y.
            return "$BASE$z/$y/$x"
        }
    }

    /** Pure URL math, exercised by the JVM harness (no key anywhere). */
    fun tileUrl(z: Int, x: Int, y: Int): String = "$BASE$z/$y/$x"
}

/**
 * VIEW ROUTE: a dark map (no API key) with the recorded ride as a bright
 * blue line, green Start pin, red End pin — like the map frame. Tiles
 * need internet to view; recording itself is fully offline. Tile cache
 * lives in the app's private files dir so no storage permission is needed.
 */
object RouteMapDialog {

    fun configure(context: Context) {
        val config = Configuration.getInstance()
        config.userAgentValue = "scooterarmy-gps/1.1.0"
        config.osmdroidBasePath = File(context.filesDir, "osmdroid")
        config.osmdroidTileCache = File(context.filesDir, "osmdroid/tiles")
    }

    /** "Sun, Nov 22 08:02 AM" → "22 Nov" — the strip's short date, like
     *  the map mockup's "12 Nov". Names without a month pass through. */
    fun shortDate(name: String): String {
        val months = setOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
        )
        val parts = name.split(",")
        if (parts.size < 2) return name
        val toks = parts[1].trim().split(" ")
        if (toks.size < 2 || toks[0] !in months) return name
        return "${toks[1]} ${toks[0]}"
    }

    /** The summary strip's four values, each once: date, distance,
     *  duration, top speed — the ride's real numbers. */
    fun stripValues(ride: RideRecord): List<String> =
        listOf(shortDate(ride.name), ride.dist, ride.dur, ride.topSpd)

    fun show(
        context: Context,
        ride: RideRecord,
        onExportGpx: (() -> Unit)? = null,
        onExportCsv: (() -> Unit)? = null
    ) {
        if (ride.route.size < 2) return
        val binding = DialogRouteBinding.inflate(LayoutInflater.from(context))
        binding.routeTitle.text = "ROUTE — ${ride.name}"
        val strip = stripValues(ride)
        binding.stripDate.text = strip[0]
        binding.stripDist.text = strip[1]
        binding.stripDur.text = strip[2]
        binding.stripTop.text = strip[3]

        val dialog = Dialog(context)
        dialog.setContentView(binding.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val map: MapView = binding.routeMap
        map.setTileSource(EsriDarkTiles.source())
        map.setMultiTouchControls(true)
        map.setUseDataConnection(true)
        map.controller.setZoom(15.0)

        val points = ride.route.map { GeoPoint(it.lat, it.lon) }
        val line = Polyline().apply {
            setPoints(points)
            color = Color.parseColor("#00b4ff")
            width = 4f * context.resources.displayMetrics.density
        }
        map.overlays.add(line)
        map.overlays.add(pinMarker(map, points.first(), Color.parseColor("#39ff14"), "Start"))
        map.overlays.add(pinMarker(map, points.last(), Color.parseColor("#ff2200"), "End"))

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

        binding.routeExportGpx.visibility =
            if (onExportGpx != null) View.VISIBLE else View.GONE
        binding.routeExportCsv.visibility =
            if (onExportCsv != null) View.VISIBLE else View.GONE
        binding.routeExportLbl.visibility =
            if (onExportGpx != null || onExportCsv != null) View.VISIBLE else View.GONE
        binding.routeExportGpx.setOnClickListener { onExportGpx?.invoke() }
        binding.routeExportCsv.setOnClickListener { onExportCsv?.invoke() }
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

    /** Teardrop map pin (like the frame's Start/End pins): colored body,
     *  white ring, white center dot, point at the bottom. Shared with the
     *  live map on the main screen. */
    fun pinMarker(map: MapView, point: GeoPoint, color: Int, title: String): Marker {
        val d = map.context.resources.displayMetrics.density
        val w = (28 * d).toInt()
        val h = (40 * d).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = w / 2f
        val r = 11f * d
        val cy = 13f * d
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE; style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, r + 1.5f * d, ring)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, r, paint)
        val tip = Path().apply {
            moveTo(cx - r * 0.75f, cy + r * 0.45f)
            lineTo(cx + r * 0.75f, cy + r * 0.45f)
            lineTo(cx, h - 2f * d)
            close()
        }
        canvas.drawPath(tip, paint)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE; style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, 3.5f * d, dot)
        return Marker(map).apply {
            position = point
            icon = BitmapDrawable(map.context.resources, bmp)
            this.title = title
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
    }
}
