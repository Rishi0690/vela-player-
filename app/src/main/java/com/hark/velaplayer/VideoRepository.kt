package com.hark.velaplayer

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class VideoItem(
    val id: Long, val uri: Uri, val name: String, val durationMs: Long, val size: Long,
    val dateAdded: Long, val width: Int, val height: Int, val path: String?
)

object VideoRepository {
    fun load(c: Context): List<VideoItem> {
        val out = ArrayList<VideoItem>()
        val proj = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT, MediaStore.Video.Media.DATA
        )
        try {
            c.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, null, null,
                MediaStore.Video.Media.DATE_ADDED + " DESC")?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(0)
                    out += VideoItem(
                        id, ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                        cur.getString(1) ?: "Video $id", cur.getLong(2), cur.getLong(3), cur.getLong(4),
                        cur.getInt(5), cur.getInt(6), cur.getString(7)
                    )
                }
            }
        } catch (e: Exception) { /* permission missing or provider error: return what we have */ }
        return out
    }
}
