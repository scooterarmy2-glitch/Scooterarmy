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
 * Esri dark tiles — free, no key, no account (look-pro, Nov 23).
 *
 * WHY NOT CARTO: the old dark tile source was CARTO dark_matter
 * (basemaps.cartocdn.com/dark_all), which used to be keyless. It is not
 * anymore — CARTO now returns HTTP 200 with a tile stamped "API KEY
 * REQUIRED" instead of map data. Esri's Canvas/World_Dark_Gray_Base
 * endpoint is free, needs no key, and serves real dark street tiles
 * (verified live in look-v2: HTTP 200, JPEG 256x256, highway shields
 * over Glasgow, KY). One quirk: Esri's URL order is {z}/{y}/{x} while
 * osmdroid's built-in sources use {z}/{x}/{y}, so this needs a custom
 * OnlineTileSourceBase that swaps x and y. No key string exists anywhere
 * in this file or the app.
 */
object EsriDarkTiles {

    private const val BASE =
        "https://services.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/"

    /**
     * Highest zoom with REAL tile data, Build #78. Esri serves genuine
     * tiles only to z16 — z17/z18/z19 all return the same 2521-byte
     * uniform (204,204,204) blank tile (verified by curl, Nov 23), so
     * the tile SOURCE stays capped at 16: the downloader never fetches
     * past real data, and no blank tile can ever be cached.
     *
     * The MAP, though, may zoom to MAP_MAX_ZOOM (Build #86): osmdroid
     * 6.1.18 ships a MapTileApproximater in the provider chain — it sits
     * BEFORE the downloader and scales the cached lower-zoom tile up
     * (verified in the shipped classes.jar: Approximater$TileLoader
     * calls approximateTileFromLowerZoom, and MapTileProviderArray
     * skips any provider whose zoom range does not cover the request,
     * so the downloader is never even asked for z17/z18). Both maps
     * open at z16 first, so the parent tiles are cached before the user
     * can pinch in. Past 16 the picture is a 2x/4x upscale of real
     * z16 data — softer, but real map, never blank.
     */
    const val MAX_ZOOM = 16

    /** Highest zoom the MapView lets the user pinch to (Build #86).
     * Served by the approximater from cached z16 tiles — see MAX_ZOOM. */
    const val MAP_MAX_ZOOM = 18

    /** Deep dark navy the loading map shows instead of light grey —
     * Build #86: matched to the tinted base (the target video frame's
     * base measures (8,24,48) — deep dark navy, near black-blue). */
    const val NAVY_BG = "#07172f"

    /**
     * Navy tint for the grey Esri tiles — Build #86 rework (was #78).
     * Casey sent the target video frame (Nov 23): the map base is
     * DEEP dark navy, near black-blue, and the street labels are WHITE
     * and clearly readable. The old #78 matrix (0.50/0.60/0.80) kept
     * the base navy but crushed the labels to dim blue-grey
     * (150 → (50,72,125)) — barely readable, which is what Casey saw.
     *
     * The fix is a steep per-channel linear map anchored on the REAL
     * tile's measured values (Glasgow z16 tile, PIL, Nov 23): the tile
     * has NO pixels brighter than 154 — the brightest pixels ARE the
     * labels (max lum 154, p99.9 = 137, 86% of pixels are the 71 base).
     * So the top anchor is 154, not 255:
     *   R' = 2.73R − 186.2, G' = 2.60G − 160.8, B' = 2.49B − 134.1
     * Verified on the real tile:
     *   base (71,71,73) → (7,23,47) deep dark navy (frame: (8,24,48))
     *   street grey (100,100,102) → (87,99,115) visible slate
     *   street grey (119,119,121) → (139,149,162) clearly readable
     *   label (137,137,139) → (187,194,207) bright
     *   label core (154,154,156) → (234,239,254) near-white
     * Label-vs-base contrast is ~13:1. The route line (#00b4ff) and
     * the Start/End pins are drawn ABOVE the tiles, so the tint never
     * touches them. No headline is added — Casey cut the "GPX + CSV
     * EXPORT. YOURS TO KEEP." line; the Export GPX / Export CSV
     * buttons stay without it.
     */
    val NAVY_MATRIX = floatArrayOf(
        2.73f, 0f, 0f, 0f, -186.2f,
        0f, 2.60f, 0f, 0f, -160.8f,
        0f, 0f, 2.49f, 0f, -134.1f,
        0f, 0f, 0f, 1f, 0f
    )

