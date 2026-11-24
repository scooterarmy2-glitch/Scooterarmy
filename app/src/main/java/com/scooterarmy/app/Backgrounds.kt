package com.scooterarmy.app

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * Brushed-steel panel backgrounds (Build #85).
 *
 * ROOT CAUSE OF THE BROKEN MAIN SCREEN (Builds #79–#84): the twelve
 * "photographic" panel drawables were layer-lists containing a
 * `<bitmap android:src="@drawable/brushed_metal" android:gravity="center" />`
 * item. A `<bitmap>` layer reports the bitmap's intrinsic size
 * (brushed_metal.jpg is 1600x1000, drawable-nodpi, no density scaling)
 * as the drawable's MINIMUM size, and Android floors wrap_content
 * views at their background's minimum (View.getSuggestedMinimumWidth /
 * Height). So headerRow, gpsInfo, rideBox and runBox each measured
 * 1000px tall instead of their natural ~150–500px, contentCol totalled
 * 5171px on a 2340px window, and the TOP SPEED chip (also floored at
 * 1000px tall, capped to 1038px wide by its margins) covered the whole
 * gauge. Build #84's on-device overlay proved it line by line
 * (col h5171, chip x0 y51, strip top1100); the Build #85 simulator
 * reproduces all eight overlay numbers exactly in BEFORE mode.
 *
 * THE FIX: panels and chips get their brushed-metal look from THIS
 * code-built drawable, which draws the same bitmap 1:1 centered (the
 * same placement the `<bitmap android:gravity="center">` item gave)
 * but reports NO minimum and NO intrinsic size, so wrap_content views
 * measure at their natural content height. Build #85 fixed the four
 * main-screen-only XML drawables (gps_info_bg, top_spd_bg,
 * live_strip_bg, fs_info_bg) by stripping their `<bitmap>` item and
 * re-backed the main-screen views in code. Build #86 finishes the job:
 * the eight remaining `<bitmap>` drawables (chip_pill, chip_pill_amber,
 * status_pill_bg, panel_bg, header_bg, log_item_bg, log_item_best_bg,
 * dialog_bg) are stripped too, and every view that used them is
 * re-backed in code from this factory (LiveMap chips + pill,
 * Logs header/summary/rows, route + log-detail dialogs) — so no
 * `<bitmap>` minimum remains anywhere in the app.
 *
 * BACKGROUND (Build #85, Casey Nov 23; black since #87 rev 1): the app
 * background is plain black (#080808, @color/bg on the roots). The carbon-weave
 * photo is gone — no Background option in Settings any more, the
 * carbon_bg.jpg asset is deleted, and the old photo-background
 * apparatus (PhotoBackgroundDrawable, PhotoCache, applyPhotoBackground,
 * DIMMED_ALPHA, backgroundResId) is removed. Brushed steel lives ON
 * THE BOXES / panels / chips only, via this drawable.
 *
 * COLOURS (Build #87, Casey Nov 23): BLUE / WHITE, like the maps.
 * The vivid-LED-orange primary is gone. Primary is bright blue #00b4ff
 * (the map's route blue) as thin glowing LINES (1dp strokes, top
 * highlights) over dark glass — never solid fills. Panel bases are
 * dark navy (#0a1220 / #0a1424) so the steel reads cool against the
 * #080808 app background. Steel blue #5a7aa8 is the secondary accent.
 * Warm colours survive ONLY where the colour MEANS something, in small
 * amounts: red = destructive / over-limit, green = locked / good /
 * done / Start, yellow = degraded / warn. The LIVE strip is navy with
 * a blue edge now (green tint gone — the LIVE badge carries "live").
 */

/** One brushed-steel bitmap, decoded once per process. */
private object BrushedCache {
    @Volatile
    private var bitmap: Bitmap? = null

    fun bitmap(resources: Resources): Bitmap = bitmap ?: synchronized(this) {
        bitmap ?: BitmapFactory.decodeResource(resources, R.drawable.brushed_metal)
            .also { bitmap = it }
    }
}

/** Which panel this drawable is for — same look each XML used to give.
 * Build #86 adds the six variants that lived on the live-map page,
 * Logs and the dialogs (CHIP_PLAIN, CHIP_AMBER, PILL, LOG_ITEM,
 * LOG_ITEM_BEST, DIALOG). */
enum class PanelVariant {
    HEADER, PANEL, GPS, CHIP, LIVE, FS,
    CHIP_PLAIN, CHIP_BLUE, PILL, LOG_ITEM, LOG_ITEM_BEST, DIALOG
}

/**
 * Brushed-steel panel: base colour, the brushed-metal bitmap drawn
 * 1:1 centred (matching the old `<bitmap android:gravity="center">`
 * placement, clipped to the bounds), a dark translucent tint gradient,
 * an optional 1dp LED-blue edge, an optional 1dp top highlight
 * (inset from the sides), and an optional 1dp top border.
 *
 * Unlike the old layer-list, this reports getMinimumWidth/Height = 0
 * and getIntrinsicWidth/Height = -1, so it NEVER floors a
 * wrap_content view's measured size. That is the whole point.
 */
class BrushedPanelDrawable(
    bitmap: Bitmap,
    private val baseColor: Int,
    private val tintTop: Int,
    private val tintBottom: Int,
    private val strokeColor: Int = 0,
    radiusDp: Float = 0f,
    private val highlightInsetDp: Float = 0f,
    private val highlightColor: Int = 0x26ffffff,
    private val topBorderColor: Int = 0,
    // Top border geometry — 1dp full-width by default (fullscreen info
    // row); DIALOG takes the old dialog_bg's 2dp LED-blue strip inset
    // 12dp from the sides (Build #86, recolored #87).
    private val topBorderHeightDp: Float = 1f,
    private val topBorderInsetDp: Float = 0f,
    density: Float = 1f
) : Drawable() {
    private val densityPx: Float = density
    private val bitmapRef: Bitmap = bitmap
    private val radiusPx: Float = radiusDp * density
    private val basePaint = Paint().apply { color = baseColor }
    private val bitmapPaint = Paint().apply { isFilterBitmap = true }
    private val tintPaint = Paint()
    private val strokePaint = Paint().apply {
        style = Paint.Style.STROKE
        color = strokeColor
        strokeWidth = 1f * density
        isAntiAlias = true
    }
    private val highlightPaint = Paint().apply { color = highlightColor }
    private val topBorderPaint = Paint().apply { color = topBorderColor }
    private val clipPath = Path()
    private val srcRect = Rect()
    private val dstRect = Rect()

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        tintPaint.shader = LinearGradient(
            0f, bounds.top.toFloat(), 0f, bounds.bottom.toFloat(),
            tintTop, tintBottom, Shader.TileMode.CLAMP
        )
        if (radiusPx > 0f) {
            clipPath.reset()
            clipPath.addRoundRect(
                bounds.left.toFloat(), bounds.top.toFloat(),
                bounds.right.toFloat(), bounds.bottom.toFloat(),
                radiusPx, radiusPx, Path.Direction.CW
            )
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val save = if (radiusPx > 0f) {
            val s = canvas.save()
            canvas.clipPath(clipPath)
            s
        } else 0
        // Base fill.
        canvas.drawRect(b, basePaint)
        // Bitmap 1:1 centred, clipped to the bounds (same placement as
        // the old <bitmap gravity="center"> item).
        val bw = bitmapRef.width
        val bh = bitmapRef.height
        val px = b.left + (b.width() - bw) / 2
        val py = b.top + (b.height() - bh) / 2
        val ix0 = maxOf(b.left, px)
        val iy0 = maxOf(b.top, py)
        val ix1 = minOf(b.right, px + bw)
        val iy1 = minOf(b.bottom, py + bh)
        if (ix0 < ix1 && iy0 < iy1) {
            srcRect.set(ix0 - px, iy0 - py, ix1 - px, iy1 - py)
            dstRect.set(ix0, iy0, ix1, iy1)
            canvas.drawBitmap(bitmapRef, srcRect, dstRect, bitmapPaint)
        }
        // Dark tint gradient over the photo.
        canvas.drawRect(b, tintPaint)
        if (radiusPx > 0f) canvas.restoreToCount(save)
        // 1dp LED edge, drawn inside the bounds.
        if (strokeColor != 0) {
            val half = strokePaint.strokeWidth / 2f
            if (radiusPx > 0f) {
                val r = (radiusPx - half).coerceAtLeast(0f)
                val p = Path()
                p.addRoundRect(
                    b.left + half, b.top + half, b.right - half, b.bottom - half,
                    r, r, Path.Direction.CW
                )
                canvas.drawPath(p, strokePaint)
            } else {
                canvas.drawRect(
                    b.left + half, b.top + half, b.right - half, b.bottom - half,
                    strokePaint
                )
            }
        }
        // 1dp top highlight, inset from the sides (same as the old XML
        // item: top 0dp, height 1dp, left/right inset).
        if (highlightInsetDp > 0f) {
            val inset = highlightInsetDp * densityPx
            val h = strokePaint.strokeWidth
            canvas.drawRect(
                b.left + inset, b.top.toFloat(), b.right - inset, b.top + h,
                highlightPaint
            )
        }
        // Top border (default 1dp, full width — the fullscreen info row;
        // DIALOG overrides height/inset for its 2dp LED-blue strip).
        if (topBorderColor != 0) {
            val h = topBorderHeightDp * densityPx
            val inset = topBorderInsetDp * densityPx
            canvas.drawRect(
                b.left + inset, b.top.toFloat(), b.right - inset, b.top + h,
                topBorderPaint
            )
        }
    }

    // The fix itself: a background must never impose a minimum size.
    override fun getMinimumWidth(): Int = 0
    override fun getMinimumHeight(): Int = 0
    override fun getIntrinsicWidth(): Int = -1
    override fun getIntrinsicHeight(): Int = -1

    override fun setAlpha(alpha: Int) {
        basePaint.alpha = alpha
        bitmapPaint.alpha = alpha
        tintPaint.alpha = alpha
        strokePaint.alpha = alpha
        highlightPaint.alpha = alpha
        topBorderPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        basePaint.colorFilter = colorFilter
        bitmapPaint.colorFilter = colorFilter
        tintPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        highlightPaint.colorFilter = colorFilter
        topBorderPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Standard dark tint the old layer-lists used over the photo. */
private const val TINT_TOP = 0x80000000.toInt()
private const val TINT_BOTTOM = 0xb3000000.toInt()

/** LED-blue panel edge (70% #00b4ff — Build #87, was #ff6a00 orange). */
private const val LED_EDGE = 0xb300b4ff.toInt()

/** Steel blue (Build #86) — the secondary accent, echoing the map's
 * blue route line. The plain live-map chips' border, the best-row
 * border, the Settings dividers. */
private const val STEEL_BLUE = 0xff5a7aa8.toInt()

/** Code-built brushed-steel background for one panel variant. */
fun brushedPanelBackground(resources: Resources, variant: PanelVariant): Drawable {
    val density = resources.displayMetrics.density
    val bitmap = BrushedCache.bitmap(resources)
    return when (variant) {
        PanelVariant.HEADER -> BrushedPanelDrawable(
            bitmap, baseColor = 0xff0a1220.toInt(), tintTop = TINT_TOP, tintBottom = TINT_BOTTOM,
            density = density
        )
        PanelVariant.PANEL -> BrushedPanelDrawable(
            bitmap, baseColor = 0xff0a1220.toInt(), tintTop = TINT_TOP, tintBottom = TINT_BOTTOM,
            strokeColor = LED_EDGE, radiusDp = 4f, highlightInsetDp = 8f,
            density = density
        )
        PanelVariant.GPS -> BrushedPanelDrawable(
            bitmap, baseColor = 0xff0a1424.toInt(), tintTop = TINT_TOP, tintBottom = TINT_BOTTOM,
            strokeColor = LED_EDGE, radiusDp = 4f, highlightInsetDp = 6f,
            density = density
        )
        PanelVariant.CHIP -> BrushedPanelDrawable(
            bitmap, baseColor = 0xff0a1424.toInt(), tintTop = TINT_TOP, tintBottom = TINT_BOTTOM,
            strokeColor = LED_EDGE, radiusDp = 4f, highlightInsetDp = 4f,
            highlightColor = 0x2effffff, density = density
        )
        PanelVariant.LIVE -> BrushedPanelDrawable(
            bitmap, baseColor = 0xff07172f.toInt(),
            tintTop = 0x66001020, tintBottom = 0x80001020.toInt(),
            strokeColor = 0xff00b4ff.toInt(), radiusDp = 6f, density = density
        )
        PanelVariant.FS -> BrushedPanelDrawable(
            bitmap, baseColor = 0xff0a1220.toInt(), tintTop = TINT_TOP, tintBottom = TINT_BOTTOM,
            topBorderColor = 0xff1a1a1a.toInt(), density = density
        )
        // ── Build #86: the live-map / Logs / dialog variants ──
        // CHIP_PLAIN is the old chip_pill (distance / time chips): same
        // base/radius/highlight, but the grey #4a4a4a border is now the
        // steel-blue secondary accent, echoing the map's blue route.
        PanelVariant.CHIP_PLAIN -> BrushedPanelDrawable(
            bitmap, baseColor = 0xff0a1220.toInt(), tintTop = TINT_TOP, tintBottom = TINT_BOTTOM,
            strokeColor = STEEL_BLUE, radiusDp = 16f, highlightInsetDp = 8f,
            density = density
        )
        // CHIP_BLUE is the old chip_pill_amber (speed chip), renamed
        // Build #87: LED-blue border — the active chip is the primary
        // colour, and the primary is blue now.
        PanelVariant.CHIP_BLUE -> BrushedPanelDrawable(
            bitmap, baseColor = 0xff0a1424.toInt(), tintTop = TINT_TOP, tintBottom = TINT_BOTTOM,
            strokeColor = 0xff00b4ff.toInt(), radiusDp = 16f, highlightInsetDp = 8f,
            density = density
        )
        // PILL is the old status_pill_bg (live-map bottom pill).
        PanelVariant.PILL -> BrushedPanelDrawable(
            bitmap, baseColor = 0xff0a1424.toInt(), tintTop = TINT_TOP, tintBottom = TINT_BOTTOM,
            strokeColor = 0xff00b4ff.toInt(), radiusDp = 16f, highlightInsetDp = 10f,
            density = density
        )
        // LOG_ITEM is the old log_item_bg (ride + run rows).
        PanelVariant.LOG_ITEM -> BrushedPanelDrawable(
            bitmap, baseColor = 0xff0a1220.toInt(), tintTop = TINT_TOP, tintBottom = TINT_BOTTOM,
            strokeColor = 0xff3d3d3d.toInt(), radiusDp = 6f, highlightInsetDp = 6f,
            highlightColor = 0x1affffff, density = density
        )
        // LOG_ITEM_BEST is the old log_item_best_bg (best run rows): bright
        // blue border + blue top strip — "best" is a distinction, not a
        // warning, so it takes the primary colour, not gold (Build #87).
        PanelVariant.LOG_ITEM_BEST -> BrushedPanelDrawable(
            bitmap, baseColor = 0xff0a1424.toInt(), tintTop = TINT_TOP, tintBottom = TINT_BOTTOM,
            strokeColor = 0xff00b4ff.toInt(), radiusDp = 6f, highlightInsetDp = 6f,
            highlightColor = 0x3300b4ff.toInt(), density = density
        )
        // DIALOG is the old dialog_bg (route + log-detail dialogs):
        // LED-blue border + the 2dp LED-blue top strip inset 12dp.
        PanelVariant.DIALOG -> BrushedPanelDrawable(
            bitmap, baseColor = 0xff0a1424.toInt(), tintTop = TINT_TOP, tintBottom = TINT_BOTTOM,
            strokeColor = 0xff00b4ff.toInt(), radiusDp = 8f,
            topBorderColor = 0xff00b4ff.toInt(), topBorderHeightDp = 2f,
            topBorderInsetDp = 12f, density = density
        )
    }
}
