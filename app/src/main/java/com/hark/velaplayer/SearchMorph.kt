package com.hark.velaplayer

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.OneShotPreDrawListener
import kotlin.math.max

/**
 * Toolbar search "morph" (v2.12 → v2.19 full-width expand).
 *
 * v2.19 root cause of the tiny field: the search item is a `collapseActionView` action view. When it expands, the
 * Toolbar hides the title/custom views but keeps the ActionMenuView (theme, grid/list, sort, overflow: all
 * `showAsAction="always"` on Home, `ifRoom` in the Vault) laid out, and measures the expanded SearchView only in the
 * width those icons leave over (plus `contentInsetStartWithNavigation` / `contentInsetEndWithActions`). On a phone
 * that is ~100 dp. Now, while — and only while — the field is expanded, the ActionMenuView is GONE and those insets
 * are 0, so the field fills the toolbar from the back arrow to the end edge. Every item comes back, in the same order,
 * the moment the field is removed (normal close, a menu rebuild, or the activity going away): the restore is tied to
 * the SearchView detaching from the toolbar, so it can never be skipped. Nothing is removed from the menu.
 *
 * Motion (all [Motion] curves; reduced motion → every step jumps to its end state):
 * - Open: the toolbar's current look (title + icons) is snapshotted and dissolves on the toolbar overlay while a glass
 *   pill stretches out of the search icon's spot to the full width ([Motion.emphasized], GPU only: the pill is drawn
 *   at an animated rect and the field is clipped to the same rounded outline — no per-frame layout). The field's
 *   contents fade/blur in just behind the surface, the back arrow turns in, one accent light sweep, the library dips
 *   back (depth pulse) and the pill navigation dims. The keyboard comes up with the focus as before.
 * - Focus: a gentle accent rim + a centred underline glow grow in (and fade on blur); the search glyph lifts.
 * - Close (back arrow, system Back, programmatic): keyboard goes down at once, contents fade, the pill shrinks back
 *   into the icon's spot ([Motion.emphasizedAccel]), then the collapse completes and the title + icons rise back in.
 * Robust to: Back during the opening (reverses from where it is), repeated Back while closing (ignored), rotation
 * (the anchor is kept relative to the end edge, the pill follows the new size), menu rebuilds (restore on detach),
 * and the activity being destroyed (animators cancelled, snapshot bitmap recycled).
 */
object SearchMorph {

    fun install(a: Activity, toolbar: Toolbar, item: MenuItem, sv: SearchView, depth: List<View>, dim: List<View>) {
        (sv.getTag(R.id.tag_search_morph) as? Morph)?.dispose()
        val m = Morph(a, toolbar, item, sv, depth, dim)
        sv.setTag(R.id.tag_search_morph, m)
        m.install()
    }

    /** Expands the field with no animation (restoring a query after a menu rebuild / recreate). */
    fun expandInstant(item: MenuItem) {
        (item.actionView?.getTag(R.id.tag_search_morph) as? Morph)?.instantNext = true
        item.expandActionView()
    }

    private enum class State { COLLAPSED, OPENING, OPEN, CLOSING }