    /** Pure form of the matrix above, for the JVM harness. Truncates
     * like the verified PIL math, so (71,71,73) → (7,23,47) and
     * (154,154,156) → (234,239,254). */
    fun navyTintRgb(r: Int, g: Int, b: Int): Triple<Int, Int, Int> =
        Triple(
            (2.73 * r - 186.2).toInt().coerceIn(0, 255),
            (2.60 * g - 160.8).toInt().coerceIn(0, 255),
            (2.49 * b - 134.1).toInt().coerceIn(0, 255)
        )

    /** One call that makes a MapView navy + zoom-capped: background,
     * max zoom, and the tint on the tiles overlay. Call AFTER
     * setTileSource(...), so the overlay exists. Shared by the route
     * dialog and the live map — one definition, one matrix. The map
     * max is MAP_MAX_ZOOM (18, approximater-scaled), NOT the source
     * max — the source stays at 16 so the downloader never fetches
     * blank tiles. */
    fun applyNavy(map: MapView) {
        map.setBackgroundColor(Color.parseColor(NAVY_BG))
        map.maxZoomLevel = MAP_MAX_ZOOM.toDouble()
        val tiles = map.overlayManager.tilesOverlay
        tiles.setColorFilter(
            android.graphics.ColorMatrixColorFilter(
                android.graphics.ColorMatrix(NAVY_MATRIX)
            )
        )
        // The overlay paints its OWN loading background over the
        // MapView's while tiles are in flight — make that navy too,
        // or the map still flashes light grey on first load. That grey
        // is what sat behind the "Map data not yet available" text in
        // Casey's screenshot. The loading line (the placeholder text)
        // gets a muted steel blue so it stays readable on navy.
        tiles.setLoadingBackgroundColor(Color.parseColor(NAVY_BG))
        tiles.setLoadingLineColor(Color.parseColor("#5a7aa8"))
    }

