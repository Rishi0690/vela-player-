package com.hark.velaplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hark.velaplayer.databinding.ActivityPickerBinding
import com.hark.velaplayer.databinding.ItemVideoBinding
import com.hark.velaplayer.databinding.ItemVideoGridBinding
import java.util.concurrent.Executors

/**
 * Pick many videos for a playlist: search, folder filter, grid/list, numbered selection (the numbers are the
 * order they'll be added in), plus importing several files with the system file picker.
 */
class VideoPickerActivity : ThemedActivity() {
    companion object { const val EXTRA_PLAYLIST = "playlist" }

    private lateinit var b: ActivityPickerBinding
    private lateinit var playlist: String
    private val io = Executors.newSingleThreadExecutor()
    private var library: List<VideoItem> = emptyList()
    private var shown: List<VideoItem> = emptyList()
    private val picked = LinkedHashMap<String, Entry>() // keeps the order they were tapped
    private var already: Set<String> = emptySet()
    private var query = ""
    private var folderId: String? = null
    private var replace = false
    private var gridMode = false
    private val adapter = PickAdapter()
    private lateinit var lm: GridLayoutManager

    private val importFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        val entries = uris.map { u ->
            try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            Entry(u.toString(), nameOf(u))
        }
        commit(entries, fromFiles = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPickerBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        playlist = intent.getStringExtra(EXTRA_PLAYLIST) ?: run { finish(); return }
        b.toolbar.setNavigationOnClickListener { finish() }
        supportActionBar?.title = "Add to \"$playlist\""
        already = (Store.playlists(this)[playlist] ?: emptyList()).map { it.uri }.toSet()
        gridMode = Prefs.viewMode(this, "picker") == "grid"
        lm = GridLayoutManager(this, 1)
        b.list.layoutManager = lm
        b.list.adapter = adapter
        (b.list.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        applyMode()
        b.search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun afterTextChanged(e: Editable?) { query = e?.toString().orEmpty(); render() }
        })
        b.btnAdd.setOnClickListener { commit(picked.values.toList(), fromFiles = false) }
        b.btnImport.setOnClickListener {
            try { importFiles.launch(arrayOf("video/*")) } catch (_: Exception) { toast("No file picker available") }
        }
        load()
        updateBar()
    }

    override fun onDestroy() { io.shutdown(); super.onDestroy() }

    private fun hasAccess(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)
    }

    private fun load() {
        io.execute {
            val items = if (hasAccess()) VideoRepository.load(this) else emptyList()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                library = items
                buildFolderChips()
                render()
            }
        }
    }

    private fun buildFolderChips() {
        b.folderChips.removeAllViews()
        fun chip(label: String, id: String?) = Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
            text = label; isCheckable = true; isChecked = folderId == id
            setOnClickListener { folderId = id; render() }
        }
        b.folderChips.addView(chip("All videos", null))
        VideoRepository.folders(library, Prefs.hideSystemFolders(this)).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .forEach { b.folderChips.addView(chip("${it.name} (${it.videos.size})", it.id)) }
    }

    private fun render() {
        val q = query.trim().lowercase()
        shown = library.filter { (folderId == null || it.bucketId == folderId) && (q.isEmpty() || it.name.lowercase().contains(q)) }
        adapter.notifyDataSetChanged()
        b.empty.isVisible = shown.isEmpty()
        b.empty.text = when {
            !hasAccess() -> "Vela can't see your videos yet. Use \"From files\" to pick videos, or allow access from the home screen."
            library.isEmpty() -> "No videos found on this device. You can still add files with \"From files\"."
            else -> "Nothing matches \"$query\"."
        }
    }

    private fun applyMode() {
        val span = if (gridMode) (Prefs.gridColumns(this).takeIf { it > 0 } ?: (resources.configuration.screenWidthDp / 180).coerceIn(2, 6)) else 1
        lm.spanCount = span
        adapter.notifyDataSetChanged()
        invalidateOptionsMenu()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean { menuInflater.inflate(R.menu.picker_menu, menu); return true }
    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.p_view)?.apply { setIcon(if (gridMode) R.drawable.ic_list else R.drawable.ic_grid); title = if (gridMode) "List view" else "Grid view" }
        menu.findItem(R.id.p_replace)?.isChecked = replace
        menu.findItem(R.id.p_clear)?.isVisible = picked.isNotEmpty()
        return super.onPrepareOptionsMenu(menu)
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.p_view -> { gridMode = !gridMode; Prefs.setViewMode(this, "picker", if (gridMode) "grid" else "list"); applyMode() }
            R.id.p_all -> { shown.forEach { picked.putIfAbsent(it.uri.toString(), Entry(it.uri.toString(), it.name)) }; adapter.notifyDataSetChanged(); updateBar() }
            R.id.p_clear -> { picked.clear(); adapter.notifyDataSetChanged(); updateBar() }
            R.id.p_replace -> { replace = !replace; item.isChecked = replace; updateBar() }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun toggle(v: VideoItem, pos: Int) {
        val k = v.uri.toString()
        if (picked.remove(k) == null) picked[k] = Entry(k, v.name)
        Motion.haptic(b.list)
        // numbers after this one shift, so refresh what's visible
        adapter.notifyItemRangeChanged(0, adapter.itemCount, "sel")
        updateBar()
        @Suppress("UNUSED_EXPRESSION") pos
    }

    private fun updateBar() {
        val n = picked.size
        b.btnAdd.isEnabled = n > 0
        b.btnAdd.text = when { n == 0 -> "Select videos"; replace -> "Replace with $n"; n == 1 -> "Add 1 video"; else -> "Add $n videos" }
        supportActionBar?.subtitle = if (n == 0) "Tap videos to select them" else "$n selected · numbers show the order"
        invalidateOptionsMenu()
    }

    private fun commit(entries: List<Entry>, fromFiles: Boolean) {
        if (entries.isEmpty()) return
        val doIt = {
            val (added, skipped) = Store.addManyToPlaylist(this, playlist, entries, atStart = Prefs.playlistAddAt(this) == "start", replace = replace && !fromFiles)
            val msg = when {
                added == 0 -> "Nothing new added — ${if (skipped == 1) "it's" else "they're all"} already in \"$playlist\""
                skipped > 0 -> "Added $added to \"$playlist\" · $skipped already there"
                else -> "Added $added video${if (added == 1) "" else "s"} to \"$playlist\""
            }
            toast(msg)
            if (fromFiles) { already = (Store.playlists(this)[playlist] ?: emptyList()).map { it.uri }.toSet(); adapter.notifyDataSetChanged() } else finish()
        }
        if (replace && !fromFiles && already.isNotEmpty()) {
            MaterialAlertDialogBuilder(this).setTitle("Replace \"$playlist\"?")
                .setMessage("The ${already.size} videos now in this playlist will be replaced by the ${entries.size} you picked. Video files aren't deleted.")
                .setPositiveButton("Replace") { _, _ -> doIt() }
                .setNegativeButton("Cancel", null).show()
        } else doIt()
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

    private inner class PickAdapter : RecyclerView.Adapter<PickVH>() {
        override fun getItemCount() = shown.size
        override fun getItemViewType(position: Int) = if (gridMode) 1 else 0
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PickVH {
            val inf = LayoutInflater.from(parent.context)
            return PickVH(if (viewType == 1) MainActivity.VideoViews.of(ItemVideoGridBinding.inflate(inf, parent, false))
            else MainActivity.VideoViews.of(ItemVideoBinding.inflate(inf, parent, false)))
        }
        override fun onBindViewHolder(holder: PickVH, position: Int) = holder.bind(shown[position])
        override fun onBindViewHolder(holder: PickVH, position: Int, payloads: MutableList<Any>) {
            if (payloads.isNotEmpty()) holder.bindSel(shown[position]) else holder.bind(shown[position])
        }
    }

    private inner class PickVH(val v: MainActivity.VideoViews) : RecyclerView.ViewHolder(v.root) {
        init {
            v.more.isVisible = false
            v.root.setOnClickListener { val p = bindingAdapterPosition; if (p != RecyclerView.NO_POSITION) toggle(shown[p], p) }
            Motion.pressable(v.root, 0.97f)
        }
        fun bind(item: VideoItem) {
            v.title.text = item.name
            val inList = item.uri.toString() in already
            v.subtitle.text = listOfNotNull(
                if (inList) "Already in playlist" else null,
                item.height.takeIf { it > 0 }?.let { "${it}p" },
                item.size.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(itemView.context, it) }
            ).joinToString(" · ")
            v.duration.isVisible = item.durationMs > 0
            v.duration.text = Fmt.time(item.durationMs)
            v.progress.isVisible = false
            v.fav.isVisible = false
            v.watched.isVisible = false
            ThumbLoader.load(v.thumb, item)
            bindSel(item)
        }
        fun bindSel(item: VideoItem) {
            val k = item.uri.toString()
            val idx = picked.keys.indexOf(k)
            val on = idx >= 0
            v.root.isActivated = on
            v.check.isVisible = true
            v.check.setBackgroundResource(if (on) R.drawable.bg_select_on else R.drawable.bg_select_off)
            v.check.text = if (on) (idx + 1).toString() else ""
            v.root.alpha = if (k in already && !on) 0.6f else 1f
            v.root.contentDescription = item.name + if (on) ", selected number ${idx + 1}" else ""
        }
    }
}
