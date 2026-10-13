package com.hark.velaplayer

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * v2.12 floating pill navigation for the home tabs.
 *
 * It is still the same single-selection [ChipGroup] (same chips, ids, order, listener and swipe logic in
 * MainActivity); this class only draws: a near-white pill behind the checked chip that slides to the next tab
 * with a "liquid" spring (the leading edge moves first, the trailing edge follows, so the pill stretches and
 * settles), and each chip's text colour follows how much of the pill is under it (dark on the pill, muted grey
 * off it). The dark glass container is the parent's background (bg_nav_pill_container).
 */
class PillNavGroup @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : ChipGroup(c, a) {
    private val d = resources.displayMetrics.density
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillShadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var curL = 0f; private var curR = 0f
    private var anim: ValueAnimator? = null
    private var hasPos = false
    private val onSel: Int
    private val offText: Int

    init {
        setWillNotDraw(false)
        pill.color = Glass.navSelected(c)
        pillShadow.color = ColorUtils.setAlphaComponent(0xFF000000.toInt(), if (isLight(c)) 0x18 else 0x40)
        onSel = Glass.navOnSelected(c)
        offText = Glass.navText(c)
    }

    private fun isLight(c: Context): Boolean {
        val tv = android.util.TypedValue()
        return c.theme.resolveAttribute(androidx.appcompat.R.attr.isLightTheme, tv, true) && tv.data != 0
    }

    private fun checkedChip(): Chip? = (0 until childCount).map { getChildAt(it) }.firstOrNull { it is Chip && it.isChecked } as? Chip

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (anim?.isRunning != true) snap()
    }

    /** Puts the pill under the checked chip without animating (first layout, rotation, restored state). */
    fun snap() {
        val c = checkedChip() ?: return
        if (c.width == 0) return
        curL = c.left.toFloat(); curR = c.right.toFloat(); hasPos = true
        tintTexts(); invalidate()
    }

    /** Slides the pill to the checked chip. Call after the checked chip changed (tab tap or swipe). */
    fun slideToChecked() {
        val c = checkedChip() ?: return
        val toL = c.left.toFloat(); val toR = c.right.toFloat()
        if (!hasPos || !Motion.enabled(context) || c.width == 0) { snap(); return }
        anim?.cancel()
        val fromL = curL; val fromR = curR
        val right = toL > fromL
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520
            addUpdateListener {
                val p = it.animatedValue as Float
                val lead = Motion.spring.getInterpolation((p / 0.78f).coerceIn(0f, 1f))
                val trail = Motion.spring.getInterpolation(((p - 0.1f) / 0.9f).coerceIn(0f, 1f))
                val fl = if (right) trail else lead
                val fr = if (right) lead else trail
                curL = fromL + (toL - fromL) * fl
                curR = fromR + (toR - fromR) * fr
                if (curR < curL + 8 * d) curR = curL + 8 * d
                tintTexts(); invalidate()
            }
            start()
        }
    }

    /** Text colour of each chip = how much of the pill covers it (so the colour "travels" with the pill). */
    private fun tintTexts() {
        for (i in 0 until childCount) {
            val ch = getChildAt(i) as? Chip ?: continue
            if (ch.width == 0) continue
            val overlap = (minOf(curR, ch.right.toFloat()) - maxOf(curL, ch.left.toFloat())).coerceAtLeast(0f) / ch.width
            val col = ColorUtils.blendARGB(offText, onSel, overlap.coerceIn(0f, 1f))
            ch.setTextColor(col)
            ch.chipIconTint = android.content.res.ColorStateList.valueOf(col)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (hasPos) {
            val c = checkedChip()
            val top = (c?.top ?: paddingTop).toFloat()
            val bottom = (c?.bottom ?: (height - paddingBottom)).toFloat()
            val r = (bottom - top) / 2f
            rect.set(curL, top + d, curR, bottom + d)
            canvas.drawRoundRect(rect, r, r, pillShadow)
            rect.set(curL, top, curR, bottom)
            canvas.drawRoundRect(rect, r, r, pill)
        }
        super.dispatchDraw(canvas)
    }

    override fun onViewAdded(child: View?) { super.onViewAdded(child); post { snap() } }
}
