package com.hark.velaplayer

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Shared motion system (v2.12 "cinematic" pass on top of the v2.8 tokens).
 *
 * Language: every interaction has its own gesture, but they all share these curves and durations.
 * - [emphasized] for things arriving, [emphasizedAccel] for things leaving, [spring] / [SpringInterpolator] for
 *   things the user moved (pills, toggles, presses), [decelerate] for reveals.
 * - Depth: entering screens zoom from 0.94–0.96 with a blur-to-sharp on Android 12+ (alpha/scale only on 11 and older).
 * - Reduced motion: when the system animator scale is 0 ("Remove animations"), every helper here jumps to its end
 *   state. Nothing here ever blocks input or playback.
 */
object Motion {
    /** Material "emphasized" curve: fast start, long gentle settle. */
    val emphasized = PathInterpolator(0.2f, 0f, 0f, 1f)
    val emphasizedAccel = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
    /** Soft deceleration for reveals (search field, panels, glass sheets). */
    val decelerate = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    private val overshoot = OvershootInterpolator(2.2f)
    /** v2.8: soft settle for small UI changes (chips, toggles, empty states). */
    val standard = PathInterpolator(0.2f, 0f, 0f, 1f)
    /** v2.12: tuned springs. [spring] = lively (pills, presses), [springSoft] = calm (panels, cards). */
    val spring: TimeInterpolator = SpringInterpolator(damping = 0.72f, frequency = 1.9f)
    val springSoft: TimeInterpolator = SpringInterpolator(damping = 0.86f, frequency = 1.4f)

    /** v2.8: shared durations so every screen moves at the same pace (v2.12 adds MICRO and XLONG). */
    const val MICRO = 90L
    const val SHORT = 150L
    const val MEDIUM = 240L
    const val LONG = 320L
    const val XLONG = 460L
    /** Stagger between neighbouring items in a group entrance. */
    const val STAGGER = 22L

    /** False when the user turned animations off (Developer options / Accessibility "Remove animations"). */
    fun enabled(c: android.content.Context): Boolean =
        android.provider.Settings.Global.getFloat(c.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

    /** Real blur exists from Android 12 (RenderEffect). Older phones get alpha + scale only. */
    val canBlur: Boolean get() = Build.VERSION.SDK_INT >= 31

    /** Pressing shrinks the view a little and it springs back on release. Clicks still work normally. */
    @SuppressLint("ClickableViewAccessibility")
    fun pressable(v: View, scale: Float = 0.96f) {
        v.setOnTouchListener { view, e ->
            if (!enabled(view.context)) return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(scale).scaleY(scale).setDuration(120).setInterpolator(emphasized).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(spring).start()
            }
            false
        }
    }

    /** Quick squash-and-spring, for taps like play/pause. */
    fun bounce(v: View) {
        v.animate().cancel()
        if (!enabled(v.context)) { v.scaleX = 1f; v.scaleY = 1f; return }
        v.scaleX = 0.82f; v.scaleY = 0.82f
        v.animate().scaleX(1f).scaleY(1f).setDuration(360).setInterpolator(overshoot).start()
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
        if (show) {
            if (v.visibility != View.VISIBLE) {
                enter(v, fromY = 12 * v.resources.displayMetrics.density, duration = MEDIUM)
                if (enabled(v.context)) { v.scaleX = 0.96f; v.scaleY = 0.96f; v.animate().scaleX(1f).scaleY(1f) }
                blurIn(v, 14f, MEDIUM + 60)
            }
        } else { v.animate().cancel(); v.visibility = View.GONE; v.alpha = 1f; v.translationY = 0f; v.scaleX = 1f; v.scaleY = 1f; clearBlur(v) }
    }

    // ------------------------------------------------------------------ v2.12 cinematic helpers

    /** Blur-to-sharp: the view starts at [radiusDp] blur and sharpens (Android 12+; no-op before, callers add alpha/scale). */
    fun blurIn(v: View, radiusDp: Float = 16f, duration: Long = LONG, delay: Long = 0) {
        if (!canBlur || !enabled(v.context)) return
        val px = radiusDp * v.resources.displayMetrics.density
        val tag = R.id.vela_blur_anim
        (v.getTag(tag) as? Animator)?.cancel()
        val a = ValueAnimator.ofFloat(px, 0f).apply {
            this.duration = duration; startDelay = delay; interpolator = decelerate
            addUpdateListener {
                val r = it.animatedValue as Float
                if (Build.VERSION.SDK_INT >= 31) v.setRenderEffect(if (r < 0.5f) null else RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { clearBlur(v); v.setTag(tag, null) }
                override fun onAnimationCancel(animation: Animator) { clearBlur(v) }
            })
        }
        v.setTag(tag, a)
        if (Build.VERSION.SDK_INT >= 31) v.setRenderEffect(RenderEffect.createBlurEffect(px, px, Shader.TileMode.CLAMP))
        a.start()
    }

