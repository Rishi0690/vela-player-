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
import androidx.recyclerview.widget.ItemTouchHelper
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.ChipGroup
import com.hark.velaplayer.databinding.ItemCollectionHeaderBinding
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.hark.velaplayer.databinding.ItemVideoBinding
import com.hark.velaplayer.databinding.ItemVideoGridBinding
import java.util.concurrent.Executors

/**
 * Hidden Vault (v2.2, reworked in v2.3).
 *
 * v2.3: opened by holding the Vela Player name on Home for 5 s. The first visit shows a welcome screen
 * (set up PIN / pattern / fingerprint, or use without password). A password-free vault opens straight away;
 * a secured one keeps the v2.2 lock rules below. Vault settings live only inside the vault (toolbar gear).
 *
 * v2.2: Authenticates before anything is shown; FLAG_SECURE keeps the app-switcher preview blank.
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
    private var sort = "custom"
    /** v2.3 filter: all, progress, unwatched, watched. */
    private var filter = "all"
    private var gridMode = false
    private var thumbs = true
    private lateinit var header: ItemCollectionHeaderBinding
    private lateinit var filters: ChipGroup
    private lateinit var touchHelper: ItemTouchHelper
    /** v2.3 "Edit order" (custom order drag & drop), saved in [Vault.setOrder]. */
    private var reorderMode: ActionMode? = null
    private var reorderDirty = false
    private var menuRef: Menu? = null
    private var panel: VaultLockPanel? = null
    private var autoBioTried = false
    /** Set while the player / picker / settings screen is opened from here, so onStop doesn't lock for that trip. */
    private var childOpen = false
    /** The trip is to the player (inactivity / "Immediately" apply on return). The picker and settings screens lock on their own background. */
    private var childIsPlayer = false

    private var actionMode: ActionMode? = null
    private val selected = LinkedHashSet<String>()

    private val idleCheck = object : Runnable {
        override fun run() {
            if (Vault.isUnlocked() && VaultAuth.lockAfterMin(this@VaultActivity) > 0 && !VaultAuth.sessionValid(this@VaultActivity)) lockNow()
            else main.postDelayed(this, 10_000)
        }
    }

    private val settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
        childOpen = false
        // Settings may have unhidden videos, changed the order, view or thumbnails: always reload.
        gridMode = Prefs.viewMode(this, "vault") == "grid"
        thumbs = VaultAuth.showThumbnails(this)
        rows = emptyList()
        render()
    }

    private val storageReq = VaultStorage.Requester(this).also { it.beforeLaunch = { childOpen = true } }

    private val pickLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        childOpen = false
        if (!Vault.isUnlocked()) { render(); return@registerForActivityResult }
        val uris = r.data?.getStringArrayListExtra(VideoPickerActivity.RESULT_URIS)
        val titles = r.data?.getStringArrayListExtra(VideoPickerActivity.RESULT_TITLES)
        if (r.resultCode == Activity.RESULT_OK && uris != null) {
            val entries = uris.mapIndexed { i, u -> Entry(u, titles?.getOrNull(i) ?: (Uri.parse(u).lastPathSegment ?: "Video")) }
            VaultStorage.chooseAndHide(this, storageReq, entries, allowApp = true, onAppHide = { l ->
                val n = Vault.hide(this, l)
                toast(if (n == 0) "Already in the vault" else "Hid $n video${if (n == 1) "" else "s"} in Vela only")
                load()
            }, onPrivateDone = { load() })
        }
    }

    private val back = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() { closeVault() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VaultAuth.applySecure(window, this)
        setContentView(R.layout.vault_activity)
        lockPane = findViewById(R.id.vaultLockPane)
        lockHost = findViewById(R.id.vaultLockHost)
        content = findViewById(R.id.vaultContent)
        toolbar = findViewById(R.id.vaultToolbar)
        list = findViewById(R.id.vaultList)
        empty = findViewById(R.id.vaultEmpty)
        addFab = findViewById(R.id.vaultAdd)
        filters = findViewById(R.id.vaultFilters)
        header = ItemCollectionHeaderBinding.inflate(layoutInflater, findViewById(R.id.vaultHeader), true)
        header.cover.isVisible = false
        header.btnAdd.isVisible = false
        header.btnPlayAll.setOnClickListener { playAll(false) }
        header.btnShuffle.setOnClickListener { playAll(true) }
        header.l23BtnReorder.setOnClickListener { if (reorderMode != null) reorderMode?.finish() else startReorder() }
        Motion.pressable(header.btnPlayAll, 0.95f); Motion.pressable(header.btnShuffle, 0.95f); Motion.pressable(header.l23BtnReorder, 0.9f)
        FitButtons.apply(header.btnPlayAll, header.btnShuffle)
        filters.setOnCheckedStateChangeListener { _, ids ->
            val nf = when (ids.firstOrNull()) { R.id.vaultFProgress -> "progress"; R.id.vaultFUnwatched -> "unwatched"; R.id.vaultFWatched -> "watched"; else -> "all" }
            if (nf != filter) { filter = nf; if (nf != "all") reorderMode?.finish(); show() }
        }
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { closeVault() }
        findViewById<MaterialToolbar>(R.id.vaultLockToolbar).setNavigationOnClickListener { closeVault() }
        onBackPressedDispatcher.addCallback(this, back)

        // A fresh launch (not a rotation) only reuses an unlocked vault inside the inactivity period
        // (e.g. coming back from the player's vault shortcut); otherwise it always asks again.
        VaultAuth.sync(this)
        if (savedInstanceState == null && !VaultAuth.sessionValid(this)) Vault.lock()
        sort = Prefs.sp(this).getString("vault_sort", "custom") ?: "custom"
        gridMode = Prefs.viewMode(this, "vault") == "grid"
        thumbs = VaultAuth.showThumbnails(this)
        lm = GridLayoutManager(this, 1)
        list.layoutManager = lm
        list.adapter = adapter
        (list.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        touchHelper = ItemTouchHelper(reorderCallback).also { it.attachToRecyclerView(list) }
        VelaScroller.attach(list)
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
        VaultAuth.applySecure(window, this)
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
            reorderMode?.finish() // saves a pending order before the list is cleared
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
        reorderMode?.finish()
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
        // v2.8: unlocking eases the library in instead of snapping it on screen.
        // v2.12: unlocking "opens" the vault: the library zooms forward out of a blur.
        if (unlocked && !content.isVisible && lockPane.isVisible) { content.isVisible = true; Motion.zoomThrough(content, forward = true) }
        content.isVisible = unlocked
        addFab.isVisible = unlocked
        lockPane.isVisible = !unlocked
        if (unlocked) {
            panel = null; lockHost.removeAllViews()
            if (rows.isEmpty()) load() else show()
        } else {
            actionMode?.finish()
            reorderMode?.finish()
            showLockPanel()
        }
        invalidateOptionsMenu()
    }

    // ------------------------------------------------------------------ v2.3 welcome (first entry)

    private fun showInLock(v: android.view.View) {
        panel = v as? VaultLockPanel
        lockHost.removeAllViews()
        lockHost.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        lockHost.scrollTo(0, 0)
    }

    private fun showWelcome() {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20), px(8), px(20), px(32)) }
        fun text(s: String, style: Int, alpha: Float = 1f, top: Int = 8, center: Boolean = false) = TextView(this).apply {
            text = s; TextViewCompat.setTextAppearance(this, style); this.alpha = alpha
            if (center) gravity = android.view.Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(top) }
        }
        col.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.vault_ic_hide)
            imageTintList = android.content.res.ColorStateList.valueOf(ThemeManager.primary(this@VaultActivity))
            layoutParams = LinearLayout.LayoutParams(px(48), px(48)).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL; topMargin = px(8) }
        })
        col.addView(text("Welcome to Hidden Vault", com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall, top = 12, center = true))
        col.addView(text("Keep private videos out of your library. You can protect the vault with a PIN, a pattern or your fingerprint, or use it without a password. You can change this later in Vault settings.",
            com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, 0.75f, 8, true))

        fun card(title: String, body: String, build: (LinearLayout) -> Unit) = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(20) }
            radius = 20 * dp
            val inner = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(14), px(16), px(16)) }
            inner.addView(text(title, com.google.android.material.R.style.TextAppearance_Material3_TitleMedium, top = 0))
            inner.addView(text(body, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, 0.75f, 4))
            build(inner)
            addView(inner)
        }
        fun button(label: String, icon: Int, outlined: Boolean, run: () -> Unit) = MaterialButton(this, null,
            if (outlined) com.google.android.material.R.attr.materialButtonOutlinedStyle else com.google.android.material.R.attr.materialButtonStyle).apply {
            text = label; setIconResource(icon)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(52)).apply { topMargin = px(10) }
            setOnClickListener { run() }
        }
        val bio = VaultAuth.biometricAvailable(this)
        col.addView(card("Option A · Set up security", "Ask for authentication every time the vault opens after it locks.") { c ->
            c.addView(button("PIN", R.drawable.ic_lock, false) { welcomeSecret(VaultAuth.PIN, false) })
            c.addView(button("Pattern", R.drawable.ic_grid, true) { welcomeSecret(VaultAuth.PATTERN, false) })
            if (bio) {
                c.addView(button("Fingerprint (with PIN backup)", R.drawable.ic_lock_open, true) { welcomeSecret(VaultAuth.PIN, true) })
                c.addView(text("Fingerprint needs a PIN or pattern as a backup for when it can't be used.", com.google.android.material.R.style.TextAppearance_Material3_BodySmall, 0.7f, 6))
            } else if (VaultAuth.biometricNeedsEnroll(this)) {
                c.addView(text("To use fingerprint, first add one in Android's Settings › Security. You can turn it on later in Vault settings.", com.google.android.material.R.style.TextAppearance_Material3_BodySmall, 0.7f, 6))
            }
        })
        col.addView(card("Option B · Use without password", "The vault will open without any authentication. Anyone who holds the Vela Player name on the Home screen for 5 seconds can see your hidden videos.") { c ->
            c.addView(button("Use without password", R.drawable.ic_lock_open, true) { usePasswordFree() })
        })
        col.addView(text(getString(R.string.vault_not_encrypted), com.google.android.material.R.style.TextAppearance_Material3_BodySmall, 0.7f, 20))
        showInLock(col)
    }

    private fun welcomeSecret(method: String, withBio: Boolean) {
        VaultSecretFlow.enterNew(this, method, ::showInLock, onCancel = { showWelcome() }, onDone = { secret ->
            VaultAuth.setSecret(this, method, secret)
            VaultAuth.completeSetup(this)
            if (withBio) {
                VaultBio.prompt(this, "Confirm fingerprint", "Skip",
                    onOk = { VaultAuth.setBiometric(this, true); toast("Vault secured with fingerprint and PIN"); unlocked() },
                    onFail = { m -> toast(m ?: "Fingerprint skipped. You can turn it on in Vault settings."); unlocked() })
            } else {
                toast(if (method == VaultAuth.PIN) "Vault secured with a PIN" else "Vault secured with a pattern")
                unlocked()
            }
        }, note = if (withBio) "This PIN is the backup for fingerprint unlock." else null)
    }

    private fun usePasswordFree() {
        VelaDialogBuilder(this)
            .setTitle("Use without password?")
            .setMessage("The Hidden Vault won't ask for a PIN, pattern or fingerprint. It is not password-protected. You can set up security later in Vault settings without losing anything.")
            .setPositiveButton("Use without password") { _, _ ->
                VaultAuth.completeSetup(this) // stores the choice and opens the vault (password-free counts as unlocked)
                Vault.markUnlocked()
                render()
            }
            .setNegativeButton("Back", null).show()
    }

    private fun showLockPanel() {
        val p = VaultLockPanel(this)
        showInLock(p)
        if (!VaultAuth.setupDone(this)) { showWelcome(); return }
        if (!VaultAuth.isConfigured(this)) { VaultAuth.sync(this); Vault.markUnlocked(); main.post { render() }; return }
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
            VaultStorage.reconcile(this)
            val lib = VideoRepository.load(this, respectHideTs = false, respectVault = false)
            val byUri = lib.associateBy { it.uri.toString() }
            val fullLookup = lib.isNotEmpty()
            val list = Vault.orderedItems(this).map { it ->
                val v = byUri[it.uri]
                val pos = Vault.position(this, it.uri)
                val dur = Vault.duration(this, it.uri).takeIf { d -> d > 0 } ?: v?.durationMs ?: it.dur
                val missing = if (it.isPrivate) !VaultStorage.exists(it) else fullLookup && it.uri.startsWith("content://media/") && v == null
                VRow(it, v, if (pos > 0 && dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f,
                    missing, Vault.lastPlayed(this, it.uri))
            }
            main.post {
                loading = false
                if (isDestroyed || !Vault.isUnlocked()) return@post
                rows = list
                show()
            }
        }
    }

    private fun durationOf(r: VRow) = r.video?.durationMs?.takeIf { it > 0 } ?: Vault.duration(this, r.item.uri).takeIf { it > 0 } ?: r.item.dur

    private fun show() {
        if (!Vault.isUnlocked()) return
        val q = query.trim().lowercase()
        var l = if (q.isEmpty()) rows else rows.filter { it.item.title.lowercase().contains(q) }
        l = when (filter) {
            "progress" -> l.filter { it.progress > 0f }
            "watched" -> l.filter { Vault.isWatched(this, it.item.uri) }
            "unwatched" -> l.filter { !Vault.isWatched(this, it.item.uri) }
            else -> l
        }
        l = when (sort) {
            "name" -> l.sortedBy { it.item.title.lowercase() }
            "played" -> l.sortedByDescending { it.lastPlayed }
            "hidden" -> l.sortedByDescending { it.item.hiddenAt }
            "longest" -> l.sortedByDescending { durationOf(it) }
            "largest" -> l.sortedByDescending { it.video?.size ?: 0L }
            else -> l // "custom": the saved manual order (rows are loaded in it)
        }
        shown = l
        selected.retainAll(shown.map { it.item.uri }.toHashSet())
        applyMode()
        @Suppress("NotifyDataSetChanged") adapter.notifyDataSetChanged()
        Motion.reveal(empty, shown.isEmpty())
        empty.text = when {
            rows.isEmpty() -> "The vault is empty.\n\nSelect videos in the library and choose \"Hide in vault\", or tap Add videos."
            q.isNotEmpty() -> "Nothing matches \"$query\"."
            else -> "No videos match this filter."
        }
        toolbar.subtitle = null
        bindHeader()
        updateActionMode()
    }

    private fun bindHeader() {
        val has = rows.isNotEmpty()
        findViewById<android.view.View>(R.id.vaultHeader).isVisible = has
        findViewById<android.view.View>(R.id.vaultFilterScroll).isVisible = has
        if (!has) return
        val playable = shown.filter { !it.unavailable }
        header.title.text = if (shown.size == rows.size) (if (rows.size == 1) "1 video" else "${rows.size} videos")
            else "${shown.size} of ${rows.size} videos"
        val parts = ArrayList<String>()
        val dur = shown.sumOf { durationOf(it) }
        if (dur > 0) parts += Fmt.time(dur)
        val size = shown.sumOf { it.video?.size ?: 0L }
        if (size > 0) parts += Formatter.formatShortFileSize(this, size)
        val watched = shown.count { Vault.isWatched(this, it.item.uri) }
        if (watched > 0) parts += "$watched watched"
        val missing = shown.count { it.unavailable }
        if (missing > 0) parts += "$missing unavailable"
        header.subtitle.text = parts.joinToString(" · ")
        header.btnPlayAll.isEnabled = playable.isNotEmpty()
        header.btnShuffle.isEnabled = playable.size > 0
        header.l23BtnReorder.isVisible = rows.size > 1
        header.l23BtnReorder.isChecked = reorderMode != null
    }

    private fun playAll(shuffle: Boolean) {
        if (reorderMode != null) return
        val playable = shown.filter { !it.unavailable }
        if (playable.isEmpty()) { toast("Nothing to play here"); return }
        if (playable.size < shown.size) toast("Skipping ${shown.size - playable.size} unavailable video(s)")
        // Shuffle only randomizes this playback queue; the saved custom order is never changed.
        play(playable, 0, shuffle)
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
            override fun onQueryTextChange(q: String?): Boolean {
                query = q ?: ""; if (query.isNotBlank()) reorderMode?.finish(); show(); return true
            }
        })
        menu.findItem(R.id.vault_lock)?.isVisible = VaultAuth.isConfigured(this)
        menu.findItem(R.id.vault_reorder)?.isVisible = rows.size > 1 && reorderMode == null
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
                val labels = arrayOf("Custom order (your arrangement)", "Recently hidden", "Name A–Z", "Last played", "Longest first", "Largest first")
                val keys = arrayOf("custom", "hidden", "name", "played", "longest", "largest")
                VelaDialogBuilder(this).setTitle("Sort by")
                    .setSingleChoiceItems(labels, keys.indexOf(sort).coerceAtLeast(0)) { d, i ->
                        sort = keys[i]; Prefs.sp(this).edit().putString("vault_sort", sort).apply()
                        if (sort != "custom") reorderMode?.finish()
                        show(); d.dismiss()
                    }.show()
            }
            R.id.vault_select -> if (reorderMode != null) toast("Tap ✓ to finish editing the order first") else startSelection(null)
            R.id.vault_add -> openAddPicker()
            R.id.vault_reorder -> startReorder()
            R.id.vault_settings -> { reorderMode?.finish(); actionMode?.finish(); childOpen = true; settingsLauncher.launch(Intent(this, VaultSettingsActivity::class.java)) }
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

    private fun play(list: List<VRow>, index: Int, shuffled: Boolean = false) {
        val playable = list.filter { !it.unavailable }
        if (playable.isEmpty()) { toast("These videos can't be found on the phone"); return }
        val start = list.getOrNull(index)?.let { playable.indexOf(it) }?.coerceAtLeast(0) ?: 0
        val from = (start - 250).coerceAtLeast(0)
        val to = (from + 500).coerceAtMost(playable.size)
        val window = playable.subList(from, to)
        val i = Intent(this, PlayerActivity::class.java)
            .putStringArrayListExtra(PlayerActivity.EXTRA_URIS, ArrayList(window.map { it.item.uri }))
            .putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, ArrayList(window.map { it.item.title }))
            .putExtra(PlayerSession.EXTRA_SOURCE, Vault.SOURCE_VAULT)
            .putExtra(Vault.EXTRA_VAULT, true)
            .putExtra(PlayerActivity.EXTRA_SHUFFLE_PLAY, shuffled)
        if (!shuffled) i.putExtra(PlayerActivity.EXTRA_INDEX, start - from)
        childOpen = true; childIsPlayer = true
        VaultAuth.touch()
        try { startActivity(i) } catch (_: Exception) { childOpen = false; toast("Couldn't open the player") }
    }

    private fun unhide(uris: List<String>) {
        if (uris.isEmpty()) return
        val all = Vault.items(this).filter { it.uri in uris.toSet() }
        val priv = all.filter { it.isPrivate }; val app = all.filter { !it.isPrivate }
        val msg = buildString {
            if (app.isNotEmpty()) append("${app.size} hidden in Vela only: they go back to the normal library with their resume points. Files aren't changed.")
            if (priv.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("${priv.size} in private storage: Vela copies ${if (priv.size == 1) "it" else "them"} back to phone storage " +
                    "(the original folder when Android allows it, otherwise Movies/Vela Restored), checks the copy, then removes the private copy. " +
                    "${if (priv.size == 1) "It" else "They"} will be visible in Gallery and other apps again.")
            }
        }
        VelaDialogBuilder(this)
            .setTitle(if (uris.size == 1) "Unhide this video?" else "Unhide ${uris.size} videos?")
            .setMessage(msg)
            .setPositiveButton(if (priv.isEmpty()) "Unhide" else "Restore") { _, _ ->
                if (app.isNotEmpty()) Vault.unhide(this, app.map { it.uri })
                actionMode?.finish()
                if (priv.isEmpty()) {
                    toast(if (uris.size == 1) "Back in the library" else "${uris.size} videos back in the library"); load()
                } else VaultStorage.restore(this, storageReq, priv) { load() }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun moveToPrivate(uris: List<String>) {
        val list = Vault.items(this).filter { it.uri in uris.toSet() && !it.isPrivate }
        if (list.isEmpty()) { toast("Already in private storage"); return }
        VaultStorage.chooseAndHide(this, storageReq, list.map { Entry(it.uri, it.title) }, allowApp = false,
            onAppHide = { }, onPrivateDone = { actionMode?.finish(); load() })
    }

    /** v2.13: Midnight Glass options sheet (same actions and behaviour as the old list dialog). */
    private fun options(row: VRow) {
        val play = ArrayList<ItemSheet.Action>(); val manage = ArrayList<ItemSheet.Action>(); val storage = ArrayList<ItemSheet.Action>()
        if (!row.unavailable) {
            play += ItemSheet.Action("Play", R.drawable.ic_play) { play(shown, shown.indexOf(row)) }
            if (row.progress > 0f) play += ItemSheet.Action("Play from start", R.drawable.ic_replay) { Vault.clearPosition(this, row.item.uri); play(shown, shown.indexOf(row)) }
        }
        manage += ItemSheet.Action("Select", R.drawable.ic_select) { startSelection(row) }
        val w = Vault.isWatched(this, row.item.uri)
        manage += ItemSheet.Action(if (w) "Mark as unwatched" else "Mark as watched", R.drawable.ic_check_circle) { Vault.setWatched(this, listOf(row.item.uri), !w); load() }
        if (rows.size > 1) manage += ItemSheet.Action("Edit order", R.drawable.l23_ic_drag_handle) { startReorder() }
        if (!row.item.isPrivate && !row.unavailable) storage += ItemSheet.Action("Move to private storage (hide from Gallery)", R.drawable.ic_lock) { moveToPrivate(listOf(row.item.uri)) }
        storage += ItemSheet.Action(if (row.item.isPrivate) "Unhide (restore to phone storage)" else "Unhide (remove from vault)", R.drawable.vault_ic_unhide) { unhide(listOf(row.item.uri)) }
        storage += ItemSheet.Action("Details", R.drawable.ic_info) { details(row) }
        val v = row.video
        val sub = when {
            row.unavailable -> if (row.item.isPrivate) "Private copy missing" else "Unavailable · moved or deleted"
            else -> listOfNotNull(
                (v?.durationMs ?: row.item.dur).takeIf { it > 0 }?.let { Fmt.time(it) },
                v?.size?.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(this, it) },
                if (row.item.isPrivate) "Private storage" else null).joinToString(" · ")
        }
        val loadThumb = { iv: android.widget.ImageView ->
            if (thumbs) ThumbLoader.load(iv, v?.uri ?: Uri.parse(row.item.uri), v?.id ?: -1L, v?.durationMs ?: 0L)
            else ThumbLoader.load(iv, null as Uri?)
        }
        val bind: ((android.widget.ImageView) -> Unit)? = if (row.unavailable) null else loadThumb
        ItemSheet(this).show(ItemSheet.Header(row.item.title, sub.takeIf { it.isNotEmpty() }, R.drawable.ic_movie, bind), listOf(play, manage, storage))
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
        if (row.item.isPrivate) {
            VaultStorage.fileOf(row.item)?.takeIf { it.exists() }?.let { sb.append("Size: ").append(Formatter.formatFileSize(this, it.length())).append('\n') }
            row.item.origDir?.let { sb.append("Originally in: ").append(it).append('\n') }
            sb.append("\nStored in Vela's private storage: Gallery and other apps can't see it. It is not encrypted. " +
                "Uninstalling Vela or clearing its storage deletes it permanently, so unhide it first.")
        } else sb.append("\nHidden in Vela only: the file isn't moved or encrypted and stays visible in Gallery and other apps. " +
            "Uninstalling Vela doesn't delete it.")
        VelaDialogBuilder(this).setTitle("Details").setMessage(sb.toString()).setPositiveButton("Close", null).show()
    }

    // ------------------------------------------------------------------ multi-select

    private fun startSelection(first: VRow?) {
        if (reorderMode != null) return
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
            menu.findItem(R.id.vsel_private).isVisible = any && Vault.items(this@VaultActivity).any { it.uri in selected && !it.isPrivate }
            menu.findItem(R.id.vsel_shuffle).isVisible = selected.size > 1
            val sel = selected.toList()
            menu.findItem(R.id.vsel_watched).isVisible = sel.any { !Vault.isWatched(this@VaultActivity, it) }
            menu.findItem(R.id.vsel_unwatched).isVisible = sel.any { Vault.isWatched(this@VaultActivity, it) }
            menu.findItem(R.id.vsel_restart).isVisible = sel.any { Vault.position(this@VaultActivity, it) > 0 }
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
                R.id.vsel_private -> moveToPrivate(selected.toList())
                R.id.vsel_shuffle -> {
                    val sel = selected.mapNotNull { u -> shown.firstOrNull { it.item.uri == u } }.filter { !it.unavailable }
                    if (sel.isNotEmpty()) play(sel, 0, true)
                }
                R.id.vsel_watched, R.id.vsel_unwatched -> {
                    val on = item.itemId == R.id.vsel_watched
                    val n = selected.size
                    Vault.setWatched(this@VaultActivity, selected.toList(), on)
                    toast(if (on) "Marked $n as watched" else "Marked $n as unwatched")
                    mode.finish(); load()
                }
                R.id.vsel_restart -> {
                    selected.forEach { Vault.clearPosition(this@VaultActivity, it) }
                    toast("Resume points cleared"); mode.finish(); load()
                }
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
                if (reorderMode != null) return@setOnClickListener
                if (actionMode != null) { toggle(p); return@setOnClickListener }
                val r = shown[p]
                if (r.unavailable) { toast("This video was moved or deleted. Long-press to unhide it.") ; return@setOnClickListener }
                play(shown, p)
            }
            v.root.setOnLongClickListener {
                val p = bindingAdapterPosition; if (p == RecyclerView.NO_POSITION) return@setOnLongClickListener true
                Motion.haptic(it, true)
                if (reorderMode != null) { touchHelper.startDrag(this); return@setOnLongClickListener true }
                if (actionMode != null) toggle(p) else if (Prefs.longPressAction(this@VaultActivity) == "select") startSelection(shown[p]) else options(shown[p])
                true
            }
            v.more.setOnClickListener { row?.let { options(it) } }
            Motion.pressable(v.root, 0.97f)
            v.handle?.setOnTouchListener { h, e ->
                if (reorderMode != null && e.actionMasked == android.view.MotionEvent.ACTION_DOWN) { Motion.haptic(h); touchHelper.startDrag(this) }
                false
            }
        }
        fun bind(r: VRow) {
            row = r
            v.title.text = r.item.title
            val item = r.video
            val parts = ArrayList<String>()
            if (r.unavailable) parts += if (r.item.isPrivate) "Private copy missing" else "Unavailable · moved or deleted"
            else if (item != null) {
                if (item.height > 0) parts += "${item.height}p"
                if (item.size > 0) parts += Formatter.formatShortFileSize(itemView.context, item.size)
            }
            if (r.item.isPrivate && !r.unavailable) parts += "Private storage"
            if (!gridMode) {
                if (Vault.isWatched(itemView.context, r.item.uri)) parts += "Watched"
                else if (r.progress > 0f) parts += "${Math.round(r.progress * 100)}% watched"
            }
            v.subtitle.text = parts.joinToString(" · ")
            v.root.alpha = if (r.unavailable) 0.55f else 1f
            val dur = item?.durationMs ?: Vault.duration(itemView.context, r.item.uri).takeIf { it > 0 } ?: r.item.dur
            v.duration.isVisible = dur > 0
            v.duration.text = Fmt.time(dur)
            v.progress.isVisible = r.progress > 0f
            v.progress.progress = (r.progress * 1000).toInt()
            v.fav.isVisible = false
            v.watched.isVisible = Vault.isWatched(itemView.context, r.item.uri) && Prefs.showWatchStats(itemView.context)
            if (thumbs) ThumbLoader.load(v.thumb, item?.uri ?: Uri.parse(r.item.uri), item?.id ?: -1L, item?.durationMs ?: 0L)
            else ThumbLoader.load(v.thumb, null as Uri?) // privacy option: plain placeholder
            bindSel(r)
        }
        fun bindSel(r: VRow) {
            val selecting = actionMode != null
            val on = r.item.uri in selected
            v.root.isActivated = on
            v.check.isVisible = selecting
            v.check.setBackgroundResource(if (on) R.drawable.bg_select_on else R.drawable.bg_select_off)
            v.check.text = if (on) "✓" else ""
            v.more.isVisible = !selecting && reorderMode == null
            v.handle?.isVisible = reorderMode != null
        }
    }

    // ------------------------------------------------------------------ v2.3 custom order

    private fun startReorder() {
        if (reorderMode != null || !Vault.isUnlocked()) return
        if (rows.size < 2) { toast("Hide at least two videos to arrange them"); return }
        actionMode?.finish()
        var reset = false
        if (query.isNotBlank()) { menuRef?.findItem(R.id.vault_search)?.collapseActionView(); query = ""; reset = true }
        if (filter != "all") { filter = "all"; filters.check(R.id.vaultFAll); reset = true }
        if (sort != "custom") { sort = "custom"; Prefs.sp(this).edit().putString("vault_sort", sort).apply(); toast("Showing your custom order"); reset = true }
        if (reset) show()
        reorderDirty = false
        reorderMode = startSupportActionMode(reorderActionCallback)
        addFab.hide()
        adapter.notifyItemRangeChanged(0, adapter.itemCount, "sel")
        bindHeader(); invalidateOptionsMenu()
    }

    private fun saveReorder() {
        if (!reorderDirty) return
        reorderDirty = false
        // Only ever saved from the full, unfiltered custom-order list, so every vault item is in it.
        if (shown.size == rows.size) {
            Vault.setOrder(this, shown.map { it.item.uri })
            val pos = shown.withIndex().associate { it.value.item.uri to it.index }
            rows = rows.sortedBy { pos[it.item.uri] ?: Int.MAX_VALUE }
        }
    }

    private val reorderActionCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.title = "Edit order"
            mode.subtitle = null // v2.9: no drag hint text
            return true
        }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
        override fun onDestroyActionMode(mode: ActionMode) {
            saveReorder()
            reorderMode = null
            adapter.notifyItemRangeChanged(0, adapter.itemCount, "sel")
            if (Vault.isUnlocked()) { addFab.show(); bindHeader() }
            invalidateOptionsMenu()
        }
    }

    private val reorderCallback = object : ItemTouchHelper.Callback() {
        override fun isLongPressDragEnabled() = false
        override fun isItemViewSwipeEnabled() = false
        override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
            if (reorderMode == null) return 0
            val dirs = if (gridMode) ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
            else ItemTouchHelper.UP or ItemTouchHelper.DOWN
            return makeMovementFlags(dirs, 0)
        }
        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val from = vh.bindingAdapterPosition; val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION || from >= shown.size || to >= shown.size) return false
            val l = shown.toMutableList(); val r = l.removeAt(from); l.add(to, r); shown = l
            adapter.notifyItemMoved(from, to)
            reorderDirty = true
            Motion.haptic(rv)
            return true
        }
        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
        override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(vh, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && vh != null) {
                vh.itemView.animate().translationZ(10 * resources.displayMetrics.density).scaleX(1.03f).scaleY(1.03f).alpha(0.96f)
                    .setDuration(160).setInterpolator(Motion.emphasized).start()
                vh.itemView.isActivated = true
            }
        }
        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            vh.itemView.animate().translationZ(0f).scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).setInterpolator(Motion.emphasized).start()
            vh.itemView.isActivated = false
            saveReorder()
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
