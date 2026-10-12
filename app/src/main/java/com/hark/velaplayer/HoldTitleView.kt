package com.hark.velaplayer

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.hypot

/**
 * v2.3 Home title ("Vela Player") that opens the Hidden Vault after a continuous 5-second hold.
 *
 * Looks exactly like the toolbar title. While held, a thin accent underline fades in after ~0.5 s and grows
 * until the 5 s are up. Releasing, moving past touch slop, a second finger, or [cancelHold] (list scroll, tab
 * change, pause) cancels it. A short haptic confirms the trigger; repeated triggers within 1.5 s are ignored.
 * Taps do nothing, so the title never interferes with the toolbar menu or the tabs below.
 */
class HoldTitleView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : AppCompatTextView(context, attrs) {

    companion object {
        const val HOLD_MS = 5_000L
        private const val FEEDBACK_DELAY_MS = 500L
        private const val DEBOUNCE_MS = 1_500L
    }

    var onHoldComplete: (() -> Unit)? = null
    /** Return false to ignore holds right now (e.g. while reordering or selecting). */
    var canHold: () -> Boolean = { true }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private var downX = 0f; private var downY = 0f
    private var holding = false
    private var progress = 0f
    private var lineAlpha = 0f
    private var animator: ValueAnimator? = null
    private var lastTrigger = 0L

    private val fire = Runnable {
        if (!holding) return@Runnable
        val now = SystemClock.elapsedRealtime()
        stop()
        if (now - lastTrigger < DEBOUNCE_MS) return@Runnable
        lastTrigger = now
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onHoldComplete?.invoke()
    }

    private val startFeedback = Runnable {
        if (!holding) return@Runnable
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = HOLD_MS - FEEDBACK_DELAY_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                progress = f
                lineAlpha = (f * 6f).coerceAtMost(1f) // fades in over the first ~0.75 s of the bar
                invalidate()
            }
            start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isEnabled || !canHold()) return false
                downX = e.x; downY = e.y
                holding = true
                removeCallbacks(fire); removeCallbacks(startFeedback)
                postDelayed(startFeedback, FEEDBACK_DELAY_MS)
                postDelayed(fire, HOLD_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (holding && hypot(e.x - downX, e.y - downY) > slop) cancelHold()
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelHold()
        }
        return holding
    }

    /** Stops a hold in progress (no-op when idle). */
    fun cancelHold() {
        if (!holding && lineAlpha == 0f) return
        stop()
    }

    private fun stop() {
        holding = false
        removeCallbacks(fire); removeCallbacks(startFeedback)
        animator?.cancel(); animator = null
        if (lineAlpha > 0f) {
            val from = lineAlpha
            animator = ValueAnimator.ofFloat(from, 0f).apply {
                duration = 180
                addUpdateListener { lineAlpha = it.animatedValue as Float; invalidate() }
                start()
            }
        } else { progress = 0f; invalidate() }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) cancelHold()
    }

    override fun onDetachedFromWindow() {
        holding = false
        removeCallbacks(fire); removeCallbacks(startFeedback); animator?.cancel(); animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (lineAlpha <= 0f || progress <= 0f) return
        line.color = ThemeManager.primary(context)
        line.alpha = (lineAlpha * 200).toInt()
        line.strokeWidth = 2f * density
        val y = height - paddingBottom + 2.5f * density
        val start = paddingLeft.toFloat()
        val textW = layout?.getLineWidth(0) ?: (width - paddingLeft - paddingRight).toFloat()
        canvas.drawLine(start, y.coerceAtMost(height - line.strokeWidth), start + textW * progress, y.coerceAtMost(height - line.strokeWidth), line)
    }
}
