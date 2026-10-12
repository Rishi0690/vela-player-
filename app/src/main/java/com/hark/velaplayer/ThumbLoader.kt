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
            val sys = systemThumb(ctx, uri, mediaId)
            val bmp = if (sys != null && !isDark(sys)) sys else (frameThumb(ctx, uri, durationMs) ?: sys)
            if (bmp != null) cache.put(key, bmp) else failed.add(key)
            main.post { if (iv.tag == key && bmp != null) show(iv, bmp) }
        }
    }

    private fun placeholder(iv: ImageView) { iv.setImageResource(R.drawable.ic_movie); iv.scaleType = ImageView.ScaleType.CENTER }
    private fun show(iv: ImageView, b: Bitmap) { iv.scaleType = ImageView.ScaleType.CENTER_CROP; iv.setImageBitmap(b) }

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