    /** Fresh source per map — osmdroid tile sources are cheap to build. */
    fun source(): OnlineTileSourceBase = object : OnlineTileSourceBase(
        "EsriDarkGray", 0, MAX_ZOOM, 256, ".jpg", arrayOf(BASE)
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

    /**
     * Street-label overlay, Build #89 (Casey Nov 23: "keep the street
     * labels and stuff above the traced route. Don't let the router
     * cover the labels"). The street names are BAKED INTO the base
     * tiles, so no reordering of the base layer can ever put them
     * above the route polyline — the polyline is an overlay and always
     * draws over the tiles. The fix is a second tile layer drawn
     * ABOVE the route: Esri's Reference/World_Transportation service
     * carries the same street names (plus highway shields) as
     * transparent PNG tiles, designed to sit on top of a basemap.
     *
     * WHY NOT the Canvas reference: Canvas/World_Dark_Gray_Reference
     * is the "matching" companion to the dark base, but it carries
     * ONLY place labels ("Nashville" at z12) — verified Nov 24: its
     * z16 tiles are fully transparent even over downtown Nashville
     * and Manhattan, so it has no street names at any zoom that
     * matters. World_Transportation was verified live Nov 24 over
     * Glasgow, KY: real street labels at z15–z18 ("W Main St",
     * "Cumberland St", "Samson St", "W Front St", highway shield 90)
     * in a light tan that the navy tint turns white (see below).
     * Free, no key, no account — same as the base. URL order is
     * z/y/x like every Esri service.
     *
     * Source max is MAP_MAX_ZOOM (18), NOT the base's 16: the
     * transportation service serves REAL tiles at z17 (1079 label px
     * over Glasgow) and z18 (243 px) — verified by curl Nov 24 —
     * unlike the base, whose z17+ tiles are the uniform blank. So
     * labels stay real at every zoom the map allows; no approximater
     * upscaling is involved.
     *
     * The overlay gets the SAME navy tint as the base (applyNavy's
     * matrix): the tan label text (229,211,170) maps to white
     * (255,255,255), the black label halos stay black, and the dark
     * road lines in the layer stay dark — invisible on navy, which
     * is what we want (only the LABELS come from this layer; the
     * roads stay the base's). Alpha is untouched by the matrix, so
     * the transparent tile background stays transparent.
     *
     * Callers add it AFTER the route polyline and BEFORE the pins,
     * so the draw order is: base tiles < route line < labels < pins
     * and position dot. The overlay fetches its own tiles into the
     * same osmdroid cache (separate subfolder per source name).
     */
    private const val REF_BASE =
        "https://services.arcgisonline.com/ArcGIS/rest/services/Reference/World_Transportation/MapServer/tile/"

    /** Fresh reference source per map — same z/y/x swap as the base. */
    fun referenceSource(): OnlineTileSourceBase = object : OnlineTileSourceBase(
        "EsriTransportRef", 0, MAP_MAX_ZOOM, 256, ".png", arrayOf(REF_BASE)
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val z = MapTileIndex.getZoom(pMapTileIndex)
            val x = MapTileIndex.getX(pMapTileIndex)
            val y = MapTileIndex.getY(pMapTileIndex)
            // Esri order is z/y/x — NOT the usual z/x/y.
            return "$REF_BASE$z/$y/$x"
        }
    }

    /** Pure URL math, exercised by the JVM harness (no key anywhere). */
    fun referenceUrl(z: Int, x: Int, y: Int): String = "$REF_BASE$z/$y/$x"

    /** Labels overlay, tinted navy like the base, appended to the
     * map's overlay list — call AFTER adding the route polyline so
     * the street names draw on top of it. Returns the overlay. */
    fun addLabelsOverlay(map: MapView): org.osmdroid.views.overlay.TilesOverlay {
        val overlay = org.osmdroid.views.overlay.TilesOverlay(
            org.osmdroid.tileprovider.MapTileProviderBasic(
                map.context, referenceSource()
            ),
            map.context
        )
        overlay.setColorFilter(
            android.graphics.ColorMatrixColorFilter(
                android.graphics.ColorMatrix(NAVY_MATRIX)
            )
        )
        map.overlays.add(overlay)
        return overlay
    }
}

/**
 * VIEW ROUTE (look-pro): a dark Esri map (no API key) with the recorded
 * ride as a bright blue line, green Start pin, red End pin — like the
 * approved map render. One stats row (distance / time / top speed, the
 * ride's real numbers) and blue Export GPX / Export CSV buttons (the
 * "Export Data" title row was cut in Build #90, Casey Nov 23). The
 * export lambdas come from LogsActivity — this dialog only wires the
 * buttons, it never builds export content. Build #90: FULL SCREEN —
 * the dialog fills the display and the map takes all remaining space.
 * Tiles need internet to view; recording itself is fully offline. Tile
 * cache lives in the app's private files dir so no storage permission
 * is needed.
 */
object RouteMapDialog {

    fun configure(context: Context) {
        val config = Configuration.getInstance()
        config.userAgentValue = "scooterarmy-gps/1.1.0"
        config.osmdroidBasePath = File(context.filesDir, "osmdroid")
        config.osmdroidTileCache = File(context.filesDir, "osmdroid/tiles")
    }

