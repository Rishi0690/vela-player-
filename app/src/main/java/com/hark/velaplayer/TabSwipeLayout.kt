package com.hark.velaplayer

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * v2.2 home tabs: horizontal swipe over the library list to go to the next / previous tab.
 *
 * - Only takes over a gesture that is clearly horizontal (past touch slop and at least 1.6× more horizontal than
 *   vertical). Once the RecyclerView starts scrolling vertically it disallows interception, so vertical scrolling
 *   and pull-to-refresh are never stolen.
 * - Ignores touches that start on something that scrolls sideways itself (Continue watching strip, filter chips)
 *   and touches that start in the system back-gesture edge zones.
 * - The content follows the finger; on release it either springs back or slides out, the host switches tab
 *   (keeping that tab's own state and scroll), and the new content slides in from the other side.
 */
class TabSwipeLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    interface Host {
        /** -1 = previous tab exists, +1 = next exists; return false when there is no tab in that direction or swiping is off. */
        fun canSwipeTo(direction: Int): Boolean
        /** Switch to the neighbour tab now (content is off-screen at this moment). */
        fun onSwipeTo(direction: Int)
    }

    var host: Host? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 6
    private val edge = 24 * resources.displayMetrics.density
    private var downX = 0f; private var downY = 0f
    private var dragging = false
    private var blocked = false
    private var vt: VelocityTracker? = null
    private var animating = false

    /** The view that moves (the list container); defaults to the first child. */
    private val target: View? get() = if (childCount > 0) getChildAt(0) else null

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (animating) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; dragging = false
                blocked = host == null || e.rawX < edge || e.rawX > resources.displayMetrics.widthPixels - edge ||
                    startsOnHorizontalScroller(this, e.x, e.y)
                vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(e)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                if (!blocked && !dragging && e.pointerCount == 1) {
                    val dx = e.x - downX; val dy = e.y - downY
                    if (abs(dx) > slop * 1.5f && abs(dx) > abs(dy) * 1.6f) {
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        downX = e.x // start following from here so the content doesn't jump by the slop
                        return true
                    }
                    if (abs(dy) > slop) blocked = true // vertical gesture: leave it alone for good
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { vt?.recycle(); vt = null }
        }
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (animating) return true
        val t = target ?: return false
        vt?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Nobody below took the touch (e.g. empty area): we can still decide on a swipe.
                downX = e.x; downY = e.y; dragging = false; blocked = host == null || e.rawX < edge || e.rawX > resources.displayMetrics.widthPixels - edge
                if (vt == null) { vt = VelocityTracker.obtain(); vt?.addMovement(e) }
                return !blocked
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    val dx = e.x - downX; val dy = e.y - downY
                    if (!blocked && abs(dx) > slop * 1.5f && abs(dx) > abs(dy) * 1.6f) { dragging = true; downX = e.x; parent?.requestDisallowInterceptTouchEvent(true) }
                    else return true
                }
                val dx = e.x - downX
                val dir = if (dx < 0) 1 else -1
                // Rubber band when there is no tab that way.
                val shown = if (host?.canSwipeTo(dir) == true) dx else dx * 0.25f
                t.translationX = shown
                t.alpha = 1f - (abs(shown) / width.coerceAtLeast(1)) * 0.5f
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    vt?.computeCurrentVelocity(1000)
                    val vx = vt?.xVelocity ?: 0f
                    val dx = e.x - downX
                    val dir = if (dx < 0) 1 else -1
                    val far = abs(dx) > width * 0.28f
                    val fling = abs(vx) > minFling && (vx < 0) == (dx < 0) && abs(dx) > slop * 2
                    if (e.actionMasked == MotionEvent.ACTION_UP && (far || fling) && host?.canSwipeTo(dir) == true) commit(dir) else settle()
                }
                dragging = false
                vt?.recycle(); vt = null
            }
        }
        return true
    }

    private fun settle() {
        val t = target ?: return
        t.animate().translationX(0f).alpha(1f).setDuration(220).setInterpolator(Motion.emphasized).setListener(null).start()
    }

    /** Slide the current content out towards [dir], switch, slide the new content in from the other side. */
    fun commit(dir: Int) {
        val t = target ?: return
        animating = true
        val w = width.toFloat().coerceAtLeast(1f)
        t.animate().translationX(-dir * w * 0.45f).alpha(0f).setDuration(140).setInterpolator(android.view.animation.AccelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    t.animate().setListener(null)
                    host?.onSwipeTo(dir)
                    slideIn(dir)
                }
            }).start()
    }

    /** New content enters from the side of travel (also used for tab taps). */
    fun slideIn(dir: Int) {
        val t = target ?: return
        animating = true
        t.translationX = dir * width * 0.35f
        t.alpha = 0f
        t.animate().translationX(0f).alpha(1f).setDuration(260).setInterpolator(Motion.emphasized)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { t.animate().setListener(null); animating = false }
                override fun onAnimationCancel(a: Animator) { t.translationX = 0f; t.alpha = 1f; animating = false }
            }).start()
    }

    private fun startsOnHorizontalScroller(v: View, x: Float, y: Float): Boolean {
        if (v !== this && v.isShown && (v.canScrollHorizontally(1) || v.canScrollHorizontally(-1))) return true
        if (v !is ViewGroup) return false
        for (i in v.childCount - 1 downTo 0) {
            val c = v.getChildAt(i)
            if (!c.isShown) continue
            val lx = x - c.left + v.scrollX - c.translationX
            val ly = y - c.top + v.scrollY - c.translationY
            if (lx >= 0 && ly >= 0 && lx < c.width && ly < c.height && startsOnHorizontalScroller(c, lx, ly)) return true
        }
        return false
    }
}
