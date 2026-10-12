package com.hark.velaplayer

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.hark.velaplayer.databinding.ItemVideoBinding
import com.hark.velaplayer.databinding.ItemVideoGridBinding
import java.util.concurrent.Executors

/**
 * Hidden Vault (v2.2). Authenticates before anything is shown; FLAG_SECURE keeps the app-switcher preview blank.
 *
 * Locking: closing the vault, leaving it (background / another app), the inactivity timer, and any normal library
 * screen resuming all call [Vault.lock]. The one exception is launching the player (or the "Add videos" picker)
 * from here: the vault stays unlocked for the return trip within the inactivity period, and the player locks
 * on its own background. Lists and adapters are emptied whenever the vault locks.
 */
class VaultActivity : ThemedActivity() {

    data class VRow(val item: Vault.Item, val video: VideoItem?, val progress: Float, val unavailable: Boolean, val lastPlayed: Long)

    private lateinit var lockPane: android.view.View
    private lateinit var lockHost: ScrollView
    private lateinit var content: android.view.View
    private lateinit var toolbar: MaterialToolbar
    private lateinit var list: RecyclerView
    private lateinit var empty: android.widget.TextView
    private lateinit var addFab: ExtendedFloatingActionButton
    private lateinit var lm: GridLayoutManager
    private val adapter = VAdapter()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var rows: List<VRow> = emptyList()
    private var shown: List<VRow> = emptyList()
    private var query = ""
    private var sort = "hidden"
    private var gridMode = false
    private var menuRef: Menu? = null
    private var panel: VaultLockPanel? = null
    private var autoBioTried = false
    /** Set while the player / picker / setup screen is opened from here, so onStop doesn't lock for that trip. */
    private var childOpen = false
    /** The trip is to the player (inactivity / "Immediately" apply on return). The picker and security screens lock on their own background. */
    private var childIsPlayer = false
    /** Nothing was configured when setup started (only then does a successful setup open the vault). */
    private var setupFromScratch = false

    private var actionMode: ActionMode? = null
    private val selected = LinkedHashSet<String>()

    private val idleCheck = object : Runnable {
        override fun run() {
            if (Vault.isUnlocked() && VaultAuth.lockAfterMin(this@VaultActivity) > 0 && !VaultAuth.sessionValid(this@VaultActivity)) lockNow()
            else main.postDelayed(this, 10_000)
        }
    }

