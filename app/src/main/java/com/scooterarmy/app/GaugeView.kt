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
 * The analog speedometer, REEL LOOK (branch look-reel) — same 270° geometry
 * as the main build (sweep starting at 135°, 0 bottom-left, max
 * bottom-right), restyled to the Facebook ad frames: a metallic outer ring,
 * a filled orange wedge under the speed arc, orange ticks + numbers, a
 * longer/bolder orange needle, and the live compass heading at the top
 * inside the dial ("NW", from real GPS bearing).
 *
 * Geometry, target marker, max-speed handling and the speed/needle math are
 * byte-identical in behavior to the main build — only the paint changes.
 * Redrawn on every GPS fix (and on every display-speed change).
 */
class GaugeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Speed to show, in mph. */
    var speed: Double = 0.0
        set(value) { field = value; invalidate() }

    /** Target speed for the timed run, in mph (0 = none). */
    var target: Double = 0.0
        set(value) { field = value; invalidate() }

    /** True while a timed run is armed/running/done (shows the target marker). */
    var showTarget: Boolean = false
        set(value) { field = value; invalidate() }

    /** Dial max, mph — from Settings (100/140/180). Redraws + relabels. */
    var maxSpd: Double = 100.0
        set(value) { field = value.coerceIn(100.0, 180.0); invalidate() }

    /** Live compass heading, e.g. "NW" — "" / "—" hides it. */
    var heading: String = ""
        set(value) { field = value; invalidate() }

    private val orbitron700: Typeface by lazy { resources.getFont(R.font.orbitron_700) }
    private val orbitron900: Typeface by lazy { resources.getFont(R.font.orbitron_900) }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT; color = Color.parseColor("#232323")
    }
    private val ringEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT; color = Color.parseColor("#4d4d4d")
    }
    private val ringGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT; color = Color.parseColor("#59ff6a00")
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Color.parseColor("#4a4a4a")
    }
    private val wedgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Color.parseColor("#39ff14")
    }
    private val tickMajorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT; color = Color.parseColor("#ff7a1a")
    }
    private val tickMinorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT; color = Color.parseColor("#3a3a3a")
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ff8c1a"); textAlign = Paint.Align.CENTER
        typeface = orbitron900; isFakeBoldText = true
    }
    private val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#cccccc"); textAlign = Paint.Align.CENTER
        typeface = orbitron900; isFakeBoldText = true
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

        // Metallic outer ring (reel frames): dark steel band with a thin
        // lighter edge and a faint orange inner line.
        ringPaint.strokeWidth = 11f * scale
        canvas.drawCircle(cx, cy, min(w, h) / 2f - 6f * scale, ringPaint)
        ringEdgePaint.strokeWidth = 1.5f * scale
        canvas.drawCircle(cx, cy, min(w, h) / 2f - 12f * scale, ringEdgePaint)
        ringGlowPaint.strokeWidth = 1.5f * scale
        canvas.drawCircle(cx, cy, r + 5f * scale, ringGlowPaint)

        trackPaint.strokeWidth = 9f * scale
        canvas.drawArc(cx - r, cy - r, cx + r, cy + r,
            Math.toDegrees(a0).toFloat(), Math.toDegrees(sweep).toFloat(), false, trackPaint)

        // Target marker (green tick) while a run is armed/running.
        if (showTarget && target > 0 && target <= maxSpd) {
            val ta = Math.toDegrees(angleFor(target)).toFloat()
            targetPaint.strokeWidth = 12f * scale
            targetPaint.setShadowLayer(12f * scale, 0f, 0f, Color.parseColor("#39ff14"))
            canvas.drawArc(cx - r, cy - r, cx + r, cy + r, ta - 1.5f, 3f, false, targetPaint)
            targetPaint.clearShadowLayer()
        }

        // Filled orange wedge under the speed arc (reel frames): a
        // translucent band from 0 to the current speed, inside the arc.
        if (ratio > 0) {
            val col = when {
                ratio > 0.85 -> "#ff3c00"
                ratio > 0.6 -> "#ffb347"
                else -> "#ff6a00"
            }
            wedgePaint.color = Color.parseColor(col)
            wedgePaint.alpha = 70
            wedgePaint.strokeWidth = 20f * scale
            val wr = r - 20f * scale
            canvas.drawArc(cx - wr, cy - wr, cx + wr, cy + wr,
                Math.toDegrees(a0).toFloat(), (Math.toDegrees(sweep) * ratio).toFloat(), false, wedgePaint)

            // Speed arc.
            arcPaint.color = Color.parseColor(col)
            arcPaint.strokeWidth = 18f * scale
            arcPaint.setShadowLayer(18f * scale, 0f, 0f, Color.parseColor(col))
            canvas.drawArc(cx - r, cy - r, cx + r, cy + r,
                Math.toDegrees(a0).toFloat(), (Math.toDegrees(sweep) * ratio).toFloat(), false, arcPaint)
            arcPaint.clearShadowLayer()
        }

        // Ticks + numbers. Wider dials label every 20 mph so they don't crowd.
        val minorStep = 5
        val majorStep = if (maxSpd > 100) 20 else 10
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
                textPaint.textSize = 12f * scale
                val tr = r - 38f * scale
                canvas.drawText(
                    v.toString(),
                    (cx + cos(a) * tr).toFloat(),
                    (cy + sin(a) * tr).toFloat() + textPaint.textSize * 0.35f,
                    textPaint
                )
            }
            v += minorStep
        }

        // Compass heading at the top inside the dial (reel frames: "NW").
        // Top centre is clear of the dial numbers (nearest labels sit
        // ~30° either side), so it never collides with a tick label.
        if (heading.isNotEmpty() && heading != "—") {
            headingPaint.textSize = 14f * scale
            canvas.drawText(
                heading, cx,
                cy - r + 46f * scale + headingPaint.textSize * 0.35f,
                headingPaint
            )
        }

        // Needle — longer and bolder than the main build (reel frames).
        val na = angleFor(speed)
        val nx = cos(na); val ny = sin(na)
        val px = -ny; val py = nx // perpendicular
        val tipR = r - 18f * scale
        val baseR = -20f * scale
        val halfW = 7f * scale
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
        canvas.drawCircle(cx, cy, 14f * scale, hubPaint)
        canvas.drawCircle(cx, cy, 14f * scale, hubStrokePaint)
        hubDotPaint.setShadowLayer(6f * scale, 0f, 0f, Color.parseColor("#ff6a00"))
        canvas.drawCircle(cx, cy, 6f * scale, hubDotPaint)
        hubDotPaint.clearShadowLayer()
    }
}
