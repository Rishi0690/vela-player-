package com.hark.velaplayer

import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import kotlin.math.roundToInt

/**
 * v2.21: makes a horizontal row of icon buttons + text pills + one weighted gap ALWAYS fit its real width
 * (player bottom row; any width down to ~280dp, any font scale / display size, cutouts and side nav bars).
 *
 * Root cause of the v2.16.1 fix not being enough (lock button past the right edge on a friend's phone):
 * `fitButtonRow()` stopped at a 36dp floor for icon buttons and never shrank the pills, so whenever
 * pills + 4 × 36dp was still wider than the row (narrow display / "Display size: Large" / font scale 1.3 makes
 * "1.25x" and "Stretch" pills wide) LinearLayout laid the last child past the edge; it also only re-ran when the
 * row's WIDTH changed or a pill's text changed, not when a button became visible (PiP/rotate shown later).
 *
 * Now each pass measures the REAL children and degrades step by step until they fit:
 *  1. pills lose their icons;  2. icon buttons shrink 48 → 36dp;  3. pills get compact padding;
 *  4. pill text steps down (to 11sp);  5. the lowest-priority icons are hidden ([droppable], first = first to go;
 *  [onHidden] lets the screen offer them elsewhere, e.g. the options sheet);  6. last resort: icon buttons go to 32dp
 *  and pills ellipsize inside whatever is left. The row re-checks itself after every layout (width, visibility,
 *  text), so it can't silently overflow later.
 */
