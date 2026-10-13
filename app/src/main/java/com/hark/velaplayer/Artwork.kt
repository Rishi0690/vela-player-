package com.hark.velaplayer

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.widget.ImageView
import androidx.core.content.ContextCompat

/**
 * v2.14 Vela artwork, "luminous glass forms": a lit glass ring, stacked glass screens and light ribbons
 * (procedural, tools/artgen/gen_art.py). The three white layers are tinted with the live theme here, so the art
 * matches every preset and custom colour in light and dark. Decorative only: never used in place of a video thumbnail.
 */
object Artwork {
    /** Layered, theme-tinted artwork (atmosphere layer in the second glow colour, forms in the accent, white highlights). */
    fun drawable(c: Context): Drawable? {
        val a = ContextCompat.getDrawable(c, R.drawable.art_forms_a)?.mutate() ?: return null
        val b = ContextCompat.getDrawable(c, R.drawable.art_forms_b)?.mutate() ?: return null
        val h = ContextCompat.getDrawable(c, R.drawable.art_forms_hi)?.mutate() ?: return null
        val accent = ThemeManager.primary(c)
        val atmo = Atmosphere.of(c)
        val second = atmo.glowB or 0xFF000000.toInt()
        if (atmo.dark) {
            b.colorFilter = PorterDuffColorFilter(Atmosphere.mix(second, accent, 0.25f), PorterDuff.Mode.SRC_IN); b.alpha = 170
            a.colorFilter = PorterDuffColorFilter(Atmosphere.mix(accent, Color.WHITE, 0.12f), PorterDuff.Mode.SRC_IN); a.alpha = 235
            h.alpha = 235
        } else {
            // Light mode: deeper ink of the accent so the forms read on frosted backgrounds; highlights stay subtle.
            b.colorFilter = PorterDuffColorFilter(Atmosphere.mix(second, accent, 0.4f), PorterDuff.Mode.SRC_IN); b.alpha = 120
            a.colorFilter = PorterDuffColorFilter(Atmosphere.mix(accent, Color.BLACK, 0.12f), PorterDuff.Mode.SRC_IN); a.alpha = 215
            h.colorFilter = PorterDuffColorFilter(Atmosphere.mix(accent, Color.WHITE, 0.6f), PorterDuff.Mode.SRC_IN); h.alpha = 200
        }
        return LayerDrawable(arrayOf(b, a, h))
    }

    fun applyTo(iv: ImageView, scale: ImageView.ScaleType = ImageView.ScaleType.CENTER_CROP) {
        iv.setImageDrawable(drawable(iv.context))
        iv.scaleType = scale
        iv.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
}
