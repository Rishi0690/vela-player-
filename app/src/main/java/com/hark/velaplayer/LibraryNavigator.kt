package com.hark.velaplayer

import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager
import java.io.File

/**
 * Library-order navigation for the player (v2.1).
 *
 * When a video is played from a folder, All videos or Continue Watching, Next/Previous follow the library
 * itself: the rest of the current folder (in the folder's sort order, as the library screen shows it), then
 * the first video of the next folder, and backwards the same way. Continue Watching is only a way to resume,
 * never the source of the order.
 *
 * Read-only: it only queries [VideoRepository]; nothing on disk is changed. Run [build] off the main thread.
 */
object LibraryNavigator {

    /** The library-ordered queue around the current video. [index] is where the current video sits in [items]. */
    class Window(val items: List<VideoItem>, val index: Int)

    /** How many videos to keep on each side of the current one (keeps the player queue light on huge libraries). */
    private const val SPAN = 300

    /** Same key the library setting uses ("Hide .ts videos"); default ON. */
    fun hideTs(c: Context): Boolean = PreferenceManager.getDefaultSharedPreferences(c).getBoolean("hide_ts", true)

    private fun isTs(v: VideoItem): Boolean =
        v.name.lowercase().endsWith(".ts") || (v.path?.lowercase()?.endsWith(".ts") == true)

    /** Folder order exactly as the library's folder list (Settings > folder sort). */
    private fun sortFolders(c: Context, folders: List<VideoFolder>): List<VideoFolder> = when (Prefs.folderSort(c)) {
        "count" -> folders.sortedByDescending { it.videos.size }
        "recent" -> folders.sortedByDescending { f -> f.videos.maxOfOrNull { it.dateAdded } ?: 0L }
        else -> folders.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    /** Video order inside a folder, matching the library's sort menu ("library_sort", newest first by default). */
    private fun sortVideos(c: Context, videos: List<VideoItem>): List<VideoItem> =
        when (Prefs.sp(c).getString("library_sort", "date_desc")) {
            "name_asc" -> videos.sortedBy { it.name.lowercase() }
            "name_desc" -> videos.sortedByDescending { it.name.lowercase() }
            "date_asc" -> videos.sortedBy { it.dateAdded }
            "duration_desc" -> videos.sortedByDescending { it.durationMs }
            "duration_asc" -> videos.sortedBy { it.durationMs }
            "size_desc" -> videos.sortedByDescending { it.size }
            "size_asc" -> videos.sortedBy { it.size }
            else -> videos.sortedByDescending { it.dateAdded }
        }

    /** Finds the library entry for a player media id (content uri, or file:// path). */
    fun find(items: List<VideoItem>, mediaId: String): VideoItem? {
        items.firstOrNull { it.uri.toString() == mediaId }?.let { return it }
        val u = Uri.parse(mediaId)
        val path = when (u.scheme) { "file" -> u.path; null -> mediaId.takeIf { it.startsWith("/") }; else -> null }
        if (path != null) items.firstOrNull { it.path == path }?.let { return it }
        // content://media/external/video/media/123 vs other MediaStore volume spellings: compare the id.
        if (u.scheme == "content" && u.authority == "media") {
            val id = u.lastPathSegment?.toLongOrNull()
            if (id != null) items.firstOrNull { it.id == id }?.let { return it }
        }
        return null
    }

    /** Skips files that are gone or empty. Unknown paths (no DATA column) are kept and handled by the player. */
    private fun playable(v: VideoItem): Boolean {
        if (v.size == 0L && v.durationMs == 0L) return false
        val p = v.path ?: return true
        return try { File(p).exists() } catch (_: Exception) { true }
    }

    /**
     * Builds the library-ordered queue around [mediaId]. Returns null when the video isn't in the library
     * (stream, file from another app, no permission) so the caller keeps the queue it was given.
     */
    fun build(c: Context, mediaId: String): Window? {
        val all = VideoRepository.load(c)
        if (all.isEmpty()) return null
        val current = find(all, mediaId) ?: return null
        val hideTs = hideTs(c)
        // v2.2: Hidden Vault videos never show up in an ordinary queue (only the playing one is kept, if at all).
        val hidden = Vault.hiddenSet(c)
        var folders = VideoRepository.folders(all, Prefs.hideSystemFolders(c))
        // A video in a hidden system folder still navigates through its own folder.
        if (folders.none { it.id == current.bucketId }) folders = VideoRepository.folders(all, false)

        val flat = ArrayList<VideoItem>(all.size)
        for (f in sortFolders(c, folders)) {
            for (v in sortVideos(c, f.videos)) {
                // The video playing now always stays, even if it is a .ts file.
                if (v.id == current.id || (!(hideTs && isTs(v)) && v.uri.toString() !in hidden)) flat += v
            }
        }
        val idx = flat.indexOfFirst { it.id == current.id }
        if (idx < 0) return null
        val from = (idx - SPAN).coerceAtLeast(0)
        val to = (idx + SPAN + 1).coerceAtMost(flat.size)
        val before = flat.subList(from, idx).filter { playable(it) }
        val after = flat.subList(idx + 1, to).filter { playable(it) }
        // Each video appears once (MediaStore can't return duplicates, but be safe against repeated ids).
        val seen = HashSet<Long>()
        val out = ArrayList<VideoItem>(before.size + after.size + 1)
        before.forEach { if (it.id != current.id && seen.add(it.id)) out += it }
        val curIndex = out.size
        out += flat[idx]; seen.add(current.id)
        after.forEach { if (seen.add(it.id)) out += it }
        return Window(out, curIndex)
    }
}
