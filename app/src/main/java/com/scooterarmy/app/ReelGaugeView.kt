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
 * The reel-frame speedometer dial — written FRESH for the look-v2 build.
 * Nothing of the old 270° arc gauge survives here: this is a FULL ROUND
 * 360° dial, like the gauge frame (6596_29_ydhs.jpg).
 *
 * Geometry (canvas angles: 0° = 3 o'clock, clockwise positive):
 *  - 0 mph sits at 135° (bottom-left, 7:30 position) and the scale sweeps
 *    a full 360° clockwise, so 180 mph lands back at the same point.
 *  - Tick ring all the way around: minor ticks every 5 mph, major orange
 *    ticks + numbers every 20 mph (0–180).
 *  - Orange wedge band fills from 0 up to the current speed; orange needle
 *    points at it; metallic outer ring; dark hub with an orange dot.
 *  - The huge digital speed + compass heading live in the layout overlay
 *    (spdNum / dialHeading), centered / top-center inside this dial.
 *
 * API matches the old gauge so the engine wiring is unchanged: set
 * [speed], [target]/[showTarget] for the timed-run marker, [maxSpd] for
 * the dial scale (100/140/180 from Settings). Speed is always mph.
 */
class ReelGaugeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var speed: Double = 0.0
        set(v) { field = v; invalidate() }
    var target: Double = 0.0
        set(v) { field = v; invalidate() }
    var showTarget: Boolean = false
        set(v) { field = v; invalidate() }
    var maxSpd: Double = 100.0
        set(v) { field = v; invalidate() }

    // 0 mph at 135° (bottom-left); full 360° sweep clockwise.
    private val a0 = Math.PI * 0.75
    private val sweep = Math.PI * 2.0

    /** Pure angle math — also exercised by the JVM test harness. */
    fun angleFor(s: Double): Double = a0 + min(s / maxSpd, 1.0) * sweep

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#3a3a3a")
    }
    private val ringHiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#5a5a5a")
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#1c1c1c")
    }
    private val wedgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#ff6a00")
        strokeCap = Paint.Cap.BUTT
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#39ff14")
        strokeCap = Paint.Cap.BUTT
    }
    private val tickMajorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#ff6a00")
        strokeCap = Paint.Cap.BUTT
    }
    private val tickMinorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#666666")
        strokeCap = Paint.Cap.BUTT
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ff6a00")
        textAlign = Paint.Align.CENTER
        typeface = try {
            resources.getFont(R.font.orbitron_700)
        } catch (_: Exception) {
            Typeface.DEFAULT_BOLD
        }
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#ff6a00")
    }
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#111111")
    }
    private val hubStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#444444")
    }
    private val hubDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#ff6a00")
    }
    private val innerDiscPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#0a0a0a")
    }
    private val innerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#2a2a2a")
        strokeWidth = 1.5f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val cx = w / 2f; val cy = h / 2f
        val scale = min(w, h) / 320f
        val r = min(w, h) / 2f - 14f * scale
        val ratio = min(speed / maxSpd, 1.0)

        // Metallic outer ring: dark band + thin bright inner edge.
        ringPaint.strokeWidth = 6f * scale
        canvas.drawCircle(cx, cy, r + 8f * scale, ringPaint)
        ringHiPaint.strokeWidth = 1.5f * scale
        canvas.drawCircle(cx, cy, r + 5f * scale, ringHiPaint)

        // Track: full thin dark circle the wedge fills over.
        trackPaint.strokeWidth = 9f * scale
        canvas.drawCircle(cx, cy, r, trackPaint)

        // Target marker (green tick) while a run is armed/running.
        if (showTarget && target > 0 && target <= maxSpd) {
            val ta = Math.toDegrees(angleFor(target)).toFloat()
            targetPaint.strokeWidth = 12f * scale
            targetPaint.setShadowLayer(12f * scale, 0f, 0f, Color.parseColor("#39ff14"))
            canvas.drawArc(cx - r, cy - r, cx + r, cy + r, ta - 1.5f, 3f, false, targetPaint)
            targetPaint.clearShadowLayer()
        }

        // Orange wedge band from 0 up to the current speed.
        if (ratio > 0) {
            wedgePaint.strokeWidth = 30f * scale
            wedgePaint.setShadowLayer(18f * scale, 0f, 0f, Color.parseColor("#ff6a00"))
            canvas.drawArc(
                cx - r, cy - r, cx + r, cy + r,
                Math.toDegrees(a0).toFloat(),
                (Math.toDegrees(sweep) * ratio).toFloat(),
                false, wedgePaint
            )
            wedgePaint.clearShadowLayer()
        }

        // Inner disc: the darker circle the huge digital speed sits in,
        // like the gauge frame — drawn before the ticks/needle.
        canvas.drawCircle(cx, cy, r * 0.70f, innerDiscPaint)
        canvas.drawCircle(cx, cy, r * 0.70f, innerRimPaint)

        // Ticks + numbers all the way around. Major every 20 mph, minor 5.
        val minorStep = 5
        val majorStep = 20
        var v = 0
        while (v <= maxSpd.toInt()) {
            val a = angleFor(v.toDouble())
            val isMaj = v % majorStep == 0
            val inner = if (isMaj) r - 24f * scale else r - 15f * scale
            val outer = r - 9f * scale
            val p = if (isMaj) tickMajorPaint else tickMinorPaint
            p.strokeWidth = if (isMaj) 2.5f * scale else 1f * scale
            canvas.drawLine(
                (cx + cos(a) * inner).toFloat(), (cy + sin(a) * inner).toFloat(),
                (cx + cos(a) * outer).toFloat(), (cy + sin(a) * outer).toFloat(), p
            )
            // The max number lands on exactly the same spot as 0 (full
            // 360° sweep wraps) — the AI frame draws both there and it
            // reads as a mistake, so the max number is skipped; its tick
            // still draws and the digital speed shows the value.
            if (isMaj && v != maxSpd.toInt()) {
                textPaint.textSize = 12f * scale
                val tr = r - 30f * scale
                canvas.drawText(
                    v.toString(),
                    (cx + cos(a) * tr).toFloat(),
                    (cy + sin(a) * tr).toFloat() + textPaint.textSize * 0.35f,
                    textPaint
                )
            }
            v += minorStep
        }

        // Needle: orange triangle from the hub to the tick ring.
        val na = angleFor(speed)
        val nx = cos(na); val ny = sin(na)
        val px = -ny; val py = nx // perpendicular
        val tipR = r - 30f * scale
        val baseR = -16f * scale
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

        // Hub: dark disc, grey rim, glowing orange dot.
        hubStrokePaint.strokeWidth = 2f * scale
        canvas.drawCircle(cx, cy, 13f * scale, hubPaint)
        canvas.drawCircle(cx, cy, 13f * scale, hubStrokePaint)
        hubDotPaint.setShadowLayer(6f * scale, 0f, 0f, Color.parseColor("#ff6a00"))
        canvas.drawCircle(cx, cy, 5f * scale, hubDotPaint)
        hubDotPaint.clearShadowLayer()
    }
}
