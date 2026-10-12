package com.hark.velaplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * v2.4: "stack of videos" look for collections (playlists, folders). The real thumbnail sits in front
 * and a second video's thumbnail peeks out behind it, shifted down and to the right.
 * The frame reserves that space as end/bottom padding, so the front children keep their own size.
 */
class StackedThumbLayout @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : FrameLayout(c, a) {
    private val d = resources.displayMetrics.density
    private val offX = 10f * d
    private val offY = 8f * d
    private val inset = 16f * d
    private val radius = 14f * d
    private var back: Bitmap? = null
    private var key: String? = null
    private var stacked = false
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59000000 }
    private val img = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()

    init {
        setWillNotDraw(false)
        setPadding(0, 0, offX.toInt(), offY.toInt())
        clipChildren = false
    }

    /** Show the back card (from the given video) only when the collection holds two or more videos. */
    fun setStack(count: Int, src: Triple<Uri, Long, Long>?) {
        stacked = count > 1
        val k = src?.first?.toString()
        if (!stacked) { key = null; back = null; invalidate(); return }
        if (k == key) return
        key = k; back = null; invalidate()
        if (src == null || k == null) return
        ThumbLoader.fetch(context, src.first, src.second, src.third) { b -> if (key == k) { back = b; invalidate() } }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!stacked) return
        rect.set(inset, offY, width.toFloat(), height.toFloat())
        val b = back
        fill.color = ThemeManager.attr(context, com.google.android.material.R.attr.colorPrimaryContainer)
        canvas.drawRoundRect(rect, radius, radius, fill)
        if (b != null && !b.isRecycled) {
            val src = if (android.os.Build.VERSION.SDK_INT >= 26 && b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b
            val sh = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val s = maxOf(rect.width() / src.width, rect.height() / src.height)
            val m = Matrix().apply { setScale(s, s); postTranslate(rect.left + (rect.width() - src.width * s) / 2, rect.top + (rect.height() - src.height * s) / 2) }
            sh.setLocalMatrix(m); img.shader = sh
            canvas.drawRoundRect(rect, radius, radius, img)
        }
        canvas.drawRoundRect(rect, radius, radius, shade)
    }
}
