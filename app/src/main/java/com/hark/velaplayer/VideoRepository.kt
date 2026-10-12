package com.hark.velaplayer

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class VideoItem(
    val id: Long, val uri: Uri, val name: String, val durationMs: Long, val size: Long,
    val dateAdded: Long, val width: Int, val height: Int, val path: String?,
    val bucketId: String = "", val bucketName: String = ""
) {
    /** Folder path on disk when known (e.g. /storage/emulated/0/Movies). */
    val folderPath: String? get() = path?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() }
}

/** One folder (MediaStore bucket) that holds at least one playable video. */
data class VideoFolder(val id: String, val name: String, val path: String?, val videos: List<VideoItem>)

object VideoRepository {
    /**
     * All videos MediaStore knows about. With [respectHideTs] (the default) ".ts" files are left out while the
     * "Hide .ts videos" setting is on, and Hidden Vault videos are left out unless [respectVault] is false.
     * Files are never touched, only filtered from the list.
     */
    fun load(c: Context, respectHideTs: Boolean = true, respectVault: Boolean = true): List<VideoItem> {
        val hideTs = respectHideTs && Prefs.hideTs(c)
        // v2.2: videos in the Hidden Vault never appear in the normal library (only the vault asks for them).
        val hidden = if (respectVault) Vault.hiddenSet(c) else emptySet()
        val out = ArrayList<VideoItem>()
        val proj = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT, MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.BUCKET_ID, MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        )
        try {
            c.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, null, null,
                MediaStore.Video.Media.DATE_ADDED + " DESC")?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(0)
                    val path = cur.getString(7)
                    val bucketId = cur.getString(8) ?: path?.substringBeforeLast('/', "") ?: ""
                    val bucketName = cur.getString(9)
                        ?: path?.substringBeforeLast('/', "")?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
                        ?: "Internal storage"
                    val display = cur.getString(1)
                    if (hideTs && isTs(display, path)) continue
                    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    if (hidden.isNotEmpty() && uri.toString() in hidden) continue
                    out += VideoItem(
                        id, uri,
                        cur.getString(1) ?: "Video $id", cur.getLong(2), cur.getLong(3), cur.getLong(4),
                        cur.getInt(5), cur.getInt(6), path, bucketId, bucketName
                    )
                }
            }
        } catch (e: Exception) { /* permission missing or provider error: return what we have */ }
        return out
    }

    /**
     * A transport-stream file: its display name (or, when the name is missing, its path) ends in ".ts",
     * ignoring case. ".mts" and ".m2ts" are different extensions and are never matched.
     */
    fun isTs(displayName: String?, path: String?): Boolean =
        if (!displayName.isNullOrBlank()) Prefs.isTsName(displayName) else Prefs.isTsName(path)

    /** Same check for a saved entry (favorites, continue watching): by title, else by the uri's last path segment. */
    fun isTsEntry(e: Entry, item: VideoItem?): Boolean = when {
        item != null -> isTs(item.name, item.path)
        Prefs.isTsName(e.title) -> true
        else -> Prefs.isTsName(android.net.Uri.parse(e.uri).lastPathSegment)
    }

    /** Folders that are system/junk locations most people never want to browse. */
    fun isSystemFolder(f: VideoFolder): Boolean {
        val p = (f.path ?: "").lowercase()
        val n = f.name.lowercase()
        return n.startsWith(".") || p.contains("/android/data/") || p.contains("/android/media/") ||
            p.contains("/.thumbnails") || p.split('/').any { it.startsWith(".") && it.length > 1 } ||
            n == "cache" || n == "temp" || n == "tmp"
    }

    /** Groups the library by folder. Folders with no videos never appear because they come from the videos themselves. */
    fun folders(items: List<VideoItem>, hideSystem: Boolean): List<VideoFolder> {
        val list = items.groupBy { it.bucketId }.map { (id, vids) ->
            VideoFolder(id, vids.first().bucketName, vids.first().folderPath, vids)
        }
        return if (hideSystem) list.filterNot { isSystemFolder(it) } else list
    }
}
