package com.hark.velaplayer

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.LruCache
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player

/** One video as a widget shows it (real data only: uri, saved title, real duration and resume position). */
data class WItem(val uri: String, val title: String, val mediaId: Long, val durationMs: Long, val positionMs: Long, val dateAdded: Long = 0L) {
    val progress: Float get() = if (durationMs > 0 && positionMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * What the Now Playing widget shows. Read on the main thread (ExoPlayer is single-threaded), rendered elsewhere.
 * LIVE = the real player exists; RESUME = no player, but the last session can be resumed; EMPTY = nothing yet.
 */
data class NowState(
    val mode: Int, val uri: String?, val title: String, val positionMs: Long, val durationMs: Long,
    val playing: Boolean, val hasPrev: Boolean, val hasNext: Boolean, val private: Boolean,
    val index: Int = 0, val count: Int = 0, val speed: Float = 1f, val at: Long = SystemClock.elapsedRealtime(),
) {
    companion object {
        const val EMPTY = 0; const val LIVE = 1; const val RESUME = 2

        /** Main thread only. */
        fun snapshot(c: Context): NowState {
            val p = PlayerHolder.player
            val item = try { p?.currentMediaItem } catch (_: Exception) { null }
            if (p != null && item != null && p.mediaItemCount > 0) {
                val id = item.mediaId
                val metaTitle = item.mediaMetadata.title?.toString()
                val priv = metaTitle == FocusTracker.PRIVATE_TITLE || Vault.isHidden(c, id)
                val title = if (priv) FocusTracker.PRIVATE_TITLE else metaTitle?.takeIf { it.isNotBlank() } ?: (Uri.parse(id).lastPathSegment ?: "Video")
                val dur = p.duration.let { if (it == C.TIME_UNSET || it < 0) 0L else it }
                val playing = p.isPlaying || (p.playWhenReady && p.playbackState == Player.STATE_BUFFERING)
                return NowState(LIVE, id, title, p.currentPosition.coerceAtLeast(0L), dur, playing,
                    p.hasPreviousMediaItem(), p.hasNextMediaItem(), priv, p.currentMediaItemIndex, p.mediaItemCount,
                    p.playbackParameters.speed)
            }
            val s = PlayerSession.load(c)
            val cur = s?.current
            if (s != null && cur != null && s.source != Vault.SOURCE_VAULT && !Vault.isHidden(c, cur.uri)) {
                val pos = s.positionMs.takeIf { it > 0 } ?: Store.position(c, cur.uri)
                val title = cur.title.ifBlank { Uri.parse(cur.uri).lastPathSegment ?: "Video" }
                return NowState(RESUME, cur.uri, title, pos, Store.duration(c, cur.uri), false, false, false, false, s.index, s.entries.size)
            }
            return NowState(EMPTY, null, "Nothing playing", 0L, 0L, false, false, false, false)
        }
    }
}

/** Real library data for widgets (Store, PlayerSession, MediaStore). Call off the main thread. */
object WidgetData {
    fun hasVideoPermission(c: Context): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(c, perm) == PackageManager.PERMISSION_GRANTED) return true
        return Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(c, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
    }

    fun mediaId(uri: String): Long = try {
        if (uri.startsWith("content://media/")) ContentUris.parseId(Uri.parse(uri)) else -1L
    } catch (_: Exception) { -1L }

    /** A MediaStore video that no longer exists (deleted or moved). Unknown (no permission, streams, files) = exists. */
    private fun missing(c: Context, uri: String, perm: Boolean): Boolean {
        if (!perm || !uri.startsWith("content://media/")) return false
        return try {
            c.contentResolver.query(Uri.parse(uri), arrayOf(MediaStore.Video.Media._ID), null, null, null)?.use { it.count == 0 } ?: false
        } catch (_: SecurityException) { false } catch (_: Exception) { true }
    }

    private fun visible(c: Context, entries: List<Entry>, limit: Int): List<WItem> {
        val perm = hasVideoPermission(c)
        val hideTs = Prefs.hideTs(c)
        val hidden = Vault.hiddenSet(c)
        val out = ArrayList<WItem>()
        for (e in entries) {
            if (out.size >= limit) break
            if (e.uri in hidden) continue // Hidden Vault videos never reach a widget
            if (hideTs && VideoRepository.isTsEntry(e, null)) continue
            if (missing(c, e.uri, perm)) continue
            out += WItem(e.uri, e.title.ifBlank { Uri.parse(e.uri).lastPathSegment ?: "Video" }, mediaId(e.uri),
                Store.duration(c, e.uri), Store.position(c, e.uri))
        }
        return out
    }

    fun continueItems(c: Context, limit: Int = 20) = visible(c, Store.continueList(c), limit)

    /** "favorites" or "playlist:<name>". A deleted playlist reads as empty (the widget then says so). */
    fun collection(c: Context, source: String): Pair<String, List<Entry>?> =
        if (source.startsWith("playlist:")) { val n = source.removePrefix("playlist:"); n to Store.playlists(c)[n] }
        else "Favorites" to Store.favorites(c)

    fun collectionItems(c: Context, source: String, limit: Int = 50): List<WItem> {
        val list = collection(c, source).second ?: return emptyList()
        val durs = libraryDurations(c)
        return visible(c, list, limit).map { if (it.durationMs <= 0) it.copy(durationMs = durs[it.uri] ?: 0L) else it }
    }

    private fun libraryDurations(c: Context): Map<String, Long> =
        if (!hasVideoPermission(c)) emptyMap() else try { VideoRepository.load(c).associate { it.uri.toString() to it.durationMs } } catch (_: Exception) { emptyMap() }

    fun recentItems(c: Context, limit: Int = 24): List<WItem> {
        if (!hasVideoPermission(c)) return emptyList()
        return VideoRepository.load(c).take(limit).map {
            WItem(it.uri.toString(), it.name, it.id, it.durationMs, Store.position(c, it.uri.toString()), it.dateAdded)
        }
    }

    fun items(c: Context, kind: WidgetKind, cfg: WidgetConfig, limit: Int): List<WItem> = when (kind) {
        WidgetKind.CONTINUE -> continueItems(c, limit)
        WidgetKind.COLLECTION -> collectionItems(c, cfg.source, limit)
        WidgetKind.LIBRARY -> recentItems(c, limit)
        else -> emptyList()
    }

    fun ago(addedSec: Long): String {
        if (addedSec <= 0) return ""
        val d = (System.currentTimeMillis() / 1000 - addedSec) / 86400
        return when { d <= 0 -> "Today"; d == 1L -> "Yesterday"; d < 7 -> "$d days ago"; d < 30 -> "${d / 7} wk ago"; d < 365 -> "${d / 30} mo ago"; else -> "${d / 365} yr ago" }
    }
}

/**
 * Widget bitmaps, kept small (well under the binder limit): rounded thumbnails with a baked progress line or duration,
 * Now Playing artwork with its scrim, progress bars, and themed placeholders built from the Vela artwork.
 * Real video thumbnails always win; the artwork only fills in when a video has no picture.
 */
object WidgetArt {
    private val cache = object : LruCache<String, Bitmap>(6 * 1024) { override fun sizeOf(k: String, v: Bitmap) = v.byteCount / 1024 }
    @Volatile private var forms: Bitmap? = null

