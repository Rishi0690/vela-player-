package com.hark.velaplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Simple bar chart of focus minutes per day, with the daily goal as a dashed line. */
class FocusChartView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    private val d = resources.displayMetrics.density
    private var data: List<Pair<Long, Long>> = emptyList()
    private var goalMs = 0L
    private var progress = 0f
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barDim = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * d; textAlign = Paint.Align.CENTER }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10 * d; textAlign = Paint.Align.CENTER }
    private val goalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.5f * d; style = Paint.Style.STROKE; pathEffect = android.graphics.DashPathEffect(floatArrayOf(6 * d, 5 * d), 0f) }
    private val r = RectF()
    private val dayFmt = SimpleDateFormat("EEEEE", Locale.getDefault())

    init {
        val primary = ThemeManager.primary(c)
        val onSurface = ThemeManager.attr(c, com.google.android.material.R.attr.colorOnSurface)
        bar.color = primary
        barDim.color = (primary and 0x00FFFFFF) or 0x40000000
        label.color = (onSurface and 0x00FFFFFF) or 0xB3000000.toInt()
        value.color = onSurface
        goalPaint.color = (onSurface and 0x00FFFFFF) or 0x80000000.toInt()
    }

    fun setData(days: List<Pair<Long, Long>>, goal: Long) {
        data = days; goalMs = goal
        contentDescription = "Focus time chart. " + days.joinToString(", ") { "${SimpleDateFormat("EEE d", Locale.getDefault()).format(Date(it.first))}: ${FocusFmt.dur(it.second)}" }
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600; interpolator = Motion.emphasized
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
        }.start()
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (168 * d).toInt())

    override fun onDraw(canvas: Canvas) {
        if (data.isEmpty()) return
        val top = 18 * d; val bottom = height - 22 * d
        val maxV = maxOf(data.maxOf { it.second }, goalMs, 60_000L).toFloat()
        val slot = width.toFloat() / data.size
        val bw = (slot * 0.56f).coerceAtMost(26 * d)
        data.forEachIndexed { i, (day, ms) ->
            val cx = slot * i + slot / 2
            val h = (bottom - top) * (ms / maxV) * progress
            r.set(cx - bw / 2, top, cx + bw / 2, bottom); canvas.drawRoundRect(r, bw / 2.5f, bw / 2.5f, barDim)
            if (ms > 0) { r.set(cx - bw / 2, bottom - h.coerceAtLeast(bw / 2), cx + bw / 2, bottom); canvas.drawRoundRect(r, bw / 2.5f, bw / 2.5f, bar) }
            canvas.drawText(dayFmt.format(Date(day)), cx, height - 6 * d, label)
            if (i == data.lastIndex && ms > 0) canvas.drawText(FocusFmt.dur(ms), cx, (bottom - h - 4 * d).coerceAtLeast(top - 4 * d), value)
        }
        if (goalMs > 0) {
            val y = bottom - (bottom - top) * (goalMs / maxV)
            canvas.drawLine(0f, y, width.toFloat(), y, goalPaint)
        }
    }
}
