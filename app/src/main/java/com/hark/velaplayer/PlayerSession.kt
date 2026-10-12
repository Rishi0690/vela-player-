package com.hark.velaplayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared contract between the library (MainActivity) and the player (PlayerActivity).
 *
 * 1. Playback context: MainActivity passes [EXTRA_SOURCE] / [EXTRA_SOURCE_ID] with every
 *    PlayerActivity intent so the player knows whether Next/Previous should follow the folder
 *    order (SOURCE_FOLDER, SOURCE_ALL, SOURCE_CONTINUE) or stay inside an intentional queue
 *    (SOURCE_PLAYLIST, SOURCE_FAVORITES, SOURCE_EXTERNAL).
 * 2. Last session: the player saves the active queue + index + position here regularly. The
 *    library reads it on launch to offer "Resume" without ever starting playback by itself.
 */
object PlayerSession {
    const val EXTRA_SOURCE = "vela_source"
    const val EXTRA_SOURCE_ID = "vela_source_id"

    const val SOURCE_FOLDER = "folder"       // sourceId = folder (bucket) id / path
    const val SOURCE_ALL = "all"
    const val SOURCE_CONTINUE = "continue"
    const val SOURCE_PLAYLIST = "playlist"   // sourceId = playlist name
    const val SOURCE_FAVORITES = "favorites"
    const val SOURCE_EXTERNAL = "external"   // opened from another app / stream URL

    data class Session(
        val entries: List<Entry>,
        val index: Int,
        val positionMs: Long,
        val source: String,
        val sourceId: String?,
        val shuffled: Boolean,
        val savedAt: Long,
    ) {
        val current: Entry? get() = entries.getOrNull(index)
    }

    private fun sp(c: Context) = c.getSharedPreferences("vela_session", Context.MODE_PRIVATE)

    fun save(c: Context, s: Session) {
        val arr = JSONArray()
        s.entries.forEach { arr.put(JSONObject().put("u", it.uri).put("t", it.title)) }
        sp(c).edit()
            .putString("entries", arr.toString())
            .putInt("index", s.index)
            .putLong("pos", s.positionMs)
            .putString("source", s.source)
            .putString("sourceId", s.sourceId)
            .putBoolean("shuffled", s.shuffled)
            .putLong("savedAt", s.savedAt)
            .apply()
    }

    /** Cheap update of just the index/position while playing. */
    fun updatePosition(c: Context, index: Int, positionMs: Long) {
        sp(c).edit().putInt("index", index).putLong("pos", positionMs)
            .putLong("savedAt", System.currentTimeMillis()).apply()
    }

    fun load(c: Context): Session? {
        val p = sp(c)
        val raw = p.getString("entries", null) ?: return null
        return runCatching {
            val arr = JSONArray(raw)
            val list = (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Entry(o.getString("u"), o.optString("t")) } }
            if (list.isEmpty()) return null
            Session(
                list,
                p.getInt("index", 0).coerceIn(0, list.lastIndex),
                p.getLong("pos", 0L),
                p.getString("source", SOURCE_ALL) ?: SOURCE_ALL,
                p.getString("sourceId", null),
                p.getBoolean("shuffled", false),
                p.getLong("savedAt", 0L),
            )
        }.getOrNull()
    }

    fun clear(c: Context) = sp(c).edit().clear().apply()
}