    fun show(
        context: Context,
        ride: RideRecord,
        onExportGpx: (() -> Unit)? = null,
        onExportCsv: (() -> Unit)? = null
    ) {
        if (ride.route.size < 2) return
        val binding = DialogRouteBinding.inflate(LayoutInflater.from(context))
        // Build #86: code-built brushed-steel backgrounds with zero
        // minimum size (Backgrounds.kt) — the old dialog_bg / panel_bg
        // XMLs floored this dialog's root and stats panel at 1000px
        // (the Build #79–#84 bitmap-floor defect), pushing the Export
        // GPX / Export CSV buttons below the screen.
        // Build #91: the root takes DIALOG_FULL — square corners, so
        // the truly full-screen window shows no Logs screen at the
        // corners (the rounded DIALOG variant stays on the log-detail
        // dialog, which is still floating).
        binding.root.background =
            brushedPanelBackground(context.resources, PanelVariant.DIALOG_FULL)
        binding.routeStatsPanel.background =
            brushedPanelBackground(context.resources, PanelVariant.PANEL)
        binding.routeTitle.text = "ROUTE — ${ride.name}"
        binding.statDistVal.text = ride.dist
        binding.statTimeVal.text = ride.dur
        binding.statTopVal.text = ride.topSpd

        // Build #91: TRULY full-screen dialog — the theme is the fix.
        // Build #90's plain Dialog() used the default FLOATING dialog
        // theme, so setLayout(MATCH_PARENT) still left an inset panel
        // with the Logs header peeking above it. RouteFullscreenDialog
        // (themes.xml) is non-floating + fullscreen, so the window
        // covers the entire display edge-to-edge. The theme MUST be
        // passed to the constructor — it is read there, not later.
        val dialog = Dialog(context, R.style.RouteFullscreenDialog)
        dialog.setContentView(binding.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val map: MapView = binding.routeMap
        map.setTileSource(EsriDarkTiles.source())
        // Navy + zoom cap (Build #86: map max 18, source max 16) —
        // after setTileSource, so the tiles overlay exists for the tint.
        EsriDarkTiles.applyNavy(map)
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
        // Build #89: street labels ABOVE the route — the labels are
        // baked into the base tiles, so the polyline always covered
        // them. The transportation reference overlay draws its own
        // street names on top of the route (added after the line,
        // before the pins, so the pins still sit on top of everything).
        EsriDarkTiles.addLabelsOverlay(map)
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
        binding.routeExportGpx.setOnClickListener { onExportGpx?.invoke() }
        binding.routeExportCsv.setOnClickListener { onExportCsv?.invoke() }
        binding.routeClose.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { map.onPause() }
        dialog.show()
        // Build #91 (Casey Nov 24 — the route view must be TRULY full
        // screen): the #90 MATCH_PARENT resize was not enough, because
        // a plain Dialog() is a FLOATING window — the window manager
        // insets it from the display edges and never lets it cover the
        // status-bar area, so the Logs header peeked above it. The
        // non-floating fullscreen theme (constructor, above) is the
        // real fix; these flags finish it: FLAG_LAYOUT_NO_LIMITS lets
        // the window extend under the status + navigation bars (full
        // display, cutout included), and the immersive flags keep the
        // system bars hidden while the route is up, same as the main
        // screen's setImmersive(). The ROUTE title, map, stats row,
        // Export GPX / Export CSV and CLOSE all live inside this
        // full-screen window — nothing of the Logs screen shows.
        dialog.window?.apply {
            setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundDrawableResource(android.R.color.transparent)
            decorView.setPadding(0, 0, 0, 0)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            decorView.systemUiVisibility =
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
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
            // zoomToBoundingBox is unbounded — a small route can push
            // past the map max (18, approximater-scaled past the z16
            // real-data ceiling). Clamp it so the map stays on data.
            if (map.zoomLevelDouble > EsriDarkTiles.MAP_MAX_ZOOM.toDouble()) {
                map.controller.setZoom(EsriDarkTiles.MAP_MAX_ZOOM.toDouble())
            }
        }
        map.invalidate()
    }

    /** Teardrop map pin (like the render's Start/End pins): colored body,
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
