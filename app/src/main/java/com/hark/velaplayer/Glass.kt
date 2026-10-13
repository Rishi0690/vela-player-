package com.hark.velaplayer

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.MaterialShapeDrawable

/**
 * v2.12 design tokens (code side). The XML side lives in values/attrs.xml + the generated theme overlays and
 * values/dimens_tokens.xml.
 *
 * Glass levels (one per depth layer; deeper = more opaque):
 * - Level 1 `glassSurface`: list rows that need a surface, chips, tonal buttons, fields. Barely there.
 * - Level 2 `glassSurfaceElevated`: grouped panels (settings groups, collection headers, floating clusters).
 * - Level 3 `glassSurfaceStrong`: dialogs, sheets, popups, the nav pill container — things that float over content.
 * Every level gets a 1 dp hairline (`glassStroke`) and a soft top sheen (`glassHighlight`).
 *
 * Performance rule: nothing here blurs. Real blur is only the Android 12+ window blur behind dialogs and sheets
 * (values-v31/themes.xml) and short RenderEffect reveals in [Motion]. The player never blurs live video.
 */
object Tokens {
    // Radii (dp)
    const val RADIUS_XS = 10f
    const val RADIUS_S = 14f
    const val RADIUS_M = 20f
    const val RADIUS_L = 26f
    const val RADIUS_XL = 32f
    const val RADIUS_PILL = 999f
    // Spacing (dp)
    const val SPACE_XS = 4
    const val SPACE_S = 8
    const val SPACE_M = 12
    const val SPACE_L = 16
    const val SPACE_XL = 24
    const val SPACE_XXL = 32
    // Elevation (dp) for floating glass (shadows stay soft and low)
    const val ELEV_FLOAT = 6f
    const val ELEV_DIALOG = 12f
}

object Glass {
    fun surface(c: Context) = ThemeManager.attrOr(c, R.attr.glassSurface, 0x14FFFFFF)
    fun elevated(c: Context) = ThemeManager.attrOr(c, R.attr.glassSurfaceElevated, 0x1CFFFFFF)
    fun strong(c: Context) = ThemeManager.attrOr(c, R.attr.glassSurfaceStrong, 0xEB1A1A1F.toInt())
    fun stroke(c: Context) = ThemeManager.attrOr(c, R.attr.glassStroke, 0x24FFFFFF)
    fun highlight(c: Context) = ThemeManager.attrOr(c, R.attr.glassHighlight, 0x1FFFFFFF)
    fun navContainer(c: Context) = ThemeManager.attrOr(c, R.attr.navContainer, 0xD90A0C16.toInt())
    fun navSelected(c: Context) = ThemeManager.attrOr(c, R.attr.navSelected, 0xFFF4F6FB.toInt())
    fun navOnSelected(c: Context) = ThemeManager.attrOr(c, R.attr.navOnSelected, 0xFF0A0C16.toInt())
    fun navText(c: Context) = ThemeManager.attrOr(c, R.attr.navText, 0xFF9CA2B6.toInt())

    private fun dp(c: Context, v: Float) = v * c.resources.displayMetrics.density

    /**
     * A glass panel: [fill] with a 1dp [stroke] and a soft sheen fading down from the top edge.
     * Defaults read the current theme's glass colors (level 1).
     */
    fun panel(c: Context, radiusDp: Float = 20f, fill: Int = surface(c), stroke: Int = stroke(c), sheen: Int = highlight(c)): Drawable {
        val r = dp(c, radiusDp)
        val base = GradientDrawable().apply { cornerRadius = r; setColor(fill); setStroke(dp(c, 1f).toInt().coerceAtLeast(1), stroke) }
        val top = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(sheen, 0x00FFFFFF and sheen, 0x00FFFFFF and sheen)).apply {
            cornerRadius = r
        }
        return LayerDrawable(arrayOf(base, top))
    }

    /** Level-2 panel (grouped content). */
    fun elevatedPanel(c: Context, radiusDp: Float = Tokens.RADIUS_L) = panel(c, radiusDp, elevated(c))
    /** Level-3 panel (floating). */
    fun floatingPanel(c: Context, radiusDp: Float = Tokens.RADIUS_L) = panel(c, radiusDp, strong(c))

    /** A pill-shaped glass background with a ripple, for buttons living on glass. */
    fun pillButton(c: Context, fill: Int = surface(c)): Drawable {
        val content = panel(c, Tokens.RADIUS_PILL, fill)
        val mask = GradientDrawable().apply { cornerRadius = dp(c, Tokens.RADIUS_PILL); setColor(-1) }
        return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, mask)
    }

    /** Fixed dark glass for player panels (always dark, never blurred: live video sits underneath). */
    fun playerPanel(c: Context, radiusDp: Float = 24f) = panel(c, radiusDp, 0xE00E1018.toInt(), 0x24FFFFFF, 0x14FFFFFF)

    /** Lighter floating cluster for player controls over video (translucent dark glass + hairline, no blur). */
    fun playerCluster(c: Context, radiusDp: Float = Tokens.RADIUS_PILL) = panel(c, radiusDp, 0x8C0B0D14.toInt(), 0x26FFFFFF, 0x12FFFFFF)
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
