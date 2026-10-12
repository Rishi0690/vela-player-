package com.hark.velaplayer

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves a captured video frame (v2.1 three-finger screenshot) to Pictures/VelaPlayer.
 * Android 10+: MediaStore with scoped storage, no permission needed.
 * Android 9 and older: a file in the public Pictures folder (needs WRITE_EXTERNAL_STORAGE), then a media scan.
 * Nothing is uploaded or shared.
 */
object FrameCapture {
    const val FOLDER = "VelaPlayer"

    fun fileName(title: String?, positionMs: Long): String {
        val clean = (title ?: "Video").substringBeforeLast('.').replace(Regex("[^A-Za-z0-9 _-]"), "").trim().take(40).ifEmpty { "Video" }
        val t = positionMs / 1000
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "${clean}_${t / 3600}h${(t % 3600) / 60}m${t % 60}s_$stamp.jpg"
    }

    /** Writes [bmp] as JPEG. Returns the saved uri, or throws with a readable message. Call off the main thread. */
    fun save(c: Context, bmp: Bitmap, name: String): Uri {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + FOLDER)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val r = c.contentResolver
            val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Couldn't create the image file")
            try {
                r.openOutputStream(uri)?.use { if (!bmp.compress(Bitmap.CompressFormat.JPEG, 95, it)) error("Couldn't encode the image") }
                    ?: error("Couldn't open the image file")
                r.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                return uri
            } catch (e: Exception) {
                try { r.delete(uri, null, null) } catch (_: Exception) { }
                throw e
            }
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER)
        if (!dir.exists() && !dir.mkdirs()) error("Couldn't create Pictures/$FOLDER")
        val f = File(dir, name)
        FileOutputStream(f).use { if (!bmp.compress(Bitmap.CompressFormat.JPEG, 95, it)) error("Couldn't encode the image") }
        MediaScannerConnection.scanFile(c.applicationContext, arrayOf(f.absolutePath), arrayOf("image/jpeg"), null)
        return Uri.fromFile(f)
    }
}
