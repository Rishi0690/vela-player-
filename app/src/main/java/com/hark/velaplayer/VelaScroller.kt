package com.hark.velaplayer

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * v2.8 slim, draggable scroll indicator for RecyclerViews.
 *
 * Real, not decorative: the thumb is computed every frame from the list's own computeVerticalScrollOffset /
 * Range / Extent, so it can't lag or drift. It shows while the list moves, fades out ~1.2 s after it stops,
 * and stays hidden when everything fits on screen. Dragging the thumb (touch area 28dp wide, visual 4dp) scrolls the
 * list proportionally. It sits on the end edge (left edge in RTL) and only grabs touches that start on the
 * visible thumb, so taps on rows, thumbnails and buttons are never stolen. Colors follow the theme accent.
 * Each RecyclerView gets its own instance, so nested lists track their own container.
 */
class VelaScroller private constructor(private val rv: RecyclerView) : RecyclerView.ItemDecoration(), RecyclerView.OnItemTouchListener {

    companion object {
        /** Attaches once; calling again on the same list is a no-op. Disables the native bar to avoid doubles. */
        fun attach(rv: RecyclerView): VelaScroller {
            (rv.getTag(R.id.vela_scroller) as? VelaScroller)?.let { return it }
            rv.isVerticalScrollBarEnabled = false
            return VelaScroller(rv).also { s ->
                rv.setTag(R.id.vela_scroller, s)
                rv.addItemDecoration(s)
                rv.addOnItemTouchListener(s)
                rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) { if (dy != 0) s.wake() }
                    override fun onScrollStateChanged(r: RecyclerView, state: Int) {
                        if (state == RecyclerView.SCROLL_STATE_IDLE && !s.dragging) s.scheduleHide()
                    }
                })
            }
        }
        /** Ends a thumb drag that never received its finger-up (the list was hidden mid-drag). */
        fun reset(rv: RecyclerView) {
            (rv.getTag(R.id.vela_scroller) as? VelaScroller)?.let { s -> if (s.dragging) { s.dragging = false; s.scheduleHide() } }
        }
    }

    private val d = rv.resources.displayMetrics.density
    private val thick = 4 * d
    private val thickActive = 7 * d
    private val margin = 3 * d
    private val minThumb = 40 * d
    private val touchW = 28 * d
    private val accent = ThemeManager.primary(rv.context)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var alpha = 0f
    private var fade: ValueAnimator? = null
    private var dragging = false
    private var dragOffset = 0f
    private var showing = false
    private val hide = Runnable { showing = false; animateTo(0f, 320) }

    private val reduced get() = !Motion.enabled(rv.context)

    private fun wake() {
        if (!scrollable()) return
        rv.removeCallbacks(hide)
        if (!showing) { showing = true; animateTo(1f, 120) } else rv.invalidate()
        if (rv.scrollState == RecyclerView.SCROLL_STATE_IDLE && !dragging) scheduleHide()
    }

    private fun scheduleHide() { rv.removeCallbacks(hide); rv.postDelayed(hide, 1200) }

    private fun animateTo(target: Float, dur: Long) {
        fade?.cancel()
        if (reduced) { alpha = target; rv.invalidate(); return }
        fade = ValueAnimator.ofFloat(alpha, target).apply {
            duration = dur
            interpolator = Motion.emphasized
            addUpdateListener { alpha = it.animatedValue as Float; rv.invalidate() }
            start()
        }
    }

    private fun range() = rv.computeVerticalScrollRange()
    private fun extent() = rv.computeVerticalScrollExtent()
    private fun offset() = rv.computeVerticalScrollOffset()
    private fun scrollable() = range() - extent() > 0 && rv.height > 0

    private fun rtl() = ViewCompat.getLayoutDirection(rv) == ViewCompat.LAYOUT_DIRECTION_RTL

    /** Track spans the padded content area so the thumb never hides under a toolbar or a bottom FAB inset. */
    private fun trackTop() = rv.paddingTop.toFloat() + margin
    private fun trackBottom() = rv.height - max(rv.paddingBottom.toFloat() * 0.5f, margin)

    private fun thumbLen(): Float {
        val track = trackBottom() - trackTop()
        return max(minThumb, track * extent() / max(1, range()).toFloat()).coerceAtMost(track)
    }

    private fun thumbTop(): Float {
        val track = trackBottom() - trackTop()
        val maxOff = (range() - extent()).coerceAtLeast(1)
        val f = (offset().toFloat() / maxOff).coerceIn(0f, 1f)
        return trackTop() + (track - thumbLen()) * f
    }

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (alpha <= 0.01f || !scrollable()) return
        val w = if (dragging) thickActive else thick
        val x = if (rtl()) margin else rv.width - margin - w
        val top = thumbTop(); val len = thumbLen()
        if (dragging) {
            trackPaint.color = accent; trackPaint.alpha = (alpha * 36).roundToInt()
            rect.set(x, trackTop(), x + w, trackBottom()); c.drawRoundRect(rect, w / 2, w / 2, trackPaint)
        }
        thumbPaint.color = accent
        thumbPaint.alpha = (alpha * (if (dragging) 255 else 170)).roundToInt()
        rect.set(x, top, x + w, top + len)
        c.drawRoundRect(rect, w / 2, w / 2, thumbPaint)
    }

    private fun onThumb(e: MotionEvent): Boolean {
        if (alpha < 0.5f || !scrollable()) return false
        val inX = if (rtl()) e.x <= touchW else e.x >= rv.width - touchW
        val top = thumbTop()
        return inX && e.y >= top - 12 * d && e.y <= top + thumbLen() + 12 * d
    }

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        // v2.18.3: a new finger-down always starts fresh; a drag left over from a lost finger-up never captures it.
        if (e.actionMasked == MotionEvent.ACTION_DOWN && dragging) { dragging = false; rv.parent?.requestDisallowInterceptTouchEvent(false) }
        if (e.actionMasked == MotionEvent.ACTION_DOWN && onThumb(e)) {
            dragging = true
            dragOffset = e.y - thumbTop()
            rv.removeCallbacks(hide)
            rv.stopScroll()
            rv.parent?.requestDisallowInterceptTouchEvent(true)
            Motion.haptic(rv)
            rv.invalidate()
            return true
        }
        return dragging
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        if (!dragging) return
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val track = trackBottom() - trackTop() - thumbLen()
                if (track <= 0) return
                val f = ((e.y - dragOffset - trackTop()) / track).coerceIn(0f, 1f)
                val target = (f * (range() - extent())).roundToInt()
                val dy = target - offset()
                // Large jumps (fast drags through long libraries) go straight to the position instead of
                // laying out every row in between.
                if (kotlin.math.abs(dy) > rv.height * 3) jumpTo(f) else if (dy != 0) rv.scrollBy(0, dy)
                rv.invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                rv.parent?.requestDisallowInterceptTouchEvent(false)
                rv.invalidate()
                scheduleHide()
            }
        }
    }

    private fun jumpTo(f: Float) {
        val n = rv.adapter?.itemCount ?: return
        if (n == 0) return
        val pos = min(n - 1, (f * n).toInt())
        (rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)?.scrollToPositionWithOffset(pos, 0)
            ?: rv.scrollToPosition(pos)
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
}
