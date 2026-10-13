package com.hark.velaplayer

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.core.graphics.ColorUtils

/**
 * The player timeline. Keeps SeekBar's touch handling and accessibility, but draws its own look:
 * a rounded track with the buffered part and the played part, and a thumb that grows while dragging.
 * Tapping anywhere jumps there; the touch area is much taller than the visible track.
 *
 * v2.12: the played part is a soft accent gradient, the thumb is a white-cored accent bead with a glow that blooms
 * while dragging, and small playback steps glide (the drawn position eases toward the real one over a few frames;
 * drags and jumps are drawn instantly so seeking never lags). Touch/seek logic is unchanged.
 */
class TimelineSeekBar @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : AppCompatSeekBar(c, a) {
    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x3DFFFFFF }
    private val buffered = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }
    private val played = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
    private val trackShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
    private val r = RectF()
    private var active = 0f // 0 = resting, 1 = being dragged
    private var anim: ValueAnimator? = null
    private var accent = 0
    private var shownFrac = -1f
    private var glowFor = 0f

    init {
        // Invisible stock visuals; the thumb keeps a real size so touch geometry stays correct.
        progressDrawable = ColorDrawable(0)
        thumb = ShapeDrawable(OvalShape()).apply { intrinsicWidth = (20 * d).toInt(); intrinsicHeight = (20 * d).toInt(); paint.color = 0 }
        splitTrack = false
        setPadding((10 * d).toInt(), 0, (10 * d).toInt(), 0)
        setAccent(ThemeManager.primary(c))
    }

    fun setAccent(color: Int) { accent = color; thumbPaint.color = color; played.shader = null; glowFor = 0f; invalidate() }

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
            duration = if (v > active) 160 else 300
            interpolator = if (v > active) Motion.spring else Motion.emphasized
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
        val th = (4f + 2.5f * active) * d          // track thickness: 4dp → 6.5dp while dragging
        val max = max.coerceAtLeast(1).toFloat()
        val target = (progress / max).coerceIn(0f, 1f)
        // Glide small forward steps (normal playback); jump for drags, seeks and big changes.
        shownFrac = if (shownFrac < 0f || isPressed || active > 0f || kotlin.math.abs(target - shownFrac) > 0.02f || !Motion.enabled(context)) target
            else shownFrac + (target - shownFrac) * 0.3f
        if (kotlin.math.abs(target - shownFrac) > 0.0005f) postInvalidateOnAnimation() else shownFrac = target
        val px = left + w * shownFrac
        val bx = left + w * (secondaryProgress / max).coerceIn(0f, 1f)
        val rad = th / 2
        if (played.shader == null || glowFor != w) {
            glowFor = w
            played.shader = LinearGradient(left, 0f, right, 0f, ColorUtils.blendARGB(accent, 0xFFFFFFFF.toInt(), 0.25f), accent, Shader.TileMode.CLAMP)
        }
        r.set(left, cy - rad + d * 0.6f, right, cy + rad + d * 0.6f); canvas.drawRoundRect(r, rad, rad, trackShadow)
        r.set(left, cy - rad, right, cy + rad); canvas.drawRoundRect(r, rad, rad, track)
        if (bx > left) { r.set(left, cy - rad, bx, cy + rad); canvas.drawRoundRect(r, rad, rad, buffered) }
        if (px > left) { r.set(left, cy - rad, px, cy + rad); canvas.drawRoundRect(r, rad, rad, played) }
        if (isEnabled) {
            val tr = (6.5f + 3.5f * active) * d       // thumb radius: 6.5dp → 10dp
            val gr = tr * (2.2f + 1.4f * active)
            glow.shader = RadialGradient(px, cy, gr, intArrayOf((accent and 0xFFFFFF) or ((0x55 + (0x40 * active).toInt()) shl 24), accent and 0xFFFFFF),
                floatArrayOf(0.25f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(px, cy, gr, glow)
            canvas.drawCircle(px, cy + d, tr + d, thumbShadow)
            canvas.drawCircle(px, cy, tr, thumbPaint)
            canvas.drawCircle(px, cy, tr * (0.42f - 0.08f * active), core)
        }
    }
}
