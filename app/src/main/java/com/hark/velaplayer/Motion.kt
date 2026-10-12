package com.hark.velaplayer

import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator

/** Shared motion: soft, springy, quick — the same feel everywhere in the app. */
object Motion {
    /** Material "emphasized" curve: fast start, long gentle settle. */
    val emphasized = PathInterpolator(0.2f, 0f, 0f, 1f)
    val emphasizedAccel = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
    private val spring = OvershootInterpolator(2.2f)

    /** Pressing shrinks the view a little and it springs back on release. Clicks still work normally. */
    @SuppressLint("ClickableViewAccessibility")
    fun pressable(v: View, scale: Float = 0.96f) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(scale).scaleY(scale).setDuration(120).setInterpolator(emphasized).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(spring).start()
            }
            false
        }
    }

    /** Quick squash-and-spring, for taps like play/pause. */
    fun bounce(v: View) {
        v.animate().cancel()
        v.scaleX = 0.82f; v.scaleY = 0.82f
        v.animate().scaleX(1f).scaleY(1f).setDuration(360).setInterpolator(spring).start()
    }

    /** Fades and lifts a view into place. */
    fun enter(v: View, fromY: Float = 0f, delay: Long = 0, duration: Long = 280) {
        v.animate().cancel()
        v.visibility = View.VISIBLE
        v.alpha = 0f; v.translationY = fromY
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(duration).setInterpolator(emphasized).start()
    }

    fun haptic(v: View, long: Boolean = false) {
        v.performHapticFeedback(if (long) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.CLOCK_TICK)
    }
}
