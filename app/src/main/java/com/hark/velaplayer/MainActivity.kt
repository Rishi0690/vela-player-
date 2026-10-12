package com.hark.velaplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hark.velaplayer.databinding.ActivityMainBinding
import com.hark.velaplayer.databinding.DialogUrlBinding
import com.hark.velaplayer.databinding.ItemContinueBinding
import com.hark.velaplayer.databinding.ItemContinueStripBinding
import com.hark.velaplayer.databinding.ItemFolderBinding
import com.hark.velaplayer.databinding.ItemSummaryBinding
import com.hark.velaplayer.databinding.ItemPlaylistBinding
import com.hark.velaplayer.databinding.ItemVideoBinding
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.media.MediaScannerConnection
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.IntentSenderRequest
import androidx.core.widget.TextViewCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private enum class Tab { FOLDERS, ALL, FAVORITES, PLAYLISTS }

    sealed class Row {
        data class Video(val entry: Entry, val item: VideoItem?, val progress: Float, val favorite: Boolean, val watched: Boolean = false) : Row()
        data class Playlist(val name: String, val count: Int) : Row()
        data class Folder(val folder: VideoFolder, val watched: Int, val inProgress: Int, val showPath: Boolean) : Row()
        /** Library counts + watch filter, shown at the top of All videos and inside a folder. */
        data class Summary(val total: Int, val watched: Int, val inProgress: Int) : Row()
        /** Horizontal "Continue watching" strip at the top of Folders and All videos. */
        data class ContinueStrip(val videos: List<Video>) : Row()
    }

    private lateinit var b: ActivityMainBinding
    private val adapter = Adapter()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var tab = Tab.FOLDERS
    private var openPlaylist: String? = null
    private var openFolder: String? = null
    /** Watch filter for video lists: all, progress, unwatched, watched. */
    private var filter = "all"
    private var library: List<VideoItem> = emptyList()
    private var byUri: Map<String, VideoItem> = emptyMap()
    private var query = ""
    private var sort = "date_desc"
    private var loaded = false
    private var bannerDismissed = false

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { openPlaylist = null; openFolder = null; filter = "all"; refresh() }
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
            play(listOf(Entry(uri.toString(), nameOf(uri))), 0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        onBackPressedDispatcher.addCallback(this, backCallback)

        sort = Prefs.sp(this).getString("library_sort", "date_desc") ?: "date_desc"
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter

        // Open on the section chosen in Settings (Folders, All videos, or the last one used) and put it first.
        val start = Prefs.homeStartSection(this)
        if (start == "all") {
            b.chips.removeView(b.chipAll); b.chips.addView(b.chipAll, 0)
            b.chipAll.isChecked = true; tab = Tab.ALL
        } else { b.chipFolders.isChecked = true; tab = Tab.FOLDERS }

        b.chips.setOnCheckedStateChangeListener { _, ids ->
            tab = when (ids.firstOrNull()) {
                R.id.chipAll -> Tab.ALL
                R.id.chipFavorites -> Tab.FAVORITES
                R.id.chipPlaylists -> Tab.PLAYLISTS
                else -> Tab.FOLDERS
            }
            if (tab == Tab.FOLDERS) Prefs.saveHomeSection(this, "folders")
            if (tab == Tab.ALL) Prefs.saveHomeSection(this, "all")
            openPlaylist = null; openFolder = null; filter = "all"
            refresh()
        }
        b.header.setOnClickListener { openPlaylist = null; openFolder = null; filter = "all"; refresh() }
        b.fab.setOnClickListener { newPlaylistDialog(null) }
        b.btnGrant.setOnClickListener { requestPermission() }
        b.accessBannerButton.setOnClickListener { openAppSettings() }
        b.accessBannerClose.setOnClickListener { bannerDismissed = true; b.accessBanner.isVisible = false }
        b.swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.accent))
        b.swipeRefresh.setOnRefreshListener { ThumbLoader.clearFailures(); loadLibrary() }

        // Ask only when we have no access at all; limited access shows the banner instead of re-asking every launch.
        if (hasPermission() || hasPartialAccess()) loadLibrary() else requestPermission()
    }

    override fun onResume() {
        super.onResume()
        // Reload so counts follow videos added/removed while we were away.
        if (loaded) { refresh(); loadLibrary() }
    }

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
        io.execute {
            val items = if (hasPermission() || hasPartialAccess()) VideoRepository.load(this) else emptyList()
            main.post {
                library = items
                byUri = items.associateBy { it.uri.toString() }
                loaded = true
                b.swipeRefresh.isRefreshing = false
                updateAccessBanner()
                refresh()
            }
        }
    }

    // ------------------------------------------------------------------ list building

    private fun refresh() {
        val rows = ArrayList<Row>()
        val folders = VideoRepository.folders(library, Prefs.hideSystemFolders(this))
        val folderObj = openFolder?.let { id -> folders.firstOrNull { it.id == id } ?: VideoRepository.folders(library, false).firstOrNull { it.id == id } }
        if (openFolder != null && folderObj == null) openFolder = null
        backCallback.isEnabled = openPlaylist != null || openFolder != null
        b.header.isVisible = openPlaylist != null || openFolder != null
        b.header.text = when {
            openFolder != null -> "‹  ${folderObj?.name ?: ""}"
            else -> "‹  ${openPlaylist ?: ""}"
        }
        b.fab.isVisible = tab == Tab.PLAYLISTS && openPlaylist == null
        val favs = Store.favorites(this).map { it.uri }.toSet()
        val watched = Store.watchedSet(this)
        val inProgress = Store.inProgressSet(this)
        val stats = Prefs.showWatchStats(this)

        fun continueStrip(): Row.ContinueStrip? {
            if (query.isNotBlank()) return null
            val list = Store.continueList(this).take(20).map { videoRowOf(it, favs, watched) }
            return if (list.isEmpty()) null else Row.ContinueStrip(list)
        }

        fun videoRow(e: Entry): Row.Video {
            val item = byUri[e.uri]
            val pos = Store.position(this, e.uri)
            val dur = Store.duration(this, e.uri).takeIf { it > 0 } ?: item?.durationMs ?: 0L
            val progress = if (pos > 0 && dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
            return Row.Video(e, item, progress, favs.contains(e.uri), watched.contains(e.uri))
        }

        /** Summary row + the videos of [items], narrowed by the watch filter. */
        fun videoList(items: List<VideoItem>, includeStreams: Boolean) {
            val uris = items.map { it.uri.toString() }
            if (stats) rows += Row.Summary(items.size, uris.count { it in watched }, uris.count { it in inProgress })
            when (filter) {
                "progress" -> {
                    // Same order as the old "Continue watching" list: most recently played first.
                    val set = uris.toSet()
                    Store.continueList(this).filter { set.contains(it.uri) || (includeStreams && byUri[it.uri] == null) }
                        .forEach { rows += videoRow(it) }
                }
                "watched" -> items.filter { it.uri.toString() in watched }.forEach { rows += videoRow(Entry(it.uri.toString(), it.name)) }
                "unwatched" -> items.filter { it.uri.toString() !in watched }.forEach { rows += videoRow(Entry(it.uri.toString(), it.name)) }
                else -> items.forEach { rows += videoRow(Entry(it.uri.toString(), it.name)) }
            }
        }

        when {
            tab == Tab.FOLDERS && folderObj != null -> videoList(folderObj.videos, false)
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
            tab == Tab.ALL -> { if (filter != "progress") continueStrip()?.let { rows += it }; videoList(library, true) }
            tab == Tab.FAVORITES -> Store.favorites(this).forEach { rows += videoRow(it) }
            openPlaylist == null -> Store.playlists(this).forEach { (name, list) -> rows += Row.Playlist(name, list.size) }
            else -> Store.playlists(this)[openPlaylist]?.forEach { rows += videoRow(it) }
        }

        var filtered: List<Row> = rows
        if (query.isNotBlank()) {
            val q = query.trim().lowercase()
            filtered = rows.filter {
                when (it) {
                    is Row.Video -> it.entry.title.lowercase().contains(q)
                    is Row.Playlist -> it.name.lowercase().contains(q)
                    is Row.Folder -> it.folder.name.lowercase().contains(q) || it.folder.videos.any { v -> v.name.lowercase().contains(q) }
                    is Row.Summary -> true
                    is Row.ContinueStrip -> true
                }
            }
        }
        // In-progress keeps its most-recent order; other video lists follow the chosen sort.
        val inVideoList = (tab == Tab.ALL || (tab == Tab.FOLDERS && openFolder != null))
        if ((inVideoList && filter != "progress") || tab == Tab.FAVORITES) filtered = sortRows(filtered)

        adapter.submit(filtered)
        val empty = filtered.none { it !is Row.Summary && it !is Row.ContinueStrip }
        b.emptyView.isVisible = empty && filtered.isEmpty()
        b.btnGrant.isVisible = empty && (tab == Tab.ALL || tab == Tab.FOLDERS) && !hasPermission()
        b.emptyText.text = when {
            query.isNotBlank() -> "Nothing matches \"$query\"."
            (tab == Tab.ALL || tab == Tab.FOLDERS) && !hasPermission() -> "Allow access so Vela can show the videos on your phone."
            tab == Tab.FOLDERS && openFolder == null -> "No folders with videos found yet."
            tab == Tab.ALL || tab == Tab.FOLDERS -> "No videos found on this device yet."
            tab == Tab.FAVORITES -> "Long-press any video and choose Add to favorites."
            openPlaylist != null -> "This playlist is empty. Long-press a video to add it."
            else -> "No playlists yet. Tap + to create one."
        }
        // A filter with no matches still shows its summary row, so explain it in the list itself.
        if (empty && filtered.isNotEmpty()) {
            b.emptyView.isVisible = false
        }
    }

    private fun videoRowOf(e: Entry, favs: Set<String>, watched: Set<String>): Row.Video {
        val item = byUri[e.uri]
        val pos = Store.position(this, e.uri)
        val dur = Store.duration(this, e.uri).takeIf { it > 0 } ?: item?.durationMs ?: 0L
        val progress = if (pos > 0 && dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        return Row.Video(e, item, progress, favs.contains(e.uri), watched.contains(e.uri))
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
        return rows.filterIsInstance<Row.ContinueStrip>() + rows.filterIsInstance<Row.Summary>() + sorted + rows.filterIsInstance<Row.Playlist>()
    }

    // ------------------------------------------------------------------ menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        val sv = menu.findItem(R.id.action_search).actionView as SearchView
        sv.queryHint = "Search videos"
        sv.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?): Boolean { sv.clearFocus(); return true }
            override fun onQueryTextChange(q: String?): Boolean { query = q ?: ""; refresh(); return true }
        })
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_sort -> sortDialog()
            R.id.action_open_url -> urlDialog()
            R.id.action_open_file -> openFile.launch(arrayOf("video/*"))
            R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun sortDialog() {
        val labels = arrayOf("Newest first", "Oldest first", "Name A–Z", "Name Z–A", "Longest first", "Shortest first", "Largest first", "Smallest first")
        val keys = arrayOf("date_desc", "date_asc", "name_asc", "name_desc", "duration_desc", "duration_asc", "size_desc", "size_asc")
        MaterialAlertDialogBuilder(this).setTitle("Sort by")
            .setSingleChoiceItems(labels, keys.indexOf(sort).coerceAtLeast(0)) { d, i ->
                sort = keys[i]
                Prefs.sp(this).edit().putString("library_sort", sort).apply()
                refresh(); d.dismiss()
            }.show()
    }

    private fun urlDialog() {
        val v = DialogUrlBinding.inflate(layoutInflater)
        MaterialAlertDialogBuilder(this).setTitle("Open network stream")
            .setMessage("Direct video links (MP4, MKV, WebM…), HLS (.m3u8) and DASH (.mpd) are supported. Web pages and DRM streams are not.")
            .setView(v.root)
            .setPositiveButton("Play") { _, _ ->
                val url = v.urlInput.text?.toString()?.trim().orEmpty()
                val uri = Uri.parse(url)
                if (uri.scheme == "http" || uri.scheme == "https") {
                    val title = uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: uri.host ?: "Stream"
                    play(listOf(Entry(url, title)), 0)
                } else toast("Enter a link starting with http:// or https://")
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------------ actions

    private fun play(entries: List<Entry>, index: Int) {
        // Keep the queue a sensible size for the intent.
        val from = (index - 250).coerceAtLeast(0)
        val to = (from + 500).coerceAtMost(entries.size)
        val window = entries.subList(from, to)
        val i = Intent(this, PlayerActivity::class.java)
            .putStringArrayListExtra(PlayerActivity.EXTRA_URIS, ArrayList(window.map { it.uri }))
            .putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, ArrayList(window.map { it.title }))
            .putExtra(PlayerActivity.EXTRA_INDEX, index - from)
        startActivity(i)
    }

    private fun onVideoClick(position: Int) {
        val videos = adapter.rows.filterIsInstance<Row.Video>()
        val row = adapter.rows[position] as Row.Video
        play(videos.map { it.entry }, videos.indexOf(row))
    }

    private class Action(val label: String, val icon: Int, val danger: Boolean = false, val run: () -> Unit)

    /** XPlayer-style options sheet used by long-press and the ⋮ button. */
    private fun showSheet(title: String, subtitle: String?, actions: List<Action>) {
        val d = BottomSheetDialog(this)
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, (12 * dp).toInt(), 0, (16 * dp).toInt()) }
        root.addView(TextView(this).apply {
            text = title; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            setPadding((20 * dp).toInt(), (4 * dp).toInt(), (20 * dp).toInt(), if (subtitle == null) (10 * dp).toInt() else 0)
        })
        if (subtitle != null) root.addView(TextView(this).apply {
            text = subtitle; alpha = 0.65f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setPadding((20 * dp).toInt(), (2 * dp).toInt(), (20 * dp).toInt(), (10 * dp).toInt())
        })
        val tv = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        actions.forEach { a ->
            root.addView(TextView(this).apply {
                text = a.label
                TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                gravity = Gravity.CENTER_VERTICAL
                minHeight = (52 * dp).toInt()
                setPadding((20 * dp).toInt(), 0, (20 * dp).toInt(), 0)
                compoundDrawablePadding = (20 * dp).toInt()
                setCompoundDrawablesRelativeWithIntrinsicBounds(a.icon, 0, 0, 0)
                if (a.danger) setTextColor(0xFFE5534B.toInt())
                TextViewCompat.setCompoundDrawableTintList(this, textColors)
                setBackgroundResource(tv.resourceId)
                setOnClickListener { d.dismiss(); a.run() }
            })
        }
        d.setContentView(android.widget.ScrollView(this).apply { addView(root) })
        d.show()
    }

    private fun onVideoLongClick(row: Row.Video) {
        val item = row.item
        val a = ArrayList<Action>()
        a += Action("Play", R.drawable.ic_play) { play(listOf(row.entry), 0) }
        if (row.progress > 0f) a += Action("Play from start", R.drawable.ic_replay) {
            Store.clearPosition(this, row.entry.uri); play(listOf(row.entry), 0)
        }
        a += Action(if (row.favorite) "Remove from favorites" else "Add to favorites", R.drawable.ic_star) {
            val added = Store.toggleFavorite(this, row.entry)
            toast(if (added) "Added to favorites" else "Removed from favorites"); refresh()
        }
        a += Action("Add to playlist", R.drawable.ic_playlist) { addToPlaylistDialog(row.entry) }
        openPlaylist?.let { pl ->
            a += Action("Remove from this playlist", R.drawable.ic_close) {
                Store.removeFromPlaylist(this, pl, row.entry.uri)
                toast("Removed from playlist (the file is untouched)"); refresh()
            }
        }
        a += Action(if (row.watched) "Mark as unwatched" else "Mark as watched", R.drawable.ic_check_circle) {
            Store.setWatched(this, row.entry.uri, !row.watched); refresh()
        }
        if (row.progress > 0f) a += Action("Remove from Continue watching", R.drawable.ic_close) { Store.clearPosition(this, row.entry.uri); refresh() }
        val shareable = Uri.parse(row.entry.uri).scheme.let { it == "content" || it == "file" }
        if (shareable) a += Action("Share", R.drawable.ic_share) { share(listOf(Uri.parse(row.entry.uri))) }
        if (item != null) {
            a += Action("Rename", R.drawable.ic_edit) { renameVideoDialog(item) }
            a += Action("Delete", R.drawable.ic_delete, danger = true) { confirmDelete(listOf(item), "Delete \"${item.name}\"?") }
        }
        a += Action("Properties", R.drawable.ic_info) { detailsDialog(row) }
        val sub = item?.let { listOfNotNull(it.durationMs.takeIf { d -> d > 0 }?.let { d -> Fmt.time(d) }, it.size.takeIf { z -> z > 0 }?.let { z -> Formatter.formatShortFileSize(this, z) }).joinToString(" · ") }
        showSheet(row.entry.title, sub?.takeIf { it.isNotEmpty() }, a)
    }

    private fun onFolderLongClick(row: Row.Folder) {
        val f = row.folder
        val sortedEntries = { f.videos.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }).map { Entry(it.uri.toString(), it.name) } }
        val allWatched = row.watched >= f.videos.size && f.videos.isNotEmpty()
        val a = ArrayList<Action>()
        a += Action("Play all", R.drawable.ic_play) { play(sortedEntries(), 0) }
        a += Action("Shuffle play", R.drawable.ic_shuffle) { play(sortedEntries().shuffled(), 0) }
        a += Action("Open", R.drawable.ic_folder) { openFolder = f.id; filter = "all"; refresh(); b.list.scrollToPosition(0) }
        a += Action("Add all to playlist", R.drawable.ic_playlist) { addManyToPlaylistDialog(sortedEntries()) }
        a += Action(if (allWatched) "Mark all as unwatched" else "Mark all as watched", R.drawable.ic_check_circle) {
            f.videos.forEach { Store.setWatched(this, it.uri.toString(), !allWatched) }; refresh()
        }
        a += Action("Share all", R.drawable.ic_share) { share(f.videos.map { it.uri }) }
        a += Action("Rename folder", R.drawable.ic_edit) { renameFolderDialog(f) }
        a += Action("Delete folder", R.drawable.ic_delete, danger = true) {
            confirmDelete(f.videos, "Delete folder \"${f.name}\"?", "All ${f.videos.size} videos in this folder will be permanently deleted from your phone.")
        }
        a += Action("Properties", R.drawable.ic_info) {
            val size = f.videos.sumOf { it.size }
            val dur = f.videos.sumOf { it.durationMs }
            val msg = "Videos available: ${f.videos.size}\nWatched: ${row.watched}\nIn progress: ${row.inProgress}\n" +
                "Total length: ${Fmt.time(dur)}\nTotal size: ${Formatter.formatFileSize(this, size)}" +
                (f.path?.let { "\nPath: $it" } ?: "")
            MaterialAlertDialogBuilder(this).setTitle("Folder properties").setMessage(msg).setPositiveButton("Close", null).show()
        }
        showSheet(f.name, f.path, a)
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
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT; setSingleLine(); setText(initial) }
        val box = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0); addView(input)
        }
        val dlg = MaterialAlertDialogBuilder(this).setTitle(title).setView(box)
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
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message)
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

    private fun addManyToPlaylistDialog(entries: List<Entry>) {
        val names = Store.playlists(this).keys.toList()
        if (names.isEmpty()) { toast("Create a playlist first in the Playlists tab"); return }
        MaterialAlertDialogBuilder(this).setTitle("Add ${entries.size} videos to")
            .setItems(names.toTypedArray()) { _, i ->
                val n = entries.count { Store.addToPlaylist(this, names[i], it) }
                toast("Added $n to \"${names[i]}\""); refresh()
            }.show()
    }

    private fun onPlaylistLongClick(row: Row.Playlist) {
        MaterialAlertDialogBuilder(this).setTitle(row.name)
            .setItems(arrayOf("Play all", "Rename", "Delete playlist")) { _, i ->
                when (i) {
                    0 -> Store.playlists(this)[row.name]?.takeIf { it.isNotEmpty() }?.let { play(it, 0) } ?: toast("Playlist is empty")
                    1 -> newPlaylistDialog(row.name)
                    2 -> MaterialAlertDialogBuilder(this).setTitle("Delete \"${row.name}\"?")
                        .setMessage("Only the playlist is removed. Your video files stay on the phone.")
                        .setPositiveButton("Delete") { _, _ -> Store.deletePlaylist(this, row.name); refresh() }
                        .setNegativeButton("Cancel", null).show()
                }
            }.show()
    }

    private fun newPlaylistDialog(renameFrom: String?, thenAdd: Entry? = null) {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; setText(renameFrom ?: ""); hint = "Playlist name" }
        val box = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0); addView(input)
        }
        MaterialAlertDialogBuilder(this).setTitle(if (renameFrom == null) "New playlist" else "Rename playlist")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) { toast("Name can't be empty"); return@setPositiveButton }
                val ok = if (renameFrom == null) Store.createPlaylist(this, name) else Store.renamePlaylist(this, renameFrom, name)
                if (!ok) toast("A playlist with that name already exists")
                else if (thenAdd != null) { Store.addToPlaylist(this, name, thenAdd); toast("Added to \"$name\"") }
                refresh()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun addToPlaylistDialog(e: Entry) {
        val names = Store.playlists(this).keys.toList()
        val labels = (names + "New playlist…").toTypedArray()
        MaterialAlertDialogBuilder(this).setTitle("Add to playlist")
            .setItems(labels) { _, i ->
                if (i == names.size) newPlaylistDialog(null, e)
                else {
                    val added = Store.addToPlaylist(this, names[i], e)
                    toast(if (added) "Added to \"${names[i]}\"" else "Already in \"${names[i]}\"")
                    refresh()
                }
            }.show()
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
        MaterialAlertDialogBuilder(this).setTitle("Details").setMessage(sb.toString().trim()).setPositiveButton("Close", null).show()
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
        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun submit(r: List<Row>) { rows = r; notifyDataSetChanged() }

        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Row.Video -> 0; is Row.Playlist -> 1; is Row.Folder -> 2; is Row.Summary -> 3; is Row.ContinueStrip -> 4
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                0 -> VideoVH(ItemVideoBinding.inflate(inf, parent, false))
                2 -> FolderVH(ItemFolderBinding.inflate(inf, parent, false))
                3 -> SummaryVH(ItemSummaryBinding.inflate(inf, parent, false))
                4 -> StripVH(ItemContinueStripBinding.inflate(inf, parent, false))
                else -> PlaylistVH(ItemPlaylistBinding.inflate(inf, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Video -> (holder as VideoVH).bind(row)
                is Row.Playlist -> (holder as PlaylistVH).bind(row)
                is Row.Folder -> (holder as FolderVH).bind(row)
                is Row.Summary -> (holder as SummaryVH).bind(row)
                is Row.ContinueStrip -> (holder as StripVH).bind(row)
            }
        }
    }

    private inner class VideoVH(val v: ItemVideoBinding) : RecyclerView.ViewHolder(v.root) {
        fun bind(row: Row.Video) {
            v.title.text = row.entry.title
            val item = row.item
            val parts = ArrayList<String>()
            if (item != null) {
                if (item.height > 0) parts += "${item.height}p"
                if (item.size > 0) parts += Formatter.formatShortFileSize(itemView.context, item.size)
                if (item.dateAdded > 0) parts += DateFormat.getMediumDateFormat(itemView.context).format(item.dateAdded * 1000)
            } else {
                val u = Uri.parse(row.entry.uri)
                parts += if (u.scheme == "http" || u.scheme == "https") "Stream · ${u.host}" else "File"
            }
            v.subtitle.text = parts.joinToString(" · ")
            val dur = item?.durationMs ?: Store.duration(itemView.context, row.entry.uri)
            v.durationBadge.isVisible = dur > 0
            v.durationBadge.text = Fmt.time(dur)
            v.progress.isVisible = row.progress > 0f
            v.progress.progress = (row.progress * 1000).toInt()
            v.favIcon.isVisible = row.favorite
            v.watchedIcon.isVisible = row.watched && Prefs.showWatchStats(itemView.context)
            ThumbLoader.load(v.thumb, item?.uri ?: Uri.parse(row.entry.uri), item?.id ?: -1L, item?.durationMs ?: 0L)
            itemView.setOnClickListener { val p = bindingAdapterPosition; if (p != RecyclerView.NO_POSITION) onVideoClick(p) }
            itemView.setOnLongClickListener { onVideoLongClick(row); true }
            v.more.setOnClickListener { onVideoLongClick(row) }
        }
    }

    private inner class FolderVH(val v: ItemFolderBinding) : RecyclerView.ViewHolder(v.root) {
        fun bind(row: Row.Folder) {
            val f = row.folder
            val n = f.videos.size
            v.name.text = f.name
            v.countBadge.text = n.toString()
            val stats = Prefs.showWatchStats(itemView.context)
            val parts = ArrayList<String>()
            parts += if (n == 1) "1 video" else "$n videos"
            if (stats) {
                if (row.watched > 0) parts += "${row.watched} watched"
                if (row.inProgress > 0) parts += "${row.inProgress} in progress"
            }
            v.count.text = parts.joinToString(" · ")
            v.path.isVisible = row.showPath && f.path != null
            v.path.text = f.path ?: ""
            v.watchedBar.isVisible = stats && row.watched > 0
            v.watchedBar.progress = if (n > 0) row.watched * 1000 / n else 0
            ThumbLoader.load(v.thumb, f.videos.maxByOrNull { it.dateAdded })
            itemView.setOnClickListener { openFolder = f.id; filter = "all"; refresh(); b.list.scrollToPosition(0) }
            itemView.setOnLongClickListener { onFolderLongClick(row); true }
            v.more.setOnClickListener { onFolderLongClick(row) }
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
            itemView.setOnClickListener { play(all.map { it.entry }, all.indexOf(row).coerceAtLeast(0)) }
            itemView.setOnLongClickListener { onVideoLongClick(row); true }
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

    private inner class PlaylistVH(val v: ItemPlaylistBinding) : RecyclerView.ViewHolder(v.root) {
        fun bind(row: Row.Playlist) {
            v.name.text = row.name
            v.count.text = if (row.count == 1) "1 video" else "${row.count} videos"
            itemView.setOnClickListener { openPlaylist = row.name; refresh() }
            itemView.setOnLongClickListener { onPlaylistLongClick(row); true }
        }
    }
}
