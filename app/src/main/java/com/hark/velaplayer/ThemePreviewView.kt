package com.hark.velaplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * v2.12: a miniature (atmosphere gradient + glows, floating pill nav, level-2 glass panel, buttons) — was v2.11: a miniature of the app drawn in a theme's real colors: background, a glass card with a thumbnail and
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

    private val clip = Path()

    override fun onDraw(cv: Canvas) {
        val pal = palette ?: return
        canvas = cv
        val w = width.toFloat(); val h = height.toFloat()
        val corner = 14f * d
        // v2.12 atmosphere: vertical gradient + two soft glows, clipped to the card's rounded shape.
        clip.reset(); r.set(0f, 0f, w, h); clip.addRoundRect(r, corner, corner, Path.Direction.CW)
        cv.save(); cv.clipPath(clip)
        p.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(pal.atmoTop, pal.atmoMid, pal.atmoBottom), floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w, h, p)
        if (pal.glowA != 0) {
            p.shader = RadialGradient(w * 0.1f, h * 0.9f, maxOf(w, h) * 0.8f, pal.glowA, pal.glowA and 0x00FFFFFF, Shader.TileMode.CLAMP); cv.drawRect(0f, 0f, w, h, p)
        }
        if (pal.glowB != 0) {
            p.shader = RadialGradient(w * 0.95f, h * 0.05f, maxOf(w, h) * 0.6f, pal.glowB, pal.glowB and 0x00FFFFFF, Shader.TileMode.CLAMP); cv.drawRect(0f, 0f, w, h, p)
        }
        p.shader = null
        val pad = (if (detailed) 14f else 8f) * d
        var y = pad
        // toolbar: title line + accent dot
        val line = (if (detailed) 7f else 4.5f) * d
        rr(pad, y, pad + w * 0.36f, y + line, line / 2, pal.text)
        p.color = pal.textDim; cv.drawCircle(w - pad - line, y + line / 2, line * 0.7f, p)
        p.color = pal.textDim; cv.drawCircle(w - pad - line * 3.2f, y + line / 2, line * 0.7f, p)
        y += line + (if (detailed) 12f else 6f) * d
        // floating pill navigation: dark glass capsule + near-white selected pill + muted labels
        val navH = (if (detailed) 22f else 13f) * d
        val navW = w * (if (detailed) 0.72f else 0.84f)
        val navL = (w - navW) / 2
        r.set(navL, y, navL + navW, y + navH)
        p.color = pal.navContainer; cv.drawRoundRect(r, navH / 2, navH / 2, p)
        s.strokeWidth = d; s.color = pal.stroke; cv.drawRoundRect(r, navH / 2, navH / 2, s)
        val inset = 2.2f * d
        val seg = (navW - inset * 2) / 4f
        r.set(navL + inset, y + inset, navL + inset + seg * 1.1f, y + navH - inset)
        p.color = pal.navSelected; cv.drawRoundRect(r, (navH - inset * 2) / 2, (navH - inset * 2) / 2, p)
        val lh = navH * 0.18f
        rr(navL + inset + seg * 0.25f, y + navH / 2 - lh, navL + inset + seg * 0.85f, y + navH / 2 + lh, lh, pal.navOnSelected)
        for (k in 1..3) {
            val sx = navL + inset + seg * (k + 0.1f)
            rr(sx + seg * 0.22f, y + navH / 2 - lh, sx + seg * 0.78f, y + navH / 2 + lh, lh, pal.navText)
        }
        y += navH + (if (detailed) 10f else 6f) * d
        // large level-2 glass panel with a thumbnail row
        val cardB = h - pad - (if (detailed) 30f else 18f) * d
        val cr = (if (detailed) 18f else 10f) * d
        r.set(pad, y, w - pad, cardB)
        p.color = pal.elevated; cv.drawRoundRect(r, cr, cr, p)
        p.shader = LinearGradient(0f, y, 0f, y + (cardB - y) * 0.5f, pal.highlight, pal.highlight and 0x00FFFFFF, Shader.TileMode.CLAMP)
        cv.drawRoundRect(r, cr, cr, p); p.shader = null
        s.color = pal.stroke; cv.drawRoundRect(r, cr, cr, s)
        // thumbnail inside card
        val inner = (if (detailed) 10f else 6f) * d
        val th = cardB - y - inner * 2
        val tw = th * 1.6f
        r.set(pad + inner, y + inner, pad + inner + tw, y + inner + th)
        p.shader = LinearGradient(r.left, r.top, r.right, r.bottom, pal.surface, pal.atmoBottom, Shader.TileMode.CLAMP)
        cv.drawRoundRect(r, cr * 0.6f, cr * 0.6f, p); p.shader = null
        val cx = pad + inner + tw / 2; val cy = y + inner + th / 2; val ts = th * 0.18f
        tri.reset(); tri.moveTo(cx - ts * 0.7f, cy - ts); tri.lineTo(cx + ts, cy); tri.lineTo(cx - ts * 0.7f, cy + ts); tri.close()
        p.color = pal.text; p.alpha = 200; cv.drawPath(tri, p); p.alpha = 255
        rr(pad + inner, y + inner + th - 2.5f * d, pad + inner + tw * 0.55f, y + inner + th, 1.5f * d, pal.primary)
        // text lines
        val tx = pad + inner * 2 + tw
        val tl = (if (detailed) 6f else 4f) * d
        rr(tx, y + inner + th * 0.15f, w - pad - inner * 2, y + inner + th * 0.15f + tl, tl / 2, pal.text)
        rr(tx, y + inner + th * 0.15f + tl * 2.2f, tx + (w - pad - inner - tx) * 0.55f, y + inner + th * 0.15f + tl * 3.2f, tl / 2, pal.textDim)
        if (detailed) rr(tx, y + inner + th * 0.15f + tl * 4.4f, tx + (w - pad - inner - tx) * 0.35f, y + inner + th * 0.15f + tl * 5.4f, tl / 2, pal.tertiary)
        // accent button + glass button + secondary/tertiary dots
        val by = cardB + (if (detailed) 8f else 4f) * d
        val bh = h - pad - by + (if (detailed) 4f else 2f) * d
        r.set(pad, by, pad + w * 0.34f, by + bh)
        p.color = pal.primary; cv.drawRoundRect(r, bh / 2, bh / 2, p)
        rr(pad + bh * 0.6f, by + bh * 0.42f, pad + w * 0.34f - bh * 0.6f, by + bh * 0.58f, bh * 0.1f, pal.onPrimary)
        r.set(pad + w * 0.37f, by, pad + w * 0.6f, by + bh)
        p.color = pal.glass; cv.drawRoundRect(r, bh / 2, bh / 2, p)
        s.color = pal.stroke; cv.drawRoundRect(r, bh / 2, bh / 2, s)
        p.color = pal.secondary; cv.drawCircle(w - pad - bh / 2, by + bh / 2, bh * 0.34f, p)
        p.color = pal.tertiary; cv.drawCircle(w - pad - bh * 1.5f, by + bh / 2, bh * 0.34f, p)
        cv.restore()
        // outer hairline
        s.color = pal.stroke; r.set(d / 2, d / 2, w - d / 2, h - d / 2); cv.drawRoundRect(r, corner, corner, s)
        canvas = null
    }
}
