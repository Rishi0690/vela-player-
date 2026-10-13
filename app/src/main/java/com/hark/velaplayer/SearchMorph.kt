package com.hark.velaplayer

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.view.MenuItem
import android.view.View
import android.view.ViewAnimationUtils
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import kotlin.math.hypot
import kotlin.math.max

/**
 * v2.12 home search "morph" — the signature interaction.
 *
 * Open: the search icon's position becomes the centre of a circular reveal that grows the glass search field out of
 * it; the toolbar's back arrow spins in (search → back morph); a short accent light sweeps across the field; the
 * library behind dips back for a moment (scale + dim) and the pill navigation dims while searching.
 * Results then update through DiffUtil + [VelaItemAnimator] (staggered rise, no layout jumps).
 * Close (back arrow, system Back, or clearing): exactly the reverse — the field shrinks back into the icon, the
 * surroundings come forward, and the toolbar icons spring back in one after another.
 *
 * Only visuals: the SearchView, its query listener and the toolbar item order are unchanged. Reduced motion → instant.
 */
object SearchMorph {
    fun install(a: Activity, toolbar: Toolbar, item: MenuItem, sv: SearchView, depth: List<View>, dim: List<View>) {
        style(a, sv)
        val iconCenter = FloatArray(2) { -1f }
        var closing = false
        val dp = a.resources.displayMetrics.density

        item.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(mi: MenuItem): Boolean {
                // Remember where the icon was (in window coordinates) before the toolbar swaps it for the field.
                toolbar.findViewById<View>(R.id.action_search)?.let { v ->
                    val loc = IntArray(2); v.getLocationInWindow(loc)
                    iconCenter[0] = loc[0] + v.width / 2f; iconCenter[1] = loc[1] + v.height / 2f
                }
                if (!Motion.enabled(a)) return true
                sv.alpha = 0f
                sv.post {
                    if (!sv.isAttachedToWindow) { sv.alpha = 1f; return@post }
                    val (cx, cy) = localCenter(sv, iconCenter)
                    val end = max(hypot(cx, cy), hypot(sv.width - cx, cy)).coerceAtLeast(sv.height.toFloat())
                    sv.alpha = 1f
                    runCatching {
                        ViewAnimationUtils.createCircularReveal(sv, cx.toInt(), cy.toInt(), 0f, end).apply {
                            duration = Motion.LONG + 60; interpolator = Motion.decelerate; start()
                        }
                    }
                    sv.scaleX = 0.96f
                    sv.animate().scaleX(1f).setDuration(Motion.XLONG).setInterpolator(Motion.springSoft).start()
                    Motion.lightSweep(sv, ThemeManager.primary(a), 720L, 140L)
                    collapseButton(toolbar)?.let { b ->
                        b.rotation = -180f; b.alpha = 0f
                        b.animate().rotation(0f).alpha(1f).setDuration(Motion.LONG + 120).setInterpolator(Motion.spring).start()
                    }
                }
                depth.forEach { Motion.depthPulse(it, 0.97f, 0.6f) }
                dim.forEach { it.animate().alpha(0.55f).setDuration(Motion.MEDIUM).setInterpolator(Motion.emphasized).start() }
                return true
            }

            override fun onMenuItemActionCollapse(mi: MenuItem): Boolean {
                if (closing || !Motion.enabled(a) || !sv.isAttachedToWindow || sv.width == 0) {
                    restore(dim, depth, toolbar)
                    return true
                }
                closing = true
                val (cx, cy) = localCenter(sv, iconCenter)
                val start = max(hypot(cx, cy), hypot(sv.width - cx, cy)).coerceAtLeast(sv.height.toFloat())
                val anim = runCatching { ViewAnimationUtils.createCircularReveal(sv, cx.toInt(), cy.toInt(), start, 18 * dp) }.getOrNull()
                if (anim == null) { closing = false; restore(dim, depth, toolbar); return true }
                anim.duration = Motion.MEDIUM + 20
                anim.interpolator = Motion.emphasizedAccel
                anim.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        mi.collapseActionView()
                        sv.alpha = 1f
                        closing = false
                    }
                })
                sv.animate().alpha(0f).setStartDelay(Motion.SHORT).setDuration(Motion.MICRO).start()
                collapseButton(toolbar)?.animate()?.rotation(180f)?.alpha(0f)?.setDuration(Motion.MEDIUM)?.setInterpolator(Motion.emphasizedAccel)?.start()
                anim.start()
                return false
            }
        })
    }

    private fun restore(dim: List<View>, depth: List<View>, toolbar: Toolbar) {
        dim.forEach { it.animate().alpha(1f).setDuration(Motion.LONG).setInterpolator(Motion.emphasized).start() }
        depth.forEach { if (Motion.enabled(it.context)) Motion.depthPulse(it, 1.012f, 0.85f) }
        // Toolbar icons come back one after another (theme, search, grid/list, sort, overflow — order unchanged).
        toolbar.post {
            val ids = listOf(R.id.action_theme, R.id.action_search, R.id.action_view, R.id.action_sort)
            val views = ids.map { toolbar.findViewById<View>(it) } + overflowButton(toolbar)
            Motion.stagger(views, 6f)
            collapseButton(toolbar)?.let { it.rotation = 0f; it.alpha = 1f }
        }
    }

    private fun localCenter(sv: View, win: FloatArray): Pair<Float, Float> {
        val loc = IntArray(2); sv.getLocationInWindow(loc)
        val cx = if (win[0] >= 0) (win[0] - loc[0]).coerceIn(0f, sv.width.toFloat()) else sv.width - 24f
        val cy = sv.height / 2f
        return cx to cy
    }

    /** The toolbar's "collapse" (back) button shown while an action view is expanded. */
    private fun collapseButton(t: Toolbar): View? =
        (0 until t.childCount).map { t.getChildAt(it) }.firstOrNull { it is ImageButton && it.contentDescription?.toString()?.contains("ollapse") == true }
            ?: (0 until t.childCount).map { t.getChildAt(it) }.firstOrNull { it is ImageButton }

    private fun overflowButton(t: Toolbar): View? {
        val menuView = (0 until t.childCount).map { t.getChildAt(it) }.firstOrNull { it is androidx.appcompat.widget.ActionMenuView } as? android.view.ViewGroup ?: return null
        return (0 until menuView.childCount).map { menuView.getChildAt(it) }.lastOrNull { it is ImageView }
    }

    /** Glass pill field: transparent plates, the pill drawable behind, readable text/hint, accent cursor handles. */
    private fun style(a: Activity, sv: SearchView) {
        sv.maxWidth = Int.MAX_VALUE
        sv.background = ContextCompat.getDrawable(a, R.drawable.bg_search_field)
        sv.findViewById<View>(androidx.appcompat.R.id.search_plate)?.background = null
        sv.findViewById<View>(androidx.appcompat.R.id.submit_area)?.background = null
        sv.findViewById<EditText>(androidx.appcompat.R.id.search_src_text)?.let { e ->
            e.setTextColor(ThemeManager.attr(a, com.google.android.material.R.attr.colorOnSurface))
            e.setHintTextColor(ThemeManager.attr(a, com.google.android.material.R.attr.colorOnSurfaceVariant))
            e.textSize = 15f
        }
        sv.findViewById<ImageView>(androidx.appcompat.R.id.search_mag_icon)?.let {
            it.imageTintList = android.content.res.ColorStateList.valueOf(ThemeManager.primary(a))
        }
        val pad = (4 * a.resources.displayMetrics.density).toInt()
        sv.setPadding(pad * 2, 0, pad, 0)
    }
}
