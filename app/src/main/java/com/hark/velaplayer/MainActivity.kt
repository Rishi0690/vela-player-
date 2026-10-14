package com.hark.velaplayer

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import org.json.JSONObject
import com.hark.velaplayer.databinding.ActivityMainBinding
import com.hark.velaplayer.databinding.DialogUrlBinding
import com.hark.velaplayer.databinding.ItemCollectionHeaderBinding
import com.hark.velaplayer.databinding.ItemContinueBinding
import com.hark.velaplayer.databinding.ItemContinueStripBinding
import com.hark.velaplayer.databinding.ItemFolderBinding
import com.hark.velaplayer.databinding.ItemFolderGridBinding
import com.hark.velaplayer.databinding.ItemPlaylistBinding
import com.hark.velaplayer.databinding.ItemPlaylistGridBinding
import com.hark.velaplayer.databinding.ItemSummaryBinding
import com.hark.velaplayer.databinding.ItemVideoBinding
import com.hark.velaplayer.databinding.ItemVideoGridBinding
import java.io.File
import java.util.concurrent.Executors

class MainActivity : ThemedActivity() {

    private enum class Tab { FOLDERS, ALL, FAVORITES, PLAYLISTS }

    sealed class Row {
        data class Video(val entry: Entry, val item: VideoItem?, val progress: Float, val favorite: Boolean, val watched: Boolean = false, val unavailable: Boolean = false) : Row()
        /** [progress] = share of the playlist watched (0..1), or -1 for an empty playlist. */
        data class Playlist(val name: String, val count: Int, val durationMs: Long, val covers: List<Triple<Uri, Long, Long>>, val unavailable: Int, val progress: Float = -1f) : Row()
        data class Folder(val folder: VideoFolder, val watched: Int, val inProgress: Int, val showPath: Boolean) : Row()
        /** Library counts + watch filter, shown at the top of All videos and inside a folder. */
        data class Summary(val total: Int, val watched: Int, val inProgress: Int) : Row()
        /** Horizontal "Continue watching" strip at the top of Folders and All videos. */
        data class ContinueStrip(val videos: List<Video>) : Row()
        /** Title + Play all / Shuffle (+ Add videos for playlists) at the top of a folder or playlist. */
        data class Header(val playlist: Boolean, val title: String, val subtitle: String, val covers: List<Triple<Uri, Long, Long>>) : Row()
    }

    private lateinit var b: ActivityMainBinding
    private val adapter = Adapter()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var grid: GridLayoutManager

    private var tab = Tab.FOLDERS
    // v2.2: each tab keeps its own browsing state. The Folders tab remembers its open folder, the Playlists tab its
    // open playlist, and every tab its own watch filter; switching tabs no longer resets them.
    private var folderOfTab: String? = null
    private var playlistOfTab: String? = null
    private val filterOfTab = HashMap<Tab, String>()
    /** Open folder of the tab that's showing (only the Folders tab has one). Setting null only clears it there. */
    private var openFolder: String?
        get() = if (tab == Tab.FOLDERS) folderOfTab else null
        set(v) { if (v != null || tab == Tab.FOLDERS) folderOfTab = v }
    /** Open playlist of the tab that's showing (only the Playlists tab has one). */
    private var openPlaylist: String?
        get() = if (tab == Tab.PLAYLISTS) playlistOfTab else null
        set(v) { if (v != null || tab == Tab.PLAYLISTS) playlistOfTab = v }
    /** Watch filter for video lists of the current tab: all, progress, unwatched, watched. */
    private var filter: String
        get() = filterOfTab[tab] ?: "all"
        set(v) { filterOfTab[tab] = v }
    /** The last refresh came from a tab change: no staggered list entrance (the slide animation plays instead). */
    private var tabTransition = false
    /** Every video MediaStore returned (incl. hidden .ts files), so saved entries never look "unavailable". */
    private var library: List<VideoItem> = emptyList()
    /** What the library screens show: [library] minus ".ts" files while "Hide .ts videos" is on. */
    private var visible: List<VideoItem> = emptyList()
    private var byUri: Map<String, VideoItem> = emptyMap()
    private var query = ""
    private var sort = "date_desc"
    private var loaded = false
    private var bannerDismissed = false
    private var lastScreenKey = ""
    private var menuRef: Menu? = null

