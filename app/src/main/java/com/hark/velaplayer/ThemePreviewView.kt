package com.hark.velaplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * v2.11: a miniature of the app drawn in a theme's real colors: background, a glass card with a thumbnail and
 * text lines, a chip and an accent button. Used by the theme gallery cards and the big live preview.
 * Pure Canvas drawing (no blur, no bitmaps) so a grid of 30 stays cheap.
 */
class ThemePreviewView @JvmOverloads constructor(c: Context, attrs: AttributeSet? = null) : View(c, attrs) {
    var palette: ThemeManager.Palette? = null
        set(v) { field = v; invalidate() }
    /** Big preview shows a little more detail (second row, toolbar). */
    var detailed = false
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()
    private val tri = Path()

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val hMode = MeasureSpec.getMode(h)
        val height = if (hMode == MeasureSpec.EXACTLY) MeasureSpec.getSize(h) else (width * if (detailed) 0.46f else 0.78f).toInt()
        setMeasuredDimension(width, height)
    }

    private fun rr(l: Float, t: Float, rr: Float, b: Float, rad: Float, color: Int) {
        r.set(l, t, rr, b); p.shader = null; p.color = color; canvas?.drawRoundRect(r, rad, rad, p)
    }
    private var canvas: Canvas? = null

    override fun onDraw(cv: Canvas) {
        val pal = palette ?: return
        canvas = cv
        val w = width.toFloat(); val h = height.toFloat()
        val corner = 14f * d
        // background
        rr(0f, 0f, w, h, corner, pal.bg)
        val pad = (if (detailed) 14f else 8f) * d
        var y = pad
        // toolbar: title line + accent dot
        val line = (if (detailed) 7f else 5f) * d
        rr(pad, y, pad + w * 0.38f, y + line, line / 2, pal.text)
        p.color = pal.primary; cv.drawCircle(w - pad - line, y + line / 2, line * 0.8f, p)
        y += line + (if (detailed) 12f else 7f) * d
        // chips row
        val chipH = (if (detailed) 16f else 10f) * d
        r.set(pad, y, pad + w * 0.22f, y + chipH)
        p.color = (pal.primary and 0x00FFFFFF) or 0x38000000; cv.drawRoundRect(r, chipH / 2, chipH / 2, p)
        s.strokeWidth = d; s.color = (pal.primary and 0x00FFFFFF) or 0x8C000000.toInt(); cv.drawRoundRect(r, chipH / 2, chipH / 2, s)
        r.set(pad + w * 0.25f, y, pad + w * 0.47f, y + chipH)
        p.color = pal.glass; cv.drawRoundRect(r, chipH / 2, chipH / 2, p)
        s.color = pal.stroke; cv.drawRoundRect(r, chipH / 2, chipH / 2, s)
        y += chipH + (if (detailed) 10f else 6f) * d
        // glass card
        val cardB = h - pad - (if (detailed) 30f else 20f) * d
        val cr = (if (detailed) 16f else 10f) * d
        r.set(pad, y, w - pad, cardB)
        p.color = pal.glass; cv.drawRoundRect(r, cr, cr, p)
        p.shader = LinearGradient(0f, y, 0f, y + (cardB - y) * 0.5f, pal.highlight, pal.highlight and 0x00FFFFFF, Shader.TileMode.CLAMP)
        cv.drawRoundRect(r, cr, cr, p); p.shader = null
        s.color = pal.stroke; cv.drawRoundRect(r, cr, cr, s)
        // thumbnail inside card
        val inner = (if (detailed) 10f else 6f) * d
        val th = cardB - y - inner * 2
        val tw = th * 1.6f
        rr(pad + inner, y + inner, pad + inner + tw, y + inner + th, cr * 0.6f, pal.surface)
        val cx = pad + inner + tw / 2; val cy = y + inner + th / 2; val ts = th * 0.18f
        tri.reset(); tri.moveTo(cx - ts * 0.7f, cy - ts); tri.lineTo(cx + ts, cy); tri.lineTo(cx - ts * 0.7f, cy + ts); tri.close()
        p.color = pal.textDim; cv.drawPath(tri, p)
        rr(pad + inner, y + inner + th - 2.5f * d, pad + inner + tw * 0.55f, y + inner + th, 1.5f * d, pal.primary)
        // text lines
        val tx = pad + inner * 2 + tw
        val tl = (if (detailed) 6f else 4f) * d
        rr(tx, y + inner + th * 0.15f, w - pad - inner * 2, y + inner + th * 0.15f + tl, tl / 2, pal.text)
        rr(tx, y + inner + th * 0.15f + tl * 2.2f, tx + (w - pad - inner - tx) * 0.55f, y + inner + th * 0.15f + tl * 3.2f, tl / 2, pal.textDim)
        if (detailed) rr(tx, y + inner + th * 0.15f + tl * 4.4f, tx + (w - pad - inner - tx) * 0.35f, y + inner + th * 0.15f + tl * 5.4f, tl / 2, pal.tertiary)
        // accent button + secondary dot
        val by = cardB + (if (detailed) 8f else 5f) * d
        val bh = h - pad - by + (if (detailed) 4f else 2f) * d
        r.set(pad, by, pad + w * 0.36f, by + bh)
        p.color = pal.primary; cv.drawRoundRect(r, bh / 2, bh / 2, p)
        rr(pad + bh * 0.6f, by + bh * 0.42f, pad + w * 0.36f - bh * 0.6f, by + bh * 0.58f, bh * 0.1f, pal.onPrimary)
        r.set(pad + w * 0.39f, by, pad + w * 0.62f, by + bh)
        p.color = pal.glass; cv.drawRoundRect(r, bh / 2, bh / 2, p)
        s.color = pal.stroke; cv.drawRoundRect(r, bh / 2, bh / 2, s)
        p.color = pal.secondary; cv.drawCircle(w - pad - bh / 2, by + bh / 2, bh * 0.36f, p)
        p.color = pal.tertiary; cv.drawCircle(w - pad - bh * 1.5f, by + bh / 2, bh * 0.36f, p)
        // outer hairline
        s.color = pal.stroke; r.set(d / 2, d / 2, w - d / 2, h - d / 2); cv.drawRoundRect(r, corner, corner, s)
        canvas = null
    }
}