    fun density(c: Context) = c.resources.displayMetrics.density.coerceAtMost(2.5f)

    private fun soft(b: Bitmap): Bitmap =
        if (Build.VERSION.SDK_INT >= 26 && b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b

    fun thumb(c: Context, uri: String, mediaId: Long, durationMs: Long): Bitmap? = try {
        ThumbLoader.bitmap(c, Uri.parse(uri), mediaId, durationMs)?.let { soft(it) }
    } catch (_: Throwable) { null }

    private fun artForms(c: Context): Bitmap? = forms ?: try {
        BitmapFactory.decodeResource(c.resources, R.drawable.art_luminous_forms)?.also { forms = it }
    } catch (_: Throwable) { null }

    private fun drawCrop(cv: Canvas, src: Bitmap, dst: RectF) {
        val sr = src.width.toFloat() / src.height; val dr = dst.width() / dst.height()
        val crop = if (sr > dr) { val cw = (src.height * dr).toInt(); Rect((src.width - cw) / 2, 0, (src.width + cw) / 2, src.height) }
        else { val ch = (src.width / dr).toInt(); Rect(0, (src.height - ch) / 2, src.width, (src.height + ch) / 2) }
        cv.drawBitmap(src, crop, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    }

    private fun drawForms(c: Context, cv: Canvas, r: RectF, pal: WidgetPalette) {
        val f = artForms(c) ?: return
        val ap = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        ap.colorFilter = PorterDuffColorFilter(if (pal.dark) Atmosphere.mix(pal.accent, Color.WHITE, 0.2f) else Atmosphere.mix(pal.accent, Color.BLACK, 0.15f), PorterDuff.Mode.SRC_IN)
        val sr = f.width.toFloat() / f.height; val dr = r.width() / r.height()
        val crop = if (sr > dr) { val cw = (f.height * dr).toInt(); Rect((f.width - cw) / 2, 0, (f.width + cw) / 2, f.height) }
        else { val ch = (f.width / dr).toInt(); Rect(0, (f.height - ch) / 2, f.width, (f.height + ch) / 2) }
        cv.drawBitmap(f, crop, r, ap)
    }

    private fun placeholderInto(c: Context, cv: Canvas, r: RectF, pal: WidgetPalette) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val base = if (pal.dark) pal.top else Atmosphere.mix(pal.top, pal.accent, 0.08f)
        p.shader = LinearGradient(r.left, r.top, r.right, r.bottom, base, Atmosphere.mix(pal.bottom, pal.accent, if (pal.dark) 0.28f else 0.20f), Shader.TileMode.CLAMP)
        cv.drawRect(r, p)
        p.shader = RadialGradient(r.left + r.width() * 0.72f, r.top + r.height() * 0.25f, maxOf(r.width(), r.height()) * 0.6f,
            Atmosphere.alpha(pal.glow, 0x55), Atmosphere.alpha(pal.glow, 0), Shader.TileMode.CLAMP)
        cv.drawRect(r, p)
        drawForms(c, cv, r, pal)
    }

    /**
     * Rounded picture of [w]×[h] px: the real thumbnail (center-cropped) or the themed placeholder, optional bottom
     * [scrim] for text, an optional progress line along the bottom and an optional duration pill.
     */
    fun card(c: Context, src: Bitmap?, w: Int, h: Int, radius: Float, pal: WidgetPalette,
             progress: Float = 0f, scrim: Boolean = false, duration: String? = null, privateIcon: Boolean = false): Bitmap {
        val out = Bitmap.createBitmap(w.coerceAtLeast(8), h.coerceAtLeast(8), Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        val r = RectF(0f, 0f, out.width.toFloat(), out.height.toFloat())
        val layer = cv.saveLayer(r, null)
        val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        cv.drawRoundRect(r, radius, radius, mask)
        val inP = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) }
        val tmp = Bitmap.createBitmap(out.width, out.height, Bitmap.Config.ARGB_8888)
        val tc = Canvas(tmp)
        if (src != null && !privateIcon) drawCrop(tc, src, r) else placeholderInto(c, tc, r, pal)
        if (privateIcon) {
            val d = ContextCompat.getDrawable(c, R.drawable.wgi_private)?.mutate()
            if (d != null) {
                val s = (minOf(r.width(), r.height()) * 0.28f).toInt()
                d.setBounds(((r.width() - s) / 2).toInt(), ((r.height() - s) / 2).toInt(), ((r.width() + s) / 2).toInt(), ((r.height() + s) / 2).toInt())
                d.setTint(Atmosphere.alpha(pal.text, 0xCC)); d.draw(tc)
            }
        }
        if (scrim) {
            val sp = Paint().apply { shader = LinearGradient(0f, r.height() * 0.35f, 0f, r.height(), 0x00000000, 0xD9000000.toInt(), Shader.TileMode.CLAMP) }
            tc.drawRect(r, sp)
        }
        // soft inner sheen on the top edge, like the rest of the glass
        val sh = Paint().apply { shader = LinearGradient(0f, 0f, 0f, r.height() * 0.3f, 0x1FFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP) }
        tc.drawRect(r, sh)
        cv.drawBitmap(tmp, 0f, 0f, inP)
        tmp.recycle()
        cv.restoreToCount(layer)
        if (progress > 0f) {
            val th = (out.height * 0.045f).coerceIn(3f, 7f)
            val y = r.bottom - th
            val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59FFFFFF }
            cv.save(); cv.clipPath(android.graphics.Path().apply { addRoundRect(r, radius, radius, android.graphics.Path.Direction.CW) })
            cv.drawRect(r.left, y, r.right, r.bottom, track)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.accent }
            cv.drawRect(r.left, y, r.left + r.width() * progress, r.bottom, fill)
            cv.restore()
        }
        if (duration != null) {
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = (out.height * 0.13f).coerceIn(14f, 30f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            val tw = tp.measureText(duration)
            val padH = tp.textSize * 0.45f; val padV = tp.textSize * 0.25f
            val m = (out.height * 0.06f).coerceAtLeast(4f) + if (progress > 0f) 4f else 0f
            val pill = RectF(r.right - m - tw - padH * 2, r.bottom - m - tp.textSize - padV * 2, r.right - m, r.bottom - m)
            cv.drawRoundRect(pill, pill.height() / 2, pill.height() / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xA6000000.toInt() })
            cv.drawText(duration, pill.left + padH, pill.bottom - padV - tp.descent() * 0.6f, tp)
        }
        return out
    }

    /** Progress bar bitmap: track, accent fill with a soft glow, bright end point. [wPx]×[hPx] (the bar is a third of the height). */
    fun progress(wPx: Int, hPx: Int, frac: Float, pal: WidgetPalette): Bitmap {
        val w = wPx.coerceIn(40, 900); val h = hPx.coerceIn(6, 40)
        val key = "p:$w:$h:${(frac * 400).toInt()}:${pal.accent}:${pal.track}"
        cache.get(key)?.let { return it }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        val bh = h / 3f; val cy = h / 2f; val pad = h / 2f
        val track = RectF(pad, cy - bh / 2, w - pad, cy + bh / 2)
        cv.drawRoundRect(track, bh / 2, bh / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.track })
        val f = frac.coerceIn(0f, 1f)
        if (f > 0f) {
            val end = track.left + track.width() * f
            val fr = RectF(track.left, track.top, end.coerceAtLeast(track.left + bh), track.bottom)
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Atmosphere.alpha(pal.accent, 0x99); maskFilter = BlurMaskFilter(bh * 1.1f, BlurMaskFilter.Blur.NORMAL) }
            cv.drawRoundRect(fr, bh / 2, bh / 2, glow)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(track.left, 0f, end, 0f, Atmosphere.mix(pal.accent, if (pal.dark) Color.BLACK else Color.WHITE, 0.25f), pal.accent, Shader.TileMode.CLAMP)
            }
            cv.drawRoundRect(fr, bh / 2, bh / 2, fill)
            cv.drawCircle(fr.right - bh / 2, cy, bh * 0.95f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Atmosphere.mix(pal.accent, Color.WHITE, 0.55f) })
        }
        cache.put(key, out)
        return out
    }

    /** Now Playing artwork, cached per video/size/palette so the 15 s progress ticks never re-decode it. */
    fun nowArt(c: Context, s: NowState, w: Int, h: Int, radius: Float, pal: WidgetPalette, scrim: Boolean): Bitmap {
        val key = "np:${s.uri}:${s.private}:$w:$h:${radius.toInt()}:$scrim:${pal.accent}:${pal.top}:${pal.dark}"
        cache.get(key)?.let { return it }
        val src = if (s.uri != null && !s.private && s.mode != NowState.EMPTY) thumb(c, s.uri, WidgetData.mediaId(s.uri), s.durationMs) else null
        val b = card(c, src, w, h, radius, pal, scrim = scrim, privateIcon = s.private)
        cache.put(key, b)
        return b
    }

    fun clear() = cache.evictAll()
}
