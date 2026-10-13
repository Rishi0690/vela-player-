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
    /** v2.8: soft settle for small UI changes (chips, toggles, empty states). */
    val standard = PathInterpolator(0.2f, 0f, 0f, 1f)

    /** v2.8: shared durations so every screen moves at the same pace. */
    const val SHORT = 150L
    const val MEDIUM = 240L
    const val LONG = 320L

    /** False when the user turned animations off (Developer options / Accessibility "Remove animations"). */
    fun enabled(c: android.content.Context): Boolean =
        android.provider.Settings.Global.getFloat(c.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

    /** Pressing shrinks the view a little and it springs back on release. Clicks still work normally. */
    @SuppressLint("ClickableViewAccessibility")
    fun pressable(v: View, scale: Float = 0.96f) {
        v.setOnTouchListener { view, e ->
            if (!enabled(view.context)) return@setOnTouchListener false
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
        if (!enabled(v.context)) { v.scaleX = 1f; v.scaleY = 1f; return }
        v.scaleX = 0.82f; v.scaleY = 0.82f
        v.animate().scaleX(1f).scaleY(1f).setDuration(360).setInterpolator(spring).start()
    }

    /** Fades and lifts a view into place. */
    fun enter(v: View, fromY: Float = 0f, delay: Long = 0, duration: Long = 280) {
        v.animate().cancel()
        v.visibility = View.VISIBLE
        if (!enabled(v.context)) { v.alpha = 1f; v.translationY = 0f; return }
        v.alpha = 0f; v.translationY = fromY
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(duration).setInterpolator(emphasized).start()
    }

    fun haptic(v: View, long: Boolean = false) {
        v.performHapticFeedback(if (long) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.CLOCK_TICK)
    }

    /** v2.8: fades a view out and sets it GONE at the end (cancellable, never leaves it half-visible). */
    fun exit(v: View, duration: Long = SHORT) {
        v.animate().cancel()
        if (v.visibility != View.VISIBLE) return
        if (!enabled(v.context)) { v.visibility = View.GONE; return }
        v.animate().alpha(0f).setStartDelay(0).setDuration(duration).setInterpolator(emphasizedAccel)
            .withEndAction { v.visibility = View.GONE; v.alpha = 1f }.start()
    }

    /** v2.8: shows an empty/error/loading message with a gentle fade-up; no-op when it's already showing. */
    fun reveal(v: View, show: Boolean) {
        if (show) { if (v.visibility != View.VISIBLE) enter(v, fromY = 12 * v.resources.displayMetrics.density, duration = MEDIUM) }
        else { v.animate().cancel(); v.visibility = View.GONE; v.alpha = 1f; v.translationY = 0f }
    }
}