    private class Morph(
        val a: Activity, val toolbar: Toolbar, val item: MenuItem, val sv: SearchView,
        val depth: List<View>, val dim: List<View>,
    ) {
        private val dp = a.resources.displayMetrics.density
        private val accent = ThemeManager.primary(a)
        private val pill = PillDrawable(
            (ContextCompat.getDrawable(a, R.drawable.bg_search_field) ?: android.graphics.drawable.ColorDrawable(0)).mutate(), accent, dp)
        private var state = State.COLLAPSED
        var instantNext = false
        private var allowCollapse = false
        private var dimmed = false
        private var disposed = false
        /** Search icon centre, measured from the toolbar's end edge (stable across rotation). */
        private var anchorFromEnd = -1f
        private var pillAnim: ValueAnimator? = null
        private var glowAnim: ValueAnimator? = null
        private var snapAnim: ValueAnimator? = null
        private var snap: BitmapDrawable? = null
        private var savedInsets: IntArray? = null
        private var openedAt = 0L
        private val outlineRect = RectF()

        private val inner: View? get() = if (sv.childCount > 0) sv.getChildAt(0) else null
        private val rtl get() = toolbar.layoutDirection == View.LAYOUT_DIRECTION_RTL

        private val attachL = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { if (!disposed && sv.parent === toolbar) hideToolbarItems() }
            override fun onViewDetachedFromWindow(v: View) { if (!disposed) onRemoved() }
        }
        private val sizeL = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sv.invalidateOutline() }

        fun install() {
            style()
            alive()
            sv.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    if (view.width == 0 || view.height == 0) { outline.setEmpty(); return }
                    pill.current(outlineRect)
                    outline.setRoundRect(outlineRect.left.toInt(), outlineRect.top.toInt(), outlineRect.right.toInt(),
                        outlineRect.bottom.toInt(), outlineRect.height() / 2f)
                }
            }
            sv.clipToOutline = true
            sv.addOnAttachStateChangeListener(attachL)
            sv.addOnLayoutChangeListener(sizeL)
            item.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
                override fun onMenuItemActionExpand(mi: MenuItem) = onExpand()
                override fun onMenuItemActionCollapse(mi: MenuItem) = onCollapse(mi)
            })
            // Installed onto a field that is already showing (menu rebuilt while searching): adopt it as open.
            if (sv.isAttachedToWindow && sv.parent === toolbar) { state = State.OPEN; hideToolbarItems() }
        }

        fun dispose() {
            disposed = true
            pillAnim?.cancel(); glowAnim?.cancel(); removeSnapshot()
            sv.removeOnAttachStateChangeListener(attachL)
            sv.removeOnLayoutChangeListener(sizeL)
        }

        // ------------------------------------------------------------------ open

        private fun onExpand(): Boolean {
            if (state == State.CLOSING) {
                // Re-opened while the close is still running: turn around from the current width.
                pillAnim?.cancel(); state = State.OPENING; animateOpen(); return false
            }
            val instant = instantNext || !Motion.enabled(a) || !toolbar.isLaidOut || toolbar.width == 0
            instantNext = false
            captureAnchor()
            pillAnim?.cancel()
            if (instant) {
                state = State.OPEN
                pill.progress = 1f; pill.invalidateSelf(); sv.invalidateOutline()
                inner?.let { it.alpha = 1f; it.translationX = 0f }
                setDim(true, animate = false)
                return true
            }
            snapshotToolbar()
            state = State.OPENING
            pill.progress = 0f
            inner?.let { it.animate().cancel(); it.alpha = 0f }
            // Start once the toolbar has laid the field out at its new (full) width, before its first frame.
            OneShotPreDrawListener.add(sv) { if (state == State.OPENING && !disposed) animateOpen() }
            depth.forEach { Motion.depthPulse(it, 0.97f, 0.6f) }
            setDim(true, animate = true)
            return true
        }

        private fun animateOpen() {
            if (sv.width == 0) { finishOpen(); return }
            pill.anchorX = anchorLocal()
            pillAnim = animatePill(1f, Motion.XLONG - 40, Motion.emphasized) { finishOpen() }
            inner?.let { c ->
                c.animate().cancel()
                c.translationX = (if (rtl) -10 else 10) * dp
                c.animate().alpha(1f).translationX(0f).setStartDelay(110).setDuration(Motion.MEDIUM + 80)
                    .setInterpolator(Motion.emphasized).start()
                Motion.blurIn(c, 10f, Motion.LONG, 110)
            }
            collapseButton()?.let { b ->
                b.animate().cancel()
                b.rotation = if (rtl) 90f else -90f; b.alpha = 0f
                b.animate().rotation(0f).alpha(1f).setStartDelay(60).setDuration(Motion.LONG + 40).setInterpolator(Motion.emphasized).start()
            }
            Motion.lightSweep(sv, accent, 720L, 300L)
            openedAt = SystemClock.uptimeMillis()
        }

        private fun finishOpen() {
            state = State.OPEN
            pill.progress = 1f; pill.invalidateSelf(); sv.invalidateOutline()
        }

        // ------------------------------------------------------------------ close

        private fun onCollapse(mi: MenuItem): Boolean {
            if (allowCollapse) { allowCollapse = false; return true }
            if (!mi.isActionViewExpanded) return true
            if (state == State.CLOSING) return false // a second Back while closing: the close is already on its way
            hideIme()
            setDim(false, animate = Motion.enabled(a))
            if (!Motion.enabled(a) || !sv.isAttachedToWindow || sv.width == 0) {
                pillAnim?.cancel(); state = State.COLLAPSED; return true
            }
            state = State.CLOSING
            pillAnim?.cancel()
            removeSnapshot()
            pill.anchorX = anchorLocal()
            inner?.let { c ->
                c.animate().cancel()
                c.animate().alpha(0f).translationX((if (rtl) -6 else 6) * dp).setStartDelay(0).setDuration(Motion.MICRO + 40)
                    .setInterpolator(Motion.emphasizedAccel).start()
            }
            collapseButton()?.let { b ->
                b.animate().cancel()
                b.animate().rotation(if (rtl) 90f else -90f).alpha(0f).setStartDelay(0).setDuration(Motion.MEDIUM)
                    .setInterpolator(Motion.emphasizedAccel).start()
            }
            val dur = (Motion.MEDIUM + 60) * pill.progress.coerceIn(0.35f, 1f)
            pillAnim = animatePill(0f, dur.toLong(), Motion.emphasizedAccel) {
                if (state != State.CLOSING || disposed) return@animatePill
                allowCollapse = true
                val collapsed = runCatching { mi.collapseActionView() }.getOrDefault(false)
                allowCollapse = false
                if (!collapsed && mi.isActionViewExpanded) { // should not happen; never leave a shrunken field behind
                    state = State.OPEN; pill.progress = 1f; pill.invalidateSelf(); sv.invalidateOutline()
                    inner?.alpha = 1f; collapseButton()?.let { it.rotation = 0f; it.alpha = 1f }
                }
            }
            return false
        }

        /** The field left the toolbar (normal close, a menu rebuild, or the screen going away): restore everything. */
        private fun onRemoved() {
            val wasOpen = state != State.COLLAPSED || savedInsets != null
            state = State.COLLAPSED
            pillAnim?.cancel(); pillAnim = null
            glowAnim?.cancel(); pill.glow = 0f
            removeSnapshot()
            pill.progress = 1f
            inner?.let { it.animate().cancel(); it.alpha = 1f; it.translationX = 0f; Motion.clearBlur(it) }
            collapseButton()?.let { it.animate().cancel(); it.rotation = 0f; it.alpha = 1f }
            setDim(false, animate = Motion.enabled(a) && !a.isFinishing)
            showToolbarItems(animate = wasOpen && Motion.enabled(a) && !a.isFinishing && !a.isDestroyed)
        }

        // ------------------------------------------------------------------ toolbar items

        private fun menuView(): ActionMenuView? =
            (0 until toolbar.childCount).map { toolbar.getChildAt(it) }.firstOrNull { it is ActionMenuView } as? ActionMenuView

        private fun hideToolbarItems() {
            menuView()?.visibility = View.GONE
            if (savedInsets == null) savedInsets = intArrayOf(toolbar.contentInsetStartWithNavigation, toolbar.contentInsetEndWithActions)
            toolbar.contentInsetStartWithNavigation = 0
            toolbar.contentInsetEndWithActions = 0
            (sv.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                val end = max(0, (12 * dp).toInt() - toolbar.currentContentInsetEnd)
                if (lp.marginEnd != end) { lp.marginEnd = end; sv.layoutParams = lp }
            }
        }

        private fun showToolbarItems(animate: Boolean) {
            val mv = menuView()
            mv?.visibility = View.VISIBLE
            savedInsets?.let { toolbar.contentInsetStartWithNavigation = it[0]; toolbar.contentInsetEndWithActions = it[1] }
            savedInsets = null
            if (!animate) return
            // Title first, then the icons one after another (order is the menu's own: never changed here).
            toolbar.post {
                if (a.isDestroyed) return@post
                val title = (0 until toolbar.childCount).map { toolbar.getChildAt(it) }
                    .filter { it !is ActionMenuView && it !== sv && it.visibility == View.VISIBLE && it.alpha > 0f && collapseButton() !== it }
                val icons = mv?.let { m -> (0 until m.childCount).map { m.getChildAt(it) }.filter { it.visibility == View.VISIBLE } } ?: emptyList()
                Motion.stagger(title, 4f, fromScale = 0.97f)
                Motion.stagger(icons, 6f, baseDelay = 40)
            }
        }

        private fun captureAnchor() {
            val icon = toolbar.findViewById<View>(item.itemId)
            if (icon == null || !icon.isShown || icon.width == 0 || toolbar.width == 0) return
            val il = IntArray(2); icon.getLocationInWindow(il)
            val tl = IntArray(2); toolbar.getLocationInWindow(tl)
            val cx = il[0] - tl[0] + icon.width / 2f
            anchorFromEnd = if (rtl) cx else toolbar.width - cx
        }

        /** The icon's centre in the field's own coordinates (or -1 → the field's end). */
        private fun anchorLocal(): Float {
            if (anchorFromEnd < 0) return -1f
            val cxToolbar = if (rtl) anchorFromEnd else toolbar.width - anchorFromEnd
            return cxToolbar - sv.left - sv.translationX
        }

        private fun collapseButton(): View? {
            val kids = (0 until toolbar.childCount).map { toolbar.getChildAt(it) }
            return kids.firstOrNull { it is ImageButton && it.contentDescription?.toString()?.contains("ollapse") == true }
        }

        // ------------------------------------------------------------------ pieces

        private fun animatePill(to: Float, duration: Long, interp: android.animation.TimeInterpolator, end: () -> Unit): ValueAnimator =
            ValueAnimator.ofFloat(pill.progress, to).apply {
                this.duration = duration; interpolator = interp
                var cancelled = false
                addUpdateListener { pill.progress = it.animatedValue as Float; pill.invalidateSelf(); sv.invalidateOutline() }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationCancel(animation: Animator) { cancelled = true }
                    override fun onAnimationEnd(animation: Animator) {
                        if (pillAnim === animation) pillAnim = null
                        if (!cancelled) end()
                    }
                })
                start()
            }

        /** The toolbar as it looked (title + icons) dissolves on its overlay while the field takes over. */
        private fun snapshotToolbar() {
            removeSnapshot()
            val w = toolbar.width; val h = toolbar.height
            if (w == 0 || h == 0) return
            val bmp = runCatching {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                    val c = Canvas(b)
                    for (i in 0 until toolbar.childCount) {
                        val ch = toolbar.getChildAt(i)
                        if (ch.visibility != View.VISIBLE || ch === sv) continue
                        val s = c.save(); c.translate(ch.left + ch.translationX, ch.top + ch.translationY); ch.draw(c); c.restoreToCount(s)
                    }
                }
            }.getOrNull() ?: return
            val d = BitmapDrawable(a.resources, bmp).also { it.setBounds(0, 0, w, h) }
            toolbar.overlay.add(d)
            snap = d
            val drift = (if (rtl) -4 else 4) * dp
            snapAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = Motion.SHORT + 40; interpolator = Motion.emphasizedAccel
                addUpdateListener {
                    val f = it.animatedFraction
                    d.alpha = ((1f - f) * 255).toInt().coerceIn(0, 255)
                    val dx = (drift * f).toInt(); d.setBounds(dx, 0, w + dx, h)
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { if (snapAnim === animation) removeSnapshot() }
                })
                start()
            }
        }

        private fun removeSnapshot() {
            val anim = snapAnim; snapAnim = null; anim?.cancel()
            snap?.let { d -> toolbar.overlay.remove(d); d.bitmap?.let { if (!it.isRecycled) it.recycle() } }
            snap = null
        }

        private fun setDim(on: Boolean, animate: Boolean) {
            if (on == dimmed) return
            dimmed = on
            val to = if (on) 0.55f else 1f
            dim.forEach { v ->
                v.animate().cancel()
                if (animate) v.animate().alpha(to).setStartDelay(0).setDuration(if (on) Motion.MEDIUM else Motion.LONG).setInterpolator(Motion.emphasized).start()
                else v.alpha = to
            }
            if (!on && animate) depth.forEach { Motion.depthPulse(it, 1.012f, 0.85f) }
        }

        private fun hideIme() {
            val e = sv.findViewById<EditText>(androidx.appcompat.R.id.search_src_text)
            runCatching { (a.getSystemService(Activity.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(sv.windowToken, 0) }
            e?.clearFocus()
        }

        private fun animateGlow(on: Boolean) {
            glowAnim?.cancel()
            val to = if (on) 1f else 0f
            if (!Motion.enabled(a)) { pill.glow = to; pill.invalidateSelf(); return }
            glowAnim = ValueAnimator.ofFloat(pill.glow, to).apply {
                duration = if (on) Motion.LONG + 80 else Motion.MEDIUM
                interpolator = if (on) Motion.emphasized else Motion.emphasizedAccel
                addUpdateListener { pill.glow = it.animatedValue as Float; pill.invalidateSelf() }
                start()
            }
        }

        /**
         * Micro-interactions. Focus: accent rim + underline glow grow in (fade on blur) and the search glyph lifts; a
         * light sweep only if the field was opened a while ago (opening already swept). Typing: the clear button pops in
         * when the first character arrives — no replaying of the opening animation.
         */
        private fun alive() {
            val e = sv.findViewById<EditText>(androidx.appcompat.R.id.search_src_text) ?: return
            val mag = sv.findViewById<ImageView>(androidx.appcompat.R.id.search_mag_icon)
            val close = sv.findViewById<ImageView>(androidx.appcompat.R.id.search_close_btn)
            close?.imageTintList = android.content.res.ColorStateList.valueOf(ThemeManager.attr(a, com.google.android.material.R.attr.colorOnSurfaceVariant))
            e.highlightColor = Atmosphere.alpha(accent, 0x55)
            if (Build.VERSION.SDK_INT >= 29) runCatching { e.textCursorDrawable?.mutate()?.let { d -> d.setTint(accent); e.textCursorDrawable = d } }
            // SearchView forwards its text field's focus here (overriding the field's own listener would break that).
            sv.setOnQueryTextFocusChangeListener { _, has ->
                animateGlow(has)
                if (!Motion.enabled(a)) return@setOnQueryTextFocusChangeListener
                mag?.animate()?.scaleX(if (has) 1.12f else 1f)?.scaleY(if (has) 1.12f else 1f)?.setDuration(Motion.MEDIUM)?.setInterpolator(Motion.spring)?.start()
                if (has && state == State.OPEN && SystemClock.uptimeMillis() - openedAt > 1200) Motion.lightSweep(sv, accent, 620L)
            }
            var hadText = false
            e.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val has = !s.isNullOrEmpty()
                    if (has == hadText) return
                    hadText = has
                    if (!Motion.enabled(a) || close == null) return
                    close.post {
                        if (has) { close.scaleX = 0.4f; close.scaleY = 0.4f; close.rotation = -90f
                            close.animate().scaleX(1f).scaleY(1f).rotation(0f).setDuration(Motion.MEDIUM).setInterpolator(Motion.spring).start() }
                    }
                }
            })
        }

        /** Glass pill field: transparent plates, the animated pill behind, readable text/hint, accent glyph. */
        private fun style() {
            sv.maxWidth = Int.MAX_VALUE
            sv.background = pill
            sv.findViewById<View>(androidx.appcompat.R.id.search_plate)?.background = null
            sv.findViewById<View>(androidx.appcompat.R.id.submit_area)?.background = null
            sv.findViewById<EditText>(androidx.appcompat.R.id.search_src_text)?.let { e ->
                e.setTextColor(ThemeManager.attr(a, com.google.android.material.R.attr.colorOnSurface))
                e.setHintTextColor(ThemeManager.attr(a, com.google.android.material.R.attr.colorOnSurfaceVariant))
                e.textSize = 15.5f
                e.letterSpacing = 0.01f
            }
            sv.findViewById<ImageView>(androidx.appcompat.R.id.search_mag_icon)?.imageTintList =
                android.content.res.ColorStateList.valueOf(accent)
            val pad = (4 * dp).toInt()
            sv.setPaddingRelative(pad * 2, 0, pad, 0)
        }
    }

    /**
     * The glass field surface, drawn at an animated rect: [progress] 0 = an icon-sized circle centred on [anchorX],
     * 1 = the full pill. [glow] 0..1 = the focus rim + underline light. The view's outline follows [current].
     */
    private class PillDrawable(private val base: Drawable, private val accent: Int, private val dp: Float) : Drawable() {
        var progress = 1f
        var anchorX = -1f
        var glow = 0f
        private val r = RectF()
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

        fun current(out: RectF) {
            val b = bounds
            val h = b.height().toFloat()
            val half = h / 2f
            val ax = if (anchorX < 0) b.right - half else anchorX.coerceIn(b.left + half, max(b.left + half, b.right - half))
            val p = progress.coerceIn(0f, 1f)
            out.set(ax - half + (b.left - (ax - half)) * p, b.top.toFloat(), ax + half + (b.right - (ax + half)) * p, b.bottom.toFloat())
        }

        override fun draw(c: Canvas) {
            if (bounds.isEmpty) return
            current(r)
            // The surface materialises over the first quarter of the stretch (from the icon), then stays solid.
            base.alpha = (progress.coerceIn(0f, 1f) * 4f).coerceIn(0f, 1f).let { (it * 255).toInt() }
            base.setBounds(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
            base.draw(c)
            val g = glow.coerceIn(0f, 1f)
            if (g <= 0.01f) return
            val radius = r.height() / 2f
            rim.color = accent
            rim.strokeWidth = 1.25f * dp
            rim.alpha = (g * 0.42f * 255).toInt()
            val inset = rim.strokeWidth / 2f
            c.drawRoundRect(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset, radius - inset, radius - inset, rim)
            // Underline light: a centred accent line along the bottom that grows outwards with focus, with a soft halo.
            val w = (r.width() - r.height()) * 0.7f * g
            if (w < 2f) return
            val cx = r.centerX(); val y = r.bottom - 2.2f * dp
            val clear = accent and 0x00FFFFFF
            line.shader = LinearGradient(cx - w / 2f, 0f, cx + w / 2f, 0f, intArrayOf(clear, accent, clear), null, Shader.TileMode.CLAMP)
            line.strokeWidth = 6f * dp; line.alpha = (g * 0.16f * 255).toInt()
            c.drawLine(cx - w / 2f, y, cx + w / 2f, y, line)
            line.strokeWidth = 1.8f * dp; line.alpha = (g * 0.9f * 255).toInt()
            c.drawLine(cx - w / 2f, y, cx + w / 2f, y, line)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) { base.colorFilter = cf }
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