    private val setupLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        childOpen = false
        if (r.resultCode == Activity.RESULT_OK && setupFromScratch && VaultAuth.isConfigured(this)) {
            setupFromScratch = false
            VaultAuth.unlockNow(); render()
        } else render()
    }

    private val pickLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        childOpen = false
        if (!Vault.isUnlocked()) { render(); return@registerForActivityResult }
        val uris = r.data?.getStringArrayListExtra(VideoPickerActivity.RESULT_URIS)
        val titles = r.data?.getStringArrayListExtra(VideoPickerActivity.RESULT_TITLES)
        if (r.resultCode == Activity.RESULT_OK && uris != null) {
            val entries = uris.mapIndexed { i, u -> Entry(u, titles?.getOrNull(i) ?: (Uri.parse(u).lastPathSegment ?: "Video")) }
            val n = Vault.hide(this, entries)
            toast(if (n == 0) "Already in the vault" else "Hid $n video${if (n == 1) "" else "s"}")
            load()
        }
    }

    private val back = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() { closeVault() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.vault_activity)
        lockPane = findViewById(R.id.vaultLockPane)
        lockHost = findViewById(R.id.vaultLockHost)
        content = findViewById(R.id.vaultContent)
        toolbar = findViewById(R.id.vaultToolbar)
        list = findViewById(R.id.vaultList)
        empty = findViewById(R.id.vaultEmpty)
        addFab = findViewById(R.id.vaultAdd)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { closeVault() }
        findViewById<MaterialToolbar>(R.id.vaultLockToolbar).setNavigationOnClickListener { closeVault() }
        onBackPressedDispatcher.addCallback(this, back)

        // A fresh launch (not a rotation) only reuses an unlocked vault inside the inactivity period
        // (e.g. coming back from the player's vault shortcut); otherwise it always asks again.
        if (savedInstanceState == null && !VaultAuth.sessionValid(this)) Vault.lock()
        sort = Prefs.sp(this).getString("vault_sort", "hidden") ?: "hidden"
        gridMode = Prefs.viewMode(this, "vault") == "grid"
        lm = GridLayoutManager(this, 1)
        list.layoutManager = lm
        list.adapter = adapter
        (list.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        addFab.setOnClickListener { openAddPicker() }
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Repeated shortcut launches land here (singleTop): never skip authentication.
        if (!VaultAuth.sessionValid(this)) Vault.lock()
        autoBioTried = false
        render()
    }

    override fun onStart() {
        super.onStart()
        if (childOpen) {
            childOpen = false
            // Back from the player: "Immediately" or an expired inactivity period asks again.
            if (childIsPlayer && !VaultAuth.sessionValid(this)) Vault.lock()
            childIsPlayer = false
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        main.removeCallbacks(idleCheck); main.postDelayed(idleCheck, 10_000)
        if (!Vault.isUnlocked() && !autoBioTried && VaultAuth.biometricEnabled(this) && VaultAuth.lockoutRemaining(this) == 0L) {
            autoBioTried = true
            main.postDelayed({ if (!isFinishing && !Vault.isUnlocked()) promptBio() }, 250)
        }
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(idleCheck)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !childOpen) {
            Vault.lock()
            clearContent()
            autoBioTried = false
            render()
        }
    }

    override fun onDestroy() {
        io.shutdown()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (Vault.isUnlocked()) VaultAuth.touch()
    }

    private fun closeVault() {
        Vault.lock()
        clearContent()
        finish()
    }

    private fun lockNow() {
        actionMode?.finish()
        Vault.lock()
        clearContent()
        render()
    }

    private fun clearContent() {
        rows = emptyList(); shown = emptyList(); selected.clear()
        @Suppress("NotifyDataSetChanged") adapter.notifyDataSetChanged()
        list.adapter = null; list.adapter = adapter // drop recycled views (thumbnails/titles)
        list.recycledViewPool.clear()
    }

    // ------------------------------------------------------------------ lock screen

    private fun render() {
        val unlocked = Vault.isUnlocked()
        content.isVisible = unlocked
        addFab.isVisible = unlocked
        lockPane.isVisible = !unlocked
        if (unlocked) {
            panel = null; lockHost.removeAllViews()
            if (rows.isEmpty()) load() else show()
        } else {
            actionMode?.finish()
            showLockPanel()
        }
        invalidateOptionsMenu()
    }

    private fun showLockPanel() {
        val p = VaultLockPanel(this)
        panel = p
        lockHost.removeAllViews()
        lockHost.addView(p, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (!VaultAuth.isConfigured(this)) {
            p.setTexts("Set up the Hidden Vault", "Choose a PIN or pattern first. You'll need it every time the vault is opened.\n\n" +
                "Hiding only removes videos from Vela. Files are not encrypted and stay visible in other apps and file managers.")
            p.secondary.isVisible = true
            p.secondary.text = "Set up PIN or pattern"
            p.secondary.setOnClickListener {
                setupFromScratch = true
                childOpen = true
                setupLauncher.launch(Intent(this, VaultSecurityActivity::class.java).putExtra(VaultSecurityActivity.EXTRA_SETUP, true))
            }
            return
        }
        val word = if (VaultAuth.method(this) == VaultAuth.PIN) "PIN" else "pattern"
        p.setTexts("Hidden Vault", "Enter your $word to open the vault")
        p.showInput(VaultAuth.method(this)) { s ->
            when (val r = VaultAuth.verify(this, s)) {
                VaultAuth.Result.Ok -> unlocked()
                is VaultAuth.Result.Wrong -> p.wrong("Wrong $word · ${r.attemptsLeft} attempt${if (r.attemptsLeft == 1) "" else "s"} left")
                is VaultAuth.Result.LockedOut -> p.lockFor(r.remainingMs)
            }
        }
        if (VaultAuth.biometricEnabled(this)) {
            p.bioButton.isVisible = true
            p.bioButton.setOnClickListener { promptBio() }
        }
        VaultAuth.lockoutRemaining(this).takeIf { it > 0 }?.let { p.lockFor(it) }
    }

    private fun promptBio() {
        if (VaultAuth.lockoutRemaining(this) > 0) return
        val word = if (VaultAuth.method(this) == VaultAuth.PIN) "Use PIN" else "Use pattern"
        VaultBio.prompt(this, "Open Hidden Vault", word, onOk = { unlocked() }, onFail = { m -> m?.let { panel?.setError(it) } })
    }

    private fun unlocked() {
        VaultAuth.unlockNow()
        render()
    }

    // ------------------------------------------------------------------ vault library

    private var loading = false

    private fun load() {
        if (io.isShutdown || loading) return
        loading = true
        io.execute {
            // Vault-only lookup: includes hidden and .ts files so every vault entry finds its thumbnail/duration.
            val lib = VideoRepository.load(this, respectHideTs = false, respectVault = false)
            val byUri = lib.associateBy { it.uri.toString() }
            val fullLookup = lib.isNotEmpty()
            val list = Vault.items(this).map { it ->
                val v = byUri[it.uri]
                val pos = Vault.position(this, it.uri)
                val dur = Vault.duration(this, it.uri).takeIf { d -> d > 0 } ?: v?.durationMs ?: 0L
                VRow(it, v, if (pos > 0 && dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f,
                    fullLookup && it.uri.startsWith("content://media/") && v == null, Vault.lastPlayed(this, it.uri))
            }
            main.post {
                loading = false
                if (isDestroyed || !Vault.isUnlocked()) return@post
                rows = list
                show()
            }
        }
    }

    private fun show() {
        if (!Vault.isUnlocked()) return
        val q = query.trim().lowercase()
        var l = if (q.isEmpty()) rows else rows.filter { it.item.title.lowercase().contains(q) }
        l = when (sort) {
            "name" -> l.sortedBy { it.item.title.lowercase() }
            "played" -> l.sortedByDescending { it.lastPlayed }
            else -> l.sortedByDescending { it.item.hiddenAt }
        }
        shown = l
        selected.retainAll(shown.map { it.item.uri }.toHashSet())
        applyMode()
        @Suppress("NotifyDataSetChanged") adapter.notifyDataSetChanged()
        empty.isVisible = shown.isEmpty()
        empty.text = when {
            rows.isEmpty() -> "The vault is empty.\n\nSelect videos in the library and choose \"Hide in vault\", or tap Add videos."
            else -> "Nothing matches \"$query\"."
        }
        toolbar.subtitle = if (rows.isEmpty()) null else if (rows.size == 1) "1 video" else "${rows.size} videos"
        updateActionMode()
    }

    private fun applyMode() {
        val span = if (gridMode) (Prefs.gridColumns(this).takeIf { it > 0 } ?: (resources.configuration.screenWidthDp / 180).coerceIn(2, 6)) else 1
        lm.spanCount = span
        menuRef?.findItem(R.id.vault_view)?.let { it.setIcon(if (gridMode) R.drawable.ic_list else R.drawable.ic_grid); it.title = if (gridMode) "List view" else "Grid view" }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (!Vault.isUnlocked()) { menuRef = null; return false }
        menuInflater.inflate(R.menu.vault_menu, menu)
        menuRef = menu
        val sv = menu.findItem(R.id.vault_search).actionView as SearchView
        sv.queryHint = "Search the vault"
        if (query.isNotEmpty()) { menu.findItem(R.id.vault_search).expandActionView(); sv.setQuery(query, false) }
        sv.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?): Boolean { sv.clearFocus(); return true }
            override fun onQueryTextChange(q: String?): Boolean { query = q ?: ""; show(); return true }
        })
        applyMode()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (!Vault.isUnlocked()) return true
        when (item.itemId) {
            R.id.vault_view -> {
                gridMode = !gridMode
                Prefs.setViewMode(this, "vault", if (gridMode) "grid" else "list")
                applyMode(); @Suppress("NotifyDataSetChanged") adapter.notifyDataSetChanged()
            }
            R.id.vault_sort -> {
                val labels = arrayOf("Recently hidden", "Name A–Z", "Last played")
                val keys = arrayOf("hidden", "name", "played")
                MaterialAlertDialogBuilder(this).setTitle("Sort by")
                    .setSingleChoiceItems(labels, keys.indexOf(sort).coerceAtLeast(0)) { d, i ->
                        sort = keys[i]; Prefs.sp(this).edit().putString("vault_sort", sort).apply(); show(); d.dismiss()
                    }.show()
            }
            R.id.vault_select -> startSelection(null)
            R.id.vault_add -> openAddPicker()
            R.id.vault_security -> { childOpen = true; setupFromScratch = false; setupLauncher.launch(Intent(this, VaultSecurityActivity::class.java)) }
            R.id.vault_lock -> { lockNow(); toast("Vault locked") }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyMode()
    }

    private fun openAddPicker() {
        if (!Vault.isUnlocked()) return
        childOpen = true
        pickLauncher.launch(Intent(this, VideoPickerActivity::class.java).putExtra(VideoPickerActivity.EXTRA_VAULT_PICK, true))
    }

    private fun play(list: List<VRow>, index: Int) {
        val playable = list.filter { !it.unavailable }
        if (playable.isEmpty()) { toast("These videos can't be found on the phone"); return }
        val start = list.getOrNull(index)?.let { playable.indexOf(it) }?.coerceAtLeast(0) ?: 0
        val from = (start - 250).coerceAtLeast(0)
        val to = (from + 500).coerceAtMost(playable.size)
        val window = playable.subList(from, to)
        val i = Intent(this, PlayerActivity::class.java)
            .putStringArrayListExtra(PlayerActivity.EXTRA_URIS, ArrayList(window.map { it.item.uri }))
            .putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, ArrayList(window.map { it.item.title }))
            .putExtra(PlayerActivity.EXTRA_INDEX, start - from)
            .putExtra(PlayerSession.EXTRA_SOURCE, Vault.SOURCE_VAULT)
            .putExtra(Vault.EXTRA_VAULT, true)
        childOpen = true; childIsPlayer = true
        VaultAuth.touch()
        try { startActivity(i) } catch (_: Exception) { childOpen = false; toast("Couldn't open the player") }
    }

    private fun unhide(uris: List<String>) {
        if (uris.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(if (uris.size == 1) "Unhide this video?" else "Unhide ${uris.size} videos?")
            .setMessage("They go back to the normal library with their resume points. Files aren't changed.")
            .setPositiveButton("Unhide") { _, _ ->
                Vault.unhide(this, uris)
                actionMode?.finish()
                toast(if (uris.size == 1) "Back in the library" else "${uris.size} videos back in the library")
                load()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun options(row: VRow) {
        val labels = ArrayList<String>(); val acts = ArrayList<() -> Unit>()
        if (!row.unavailable) {
            labels += "Play"; acts += { play(shown, shown.indexOf(row)) }
            if (row.progress > 0f) { labels += "Play from start"; acts += { Vault.clearPosition(this, row.item.uri); play(shown, shown.indexOf(row)) } }
        }
        labels += "Select"; acts += { startSelection(row) }
        labels += "Unhide (remove from vault)"; acts += { unhide(listOf(row.item.uri)) }
        labels += "Details"; acts += { details(row) }
        MaterialAlertDialogBuilder(this).setTitle(row.item.title)
            .setItems(labels.toTypedArray()) { _, i -> acts[i]() }.show()
    }

    private fun details(row: VRow) {
        val v = row.video
        val sb = StringBuilder()
        sb.append("Name: ").append(row.item.title).append('\n')
        if (row.unavailable) sb.append("Unavailable: moved or deleted\n")
        if (v != null) {
            if (v.durationMs > 0) sb.append("Duration: ").append(Fmt.time(v.durationMs)).append('\n')
            if (v.width > 0) sb.append("Resolution: ").append(v.width).append(" × ").append(v.height).append('\n')
            if (v.size > 0) sb.append("Size: ").append(Formatter.formatFileSize(this, v.size)).append('\n')
            v.path?.let { sb.append("Path: ").append(it).append('\n') }
        }
        if (row.item.hiddenAt > 0) sb.append("Hidden: ").append(DateFormat.getMediumDateFormat(this).format(row.item.hiddenAt)).append('\n')
        val pos = Vault.position(this, row.item.uri)
        if (pos > 0) sb.append("Resume at: ").append(Fmt.time(pos)).append('\n')
        sb.append("\nThe file isn't encrypted and stays visible in other apps.")
        MaterialAlertDialogBuilder(this).setTitle("Details").setMessage(sb.toString()).setPositiveButton("Close", null).show()
    }

    // ------------------------------------------------------------------ multi-select

    private fun startSelection(first: VRow?) {
        if (shown.isEmpty()) { toast("Nothing to select"); return }
        if (actionMode == null) actionMode = startSupportActionMode(selCallback)
        first?.let { selected.add(it.item.uri) }
        adapter.notifyItemRangeChanged(0, adapter.itemCount, "sel")
        updateActionMode()
    }

    private fun toggle(pos: Int) {
        val r = shown.getOrNull(pos) ?: return
        if (!selected.remove(r.item.uri)) selected.add(r.item.uri)
        Motion.haptic(list)
        adapter.notifyItemChanged(pos, "sel")
        updateActionMode()
    }

    private fun updateActionMode() {
        val m = actionMode ?: return
        m.title = if (selected.isEmpty()) "Select items" else "${selected.size} selected"
        m.subtitle = "of ${shown.size}"
        m.invalidate()
    }

    private val selCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean { mode.menuInflater.inflate(R.menu.vault_selection_menu, menu); addFab.hide(); return true }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            val any = selected.isNotEmpty()
            menu.findItem(R.id.vsel_all).title = if (any && selected.size >= shown.size) "Deselect all" else "Select all"
            menu.findItem(R.id.vsel_play).isVisible = any
            menu.findItem(R.id.vsel_unhide).isVisible = any
            return true
        }
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                R.id.vsel_all -> {
                    val all = shown.map { it.item.uri }
                    if (selected.containsAll(all)) selected.clear() else selected.addAll(all)
                    adapter.notifyItemRangeChanged(0, adapter.itemCount, "sel"); updateActionMode()
                }
                R.id.vsel_play -> {
                    val sel = selected.mapNotNull { u -> shown.firstOrNull { it.item.uri == u } }
                    play(sel, 0)
                }
                R.id.vsel_unhide -> unhide(selected.toList())
            }
            return true
        }
        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null; selected.clear()
            adapter.notifyItemRangeChanged(0, adapter.itemCount, "sel")
            if (Vault.isUnlocked()) addFab.show()
        }
    }

    // ------------------------------------------------------------------ adapter

    private inner class VAdapter : RecyclerView.Adapter<VVH>() {
        override fun getItemCount() = shown.size
        override fun getItemViewType(position: Int) = if (gridMode) 1 else 0
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VVH {
            val inf = LayoutInflater.from(parent.context)
            return VVH(if (viewType == 1) MainActivity.VideoViews.of(ItemVideoGridBinding.inflate(inf, parent, false))
            else MainActivity.VideoViews.of(ItemVideoBinding.inflate(inf, parent, false)))
        }
        override fun onBindViewHolder(holder: VVH, position: Int) = holder.bind(shown[position])
        override fun onBindViewHolder(holder: VVH, position: Int, payloads: MutableList<Any>) {
            if (payloads.isNotEmpty()) holder.bindSel(shown[position]) else holder.bind(shown[position])
        }
    }

    private inner class VVH(val v: MainActivity.VideoViews) : RecyclerView.ViewHolder(v.root) {
        private var row: VRow? = null
        init {
            v.root.setOnClickListener {
                val p = bindingAdapterPosition; if (p == RecyclerView.NO_POSITION) return@setOnClickListener
                if (actionMode != null) { toggle(p); return@setOnClickListener }
                val r = shown[p]
                if (r.unavailable) { toast("This video was moved or deleted. Long-press to unhide it.") ; return@setOnClickListener }
                play(shown, p)
            }
            v.root.setOnLongClickListener {
                val p = bindingAdapterPosition; if (p == RecyclerView.NO_POSITION) return@setOnLongClickListener true
                Motion.haptic(it, true)
                if (actionMode != null) toggle(p) else if (Prefs.longPressAction(this@VaultActivity) == "select") startSelection(shown[p]) else options(shown[p])
                true
            }
            v.more.setOnClickListener { row?.let { options(it) } }
            Motion.pressable(v.root, 0.97f)
        }
        fun bind(r: VRow) {
            row = r
            v.title.text = r.item.title
            val item = r.video
            val parts = ArrayList<String>()
            if (r.unavailable) parts += "Unavailable · moved or deleted"
            else if (item != null) {
                if (item.height > 0) parts += "${item.height}p"
                if (item.size > 0) parts += Formatter.formatShortFileSize(itemView.context, item.size)
            }
            if (!gridMode && Vault.isWatched(itemView.context, r.item.uri)) parts += "Watched"
            v.subtitle.text = parts.joinToString(" · ")
            v.root.alpha = if (r.unavailable) 0.55f else 1f
            val dur = item?.durationMs ?: Vault.duration(itemView.context, r.item.uri)
            v.duration.isVisible = dur > 0
            v.duration.text = Fmt.time(dur)
            v.progress.isVisible = r.progress > 0f
            v.progress.progress = (r.progress * 1000).toInt()
            v.fav.isVisible = false
            v.watched.isVisible = Vault.isWatched(itemView.context, r.item.uri) && Prefs.showWatchStats(itemView.context)
            ThumbLoader.load(v.thumb, item?.uri ?: Uri.parse(r.item.uri), item?.id ?: -1L, item?.durationMs ?: 0L)
            bindSel(r)
        }
        fun bindSel(r: VRow) {
            val selecting = actionMode != null
            val on = r.item.uri in selected
            v.root.isActivated = on
            v.check.isVisible = selecting
            v.check.setBackgroundResource(if (on) R.drawable.bg_select_on else R.drawable.bg_select_off)
            v.check.text = if (on) "✓" else ""
            v.more.isVisible = !selecting
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
