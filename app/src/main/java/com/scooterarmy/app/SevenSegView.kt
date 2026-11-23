package com.scooterarmy.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/**
 * Seven-segment LED digits, drawn like the reel frame's huge orange speed.
 *
 * The frame's speed digits are classic seven-segment LED glyphs, not a
 * font — Orbitron renders "95" as solid angular letters, which reads as a
 * different graphic. This view draws the segments directly (hexagon-ended
 * bars, ember orange with a red-orange glow) so the on-open screen matches
 * the picture.
 *
 * API mirrors the TextView it replaces so the engine wiring needs no
 * changes: `text`, `setTextSize(unit, size)`, `setTextColor(color)`,
 * `setShadowLayer(...)` (inherited from View).
 */
object SevenSeg {
    // Bitmask: a=1 top, b=2 top-right, c=4 bottom-right, d=8 bottom,
    // e=16 bottom-left, f=32 top-left, g=64 middle.
    fun segmentsFor(c: Char): Int = when (c) {
        '0' -> 0b0111111
        '1' -> 0b0000110
        '2' -> 0b1011011
        '3' -> 0b1001111
        '4' -> 0b1100110
        '5' -> 0b1101101
        '6' -> 0b1111101
        '7' -> 0b0000111
        '8' -> 0b1111111
        '9' -> 0b1101111
        else -> 0
    }

    /** Digit cell: width 0.48 of height, gap 0.14 of height. Three digits
     *  shrink to 0.72 so the block stays the same width as two digits
     *  (speeds over 99 must not burst out of the inner disc). */
    fun digitHeightPx(textSizePx: Float, len: Int): Float =
        if (len >= 3) textSizePx * 0.72f else textSizePx

    fun measure(text: String, textSizePx: Float): Pair<Int, Int> {
        val h = digitHeightPx(textSizePx, text.length)
        val w = 0.48f * h
        val gap = 0.14f * h
        val totalW = text.length * w +
            (text.length - 1).coerceAtLeast(0) * gap
        return Pair(totalW.toInt(), h.toInt())
    }
}

class SevenSegView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var text: String = "0"
        set(v) {
            if (field != v) {
                field = v
                requestLayout()
                invalidate()
            }
        }

    private var textSizePx: Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, 160f, context.resources.displayMetrics
    )
    private var digitColor: Int = Color.parseColor("#ff6a00")
    // Glow, mirroring TextView.setShadowLayer (plain View has none):
    // stored here and applied to the segment paint in onDraw.
    private var shadowRadius: Float = -1f
    private var shadowDx: Float = 0f
    private var shadowDy: Float = 0f
    private var shadowColor: Int = Color.parseColor("#ff4400")

    fun setShadowLayer(radius: Float, dx: Float, dy: Float, color: Int) {
        shadowRadius = radius
        shadowDx = dx
        shadowDy = dy
        shadowColor = color
        invalidate()
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    init {
        attrs?.let {
            val a = context.obtainStyledAttributes(
                it,
                intArrayOf(
                    android.R.attr.text,
                    android.R.attr.textColor,
                    android.R.attr.textSize
                )
            )
            val t = a.getString(0)
            if (t != null) text = t
            if (a.hasValue(1)) digitColor = a.getColor(1, digitColor)
            if (a.hasValue(2)) textSizePx = a.getDimension(2, textSizePx)
            a.recycle()
        }
    }

    /** Mirrors TextView.setTextSize(unit, size). */
    fun setTextSize(unit: Int, size: Float) {
        textSizePx = TypedValue.applyDimension(
            unit, size, context.resources.displayMetrics
        )
        requestLayout()
        invalidate()
    }

    /** Mirrors TextView.setTextColor(color). */
    fun setTextColor(c: Int) {
        digitColor = c
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val (w, h) = SevenSeg.measure(text, textSizePx)
        setMeasuredDimension(
            resolveSize(w, widthMeasureSpec),
            resolveSize(h, heightMeasureSpec)
        )
    }

    private fun hSeg(path: Path, x: Float, y: Float, len: Float, t: Float) {
        // Horizontal bar, hexagon-ended (pointed left/right).
        val p = t * 0.6f
        path.moveTo(x, y + t / 2f)
        path.lineTo(x + p, y)
        path.lineTo(x + len - p, y)
        path.lineTo(x + len, y + t / 2f)
        path.lineTo(x + len - p, y + t)
        path.lineTo(x + p, y + t)
        path.close()
    }

    private fun vSeg(path: Path, x: Float, y: Float, len: Float, t: Float) {
        // Vertical bar, hexagon-ended (pointed top/bottom).
        val p = t * 0.6f
        path.moveTo(x + t / 2f, y)
        path.lineTo(x + t, y + p)
        path.lineTo(x + t, y + len - p)
        path.lineTo(x + t / 2f, y + len)
        path.lineTo(x, y + len - p)
        path.lineTo(x, y + p)
        path.close()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (text.isEmpty()) return
        val h = SevenSeg.digitHeightPx(textSizePx, text.length)
        val w = 0.48f * h
        val gap = 0.14f * h
        val t = w * 0.22f
        paint.color = digitColor
        val glowR = if (shadowRadius >= 0f) shadowRadius else h * 0.06f
        paint.setShadowLayer(glowR, shadowDx, shadowDy, shadowColor)
        for ((i, ch) in text.withIndex()) {
            val mask = SevenSeg.segmentsFor(ch)
            if (mask == 0) continue
            val x0 = i * (w + gap)
            val y0 = 0f
            val path = Path()
            // a: top, g: middle, d: bottom
            if (mask and 1 != 0) hSeg(path, x0, y0, w, t)
            if (mask and 64 != 0) hSeg(path, x0, y0 + h / 2f - t / 2f, w, t)
            if (mask and 8 != 0) hSeg(path, x0, y0 + h - t, w, t)
            // f: top-left, b: top-right, e: bottom-left, c: bottom-right
            val vLen = h / 2f - t * 0.25f
            if (mask and 32 != 0) vSeg(path, x0, y0, vLen, t)
            if (mask and 2 != 0) vSeg(path, x0 + w - t, y0, vLen, t)
            if (mask and 16 != 0) vSeg(path, x0, y0 + h / 2f, vLen, t)
            if (mask and 4 != 0) vSeg(path, x0 + w - t, y0 + h / 2f, vLen, t)
            canvas.drawPath(path, paint)
        }
        paint.clearShadowLayer()
    }
}
