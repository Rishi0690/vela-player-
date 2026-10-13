package com.hark.velaplayer

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * v2.12 settings layout: each group of rows (everything between two category headers) sits inside ONE large rounded
 * glass panel (level 2), with hairline dividers between rows inside it and no per-row cards. Headers stay on the
 * atmosphere above their panel. Pure ItemDecoration: the preference rows, their clicks, switches and dialogs are
 * untouched.
 */
@SuppressLint("RestrictedApi")
class SettingsPanelDecoration(private val list: RecyclerView) : RecyclerView.ItemDecoration() {
    private val c = list.context
    private val d = c.resources.displayMetrics.density
    private val side = (16 * d).toInt()
    private val inner = (6 * d).toInt()
    private val radius = Tokens.RADIUS_L * d
    private val fill = GradientDrawable().apply { cornerRadius = radius; setColor(Glass.elevated(c)); setStroke(d.toInt().coerceAtLeast(1), Glass.stroke(c)) }
    private val sheen = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Glass.highlight(c), Glass.highlight(c) and 0x00FFFFFF, Glass.highlight(c) and 0x00FFFFFF)).apply { cornerRadius = radius }
    private val divider = Paint().apply { color = Glass.stroke(c); strokeWidth = d.coerceAtLeast(1f) }
    private val r = RectF()

    private fun pref(pos: Int): Preference? = (list.adapter as? PreferenceGroupAdapter)?.let { a ->
        if (pos in 0 until a.itemCount) a.getItem(pos) else null
    }
    private fun isHeader(p: Preference?) = p is PreferenceCategory
    private fun isRow(pos: Int) = pos != RecyclerView.NO_POSITION && pref(pos) != null && !isHeader(pref(pos))

    override fun getItemOffsets(out: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val pos = parent.getChildAdapterPosition(view)
        if (!isRow(pos)) { out.set(side, 0, side, 0); return }
        val first = !isRow(pos - 1)
        val last = !isRow(pos + 1)
        out.set(side, if (first) inner else 0, side, if (last) inner + (6 * d).toInt() else 0)
    }

    private val lines = ArrayList<Float>()

    override fun onDraw(cv: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        var top = Float.NaN
        var bottom = 0f
        var left = 0f; var right = 0f
        lines.clear()
        val n = parent.childCount
        for (i in 0 until n) {
            val v = parent.getChildAt(i)
            val pos = parent.getChildAdapterPosition(v)
            if (!isRow(pos)) {
                if (!top.isNaN()) { drawPanel(cv, left, top, right, bottom); top = Float.NaN }
                continue
            }
            val vt = v.top + v.translationY
            val vb = v.bottom + v.translationY
            if (top.isNaN()) {
                top = if (isRow(pos - 1)) -radius * 2 else vt - inner
                left = v.left.toFloat(); right = v.right.toFloat()
            }
            bottom = if (isRow(pos + 1)) vb else vb + inner
            if (isRow(pos + 1)) lines += vb
            if (i == n - 1 && isRow(pos + 1)) bottom = parent.height + radius * 2
        }
        if (!top.isNaN()) drawPanel(cv, left, top, right, bottom)
        // Hairline dividers between rows of the same panel (drawn after the panels so they sit on the glass).
        val l = side + 18 * d; val rr = parent.width - side - 18 * d
        lines.forEach { y -> cv.drawLine(l, y, rr, y, divider) }
    }

    private fun drawPanel(cv: Canvas, l: Float, t: Float, rr: Float, b: Float) {
        r.set(l, t, rr, b)
        fill.setBounds(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
        fill.draw(cv)
        sheen.setBounds(r.left.toInt(), r.top.toInt(), r.right.toInt(), (r.top + minOf(r.height(), 120 * d)).toInt())
        sheen.draw(cv)
    }

    companion object {
        /** Applies the grouped-panel look to a preference screen's list. */
        fun attach(f: PreferenceFragmentCompat) {
            f.setDivider(null)
            f.setDividerHeight(0)
            val l = f.listView ?: return
            if (l.itemDecorationCount > 0) for (i in l.itemDecorationCount - 1 downTo 0) if (l.getItemDecorationAt(i) is SettingsPanelDecoration) l.removeItemDecorationAt(i)
            l.addItemDecoration(SettingsPanelDecoration(l))
        }
    }
}
