package com.scooterarmy.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The reel-frame speedometer dial — written FRESH for the look-v2 build.
 *
 * CORRECTION Nov 23 01:26 CST (Casey, from frame-11): the dial is a
 * 180° ARC, NOT a 360° ring. Build #73 shipped a full round tick ring
 * and was WRONG — the hub is round, the scale is not. Exact frame-11
 * geometry (canvas angles: 0° = 3 o'clock, clockwise positive):
 *  - Scale 0 to 170 mph, fixed — NOT 0–180, and NOT the Settings
 *    gauge-max (100/140/180). The frame's numbers are the dial; the
 *    gauge-max setting does not rescale this dial (see MainActivity).
 *  - 0 mph sits at 135° (bottom-left, 7:30 position) and the scale
 *    sweeps 180° clockwise through 9 o'clock and 12 o'clock, ending
 *    at 315° (top-right, 1:30 position) for 170 mph.
 *  - Numbers on the arc only: 0, 20, 40, 60, 80, 100, 170.
 *    Minor ticks every 5 mph, arc only. NO ticks, NO numbers, NO arc
 *    on the right side (315° clockwise back to 135°) — that side is
 *    just the round black hub, metallic rim, and carbon background.
 *  - ELLIPSE (Nov 23 ~09:40 CST, from Casey's Build #75 screenshot):
 *    frame-11's dial is a perspective ELLIPSE, taller than wide (its
 *    rim is ~1.12x taller than wide) — a true circle floats in dead
 *    carbon on a tall phone screen, which is the #1 thing wrong in
 *    Build #75. So the dial stretches vertically: ry = 1.15 * rx.
 *    A square view (landscape/fullscreen) gives rx == ry: a circle.
 *    Angles are unchanged (angleFor is identical); only the radius
 *    differs per direction. Numbers sit inset from the ellipse edge
 *    along the radial direction, so "20" stays on screen.
 *  - Orange wedge band fills from 0 up to the current speed along the
 *    arc; SHORT orange needle at the rim only (Build #75's long needle
 *    ran from the hub center and cut through the speed digits); the
 *    hub has no center dot any more (it showed through the "0").
 *  - The huge digital speed + compass heading live in the layout overlay
 *    (spdNum / dialHeading), centered / top-center inside this dial.
 *
 * API matches the old gauge so the engine wiring is unchanged: set
 * [speed], [target]/[showTarget] for the timed-run marker. Speed is
 * always mph.
 */
class ReelGaugeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        /** Frame-11 dial scale, mph — fixed. See the header comment. */
        const val SCALE_MAX_MPH = 170.0
        /** Dial numbers, in frame-11 order. 120/140/160 get ticks only. */
        val DIAL_NUMBERS = listOf(0, 20, 40, 60, 80, 100, 170)
        /** Vertical stretch of the dial ellipse (ry = ELLIPSE_Y * rx).
         *  Frame-11's perspective rim is ~1.12x taller than wide. */
        const val ELLIPSE_Y = 1.15f
    }

    var speed: Double = 0.0
        set(v) { field = v; invalidate() }
    var target: Double = 0.0
        set(v) { field = v; invalidate() }
    var showTarget: Boolean = false
        set(v) { field = v; invalidate() }

    // 0 mph at 135° (bottom-left); 180° sweep clockwise to 315° at 170.
    private val a0 = Math.PI * 0.75
    private val sweep = Math.PI

    /** Pure angle math — also exercised by the JVM test harness. */
    fun angleFor(s: Double): Double = a0 + min(s / SCALE_MAX_MPH, 1.0) * sweep

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
        // Elliptical dial (see header): rx is the limiting half-width,
        // ry stretches 1.15x so the dial fills a tall phone screen.
        // A square view gives rx == ry — a circle, as before.
        val rx = w / 2f - 14f * scale
        val ry = h / 2f - 14f * scale
        val ratio = min(speed / SCALE_MAX_MPH, 1.0)
        val oval = RectF(cx - rx, cy - ry, cx + rx, cy + ry)
        // Point on the ellipse at canvas angle a, fraction t of the way
        // from the center to the edge (t = 1 is the rim).
        fun ept(a: Double, t: Float): Pair<Float, Float> =
            Pair((cx + rx * cos(a) * t).toFloat(), (cy + ry * sin(a) * t).toFloat())
        // Point `inset` px inside the ellipse edge, along the radial
        // direction of angle a (ticks / numbers / needle tip).
        fun edgePt(a: Double, inset: Float): Pair<Float, Float> {
            val dx = rx * cos(a); val dy = ry * sin(a)
            val rLen = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            val f = (rLen - inset) / rLen
            return Pair((cx + dx * f).toFloat(), (cy + dy * f).toFloat())
        }

        // Metallic outer ring: dark band + thin bright inner edge.
        // The RIM is a full ellipse; only the SCALE is a 180° arc.
        ringPaint.strokeWidth = 6f * scale
        canvas.drawOval(RectF(cx - rx - 8f * scale, cy - ry - 8f * scale, cx + rx + 8f * scale, cy + ry + 8f * scale), ringPaint)
        ringHiPaint.strokeWidth = 1.5f * scale
        canvas.drawOval(RectF(cx - rx - 5f * scale, cy - ry - 5f * scale, cx + rx + 5f * scale, cy + ry + 5f * scale), ringHiPaint)

        // Track: thin dark arc over the 180° scale only — nothing on
        // the right side (315° clockwise back to 135°).
        trackPaint.strokeWidth = 9f * scale
        canvas.drawArc(
            oval,
            Math.toDegrees(a0).toFloat(), Math.toDegrees(sweep).toFloat(),
            false, trackPaint
        )

        // Target marker (green tick) while a run is armed/running.
        if (showTarget && target > 0 && target <= SCALE_MAX_MPH) {
            val ta = Math.toDegrees(angleFor(target)).toFloat()
            targetPaint.strokeWidth = 12f * scale
            targetPaint.setShadowLayer(12f * scale, 0f, 0f, Color.parseColor("#39ff14"))
            canvas.drawArc(oval, ta - 1.5f, 3f, false, targetPaint)
            targetPaint.clearShadowLayer()
        }

        // Orange wedge band from 0 up to the current speed.
        if (ratio > 0) {
            wedgePaint.strokeWidth = 30f * scale
            wedgePaint.setShadowLayer(18f * scale, 0f, 0f, Color.parseColor("#ff6a00"))
            canvas.drawArc(
                oval,
                Math.toDegrees(a0).toFloat(),
                (Math.toDegrees(sweep) * ratio).toFloat(),
                false, wedgePaint
            )
            wedgePaint.clearShadowLayer()
        }

        // Inner disc: the darker ellipse the huge digital speed sits in,
        // like the gauge frame — drawn before the ticks/needle.
        canvas.drawOval(RectF(cx - rx * 0.70f, cy - ry * 0.70f, cx + rx * 0.70f, cy + ry * 0.70f), innerDiscPaint)
        canvas.drawOval(RectF(cx - rx * 0.70f, cy - ry * 0.70f, cx + rx * 0.70f, cy + ry * 0.70f), innerRimPaint)

        // Numbers pass (on top of the ticks AND the needle). The numbers
        // sit at inset 40, not 30: a horizontal string's outer corner
        // extends ~13px past its center along the radial direction at
        // 315°/135°, so at inset 30 the "170"/"0" glyphs sat inside the
        // major ticks' span (inner end inset 24) and the tick line cut
        // straight through the glyph — visible in the v6 render. At
        // inset 40 the outermost glyph corner is at ~27, clear of the
        // ticks. Numbers only at 0, 20, 40, 60, 80, 100, 170 (frame-11).
        // 170 is its own position (315°, top-right) — it does NOT collide
        // with 0, so no wrap-point skip is needed or wanted.
        val minorStep = 5
        var v = 0
        while (v <= SCALE_MAX_MPH.toInt()) {
            val a = angleFor(v.toDouble())
            val isMaj = v in DIAL_NUMBERS
            val innerPt = edgePt(a, if (isMaj) 24f * scale else 15f * scale)
            val outerPt = edgePt(a, 9f * scale)
            val p = if (isMaj) tickMajorPaint else tickMinorPaint
            p.strokeWidth = if (isMaj) 2.5f * scale else 1f * scale
            canvas.drawLine(innerPt.first, innerPt.second, outerPt.first, outerPt.second, p)
            v += minorStep
        }

        // Needle: SHORT orange pointer at the rim only — from the inner
        // disc edge (t = 0.70) to the tick ring. Build #75's long needle
        // ran from the hub center to the rim and cut straight through
        // the big speed digits; the hub dot showed through the "0".
        // Nothing is drawn at the center any more. Drawn BEFORE the
        // numbers, so when the needle points near a numbered value
        // (e.g. 95 mph, 5° from "100") the number paints over it
        // instead of the needle covering the glyph.
        val na = angleFor(speed)
        val tipPt = edgePt(na, 28f * scale)
        val basePt = ept(na, 0.70f)
        val dxn = tipPt.first - basePt.first
        val dyn = tipPt.second - basePt.second
        val dLen = Math.sqrt((dxn * dxn + dyn * dyn).toDouble()).toFloat()
        val px = -dyn / dLen; val py = dxn / dLen // perpendicular
        val halfW = 5f * scale
        val path = android.graphics.Path().apply {
            moveTo(tipPt.first, tipPt.second)
            lineTo(basePt.first + px * halfW, basePt.second + py * halfW)
            lineTo(basePt.first - px * halfW, basePt.second - py * halfW)
            close()
        }
        needlePaint.setShadowLayer(10f * scale, 0f, 0f, Color.parseColor("#ff3c00"))
        canvas.drawPath(path, needlePaint)
        needlePaint.clearShadowLayer()

        // Numbers pass (on top of ticks and needle).
        textPaint.textSize = 12f * scale
        for (n in DIAL_NUMBERS) {
            val a = angleFor(n.toDouble())
            val pt = edgePt(a, 40f * scale)
            canvas.drawText(n.toString(), pt.first, pt.second + textPaint.textSize * 0.35f, textPaint)
        }
    }
}
