package com.hark.velaplayer

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Loads video thumbnails off the main thread.
 * Tries the system thumbnail first, then grabs a frame from the file itself (some phones have no
 * generated thumbnails, and the first frame is often black, so it samples a bit into the video).
 */
object ThumbLoader {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val failed = Collections.synchronizedSet(HashSet<String>())
    private val exec = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun clearFailures() = failed.clear()

    fun load(iv: ImageView, item: VideoItem?) = load(iv, item?.uri, item?.id ?: -1L, item?.durationMs ?: 0L)

    fun load(iv: ImageView, uri: Uri?, mediaId: Long = -1L, durationMs: Long = 0L) {
        val key = uri?.toString()
        iv.tag = key
        if (uri == null || key == null) { placeholder(iv); return }
        cache.get(key)?.let { show(iv, it); return }
        placeholder(iv)
        if (failed.contains(key)) return
        val ctx = iv.context.applicationContext
        exec.execute {
            // The phone's own thumbnail is often an all-black first frame; if so, grab a real frame from inside the video.
            val bmp = loadBitmap(ctx, uri, mediaId, durationMs)
            if (bmp != null) cache.put(key, bmp) else failed.add(key)
            main.post {
                if (iv.tag == key && bmp != null) {
                    show(iv, bmp)
                    // Soft fade-in when a thumbnail arrives (cached ones show instantly).
                    iv.alpha = 0f
                    iv.animate().alpha(1f).setDuration(240).setInterpolator(Motion.emphasized).start()
                }
            }
        }
    }

    /** Async thumbnail for custom drawing; the callback runs on the main thread. */
    fun fetch(ctx: Context, uri: Uri, mediaId: Long, durationMs: Long, cb: (Bitmap?) -> Unit) {
        cache.get(uri.toString())?.let { cb(it); return }
        val app = ctx.applicationContext
        exec.execute { val b = bitmap(app, uri, mediaId, durationMs); main.post { cb(b) } }
    }

    /** Synchronous thumbnail (call off the main thread). Uses the same cache. */
    fun bitmap(ctx: Context, uri: Uri, mediaId: Long = -1L, durationMs: Long = 0L): Bitmap? {
        val key = uri.toString()
        cache.get(key)?.let { return it }
        if (failed.contains(key)) return null
        val b = loadBitmap(ctx.applicationContext, uri, mediaId, durationMs)
        if (b != null) cache.put(key, b) else failed.add(key)
        return b
    }

    private fun loadBitmap(ctx: Context, uri: Uri, mediaId: Long, durationMs: Long): Bitmap? {
        val sys = systemThumb(ctx, uri, mediaId)
        // "Smart thumbnails" (Settings): skip black system thumbnails and grab a real frame instead.
        if (!Prefs.smartThumbs(ctx)) return sys ?: frameThumb(ctx, uri, durationMs)
        return if (sys != null && !isDark(sys)) sys else (frameThumb(ctx, uri, durationMs) ?: sys)
    }

    fun clearCache() { cache.evictAll(); failed.clear() }

    private fun placeholder(iv: ImageView) { iv.imageTintList = null; iv.setImageResource(R.drawable.ic_movie); iv.scaleType = ImageView.ScaleType.CENTER }
    private fun show(iv: ImageView, b: Bitmap) { iv.imageTintList = null; iv.scaleType = ImageView.ScaleType.CENTER_CROP; iv.setImageBitmap(b) }

    // ---------------------------------------------------------------- playlist covers

    /**
     * Playlist cover: a 2×2 (or 2/3-piece) collage of the first videos' thumbnails, or the first one alone.
     * The cache key includes the videos, so the cover updates when the playlist changes.
     * With no thumbnails at all, a soft accent-colored placeholder is shown instead of a broken image.
     */
    fun loadCover(iv: ImageView, sources: List<Triple<Uri, Long, Long>>, collage: Boolean) {
        val picked = sources.take(1) // v2.5.1: always a single thumbnail (no 4-up collage)
        val key = "cover:" + collage + ":" + picked.joinToString("|") { it.first.toString() }
        iv.tag = key
        cache.get(key)?.let { show(iv, it); return }
        coverPlaceholder(iv)
        if (picked.isEmpty()) return
        val ctx = iv.context.applicationContext
        exec.execute {
            val bmps = picked.mapNotNull { bitmap(ctx, it.first, it.second, it.third) }
            val out = when { bmps.isEmpty() -> null; bmps.size == 1 -> bmps[0]; else -> compose(bmps) }
            if (out != null) cache.put(key, out)
            main.post {
                if (iv.tag == key && out != null) {
                    iv.background = null
                    show(iv, out)
                    iv.alpha = 0f
                    iv.animate().alpha(1f).setDuration(240).setInterpolator(Motion.emphasized).start()
                }
            }
        }
    }

