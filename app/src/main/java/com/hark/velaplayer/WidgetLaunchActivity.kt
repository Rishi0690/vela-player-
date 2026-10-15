package com.hark.velaplayer

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * v2.14: invisible trampoline for widget taps. Builds the real queue / destination at tap time (fresh Store data,
 * Hidden Vault filtering, same PlayerActivity contract as the library) so widgets never carry stale queues, then
 * finishes. Its own empty task affinity keeps it out of the app's back stack.
 */
class WidgetLaunchActivity : Activity() {
    companion object {
        const val ACTION_PLAY = "com.hark.velaplayer.widget.LAUNCH_PLAY"
        const val ACTION_RESUME = "com.hark.velaplayer.widget.LAUNCH_RESUME"
        const val ACTION_PLAYER = "com.hark.velaplayer.widget.LAUNCH_PLAYER"
        const val ACTION_OPEN = "com.hark.velaplayer.widget.LAUNCH_OPEN"
        const val ACTION_VAULT = "com.hark.velaplayer.widget.LAUNCH_VAULT"
        const val EXTRA_KIND = "vela_widget_kind"
        const val EXTRA_SOURCE = "vela_widget_source"
        const val EXTRA_URI = "vela_widget_uri"
        const val EXTRA_INDEX = "vela_widget_index"
        const val EXTRA_SHUFFLE = "vela_widget_shuffle"
        const val EXTRA_DEST = "vela_widget_dest"
        const val EXTRA_PLAYLIST = "vela_widget_playlist"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v2.20: no flash for this invisible trampoline. Android 14+: overrideActivityTransition (open + close of this
        // activity); older versions: the deprecated overridePendingTransition after finish().
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        try { handle(intent) } catch (_: Exception) { open("home", null) }
        finish()
        if (Build.VERSION.SDK_INT < 34) @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    private fun handle(i: Intent) {
        when (i.action) {
            ACTION_PLAY -> play(i)
            ACTION_RESUME -> resume()
            ACTION_PLAYER -> if (PlayerHolder.player != null)
                startActivity(Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(PlayerActivity.EXTRA_WIDGET_PLAY, i.getBooleanExtra(PlayerActivity.EXTRA_WIDGET_PLAY, false)))
                else resume()
            // The vault authenticates itself (PIN / pattern / biometric) before anything is shown.
            ACTION_VAULT -> startActivity(Intent(this, VaultActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            else -> open(i.getStringExtra(EXTRA_DEST) ?: "home", i.getStringExtra(EXTRA_PLAYLIST))
        }
    }

    /** Library destinations: brings the existing library forward (no duplicate screens) and switches its tab. */
    private fun open(dest: String, playlist: String?) {
        if (dest == "resume") { resume(); return }
        if (dest == "vault") { startActivity(Intent(this, VaultActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return }
        val m = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        if (dest != "home") m.putExtra(MainActivity.EXTRA_OPEN, dest)
        if (playlist != null) m.putExtra(MainActivity.EXTRA_OPEN_PLAYLIST, playlist)
        startActivity(m)
    }

    private fun resume() {
        val s = PlayerSession.load(this)
        val cur = s?.current
        if (s == null || cur == null || s.source == Vault.SOURCE_VAULT || Vault.isHidden(this, cur.uri)) {
            Toast.makeText(this, "Nothing to resume yet", Toast.LENGTH_SHORT).show(); open("home", null); return
        }
        val entries = Vault.visibleEntries(this, s.entries)
        val idx = entries.indexOfFirst { it.uri == cur.uri }.coerceAtLeast(0)
        startPlayer(entries, idx, s.shuffled, s.source, s.sourceId)
    }

    private fun play(i: Intent) {
        val kind = WidgetKind.of(i.getStringExtra(EXTRA_KIND))
        val uri = i.getStringExtra(EXTRA_URI)
        val shuffle = i.getBooleanExtra(EXTRA_SHUFFLE, false)
        val source = i.getStringExtra(EXTRA_SOURCE) ?: "favorites"
        when (kind) {
            WidgetKind.CONTINUE -> {
                // Fast path on the main thread: the saved list minus vault videos (the player skips files that vanished).
                val list = Vault.visibleEntries(this, Store.continueList(this))
                startAt(list, uri, PlayerSession.SOURCE_CONTINUE, null)
            }
            WidgetKind.COLLECTION -> {
                val (name, raw) = WidgetData.collection(this, source)
                if (raw == null) { Toast.makeText(this, "That playlist no longer exists", Toast.LENGTH_SHORT).show(); open("playlists", null); return }
                val list = Vault.visibleEntries(this, raw)
                val isPl = source.startsWith("playlist:")
                if (list.isEmpty()) { Toast.makeText(this, "Nothing to play here", Toast.LENGTH_SHORT).show(); open(if (isPl) "playlists" else "favorites", if (isPl) name else null); return }
                val src = if (isPl) PlayerSession.SOURCE_PLAYLIST else PlayerSession.SOURCE_FAVORITES
                if (uri == null) startPlayer(list, 0, shuffle, src, if (isPl) name else null)
                else startAt(list, uri, src, if (isPl) name else null)
            }
            else -> { // Recently added: one video; the player fills Next/Previous in library order (like All videos).
                if (uri == null) { open("all", null); return }
                val title = try {
                    contentResolver.query(android.net.Uri.parse(uri), arrayOf(android.provider.MediaStore.Video.Media.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                } catch (_: Exception) { null } ?: (android.net.Uri.parse(uri).lastPathSegment ?: "Video")
                startPlayer(listOf(Entry(uri, title)), 0, false, PlayerSession.SOURCE_ALL, null)
            }
        }
    }

    private fun startAt(list: List<Entry>, uri: String?, source: String, sourceId: String?) {
        val idx = list.indexOfFirst { it.uri == uri }
        when {
            idx >= 0 -> startPlayer(list, idx, false, source, sourceId)
            uri != null && !Vault.isHidden(this, uri) -> startPlayer(listOf(Entry(uri, android.net.Uri.parse(uri).lastPathSegment ?: "Video")), 0, false, source, sourceId)
            else -> { Toast.makeText(this, "That video is no longer available", Toast.LENGTH_SHORT).show(); WidgetUpdater.requestData(this) }
        }
    }

    /** Same intent the library sends (MainActivity.play), so the player behaves identically. */
    private fun startPlayer(entries: List<Entry>, index: Int, shuffled: Boolean, source: String, sourceId: String?) {
        if (entries.isEmpty()) return
        val from = (index - 250).coerceAtLeast(0)
        val to = (from + 500).coerceAtMost(entries.size)
        val window = entries.subList(from, to)
        val p = Intent(this, PlayerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putStringArrayListExtra(PlayerActivity.EXTRA_URIS, ArrayList(window.map { it.uri }))
            .putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, ArrayList(window.map { it.title }))
            .putExtra(PlayerActivity.EXTRA_SHUFFLE_PLAY, shuffled)
            .putExtra(PlayerSession.EXTRA_SOURCE, source)
            .putExtra(PlayerSession.EXTRA_SOURCE_ID, sourceId)
        if (!shuffled) p.putExtra(PlayerActivity.EXTRA_INDEX, index - from)
        startActivity(p)
    }
}