class RowFitter(
    private val row: LinearLayout,
    private val pills: List<MaterialButton>,
    /** Icon buttons managed here (always-there ones and droppable ones). */
    private val icons: List<View>,
    /** Icons that may be hidden when nothing else helps, in the order they go. Never include the one that must stay. */
    private val droppable: List<View>,
    private val onHidden: (Set<View>) -> Unit = {},
) {
    private val dm = row.resources.displayMetrics
    private fun dp(v: Float) = (v * dm.density).roundToInt()
    private val pillIcons: Map<MaterialButton, Drawable?> = pills.associateWith { it.icon }
    private val pillPad: Map<MaterialButton, Pair<Int, Int>> = pills.associateWith { it.paddingStart to it.paddingEnd }
    private val pillText: Map<MaterialButton, Float> = pills.associateWith { it.textSize }
    private val pillMin: Map<MaterialButton, Pair<Int, Int>> = pills.associateWith { it.minWidth to it.minimumWidth }
    /** Visibility the screen wants (we only ever hide on top of that). */
    private val wanted = HashMap<View, Boolean>()
    var hidden: Set<View> = emptySet()
        private set
    private var lastSig = ""
    private var fitting = false

    init {
        row.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (!fitting && signature() != lastSig) row.post { fit() } }
    }

    /** Call when the screen changes a managed icon's visibility (instead of setting it directly). */
    fun setWanted(v: View, visible: Boolean) { wanted[v] = visible; if (v !in hidden) v.isVisible = visible; row.post { fit() } }

    private fun wantedVisible(v: View) = wanted[v] ?: (v.isVisible || v in hidden)

    private fun signature(): String = buildString {
        append(row.width).append('|')
        for (i in 0 until row.childCount) { val c = row.getChildAt(i); append(if (c.isVisible) '1' else '0') }
        pills.forEach { append('|').append(it.text) }
        icons.forEach { append(if (wantedVisible(it)) 'w' else '-') }
    }

    private fun avail() = row.width - row.paddingLeft - row.paddingRight

    /** Width the visible children need (gap excluded: it is weighted and may be 0). */
    private fun needed(): Int {
        val h = View.MeasureSpec.makeMeasureSpec(dp(48f), View.MeasureSpec.AT_MOST)
        var sum = 0
        for (i in 0 until row.childCount) {
            val c = row.getChildAt(i)
            if (!c.isVisible) continue
            val lp = c.layoutParams as LinearLayout.LayoutParams
            if (lp.weight > 0f && lp.width == 0) { sum += lp.leftMargin + lp.rightMargin; continue }
            val w = if (lp.width > 0) View.MeasureSpec.makeMeasureSpec(lp.width, View.MeasureSpec.EXACTLY)
                else View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            c.measure(w, h)
            sum += c.measuredWidth + lp.leftMargin + lp.rightMargin
        }
        return sum
    }

    private fun setIconWidth(px: Int) = icons.forEach { v ->
        val lp = v.layoutParams; if (lp.width != px) { lp.width = px; v.layoutParams = lp }
    }

    private fun reset() {
        hidden.forEach { v -> v.isVisible = wantedVisible(v) }
        hidden = emptySet()
        icons.forEach { v -> v.isVisible = wantedVisible(v) }
        pills.forEach { p ->
            p.icon = pillIcons[p]
            val (s, e) = pillPad[p]!!; p.setPaddingRelative(s, p.paddingTop, e, p.paddingBottom)
            p.setTextSize(TypedValue.COMPLEX_UNIT_PX, pillText[p]!!)
            p.maxWidth = Int.MAX_VALUE; p.ellipsize = null; p.maxLines = 1
            val (mw, mmw) = pillMin[p]!!; p.minWidth = mw; p.minimumWidth = mmw
        }
        setIconWidth(dp(48f))
    }

    /** Runs the staged fit now (no-op until the row has a width). Returns the step that made it fit (0 = as designed). */
    fun fit(): Int {
        val avail = avail()
        if (avail <= 0) return -1
        fitting = true
        try {
            reset()
            var step = 0
            fun fits() = needed() <= avail
            fun shrinkIcons(min: Float) {
                val visIcons = icons.filter { it.isVisible }
                if (visIcons.isEmpty()) return
                setIconWidth(dp(48f)) // measure pills alone
                val others = needed() - visIcons.size * dp(48f)
                val each = ((avail - others) / visIcons.size).coerceIn(dp(min), dp(48f))
                setIconWidth(each)
            }
            if (!fits()) { step = 1; pills.forEach { it.icon = null } }
            if (!fits()) { step = 2; shrinkIcons(36f) }
            if (!fits()) {
                // Material buttons have a 64dp+ minimum width: compact pills hug their text.
                step = 3; pills.forEach { it.setPaddingRelative(dp(8f), it.paddingTop, dp(8f), it.paddingBottom); it.minWidth = 0; it.minimumWidth = 0 }
                shrinkIcons(36f)
            }
            var sp = pills.firstOrNull()?.let { it.textSize / dm.scaledDensity } ?: 11f
            while (!fits() && sp > 11f) {
                step = 4
                sp = (sp - 1f).coerceAtLeast(11f)
                pills.forEach { it.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp) }
                shrinkIcons(36f)
            }
            val gone = LinkedHashSet<View>()
            for (v in droppable) {
                if (fits()) break
                if (!v.isVisible) continue
                step = 5; v.isVisible = false; gone += v; shrinkIcons(36f)
            }
            hidden = gone
            if (!fits()) {
                step = 6
                shrinkIcons(32f)
                val visPills = pills.filter { it.isVisible }
                if (visPills.isNotEmpty()) {
                    val iconsW = icons.filter { it.isVisible }.sumOf { (it.layoutParams.width) }
                    val margins = visPills.sumOf { (it.layoutParams as ViewGroup.MarginLayoutParams).let { m -> m.leftMargin + m.rightMargin } }
                    val each = ((avail - iconsW - margins) / visPills.size).coerceAtLeast(dp(32f))
                    visPills.forEach { p -> p.maxWidth = each; p.ellipsize = android.text.TextUtils.TruncateAt.END }
                }
            }
            onHidden(gone)
            row.requestLayout()
            return step
        } finally {
            fitting = false
            lastSig = signature()
        }
    }
}
