package com.hark.velaplayer

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Region
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import kotlin.math.hypot
import kotlin.math.max

/**
 * v2.10: one-tap dark/light switch on the home toolbar (next to search) with a circular reveal.
 * Before the theme changes we snapshot the screen; after the activity recreates in the new theme the old snapshot
 * is laid on top and a growing circle (from the button) cuts it away, so the new theme "spreads" out of the icon.
 */
object ThemeSwitch {
    private class Pending(val shot: Bitmap, val cx: Float, val cy: Float, val toDark: Boolean, val fade: Boolean = false)
    private var pending: Pending? = null

    fun isPendingAvailable() = pending != null

    fun isDark(c: Context) =
        (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun iconFor(c: Context) = if (isDark(c)) R.drawable.ic_light_mode else R.drawable.ic_dark_mode

    fun toggle(a: Activity, anchor: View?) {
        val toDark = !isDark(a)
        val decor = a.window.decorView
        val shot = runCatching {
            Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) }
        }.getOrNull()
        var cx = decor.width - 120f; var cy = 120f
        anchor?.let { v ->
            val loc = IntArray(2); v.getLocationInWindow(loc)
            cx = loc[0] + v.width / 2f; cy = loc[1] + v.height / 2f
        }
        if (anchor != null) Motion.haptic(anchor)
        pending = shot?.let { Pending(it, cx, cy, toDark) }
        Prefs.sp(a).edit().putString("theme", if (toDark) "dark" else "light").apply()
        a.overridePendingTransition(0, 0)
        Prefs.applyTheme(a) // AppCompat recreates every open screen in the new mode
    }

    /**
     * v2.11: snapshot the screen so that, after [Activity.recreate] in a new theme, the old look cross-fades into the
     * new one instead of flashing. Call right before recreate(); the recreated screen calls [playIfPending].
     */
    fun prepareCrossfade(a: Activity) {
        val decor = a.window.decorView
        if (decor.width == 0 || decor.height == 0) return
        val shot = runCatching {
            Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) }
        }.getOrNull() ?: return
        pending = Pending(shot, 0f, 0f, isDark(a), fade = true)
    }

    /** Call from the recreated activity's onCreate (after setContentView). */
    fun playIfPending(a: Activity) {
        val p = pending ?: return
        pending = null
        val decor = a.window.decorView as ViewGroup
        if (p.fade) {
            val v = object : View(a) {
                init { isClickable = true }
                override fun onDraw(canvas: Canvas) { canvas.drawBitmap(p.shot, 0f, 0f, null) }
            }
            decor.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            v.animate().alpha(0f).setStartDelay(60).setDuration(if (Motion.enabled(a)) 340 else 1)
                .setInterpolator(PathInterpolator(0.3f, 0f, 0.1f, 1f))
                .withEndAction { decor.removeView(v); p.shot.recycle() }.start()
            return
        }
        val overlay = RevealView(a, p.shot, p.cx, p.cy)
        decor.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay.post {
            val end = max(max(hypot(p.cx, p.cy), hypot(overlay.width - p.cx, p.cy)),
                max(hypot(p.cx, overlay.height - p.cy), hypot(overlay.width - p.cx, overlay.height - p.cy)))
            ValueAnimator.ofFloat(0f, end).apply {
                duration = 620
                interpolator = PathInterpolator(0.3f, 0f, 0.1f, 1f)
                addUpdateListener { overlay.radius = it.animatedValue as Float }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        decor.removeView(overlay); p.shot.recycle()
                    }
                })
                start()
            }
        }
    }

    /** Spin-and-pop for the toolbar icon after the switch. */
    fun popIcon(v: View?) {
        v ?: return
        v.rotation = -120f; v.scaleX = 0.4f; v.scaleY = 0.4f; v.alpha = 0f
        v.animate().rotation(0f).scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(120).setDuration(520)
            .setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
    }

    private class RevealView(c: Context, val shot: Bitmap, val cx: Float, val cy: Float) : View(c) {
        private val path = Path()
        var radius = 0f
            set(value) { field = value; invalidate() }
        init { isClickable = true } // swallow taps while it plays
        override fun onDraw(canvas: Canvas) {
            path.reset(); path.addCircle(cx, cy, radius, Path.Direction.CW)
            canvas.save()
            if (Build.VERSION.SDK_INT >= 26) canvas.clipOutPath(path)
            else @Suppress("DEPRECATION") canvas.clipPath(path, Region.Op.DIFFERENCE)
            canvas.drawBitmap(shot, 0f, 0f, null)
            canvas.restore()
        }
    }
}
