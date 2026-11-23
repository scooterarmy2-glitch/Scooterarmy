package com.scooterarmy.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
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

    /** Live heading label below the 50, e.g. "NW 315°" (or "—" when stopped).
     *  Set by MainActivity from the GPS fix; null hides it. */
    var heading: String? = null
        set(value) { if (field != value) { field = value; invalidate() } }

    private val orbitron700: Typeface by lazy { resources.getFont(R.font.orbitron_700) }
    private val orbitron900: Typeface by lazy { resources.getFont(R.font.orbitron_900) }

    // Build #81 LED orange: vivid glowing orange lines, not the muted
    // amber Casey called pale and yellowish. The needle, speed
    // arc and accent ring are thin luminous orange strokes with a glow
    // halo; the dial face stays open (photo shows through).
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val accentRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#ff8c1a")
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#ff8c1a")
    }
    // Build #78 LED glow passes — wide translucent halo under the solid
    // core, so the arc and needle read as lit, not painted.
    private val arcGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#40ff6a00")
    }
    private val rimHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#f5f5f5"); alpha = 220
    }
    private val needleGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.parseColor("#40ff6a00")
    }
    private val hubGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Color.parseColor("#39ff14")
    }
    private val tickMajorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT; color = Color.parseColor("#bbbbbb")
    }
    private val tickMinorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT; color = Color.parseColor("#3d3d3d")
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#cccccc"); textAlign = Paint.Align.CENTER
        typeface = orbitron900; isFakeBoldText = true
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.parseColor("#ff6a00")
    }
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.parseColor("#141414")
    }
    private val hubStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.parseColor("#3a3a3a")
    }
    private val hubDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.parseColor("#ff6a00")
    }
    private val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#e8e8e8"); textAlign = Paint.Align.CENTER
        typeface = orbitron700
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

        // Steel rim: brushed-metal diagonal gradient, thin (look-pro).
        rimPaint.shader = LinearGradient(
            cx - r, cy - r, cx + r, cy + r,
            intArrayOf(
                Color.parseColor("#d8d8d8"), Color.parseColor("#7a7a7a"),
                Color.parseColor("#e8e8e8"), Color.parseColor("#5a5a5a")
            ),
            floatArrayOf(0f, 0.35f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )
        rimPaint.strokeWidth = 7f * scale
        canvas.drawArc(cx - r, cy - r, cx + r, cy + r,
            Math.toDegrees(a0).toFloat(), Math.toDegrees(sweep).toFloat(), false, rimPaint)
        // Bright top highlight on the rim — machined metal catching the
        // light from above, like the render (Build #78). 225°–315° is the
        // top of the dial (12 o'clock = 270° in canvas degrees); the
        // steel gradient already darkens the lower right.
        rimHighlightPaint.strokeWidth = 2.5f * scale
        rimHighlightPaint.setShadowLayer(6f * scale, 0f, 0f, Color.parseColor("#ffffff"))
        canvas.drawArc(cx - r, cy - r, cx + r, cy + r, 225f, 90f, false, rimHighlightPaint)
        rimHighlightPaint.clearShadowLayer()

        // Fine LED-orange accent ring — the scale track the speed arc rides on.
        val arcR = r - 12f * scale
        accentRingPaint.strokeWidth = 1.5f * scale
        accentRingPaint.alpha = 200
        canvas.drawArc(cx - arcR, cy - arcR, cx + arcR, cy + arcR,
            Math.toDegrees(a0).toFloat(), Math.toDegrees(sweep).toFloat(), false, accentRingPaint)

        // Target marker (green tick) while a run is armed/running.
        if (showTarget && target > 0 && target <= maxSpd) {
            val ta = Math.toDegrees(angleFor(target)).toFloat()
            targetPaint.strokeWidth = 5f * scale
            targetPaint.setShadowLayer(8f * scale, 0f, 0f, Color.parseColor("#39ff14"))
            canvas.drawArc(cx - arcR, cy - arcR, cx + arcR, cy + arcR, ta - 1.5f, 3f, false, targetPaint)
            targetPaint.clearShadowLayer()
        }

        // Speed arc — thin LED orange on the accent ring. Build #78:
        // a wide soft halo pass under the solid core, so the arc glows
        // like the render instead of reading as one flat shadow pass.
        if (ratio > 0) {
            arcGlowPaint.strokeWidth = 8f * scale
            arcGlowPaint.setShadowLayer(16f * scale, 0f, 0f, Color.parseColor("#ff6a00"))
            canvas.drawArc(cx - arcR, cy - arcR, cx + arcR, cy + arcR,
                Math.toDegrees(a0).toFloat(), (Math.toDegrees(sweep) * ratio).toFloat(), false, arcGlowPaint)
            arcGlowPaint.clearShadowLayer()
            arcPaint.strokeWidth = 4f * scale
            arcPaint.setShadowLayer(4f * scale, 0f, 0f, Color.parseColor("#ff6a00"))
            canvas.drawArc(cx - arcR, cy - arcR, cx + arcR, cy + arcR,
                Math.toDegrees(a0).toFloat(), (Math.toDegrees(sweep) * ratio).toFloat(), false, arcPaint)
            arcPaint.clearShadowLayer()
        }

        // Ticks + numbers. Wider dials label every 20 mph so they don't crowd.
        // Build #78: numbers carry a soft white glow (the render's digits
        // are lit, not flat grey).
        val minorStep = 5
        val majorStep = if (maxSpd > 100) 20 else 10
        textPaint.setShadowLayer(5f * scale, 0f, 0f, Color.parseColor("#66ffffff"))
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
                textPaint.textSize = 11f * scale
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
        textPaint.clearShadowLayer()

        // Needle — Build #78: wide soft halo pass, then the solid core,
        // so the needle glows like the render's instead of sitting flat.
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
        needleGlowPaint.setShadowLayer(18f * scale, 0f, 0f, Color.parseColor("#ff6a00"))
        canvas.drawPath(path, needleGlowPaint)
        needleGlowPaint.clearShadowLayer()
        needlePaint.setShadowLayer(4f * scale, 0f, 0f, Color.parseColor("#ff6a00"))
        canvas.drawPath(path, needlePaint)
        needlePaint.clearShadowLayer()

        // Hub — Build #78: a radial LED-orange glow behind the hub so the
        // center of the dial is lit, like the render's glowing hub.
        hubGlowPaint.shader = RadialGradient(
            cx, cy, 44f * scale,
            intArrayOf(Color.parseColor("#33ff6a00"), Color.parseColor("#00000000")),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, 44f * scale, hubGlowPaint)
        hubGlowPaint.shader = null
        hubStrokePaint.strokeWidth = 2f * scale
        canvas.drawCircle(cx, cy, 12f * scale, hubPaint)
        canvas.drawCircle(cx, cy, 12f * scale, hubStrokePaint)
        hubDotPaint.setShadowLayer(6f * scale, 0f, 0f, Color.parseColor("#ff6a00"))
        canvas.drawCircle(cx, cy, 5f * scale, hubDotPaint)
        hubDotPaint.clearShadowLayer()

        // Live heading below the 50 (look-pro) — drawn last so it paints
        // over the needle when the needle points straight up at 50 mph.
        // The 50 number sits at radius r-36*scale straight up; the heading
        // sits just below its glyph.
        heading?.let {
            headingPaint.textSize = 13f * scale
            headingPaint.setShadowLayer(4f * scale, 0f, 0f, Color.parseColor("#59ff6a00"))
            val numR = r - 36f * scale
            canvas.drawText(it, cx, cy - numR + 22f * scale, headingPaint)
            headingPaint.clearShadowLayer()
        }
    }
}
