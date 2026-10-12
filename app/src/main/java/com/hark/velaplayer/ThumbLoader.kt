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
            val bmp = systemThumb(ctx, uri, mediaId) ?: frameThumb(ctx, uri, durationMs)
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

    private fun frameThumb(ctx: Context, uri: Uri, durationMs: Long): Bitmap? {
        if (uri.scheme == "http" || uri.scheme == "https") return null
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            val dur = durationMs.takeIf { it > 0 }
                ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val atMs = if (dur > 0) (dur / 10).coerceIn(1_000L, 60_000L).coerceAtMost(dur) else 1_000L
            val frame = if (Build.VERSION.SDK_INT >= 27)
                r.getScaledFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 384, 216)
            else r.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            frame ?: r.frameAtTime
        } catch (_: Throwable) { null } finally {
            try { r.release() } catch (_: Throwable) { }
        }
    }
}
