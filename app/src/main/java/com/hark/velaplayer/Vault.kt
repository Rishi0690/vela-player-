package com.hark.velaplayer

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hidden Vault records (v2.2). Shared contract between the library and the player.
 *
 * IMPORTANT: hiding only changes what Vela shows. The original files are NOT moved, deleted or encrypted and stay
 * visible to other apps / file managers.
 *
 * Data lives in its own prefs file ("vela_vault"), separate from [Store]. Do not change these signatures;
 * the library side may add more functions (auth, settings) in VaultAuth.kt / VaultActivity.kt.
 */
object Vault {
    /** Intent extra: Boolean. true = the player is playing vault videos (no Continue Watching, no library order,
     *  no PlayerSession restore, positions via [savePosition]/[position], private title in notifications). */
    const val EXTRA_VAULT = "vela.vault"
    const val SOURCE_VAULT = "vault"

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("vela_vault", Context.MODE_PRIVATE)

    // ---- hidden list (uri + title + time hidden), insertion order kept ----
    data class Item(val uri: String, val title: String, val hiddenAt: Long)

    fun items(c: Context): List<Item> = try {
        val a = JSONArray(sp(c).getString("items", "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it); Item(o.getString("u"), o.optString("t", "Video"), o.optLong("h", 0L)) }
    } catch (_: Exception) { emptyList() }

    private fun writeItems(c: Context, list: List<Item>) {
        val a = JSONArray(); list.forEach { a.put(JSONObject().put("u", it.uri).put("t", it.title).put("h", it.hiddenAt)) }
        sp(c).edit().putString("items", a.toString()).apply()
        cache = null
    }

    @Volatile private var cache: Set<String>? = null
    fun hiddenSet(c: Context): Set<String> = cache ?: items(c).map { it.uri }.toHashSet().also { cache = it }
    fun isHidden(c: Context, uri: String?): Boolean = uri != null && uri in hiddenSet(c)

    /** Adds to the vault (duplicates ignored) and moves any resume position over from [Store]. Returns how many were new. */
    fun hide(c: Context, entries: List<Entry>): Int {
        val cur = items(c).toMutableList(); val have = cur.map { it.uri }.toHashSet(); var n = 0
        val now = System.currentTimeMillis()
        for (e in entries) if (have.add(e.uri)) {
            cur.add(Item(e.uri, e.title, now)); n++
            val p = Store.position(c, e.uri)
            if (p > 0) savePosition(c, e.uri, p, Store.duration(c, e.uri))
        }
        writeItems(c, cur)
        Store.forgetProgressOnly(c, entries.map { it.uri })
        return n
    }

    /** Back to the normal library; the resume position goes back to [Store]. */
    fun unhide(c: Context, uris: Collection<String>) {
        val set = uris.toHashSet()
        val cur = items(c)
        for (it in cur) if (it.uri in set) {
            val p = position(c, it.uri)
            if (p > 0) Store.savePosition(c, it.uri, it.title, p, duration(c, it.uri))
        }
        writeItems(c, cur.filter { it.uri !in set })
        val e = sp(c).edit(); set.forEach { e.remove("pos:$it").remove("dur:$it").remove("time:$it") }; e.apply()
    }

    fun rename(c: Context, uri: String, title: String) = writeItems(c, items(c).map { if (it.uri == uri) it.copy(title = title) else it })

    // ---- vault-only playback history / resume ----
    fun savePosition(c: Context, uri: String, pos: Long, dur: Long) {
        val e = sp(c).edit()
        if (dur > 0 && (pos >= dur - 10_000 || pos.toFloat() / dur > 0.97f)) e.remove("pos:$uri").putBoolean("watched:$uri", true)
        else if (pos > 3_000) e.putLong("pos:$uri", pos).putLong("dur:$uri", dur)
        e.putLong("time:$uri", System.currentTimeMillis()).apply()
    }
    fun position(c: Context, uri: String): Long = sp(c).getLong("pos:$uri", 0L)
    fun duration(c: Context, uri: String): Long = sp(c).getLong("dur:$uri", 0L)
    fun lastPlayed(c: Context, uri: String): Long = sp(c).getLong("time:$uri", 0L)
    fun markWatched(c: Context, uri: String) = sp(c).edit().remove("pos:$uri").putBoolean("watched:$uri", true).apply()
    fun isWatched(c: Context, uri: String) = sp(c).getBoolean("watched:$uri", false)

    // ---- v2.2 library additions ----
    /** "Play from start" in the vault: forget the vault resume point only. */
    fun clearPosition(c: Context, uri: String) = sp(c).edit().remove("pos:$uri").remove("dur:$uri").apply()
    fun count(c: Context): Int = hiddenSet(c).size
    /** Filters hidden videos out of a normal-library list of entries. */
    fun visibleEntries(c: Context, list: List<Entry>): List<Entry> {
        val h = hiddenSet(c); return if (h.isEmpty()) list else list.filter { it.uri !in h }
    }

    // ---- in-memory unlock state (process death = locked) ----
    @Volatile private var unlockedAt = 0L
    fun isUnlocked(): Boolean = unlockedAt != 0L
    fun markUnlocked() { unlockedAt = SystemClock.elapsedRealtime() }
    fun lock() { unlockedAt = 0L }
}