    fun clearBlur(v: View) { if (Build.VERSION.SDK_INT >= 31) v.setRenderEffect(null) }

    /**
     * Screen-level "zoom through": the new content arrives from slightly smaller (opening a folder/playlist) or
     * slightly larger (going back), fading in and sharpening. Runs on a hardware layer.
     */
    fun zoomThrough(v: View, forward: Boolean) {
        v.animate().cancel()
        if (!enabled(v.context)) { v.alpha = 1f; v.scaleX = 1f; v.scaleY = 1f; return }
        val from = if (forward) 0.94f else 1.05f
        v.alpha = 0f; v.scaleX = from; v.scaleY = from
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(XLONG - 60).setInterpolator(emphasized)
            .withLayer().withEndAction { v.scaleX = 1f; v.scaleY = 1f; v.alpha = 1f }.start()
        blurIn(v, 18f, LONG)
    }

    /**
     * Swaps a view's content with a cross-dissolve: a snapshot of the old look fades/zooms out on the parent's overlay
     * while the view (already showing the new content) fades in from 0.97 and sharpens. Used for grid ↔ list.
     */
    fun crossSwap(v: View, change: () -> Unit) {
        val parent = v.parent as? ViewGroup
        if (!enabled(v.context) || parent == null || v.width == 0 || v.height == 0) { change(); return }
        val snap = try {
            Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
        } catch (_: Throwable) { null }
        change()
        if (snap == null) return
        val d = BitmapDrawable(v.resources, snap)
        val l = v.left; val t = v.top
        d.setBounds(l, t, l + v.width, t + v.height)
        parent.overlay.add(d)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = LONG; interpolator = emphasized
            addUpdateListener {
                val f = it.animatedFraction
                d.alpha = ((1f - f) * 255).toInt().coerceIn(0, 255)
                val s = 1f + 0.04f * f
                val w = v.width * s; val h = v.height * s
                val cx = l + v.width / 2f; val cy = t + v.height / 2f
                d.setBounds((cx - w / 2).toInt(), (cy - h / 2).toInt(), (cx + w / 2).toInt(), (cy + h / 2).toInt())
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { parent.overlay.remove(d); snap.recycle() }
            })
            start()
        }
        v.alpha = 0f; v.scaleX = 0.97f; v.scaleY = 0.97f
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(40).setDuration(LONG).setInterpolator(emphasized).start()
        blurIn(v, 12f, LONG, 40)
    }

    /** Icon morph for toolbar/menu icons that have no path-compatible vector: spin-shrink out, swap, spring in. */
    fun iconSwap(v: View?, swap: () -> Unit) {
        if (v == null || !enabled(v.context)) { swap(); return }
        v.animate().cancel()
        v.animate().rotation(-90f).scaleX(0.5f).scaleY(0.5f).alpha(0f).setDuration(MICRO + 30).setInterpolator(emphasizedAccel)
            .withEndAction {
                swap()
                v.rotation = 90f
                v.animate().rotation(0f).scaleX(1f).scaleY(1f).alpha(1f).setDuration(LONG + 80).setInterpolator(spring).start()
            }.start()
    }

    /** Staggered entrance for a group of views (fade + lift + slight scale), e.g. menu icons after search closes. */
    fun stagger(views: List<View?>, fromYdp: Float = 8f, baseDelay: Long = 0, fromScale: Float = 0.8f) {
        views.filterNotNull().forEachIndexed { i, v ->
            if (!enabled(v.context)) { v.alpha = 1f; v.translationY = 0f; v.scaleX = 1f; v.scaleY = 1f; return@forEachIndexed }
            v.animate().cancel()
            v.alpha = 0f; v.translationY = fromYdp * v.resources.displayMetrics.density; v.scaleX = fromScale; v.scaleY = fromScale
            v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(baseDelay + i * (STAGGER + 14))
                .setDuration(LONG + 60).setInterpolator(spring).start()
        }
    }

    /** "Depth pulse": the surroundings dip back for a moment (scale + dim) when something takes focus, then settle. */
    fun depthPulse(v: View, scale: Float = 0.975f, dim: Float = 0.7f) {
        if (!enabled(v.context)) return
        v.animate().cancel()
        v.animate().scaleX(scale).scaleY(scale).alpha(dim).setStartDelay(0).setDuration(SHORT + 40).setInterpolator(emphasized)
            .withEndAction { v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(LONG + 120).setInterpolator(springSoft).start() }.start()
    }

    /**
     * A brief accent light sweeping across [v] (drawn on its overlay, nothing is laid out).
     * v2.16 fix: the old sweep drew an unclipped rotated rectangle, so the light spilled past the pill's rounded ends
     * as a square block, rotated around (0,0) so its height drifted, and a second sweep started on top of a running one
     * (rapid open/close) doubled the brightness. Now: clipped to the view's rounded shape, one sweep per view at a time,
     * soft fade in/out, bounds follow size changes.
     */
    fun lightSweep(v: View, color: Int, duration: Long = 640L, delay: Long = 0L, radius: Float = -1f) {
        if (!enabled(v.context) || v.width == 0) return
        (v.getTag(R.id.tag_light_sweep) as? ValueAnimator)?.cancel()
        val d = SweepDrawable(color, radius)
        d.setBounds(0, 0, v.width, v.height)
        v.overlay.add(d)
        val sizeL = View.OnLayoutChangeListener { _, l, t, r, b, _, _, _, _ -> d.setBounds(0, 0, r - l, b - t) }
        v.addOnLayoutChangeListener(sizeL)
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration; startDelay = delay; interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener { d.pos = it.animatedValue as Float; d.invalidateSelf() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    v.overlay.remove(d); v.removeOnLayoutChangeListener(sizeL)
                    if (v.getTag(R.id.tag_light_sweep) === animation) v.setTag(R.id.tag_light_sweep, null)
                }
            })
        }
        v.setTag(R.id.tag_light_sweep, anim)
        anim.start()
    }

    private class SweepDrawable(private val color: Int, private val radius: Float) : Drawable() {
        var pos = 0f
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val m = Matrix()
        private val clip = android.graphics.Path()
        private val rect = android.graphics.RectF()
        private var shader: LinearGradient? = null
        private var band = 0f
        override fun onBoundsChange(b: android.graphics.Rect) {
            rect.set(b); val r = if (radius >= 0) radius else b.height() / 2f
            clip.reset(); clip.addRoundRect(rect, r, r, android.graphics.Path.Direction.CW)
            band = b.width() * 0.45f
            val c0 = color and 0x00FFFFFF
            shader = LinearGradient(0f, 0f, band, 0f, intArrayOf(c0, c0 or 0x26000000, 0x40FFFFFF, c0 or 0x26000000, c0),
                floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f), Shader.TileMode.CLAMP)
        }
        override fun draw(c: Canvas) {
            val b = bounds; val sh = shader ?: return; if (b.width() <= 0) return
            // Fade in over the first fifth and out over the last fifth: no hard start/stop.
            val fade = (minOf(pos, 1f - pos) * 5f).coerceIn(0f, 1f)
            val x = b.left - band + (b.width() + band) * pos
            m.reset(); m.setSkew(-0.35f, 0f); m.postTranslate(x + b.height() * 0.35f * 0.5f, b.top.toFloat())
            sh.setLocalMatrix(m); p.shader = sh; p.alpha = (255 * fade).toInt()
            val save = c.save(); c.clipPath(clip); c.drawRect(b, p)
            // Edge light: the rim of the glass catches the sweep.
            edge.shader = sh; edge.alpha = (200 * fade).toInt(); edge.strokeWidth = b.height() * 0.04f
            c.drawPath(clip, edge)
            c.restoreToCount(save)
        }
        override fun setAlpha(alpha: Int) { p.alpha = alpha }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) { p.colorFilter = cf }
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}

