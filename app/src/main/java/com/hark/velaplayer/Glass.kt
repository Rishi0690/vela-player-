package com.hark.velaplayer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import androidx.activity.ComponentDialog
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
 * Performance rule: nothing here blurs. Real blur is only the Android 12+ window blur behind dialogs, sheets and
 * popup menus (values-v31/themes.xml + [Glass.blurPopupWindows]) and short RenderEffect reveals in [Motion].
 * The player never blurs live video.
 *
 * v2.19 floating glass: windows that float over a BLURRED screen use `glassFloat` / `glassFloatStroke` (light mode:
 * frosted white ~82% + darker ink hairline, so the blur is actually visible; dark: same as level 3). Whenever the
 * screen behind is NOT blurred (Android 11-, the system turned blur off, player theme) they fall back to
 * `glassSurfaceStrong`, so text never sits on see-through glass without blur ([floatFill], [bindWindowBlur]).
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
    /** v2.19 floating glass fill / hairline (see class doc). */
    fun float(c: Context) = ThemeManager.attrOr(c, R.attr.glassFloat, strong(c))
    fun floatStroke(c: Context) = ThemeManager.attrOr(c, R.attr.glassFloatStroke, stroke(c))
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

    /** v2.19: false in the player theme (fixed dark glass, never blur over live video). */
    fun blurWindows(c: Context): Boolean {
        val tv = android.util.TypedValue()
        return c.theme.resolveAttribute(R.attr.glassBlurWindows, tv, true) && tv.data != 0
    }

    /** Android 12+ and the system currently allows cross-window blur (off in battery saver / on some GPUs). */
    fun blurAvailable(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31 || !blurWindows(c)) return false
        return try { (c.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.isCrossWindowBlurEnabled == true } catch (_: Exception) { false }
    }

    /** Fill for a floating window: frosted [float] over a blurred screen, near-opaque [strong] otherwise. */
    fun floatFill(c: Context, blurred: Boolean) = if (blurred) float(c) else strong(c)

    /** Dim behind a floating window for the current mode (values(-night)/dimens_glass.xml). */
    fun dim(c: Context, floating: Boolean, blurred: Boolean): Float {
        val id = when {
            floating && blurred -> R.dimen.vela_dim_floating_blur
            floating -> R.dimen.vela_dim_floating_flat
            blurred -> R.dimen.vela_dim_dialog_blur
            else -> R.dimen.vela_dim_dialog_flat
        }
        return androidx.core.content.res.ResourcesCompat.getFloat(c.resources, id)
    }

    /**
     * Level-3 panel for a floating WINDOW (ItemSheet card): floating fill + floating hairline. Recolour the fill
     * later with [setPanelFill] (e.g. from [bindWindowBlur]).
     */
    fun floatingWindowPanel(c: Context, radiusDp: Float = Tokens.RADIUS_XL, blurred: Boolean = blurAvailable(c)) =
        panel(c, radiusDp, floatFill(c, blurred), floatStroke(c))

    /** Changes the fill of a [panel] drawable (layer 0) without touching hairline or sheen. */
    fun setPanelFill(d: Drawable?, color: Int) {
        ((d as? LayerDrawable)?.getDrawable(0) as? GradientDrawable)?.setColor(color)
    }

    /**
     * Follows the system's cross-window blur switch while [dialog] is showing (Android 12+): [onChange] gets true when
     * the screen behind is blurred, false when it is not (also called once right away; always false below Android 12).
     * Uses the dialog's lifecycle (AppCompatDialog is a ComponentDialog), so callers' own show/dismiss listeners stay
     * untouched and nothing leaks after dismiss.
     */
    fun bindWindowBlur(dialog: ComponentDialog, onChange: (Boolean) -> Unit) {
        val c = dialog.context
        onChange(blurAvailable(c))
        if (Build.VERSION.SDK_INT < 31 || !blurWindows(c)) return
        val listener = java.util.function.Consumer<Boolean> { enabled -> onChange(enabled) }
        var registered: WindowManager? = null
        dialog.lifecycle.addObserver(LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> if (registered == null) {
                    val wm = dialog.window?.windowManager ?: return@LifecycleEventObserver
                    try { wm.addCrossWindowBlurEnabledListener(listener); registered = wm } catch (_: Exception) {}
                }
                Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> registered?.let {
                    try { it.removeCrossWindowBlurEnabledListener(listener) } catch (_: Exception) {}
                    registered = null
                }
                else -> {}
            }
        })
    }

    /**
     * v2.19: frosted popups. PopupMenu / toolbar and ActionMode overflow / ListPopupWindow windows are created inside
     * AppCompat with no hook, so after one opens (the activity loses window focus) this finds this activity's popup
     * windows (TYPE_APPLICATION_PANEL; tooltips, text-selection toolbars and handles use other types) and turns on the
     * Android 12+ blur behind them. If the system has blur off, the frosted fill of `glass_popup_float` is swapped back
     * to the near-opaque level 3 instead. Called by [ThemedActivity] only, so the player never gets it.
     */
    fun blurPopupWindows(a: Activity) {
        if (Build.VERSION.SDK_INT < 31 || !blurWindows(a)) return
        val roots = try { android.view.inspector.WindowInspector.getGlobalWindowViews() } catch (_: Throwable) { return }
        val wm = a.windowManager
        val enabled = try { wm.isCrossWindowBlurEnabled } catch (_: Exception) { false }
        for (root in roots) {
            val lp = root.layoutParams as? WindowManager.LayoutParams ?: continue
            if (lp.type != WindowManager.LayoutParams.TYPE_APPLICATION_PANEL || !root.isAttachedToWindow) continue
            if (root.getTag(R.id.tag_glass_popup) != null || activityOf(root.context) !== a) continue
            root.setTag(R.id.tag_glass_popup, true)
            if (enabled) {
                lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                lp.blurBehindRadius = a.resources.getDimensionPixelSize(R.dimen.vela_popup_blur_radius)
                try { wm.updateViewLayout(root, lp) } catch (_: Exception) {}
            } else {
                popupFillLayer(root)?.setColor(strong(a))
            }
        }
    }

    /** The `@id/glass_fill` layer of the popup background (PopupWindow puts it on the decor or its first child). */
    private fun popupFillLayer(root: View): GradientDrawable? {
        var v: View? = root
        repeat(3) {
            val bg = v?.background as? LayerDrawable
            val layer = bg?.findDrawableByLayerId(R.id.glass_fill)
            if (layer is GradientDrawable) { bg.mutate(); return bg.findDrawableByLayerId(R.id.glass_fill) as? GradientDrawable }
            v = (v as? android.view.ViewGroup)?.takeIf { it.childCount > 0 }?.getChildAt(0)
        }
        return null
    }

    private fun activityOf(c: Context?): Activity? {
        var x = c
        while (x != null) { if (x is Activity) return x; x = (x as? ContextWrapper)?.baseContext }
        return null
    }

    /** A pill-shaped glass background with a ripple, for buttons living on glass. */
    fun pillButton(c: Context, fill: Int = surface(c)): Drawable {
        val content = panel(c, Tokens.RADIUS_PILL, fill)
        val mask = GradientDrawable().apply { cornerRadius = dp(c, Tokens.RADIUS_PILL); setColor(-1) }
        return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, mask)
    }

    /** v2.13: glass text field for dialogs (rename, new playlist): level-2 fill, hairline, no underline. */
    fun styleField(e: android.widget.EditText) {
        val c = e.context
        e.background = panel(c, Tokens.RADIUS_S, elevated(c))
        val h = dp(c, 14f).toInt(); val v = dp(c, 12f).toInt()
        e.setPadding(h, v, h, v)
    }

    /** Fixed dark glass for player panels (always dark, never blurred: live video sits underneath). */
    fun playerPanel(c: Context, radiusDp: Float = 24f) = panel(c, radiusDp, 0xE00E1018.toInt(), 0x24FFFFFF, 0x14FFFFFF)

    /** Lighter floating cluster for player controls over video (translucent dark glass + hairline, no blur). */
    fun playerCluster(c: Context, radiusDp: Float = Tokens.RADIUS_PILL) = panel(c, radiusDp, 0x8C0B0D14.toInt(), 0x26FFFFFF, 0x12FFFFFF)
}

/**
 * MaterialAlertDialogBuilder with the glass hairline border. Every dialog in the app is built with this
 * (the fill/shape/blur come from VelaDialog in themes.xml).
 * v2.19: outside the player, the fill/dim follow the live blur state ([Glass.bindWindowBlur]): frosted
 * `glassFloat` + lighter dim over the Android 12+ blur, near-opaque `glassSurfaceStrong` + normal dim without it.
 */
open class VelaDialogBuilder : MaterialAlertDialogBuilder {
    constructor(context: Context) : super(context)
    constructor(context: Context, overrideThemeResId: Int) : super(context, overrideThemeResId)

    override fun create(): AlertDialog {
        val c = context
        val glass = Glass.blurWindows(c)
        val shape = background as? MaterialShapeDrawable
        shape?.setStroke(c.resources.displayMetrics.density, if (glass) Glass.floatStroke(c) else Glass.stroke(c))
        val dlg = super.create()
        if (glass && shape != null) Glass.bindWindowBlur(dlg) { blurred ->
            shape.fillColor = ColorStateList.valueOf(Glass.floatFill(c, blurred))
            dlg.window?.setDimAmount(Glass.dim(c, floating = false, blurred = blurred))
        }
        return dlg
    }
}
