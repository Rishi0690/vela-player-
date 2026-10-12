package com.hark.velaplayer

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class Entry(val uri: String, val title: String)

/** Local library state: resume positions, favorites and playlists. Never touches the media files themselves. */
object Store {
    private fun sp(c: Context) = c.getSharedPreferences("vela_store", Context.MODE_PRIVATE)

    // ---- resume positions ----
    fun savePosition(c: Context, uri: String, title: String, pos: Long, dur: Long) {
        val e = sp(c).edit()
        if (dur > 0 && (pos >= dur - 10_000 || pos.toFloat() / dur > 0.97f)) {
            e.remove("pos:$uri").putBoolean("watched:$uri", true)
        } else if (pos > 3_000) {
            e.putLong("pos:$uri", pos).putLong("dur:$uri", dur).putString("title:$uri", title)
                .putLong("time:$uri", System.currentTimeMillis())
        }
        e.apply()
    }
    fun clearPosition(c: Context, uri: String) = sp(c).edit().remove("pos:$uri").apply()

    // ---- watched history (a video counts as watched once it was played to the end) ----
    fun markWatched(c: Context, uri: String) = sp(c).edit().remove("pos:$uri").putBoolean("watched:$uri", true).apply()
    fun setWatched(c: Context, uri: String, watched: Boolean) {
        val e = sp(c).edit()
        if (watched) e.remove("pos:$uri").putBoolean("watched:$uri", true) else e.remove("watched:$uri")
        e.apply()
    }
    fun watchedSet(c: Context): Set<String> =
        sp(c).all.keys.filter { it.startsWith("watched:") }.map { it.removePrefix("watched:") }.toSet()
    fun inProgressSet(c: Context): Set<String> =
        sp(c).all.keys.filter { it.startsWith("pos:") }.map { it.removePrefix("pos:") }.toSet()
    fun lastPlayed(c: Context, uri: String): Long = sp(c).getLong("time:$uri", 0L)
    fun position(c: Context, uri: String): Long = sp(c).getLong("pos:$uri", 0L)
    fun duration(c: Context, uri: String): Long = sp(c).getLong("dur:$uri", 0L)
    fun continueList(c: Context): List<Entry> {
        val all = sp(c).all
        return all.keys.filter { it.startsWith("pos:") }.map { it.removePrefix("pos:") }
            .sortedByDescending { (all["time:$it"] as? Long) ?: 0L }
            .map { Entry(it, (all["title:$it"] as? String) ?: (Uri.parse(it).lastPathSegment ?: it)) }
    }

    // ---- favorites ----
    fun favorites(c: Context): List<Entry> = read(sp(c).getString("favorites", "[]") ?: "[]")
    fun isFavorite(c: Context, uri: String) = favorites(c).any { it.uri == uri }
    fun toggleFavorite(c: Context, e: Entry): Boolean {
        val list = favorites(c).toMutableList()
        val removed = list.removeAll { it.uri == e.uri }
        if (!removed) list.add(0, e)
        sp(c).edit().putString("favorites", write(list)).apply()
        return !removed
    }

