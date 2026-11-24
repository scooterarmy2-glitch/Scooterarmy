package com.scooterarmy.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.widget.TextView
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Bottom-pill status text for the live map (pure, harness-tested).
 * "Auto-stop" is the app's real idle auto-stop setting (Settings → idle
 * minutes, 0 = off) — the pill never claims it is off when it is on.
 */
fun liveStatusText(recording: Boolean, idleMin: Int): String {
    if (!recording) return "Not recording"
    return if (idleMin > 0) "Recording ride • Auto-stop $idleMin min"
    else "Recording ride • Auto-stop off"
}

/**
 * Live map page (look-pro) — swipe-right from the speedometer, swipe back
 * (or tap ‹) to return. Draws the current ride's route LIVE in blue as
 * the fixes come in, reusing the ride's recorded route points — no second
 * recording pipeline. The map follows the rider while recording; when
 * not recording it shows just the current position, no trail, no LIVE
 * badge, no End pin. The End pin appears only once the ride is stopped.
 *
 * Touch note (Build #85): the page is a direct child of rootLayout, NOT
 * a ViewPager2 page any more (the pager is gone since Build #81) — the
 * swipe-back is MainActivity.dispatchTouchEvent (fling detector +
 * drag-release tracking), so a horizontal drag on the map switches
 * pages, same as the pager did. While recording the map auto-follows,
 * so panning is not needed; pinch-zoom and vertical panning still work
 * on the map itself, and multi-touch never triggers a page switch.
 */
