package com.scooterarmy.app

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View

/**
 * Shared photographic background (Build #79): a real photo of dark carbon
 * fiber weave (`res/drawable-nodpi/carbon_bg.jpg`, 720x1280 portrait crop,
 * JPEG, ~151 KB) drawn cover-scale (center-crop via [coverTransform]) so it fills the
 * whole screen with no stretching and no seams, plus a subtle dark scrim
 * top and bottom so text stays readable. One definition used by
 * MainActivity, the Settings screen and the Logs page, so every screen
 * has the same photographic background instead of flat black.
 *
 * This REPLACES Build #78's `applyLedBackground`: its Canvas-drawn
 * diamond-plate tile + ember glows were the thing that made the app read
 * as a rendering rather than a photo. The diamond lattice, the wide ember
 * glow, the hot core and the bottom ember fade are all gone — the only
 * drawn element left is the legibility scrim (a photographic darkening,
 * not a pattern). The dial face needs no separate work: GaugeView draws
 * no face fill, so the photo shows straight through the dial.
 *
 * The bitmap is decoded once per process and cached; the shader matrix
 * is rebuilt only when the bounds change, so redraws are cheap.
 * Visual only — no behavior, layout or default changes.
 */
private object PhotoCache {
    @Volatile
    private var carbon: Bitmap? = null

    fun carbon(resources: Resources): Bitmap {
        return carbon ?: synchronized(this) {
            carbon ?: BitmapFactory.decodeResource(resources, R.drawable.carbon_bg)
                .also { carbon = it }
        }
    }
}

/** Draws one bitmap cover-scale inside its bounds, then the legibility scrim. */
private class PhotoBackgroundDrawable(private val bitmap: Bitmap) : Drawable() {
    private val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val matrix = Matrix()
    private val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
    private val scrimPaint = Paint()

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val (scale, dx, dy) = coverTransform(
            bitmap.width, bitmap.height, bounds.width(), bounds.height()
        )
        matrix.setScale(scale.toFloat(), scale.toFloat())
        matrix.postTranslate(bounds.left + dx.toFloat(), bounds.top + dy.toFloat())
        shader.setLocalMatrix(matrix)
        // Legibility scrim: 25% black at the very top (header), clear
        // through the middle (dial), 40% black at the bottom (boxes).
        scrimPaint.shader = LinearGradient(
            0f, bounds.top.toFloat(), 0f, bounds.bottom.toFloat(),
            intArrayOf(
                Color.parseColor("#40000000"),
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                Color.parseColor("#66000000")
            ),
            floatArrayOf(0f, 0.22f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds, photoPaint)
        canvas.drawRect(bounds, scrimPaint)
    }

    override fun setAlpha(alpha: Int) {
        photoPaint.alpha = alpha
        scrimPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        photoPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/** Photographic carbon-fiber background for a screen root view. */
fun applyPhotoBackground(root: View) {
    root.background = PhotoBackgroundDrawable(PhotoCache.carbon(root.resources))
}