    // ---- playlists ----
    fun playlists(c: Context): LinkedHashMap<String, MutableList<Entry>> {
        val o = try { JSONObject(sp(c).getString("playlists", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        val m = LinkedHashMap<String, MutableList<Entry>>()
        o.keys().forEach { k -> m[k] = read(o.getJSONArray(k).toString()).toMutableList() }
        return m
    }
    private fun savePlaylists(c: Context, m: Map<String, List<Entry>>) {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, JSONArray(write(v))) }
        sp(c).edit().putString("playlists", o.toString()).apply()
    }
    fun createPlaylist(c: Context, name: String): Boolean {
        val m = playlists(c); if (m.containsKey(name)) return false
        m[name] = mutableListOf(); savePlaylists(c, m); return true
    }
    fun deletePlaylist(c: Context, name: String) { val m = playlists(c); m.remove(name); savePlaylists(c, m) }
    fun renamePlaylist(c: Context, old: String, new: String): Boolean {
        val m = playlists(c); if (m.containsKey(new)) return false
        val r = LinkedHashMap<String, MutableList<Entry>>()
        m.forEach { (k, v) -> r[if (k == old) new else k] = v }
        savePlaylists(c, r); return true
    }
    fun addToPlaylist(c: Context, name: String, e: Entry): Boolean {
        val m = playlists(c); val l = m.getOrPut(name) { mutableListOf() }
        if (l.any { it.uri == e.uri }) return false
        if (Prefs.playlistAddAt(c) == "start") l.add(0, e) else l.add(e)
        savePlaylists(c, m); return true
    }
    /**
     * Adds many videos at once, keeping their order. Videos already in the playlist (or picked twice) are skipped.
     * [replace] = true clears the playlist first (only when the user explicitly chose to replace it).
     * Returns (added, skipped).
     */
    fun addManyToPlaylist(c: Context, name: String, entries: List<Entry>, atStart: Boolean = false, replace: Boolean = false): Pair<Int, Int> {
        val m = playlists(c); val l = m.getOrPut(name) { mutableListOf() }
        if (replace) l.clear()
        val have = l.map { it.uri }.toHashSet()
        val fresh = ArrayList<Entry>()
        var skipped = 0
        entries.forEach { e -> if (have.add(e.uri)) fresh += e else skipped++ }
        if (atStart) l.addAll(0, fresh) else l.addAll(fresh)
        savePlaylists(c, m)
        return fresh.size to skipped
    }
    /** Removes entries from the playlist only. The video files are never deleted. */
    fun removeManyFromPlaylist(c: Context, name: String, uris: Collection<String>) {
        val set = uris.toSet(); val m = playlists(c); m[name]?.removeAll { it.uri in set }; savePlaylists(c, m)
    }
    fun setFavorites(c: Context, entries: List<Entry>, favorite: Boolean) {
        val list = favorites(c).toMutableList()
        val uris = entries.map { it.uri }.toSet()
        list.removeAll { it.uri in uris }
        if (favorite) list.addAll(0, entries.distinctBy { it.uri })
        sp(c).edit().putString("favorites", write(list)).apply()
    }
    fun setWatchedMany(c: Context, uris: Collection<String>, watched: Boolean) {
        val e = sp(c).edit()
        uris.forEach { u -> if (watched) e.remove("pos:$u").putBoolean("watched:$u", true) else e.remove("watched:$u") }
        e.apply()
    }

    /** Removes the entry from the playlist only. The video file is never deleted. */
    fun removeFromPlaylist(c: Context, name: String, uri: String) {
        val m = playlists(c); m[name]?.removeAll { it.uri == uri }; savePlaylists(c, m)
    }

    // ---- file changes made from the library ----
    /** A video was renamed: update the saved title everywhere it appears. */
    fun renameTitle(c: Context, uri: String, title: String) {
        val e = sp(c).edit()
        if (sp(c).contains("title:$uri")) e.putString("title:$uri", title)
        e.putString("favorites", write(favorites(c).map { if (it.uri == uri) Entry(uri, title) else it }))
        e.apply()
        val m = playlists(c); m.values.forEach { l -> l.replaceAll { if (it.uri == uri) Entry(uri, title) else it } }; savePlaylists(c, m)
    }
    /** A video file was deleted: forget its progress, watched mark, favorite and playlist entries. */
    fun forget(c: Context, uris: Collection<String>) {
        if (uris.isEmpty()) return
        val set = uris.toSet()
        val e = sp(c).edit()
        set.forEach { u -> listOf("pos:", "dur:", "title:", "time:", "watched:").forEach { e.remove(it + u) } }
        e.putString("favorites", write(favorites(c).filter { it.uri !in set }))
        e.apply()
        val m = playlists(c); m.values.forEach { l -> l.removeAll { it.uri in set } }; savePlaylists(c, m)
    }

    private fun read(json: String): List<Entry> = try {
        val a = JSONArray(json)
        (0 until a.length()).map { val o = a.getJSONObject(it); Entry(o.getString("uri"), o.optString("title")) }
    } catch (e: Exception) { emptyList() }
    private fun write(list: List<Entry>): String {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("uri", it.uri).put("title", it.title)) }
        return a.toString()
    }
}
