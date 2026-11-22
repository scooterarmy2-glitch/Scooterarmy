package com.scooterarmy.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The analog speedometer, drawn natively — same geometry as the web canvas:
 * 270° sweep starting at 135°, track ring, speed arc, ticks, target marker,
 * needle and hub. Redrawn on every GPS fix (and on every display-speed
 * change); the dial itself is pure Canvas, no WebView anywhere near it.
 */
class GaugeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Speed to show, in display units (mph or km/h). */
    var speed: Double = 0.0
        set(value) { field = value; invalidate() }

    var useMetric: Boolean = false
        set(value) { field = value; invalidate() }

    /** Target speed for the timed run, display units (0 = none). */
    var target: Double = 0.0
        set(value) { field = value; invalidate() }

    /** True while a timed run is armed/running/done (shows the target marker). */
    var showTarget: Boolean = false
        set(value) { field = value; invalidate() }

    private val maxSpd: Double get() = if (useMetric) 160.0 else 100.0

    private val orbitron700: Typeface? = try {
        resources.getFont(R.font.orbitron_700)
    } catch (_: Exception) {
        null
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Color.parseColor("#181818")
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Color.parseColor("#39ff14")
    }
    private val tickMajorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT; color = Color.parseColor("#999999")
    }
    private val tickMinorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT; color = Color.parseColor("#2e2e2e")
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#cccccc"); textAlign = Paint.Align.CENTER
        orbitron700?.let { typeface = it }
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.parseColor("#ff3c00")
    }
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.parseColor("#141414")
    }
    private val hubStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.parseColor("#3a3a3a")
    }
    private val hubDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.parseColor("#ff3c00")
    }

    private val a0 = Math.PI * 0.75
    private val sweep = Math.PI * 1.5

    private fun angleFor(s: Double): Double = a0 + min(s / maxSpd, 1.0) * sweep

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val cx = w / 2f; val cy = h / 2f
        val scale = min(w, h) / 320f
        val r = min(w, h) / 2f - 18f * scale
        val ratio = min(speed / maxSpd, 1.0)

        trackPaint.strokeWidth = 13f * scale
        canvas.drawArc(cx - r, cy - r, cx + r, cy + r,
            Math.toDegrees(a0).toFloat(), Math.toDegrees(sweep).toFloat(), false, trackPaint)

        // Target marker (green tick) while a run is armed/running.
        if (showTarget && target > 0 && target <= maxSpd) {
            val ta = Math.toDegrees(angleFor(target)).toFloat()
            targetPaint.strokeWidth = 13f * scale
            targetPaint.setShadowLayer(14f * scale, 0f, 0f, Color.parseColor("#39ff14"))
            canvas.drawArc(cx - r, cy - r, cx + r, cy + r, ta - 1.5f, 3f, false, targetPaint)
            targetPaint.clearShadowLayer()
        }

        // Speed arc.
        if (ratio > 0) {
            val col = when {
                ratio > 0.85 -> "#ff3c00"
                ratio > 0.6 -> "#ffb347"
                else -> "#ff6a00"
            }
            arcPaint.color = Color.parseColor(col)
            arcPaint.strokeWidth = 13f * scale
            arcPaint.setShadowLayer(14f * scale, 0f, 0f, Color.parseColor(col))
            canvas.drawArc(cx - r, cy - r, cx + r, cy + r,
                Math.toDegrees(a0).toFloat(), (Math.toDegrees(sweep) * ratio).toFloat(), false, arcPaint)
            arcPaint.clearShadowLayer()
        }

        // Ticks + numbers.
        val minorStep = if (useMetric) 10 else 5
        val majorStep = if (useMetric) 20 else 10
        var v = 0
        while (v <= maxSpd.toInt()) {
            val a = angleFor(v.toDouble())
            val isMaj = v % majorStep == 0
            val inner = if (isMaj) r - 22f * scale else r - 14f * scale
            val outer = r - 8f * scale
            val p = if (isMaj) tickMajorPaint else tickMinorPaint
            p.strokeWidth = if (isMaj) 2f * scale else 1f * scale
            canvas.drawLine(
                (cx + cos(a) * inner).toFloat(), (cy + sin(a) * inner).toFloat(),
                (cx + cos(a) * outer).toFloat(), (cy + sin(a) * outer).toFloat(), p
            )
            if (isMaj) {
                textPaint.textSize = 10f * scale
                val tr = r - 36f * scale
                canvas.drawText(
                    v.toString(),
                    (cx + cos(a) * tr).toFloat(),
                    (cy + sin(a) * tr).toFloat() + textPaint.textSize * 0.35f,
                    textPaint
                )
            }
            v += minorStep
        }

        // Needle.
        val na = angleFor(speed)
        val nx = cos(na); val ny = sin(na)
        val px = -ny; val py = nx // perpendicular
        val tipR = r - 28f * scale
        val baseR = -18f * scale
        val halfW = 5f * scale
        val path = android.graphics.Path().apply {
            moveTo((cx + nx * tipR).toFloat(), (cy + ny * tipR).toFloat())
            lineTo((cx + nx * baseR + px * halfW).toFloat(), (cy + ny * baseR + py * halfW).toFloat())
            lineTo((cx + nx * baseR - px * halfW).toFloat(), (cy + ny * baseR - py * halfW).toFloat())
            close()
        }
        needlePaint.setShadowLayer(10f * scale, 0f, 0f, Color.parseColor("#ff3c00"))
        canvas.drawPath(path, needlePaint)
        needlePaint.clearShadowLayer()

        // Hub.
        hubStrokePaint.strokeWidth = 2f * scale
        canvas.drawCircle(cx, cy, 12f * scale, hubPaint)
        canvas.drawCircle(cx, cy, 12f * scale, hubStrokePaint)
        hubDotPaint.setShadowLayer(6f * scale, 0f, 0f, Color.parseColor("#ff6a00"))
        canvas.drawCircle(cx, cy, 5f * scale, hubDotPaint)
        hubDotPaint.clearShadowLayer()
    }
}
