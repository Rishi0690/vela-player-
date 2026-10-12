package com.hark.velaplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageButton

/**
 * A white player icon with a soft dark glow behind it, so it stays visible on bright scenes.
 * The icon itself is drawn exactly as before; only a blurred silhouette is painted underneath.
 */
class ShadowImageButton @JvmOverloads constructor(c: Context, a: AttributeSet? = null, s: Int = androidx.appcompat.R.attr.imageButtonStyle) :
    AppCompatImageButton(c, a, s) {
    private val density = resources.displayMetrics.density
    private val blurPaint = Paint().apply { maskFilter = BlurMaskFilter(3.5f * density, BlurMaskFilter.Blur.NORMAL) }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { color = 0x99000000.toInt() }
    private var shadow: Bitmap? = null
    private val offset = IntArray(2)
    private var builtFor: Drawable? = null
    private var building = false

    override fun setImageDrawable(drawable: Drawable?) { super.setImageDrawable(drawable); drop() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); drop() }
    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) { super.setPadding(left, top, right, bottom); drop() }

    private fun drop() { shadow?.recycle(); shadow = null; builtFor = null; invalidate() }

    override fun onDraw(canvas: Canvas) {
        if (!building && drawable != null && width > 0 && height > 0) {
            if (shadow == null || builtFor !== drawable) build()
            shadow?.let { canvas.drawBitmap(it, offset[0].toFloat(), offset[1] + density, shadowPaint) }
        }
        super.onDraw(canvas)
    }

    private fun build() {
        building = true
        try {
            val src = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            super.onDraw(Canvas(src))
            shadow?.recycle()
            shadow = src.extractAlpha(blurPaint, offset)
            src.recycle()
            builtFor = drawable
        } catch (_: Throwable) { shadow = null } finally { building = false }
    }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); shadow?.recycle(); shadow = null; builtFor = null }
}

/** Bakes a soft shadow into a white icon for MaterialButtons (their icon can't use ShadowImageButton). */
object ShadowIcon {
    fun apply(btn: com.google.android.material.button.MaterialButton, res: Int) {
        val c = btn.context
        val d = c.resources.displayMetrics.density
        val s = (18 * d).toInt(); val pad = (3 * d).toInt(); val full = s + pad * 2
        val icon = androidx.appcompat.content.res.AppCompatResources.getDrawable(c, res)?.mutate() ?: return
        icon.setTint(0xFFFFFFFF.toInt())
        icon.setBounds(pad, pad, pad + s, pad + s)
        val src = Bitmap.createBitmap(full, full, Bitmap.Config.ARGB_8888)
        icon.draw(Canvas(src))
        val off = IntArray(2)
        val alpha = src.extractAlpha(Paint().apply { maskFilter = BlurMaskFilter(2.5f * d, BlurMaskFilter.Blur.NORMAL) }, off)
        val out = Bitmap.createBitmap(full, full, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.drawBitmap(alpha, off[0].toFloat(), off[1] + d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() })
        cv.drawBitmap(src, 0f, 0f, null)
        alpha.recycle(); src.recycle()
        btn.iconTint = null
        btn.iconSize = full
        btn.icon = android.graphics.drawable.BitmapDrawable(c.resources, out)
    }
}
