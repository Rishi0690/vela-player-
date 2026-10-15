package com.hark.velaplayer

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * v2.20 (target SDK 36): Android 15+ always draws apps edge to edge (and Android 16 ignores the opt-out), so every
 * screen lays itself out under the status/navigation bars and display cutout and moves its own content clear of them.
 * Vela does the same on EVERY Android version (one behaviour to test): the window background (theme atmosphere or the
 * backdrop photo) runs behind the bars, and each screen pads only the views that must stay clear:
 *
 *  - the screen root: left / top / right (status bar + cutout + side navigation bar), so toolbars sit below the bar;
 *  - scrolling lists: bottom padding for the navigation bar (and the keyboard while it is open) with
 *    clipToPadding = false, so rows scroll behind the gesture/nav bar but the last row can be scrolled clear of it;
 *  - bottom bars / panels: bottom padding (their background extends behind the nav bar);
 *  - floating buttons: bottom / end margins.
 *
 * Insets are ADDED to the view's own padding/margins and the previously added amount is remembered in a view tag, so
 * code that later changes another side (e.g. Settings' search padding on the top of a list) keeps working, and repeated
 * inset passes never accumulate. Listeners never consume the insets: siblings (Snackbar, Material bottom sheets) still
 * get them and place themselves above the navigation bar on their own.
 */
object EdgeToEdge {
    /** System bars + display cutout: what content must never sit under. */
    val SAFE: Int = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()

    /** Lays the window out behind the system bars (what Android 15+ enforces for target SDK 35+). */
    fun enable(a: Activity) {
        WindowCompat.setDecorFitsSystemWindows(a.window, false)
        // Android 15+ treats every non-floating window as LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS; ask for the same on
        // Android 11-14 so the backdrop also fills the cutout area there (content is padded by SAFE either way).
        if (Build.VERSION.SDK_INT in 30..34) a.window.attributes = a.window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    /** The cutout mode the full-screen players use: ALWAYS on Android 11+ (what 15+ enforces), SHORT_EDGES on 9-10. */
    fun playerCutoutMode(a: Activity) {
        if (Build.VERSION.SDK_INT >= 28) a.window.attributes = a.window.attributes.apply {
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    /**
     * Adds the chosen sides of the safe insets to [v]'s padding. [bottom] = navigation bar; [ime] = the keyboard too
     * (the larger of the two, like the old adjustResize window). [extra] runs after each pass (for derived layout).
     */
    fun pad(
        v: View, left: Boolean = false, top: Boolean = false, right: Boolean = false, bottom: Boolean = false,
        ime: Boolean = false, extra: ((Insets) -> Unit)? = null,
    ) {
        ViewCompat.setOnApplyWindowInsetsListener(v) { view, insets ->
            val s = insets.getInsets(SAFE)
            val k = if (ime) insets.getInsets(WindowInsetsCompat.Type.ime()) else Insets.NONE
            val want = Insets.of(if (left) s.left else 0, if (top) s.top else 0, if (right) s.right else 0,
                if (bottom || ime) maxOf(if (bottom) s.bottom else 0, k.bottom) else 0)
            val old = view.getTag(R.id.e2e_padding) as? Insets ?: Insets.NONE
            if (old != want) {
                view.setPadding(view.paddingLeft - old.left + want.left, view.paddingTop - old.top + want.top,
                    view.paddingRight - old.right + want.right, view.paddingBottom - old.bottom + want.bottom)
                view.setTag(R.id.e2e_padding, want)
            }
            extra?.invoke(want)
            insets
        }
        requestWhenAttached(v)
    }

    /** A scrolling list/scroll view: bottom padding for the nav bar (+ keyboard when [ime]) and draw under it. */
    fun list(v: View, ime: Boolean = false) {
        (v as? ViewGroup)?.clipToPadding = false
        pad(v, bottom = true, ime = ime)
    }

    /** The screen root: everything below the status bar and clear of the cutout / side navigation bar. */
    fun root(v: View) = pad(v, left = true, top = true, right = true)

    /** Adds the safe insets to [v]'s margins (floating action buttons; left/right only when the parent isn't padded). */
    fun margin(v: View, bottom: Boolean = true, left: Boolean = false, right: Boolean = false) {
        ViewCompat.setOnApplyWindowInsetsListener(v) { view, insets ->
            val s = insets.getInsets(SAFE)
            val want = Insets.of(if (left) s.left else 0, 0, if (right) s.right else 0, if (bottom) s.bottom else 0)
            val old = view.getTag(R.id.e2e_margin) as? Insets ?: Insets.NONE
            val lp = view.layoutParams as? ViewGroup.MarginLayoutParams
            if (lp != null && old != want) {
                lp.leftMargin += want.left - old.left; lp.rightMargin += want.right - old.right
                lp.bottomMargin += want.bottom - old.bottom
                view.layoutParams = lp
                view.setTag(R.id.e2e_margin, want)
            }
            insets
        }
        requestWhenAttached(v)
    }

    /** Insets added to [v] so far by [pad] (tests and derived layout). */
    fun applied(v: View): Insets = v.getTag(R.id.e2e_padding) as? Insets ?: Insets.NONE

    private fun requestWhenAttached(v: View) {
        if (v.isAttachedToWindow) ViewCompat.requestApplyInsets(v)
        else v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { view.removeOnAttachStateChangeListener(this); ViewCompat.requestApplyInsets(view) }
            override fun onViewDetachedFromWindow(view: View) {}
        })
    }
}