    // ---- v2.1: remembered UI state (screen, open folder/playlist, scroll per screen) ----
    private val ui by lazy { getSharedPreferences("vela_ui_state", MODE_PRIVATE) }
    /**
     * Scroll per screen key: the stable key of the first visible row (anchor), its pixel offset, and the index as a
     * fallback when the anchor item is gone. Persisted in [ui] while "Remember scroll position" is on.
     */
    private data class ScrollPos(val anchor: String?, val index: Int, val offset: Int)
    private val scrolls = LinkedHashMap<String, ScrollPos>()
    /** Cold start: the screen we restored, waiting for the library to load before it's validated and scrolled. */
    private var restoring = false
    private var resumeOffered = false
    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "hide_ts" || key == "hide_system_folders") main.post { if (!isDestroyed && loaded) refresh() }
        if (key == "remember_scroll" && !Prefs.rememberScroll(this)) ui.edit().remove("scrolls").apply()
    }
    private val storeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        // Playlist progress follows watch progress saved elsewhere (e.g. the player in PiP). Debounced.
        main.removeCallbacks(storeRefresh); main.postDelayed(storeRefresh, 800)
    }
    private val storeRefresh = Runnable { if (!isDestroyed && loaded && tab == Tab.PLAYLISTS && openPlaylist == null) refresh() }

    // ---- multi-select ----
    private var actionMode: ActionMode? = null
    /** Selected rows by stable key: "v:<uri>" for videos, "p:<name>" for playlists. Keeps tap order. */
    private val selected = LinkedHashSet<String>()
    private val selecting get() = actionMode != null

    // ---- v2.3: playlist "Edit order" (drag & drop, saved in Store) ----
    private var reorderMode: ActionMode? = null
    private val reordering get() = reorderMode != null
    /** The playlist being reordered (the order is saved into this one only). */
    private var reorderPlaylistName: String? = null
    private var reorderDirty = false
    private lateinit var touchHelper: ItemTouchHelper

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { reorderMode?.finish(); openPlaylist = null; openFolder = null; filter = "all"; refresh() }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { loadLibrary() }

    /** Runs after the system "Allow Vela to modify/delete?" dialog is approved (Android 10+). */
    private var afterGrant: (() -> Unit)? = null
    private val grantLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val next = afterGrant; afterGrant = null
        if (r.resultCode == Activity.RESULT_OK) next?.invoke() else toast("Cancelled")
    }
    /** Android 10 and older: storage write permission, asked only when the user renames or deletes. */
    private var afterWritePerm: (() -> Unit)? = null
    private val writePermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val next = afterWritePerm; afterWritePerm = null
        if (ok) next?.invoke() else toast("Storage permission is needed to change files")
    }

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            play(listOf(Entry(uri.toString(), nameOf(uri))), 0, source = PlayerSession.SOURCE_EXTERNAL, sourceId = null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v2.19: an active search survives a recreate (e.g. system dark/light switch); the menu re-opens the field.
        query = savedInstanceState?.getString(STATE_SEARCH_QUERY) ?: ""
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        // v2.3: the app name is the HoldTitleView inside the toolbar (same look), not the action bar title.
        supportActionBar?.setDisplayShowTitleEnabled(false)
        onBackPressedDispatcher.addCallback(this, backCallback)
        themeSwitched = ThemeSwitch.isPendingAvailable()
        ThemeSwitch.playIfPending(this)

        sort = Prefs.sp(this).getString("library_sort", "date_desc") ?: "date_desc"
        grid = GridLayoutManager(this, 1)
        grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                val r = adapter.rows.getOrNull(position) ?: return grid.spanCount
                return if (adapter.grid && (r is Row.Video || r is Row.Folder || r is Row.Playlist)) 1 else grid.spanCount
            }
        }
        b.list.layoutManager = grid
        b.list.adapter = adapter
        VelaScroller.attach(b.list)
        b.list.layoutAnimation = android.view.animation.AnimationUtils.loadLayoutAnimation(this, R.anim.list_layout)
        touchHelper = ItemTouchHelper(reorderCallback).also { it.attachToRecyclerView(b.list) }
        setupVaultHold()
        // v2.12: staggered rise for new rows (search results, filters, playlist edits); moves/removals as before.
        b.list.itemAnimator = VelaItemAnimator()
        b.chipScroll.clipToOutline = true
        // Toolbar gets a soft shadow once the list scrolls under it.
        val lift = 4 * resources.displayMetrics.density
        b.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy != 0) b.l23AppTitle.cancelHold()
                val target = if (rv.canScrollVertically(-1)) lift else 0f
                if (b.toolbar.tag != target) {
                    b.toolbar.tag = target
                    b.toolbar.animate().translationZ(target).setDuration(180).start()
                }
            }
        })

        // Open on the section chosen in Settings (Folders, All videos, or the last one used) and put it first.
        val start = Prefs.homeStartSection(this)
        if (start == "all") {
            b.chips.removeView(b.chipAll); b.chips.addView(b.chipAll, 0)
            b.chipAll.isChecked = true; tab = Tab.ALL
        } else { b.chipFolders.isChecked = true; tab = Tab.FOLDERS }
        // Scroll positions survive restarts only while "Remember scroll position" is on (default); in-session
        // tab switches always keep each tab's own position.
        if (Prefs.rememberScroll(this)) loadScrolls()
        // "Open app where I left off" (default ON): reopen the exact screen, never start playback by itself.
        // A recreated activity (rotation, process death) always comes back to where it was.
        if (Prefs.openWhereLeftOff(this) || savedInstanceState != null) restoreUiState()
        if (savedInstanceState == null) applyWidgetDestination(intent, initial = true)

        b.chips.setOnCheckedStateChangeListener { group, ids ->
            val newTab = when (ids.firstOrNull()) {
                R.id.chipAll -> Tab.ALL
                R.id.chipFavorites -> Tab.FAVORITES
                R.id.chipPlaylists -> Tab.PLAYLISTS
                else -> Tab.FOLDERS
            }
            if (newTab == tab) return@setOnCheckedStateChangeListener
            b.chips.slideToChecked() // v2.12: the nav pill slides/stretches to the new tab
            b.l23AppTitle.cancelHold()
            reorderMode?.finish()
            Motion.haptic(group)
            val dir = if (tabOrder().indexOf(newTab) > tabOrder().indexOf(tab)) 1 else -1
            tab = newTab
            if (tab == Tab.FOLDERS) Prefs.saveHomeSection(this, "folders")
            if (tab == Tab.ALL) Prefs.saveHomeSection(this, "all")
            // The tab comes back with its own folder/playlist, filter and scroll (nothing is reset).
            tabTransition = true
            refresh()
            tabTransition = false
            saveUiState()
            revealChip()
            if (!swipeSwitching) b.tabSwipe.slideIn(dir)
        }
        b.tabSwipe.host = object : TabSwipeLayout.Host {
            override fun canSwipeTo(direction: Int): Boolean {
                if (selecting || reordering || !Prefs.swipeTabs(this@MainActivity)) return false
                val i = tabOrder().indexOf(tab) + direction
                return i in tabOrder().indices
            }
            override fun onSwipeTo(direction: Int) {
                b.l23AppTitle.cancelHold()
                val next = tabOrder().getOrNull(tabOrder().indexOf(tab) + direction) ?: return
                swipeSwitching = true
                chipFor(next).isChecked = true
                swipeSwitching = false
            }
        }
        b.header.setOnClickListener { reorderMode?.finish(); openPlaylist = null; openFolder = null; filter = "all"; refresh() }
        b.fab.setOnClickListener { newPlaylistDialog(null, openPickerAfter = true) }
        b.btnGrant.setOnClickListener { requestPermission() }
        b.accessBannerButton.setOnClickListener { openAppSettings() }
        b.accessBannerClose.setOnClickListener { bannerDismissed = true; b.accessBanner.isVisible = false }
        b.swipeRefresh.setColorSchemeColors(ThemeManager.primary(this))
        Artwork.applyTo(b.emptyArt, android.widget.ImageView.ScaleType.FIT_CENTER) // v2.14
        b.swipeRefresh.setOnRefreshListener { ThumbLoader.clearFailures(); loadLibrary() }

        // Ask only when we have no access at all; limited access shows the banner instead of re-asking every launch.
        if (hasPermission() || hasPartialAccess()) { if (!loaded) b.skeleton.isVisible = true; loadLibrary() } else requestPermission()
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(prefListener)
        Store.registerListener(this, storeListener)
    }

    override fun onResume() {
        super.onResume()
        // A normal library screen is showing: the Hidden Vault is never left unlocked behind it.
        Vault.lock()
        // Reload so counts follow videos added/removed while we were away (also re-applies "Hide .ts videos"
        // and drops anything hidden in the vault meanwhile).
        if (loaded) { refresh(); loadLibrary() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        rememberScroll(); saveUiState()
        outState.putString(STATE_SEARCH_QUERY, query)
        super.onSaveInstanceState(outState)
    }

    // ------------------------------------------------------------------ tabs (v2.2)

    private var swipeSwitching = false

    /** Tabs in the order the chips show them (All videos may be first, see "Open the app on"). */
    private fun tabOrder(): List<Tab> = (0 until b.chips.childCount).mapNotNull { i ->
        when (b.chips.getChildAt(i).id) {
            R.id.chipFolders -> Tab.FOLDERS; R.id.chipAll -> Tab.ALL; R.id.chipFavorites -> Tab.FAVORITES; R.id.chipPlaylists -> Tab.PLAYLISTS; else -> null
        }
    }

    private fun chipFor(t: Tab) = when (t) { Tab.FOLDERS -> b.chipFolders; Tab.ALL -> b.chipAll; Tab.FAVORITES -> b.chipFavorites; Tab.PLAYLISTS -> b.chipPlaylists }

    /** Keeps the selected chip in view when the tab changes by swiping. */
    private fun revealChip() {
        val c = chipFor(tab)
        b.chipScroll.post {
            val left = c.left - b.chipScroll.paddingLeft
            val right = c.right + b.chipScroll.paddingRight - b.chipScroll.width
            val x = b.chipScroll.scrollX
            if (left < x) b.chipScroll.smoothScrollTo(left.coerceAtLeast(0), 0) else if (right > x) b.chipScroll.smoothScrollTo(right, 0)
        }
    }

    override fun onPause() {
        super.onPause()
        b.l23AppTitle.cancelHold()
        if (reorderDirty) saveReorder()
        rememberScroll()
        saveUiState()
    }

    override fun onDestroy() {
        Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        Store.unregisterListener(this, storeListener)
        main.removeCallbacks(storeRefresh)
        io.shutdown(); super.onDestroy()
    }

    // ------------------------------------------------------------------ remembered state (v2.1)

    private fun tabKey(t: Tab) = when (t) { Tab.FOLDERS -> "folders"; Tab.ALL -> "all"; Tab.FAVORITES -> "favorites"; Tab.PLAYLISTS -> "playlists" }

    /** Saves the open screen. Called on every navigation change and in onPause. */
    private fun saveUiState() {
        val e = ui.edit().putString("tab", tabKey(tab)).putString("folder", folderOfTab).putString("playlist", playlistOfTab)
            .putString("filter", filter)
        Tab.values().forEach { t -> e.putString("filter_" + tabKey(t), filterOfTab[t] ?: "all") }
        e.apply()
    }

    /** Cold start: puts back the last screen. The folder/playlist is validated once the library has loaded. */
    private fun restoreUiState() {
        val t = when (ui.getString("tab", null)) { "all" -> Tab.ALL; "favorites" -> Tab.FAVORITES; "playlists" -> Tab.PLAYLISTS; "folders" -> Tab.FOLDERS; else -> return }
        tab = t
        when (t) {
            Tab.ALL -> b.chipAll.isChecked = true
            Tab.FAVORITES -> b.chipFavorites.isChecked = true
            Tab.PLAYLISTS -> b.chipPlaylists.isChecked = true
            Tab.FOLDERS -> b.chipFolders.isChecked = true
        }
        // Both tabs' open folder/playlist come back (v2.2 keeps them per tab). A missing playlist falls back to
        // the playlist list now; a missing folder is checked once the library has loaded (refresh()).
        folderOfTab = ui.getString("folder", null)
        playlistOfTab = ui.getString("playlist", null)?.takeIf { Store.playlists(this).containsKey(it) }
        val valid = setOf("all", "progress", "unwatched", "watched")
        Tab.values().forEach { tt -> ui.getString("filter_" + tabKey(tt), null)?.takeIf { it in valid }?.let { filterOfTab[tt] = it } }
        if (!ui.contains("filter_" + tabKey(t))) ui.getString("filter", "all")?.takeIf { it in valid }?.let { filterOfTab[t] = it }
        filterOfTab[Tab.FAVORITES] = "all"; filterOfTab[Tab.PLAYLISTS] = "all"
        restoring = true
    }

    private fun screenKey() = "$tab|$openFolder|$openPlaylist|$filter"

    private fun loadScrolls() {
        scrolls.clear()
        try {
            val o = JSONObject(ui.getString("scrolls", "{}") ?: "{}")
            o.keys().forEach { k ->
                val v = o.get(k)
                if (v is JSONObject) scrolls[k] = ScrollPos(v.optString("a").takeIf { it.isNotEmpty() }, v.optInt("i", 0), v.optInt("o", 0))
                else { // v2.1 format "index,offset"
                    val a = v.toString().split(','); if (a.size == 2) scrolls[k] = ScrollPos(null, a[0].toIntOrNull() ?: 0, a[1].toIntOrNull() ?: 0)
                }
            }
        } catch (_: Exception) { }
    }

    /**
     * Remembers where the list is scrolled for the screen that's showing now (60 most recent screens), anchored to
     * the first visible row's stable key (video uri, folder id, playlist name). Never while searching.
     */
    private fun rememberScroll(key: String = lastScreenKey) {
        if (key.isEmpty() || !loaded || adapter.itemCount == 0 || lastScreenQuery.isNotBlank()) return
        val pos = grid.findFirstVisibleItemPosition()
        if (pos == RecyclerView.NO_POSITION) return
        val off = (grid.findViewByPosition(pos)?.top ?: 0) - b.list.paddingTop
        val anchor = adapter.rows.getOrNull(pos)?.let { adapter.key(it) }
        scrolls.remove(key); scrolls[key] = ScrollPos(anchor, pos, off)
        while (scrolls.size > 60) scrolls.remove(scrolls.keys.first())
        if (Prefs.rememberScroll(this)) {
            val o = JSONObject(); scrolls.forEach { (k, v) -> o.put(k, JSONObject().put("a", v.anchor ?: "").put("i", v.index).put("o", v.offset)) }
            ui.edit().putString("scrolls", o.toString()).apply()
        }
    }

    /** Back to the remembered spot: the anchor row if it still exists, else the nearest valid index. */
    private fun applyScroll(key: String) {
        val s = scrolls[key]
        val n = adapter.itemCount
        if (s == null || n == 0) { b.list.scrollToPosition(0); return }
        val byAnchor = s.anchor?.let { a -> adapter.rows.indexOfFirst { adapter.key(it) == a } } ?: -1
        val idx = if (byAnchor >= 0) byAnchor else s.index.coerceIn(0, n - 1)
        // When the anchor vanished the old offset belongs to another row: land on the row's top instead.
        grid.scrollToPositionWithOffset(idx, if (byAnchor >= 0 || s.anchor == null) s.offset else 0)
    }
    /** Query of the screen whose scroll [rememberScroll] would save. */
    private var lastScreenQuery = ""

    /** Offers the last player session (never starts it): "Resume <title> · mm:ss". */
    private fun offerResume() {
        if (resumeOffered) return
        resumeOffered = true
        val s = PlayerSession.load(this) ?: return
        val cur = s.current ?: return
        // Never offer (or even name) a vault video or a vault session in the normal library.
        if (s.source == Vault.SOURCE_VAULT || Vault.isHidden(this, cur.uri)) return
        if (ui.getLong("resume_dismissed_at", -1L) == s.savedAt) return
        // Validate the file: a library video must still exist; a stream or picked file is kept as is.
        if (isUnavailable(cur.uri)) return
        if (cur.uri.startsWith("file://") && Uri.parse(cur.uri).path?.let { File(it).exists() } == false) return
        val pos = s.positionMs.takeIf { it > 0 } ?: Store.position(this, cur.uri)
        if (pos <= 0L && Store.watchedSet(this).contains(cur.uri)) return
        val title = cur.title.ifBlank { Uri.parse(cur.uri).lastPathSegment ?: "video" }
        val text = if (pos > 0) "Resume $title · ${Fmt.time(pos)}" else "Resume $title"
        Snackbar.make(b.root, text, 10_000)
            .setAction("Resume") {
                // Anything hidden since the session was saved stays out of the queue.
                val entries = Vault.visibleEntries(this, s.entries)
                val idx = entries.indexOfFirst { it.uri == cur.uri }.coerceAtLeast(0)
                play(entries, idx, s.shuffled, s.source, s.sourceId)
            }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(bar: Snackbar?, event: Int) {
                    if (event == com.google.android.material.snackbar.BaseTransientBottomBar.BaseCallback.DISMISS_EVENT_SWIPE) ui.edit().putLong("resume_dismissed_at", s.savedAt).apply()
                }
            })
            .show()
    }

    /** True when a saved entry is a ".ts" file that "Hide .ts videos" keeps out of the library screens. */
    private fun hiddenTs(e: Entry, hide: Boolean) = hide && VideoRepository.isTsEntry(e, byUri[e.uri])

    // ------------------------------------------------------------------ permissions & loading

    private fun videoPermission() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasPermission() = ContextCompat.checkSelfPermission(this, videoPermission()) == PackageManager.PERMISSION_GRANTED

    /** Android 14+: the user picked "Allow limited access" (only some videos are visible). */
    private fun hasPartialAccess() = Build.VERSION.SDK_INT >= 34 && !hasPermission() &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED

    private fun updateAccessBanner() {
        b.accessBanner.isVisible = hasPartialAccess() && !bannerDismissed
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } catch (_: Exception) {
            startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
        }
    }

    private fun requestPermission() {
        val perms = mutableListOf(videoPermission())
        if (Build.VERSION.SDK_INT >= 34) perms += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun loadLibrary() {
        if (io.isShutdown) return
        io.execute {
            val items = if (hasPermission() || hasPartialAccess()) VideoRepository.load(this, respectHideTs = false) else emptyList()
            main.post {
                if (isDestroyed) return@post
                library = items
                byUri = items.associateBy { it.uri.toString() }
                loaded = true
                b.swipeRefresh.isRefreshing = false
                if (b.skeleton.isVisible) Motion.exit(b.skeleton, Motion.MEDIUM)
                updateAccessBanner()
                refresh()
                // v2.3.1: launch Resume snackbar removed (user request); Continue Watching still resumes.
            }
        }
    }

    /** A library video that's gone (moved/deleted) — only judged when we can see the whole library. */
    private fun isUnavailable(uri: String) = loaded && fullAccess && uri.startsWith("content://media/") && byUri[uri] == null
    /** Cached once per refresh: permission checks are too slow to repeat for every row. */
    private var fullAccess = false

    // ------------------------------------------------------------------ list building

    /** Which screen is showing; each remembers its own grid/list choice. */
    private fun section(): String = when {
        tab == Tab.FOLDERS && openFolder == null -> "folders"
        tab == Tab.FOLDERS -> "folder"
        tab == Tab.ALL -> "all"
        tab == Tab.FAVORITES -> "favorites"
        openPlaylist == null -> "playlists"
        else -> "playlist"
    }

    private fun spanFor(gridMode: Boolean): Int {
        if (!gridMode) return 1
        val fixed = Prefs.gridColumns(this)
        if (fixed > 0) return fixed
        val wDp = resources.configuration.screenWidthDp
        return (wDp / 180).coerceIn(2, 6)
    }

    private fun applyViewMode(animate: Boolean) {
        val g = Prefs.viewMode(this, section()) == "grid"
        val span = spanFor(g)
        val changed = adapter.grid != g || grid.spanCount != span
        adapter.grid = g
        grid.spanCount = span
        menuRef?.findItem(R.id.action_view)?.let {
            it.setIcon(if (g) R.drawable.ic_list else R.drawable.ic_grid)
            it.title = if (g) "List view" else "Grid view"
        }
        if (changed && animate) {
            // v2.12: grid ↔ list cross-dissolve: the old layout zooms/fades away while the new one sharpens in.
            Motion.crossSwap(b.list) { @Suppress("NotifyDataSetChanged") adapter.notifyDataSetChanged() }
        }
    }

    private fun totalDuration(entries: List<Entry>): Long = entries.sumOf { e -> byUri[e.uri]?.durationMs ?: Store.duration(this, e.uri) }

    private fun coversFor(entries: List<Entry>): List<Triple<Uri, Long, Long>> =
        entries.asSequence().filter { !isUnavailable(it.uri) }.take(4).map { e ->
            val it = byUri[e.uri]
            Triple(it?.uri ?: Uri.parse(e.uri), it?.id ?: -1L, it?.durationMs ?: 0L)
        }.toList()

    private fun countText(n: Int) = if (n == 1) "1 video" else "$n videos"

    private fun refresh() {
        fullAccess = hasPermission()
        val hideTs = Prefs.hideTs(this)
        visible = if (hideTs) library.filterNot { VideoRepository.isTs(it.name, it.path) } else library
        val rows = ArrayList<Row>()
        val folders = VideoRepository.folders(visible, Prefs.hideSystemFolders(this))
        val folderObj = openFolder?.let { id -> folders.firstOrNull { it.id == id } ?: VideoRepository.folders(visible, false).firstOrNull { it.id == id } }
        // A remembered folder that's gone (or holds only hidden videos): fall back to the folder list.
        if (openFolder != null && folderObj == null && loaded) openFolder = null
        // Same for the Folders tab's remembered folder while another tab is showing (checked against the loaded library).
        if (tab != Tab.FOLDERS && folderOfTab != null && loaded && visible.none { it.bucketId == folderOfTab }) folderOfTab = null
        val playlists = Store.playlists(this)
        if (playlistOfTab != null && !playlists.containsKey(playlistOfTab)) playlistOfTab = null
        val hidden = Vault.hiddenSet(this)
        fun shownEntries(l: List<Entry>) = if (hidden.isEmpty()) l else l.filter { it.uri !in hidden }
        backCallback.isEnabled = openPlaylist != null || openFolder != null
        val showHeader = openPlaylist != null || openFolder != null
        if (showHeader && !b.header.isVisible) Motion.enter(b.header, -12 * resources.displayMetrics.density)
        else b.header.isVisible = showHeader
        b.header.text = if (openFolder != null) "‹  All folders" else "‹  All playlists"
        if (tab == Tab.PLAYLISTS && openPlaylist == null && !selecting) b.fab.show() else b.fab.hide()
        val favs = Store.favorites(this).map { it.uri }.toSet()
        val watched = Store.watchedSet(this)
        val inProgress = Store.inProgressSet(this)
        val stats = Prefs.showWatchStats(this)

        fun videoRow(e: Entry) = videoRowOf(e, favs, watched)

        fun continueStrip(): Row.ContinueStrip? {
            if (query.isNotBlank()) return null
            val list = Store.continueList(this).filter { it.uri !in hidden && !isUnavailable(it.uri) && !hiddenTs(it, hideTs) }.take(20).map { videoRow(it) }
            return if (list.isEmpty()) null else Row.ContinueStrip(list)
        }

        /** Summary row + the videos of [items], narrowed by the watch filter. */
        fun videoList(items: List<VideoItem>, includeStreams: Boolean) {
            val uris = items.map { it.uri.toString() }
            if (stats) rows += Row.Summary(items.size, uris.count { it in watched }, uris.count { it in inProgress })
            when (filter) {
                "progress" -> {
                    val set = uris.toSet()
                    Store.continueList(this).filter { it.uri !in hidden && (set.contains(it.uri) || (includeStreams && byUri[it.uri] == null && !isUnavailable(it.uri) && !hiddenTs(it, hideTs))) }
                        .forEach { rows += videoRow(it) }
                }
                "watched" -> items.filter { it.uri.toString() in watched }.forEach { rows += videoRow(Entry(it.uri.toString(), it.name)) }
                "unwatched" -> items.filter { it.uri.toString() !in watched }.forEach { rows += videoRow(Entry(it.uri.toString(), it.name)) }
                else -> items.forEach { rows += videoRow(Entry(it.uri.toString(), it.name)) }
            }
        }

        when {
            tab == Tab.FOLDERS && folderObj != null -> {
                if (query.isBlank()) rows += Row.Header(false, folderObj.name,
                    listOfNotNull(countText(folderObj.videos.size), Fmt.time(folderObj.videos.sumOf { it.durationMs }).takeIf { folderObj.videos.isNotEmpty() }).joinToString(" · "),
                    emptyList())
                videoList(folderObj.videos, false)
            }
            tab == Tab.FOLDERS -> {
                continueStrip()?.let { rows += it }
                val dupNames = folders.groupBy { it.name.lowercase() }.filter { it.value.size > 1 }.keys
                val sorted = when (Prefs.folderSort(this)) {
                    "count" -> folders.sortedByDescending { it.videos.size }
                    "recent" -> folders.sortedByDescending { f -> f.videos.maxOfOrNull { it.dateAdded } ?: 0L }
                    else -> folders.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                }
                sorted.forEach { f ->
                    val u = f.videos.map { it.uri.toString() }
                    rows += Row.Folder(f, u.count { it in watched }, u.count { it in inProgress }, f.name.lowercase() in dupNames)
                }
            }
            tab == Tab.ALL -> { if (filter != "progress") continueStrip()?.let { rows += it }; videoList(visible, true) }
            tab == Tab.FAVORITES -> shownEntries(Store.favorites(this)).filter { !hiddenTs(it, hideTs) }.forEach { rows += videoRow(it) }
            openPlaylist == null -> {
                val watchedAll = watched
                // Vault videos stay in the playlist data (unhiding brings them back) but are never listed or counted.
                val plRows = playlists.map { (name, all) ->
                    val list = shownEntries(all)
                    Row.Playlist(name, list.size, totalDuration(list), coversFor(list), list.count { isUnavailable(it.uri) }, playlistProgress(list, watchedAll))
                }
                rows += when (Prefs.sp(this).getString("playlists_list_sort", "created")) {
                    "name_asc" -> plRows.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                    "name_desc" -> plRows.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name })
                    "count_desc" -> plRows.sortedByDescending { it.count }
                    "count_asc" -> plRows.sortedBy { it.count }
                    "newest" -> plRows.reversed()
                    else -> plRows
                }
            }
            else -> {
                val list = shownEntries(playlists[openPlaylist] ?: emptyList())
                val missing = list.count { isUnavailable(it.uri) }
                if (query.isBlank()) rows += Row.Header(true, openPlaylist ?: "",
                    listOfNotNull(countText(list.size), Fmt.time(totalDuration(list)).takeIf { list.isNotEmpty() },
                        if (missing > 0) "$missing unavailable" else null).joinToString(" · "),
                    coversFor(list))
                playlistSorted(list.map { videoRow(it) }).forEach { rows += it }
            }
        }

        var filtered: List<Row> = rows
        if (query.isNotBlank()) {
            val q = query.trim().lowercase()
            filtered = rows.filter {
                when (it) {
                    is Row.Video -> it.entry.title.lowercase().contains(q)
                    is Row.Playlist -> it.name.lowercase().contains(q)
                    is Row.Folder -> it.folder.name.lowercase().contains(q) || it.folder.videos.any { v -> v.name.lowercase().contains(q) }
                    is Row.Summary, is Row.ContinueStrip, is Row.Header -> true
                }
            }
        }
        // In-progress keeps its most-recent order and playlists keep their own order; other video lists follow the chosen sort.
        val inVideoList = (tab == Tab.ALL || (tab == Tab.FOLDERS && openFolder != null))
        if ((inVideoList && filter != "progress") || tab == Tab.FAVORITES) filtered = sortRows(filtered)

        val ctxKey = screenKey()
        val newScreen = ctxKey != lastScreenKey
        if (newScreen && lastScreenKey.isNotEmpty()) { actionMode?.finish(); if (reordering && openPlaylist != reorderPlaylistName) reorderMode?.finish(); rememberScroll(lastScreenKey) }
        updateReorderMenu()
        lastScreenKey = ctxKey
        applyViewMode(animate = false)
        // Selection survives actions; anything that disappeared (deleted, removed) drops out of it.
        if (selected.isNotEmpty()) {
            val keys = rows.mapNotNull { selKey(it) }.toHashSet()
            if (selected.retainAll(keys)) updateActionMode()
        }
        adapter.submit(filtered, newScreen)
        // Tab changes slide as a whole (TabSwipeLayout); other screen changes keep the staggered entrance.
        if (newScreen && !tabTransition) {
            val depth = if (openFolder != null || openPlaylist != null) 1 else 0
            // v2.12: opening a folder/playlist zooms in (from 94%), going back zooms out (from 105%), with blur-to-sharp.
            if (screenDepth >= 0 && depth != screenDepth) Motion.zoomThrough(b.list, forward = depth > screenDepth)
            else b.list.scheduleLayoutAnimation()
        }
        screenDepth = if (openFolder != null || openPlaylist != null) 1 else 0
        lastScreenQuery = query
        // Each screen comes back where it was scrolled (also after a cold start, once the library is there).
        if (loaded && (newScreen || restoring)) {
            restoring = false
            applyScroll(ctxKey)
            saveUiState()
        }
        val empty = filtered.none { it is Row.Video || it is Row.Folder || it is Row.Playlist }
        // v2.12: while the first load runs the skeleton shows instead of an "empty" message.
        Motion.reveal(b.emptyView, empty && filtered.none { it is Row.Summary || it is Row.ContinueStrip } && (loaded || !(hasPermission() || hasPartialAccess())))
        b.btnGrant.isVisible = empty && (tab == Tab.ALL || tab == Tab.FOLDERS) && !hasPermission() && !hasPartialAccess()
        b.emptyIcon.setImageResource(when {
            query.isNotBlank() -> R.drawable.ic_search
            (tab == Tab.ALL || tab == Tab.FOLDERS) && !hasPermission() && !hasPartialAccess() -> R.drawable.ic_lock_open
            tab == Tab.FOLDERS && openFolder == null -> R.drawable.ic_folder
            tab == Tab.FAVORITES -> R.drawable.ic_star
            tab == Tab.PLAYLISTS -> R.drawable.ic_playlist
            else -> R.drawable.ic_video_library
        })
        b.emptyText.text = when {
            query.isNotBlank() -> "Nothing matches \"$query\"."
            (tab == Tab.ALL || tab == Tab.FOLDERS) && !hasPermission() && !hasPartialAccess() -> "Allow access so Vela can show the videos on your phone."
            tab == Tab.FOLDERS && openFolder == null -> "No folders with videos found yet."
            tab == Tab.ALL || tab == Tab.FOLDERS -> "No videos found on this device yet."
            tab == Tab.FAVORITES -> "Tap ⋮ on any video and choose Add to favorites."
            openPlaylist != null -> "This playlist is empty. Tap + above to add videos."
            else -> "No playlists yet. Tap + to create one."
        }
    }

    /**
     * Playlist progress: average over the playlist's unique videos of 1.0 for a watched video, else
     * position / duration when both are known (0 otherwise). -1 for an empty playlist.
     */
    private fun playlistProgress(list: List<Entry>, watched: Set<String>): Float {
        val uris = list.map { it.uri }.distinct()
        if (uris.isEmpty()) return -1f
        var sum = 0.0
        uris.forEach { u ->
            sum += if (u in watched) 1.0 else {
                val pos = Store.position(this, u)
                val dur = Store.duration(this, u).takeIf { it > 0 } ?: byUri[u]?.durationMs ?: 0L
                if (pos > 0 && dur > 0) (pos.toDouble() / dur).coerceIn(0.0, 1.0) else 0.0
            }
        }
        return (sum / uris.size).toFloat().coerceIn(0f, 1f)
    }

    private fun videoRowOf(e: Entry, favs: Set<String>, watched: Set<String>): Row.Video {
        val item = byUri[e.uri]
        val pos = Store.position(this, e.uri)
        val dur = Store.duration(this, e.uri).takeIf { it > 0 } ?: item?.durationMs ?: 0L
        val progress = if (pos > 0 && dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        return Row.Video(e, item, progress, favs.contains(e.uri), watched.contains(e.uri), isUnavailable(e.uri))
    }

    private fun sortRows(rows: List<Row>): List<Row> {
        val videos = rows.filterIsInstance<Row.Video>()
        val sorted = when (sort) {
            "name_asc" -> videos.sortedBy { it.entry.title.lowercase() }
            "name_desc" -> videos.sortedByDescending { it.entry.title.lowercase() }
            "date_asc" -> videos.sortedBy { it.item?.dateAdded ?: 0L }
            "duration_desc" -> videos.sortedByDescending { it.item?.durationMs ?: 0L }
            "duration_asc" -> videos.sortedBy { it.item?.durationMs ?: 0L }
            "size_desc" -> videos.sortedByDescending { it.item?.size ?: 0L }
            "size_asc" -> videos.sortedBy { it.item?.size ?: 0L }
            else -> videos.sortedByDescending { it.item?.dateAdded ?: 0L }
        }
        return rows.filterIsInstance<Row.Header>() + rows.filterIsInstance<Row.ContinueStrip>() + rows.filterIsInstance<Row.Summary>() + sorted + rows.filterIsInstance<Row.Playlist>()
    }

    // ------------------------------------------------------------------ menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        menuRef = menu
        val searchItem = menu.findItem(R.id.action_search)
        val sv = searchItem.actionView as SearchView
        sv.queryHint = "Search videos"
        SearchMorph.install(this, b.toolbar, searchItem, sv, depth = listOf(b.tabSwipe), dim = listOf(b.navWrap))
        if (query.isNotBlank()) { val q = query; SearchMorph.expandInstant(searchItem); sv.setQuery(q, false); query = q }
        sv.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?): Boolean { sv.clearFocus(); return true }
            override fun onQueryTextChange(q: String?): Boolean {
                val nq = q ?: ""
                if (nq.isNotBlank() && reordering) reorderMode?.finish()
                query = nq; refresh(); return true
            }
        })
        applyViewMode(animate = false)
        updateReorderMenu()
        menu.findItem(R.id.action_theme)?.setIcon(ThemeSwitch.iconFor(this))
        if (themeSwitched) { themeSwitched = false; b.toolbar.post { ThemeSwitch.popIcon(findViewById(R.id.action_theme)) } }
        return true
    }
    private var themeSwitched = false

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_view -> {
                val now = if (Prefs.viewMode(this, section()) == "grid") "list" else "grid"
                Prefs.setViewMode(this, section(), now)
                val icon = findViewById<View>(R.id.action_view)
                applyViewMode(animate = true)
                // icon morph (grid ⇄ list): the new icon spins in on a spring
                if (icon != null && Motion.enabled(this)) { icon.rotation = -90f; icon.scaleX = 0.5f; icon.scaleY = 0.5f; icon.alpha = 0f
                    icon.animate().rotation(0f).scaleX(1f).scaleY(1f).alpha(1f).setDuration(Motion.LONG + 80).setInterpolator(Motion.spring).start() }
            }
            R.id.action_sort -> if (tab == Tab.PLAYLISTS && openPlaylist != null) playlistSortDialog() else if (tab == Tab.PLAYLISTS) playlistsListSortDialog() else sortDialog()
            R.id.l23_action_reorder -> startReorder()
            R.id.action_select -> if (reordering) toast("Tap ✓ to finish editing the order first") else startSelection(null)
            R.id.action_focus -> startActivity(Intent(this, FocusActivity::class.java))
            R.id.action_open_url -> urlDialog()
            R.id.action_open_file -> openFile.launch(arrayOf("video/*"))
            R.id.action_theme -> ThemeSwitch.toggle(this, findViewById(R.id.action_theme))
            R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.action_widgets -> startActivity(Intent(this, WidgetsActivity::class.java))
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyViewMode(animate = true)
    }

    /** v2.3.1: sort for the Playlists list itself (never changes the saved playlists). */
    private fun playlistsListSortDialog() {
        val labels = arrayOf("Oldest created first", "Newest created first", "Name A–Z", "Name Z–A", "Most videos", "Fewest videos")
        val keys = arrayOf("created", "newest", "name_asc", "name_desc", "count_desc", "count_asc")
        val cur = Prefs.sp(this).getString("playlists_list_sort", "created")
        VelaDialogBuilder(this).setTitle("Sort playlists")
            .setSingleChoiceItems(labels, keys.indexOf(cur).coerceAtLeast(0)) { d, i ->
                Prefs.sp(this).edit().putString("playlists_list_sort", keys[i]).apply()
                refresh(); d.dismiss()
            }.show()
    }

    private fun sortDialog() {
        val labels = arrayOf("Newest first", "Oldest first", "Name A–Z", "Name Z–A", "Longest first", "Shortest first", "Largest first", "Smallest first")
        val keys = arrayOf("date_desc", "date_asc", "name_asc", "name_desc", "duration_desc", "duration_asc", "size_desc", "size_asc")
        VelaDialogBuilder(this).setTitle("Sort by")
            .setSingleChoiceItems(labels, keys.indexOf(sort).coerceAtLeast(0)) { d, i ->
                sort = keys[i]
                Prefs.sp(this).edit().putString("library_sort", sort).apply()
                refresh(); d.dismiss()
            }.show()
    }

    private fun urlDialog() {
        val v = DialogUrlBinding.inflate(layoutInflater)
        VelaDialogBuilder(this).setTitle("Open network stream")
            .setMessage("Direct video links (MP4, MKV, WebM…), HLS (.m3u8) and DASH (.mpd) are supported. Web pages and DRM streams are not.")
            .setView(v.root)
            .setPositiveButton("Play") { _, _ ->
                val url = v.urlInput.text?.toString()?.trim().orEmpty()
                val uri = Uri.parse(url)
                if (uri.scheme == "http" || uri.scheme == "https") {
                    val title = uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: uri.host ?: "Stream"
                    play(listOf(Entry(url, title)), 0, source = PlayerSession.SOURCE_EXTERNAL, sourceId = null)
                } else toast("Enter a link starting with http:// or https://")
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------------ playing

    /** Playback context of the screen that's showing (see [PlayerSession]). */
    private fun currentSource(): Pair<String, String?> = when {
        tab == Tab.FOLDERS && openFolder != null -> PlayerSession.SOURCE_FOLDER to openFolder
        tab == Tab.FOLDERS || tab == Tab.ALL -> PlayerSession.SOURCE_ALL to null
        tab == Tab.FAVORITES -> PlayerSession.SOURCE_FAVORITES to null
        openPlaylist != null -> PlayerSession.SOURCE_PLAYLIST to openPlaylist
        else -> PlayerSession.SOURCE_PLAYLIST to null
    }

    private fun play(entries: List<Entry>, index: Int, shuffled: Boolean = false,
                     source: String = currentSource().first, sourceId: String? = currentSource().second) {
        if (entries.isEmpty()) { toast("Nothing to play"); return }
        // Keep the queue a sensible size for the intent.
        val from = (index - 250).coerceAtLeast(0)
        val to = (from + 500).coerceAtMost(entries.size)
        val window = entries.subList(from, to)
        // v2.3: shuffle sends the saved order + EXTRA_SHUFFLE_PLAY (player shuffles; turning shuffle off restores the order).
        val i = Intent(this, PlayerActivity::class.java)
            .putStringArrayListExtra(PlayerActivity.EXTRA_URIS, ArrayList(window.map { it.uri }))
            .putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, ArrayList(window.map { it.title }))
            .putExtra(PlayerActivity.EXTRA_SHUFFLE_PLAY, shuffled)
            .putExtra(PlayerSession.EXTRA_SOURCE, source)
            .putExtra(PlayerSession.EXTRA_SOURCE_ID, sourceId)
        if (!shuffled) i.putExtra(PlayerActivity.EXTRA_INDEX, index - from)
        rememberScroll(); saveUiState()
        // v2.12: the player grows out of the tapped thumbnail (system scale-up transition; plain launch otherwise).
        val from0 = launchFrom; launchFrom = null
        val opts = if (from0 != null && from0.isAttachedToWindow && from0.width > 0 && Motion.enabled(this))
            androidx.core.app.ActivityOptionsCompat.makeScaleUpAnimation(from0, 0, 0, from0.width, from0.height).toBundle() else null
        startActivity(i, opts)
    }

    /** Play all / Shuffle for the open folder or playlist, in the order shown. Unavailable videos are left out. */
    private fun playCollection(shuffle: Boolean) {
        val all = adapter.rows.filterIsInstance<Row.Video>()
        val playable = all.filter { !it.unavailable }.map { it.entry }
        if (playable.isEmpty()) { toast(if (all.isEmpty()) "Nothing to play here" else "None of these videos can be found on the phone"); return }
        if (playable.size < all.size) toast("Skipping ${all.size - playable.size} unavailable video(s)")
        play(playable, 0, shuffled = shuffle)
    }

    private fun onVideoClick(position: Int) {
        val row = adapter.rows.getOrNull(position) as? Row.Video ?: return
        if (reordering) return
        if (selecting) { toggleSelection(position); return }
        if (row.unavailable) { toast("This video was moved or deleted. Tap ⋮ to remove it."); return }
        val videos = adapter.rows.filterIsInstance<Row.Video>().filter { !it.unavailable }
        launchFrom = b.list.findViewHolderForAdapterPosition(position)?.itemView?.let { it.findViewById<View>(R.id.thumb) ?: it }
        play(videos.map { it.entry }, videos.indexOf(row).coerceAtLeast(0))
    }

    /** v2.12: the view the next player launch grows out of (set by a thumbnail tap, used once). */
    private var launchFrom: View? = null
    /** v2.12: 0 = a tab's top level, 1 = inside a folder/playlist; drives the zoom direction of screen changes. */
    private var screenDepth = -1

    private fun openPicker(playlist: String) {
        startActivity(Intent(this, VideoPickerActivity::class.java).putExtra(VideoPickerActivity.EXTRA_PLAYLIST, playlist))
    }

    // ------------------------------------------------------------------ multi-select

    private fun selKey(r: Row): String? = when (r) {
        is Row.Video -> "v:" + r.entry.uri
        is Row.Playlist -> "p:" + r.name
        else -> null
    }

    /** Playlists list selects playlists; every other screen selects videos. */
    private fun selectingPlaylists() = tab == Tab.PLAYLISTS && openPlaylist == null

    private fun selectableRows(): List<Row> = adapter.rows.filter { if (selectingPlaylists()) it is Row.Playlist else it is Row.Video }

    private fun startSelection(first: Row?) {
        if (reordering) return
        if (selectableRows().isEmpty()) { toast("Nothing to select here"); return }
        if (actionMode == null) actionMode = startSupportActionMode(selectionCallback)
        b.fab.hide()
        first?.let { selKey(it)?.let { k -> selected.add(k) } }
        adapter.notifyItemRangeChanged(0, adapter.itemCount, PAYLOAD_SEL)
        updateActionMode()
    }

    private fun toggleSelection(position: Int) {
        val r = adapter.rows.getOrNull(position) ?: return
        val k = selKey(r) ?: return
        if (selectingPlaylists() != (r is Row.Playlist)) return
        if (!selected.remove(k)) selected.add(k)
        Motion.haptic(b.list)
        adapter.notifyItemChanged(position, PAYLOAD_SEL)
        updateActionMode()
    }

    private fun updateActionMode() {
        val m = actionMode ?: return
        val n = selected.size
        m.title = if (n == 0) "Select items" else "$n selected"
        val total = selectableRows().size
        m.subtitle = if (total > 0) "of $total" else null
        m.invalidate()
    }

    private fun selectedVideos(): List<Row.Video> {
        val set = selected
        return adapter.rows.filterIsInstance<Row.Video>().filter { ("v:" + it.entry.uri) in set }
            .sortedBy { set.indexOf("v:" + it.entry.uri) }
    }
    private fun selectedPlaylists(): List<String> = selected.filter { it.startsWith("p:") }.map { it.removePrefix("p:") }

    private val selectionCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean { mode.menuInflater.inflate(R.menu.selection_menu, menu); return true }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            val any = selected.isNotEmpty()
            val allSelected = selected.size >= selectableRows().size && any
            menu.findItem(R.id.sel_all).apply { title = if (allSelected) "Deselect all" else "Select all" }
            for (id in SEL_IDS) menu.findItem(id).isVisible = selActionVisible(id)
            return true
        }
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean { runSelAction(item.itemId); return true }
        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
            selected.clear()
            adapter.notifyItemRangeChanged(0, adapter.itemCount, PAYLOAD_SEL)
            if (tab == Tab.PLAYLISTS && openPlaylist == null) b.fab.show()
        }
    }

    private val SEL_IDS = intArrayOf(R.id.sel_playlist, R.id.sel_play, R.id.sel_share, R.id.sel_fav, R.id.sel_unfav, R.id.sel_watched,
        R.id.sel_unwatched, R.id.sel_remove, R.id.sel_delete, R.id.sel_delete_playlists, R.id.sel_clear)

    /** v2.17.1: single source of truth for which bulk actions apply to the CURRENT selection (action bar + long-press sheet). */
    private fun selActionVisible(id: Int): Boolean {
        val pl = selectingPlaylists()
        val vids = if (pl) emptyList() else selectedVideos()
        val any = selected.isNotEmpty()
        return when (id) {
            R.id.sel_playlist -> !pl && any
            R.id.sel_play -> any
            R.id.sel_share -> !pl && vids.any { !it.unavailable && (it.entry.uri.startsWith("content") || it.entry.uri.startsWith("file")) }
            R.id.sel_fav -> !pl && vids.any { !it.favorite }
            R.id.sel_unfav -> !pl && vids.any { it.favorite }
            R.id.sel_watched -> !pl && vids.any { !it.watched }
            R.id.sel_unwatched -> !pl && vids.any { it.watched }
            R.id.sel_remove -> !pl && any && openPlaylist != null
            R.id.sel_delete -> !pl && vids.any { it.item != null }
            R.id.sel_delete_playlists -> pl && any
            R.id.sel_clear -> any
            else -> false
        }
    }

    /** v2.17.1: runs a bulk action on EVERY selected item. Used by the selection action bar and the long-press options sheet. */
    private fun runSelAction(id: Int) {
            val mode = actionMode ?: return
            val vids = selectedVideos()
            when (id) {
                R.id.sel_all -> {
                    val keys = selectableRows().mapNotNull { selKey(it) }
                    if (selected.containsAll(keys)) selected.clear() else selected.addAll(keys)
                    adapter.notifyItemRangeChanged(0, adapter.itemCount, PAYLOAD_SEL); updateActionMode()
                }
                R.id.sel_clear -> { mode.finish() }
                R.id.sel_play -> {
                    if (selectingPlaylists()) {
                        val pls = Store.playlists(this@MainActivity)
                        val entries = Vault.visibleEntries(this@MainActivity, selectedPlaylists().flatMap { pls[it] ?: emptyList() }).filter { !isUnavailable(it.uri) }.distinctBy { it.uri }
                        val names = selectedPlaylists()
                        play(entries, 0, source = PlayerSession.SOURCE_PLAYLIST, sourceId = names.singleOrNull())
                    } else play(vids.filter { !it.unavailable }.map { it.entry }, 0)
                }
                R.id.sel_playlist -> addManyToPlaylistDialog(vids.map { it.entry })
                R.id.sel_share -> share(vids.mapNotNull { Uri.parse(it.entry.uri).takeIf { u -> u.scheme == "content" || u.scheme == "file" } })
                R.id.sel_fav -> { Store.setFavorites(this@MainActivity, vids.map { it.entry }, true); toast("Added ${vids.size} to favorites"); refresh() }
                R.id.sel_unfav -> { Store.setFavorites(this@MainActivity, vids.map { it.entry }, false); toast("Removed ${vids.size} from favorites"); refresh() }
                R.id.sel_watched -> { Store.setWatchedMany(this@MainActivity, vids.map { it.entry.uri }, true); refresh() }
                R.id.sel_unwatched -> { Store.setWatchedMany(this@MainActivity, vids.map { it.entry.uri }, false); refresh() }
                R.id.sel_remove -> {
                    val pl = openPlaylist ?: return
                    VelaDialogBuilder(this@MainActivity).setTitle("Remove ${vids.size} from \"$pl\"?")
                        .setMessage("They're only taken out of this playlist. The video files stay on your phone.")
                        .setPositiveButton("Remove") { _, _ ->
                            Store.removeManyFromPlaylist(this@MainActivity, pl, vids.map { it.entry.uri })
                            toast("Removed ${vids.size} from the playlist"); refresh()
                        }.setNegativeButton("Cancel", null).show()
                }
                R.id.sel_delete -> {
                    val files = vids.mapNotNull { it.item }
                    val size = Formatter.formatShortFileSize(this@MainActivity, files.sumOf { it.size })
                    confirmDelete(files, "Delete ${files.size} video${if (files.size == 1) "" else "s"}?",
                        "${files.size} file${if (files.size == 1) "" else "s"} ($size) will be permanently deleted from your phone. This can't be undone." +
                            if (files.size < vids.size) "\n\nStreams and files outside your library aren't affected." else "")
                }
                R.id.sel_delete_playlists -> {
                    val names = selectedPlaylists()
                    VelaDialogBuilder(this@MainActivity).setTitle("Delete ${names.size} playlist${if (names.size == 1) "" else "s"}?")
                        .setMessage("Only the playlists are removed. Your video files stay on the phone.")
                        .setPositiveButton("Delete") { _, _ -> names.forEach { Store.deletePlaylist(this@MainActivity, it) }; toast("Deleted"); refresh() }
                        .setNegativeButton("Cancel", null).show()
                }
            }
    }

    /** v2.17.1: options sheet for long-press on an already-selected video while 2+ videos are selected.
     *  Every action targets the whole selection; single-video-only actions (Rename) are left out and named in the header. */
    private fun onSelectionOptions() {
        val vids = selectedVideos()
        if (vids.size < 2) { vids.firstOrNull()?.let { onVideoOptions(it) }; return }
        val a = ArrayList<Action>()
        fun add(id: Int, label: String, icon: Int, danger: Boolean = false) { if (selActionVisible(id)) a += Action(label, icon, danger) { runSelAction(id) } }
        val playable = vids.filter { !it.unavailable }
        add(R.id.sel_play, "Play ${playable.size} selected", R.drawable.ic_play)
        if (playable.any { it.progress > 0f }) a += Action("Play selected from start", R.drawable.ic_replay) {
            playable.forEach { Store.clearPosition(this, it.entry.uri) }; play(playable.map { it.entry }, 0)
        }
        a += GROUP
        add(R.id.sel_fav, "Add to favorites", R.drawable.ic_star)
        add(R.id.sel_unfav, "Remove from favorites", R.drawable.ic_star)
        add(R.id.sel_playlist, "Add to playlist", R.drawable.ic_playlist_add)
        add(R.id.sel_remove, "Remove from this playlist", R.drawable.ic_close)
        add(R.id.sel_watched, "Mark as watched", R.drawable.ic_check_circle)
        add(R.id.sel_unwatched, "Mark as unwatched", R.drawable.ic_check_circle)
        val inProgress = vids.filter { it.progress > 0f }
        if (inProgress.isNotEmpty()) a += Action("Remove from Continue watching", R.drawable.ic_close) {
            inProgress.forEach { Store.clearPosition(this, it.entry.uri) }
            toast("Removed ${inProgress.size} from Continue watching"); refresh()
        }
        a += GROUP
        add(R.id.sel_share, "Share", R.drawable.ic_share)
        a += Action("Properties", R.drawable.ic_info) { selectionDetailsDialog(vids) }
        a += Action("Select all", R.drawable.ic_select_all) { val keys = selectableRows().mapNotNull { selKey(it) }; selected.addAll(keys)
            adapter.notifyItemRangeChanged(0, adapter.itemCount, PAYLOAD_SEL); updateActionMode() }
        a += Action("Clear selection", R.drawable.ic_close) { actionMode?.finish() }
        if (selActionVisible(R.id.sel_delete)) { a += GROUP; add(R.id.sel_delete, "Delete ${vids.count { it.item != null }} videos", R.drawable.ic_delete, danger = true) }
        val bytes = vids.sumOf { it.item?.size ?: 0L }
        val sub = listOfNotNull(bytes.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(this, it) }, "Rename works on one video at a time").joinToString(" · ")
        showSheet(ItemSheet.Header("${vids.size} videos selected", sub, R.drawable.ic_select_all), a)
    }

    private fun selectionDetailsDialog(vids: List<Row.Video>) {
        val items = vids.mapNotNull { it.item }
        val sb = StringBuilder()
        sb.append("Videos: ").append(vids.size).append('\n')
        val dur = items.sumOf { it.durationMs.coerceAtLeast(0L) }
        if (dur > 0) sb.append("Total length: ").append(Fmt.time(dur)).append('\n')
        val size = items.sumOf { it.size.coerceAtLeast(0L) }
        if (size > 0) sb.append("Total size: ").append(Formatter.formatFileSize(this, size)).append('\n')
        sb.append("Watched: ").append(vids.count { it.watched }).append(" of ").append(vids.size).append('\n')
        val missing = vids.count { it.unavailable }
        if (missing > 0) sb.append("Unavailable: ").append(missing).append('\n')
        sb.append('\n').append(vids.take(8).joinToString("\n") { "• " + it.entry.title })
        if (vids.size > 8) sb.append("\n…and ").append(vids.size - 8).append(" more")
        VelaDialogBuilder(this).setTitle("Properties").setMessage(sb.toString()).setPositiveButton("Close", null).show()
    }

    // ------------------------------------------------------------------ option sheets

    private class Action(val label: String, val icon: Int, val danger: Boolean = false, val run: () -> Unit)

    /** v2.13: marks the start of a new group in an options list (groups are separated by spacing in [ItemSheet]). */
    private val GROUP = Action("", 0) { }

    /** Options sheet used by long-press and the ⋮ button (v2.13: Midnight Glass [ItemSheet]). */
    private fun showSheet(header: ItemSheet.Header, actions: List<Action>) {
        val groups = ArrayList<ArrayList<ItemSheet.Action>>()
        groups.add(ArrayList())
        actions.forEach { a -> if (a === GROUP) groups.add(ArrayList()) else groups.last().add(ItemSheet.Action(a.label, a.icon, a.danger, a.run)) }
        ItemSheet(this).show(header, groups.filter { it.isNotEmpty() })
    }


    private fun onVideoLongPress(row: Row.Video, position: Int) {
        if (reordering) return
        // v2.17: long-press SELECTS a video; long-pressing a video that is already selected opens its options.
        // The ⋮ button still opens options directly; tap still toggles while selecting.
        if (selecting) {
            val k = selKey(row)
            if (k != null && selected.contains(k)) { if (selectedVideos().size > 1) onSelectionOptions() else onVideoOptions(row) } else toggleSelection(position)
            return
        }
        startSelection(row)
    }

    private fun onVideoOptions(row: Row.Video) {
        val item = row.item
        val a = ArrayList<Action>()
        if (!row.unavailable) {
            a += Action("Play", R.drawable.ic_play) { play(listOf(row.entry), 0) }
            if (row.progress > 0f) a += Action("Play from start", R.drawable.ic_replay) {
                Store.clearPosition(this, row.entry.uri); play(listOf(row.entry), 0)
            }
        }
        a += GROUP
        a += Action("Select", R.drawable.ic_select) { startSelection(row) }
        a += Action(if (row.favorite) "Remove from favorites" else "Add to favorites", R.drawable.ic_star) {
            val added = Store.toggleFavorite(this, row.entry)
            toast(if (added) "Added to favorites" else "Removed from favorites"); refresh()
        }
        a += Action("Add to playlist", R.drawable.ic_playlist_add) { addManyToPlaylistDialog(listOf(row.entry)) }
        openPlaylist?.let { pl ->
            a += Action("Remove from this playlist", R.drawable.ic_close) {
                Store.removeFromPlaylist(this, pl, row.entry.uri)
                toast("Removed from playlist (the file is untouched)"); refresh()
            }
        }
        if (!row.unavailable) a += Action(if (row.watched) "Mark as unwatched" else "Mark as watched", R.drawable.ic_check_circle) {
            Store.setWatched(this, row.entry.uri, !row.watched); refresh()
        }
        if (row.progress > 0f) a += Action("Remove from Continue watching", R.drawable.ic_close) { Store.clearPosition(this, row.entry.uri); refresh() }
        val shareable = !row.unavailable && Uri.parse(row.entry.uri).scheme.let { it == "content" || it == "file" }
        a += GROUP
        if (shareable) a += Action("Share", R.drawable.ic_share) { share(listOf(Uri.parse(row.entry.uri))) }
        if (item != null) a += Action("Rename", R.drawable.ic_edit) { renameVideoDialog(item) }
        a += Action("Properties", R.drawable.ic_info) { detailsDialog(row) }
        if (item != null) {
            a += GROUP
            a += Action("Delete", R.drawable.ic_delete, danger = true) { confirmDelete(listOf(item), "Delete \"${item.name}\"?") }
        }
        val sub = if (row.unavailable) "Unavailable — moved or deleted" else item?.let { listOfNotNull(it.durationMs.takeIf { d -> d > 0 }?.let { d -> Fmt.time(d) }, it.size.takeIf { z -> z > 0 }?.let { z -> Formatter.formatShortFileSize(this, z) }).joinToString(" · ") }
        val thumbUri = if (row.unavailable) null else item?.uri ?: Uri.parse(row.entry.uri).takeIf { it.scheme == "content" || it.scheme == "file" }
        showSheet(ItemSheet.Header(row.entry.title, sub?.takeIf { it.isNotEmpty() }, R.drawable.ic_movie,
            thumbUri?.let { u -> { iv: ImageView -> ThumbLoader.load(iv, u, item?.id ?: -1L, item?.durationMs ?: 0L) } }), a)
    }

    private fun onFolderLongClick(row: Row.Folder) {
        val f = row.folder
        val sortedEntries = { f.videos.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }).map { Entry(it.uri.toString(), it.name) } }
        val allWatched = row.watched >= f.videos.size && f.videos.isNotEmpty()
        val a = ArrayList<Action>()
        a += Action("Play all", R.drawable.ic_play) { play(sortedEntries(), 0, source = PlayerSession.SOURCE_FOLDER, sourceId = f.id) }
        a += Action("Shuffle play", R.drawable.ic_shuffle) { play(sortedEntries(), 0, shuffled = true, source = PlayerSession.SOURCE_FOLDER, sourceId = f.id) }
        a += Action("Open", R.drawable.ic_folder) { openFolder = f.id; filter = "all"; refresh() }
        a += GROUP
        a += Action("Add all to playlist", R.drawable.ic_playlist_add) { addManyToPlaylistDialog(sortedEntries()) }
        a += Action(if (allWatched) "Mark all as unwatched" else "Mark all as watched", R.drawable.ic_check_circle) {
            Store.setWatchedMany(this, f.videos.map { it.uri.toString() }, !allWatched); refresh()
        }
        a += Action("Share all", R.drawable.ic_share) { share(f.videos.map { it.uri }) }
        a += GROUP
        a += Action("Rename folder", R.drawable.ic_edit) { renameFolderDialog(f) }
        a += Action("Properties", R.drawable.ic_info) {
            val size = f.videos.sumOf { it.size }
            val dur = f.videos.sumOf { it.durationMs }
            val msg = "Videos available: ${f.videos.size}\nWatched: ${row.watched}\nIn progress: ${row.inProgress}\n" +
                "Total length: ${Fmt.time(dur)}\nTotal size: ${Formatter.formatFileSize(this, size)}" +
                (f.path?.let { "\nPath: $it" } ?: "")
            VelaDialogBuilder(this).setTitle("Folder properties").setMessage(msg).setPositiveButton("Close", null).show()
        }
        a += GROUP
        a += Action("Delete folder", R.drawable.ic_delete, danger = true) {
            confirmDelete(f.videos, "Delete folder \"${f.name}\"?", "All ${f.videos.size} videos in this folder will be permanently deleted from your phone.")
        }
        val first = f.videos.firstOrNull()
        showSheet(ItemSheet.Header(f.name, f.path, R.drawable.ic_folder, first?.let { v -> { iv: ImageView -> ThumbLoader.load(iv, v) } }), a)
    }

    private fun onPlaylistLongPress(row: Row.Playlist, position: Int) {
        if (selecting) { toggleSelection(position); return }
        if (Prefs.longPressAction(this) == "select") { startSelection(row); return }
        onPlaylistOptions(row)
    }

    private fun onPlaylistOptions(row: Row.Playlist) {
        val entries = { Vault.visibleEntries(this, Store.playlists(this)[row.name] ?: emptyList()).filter { !isUnavailable(it.uri) } }
        val a = ArrayList<Action>()
        a += Action("Play all", R.drawable.ic_play) { play(entries(), 0, source = PlayerSession.SOURCE_PLAYLIST, sourceId = row.name) }
        a += Action("Shuffle play", R.drawable.ic_shuffle) { play(entries(), 0, shuffled = true, source = PlayerSession.SOURCE_PLAYLIST, sourceId = row.name) }
        a += GROUP
        a += Action("Add videos", R.drawable.ic_playlist_add) { openPicker(row.name) }
        if (row.count > 1) a += Action("Edit order", R.drawable.l23_ic_drag_handle) {
            openPlaylist = row.name; filter = "all"; refresh(); b.list.post { startReorder() }
        }
        a += Action("Select", R.drawable.ic_select) { startSelection(row) }
        a += Action("Rename", R.drawable.ic_edit) { newPlaylistDialog(row.name) }
        a += GROUP
        a += Action("Delete playlist", R.drawable.ic_delete, danger = true) {
            VelaDialogBuilder(this).setTitle("Delete \"${row.name}\"?")
                .setMessage("Only the playlist is removed. Your video files stay on the phone.")
                .setPositiveButton("Delete") { _, _ -> Store.deletePlaylist(this, row.name); refresh() }
                .setNegativeButton("Cancel", null).show()
        }
        showSheet(ItemSheet.Header(row.name, countText(row.count) + if (row.durationMs > 0) " · " + Fmt.time(row.durationMs) else "", R.drawable.ic_playlist,
            row.covers.takeIf { it.isNotEmpty() }?.let { cv -> { iv: ImageView -> ThumbLoader.loadCover(iv, cv, Prefs.playlistCovers(this)) } }), a)
    }

    // ------------------------------------------------------------------ file actions (share / rename / delete)

    private fun share(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        i.type = "video/*"; i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(Intent.createChooser(i, "Share")) } catch (_: Exception) { toast("No app to share with") }
    }

    /** Old Android (10 and below) edits files directly and needs the storage permission first. */
    private fun withLegacyWrite(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) action()
        else { afterWritePerm = action; writePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
    }

    private fun textDialog(title: String, initial: String, selectEnd: Int, onOk: (String) -> Unit) {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT; setSingleLine(); setText(initial); Glass.styleField(this) }
        val box = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0); addView(input)
        }
        val dlg = VelaDialogBuilder(this).setTitle(title).setView(box)
            .setPositiveButton("Rename") { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton("Cancel", null).create()
        dlg.setOnShowListener {
            input.requestFocus(); input.setSelection(0, selectEnd.coerceIn(0, initial.length))
            dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dlg.show()
    }

    private val badChars = Regex("[\\\\/:*?\"<>|]")

    private fun renameVideoDialog(item: VideoItem) {
        val dot = item.name.lastIndexOf('.')
        val ext = if (dot > 0) item.name.substring(dot) else ""
        textDialog("Rename video", item.name, if (dot > 0) dot else item.name.length) { raw ->
            var name = raw
            if (name.isEmpty()) { toast("Name can't be empty"); return@textDialog }
            if (badChars.containsMatchIn(name)) { toast("Name can't contain \\ / : * ? \" < > |"); return@textDialog }
            if (ext.isNotEmpty() && !name.endsWith(ext, ignoreCase = true)) name += ext
            if (name == item.name) return@textDialog
            renameVideo(item, name)
        }
    }

    private fun renameVideo(item: VideoItem, newName: String) {
        if (Build.VERSION.SDK_INT >= 30) {
            afterGrant = { applyVideoRename(item, newName) }
            launchGrant(MediaStore.createWriteRequest(contentResolver, listOf(item.uri)).intentSender)
        } else withLegacyWrite { applyVideoRename(item, newName) }
    }

    private fun applyVideoRename(item: VideoItem, newName: String) {
        io.execute {
            val ok = try {
                if (Build.VERSION.SDK_INT >= 30) {
                    contentResolver.update(item.uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }, null, null) > 0
                } else {
                    val old = item.path?.let { File(it) }
                    val dest = old?.let { File(it.parentFile, newName) }
                    if (old == null || dest == null || dest.exists()) false
                    else if (old.renameTo(dest)) {
                        try {
                            contentResolver.update(item.uri, ContentValues().apply {
                                put(MediaStore.MediaColumns.DATA, dest.absolutePath); put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
                            }, null, null)
                        } catch (_: Exception) { }
                        MediaScannerConnection.scanFile(this, arrayOf(old.absolutePath, dest.absolutePath), null, null)
                        true
                    } else false
                }
            } catch (_: Exception) { false }
            main.post {
                if (ok) { Store.renameTitle(this, item.uri.toString(), newName); toast("Renamed"); loadLibrary() }
                else toast("Couldn't rename. A file with that name may already exist.")
            }
        }
    }

    private fun renameFolderDialog(f: VideoFolder) {
        if (Build.VERSION.SDK_INT == 29) { toast("Renaming folders isn't supported on Android 10"); return }
        textDialog("Rename folder", f.name, f.name.length) { raw ->
            val name = raw
            if (name.isEmpty()) { toast("Name can't be empty"); return@textDialog }
            if (badChars.containsMatchIn(name) || name.startsWith(".")) { toast("That folder name isn't allowed"); return@textDialog }
            if (name == f.name) return@textDialog
            if (Build.VERSION.SDK_INT >= 30) {
                afterGrant = { applyFolderRename(f, name) }
                launchGrant(MediaStore.createWriteRequest(contentResolver, f.videos.map { it.uri }).intentSender)
            } else withLegacyWrite { applyFolderRename(f, name) }
        }
    }

    /** Android 11+: moves the folder's videos to a sibling folder with the new name. Older: renames the directory. */
    private fun applyFolderRename(f: VideoFolder, newName: String) {
        io.execute {
            var moved = 0
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    f.videos.forEach { v ->
                        val rel = contentResolver.query(v.uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { c ->
                            if (c.moveToFirst()) c.getString(0) else null
                        } ?: return@forEach
                        val parts = rel.trimEnd('/').split('/')
                        if (parts.isEmpty()) return@forEach
                        val newRel = (parts.dropLast(1) + newName).joinToString("/") + "/"
                        try {
                            if (contentResolver.update(v.uri, ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, newRel) }, null, null) > 0) moved++
                        } catch (_: Exception) { }
                    }
                } else {
                    val dir = f.path?.let { File(it) }
                    val dest = dir?.let { File(it.parentFile, newName) }
                    if (dir != null && dest != null && !dest.exists() && dir.renameTo(dest)) {
                        moved = f.videos.size
                        val paths = f.videos.mapNotNull { it.path } + f.videos.mapNotNull { v -> v.path?.let { File(dest, File(it).name).absolutePath } }
                        MediaScannerConnection.scanFile(this, paths.toTypedArray(), null, null)
                    }
                }
            } catch (_: Exception) { }
            main.post {
                when {
                    moved == 0 -> toast("Couldn't rename this folder. Android may not allow it in this location.")
                    moved < f.videos.size -> toast("Moved $moved of ${f.videos.size} videos to \"$newName\"")
                    else -> toast("Folder renamed")
                }
                openFolder = null
                main.postDelayed({ loadLibrary() }, if (Build.VERSION.SDK_INT >= 30) 0L else 1500L)
            }
        }
    }

    private fun confirmDelete(items: List<VideoItem>, title: String, message: String = "This video will be permanently deleted from your phone.") {
        if (items.isEmpty()) return
        VelaDialogBuilder(this).setTitle(title).setMessage(message)
            .setPositiveButton("Delete") { _, _ -> deleteVideos(items) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun deleteVideos(items: List<VideoItem>) {
        if (Build.VERSION.SDK_INT >= 30) {
            // Android shows its own confirmation and deletes the files when the user allows it.
            afterGrant = { onDeleted(items) }
            launchGrant(MediaStore.createDeleteRequest(contentResolver, items.map { it.uri }).intentSender)
        } else withLegacyWrite {
            io.execute {
                val gone = items.filter { v ->
                    try {
                        v.path?.let { File(it).delete() }
                        contentResolver.delete(v.uri, null, null)
                        v.path?.let { !File(it).exists() } ?: true
                    } catch (_: Exception) { false }
                }
                main.post {
                    if (gone.size < items.size) toast("Deleted ${gone.size} of ${items.size}")
                    onDeleted(gone)
                }
            }
        }
    }

    private fun onDeleted(items: List<VideoItem>) {
        if (items.isEmpty()) return
        Store.forget(this, items.map { it.uri.toString() })
        val ids = items.map { it.uri.toString() }.toSet()
        library = library.filter { it.uri.toString() !in ids }
        byUri = library.associateBy { it.uri.toString() }
        toast(if (items.size == 1) "Deleted" else "Deleted ${items.size} videos")
        refresh(); loadLibrary()
    }

    private fun launchGrant(sender: android.content.IntentSender) {
        try { grantLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
        catch (_: Exception) { afterGrant = null; toast("Couldn't ask for permission") }
    }


    // ------------------------------------------------------------------ Hidden Vault (v2.2)

    /** v2.5: hide videos — choose "Hide in Vela only" or "Move to Vela's private storage" (see [VaultStorage]). */
    private val vaultReq = VaultStorage.Requester(this)

    // v2.15: "Hide in vault" is offered only inside the Vault (Vault › Add videos); kept for that architecture.
    @Suppress("unused")
    private fun confirmHide(entries: List<Entry>) {
        val list = entries.distinctBy { it.uri }
        if (list.isEmpty()) return
        VaultStorage.chooseAndHide(this, vaultReq, list, allowApp = true, onAppHide = { l ->
            val added = Vault.hide(this, l)
            actionMode?.finish()
            val gone = l.map { it.uri }.toHashSet()
            library = library.filter { it.uri.toString() !in gone }
            byUri = library.associateBy { it.uri.toString() }
            refresh()
            Snackbar.make(b.root, if (added == 0) "Already hidden" else if (added == 1) "Hidden in Vela (still visible in other apps)" else "$added videos hidden in Vela (still visible in other apps)", Snackbar.LENGTH_SHORT).show()
        }, onPrivateDone = { moved ->
            actionMode?.finish()
            if (moved > 0) {
                val gone = Vault.hiddenSet(this)
                library = library.filter { it.uri.toString() !in gone }
                byUri = library.associateBy { it.uri.toString() }
            }
            refresh(); loadLibrary()
        })
    }

    // ------------------------------------------------------------------ playlists

    /** Adds one or many videos to a playlist the user picks (or a new one). Duplicates are skipped and reported. */
    private fun addManyToPlaylistDialog(entries: List<Entry>) {
        if (entries.isEmpty()) return
        val names = Store.playlists(this).keys.toList()
        val labels = (names + "New playlist…").toTypedArray()
        VelaDialogBuilder(this).setTitle(if (entries.size == 1) "Add to playlist" else "Add ${entries.size} videos to")
            .setItems(labels) { _, i ->
                if (i == names.size) newPlaylistDialog(null, thenAdd = entries)
                else addToPlaylistReport(names[i], entries)
            }.show()
    }

    private fun addToPlaylistReport(name: String, entries: List<Entry>) {
        val (added, skipped) = Store.addManyToPlaylist(this, name, entries, atStart = Prefs.playlistAddAt(this) == "start")
        toast(when {
            added == 0 && skipped > 0 -> if (skipped == 1) "Already in \"$name\"" else "All $skipped are already in \"$name\""
            skipped > 0 -> "Added $added to \"$name\" · $skipped already there"
            else -> if (added == 1) "Added to \"$name\"" else "Added $added videos to \"$name\""
        })
        refresh()
    }

    private fun newPlaylistDialog(renameFrom: String?, thenAdd: List<Entry>? = null, openPickerAfter: Boolean = false) {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; setText(renameFrom ?: ""); hint = "Playlist name"; Glass.styleField(this) }
        val box = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0); addView(input)
        }
        VelaDialogBuilder(this).setTitle(if (renameFrom == null) "New playlist" else "Rename playlist")
            .setView(box)
            .setPositiveButton(if (openPickerAfter) "Next: add videos" else "Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) { toast("Name can't be empty"); return@setPositiveButton }
                val ok = if (renameFrom == null) Store.createPlaylist(this, name) else Store.renamePlaylist(this, renameFrom, name)
                if (!ok) { toast("A playlist with that name already exists"); return@setPositiveButton }
                if (renameFrom != null && openPlaylist == renameFrom) openPlaylist = name
                if (thenAdd != null) addToPlaylistReport(name, thenAdd)
                refresh()
                if (openPickerAfter) openPicker(name)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun detailsDialog(row: Row.Video) {
        val it = row.item
        val sb = StringBuilder()
        sb.append("Name: ").append(row.entry.title).append('\n')
        if (it != null) {
            if (it.durationMs > 0) sb.append("Duration: ").append(Fmt.time(it.durationMs)).append('\n')
            if (it.width > 0) sb.append("Resolution: ").append(it.width).append(" × ").append(it.height).append('\n')
            if (it.size > 0) sb.append("Size: ").append(Formatter.formatFileSize(this, it.size)).append('\n')
            if (it.dateAdded > 0) sb.append("Added: ").append(DateFormat.getMediumDateFormat(this).format(it.dateAdded * 1000)).append('\n')
            it.path?.let { p -> sb.append("Path: ").append(p).append('\n') }
        } else sb.append("Source: ").append(row.entry.uri).append('\n')
        val pos = Store.position(this, row.entry.uri)
        if (pos > 0) sb.append("Resume at: ").append(Fmt.time(pos)).append('\n')
        VelaDialogBuilder(this).setTitle("Details").setMessage(sb.toString().trim()).setPositiveButton("Close", null).show()
    }

    private fun nameOf(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        } catch (_: Exception) { }
        return uri.lastPathSegment ?: "Video"
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()



    // ------------------------------------------------------------------ adapter

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var rows: List<Row> = emptyList()
        var grid = false
        private fun keyOf(r: Row): String = when (r) {
            is Row.Video -> "v:" + r.entry.uri
            is Row.Folder -> "f:" + r.folder.id
            is Row.Playlist -> "p:" + r.name
            is Row.Summary -> "summary"
            is Row.ContinueStrip -> "strip"
            is Row.Header -> "header"
        }
        fun key(r: Row): String = keyOf(r)
        /** v2.3 drag & drop: moves one row (no rebuild, so the dragged view keeps following the finger). */
        fun move(from: Int, to: Int) {
            val l = rows.toMutableList(); val r = l.removeAt(from); l.add(to, r); rows = l
            notifyItemMoved(from, to)
        }
        /** New screen: rebuild and play the staggered entrance. Same screen: animate only what changed. */
        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun submit(r: List<Row>, newScreen: Boolean) {
            val old = rows
            rows = r
            if (newScreen || old.isEmpty()) { notifyDataSetChanged(); return }
            androidx.recyclerview.widget.DiffUtil.calculateDiff(object : androidx.recyclerview.widget.DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = r.size
                override fun areItemsTheSame(o: Int, n: Int) = keyOf(old[o]) == keyOf(r[n])
                override fun areContentsTheSame(o: Int, n: Int) = old[o] == r[n]
            }).dispatchUpdatesTo(this)
        }

        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Row.Video -> if (grid) 10 else 0
            is Row.Playlist -> if (grid) 11 else 1
            is Row.Folder -> if (grid) 12 else 2
            is Row.Summary -> 3
            is Row.ContinueStrip -> 4
            is Row.Header -> 5
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                0 -> VideoVH(VideoViews.of(ItemVideoBinding.inflate(inf, parent, false)))
                10 -> VideoVH(VideoViews.of(ItemVideoGridBinding.inflate(inf, parent, false)))
                2 -> FolderVH(FolderViews.of(ItemFolderBinding.inflate(inf, parent, false)))
                12 -> FolderVH(FolderViews.of(ItemFolderGridBinding.inflate(inf, parent, false)))
                1 -> PlaylistVH(PlaylistViews.of(ItemPlaylistBinding.inflate(inf, parent, false)))
                11 -> PlaylistVH(PlaylistViews.of(ItemPlaylistGridBinding.inflate(inf, parent, false)))
                3 -> SummaryVH(ItemSummaryBinding.inflate(inf, parent, false))
                4 -> StripVH(ItemContinueStripBinding.inflate(inf, parent, false))
                else -> HeaderVH(ItemCollectionHeaderBinding.inflate(inf, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_SEL }) {
                when (holder) { is VideoVH -> holder.bindSelection(); is PlaylistVH -> holder.bindSelection() }
                return
            }
            onBindViewHolder(holder, position)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Video -> (holder as VideoVH).bind(row)
                is Row.Playlist -> (holder as PlaylistVH).bind(row)
                is Row.Folder -> (holder as FolderVH).bind(row)
                is Row.Summary -> (holder as SummaryVH).bind(row)
                is Row.ContinueStrip -> (holder as StripVH).bind(row)
                is Row.Header -> (holder as HeaderVH).bind(row)
            }
        }
    }

    /** Shows the selection badge on a row (both grid and list layouts use the same views). */
    private fun showSelection(root: View, badge: TextView, key: String?) {
        val on = key != null && key in selected
        root.isActivated = on
        badge.isVisible = selecting
        badge.setBackgroundResource(if (on) R.drawable.bg_select_on else R.drawable.bg_select_off)
        badge.text = if (on) "✓" else ""
        root.contentDescription = if (selecting) (if (on) "Selected" else "Not selected") else null
    }

    /** The views a video row needs, from either the list or the grid layout. */
    class VideoViews(val root: View, val thumb: ImageView, val title: TextView, val subtitle: TextView, val duration: TextView,
                     val progress: LinearProgressIndicator, val fav: ImageView, val watched: ImageView, val more: ImageButton, val check: TextView,
                     /** v2.3 drag handle (list layout only; the grid drags with a long press). */
                     val handle: ImageView? = null) {
        companion object {
            fun of(v: ItemVideoBinding) = VideoViews(v.root, v.thumb, v.title, v.subtitle, v.durationBadge, v.progress, v.favIcon, v.watchedIcon, v.more, v.selectCheck, v.l23DragHandle)
            fun of(v: ItemVideoGridBinding) = VideoViews(v.root, v.thumb, v.title, v.subtitle, v.durationBadge, v.progress, v.favIcon, v.watchedIcon, v.more, v.selectCheck)
        }
    }
    class FolderViews(val root: View, val thumb: ImageView, val name: TextView, val count: TextView, val badge: TextView, val bar: LinearProgressIndicator, val more: ImageButton, val path: TextView?, val stack: StackedThumbLayout) {
        companion object {
            fun of(v: ItemFolderBinding) = FolderViews(v.root, v.thumb, v.name, v.count, v.countBadge, v.watchedBar, v.more, v.path, v.stack)
            fun of(v: ItemFolderGridBinding) = FolderViews(v.root, v.thumb, v.name, v.count, v.countBadge, v.watchedBar, v.more, null, v.stack)
        }
    }
    class PlaylistViews(val root: View, val cover: ImageView, val name: TextView, val count: TextView, val badge: TextView, val more: ImageButton, val check: TextView,
                        val progress: LinearProgressIndicator, val progressText: TextView, val stack: StackedThumbLayout) {
        companion object {
            fun of(v: ItemPlaylistBinding) = PlaylistViews(v.root, v.cover, v.name, v.count, v.countBadge, v.more, v.selectCheck, v.playlistProgress, v.playlistProgressText, v.stack)
            fun of(v: ItemPlaylistGridBinding) = PlaylistViews(v.root, v.cover, v.name, v.count, v.countBadge, v.more, v.selectCheck, v.playlistProgress, v.playlistProgressText, v.stack)
        }
    }

    private inner class VideoVH(val v: VideoViews) : RecyclerView.ViewHolder(v.root) {
        private var row: Row.Video? = null
        init {
            v.root.setOnClickListener { val p = bindingAdapterPosition; if (p != RecyclerView.NO_POSITION) onVideoClick(p) }
            v.root.setOnLongClickListener {
                val p = bindingAdapterPosition; val r = row
                if (reordering) { if (p != RecyclerView.NO_POSITION) { Motion.haptic(it, true); touchHelper.startDrag(this) }; return@setOnLongClickListener true }
                if (p != RecyclerView.NO_POSITION && r != null) { Motion.haptic(it, true); onVideoLongPress(r, p) }; true
            }
            v.more.setOnClickListener { row?.let { onVideoOptions(it) } }
            Motion.pressable(v.root, 0.97f)
            v.handle?.setOnTouchListener { h, e ->
                if (reordering && e.actionMasked == android.view.MotionEvent.ACTION_DOWN) { Motion.haptic(h); touchHelper.startDrag(this) }
                false
            }
        }
        fun bind(row: Row.Video) {
            this.row = row
            v.title.text = row.entry.title
            val item = row.item
            val parts = ArrayList<String>()
            if (row.unavailable) parts += "Unavailable · moved or deleted"
            else if (item != null) {
                if (adapter.grid) {
                    if (item.height > 0) parts += "${item.height}p"
                    if (item.size > 0) parts += Formatter.formatShortFileSize(itemView.context, item.size)
                } else {
                    if (item.height > 0) parts += "${item.height}p"
                    if (item.size > 0) parts += Formatter.formatShortFileSize(itemView.context, item.size)
                    if (item.dateAdded > 0) parts += DateFormat.getMediumDateFormat(itemView.context).format(item.dateAdded * 1000)
                }
            } else {
                val u = Uri.parse(row.entry.uri)
                parts += if (u.scheme == "http" || u.scheme == "https") "Stream · ${u.host}" else "File"
            }
            v.subtitle.text = parts.joinToString(" · ")
            v.root.alpha = if (row.unavailable) 0.55f else 1f
            val dur = item?.durationMs ?: Store.duration(itemView.context, row.entry.uri)
            v.duration.isVisible = dur > 0
            v.duration.text = Fmt.time(dur)
            v.progress.isVisible = row.progress > 0f
            v.progress.progress = (row.progress * 1000).toInt()
            v.fav.isVisible = row.favorite
            v.watched.isVisible = row.watched && Prefs.showWatchStats(itemView.context)
            ThumbLoader.load(v.thumb, item?.uri ?: Uri.parse(row.entry.uri), item?.id ?: -1L, item?.durationMs ?: 0L)
            bindSelection()
        }
        fun bindSelection() {
            showSelection(v.root, v.check, row?.let { selKey(it) })
            v.more.isVisible = !selecting && !reordering
            v.handle?.isVisible = reordering
            if (reordering) v.root.contentDescription = "${row?.entry?.title ?: "Video"}. " + if (adapter.grid) "Long-press and drag to move" else "Drag the handle to move"
        }
    }

    private inner class FolderVH(val v: FolderViews) : RecyclerView.ViewHolder(v.root) {
        private var row: Row.Folder? = null
        init {
            v.root.setOnClickListener {
                val f = row?.folder ?: return@setOnClickListener
                if (selecting) return@setOnClickListener
                openFolder = f.id; filter = "all"; refresh()
            }
            v.root.setOnLongClickListener { row?.let { Motion.haptic(v.root, true); onFolderLongClick(it) }; true }
            v.more.setOnClickListener { row?.let { onFolderLongClick(it) } }
            Motion.pressable(v.root, 0.97f)
        }
        fun bind(row: Row.Folder) {
            this.row = row
            val f = row.folder
            val n = f.videos.size
            v.name.text = f.name
            v.badge.text = n.toString()
            val stats = Prefs.showWatchStats(itemView.context)
            val parts = ArrayList<String>()
            parts += countText(n)
            if (stats && !adapter.grid) {
                if (row.watched > 0) parts += "${row.watched} watched"
                if (row.inProgress > 0) parts += "${row.inProgress} in progress"
            } else if (stats && row.watched > 0) parts += "${row.watched} watched"
            v.count.text = parts.joinToString(" · ")
            v.path?.isVisible = row.showPath && f.path != null
            v.path?.text = f.path ?: ""
            v.bar.isVisible = stats && row.watched > 0
            v.bar.progress = if (n > 0) row.watched * 1000 / n else 0
            val newest = f.videos.sortedByDescending { it.dateAdded }
            ThumbLoader.load(v.thumb, newest.firstOrNull())
            v.stack.setStack(n, newest.getOrNull(1)?.let { Triple(it.uri, it.id, it.durationMs) })
        }
    }

    private inner class PlaylistVH(val v: PlaylistViews) : RecyclerView.ViewHolder(v.root) {
        private var row: Row.Playlist? = null
        init {
            v.root.setOnClickListener {
                val p = bindingAdapterPosition
                if (selecting) { if (p != RecyclerView.NO_POSITION) toggleSelection(p); return@setOnClickListener }
                row?.let { openPlaylist = it.name; refresh() }
            }
            v.root.setOnLongClickListener { val p = bindingAdapterPosition; row?.let { Motion.haptic(v.root, true); onPlaylistLongPress(it, p) }; true }
            v.more.setOnClickListener { row?.let { onPlaylistOptions(it) } }
            Motion.pressable(v.root, 0.97f)
        }
        fun bind(row: Row.Playlist) {
            this.row = row
            v.name.text = row.name
            val parts = arrayListOf(countText(row.count))
            if (row.durationMs > 0) parts += Fmt.time(row.durationMs)
            if (row.unavailable > 0 && !adapter.grid) parts += "${row.unavailable} unavailable"
            v.count.text = parts.joinToString(" · ")
            v.badge.text = row.count.toString()
            if (row.progress < 0f) {
                v.progress.isVisible = false
                v.progressText.text = "Empty"
            } else {
                val pct = Math.round(row.progress * 100)
                v.progress.isVisible = true
                v.progress.setProgressCompat((row.progress * 1000).toInt().coerceIn(0, 1000), false)
                v.progressText.text = if (adapter.grid) "$pct%" else "$pct% watched"
            }
            v.progress.contentDescription = if (row.progress < 0f) "Empty playlist" else "${Math.round(row.progress * 100)} percent watched"
            ThumbLoader.loadCover(v.cover, row.covers, Prefs.playlistCovers(itemView.context))
            v.stack.setStack(row.count, row.covers.getOrNull(1))
            bindSelection()
        }
        fun bindSelection() { showSelection(v.root, v.check, row?.let { selKey(it) }); v.more.isVisible = !selecting }
    }

    private inner class HeaderVH(val v: ItemCollectionHeaderBinding) : RecyclerView.ViewHolder(v.root) {
        init {
            v.btnPlayAll.setOnClickListener { playCollection(false) }
            v.btnShuffle.setOnClickListener { playCollection(true) }
            v.btnAdd.setOnClickListener { openPlaylist?.let { openPicker(it) } }
            v.l23BtnReorder.setOnClickListener { if (reordering) reorderMode?.finish() else startReorder() }
            Motion.pressable(v.btnPlayAll, 0.95f); Motion.pressable(v.btnShuffle, 0.95f); Motion.pressable(v.btnAdd, 0.9f)
            FitButtons.apply(v.btnPlayAll, v.btnShuffle)
        }
        fun bind(row: Row.Header) {
            v.title.text = row.title
            v.subtitle.text = row.subtitle
            v.btnAdd.isVisible = row.playlist
            v.l23BtnReorder.isVisible = row.playlist && adapter.rows.count { it is Row.Video } > 1
            v.l23BtnReorder.isChecked = reordering
            v.stack.isVisible = row.playlist
            if (row.playlist) { ThumbLoader.loadCover(v.cover, row.covers, Prefs.playlistCovers(itemView.context)); v.stack.setStack(row.covers.size, row.covers.getOrNull(1)) }
        }
    }

    private inner class StripVH(val v: ItemContinueStripBinding) : RecyclerView.ViewHolder(v.root) {
        private val strip = StripAdapter()
        init {
            v.stripList.layoutManager = LinearLayoutManager(itemView.context, LinearLayoutManager.HORIZONTAL, false)
            v.stripList.adapter = strip
        }
        fun bind(row: Row.ContinueStrip) {
            v.stripTitle.text = "Continue watching (${row.videos.size})"
            v.stripSeeAll.setOnClickListener {
                openPlaylist = null; openFolder = null
                if (tab != Tab.ALL) b.chipAll.isChecked = true // its listener resets the filter, so set it after
                filter = "progress"
                refresh()
                b.list.scrollToPosition(0)
            }
            strip.submit(row.videos)
        }
    }

    private inner class StripAdapter : RecyclerView.Adapter<ContinueVH>() {
        var items: List<Row.Video> = emptyList()
        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun submit(l: List<Row.Video>) { items = l; notifyDataSetChanged() }
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ContinueVH(ItemContinueBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: ContinueVH, position: Int) = holder.bind(items[position], items)
    }

    private inner class ContinueVH(val v: ItemContinueBinding) : RecyclerView.ViewHolder(v.root) {
        fun bind(row: Row.Video, all: List<Row.Video>) {
            val item = row.item
            v.title.text = row.entry.title
            val dur = item?.durationMs?.takeIf { it > 0 } ?: Store.duration(itemView.context, row.entry.uri)
            v.duration.text = if (dur > 0) Fmt.time(dur) else "Resume"
            v.progress.progress = (row.progress * 1000).toInt()
            ThumbLoader.load(v.thumb, item?.uri ?: Uri.parse(row.entry.uri), item?.id ?: -1L, dur)
            itemView.setOnClickListener { play(all.map { it.entry }, all.indexOf(row).coerceAtLeast(0), source = PlayerSession.SOURCE_CONTINUE, sourceId = null) }
            itemView.setOnLongClickListener { Motion.haptic(it, true); onVideoOptions(row); true }
            Motion.pressable(itemView, 0.95f)
        }
    }

    private inner class SummaryVH(val v: ItemSummaryBinding) : RecyclerView.ViewHolder(v.root) {
        fun bind(row: Row.Summary) {
            val parts = ArrayList<String>()
            parts += if (row.total == 1) "1 video available" else "${row.total} videos available"
            parts += "${row.watched} watched"
            if (row.inProgress > 0) parts += "${row.inProgress} in progress"
            v.counts.text = parts.joinToString(" · ")
            v.watchedBar.progress = if (row.total > 0) row.watched * 1000 / row.total else 0
            v.filters.setOnCheckedStateChangeListener(null)
            v.filters.check(when (filter) { "progress" -> R.id.fProgress; "unwatched" -> R.id.fUnwatched; "watched" -> R.id.fWatched; else -> R.id.fAll })
            v.filters.setOnCheckedStateChangeListener { _, ids ->
                val nf = when (ids.firstOrNull()) { R.id.fProgress -> "progress"; R.id.fUnwatched -> "unwatched"; R.id.fWatched -> "watched"; else -> "all" }
                if (nf != filter) { filter = nf; b.list.post { refresh() } }
            }
        }
    }

    // ------------------------------------------------------------------ v2.3 playlist order

    /** Display order of the open playlist: the saved custom order unless the user picked another sort. */
    private fun playlistSorted(rows: List<Row.Video>): List<Row.Video> = when (Prefs.sp(this).getString("l23_playlist_sort", "custom")) {
        "name_asc" -> rows.sortedBy { it.entry.title.lowercase() }
        "name_desc" -> rows.sortedByDescending { it.entry.title.lowercase() }
        "duration_desc" -> rows.sortedByDescending { it.item?.durationMs ?: Store.duration(this, it.entry.uri) }
        "duration_asc" -> rows.sortedBy { it.item?.durationMs ?: Store.duration(this, it.entry.uri) }
        else -> rows
    }

    private fun playlistSortDialog() {
        val labels = arrayOf("Custom order (your arrangement)", "Name A–Z", "Name Z–A", "Longest first", "Shortest first")
        val keys = arrayOf("custom", "name_asc", "name_desc", "duration_desc", "duration_asc")
        val cur = Prefs.sp(this).getString("l23_playlist_sort", "custom")
        VelaDialogBuilder(this).setTitle("Sort playlist by")
            .setSingleChoiceItems(labels, keys.indexOf(cur).coerceAtLeast(0)) { d, i ->
                Prefs.sp(this).edit().putString("l23_playlist_sort", keys[i]).apply()
                if (keys[i] != "custom") reorderMode?.finish()
                refresh(); d.dismiss()
            }.show()
    }

    private fun updateReorderMenu() {
        menuRef?.findItem(R.id.l23_action_reorder)?.isVisible = tab == Tab.PLAYLISTS && openPlaylist != null && !reordering
    }

    /** Enters "Edit order" for the open playlist (multi-select, search and tab swipes are off meanwhile). */
    private fun startReorder() {
        val pl = openPlaylist ?: return
        if (reordering) return
        if (query.isNotBlank()) { toast("Clear the search to edit the order"); return }
        if (adapter.rows.count { it is Row.Video } < 2) { toast("Add at least two videos to arrange them"); return }
        actionMode?.finish()
        if (Prefs.sp(this).getString("l23_playlist_sort", "custom") != "custom") {
            Prefs.sp(this).edit().putString("l23_playlist_sort", "custom").apply()
            toast("Showing your custom order")
            refresh()
        }
        reorderPlaylistName = pl
        reorderDirty = false
        reorderMode = startSupportActionMode(reorderActionCallback)
        b.swipeRefresh.isEnabled = false
        b.fab.hide()
        adapter.notifyItemRangeChanged(0, adapter.itemCount, PAYLOAD_SEL)
        adapter.rows.indexOfFirst { it is Row.Header }.takeIf { it >= 0 }?.let { adapter.notifyItemChanged(it) }
        updateReorderMenu()
    }

    private fun saveReorder() {
        val pl = reorderPlaylistName ?: return
        if (!reorderDirty) return
        reorderDirty = false
        Store.reorderPlaylist(this, pl, adapter.rows.filterIsInstance<Row.Video>().map { it.entry.uri })
    }

    private val reorderActionCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.title = "Edit order"
            mode.subtitle = null // v2.9: no drag hint text; dragging still works
            return true
        }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
        override fun onDestroyActionMode(mode: ActionMode) {
            saveReorder()
            reorderMode = null
            reorderPlaylistName = null
            b.swipeRefresh.isEnabled = true
            adapter.notifyItemRangeChanged(0, adapter.itemCount, PAYLOAD_SEL)
            adapter.rows.indexOfFirst { it is Row.Header }.takeIf { it >= 0 }?.let { adapter.notifyItemChanged(it) }
            updateReorderMenu()
        }
    }

    private val reorderCallback = object : ItemTouchHelper.Callback() {
        private val lift get() = 10 * resources.displayMetrics.density
        override fun isLongPressDragEnabled() = false
        override fun isItemViewSwipeEnabled() = false
        override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
            if (!reordering || vh !is VideoVH) return 0
            val dirs = if (adapter.grid) ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
            else ItemTouchHelper.UP or ItemTouchHelper.DOWN
            return makeMovementFlags(dirs, 0)
        }
        override fun canDropOver(rv: RecyclerView, current: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = target is VideoVH
        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val from = vh.bindingAdapterPosition; val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            if (adapter.rows.getOrNull(to) !is Row.Video) return false
            adapter.move(from, to)
            reorderDirty = true
            Motion.haptic(rv)
            return true
        }
        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
        override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(vh, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && vh != null) {
                vh.itemView.animate().translationZ(lift).scaleX(1.03f).scaleY(1.03f).alpha(0.96f).setDuration(160).setInterpolator(Motion.emphasized).start()
                vh.itemView.isActivated = true
            }
        }
        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            vh.itemView.animate().translationZ(0f).scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).setInterpolator(Motion.emphasized).start()
            vh.itemView.isActivated = false
            saveReorder() // every drop is saved right away
        }
    }

    // ------------------------------------------------------------------ v2.3 Home → Hidden Vault

    /** Holding the "Vela Player" name for 5 s opens the Hidden Vault (welcome on first use, else its lock screen). */
    private fun setupVaultHold() {
        b.l23AppTitle.canHold = { !selecting && !reordering }
        b.l23AppTitle.onHoldComplete = {
            if (!isFinishing && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                try {
                    startActivity(Intent(this, VaultActivity::class.java))
                    // v2.12: the vault rises out of the dark while home sinks away.
                    if (Motion.enabled(this)) @Suppress("DEPRECATION") overridePendingTransition(R.anim.vault_enter, R.anim.vault_hold_exit)
                } catch (_: Exception) { }
            }
        }
    }

    companion object {
        private const val PAYLOAD_SEL = "sel"
        /** v2.14 widgets: open a library destination ("all", "folders", "favorites", "playlists", "continue"). */
        const val EXTRA_OPEN = "vela_open"
        private const val STATE_SEARCH_QUERY = "vela_search_query"
        /** v2.14 widgets: with EXTRA_OPEN = "playlists", open this playlist when it still exists. */
        const val EXTRA_OPEN_PLAYLIST = "vela_open_playlist"
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (::b.isInitialized) applyWidgetDestination(intent, initial = false)
    }

    /**
     * v2.14: a widget shortcut asked for a destination. Switches to that tab (top level, or the given playlist) the
     * same way the chips do. Called before the chip listener exists on a cold start ([initial]).
     */
    private fun applyWidgetDestination(i: Intent?, initial: Boolean) {
        val dest = i?.getStringExtra(EXTRA_OPEN) ?: return
        val pl = i.getStringExtra(EXTRA_OPEN_PLAYLIST)
        i.removeExtra(EXTRA_OPEN); i.removeExtra(EXTRA_OPEN_PLAYLIST)
        val t = when (dest) { "all", "continue" -> Tab.ALL; "favorites" -> Tab.FAVORITES; "playlists" -> Tab.PLAYLISTS; "folders" -> Tab.FOLDERS; else -> return }
        if (!initial) { actionMode?.finish(); reorderMode?.finish() }
        if (t == Tab.FOLDERS) folderOfTab = null
        if (t == Tab.PLAYLISTS) playlistOfTab = pl?.takeIf { Store.playlists(this).containsKey(it) }
        if (initial) {
            tab = t
            chipFor(t).isChecked = true
            if (dest == "continue") filterOfTab[t] = "progress"
        } else {
            if (tab != t) chipFor(t).isChecked = true
            if (dest == "continue") filter = "progress"
            refresh()
            saveUiState()
        }
    }
}
