package com.hark.velaplayer

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatSeekBar

/**
 * The player timeline. Keeps SeekBar's touch handling and accessibility, but draws its own look:
 * a rounded track with the buffered part and the played part, and a thumb that grows while dragging.
 * Tapping anywhere jumps there; the touch area is much taller than the visible track.
 */
class TimelineSeekBar @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : AppCompatSeekBar(c, a) {
    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x4DFFFFFF }
    private val buffered = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x80FFFFFF.toInt() }
    private val played = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
    private val trackShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
    private val r = RectF()
    private var active = 0f // 0 = resting, 1 = being dragged
    private var anim: ValueAnimator? = null

    init {
        // Invisible stock visuals; the thumb keeps a real size so touch geometry stays correct.
        progressDrawable = ColorDrawable(0)
        thumb = ShapeDrawable(OvalShape()).apply { intrinsicWidth = (20 * d).toInt(); intrinsicHeight = (20 * d).toInt(); paint.color = 0 }
        splitTrack = false
        setPadding((10 * d).toInt(), 0, (10 * d).toInt(), 0)
        val accent = ThemeManager.primary(c)
        played.color = accent; thumbPaint.color = accent
    }

    fun setAccent(color: Int) { played.color = color; thumbPaint.color = color; invalidate() }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); animateTo(1f) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animateTo(0f)
        }
        return super.onTouchEvent(event)
    }

    private fun animateTo(v: Float) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(active, v).apply {
            duration = if (v > active) 140 else 260
            interpolator = Motion.emphasized
            addUpdateListener { active = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    @Synchronized
    override fun onDraw(canvas: Canvas) {
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val w = right - left
        if (w <= 0) return
        val cy = height / 2f
        val th = (4f + 3f * active) * d           // track thickness: 4dp → 7dp while dragging
        val max = max.coerceAtLeast(1).toFloat()
        val px = left + w * (progress / max).coerceIn(0f, 1f)
        val bx = left + w * (secondaryProgress / max).coerceIn(0f, 1f)
        val rad = th / 2
        r.set(left, cy - rad + d * 0.6f, right, cy + rad + d * 0.6f); canvas.drawRoundRect(r, rad, rad, trackShadow)
        r.set(left, cy - rad, right, cy + rad); canvas.drawRoundRect(r, rad, rad, track)
        if (bx > left) { r.set(left, cy - rad, bx, cy + rad); canvas.drawRoundRect(r, rad, rad, buffered) }
        if (px > left) { r.set(left, cy - rad, px, cy + rad); canvas.drawRoundRect(r, rad, rad, played) }
        if (isEnabled) {
            val tr = (6.5f + 4f * active) * d       // thumb radius: 6.5dp → 10.5dp
            canvas.drawCircle(px, cy + d, tr + d, thumbShadow)
            canvas.drawCircle(px, cy, tr, thumbPaint)
            if (active > 0f) { thumbPaint.alpha = (60 * active).toInt(); canvas.drawCircle(px, cy, tr + 10 * d * active, thumbPaint); thumbPaint.alpha = 255 }
        }
    }
}
