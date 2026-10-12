package com.hark.velaplayer

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.util.concurrent.Executors

object ThumbLoader {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 12).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val exec = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun load(iv: ImageView, item: VideoItem?) {
        val key = item?.uri?.toString()
        iv.tag = key
        if (item == null || key == null) { iv.setImageResource(R.drawable.ic_movie); iv.scaleType = ImageView.ScaleType.CENTER; return }
        cache.get(key)?.let { iv.scaleType = ImageView.ScaleType.CENTER_CROP; iv.setImageBitmap(it); return }
        iv.setImageResource(R.drawable.ic_movie); iv.scaleType = ImageView.ScaleType.CENTER
        val ctx = iv.context.applicationContext
        exec.execute {
            val bmp: Bitmap? = try {
                if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(item.uri, Size(320, 180), null)
                else @Suppress("DEPRECATION") MediaStore.Video.Thumbnails.getThumbnail(ctx.contentResolver, item.id, MediaStore.Video.Thumbnails.MINI_KIND, null)
            } catch (e: Exception) { null }
            if (bmp != null) cache.put(key, bmp)
            main.post { if (iv.tag == key && bmp != null) { iv.scaleType = ImageView.ScaleType.CENTER_CROP; iv.setImageBitmap(bmp) } }
        }
    }
}
