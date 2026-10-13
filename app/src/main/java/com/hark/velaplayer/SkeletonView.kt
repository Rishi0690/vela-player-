package com.hark.velaplayer

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * v2.12 loading state: glass skeleton rows (thumbnail + two text lines) with a soft light sweeping across them,
 * shown while the library loads for the first time. Pure canvas, one shader; the animation runs only while the view
 * is visible and attached, and stays still when animations are off.
 */
class SkeletonView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    private val d = resources.displayMetrics.density
    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Glass.elevated(c) }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val m = Matrix()
    private var phase = 0f
    private var anim: ValueAnimator? = null
    private val hi = Glass.highlight(c)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        shine.shader = LinearGradient(0f, 0f, w * 0.45f, 0f,
            intArrayOf(hi and 0x00FFFFFF, (hi and 0x00FFFFFF) or (minOf(0x30, (hi ushr 24)) shl 24), hi and 0x00FFFFFF),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); start() }
    override fun onDetachedFromWindow() { anim?.cancel(); anim = null; super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) start() else { anim?.cancel(); anim = null }
    }

    private fun start() {
        if (anim != null || !Motion.enabled(context) || visibility != VISIBLE) return
        anim = ValueAnimator.ofFloat(-0.5f, 1.5f).apply {
            duration = 1400; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val pad = 16 * d
        val rowH = 84 * d
        val thumbW = 120 * d; val thumbH = 68 * d
        var y = 12 * d
        var i = 0
        while (y + rowH < height && i < 12) {
            draw(c, pad, y, pad + thumbW, y + thumbH, 14 * d)
            val tx = pad + thumbW + 14 * d
            draw(c, tx, y + 10 * d, width - pad - 24 * d, y + 22 * d, 6 * d)
            draw(c, tx, y + 32 * d, tx + (width - tx - pad) * 0.5f, y + 42 * d, 5 * d)
            y += rowH; i++
        }
    }

    private fun draw(c: Canvas, l: Float, t: Float, rr: Float, b: Float, rad: Float) {
        r.set(l, t, rr, b)
        c.drawRoundRect(r, rad, rad, base)
        if (shine.shader != null && Motion.enabled(context)) {
            m.setTranslate(width * phase - width * 0.2f, 0f)
            shine.shader.setLocalMatrix(m)
            c.drawRoundRect(r, rad, rad, shine)
        }
    }
}
