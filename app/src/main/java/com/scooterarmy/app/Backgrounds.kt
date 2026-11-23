package com.scooterarmy.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View

/**
 * Shared LED / high-tech-device background (Build #78): ember glow +
 * diamond-plate texture covering the whole screen, matching the old web
 * app's body background. One definition used by MainActivity, the
 * Settings screen and the Logs page, so every screen has the same lit
 * carbon feel instead of flat black.
 *
 * Build #78 strengthened it — the Build #77 texture was nearly
 * invisible on Casey's phone (background sampled ~(14,13,12) vs a
 * (8,8,8) base: the diamonds added only ~+6 grey levels, crushed to
 * black in the photo). Diamond stroke 10% → ~19% white, 0.8dp → 1.0dp;
 * wide ember glow 16% → 25%, core 9% → 19%, bottom fade roughly
 * doubled. The pattern is the same diamond lattice as before (tiled,
 * it reads as the diagonal grid in the render) — the problem was
 * visibility, not the pattern. Visual only.
 */
fun applyLedBackground(root: View) {
    val dm = root.context.resources.displayMetrics
    val density = dm.density
    val screenH = dm.heightPixels.toFloat()
    // Diamond-plate texture tile (28x16dp, like the web app's 28px x 16px).
    val w = (28f * density).toInt().coerceAtLeast(2)
    val h = (16f * density).toInt().coerceAtLeast(2)
    val tile = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(tile)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.0f * density
        color = Color.parseColor("#30ffffff")
    }
    val diamond = Path().apply {
        moveTo(w / 2f, 0f)
        lineTo(w.toFloat(), h / 2f)
        lineTo(w / 2f, h.toFloat())
        lineTo(0f, h / 2f)
        close()
    }
    canvas.drawPath(diamond, paint)
    val tiled = BitmapDrawable(root.context.resources, tile).apply {
        tileModeX = Shader.TileMode.REPEAT
        tileModeY = Shader.TileMode.REPEAT
    }
    // Wide ember glow, bottom center — reaches ~85% up the screen.
    val wideGlow = GradientDrawable().apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT
        setGradientCenter(0.5f, 1.0f)
        gradientRadius = screenH * 0.85f
        colors = intArrayOf(Color.parseColor("#40c83c00"), Color.TRANSPARENT)
    }
    // Hotter orange core, bottom center.
    val coreGlow = GradientDrawable().apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT
        setGradientCenter(0.5f, 1.0f)
        gradientRadius = screenH * 0.52f
        colors = intArrayOf(Color.parseColor("#30ff6400"), Color.TRANSPARENT)
    }
    // Dark vignette at the top (as in the web app).
    val vignette = GradientDrawable().apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT
        setGradientCenter(0.5f, 0.0f)
        gradientRadius = screenH * 0.7f
        colors = intArrayOf(Color.parseColor("#b3000000"), Color.TRANSPARENT)
    }
    // Bottom ember fade (web body::after, 160dp tall).
    val fadeH = (160f * density).toInt()
    val bottomFade = GradientDrawable(
        GradientDrawable.Orientation.BOTTOM_TOP,
        intArrayOf(
            Color.parseColor("#30b42800"),
            Color.parseColor("#1aff5000"),
            Color.TRANSPARENT
        )
    )
    val base = ColorDrawable(Color.parseColor("#080808"))
    val layers = LayerDrawable(arrayOf(base, wideGlow, coreGlow, vignette, bottomFade, tiled))
    // Confine the bottom fade to the bottom 160dp of the screen.
    val topInset = (screenH - fadeH).toInt().coerceAtLeast(0)
    layers.setLayerInset(4, 0, topInset, 0, 0)
    root.background = layers
}
