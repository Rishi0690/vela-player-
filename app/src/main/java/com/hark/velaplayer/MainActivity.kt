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
import com.hark.velaplayer.databinding.ItemPlaylistBinding
import com.hark.velaplayer.databinding.ItemVideoBinding
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private enum class Tab { ALL, CONTINUE, FAVORITES, PLAYLISTS }

    sealed class Row {
        data class Video(val entry: Entry, val item: VideoItem?, val progress: Float, val favorite: Boolean) : Row()
        data class Playlist(val name: String, val count: Int) : Row()
    }

    private lateinit var b: ActivityMainBinding
    private val adapter = Adapter()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var tab = Tab.ALL
    private var openPlaylist: String? = null
    private var library: List<VideoItem> = emptyList()
    private var byUri: Map<String, VideoItem> = emptyMap()
    private var query = ""
    private var sort = "date_desc"
    private var loaded = false

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { openPlaylist = null; refresh() }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { loadLibrary() }

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

        b.chips.setOnCheckedStateChangeListener { _, ids ->
            tab = when (ids.firstOrNull()) {
                R.id.chipContinue -> Tab.CONTINUE
                R.id.chipFavorites -> Tab.FAVORITES
                R.id.chipPlaylists -> Tab.PLAYLISTS
                else -> Tab.ALL
            }
            openPlaylist = null
            refresh()
        }
        b.header.setOnClickListener { openPlaylist = null; refresh() }
        b.fab.setOnClickListener { newPlaylistDialog(null) }
        b.btnGrant.setOnClickListener { requestPermission() }

        if (hasPermission()) loadLibrary() else requestPermission()
    }

    override fun onResume() {
        super.onResume()
        if (loaded) refresh()
    }

    // ------------------------------------------------------------------ permissions & loading

    private fun videoPermission() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasPermission() = ContextCompat.checkSelfPermission(this, videoPermission()) == PackageManager.PERMISSION_GRANTED

    private fun requestPermission() {
        val perms = mutableListOf(videoPermission())
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun loadLibrary() {
        io.execute {
            val items = if (hasPermission()) VideoRepository.load(this) else emptyList()
            main.post {
                library = items
                byUri = items.associateBy { it.uri.toString() }
                loaded = true
                refresh()
            }
        }
    }

    // ------------------------------------------------------------------ list building

    private fun refresh() {
        val rows = ArrayList<Row>()
        backCallback.isEnabled = openPlaylist != null
        b.header.isVisible = openPlaylist != null
        b.header.text = "‹  ${openPlaylist ?: ""}"
        b.fab.isVisible = tab == Tab.PLAYLISTS && openPlaylist == null
        val favs = Store.favorites(this).map { it.uri }.toSet()

        fun videoRow(e: Entry): Row.Video {
            val item = byUri[e.uri]
            val pos = Store.position(this, e.uri)
            val dur = Store.duration(this, e.uri).takeIf { it > 0 } ?: item?.durationMs ?: 0L
            val progress = if (pos > 0 && dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
            return Row.Video(e, item, progress, favs.contains(e.uri))
        }

        when {
            tab == Tab.ALL -> library.forEach { rows += videoRow(Entry(it.uri.toString(), it.name)) }
            tab == Tab.CONTINUE -> Store.continueList(this).forEach { rows += videoRow(it) }
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
                }
            }
        }
        // Continue watching keeps its most-recent order; other video lists follow the chosen sort.
        if (tab == Tab.ALL || tab == Tab.FAVORITES) filtered = sortRows(filtered)

        adapter.submit(filtered)
        val empty = filtered.isEmpty()
        b.emptyView.isVisible = empty
        b.btnGrant.isVisible = empty && tab == Tab.ALL && !hasPermission()
        b.emptyText.text = when {
            query.isNotBlank() -> "Nothing matches \"$query\"."
            tab == Tab.ALL && !hasPermission() -> "Allow access so Vela can show the videos on your phone."
            tab == Tab.ALL -> "No videos found on this device yet."
            tab == Tab.CONTINUE -> "Videos you stop partway through show up here."
            tab == Tab.FAVORITES -> "Long-press any video and choose Add to favorites."
            openPlaylist != null -> "This playlist is empty. Long-press a video to add it."
            else -> "No playlists yet. Tap + to create one."
        }
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
        return sorted + rows.filterIsInstance<Row.Playlist>()
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

    private fun onVideoLongClick(row: Row.Video) {
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions += "Play" to { play(listOf(row.entry), 0) }
        actions += (if (row.favorite) "Remove from favorites" else "Add to favorites") to {
            val added = Store.toggleFavorite(this, row.entry)
            toast(if (added) "Added to favorites" else "Removed from favorites"); refresh()
        }
        actions += "Add to playlist" to { addToPlaylistDialog(row.entry) }
        openPlaylist?.let { pl ->
            actions += "Remove from this playlist" to {
                Store.removeFromPlaylist(this, pl, row.entry.uri)
                toast("Removed from playlist (the file is untouched)"); refresh()
            }
        }
        if (tab == Tab.CONTINUE) actions += "Remove from Continue watching" to { Store.clearPosition(this, row.entry.uri); refresh() }
        actions += "Details" to { detailsDialog(row) }
        MaterialAlertDialogBuilder(this).setTitle(row.entry.title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }.show()
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
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Video) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == 0) VideoVH(ItemVideoBinding.inflate(inf, parent, false))
            else PlaylistVH(ItemPlaylistBinding.inflate(inf, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Video -> (holder as VideoVH).bind(row)
                is Row.Playlist -> (holder as PlaylistVH).bind(row)
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
            ThumbLoader.load(v.thumb, item)
            itemView.setOnClickListener { val p = bindingAdapterPosition; if (p != RecyclerView.NO_POSITION) onVideoClick(p) }
            itemView.setOnLongClickListener { onVideoLongClick(row); true }
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
