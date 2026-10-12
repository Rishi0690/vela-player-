package com.hark.velaplayer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Saturation/brightness square with a hue bar underneath. Calls [onColorChanged] while dragging. */
class ColorPickerView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    private val d = resources.displayMetrics.density
    private val hsv = floatArrayOf(260f, 0.6f, 1f)
    var onColorChanged: ((Int) -> Unit)? = null

    private val svRect = RectF(); private val hueRect = RectF()
    private val svPaint = Paint(Paint.ANTI_ALIAS_FLAG); private val huePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d; color = Color.WHITE }
    private val ringShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5 * d; color = 0x66000000 }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private var dragging = 0 // 1 = square, 2 = hue bar
    private val radius = 16 * d

    var color: Int
        get() = Color.HSVToColor(hsv)
        set(v) { Color.colorToHSV(v, hsv); rebuildSv(); invalidate() }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        setMeasuredDimension(w, (w * 0.62f + 52 * d).toInt().coerceAtMost((300 * d).toInt()))
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val pad = 4 * d
        hueRect.set(pad, h - 30 * d, w - pad, h - 6 * d)
        svRect.set(pad, pad, w - pad, hueRect.top - 18 * d)
        val hues = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
        huePaint.shader = LinearGradient(hueRect.left, 0f, hueRect.right, 0f, hues, null, Shader.TileMode.CLAMP)
        rebuildSv()
    }

    private fun rebuildSv() {
        if (svRect.width() <= 0) return
        val pure = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        val sat = LinearGradient(svRect.left, 0f, svRect.right, 0f, Color.WHITE, pure, Shader.TileMode.CLAMP)
        val value = LinearGradient(0f, svRect.top, 0f, svRect.bottom, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        svPaint.shader = ComposeShader(sat, value, PorterDuff.Mode.SRC_OVER)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRoundRect(svRect, 18 * d, 18 * d, svPaint)
        canvas.drawRoundRect(hueRect, 12 * d, 12 * d, huePaint)
        val sx = svRect.left + hsv[1] * svRect.width()
        val sy = svRect.top + (1 - hsv[2]) * svRect.height()
        fill.color = color
        canvas.drawCircle(sx, sy, radius * 0.75f, fill); canvas.drawCircle(sx, sy, radius * 0.75f, ringShadow); canvas.drawCircle(sx, sy, radius * 0.75f, ring)
        val hx = hueRect.left + hsv[0] / 360f * hueRect.width()
        fill.color = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        canvas.drawCircle(hx, hueRect.centerY(), radius * 0.7f, fill); canvas.drawCircle(hx, hueRect.centerY(), radius * 0.7f, ringShadow); canvas.drawCircle(hx, hueRect.centerY(), radius * 0.7f, ring)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = if (e.y >= hueRect.top - 14 * d) 2 else 1
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = 0; parent?.requestDisallowInterceptTouchEvent(false); return true }
        }
        if (dragging == 2) {
            hsv[0] = ((e.x - hueRect.left) / hueRect.width()).coerceIn(0f, 1f) * 359.9f
            rebuildSv()
        } else if (dragging == 1) {
            hsv[1] = ((e.x - svRect.left) / svRect.width()).coerceIn(0f, 1f)
            hsv[2] = (1 - (e.y - svRect.top) / svRect.height()).coerceIn(0f, 1f)
        }
        invalidate()
        onColorChanged?.invoke(color)
        return true
    }
}
