package com.hark.velaplayer

import android.util.TypedValue
import com.google.android.material.button.MaterialButton

/**
 * v2.9: keeps the "Play all" / "Shuffle" header buttons on one line on any screen width.
 * Each layout pass it checks whether icon + text fit the button's real width; if not it shrinks the
 * text (down to 12sp), and on very narrow buttons drops the icon. Wider screens get the full 14sp + icon back.
 */
object FitButtons {
    fun apply(vararg buttons: MaterialButton) = buttons.forEach { b ->
        val icon = b.icon
        b.addOnLayoutChangeListener { v, l, _, r, _, ol, _, or_, _ ->
            if (r - l == or_ - ol) return@addOnLayoutChangeListener
            v.post { fit(b, icon) }
        }
    }

    private fun fit(b: MaterialButton, icon: android.graphics.drawable.Drawable?) {
        val w = b.width - b.paddingStart - b.paddingEnd - b.insetLeftCompat() - b.insetRightCompat()
        if (w <= 0) return
        val d = b.resources.displayMetrics
        val iconW = if (icon != null) b.iconSize + b.iconPadding else 0
        val text = b.text.toString()
        var sp = 14f
        fun textW(s: Float): Float { val p = android.graphics.Paint(b.paint); p.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, s, d); return p.measureText(text) }
        while (sp > 12f && textW(sp) + iconW > w) sp -= 0.5f
        val showIcon = icon != null && textW(sp) + iconW <= w
        if (!showIcon) { sp = 14f; while (sp > 11f && textW(sp) > w) sp -= 0.5f }
        if (b.textSize != TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, d)) b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        val want = if (showIcon) icon else null
        if (b.icon !== want) b.icon = want
    }

    private fun MaterialButton.insetLeftCompat() = 0
    private fun MaterialButton.insetRightCompat() = 0
}