/**
 * Damped-spring timing curve (critically/under-damped harmonic oscillator normalised to end at 1).
 * [damping] < 1 overshoots a little; [frequency] = oscillations over the animation.
 */
class SpringInterpolator(private val damping: Float = 0.75f, private val frequency: Float = 1.8f) : TimeInterpolator {
    override fun getInterpolation(t: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        val w0 = 2.0 * PI * frequency
        val z = damping.toDouble()
        val x = t.toDouble() * 1.6 // the spring has settled well before the end
        return if (z < 1.0) {
            val wd = w0 * sqrt(1 - z * z)
            (1 - exp(-z * w0 * x) * (cos(wd * x) + (z * w0 / wd) * sin(wd * x))).toFloat()
        } else {
            (1 - exp(-w0 * x) * (1 + w0 * x)).toFloat()
        }
    }
}

/** Unused-import guard for older compilers (keeps ObjectAnimator/PropertyValuesHolder available to callers). */
internal object MotionKit {
    fun lift(v: View, dy: Float, scale: Float): ObjectAnimator = ObjectAnimator.ofPropertyValuesHolder(v,
        PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, dy, 0f),
        PropertyValuesHolder.ofFloat(View.SCALE_X, scale, 1f),
        PropertyValuesHolder.ofFloat(View.SCALE_Y, scale, 1f))
}
