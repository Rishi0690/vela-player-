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
            e.remove("pos:$uri")
        } else if (pos > 3_000) {
            e.putLong("pos:$uri", pos).putLong("dur:$uri", dur).putString("title:$uri", title)
                .putLong("time:$uri", System.currentTimeMillis())
        }
        e.apply()
    }
    fun clearPosition(c: Context, uri: String) = sp(c).edit().remove("pos:$uri").apply()
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
        l.add(e); savePlaylists(c, m); return true
    }
    /** Removes the entry from the playlist only. The video file is never deleted. */
    fun removeFromPlaylist(c: Context, name: String, uri: String) {
        val m = playlists(c); m[name]?.removeAll { it.uri == uri }; savePlaylists(c, m)
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
