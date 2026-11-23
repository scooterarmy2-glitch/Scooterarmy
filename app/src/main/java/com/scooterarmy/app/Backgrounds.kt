package com.scooterarmy.app

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View

/**
 * Shared photographic background (Build #80): a real photo drawn
 * cover-scale (center-crop via [coverSrcRect]) so it fills the whole
 * screen with no stretching and no seams. Two photos, one choice
 * (Settings > Display > Background, carbon default): Casey's carbon
 * weave (`res/drawable-nodpi/carbon_bg.jpg`, 1080x2400 portrait,
 * JPEG, legibility scrim baked into the asset) or brushed steel
 * (`res/drawable-nodpi/brushed_metal.jpg`, 1600x1000, the same photo
 * the panels and chips use — kept, not deleted). One definition used
 * by MainActivity, the Settings screen and the Logs page, so every
 * screen has the same photographic background instead of flat black.
 *
 * The asset is Casey's own weave photograph (he sent it 2026-11-23),
 * pre-scaled to 1080x2400 so the draw is a 1:1 blit on his phone.
 *
 * This REPLACES the Build #79 draw path, not just the asset: #79 drew
 * the photo through a BitmapShader + local matrix (coverTransform)
 * with a runtime LinearGradient scrim, and on Casey's phone the weave
 * measured nearly flat (band stddev ~6-11 vs ~25-27 in the asset) —
 * the pattern was essentially absent, not merely blurred, so the
 * shader path was not delivering the bitmap to the screen. Build #80
 * draws the bitmap with the standard framework path,
 * canvas.drawBitmap(srcRect, dstRect) with isFilterBitmap, which has
 * no matrix, no shader and no runtime scrim between the asset and
 * the screen. The dial face needs no separate work: GaugeView draws
 * no face fill, so the photo shows straight through the dial.
 *
 * The bitmap is decoded once per process and cached (one entry per
 * background choice); the source rect is rebuilt only when the bounds
 * change, so redraws are cheap. Visual only — no behavior, layout or
 * default changes.
 */

/** Drawable for a background choice — pure, harness-tested. Carbon
 * (Casey's weave photo) is the default; brushed steel is the option
 * Casey asked to keep. Anything unrecognised is carbon. */
fun backgroundResId(background: String): Int =
    if (AppSettings.validBackground(background) == AppSettings.BG_BRUSHED)
        R.drawable.brushed_metal
    else
        R.drawable.carbon_bg

private object PhotoCache {
    @Volatile
    private var carbon: Bitmap? = null

    @Volatile
    private var brushed: Bitmap? = null

    fun photo(resources: Resources, background: String): Bitmap {
        return if (AppSettings.validBackground(background) == AppSettings.BG_BRUSHED) {
            brushed ?: synchronized(this) {
                brushed ?: BitmapFactory.decodeResource(resources, R.drawable.brushed_metal)
                    .also { brushed = it }
            }
        } else {
            carbon ?: synchronized(this) {
                carbon ?: BitmapFactory.decodeResource(resources, R.drawable.carbon_bg)
                    .also { carbon = it }
            }
        }
    }
}

/** Draws one bitmap cover-scale inside its bounds (Build #80).
 * Build #81: an optional readability scrim — a black overlay of
 * `dimAlpha` (0–255) drawn over the photo, so the weave still shows
 * through dimmed underneath while text on top reads clearly. The main
 * screen keeps dimAlpha 0 (its scrim is baked into the asset, as Casey
 * approved); the text-heavy Settings and Logs screens pass
 * [DIMMED_ALPHA], because Casey found the bare weave "terrible behind
 * the settings" — the small grey helper text was nearly illegible
 * against the high-contrast basket pattern. */
private class PhotoBackgroundDrawable(
    private val bitmap: Bitmap,
    private val dimAlpha: Int = 0
) : Drawable() {
    private val photoPaint = Paint().apply { isFilterBitmap = true }
    private val dimPaint = Paint().apply {
        color = android.graphics.Color.BLACK
        alpha = dimAlpha.coerceIn(0, 255)
    }
    private var srcRect: Rect? = null

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        srcRect = coverSrcRect(
            bitmap.width, bitmap.height, bounds.width(), bounds.height()
        )?.let { Rect(it.left, it.top, it.right, it.bottom) }
    }

    override fun draw(canvas: Canvas) {
        val src = srcRect ?: return
        canvas.drawBitmap(bitmap, src, bounds, photoPaint)
        if (dimAlpha > 0) canvas.drawRect(bounds, dimPaint)
    }

    override fun setAlpha(alpha: Int) {
        photoPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        photoPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/** Readability scrim for the text-heavy screens (Build #81): ~67%
 * black over the photo. Strong enough that the small grey helper text
 * reads (verified in the render + harness contrast check), light enough
 * that the weave still shows through dimmed underneath. Main keeps 0. */
const val DIMMED_ALPHA = 170

/** Photographic background for a screen root view. Reads the stored
 *  background choice (Settings > Display > Background, carbon default)
 *  from the view's own context, so Main, Settings and Logs all follow
 *  the same choice with no call-site changes. Panels and chips keep
 *  their brushed-metal photo look regardless — they are separate
 *  drawables and this choice only changes the full-screen photo.
 *  `dimAlpha` is the Build #81 readability scrim (0 = none, as Main
 *  uses; DIMMED_ALPHA on Settings/Logs). */
fun applyPhotoBackground(root: View, dimAlpha: Int = 0) {
    root.background = PhotoBackgroundDrawable(
        PhotoCache.photo(root.resources, AppSettings(root.context).background),
        dimAlpha
    )
}