    private fun coverPlaceholder(iv: ImageView) {
        val c = iv.context
        val p = ThemeManager.primary(c)
        val p2 = ThemeManager.attr(c, com.google.android.material.R.attr.colorPrimaryContainer)
        iv.background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(p, p2))
        iv.setImageResource(R.drawable.ic_playlist)
        iv.imageTintList = android.content.res.ColorStateList.valueOf(0xE6FFFFFF.toInt())
        iv.scaleType = ImageView.ScaleType.CENTER
    }

    private fun compose(b: List<Bitmap>): Bitmap {
        val w = 384; val h = 216; val g = 3
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(out)
        cv.drawColor(0xFF101010.toInt())
        val rects = when (b.size) {
            2 -> listOf(android.graphics.Rect(0, 0, w / 2 - g / 2, h), android.graphics.Rect(w / 2 + g / 2, 0, w, h))
            3 -> listOf(android.graphics.Rect(0, 0, w / 2 - g / 2, h), android.graphics.Rect(w / 2 + g / 2, 0, w, h / 2 - g / 2), android.graphics.Rect(w / 2 + g / 2, h / 2 + g / 2, w, h))
            else -> listOf(android.graphics.Rect(0, 0, w / 2 - g / 2, h / 2 - g / 2), android.graphics.Rect(w / 2 + g / 2, 0, w, h / 2 - g / 2),
                android.graphics.Rect(0, h / 2 + g / 2, w / 2 - g / 2, h), android.graphics.Rect(w / 2 + g / 2, h / 2 + g / 2, w, h))
        }
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        b.take(rects.size).forEachIndexed { i, src0 ->
            val src = if (Build.VERSION.SDK_INT >= 26 && src0.config == Bitmap.Config.HARDWARE) src0.copy(Bitmap.Config.ARGB_8888, false) else src0
            val dst = rects[i]
            // center-crop the thumbnail into its tile
            val sr = src.width.toFloat() / src.height; val dr = dst.width().toFloat() / dst.height()
            val crop = if (sr > dr) { val cw = (src.height * dr).toInt(); android.graphics.Rect((src.width - cw) / 2, 0, (src.width + cw) / 2, src.height) }
            else { val ch = (src.width / dr).toInt(); android.graphics.Rect(0, (src.height - ch) / 2, src.width, (src.height + ch) / 2) }
            cv.drawBitmap(src, crop, dst, paint)
        }
        return out
    }

    private fun systemThumb(ctx: Context, uri: Uri, id: Long): Bitmap? = try {
        if (uri.scheme != "content") null
        else if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(uri, Size(384, 216), null)
        else if (id >= 0) @Suppress("DEPRECATION") MediaStore.Video.Thumbnails.getThumbnail(ctx.contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
        else null
    } catch (_: Throwable) { null }

    /** True when the picture is (almost) all black — samples a grid of pixels. */
    private fun isDark(src: Bitmap): Boolean = try {
        // Hardware bitmaps can't be read pixel by pixel, so read a software copy.
        val b = if (Build.VERSION.SDK_INT >= 26 && src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val w = b.width; val h = b.height
        if (w <= 0 || h <= 0) true else {
            var sum = 0L; var n = 0
            for (yi in 1..9) for (xi in 1..9) {
                val c = b.getPixel(w * xi / 10, h * yi / 10)
                sum += (299 * ((c shr 16) and 0xFF) + 587 * ((c shr 8) and 0xFF) + 114 * (c and 0xFF)) / 1000
                n++
            }
            sum / n < 20
        }
    } catch (_: Throwable) { false }

    /** Tries a few points inside the video (10%, 25%, 50%) and keeps the first frame that isn't black. */
    private fun frameThumb(ctx: Context, uri: Uri, durationMs: Long): Bitmap? {
        if (uri.scheme == "http" || uri.scheme == "https") return null
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            val dur = durationMs.takeIf { it > 0 }
                ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val points = if (dur > 0) listOf(dur / 10, dur / 4, dur / 2).map { it.coerceAtLeast(500L) } else listOf(1_000L, 5_000L)
            var fallback: Bitmap? = null
            for (atMs in points) {
                val f = grab(r, atMs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: grab(r, atMs, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC) ?: continue
                if (!isDark(f)) return f
                if (fallback == null) fallback = f
            }
            fallback ?: r.frameAtTime
        } catch (_: Throwable) { null } finally {
            try { r.release() } catch (_: Throwable) { }
        }
    }

    private fun grab(r: MediaMetadataRetriever, atMs: Long, option: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(atMs * 1000, option, 384, 216)
        else r.getFrameAtTime(atMs * 1000, option)
    } catch (_: Throwable) { null }
}
