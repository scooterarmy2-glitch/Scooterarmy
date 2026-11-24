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

    /** Live heading label below the 50, e.g. "NW" (or "—" when stopped).
     *  Set by MainActivity from the GPS fix; null hides it. */
    var heading: String? = null
        set(value) { if (field != value) { field = value; invalidate() } }

    private val orbitron700: Typeface by lazy { resources.getFont(R.font.orbitron_700) }
    private val orbitron900: Typeface by lazy { resources.getFont(R.font.orbitron_900) }

    // Build #87 blue/white (Casey Nov 23): the dial follows the map —
    // bright-blue LED lines on deep navy, white digits. The needle is
    // white so it stands out against the blue arc; the arc, ring and
    // hub glow blue. Green stays ONLY on the target tick (the goal
    // marker); red never appears on the dial face.
    // Build #89: STRONG underglow under the outer rim — two blurred
    // blue arcs drawn BEFORE the rim, so the rim covers their inner
    // part and the glow spills outward onto the black background.
    // (Casey Nov 23 picked STRONG over soft.) The paints are the
    // glow passes; see onDraw for the widths / radii.
    private val rimGlowHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#00b4ff")
    }
    private val rimGlowCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#00b4ff")
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val accentRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#00b4ff")
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#00b4ff")
    }
    // Build #78 LED glow passes — wide translucent halo under the solid
    // core, so the arc and needle read as lit, not painted.
    private val arcGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#4000b4ff")
    }
    private val rimHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#f5f5f5"); alpha = 220
    }
    private val needleGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.parseColor("#4000b4ff")
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
        style = Paint.Style.FILL; color = Color.parseColor("#ffffff")
    }
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.parseColor("#141414")
    }
    private val hubStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.parseColor("#3a3a3a")
    }
    private val hubDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.parseColor("#00b4ff")
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
        // Build #89: rim inset 10 -> 4 (RIM_INSET_UNITS, Models.kt) —
        // the rim stroke is 7*scale wide, centred on r, so its outer
        // edge sits at r + 3.5*scale. With the inset at 4*scale the
        // outer edge lands 0.5*scale (~1.7px at 1080px) inside the
        // view edge: the rim TOUCHES the screen edges (Casey Nov 23:
        // "edge to edge literally") without being clipped. The view
        // itself is now exactly the window width (Models.kt
        // gaugeSizeForScreen), so that ~1.7px is the whole side
        // margin. The glow shadows still extend past the rim and are
        // clipped by the view bounds — they just fade at the edge,
        // which is the look.
        val r = min(w, h) / 2f - RIM_INSET_UNITS * scale
        val ratio = min(speed / maxSpd, 1.0)

        // Build #89: STRONG underglow under the outer rim (Casey Nov 23
        // picked STRONG). Two passes, drawn BEFORE the rim so the rim
        // covers the inner part and the glow spills outward onto the
        // black background around the ring — glow ONLY under the outer
        // ring, not under the ticks, labels or digit. Scaled from the
        // reference render (1080x2340, #88 gauge 1047px, scale 3.272):
        // halo 70px wide / alpha 120 / blur 30, core 34px wide /
        // alpha 200 / blur 14. In `scale` units (px per 320-unit, so
        // dp-based at any gauge size): halo 21.4 wide / blur 9.2 at
        // radius r + 9.2, core 10.4 wide / blur 4.3 at radius r + 6.7.
        // Same 270° sweep as the dial. Blue #00b4ff only; the ring
        // itself is untouched (grey/white, drawn next).
        rimGlowHaloPaint.strokeWidth = 21.4f * scale
        rimGlowHaloPaint.alpha = 120
        rimGlowHaloPaint.setShadowLayer(9.2f * scale, 0f, 0f, Color.parseColor("#00b4ff"))
        val haloR = r + 9.2f * scale
        canvas.drawArc(cx - haloR, cy - haloR, cx + haloR, cy + haloR,
            Math.toDegrees(a0).toFloat(), Math.toDegrees(sweep).toFloat(), false, rimGlowHaloPaint)
        rimGlowHaloPaint.clearShadowLayer()
        rimGlowCorePaint.strokeWidth = 10.4f * scale
        rimGlowCorePaint.alpha = 200
        rimGlowCorePaint.setShadowLayer(4.3f * scale, 0f, 0f, Color.parseColor("#00b4ff"))
        val coreR = r + 6.7f * scale
        canvas.drawArc(cx - coreR, cy - coreR, cx + coreR, cy + coreR,
            Math.toDegrees(a0).toFloat(), Math.toDegrees(sweep).toFloat(), false, rimGlowCorePaint)
        rimGlowCorePaint.clearShadowLayer()

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
        rimHighlightPaint.setShadowLayer(8f * scale, 0f, 0f, Color.parseColor("#ffffff"))
        canvas.drawArc(cx - r, cy - r, cx + r, cy + r, 225f, 90f, false, rimHighlightPaint)
        rimHighlightPaint.clearShadowLayer()

        // Fine LED-blue accent ring — the scale track the speed arc rides on.
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

        // Speed arc — thin LED blue on the accent ring. Build #78:
        // a wide soft halo pass under the solid core, so the arc glows
        // like the render instead of reading as one flat shadow pass.
        // Build #88: a little more glow (Casey Nov 23) — halo 16 -> 20,
        // core shadow 4 -> 6; still blue/white only.
        if (ratio > 0) {
            arcGlowPaint.strokeWidth = 8f * scale
            arcGlowPaint.setShadowLayer(20f * scale, 0f, 0f, Color.parseColor("#00b4ff"))
            canvas.drawArc(cx - arcR, cy - arcR, cx + arcR, cy + arcR,
                Math.toDegrees(a0).toFloat(), (Math.toDegrees(sweep) * ratio).toFloat(), false, arcGlowPaint)
            arcGlowPaint.clearShadowLayer()
            arcPaint.strokeWidth = 4f * scale
            arcPaint.setShadowLayer(6f * scale, 0f, 0f, Color.parseColor("#00b4ff"))
            canvas.drawArc(cx - arcR, cy - arcR, cx + arcR, cy + arcR,
                Math.toDegrees(a0).toFloat(), (Math.toDegrees(sweep) * ratio).toFloat(), false, arcPaint)
            arcPaint.clearShadowLayer()
        }

        // Ticks + numbers. Wider dials label every 20 mph so they don't crowd.
        // Build #78: numbers carry a soft white glow (the render's digits
        // are lit, not flat grey). Build #88: a touch more (5 -> 7,
        // #66 -> #88) so the digits glow with the arc.
        val minorStep = 5
        val majorStep = if (maxSpd > 100) 20 else 10
        textPaint.setShadowLayer(7f * scale, 0f, 0f, Color.parseColor("#88ffffff"))
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
        needleGlowPaint.setShadowLayer(24f * scale, 0f, 0f, Color.parseColor("#00b4ff"))
        canvas.drawPath(path, needleGlowPaint)
        needleGlowPaint.clearShadowLayer()
        needlePaint.setShadowLayer(6f * scale, 0f, 0f, Color.parseColor("#00b4ff"))
        canvas.drawPath(path, needlePaint)
        needlePaint.clearShadowLayer()

        // Hub — Build #78: a radial LED-blue glow behind the hub so the
        // center of the dial is lit, like the render's glowing hub.
        // Build #88: glow radius 44 -> 48, a little more light.
        hubGlowPaint.shader = RadialGradient(
            cx, cy, 48f * scale,
            intArrayOf(Color.parseColor("#3300b4ff"), Color.parseColor("#00000000")),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, 44f * scale, hubGlowPaint)
        hubGlowPaint.shader = null
        hubStrokePaint.strokeWidth = 2f * scale
        canvas.drawCircle(cx, cy, 12f * scale, hubPaint)
        canvas.drawCircle(cx, cy, 12f * scale, hubStrokePaint)
        hubDotPaint.setShadowLayer(8f * scale, 0f, 0f, Color.parseColor("#00b4ff"))
        canvas.drawCircle(cx, cy, 5f * scale, hubDotPaint)
        hubDotPaint.clearShadowLayer()

        // Live heading below the 50 (look-pro) — drawn last so it paints
        // over the needle when the needle points straight up at 50 mph.
        // The 50 number sits at radius r-36*scale straight up; the heading
        // sits just below its glyph.
        heading?.let {
            headingPaint.textSize = 13f * scale
            headingPaint.setShadowLayer(6f * scale, 0f, 0f, Color.parseColor("#5900b4ff"))
            val numR = r - 36f * scale
            canvas.drawText(it, cx, cy - numR + 22f * scale, headingPaint)
            headingPaint.clearShadowLayer()
        }
    }
}
