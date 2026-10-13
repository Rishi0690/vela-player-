package com.hark.velaplayer

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.MaterialShapeDrawable

/**
 * v2.11 glass system, code side. The look itself lives in themes (?attr/glassSurface, glassSurfaceStrong,
 * glassStroke, glassHighlight, glassScrim, glassTint set by every theme overlay) and the Widget.Vela.Glass.* styles;
 * this object builds the same look for views created in code.
 *
 * Performance rule: nothing here blurs. Real blur is only the Android 12+ window blur behind dialogs and sheets
 * (values-v31/themes.xml). The player never blurs live video; it uses translucent dark glass.
 */
object Glass {
    fun surface(c: Context) = ThemeManager.attrOr(c, R.attr.glassSurface, 0x14FFFFFF)
    fun strong(c: Context) = ThemeManager.attrOr(c, R.attr.glassSurfaceStrong, 0xEB1A1A1F.toInt())
    fun stroke(c: Context) = ThemeManager.attrOr(c, R.attr.glassStroke, 0x24FFFFFF)
    fun highlight(c: Context) = ThemeManager.attrOr(c, R.attr.glassHighlight, 0x1FFFFFFF)

    private fun dp(c: Context, v: Float) = v * c.resources.displayMetrics.density

    /**
     * A glass panel: [fill] with a 1dp [stroke] and a soft sheen fading down from the top edge.
     * Defaults read the current theme's glass colors.
     */
    fun panel(c: Context, radiusDp: Float = 20f, fill: Int = surface(c), stroke: Int = stroke(c), sheen: Int = highlight(c)): Drawable {
        val r = dp(c, radiusDp)
        val base = GradientDrawable().apply { cornerRadius = r; setColor(fill); setStroke(dp(c, 1f).toInt().coerceAtLeast(1), stroke) }
        val top = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(sheen, 0x00FFFFFF and sheen, 0x00FFFFFF and sheen)).apply {
            cornerRadius = r
        }
        return LayerDrawable(arrayOf(base, top))
    }

    /** Fixed dark glass for player panels (always dark, never blurred: live video sits underneath). */
    fun playerPanel(c: Context, radiusDp: Float = 24f) = panel(c, radiusDp, 0xE0101014.toInt(), 0x24FFFFFF, 0x14FFFFFF)
}

/**
 * MaterialAlertDialogBuilder with the glass hairline border. Every dialog in the app is built with this
 * (the fill/shape/blur come from VelaDialog in themes.xml).
 */
open class VelaDialogBuilder : MaterialAlertDialogBuilder {
    constructor(context: Context) : super(context)
    constructor(context: Context, overrideThemeResId: Int) : super(context, overrideThemeResId)

    override fun create(): AlertDialog {
        (background as? MaterialShapeDrawable)?.let { d ->
            val c = context
            d.setStroke(c.resources.displayMetrics.density, Glass.stroke(c))
        }
        return super.create()
    }
}
