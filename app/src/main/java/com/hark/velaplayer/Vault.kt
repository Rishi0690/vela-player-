package com.hark.velaplayer

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hidden Vault records (v2.2). Shared contract between the library and the player.
 *
 * Two storage modes per item (v2.5):
 *  - MODE_APP ("Hide in Vela only"): only Vela's lists change. The file is NOT moved, deleted or encrypted and stays
 *    visible to Gallery, file managers and other apps.
 *  - MODE_PRIVATE ("Move to private storage"): the file was copied into Vela's private app storage (files/vault/),
 *    the copy was checked, and the user allowed Android to delete the original. Other apps can't see it, but it is
 *    NOT encrypted, and Android deletes it when Vela is uninstalled or its storage is cleared. See [VaultStorage].
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
    const val MODE_APP = "app"
    const val MODE_PRIVATE = "private"

    /**
     * [uri] is the key Vela uses everywhere (positions, order). For a private item it is the file:// uri of the copy,
     * and [orig] is the original MediaStore uri (kept so playlists/favorites that still point to it stay hidden and
     * can be re-pointed on restore). [origDir] is the original folder (RELATIVE_PATH, or an absolute folder below
     * Android 10), [pending] is true until Android confirmed deleting the original.
     */
    data class Item(val uri: String, val title: String, val hiddenAt: Long,
                    val mode: String = MODE_APP, val orig: String? = null, val origDir: String? = null,
                    val origName: String? = null, val mime: String? = null, val size: Long = 0L, val dur: Long = 0L,
                    val pending: Boolean = false, val wasApp: Boolean = false) {
        val isPrivate get() = mode == MODE_PRIVATE
    }

    fun items(c: Context): List<Item> = try {
        val a = JSONArray(sp(c).getString("items", "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it)
            Item(o.getString("u"), o.optString("t", "Video"), o.optLong("h", 0L), o.optString("m", MODE_APP),
                o.optString("o").ifEmpty { null }, o.optString("od").ifEmpty { null }, o.optString("on").ifEmpty { null },
                o.optString("mt").ifEmpty { null }, o.optLong("sz", 0L), o.optLong("du", 0L), o.optBoolean("pd", false), o.optBoolean("wa", false)) }
    } catch (_: Exception) { emptyList() }

    private fun writeItems(c: Context, list: List<Item>) {
        val a = JSONArray(); list.forEach {
            val o = JSONObject().put("u", it.uri).put("t", it.title).put("h", it.hiddenAt)
            if (it.isPrivate) {
                o.put("m", it.mode).put("o", it.orig ?: "").put("od", it.origDir ?: "").put("on", it.origName ?: "")
                    .put("mt", it.mime ?: "").put("sz", it.size).put("du", it.dur).put("pd", it.pending).put("wa", it.wasApp)
            }
            a.put(o)
        }
        // commit(): storage moves depend on this record being on disk before the original is deleted.
        sp(c).edit().putString("items", a.toString()).commit()
        cache = null
    }

    @Volatile private var cache: Set<String>? = null
    /** Every uri the normal library must not show: vault keys plus the original uris of privately stored videos. */
    fun hiddenSet(c: Context): Set<String> = cache ?: items(c).flatMap { listOfNotNull(it.uri, it.orig) }.toHashSet().also { cache = it }
    fun item(c: Context, uri: String): Item? = items(c).firstOrNull { it.uri == uri }

    // ---- v2.5 private storage records (used by VaultStorage) ----
    private val keyPrefixes = listOf("pos:", "dur:", "time:", "watched:")
    private fun moveKeys(c: Context, from: String, to: String) {
        if (from == to) return
        val all = sp(c).all; val e = sp(c).edit()
        for (p in keyPrefixes) { val v = all[p + from] ?: continue
            when (v) { is Long -> e.putLong(p + to, v); is Boolean -> e.putBoolean(p + to, v) }
            e.remove(p + from) }
        e.commit()
        val order = savedOrder(c); if (from in order) setOrder(c, order.map { if (it == from) to else it })
    }

    /** Records a private copy before the original is deleted (pending). Converts an existing Vela-only item in place. */
    fun addPendingPrivate(c: Context, rec: Item) {
        val origUri = rec.orig ?: return
        val cur = items(c).toMutableList()
        val i = cur.indexOfFirst { it.uri == origUri }
        if (i >= 0) {
            val old = cur.removeAt(i)
            moveKeys(c, origUri, rec.uri)
            cur.add(i, rec.copy(title = old.title, hiddenAt = old.hiddenAt, pending = true, wasApp = true))
        } else {
            val p = Store.position(c, origUri)
            if (p > 0) savePosition(c, rec.uri, p, Store.duration(c, origUri))
            cur.add(rec.copy(pending = true, wasApp = false))
            Store.forgetProgressOnly(c, listOf(origUri))
        }
        writeItems(c, cur)
    }

    fun confirmPrivate(c: Context, uri: String) = writeItems(c, items(c).map { if (it.uri == uri) it.copy(pending = false) else it })

    /** The original was not deleted: forget the private record (the caller deletes the copy). A converted item becomes Vela-only again. */
    fun revertPrivate(c: Context, uri: String) {
        val cur = items(c).toMutableList()
        val i = cur.indexOfFirst { it.uri == uri }; if (i < 0) return
        val r = cur.removeAt(i)
        val o = r.orig
        if (o != null && r.wasApp) {
            moveKeys(c, uri, o)
            cur.add(i, Item(o, r.title, r.hiddenAt))
        } else {
            val p = position(c, uri)
            if (o != null && p > 0) Store.savePosition(c, o, r.title, p, duration(c, uri))
            val e = sp(c).edit(); keyPrefixes.forEach { e.remove(it + uri) }; e.commit()
            val order = savedOrder(c); if (uri in order) setOrder(c, order.filter { it != uri })
        }
        writeItems(c, cur)
    }

    /** A private video was copied back to shared storage as [newUri]: leave the vault, keep resume point, re-point lists. */
    fun finishRestore(c: Context, uri: String, newUri: String) {
        val r = item(c, uri) ?: return
        val p = position(c, uri)
        if (p > 0) Store.savePosition(c, newUri, r.title, p, duration(c, uri))
        r.orig?.let { Store.replaceUri(c, it, newUri, r.title) }
        writeItems(c, items(c).filter { it.uri != uri })
        val e = sp(c).edit(); keyPrefixes.forEach { e.remove(it + uri) }; e.commit()
        val order = savedOrder(c); if (uri in order) setOrder(c, order.filter { it != uri })
    }

    /** A file found in private storage with no record (e.g. the app was killed mid-move): keep it, never delete it. */
    fun adoptOrphan(c: Context, rec: Item) { val cur = items(c).toMutableList(); if (cur.none { it.uri == rec.uri }) { cur.add(rec); writeItems(c, cur) } }
    fun isHidden(c: Context, uri: String?): Boolean = uri != null && uri in hiddenSet(c)

    /** Adds to the vault (duplicates ignored) and moves any resume position over from [Store]. Returns how many were new. */
    fun hide(c: Context, entries: List<Entry>): Int {
        val cur = items(c).toMutableList(); val have = HashSet(hiddenSet(c)); var n = 0
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

    /** Back to the normal library; the resume position goes back to [Store]. Vela-only items only (private ones restore via [VaultStorage]). */
    fun unhide(c: Context, uris: Collection<String>) {
        val set = uris.toHashSet().apply { removeAll(items(c).filter { it.isPrivate }.map { it.uri }.toSet()) }
        val cur = items(c)
        for (it in cur) if (it.uri in set) {
            val p = position(c, it.uri)
            if (p > 0) Store.savePosition(c, it.uri, it.title, p, duration(c, it.uri))
        }
        writeItems(c, cur.filter { it.uri !in set })
        val e = sp(c).edit(); set.forEach { e.remove("pos:$it").remove("dur:$it").remove("time:$it") }; e.apply()
        val order = savedOrder(c); if (order.any { it in set }) setOrder(c, order.filter { it !in set })
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

    // ---- v2.3: manual "Custom order" (uris, top to bottom). Items not in the saved order show first, newest hidden first. ----
    fun savedOrder(c: Context): List<String> = try {
        val a = JSONArray(sp(c).getString("order", "[]")); (0 until a.length()).map { a.getString(it) }
    } catch (_: Exception) { emptyList() }

    fun setOrder(c: Context, uris: List<String>) {
        val a = JSONArray(); uris.distinct().forEach { a.put(it) }
        sp(c).edit().putString("order", a.toString()).apply()
    }

    /** All vault items in the custom order (never changes the hidden list itself). */
    fun orderedItems(c: Context): List<Item> {
        val all = items(c)
        val pos = HashMap<String, Int>(); savedOrder(c).forEachIndexed { i, u -> if (u !in pos) pos[u] = i }
        val fresh = all.filter { it.uri !in pos }.sortedByDescending { it.hiddenAt }
        val known = all.filter { it.uri in pos }.sortedBy { pos[it.uri] }
        return fresh + known
    }

    /** v2.3 bulk: watched / unwatched for vault videos only. */
    fun setWatched(c: Context, uris: Collection<String>, watched: Boolean) {
        val e = sp(c).edit()
        uris.forEach { u -> if (watched) e.remove("pos:$u").remove("dur:$u").putBoolean("watched:$u", true) else e.remove("watched:$u") }
        e.apply()
    }

    // ---- in-memory unlock state (process death = locked) ----
    @Volatile private var unlockedAt = 0L
    /**
     * v2.3: true while the user deliberately chose "Use without password" (kept in sync by [VaultAuth]).
     * Then the vault counts as unlocked at all times, so no screen ever asks for authentication.
     */
    @Volatile private var openWithoutAuth = false
    fun setOpenWithoutAuth(on: Boolean) { openWithoutAuth = on }
    fun isPasswordFree(): Boolean = openWithoutAuth
    fun isUnlocked(): Boolean = openWithoutAuth || unlockedAt != 0L
    fun markUnlocked() { unlockedAt = SystemClock.elapsedRealtime() }
    fun lock() { unlockedAt = 0L }
}