class LiveMap(
    root: View,
    private val onBack: () -> Unit,
    private val idleMin: () -> Int
) {
    private val map: MapView = root.findViewById(R.id.liveMap)
    private val speedVal: TextView = root.findViewById(R.id.liveSpeedVal)
    private val distVal: TextView = root.findViewById(R.id.liveDistVal)
    private val timeVal: TextView = root.findViewById(R.id.liveTimeVal)
    private val liveBadge: TextView = root.findViewById(R.id.liveBadge)
    private val statusPill: TextView = root.findViewById(R.id.liveStatusPill)
    // Chip containers (Build #86 ids in page_live_map.xml) — their
    // backgrounds are set in code below, same recipe as the main screen.
    private val speedChip: View = root.findViewById(R.id.liveSpeedChip)
    private val distChip: View = root.findViewById(R.id.liveDistChip)
    private val timeChip: View = root.findViewById(R.id.liveTimeChip)

    private val density = map.context.resources.displayMetrics.density
    private val routeLine = Polyline().apply {
        color = Color.parseColor("#00b4ff")
        width = 4f * density
    }
    private val positionOverlay = PositionOverlay()
    private var startMarker: Marker? = null
    private var endMarker: Marker? = null
    private var lastRouteSize = -1
    private var centeredOnce = false
    private var recording = false

    init {
        // Build #86: code-built brushed-steel backgrounds with zero
        // minimum size (Backgrounds.kt) — the old chip_pill /
        // chip_pill_amber / status_pill_bg XMLs each floored their chip
        // at 1000px tall (the Build #79 bitmap-floor defect), so the
        // chips row and the pill were giant steel boxes squeezing the
        // MapView into thin strips (Casey's Build #85 screenshot).
        // Speed chip keeps the LED-orange border (CHIP_AMBER); the
        // distance/time chips take the steel-blue secondary accent
        // (CHIP_PLAIN), echoing the map's blue route line.
        val res = map.context.resources
        speedChip.background = brushedPanelBackground(res, PanelVariant.CHIP_BLUE)
        distChip.background = brushedPanelBackground(res, PanelVariant.CHIP_PLAIN)
        timeChip.background = brushedPanelBackground(res, PanelVariant.CHIP_PLAIN)
        statusPill.background = brushedPanelBackground(res, PanelVariant.PILL)
        map.setTileSource(EsriDarkTiles.source())
        // Navy + zoom cap (Build #86: map max 18, source max 16) —
        // after setTileSource, so the tiles overlay exists for the tint.
        // Same single call the route dialog uses; same matrix, same cap.
        EsriDarkTiles.applyNavy(map)
        map.setMultiTouchControls(true)
        map.setUseDataConnection(true)
        // Build #85: a home fallback centre (Glasgow, KY — Casey's town),
        // so the map shows familiar tiles on the first swipe even before
        // the first GPS fix arrives. Without it the map opens at (0, 0)
        // — ocean off Africa at zoom 16 — which reads as a blank/broken
        // page. The first real fix re-centres on the rider (centeredOnce
        // in onFix), and recording follows the rider from then on.
        map.controller.setCenter(GeoPoint(37.006, -85.921))
        map.controller.setZoom(16.0)
        map.overlays.add(routeLine)
        map.overlays.add(positionOverlay)
        root.findViewById<View>(R.id.liveMapBack).setOnClickListener { onBack() }
        setRecording(false)
    }

    fun onResume() = map.onResume()
    fun onPause() = map.onPause()

    /**
     * Re-request tiles + redraw, called when the live-map page is swiped
     * into view (Build #78, still true after the pager is gone: the map
     * is created while GONE and its first draw happens only when it
     * becomes visible). A forced redraw here makes sure tiles are
     * actually requested then, instead of leaving the "Map data not yet
     * available" placeholders on screen. Cheap — a no-op redraw when
     * tiles are already there.
     * (TilesOverlay has no invalidate() of its own — MapView.invalidate()
     * drives the overlay draw that requests missing tiles.)
     */
    fun refresh() {
        map.invalidate()
    }
    fun onDestroy() = map.onDetach()

    /** Called on every GPS fix from MainActivity.handleFix. */
    fun onFix(
        f: FixData,
        recording: Boolean,
        speedMph: Double,
        distMi: Double,
        rideSeconds: Int,
        route: List<RoutePoint>
    ) {
        speedVal.text = "${speedMph.roundToInt()} mph"
        distVal.text = String.format(Locale.US, "%.1f mi", distMi)
        timeVal.text = fmtDur(rideSeconds)
        val pos = GeoPoint(f.lat, f.lon)
        // Heading arrow only while actually moving — a stopped chip can
        // keep reporting its last bearing, which would point a lie.
        positionOverlay.setPosition(pos, if (speedMph >= 1.0) f.bearingDeg else null)
        if (recording) {
            if (route.size != lastRouteSize) {
                lastRouteSize = route.size
                routeLine.setPoints(route.map { GeoPoint(it.lat, it.lon) })
                if (startMarker == null && route.isNotEmpty()) {
                    val first = route.first()
                    startMarker = RouteMapDialog.pinMarker(
                        map, GeoPoint(first.lat, first.lon),
                        Color.parseColor("#39ff14"), "Start"
                    ).also { map.overlays.add(it) }
                }
            }
            // Follow the rider.
            map.controller.setCenter(pos)
        } else if (!centeredOnce) {
            map.controller.setCenter(pos)
            centeredOnce = true
        }
        map.invalidate()
    }

    /** Once a second while a ride runs (live tick) — keeps ride time fresh
     *  even between GPS fixes. */
    fun tick(rideSeconds: Int) {
        timeVal.text = fmtDur(rideSeconds)
    }

    fun onSessionStart() {
        recording = true
        startMarker?.let { map.overlays.remove(it) }
        endMarker?.let { map.overlays.remove(it) }
        startMarker = null
        endMarker = null
        lastRouteSize = -1
        routeLine.setPoints(emptyList())
        centeredOnce = false
        setRecording(true)
        map.invalidate()
    }

    fun onSessionStop() {
        recording = false
        val last = routeLine.points.lastOrNull()
        if (last != null && endMarker == null) {
            endMarker = RouteMapDialog.pinMarker(
                map, last, Color.parseColor("#ff2200"), "End"
            ).also { map.overlays.add(it) }
        }
        setRecording(false)
        map.invalidate()
    }

    private fun setRecording(isRecording: Boolean) {
        recording = isRecording
        liveBadge.visibility = if (isRecording) View.VISIBLE else View.GONE
        statusPill.text = liveStatusText(isRecording, idleMin())
    }

    /** Glowing current-position dot + heading arrow, drawn over the map. */
    private inner class PositionOverlay : Overlay() {
        private var pos: GeoPoint? = null
        private var bearing: Float? = null

        fun setPosition(p: GeoPoint, b: Float?) {
            pos = p
            bearing = b
        }

        override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow) return
            val p = pos ?: return
            val pt = mapView.projection.toPixels(p, null) ?: return
            val x = pt.x.toFloat()
            val y = pt.y.toFloat()
            // Soft blue glow.
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#4000b4ff"); style = Paint.Style.FILL
            }
            canvas.drawCircle(x, y, 26f * density, glow)
            // White dot with a blue ring.
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#00b4ff"); style = Paint.Style.FILL
            }
            canvas.drawCircle(x, y, 9f * density, ring)
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; style = Paint.Style.FILL
            }
            canvas.drawCircle(x, y, 5.5f * density, dot)
            // Heading arrow — points along the bearing (0° = up/north).
            val b = bearing ?: return
            val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; style = Paint.Style.FILL
            }
            val tri = Path().apply {
                moveTo(0f, -26f * density)
                lineTo(8f * density, -8f * density)
                lineTo(-8f * density, -8f * density)
                close()
            }
            canvas.save()
            canvas.translate(x, y)
            canvas.rotate(b)
            canvas.drawPath(tri, arrow)
            canvas.restore()
        }
    }
}
