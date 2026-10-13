package com.hark.velaplayer

import android.content.Intent
import android.content.res.XmlResourceParser
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.preference.PreferenceGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hark.velaplayer.databinding.ItemSettingResultBinding
import org.xmlpull.v1.XmlPullParser
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hark.velaplayer.databinding.ActivitySettingsBinding

/** Settings home with six categories; each opens its own screen. */
class SettingsActivity : ThemedActivity() {
    companion object {
        const val EXTRA_SECTION = "section"
        /** Preference key to scroll to and highlight when a section opens (from settings search). */
        const val EXTRA_HIGHLIGHT = "highlight"
        private val TITLES = mapOf(
            "appearance" to "Appearance & personalization", "library" to "Library & display", "gestures" to "Gestures",
            "playback" to "Playback", "playlists" to "Playlists & selection", "focus" to "Focus Mode"
        )
        private val XML = mapOf(
            "appearance" to R.xml.prefs_appearance, "library" to R.xml.prefs_library, "gestures" to R.xml.prefs_gestures,
            "playback" to R.xml.prefs_playback, "playlists" to R.xml.prefs_playlists, "focus" to R.xml.prefs_focus
        )
        /** v2.3: the Hidden Vault instruction rows, left out of the screen and search while hidden from inside the vault. */
        private val VAULT_KEYS = setOf("vault_section", "vault_instructions")
    }

    private lateinit var b: ActivitySettingsBinding
    private var index: List<SettingsSearch.Item>? = null
    private val results = ResultAdapter()
    private val clearSearchOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { b.search.setText("") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        onBackPressedDispatcher.addCallback(this, clearSearchOnBack)
        b.searchResults.layoutManager = LinearLayoutManager(this)
        b.searchResults.adapter = results
        // v2.12: opaque layers carry a window-aligned copy of the atmosphere (no flat colour blocks).
        Atmosphere.viewBackground(b.searchResults); Atmosphere.viewBackground(b.searchEmpty); Atmosphere.viewBackground(b.searchBox)
        b.searchResults.itemAnimator = VelaItemAnimator()
        VelaScroller.attach(b.searchResults)
        // v2.8: results and the empty message sit below the overlaid search field.
        b.searchBox.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) {
                val h = bottom - top
                b.searchResults.setPadding(0, h, 0, b.searchResults.paddingBottom)
                b.searchEmpty.setPadding(b.searchEmpty.paddingLeft, h, b.searchEmpty.paddingRight, b.searchEmpty.paddingBottom)
                (supportFragmentManager.findFragmentById(R.id.container) as? RootFragment)?.padForSearch(h)
            }
        }
        b.search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun afterTextChanged(e: Editable?) { runSearch(e?.toString().orEmpty()) }
        })
        b.search.setOnEditorActionListener { v, _, _ -> hideKeyboard(v); true }
        supportFragmentManager.addOnBackStackChangedListener { updateTitle() }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.container, RootFragment()).commit()
            intent.getStringExtra(EXTRA_SECTION)?.takeIf { XML.containsKey(it) }?.let { open(it, addToBack = false, highlight = intent.getStringExtra(EXTRA_HIGHLIGHT)) }
        }
        updateTitle()
    }

    override fun onResume() {
        super.onResume()
        // Settings is a normal screen: the Hidden Vault never stays unlocked behind it.
        Vault.lock()
    }

    // ------------------------------------------------------------------ search (v2.1)

    /** Called by each fragment when its screen shows: the search field lives on the main Settings screen only. */
    fun onScreenShown(root: Boolean) {
        // v2.8: the field is an overlay, so fading it never moves the screens below (that shift was the jump
        // users saw when entering or leaving a category).
        if (root) Motion.enter(b.searchBox, duration = Motion.MEDIUM, delay = 40) else Motion.exit(b.searchBox, 120)
        if (!root && b.search.text?.isNotEmpty() == true) b.search.setText("")
    }

    fun searchBoxHeight() = b.searchBox.height

    /** v2.8: cross-fades the toolbar title instead of swapping it instantly. */
    private fun setTitleAnimated(t: CharSequence) {
        if (b.toolbar.title == t) return
        val tv = (0 until b.toolbar.childCount).map { b.toolbar.getChildAt(it) }.filterIsInstance<android.widget.TextView>()
            .firstOrNull { it.text == b.toolbar.title }
        if (tv == null || !Motion.enabled(this)) { b.toolbar.title = t; return }
        tv.animate().cancel()
        tv.animate().alpha(0f).setDuration(90).setInterpolator(Motion.emphasizedAccel).withEndAction {
            b.toolbar.title = t
            tv.alpha = 0f
            tv.animate().alpha(1f).setDuration(180).setInterpolator(Motion.emphasized).start()
        }.start()
    }

    private fun runSearch(q: String) {
        val query = q.trim()
        clearSearchOnBack.isEnabled = query.isNotEmpty()
        if (query.isEmpty()) {
            b.searchResults.isVisible = false; b.searchEmpty.isVisible = false
            results.submit(emptyList()); return
        }
        // Built once per visit by reading every settings XML, so new settings appear without code changes.
        val idx = (index ?: SettingsSearch.build(this, XML, TITLES).also { index = it })
            .let { all -> if (VaultAuth.hideInstructions(this)) all.filter { it.key !in VAULT_KEYS } else all }
        val found = SettingsSearch.search(idx, query)
        results.submit(found)
        if (found.isNotEmpty() && !b.searchResults.isVisible) { b.searchResults.isVisible = true; Motion.zoomThrough(b.searchResults, true) }
        b.searchResults.isVisible = found.isNotEmpty()
        if (found.isEmpty()) Motion.reveal(b.searchEmpty, true) else Motion.reveal(b.searchEmpty, false)
        b.searchEmpty.text = "No settings match \"$query\""
        if (found.isNotEmpty()) b.searchResults.scrollToPosition(0)
    }

    private fun hideKeyboard(v: View) {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(v.windowToken, 0)
        v.clearFocus()
    }

    private fun openResult(item: SettingsSearch.Item) {
        hideKeyboard(b.search)
        b.search.setText("")
        if (item.section == null) {
            (supportFragmentManager.findFragmentById(R.id.container) as? RootFragment)?.reveal(item.key)
        } else open(item.section, highlight = item.key.takeIf { !item.isSection })
    }

    private inner class ResultAdapter : RecyclerView.Adapter<ResultVH>() {
        var items: List<SettingsSearch.Item> = emptyList()
        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun submit(l: List<SettingsSearch.Item>) { items = l; notifyDataSetChanged() }
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ResultVH(ItemSettingResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: ResultVH, position: Int) = holder.bind(items[position])
    }

    private inner class ResultVH(val v: ItemSettingResultBinding) : RecyclerView.ViewHolder(v.root) {
        fun bind(item: SettingsSearch.Item) {
            v.resultTitle.text = item.title
            v.resultPath.text = if (item.isSection) "Settings category" else
                listOfNotNull(item.sectionTitle, item.category?.takeIf { it.isNotBlank() && it != item.sectionTitle }).joinToString("  ›  ")
            v.resultSummary.text = item.summary
            v.resultSummary.isVisible = !item.summary.isNullOrBlank()
            v.root.setOnClickListener { openResult(item) }
        }
    }

    private var lastNavAt = 0L
    fun open(section: String, addToBack: Boolean = true, highlight: String? = null) {
        // v2.7: ignore rapid repeat taps / re-opening the screen already on top, which stacked duplicate transitions.
        val now = android.os.SystemClock.uptimeMillis()
        val fm = supportFragmentManager
        if (fm.isStateSaved || now - lastNavAt < 350 || fm.findFragmentById(R.id.container)?.tag == section) return
        lastNavAt = now
        val frag = SectionFragment().apply { arguments = Bundle().apply { putString(EXTRA_SECTION, section); if (highlight != null) putString(EXTRA_HIGHLIGHT, highlight) } }
        val t = fm.beginTransaction().setReorderingAllowed(true)
            .setCustomAnimations(R.anim.nav_push_enter, R.anim.nav_push_exit, R.anim.nav_pop_enter, R.anim.nav_pop_exit)
        val current = fm.findFragmentById(R.id.container)
        if (addToBack && current != null) {
            // v2.8 root cause fix: `replace` destroyed the main screen's view and re-inflated + re-bound every row
            // on Back, on the same frame the pop animation started (the visible hitch). Hiding keeps the screen
            // alive, so Back only fades it in again with its scroll position intact.
            t.hide(current).add(R.id.container, frag, section)
        } else t.replace(R.id.container, frag, section)
        if (addToBack) t.addToBackStack(section)
        t.commit()
        TITLES[section]?.let { setTitleAnimated(it) }
    }

    private fun updateTitle() {
        val f = supportFragmentManager.findFragmentById(R.id.container)
        setTitleAnimated((f as? SectionFragment)?.arguments?.getString(EXTRA_SECTION)?.let { TITLES[it] } ?: "Settings")
    }

    class RootFragment : PreferenceFragmentCompat() {
        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            prepareNavScreen(this, view)
            (activity as? SettingsActivity)?.let { a -> if (!isHidden) a.onScreenShown(true); padForSearch(a.searchBoxHeight()) }
        }
        override fun onHiddenChanged(hidden: Boolean) {
            super.onHiddenChanged(hidden)
            if (!hidden) (activity as? SettingsActivity)?.onScreenShown(true)
        }
        override fun onCreateAnimation(transit: Int, enter: Boolean, nextAnim: Int): android.view.animation.Animation? =
            navAnim(this, nextAnim)
        fun reveal(key: String) = revealPreference(this, key)

        /** v2.8.2: choice and text pop-ups use the same Material dialog as the rest of the app. */
        override fun onDisplayPreferenceDialog(preference: Preference) {
            if (!showVelaPrefDialog(this, preference)) super.onDisplayPreferenceDialog(preference)
        }

        /** Keeps the first row clear of the overlaid search field; rows scroll up underneath it. */
        fun padForSearch(h: Int) {
            val l = listView ?: return
            if (h <= 0) return
            l.clipToPadding = false
            l.setPadding(l.paddingLeft, h, l.paddingRight, l.paddingBottom)
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            // v2.3: the Hidden Vault section only explains how to open the vault (no list, no controls) and can be
            // removed from inside the vault.
            if (VaultAuth.hideInstructions(requireContext())) findPreference<Preference>("vault_section")?.let { preferenceScreen.removePreference(it) }
            listOf("appearance", "library", "gestures", "playback", "playlists", "focus").forEach { s ->
                findPreference<Preference>("sec_$s")?.setOnPreferenceClickListener { (activity as SettingsActivity).open(s); true }
            }
            // v2.14: the Widgets gallery (its own screen, like the theme editor).
            findPreference<Preference>("open_widgets")?.setOnPreferenceClickListener { startActivity(Intent(requireContext(), WidgetsActivity::class.java)); true }
            findPreference<Preference>("reset_all")?.setOnPreferenceClickListener {
                VelaDialogBuilder(requireContext())
                    .setTitle("Reset all settings?")
                    .setMessage("Playback, gesture, subtitle, layout, Focus Mode and theme settings go back to defaults. Playlists, favorites, resume points, saved themes, focus history and the Hidden Vault (its videos and its PIN/pattern) are kept.")
                    .setPositiveButton("Reset") { _, _ ->
                        val sp = Prefs.sp(requireContext())
                        val keepThemes = sp.getString("custom_themes", null)
                        val dndApplied = sp.getBoolean("focus_dnd_applied", false)
                        Dnd.restore(requireContext())
                        sp.edit().clear().commit()
                        sp.edit().apply { if (keepThemes != null) putString("custom_themes", keepThemes); if (dndApplied) putBoolean("focus_dnd_applied", false) }.commit()
                        ThemeManager.touch(requireContext())
                        Prefs.applyTheme(requireContext())
                        activity?.recreate()
                    }
                    .setNegativeButton("Cancel", null).show()
                true
            }
        }
    }

    class SectionFragment : PreferenceFragmentCompat() {
        private var askingDnd = false

        /** v2.8.2: choice and text pop-ups use the same Material dialog as the rest of the app. */
        override fun onDisplayPreferenceDialog(preference: Preference) {
            if (!showVelaPrefDialog(this, preference)) super.onDisplayPreferenceDialog(preference)
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            prepareNavScreen(this, view)
            (activity as? SettingsActivity)?.onScreenShown(false)
            if (savedInstanceState == null) arguments?.getString(EXTRA_HIGHLIGHT)?.let { key ->
                // Wait for the open animation so the row is laid out, then scroll to it and flash it.
                view.postDelayed({ if (isAdded) revealPreference(this, key) }, 320)
            }
        }

        override fun onCreateAnimation(transit: Int, enter: Boolean, nextAnim: Int): android.view.animation.Animation? =
            navAnim(this, nextAnim)

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val section = arguments?.getString(EXTRA_SECTION) ?: "playback"
            setPreferencesFromResource(XML[section] ?: R.xml.prefs_playback, rootKey)
            bind()
        }

        override fun onResume() {
            super.onResume()
            // v2.9: the player's rotate button may have changed the orientation mode meanwhile.
            findPreference<androidx.preference.ListPreference>("default_orientation")?.let { lp ->
                val cur = Prefs.orientation(requireContext()); if (lp.value != cur) lp.value = cur
            }
            // Came back from Android's Do Not Disturb access screen.
            if (askingDnd) {
                askingDnd = false
                val granted = Dnd.hasAccess(requireContext())
                findPreference<SwitchPreferenceCompat>("focus_dnd")?.isChecked = granted
                toast(if (granted) "Do Not Disturb will turn on during focus sessions" else "Access not given, so Do Not Disturb stays off")
            }
        }

        private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()

        private fun bind() {
            val ctx = requireContext()
            findPreference<Preference>("theme_editor")?.setOnPreferenceClickListener { startActivity(Intent(ctx, ThemeActivity::class.java)); true }
            findPreference<Preference>("theme")?.setOnPreferenceChangeListener { _, _ ->
                view?.post { ThemeManager.touch(ctx); Prefs.applyTheme(ctx) }
                true
            }
            findPreference<Preference>("reset_views")?.setOnPreferenceClickListener {
                val sp = Prefs.sp(ctx); val e = sp.edit()
                sp.all.keys.filter { it.startsWith("view_") }.forEach { e.remove(it) }
                e.apply(); toast("Every screen now uses the default view"); true
            }
            findPreference<Preference>("refresh_thumbs")?.setOnPreferenceClickListener { ThumbLoader.clearCache(); toast("Thumbnails will reload"); true }
            findPreference<Preference>("smart_thumbnails")?.setOnPreferenceChangeListener { _, _ -> ThumbLoader.clearCache(); true }
            findPreference<Preference>("clear_remembered")?.setOnPreferenceClickListener {
                Prefs.sp(ctx).edit().remove("last_audio_lang").remove("last_sub_lang").remove("last_sub_state").remove("last_sub_label").apply()
                toast("Remembered tracks cleared"); true
            }
            findPreference<Preference>("focus_goal_min")?.let { p ->
                fun summary() { val m = Prefs.focusGoalMin(ctx); p.summary = if (m > 0) FocusFmt.dur(m * 60_000L) + " a day" else "No limit" }
                summary()
                p.setOnPreferenceClickListener { FocusLimitDialog.show(requireActivity()) { summary() }; true }
            }
            findPreference<Preference>("focus_dashboard")?.setOnPreferenceClickListener { startActivity(Intent(ctx, FocusActivity::class.java)); true }
            findPreference<Preference>("focus_clear")?.setOnPreferenceClickListener {
                VelaDialogBuilder(ctx).setTitle("Clear focus history?")
                    .setMessage("All focus sessions and totals will be deleted from this phone. Videos, watch progress and settings stay.")
                    .setPositiveButton("Clear") { _, _ -> FocusStore.get(ctx).clearAll(); toast("Focus history cleared") }
                    .setNegativeButton("Cancel", null).show()
                true
            }
            findPreference<SwitchPreferenceCompat>("focus_dnd")?.setOnPreferenceChangeListener { _, v ->
                val on = v as Boolean
                if (!on) { Dnd.restore(ctx); return@setOnPreferenceChangeListener true }
                if (Dnd.hasAccess(ctx)) return@setOnPreferenceChangeListener true
                // Explain before sending the user to the system screen; never change anything silently.
                VelaDialogBuilder(ctx).setTitle("Allow Do Not Disturb access?")
                    .setMessage("To silence calls and notifications during focus sessions, Vela needs Android's \"Do Not Disturb access\". " +
                        "Vela only switches Do Not Disturb on when a focus session starts (if it was off) and switches it back off when the session ends. " +
                        "Priority contacts and alarms follow your phone's own Do Not Disturb rules.\n\nOn the next screen, find Vela Player and allow access.")
                    .setPositiveButton("Continue") { _, _ ->
                        askingDnd = true
                        try { startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
                        catch (_: Exception) { askingDnd = false; toast("This phone doesn't offer Do Not Disturb access") }
                    }
                    .setNegativeButton("Not now", null).show()
                false
            }
            if (Build.VERSION.SDK_INT < 23) findPreference<Preference>("focus_dnd")?.isEnabled = false
        }
    }
}

/**
 * v2.8.2: Material 3 versions of the stock preference pop-ups (which used the old platform AlertDialog look).
 * Lists: radio choices with the current one checked, picks apply at once and close with the shared dialog motion;
 * the change still goes through callChangeListener, so listeners (theme, thumbnails...) behave as before.
 * Text: an outlined field with the explanation as helper text. Returns false for anything else.
 */
private fun showVelaPrefDialog(f: PreferenceFragmentCompat, p: Preference): Boolean {
    val ctx = f.context ?: return false
    if (f.parentFragmentManager.isStateSaved) return false
    val d = ctx.resources.displayMetrics.density
    fun titleView(title: CharSequence?, sub: CharSequence?): View = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding((24 * d).toInt(), (22 * d).toInt(), (24 * d).toInt(), (if (sub.isNullOrBlank()) 8 else 10).let { (it * d).toInt() })
        addView(android.widget.TextView(ctx).apply {
            text = title
            androidx.core.widget.TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall)
        })
        if (!sub.isNullOrBlank()) addView(android.widget.TextView(ctx).apply {
            text = sub; alpha = 0.72f
            androidx.core.widget.TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setPadding(0, (6 * d).toInt(), 0, 0)
        })
    }
    when (p) {
        is androidx.preference.ListPreference -> {
            val entries = p.entries ?: return false
            val values = p.entryValues ?: return false
            val cur = p.findIndexOfValue(p.value)
            val sub = (p.dialogTitle ?: p.dialogMessage)?.takeIf { it.toString() != p.title?.toString() }
            VelaDialogBuilder(ctx)
                .setCustomTitle(titleView(p.title, sub))
                .setSingleChoiceItems(entries, cur) { dlg, i ->
                    val v = values[i].toString()
                    if (p.callChangeListener(v)) p.value = v
                    dlg.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
            return true
        }
        is androidx.preference.EditTextPreference -> {
            val box = com.google.android.material.textfield.TextInputLayout(ctx, null,
                com.google.android.material.R.attr.textInputOutlinedStyle).apply {
                hint = p.title
                helperText = p.dialogMessage
                setBoxCornerRadii(16 * d, 16 * d, 16 * d, 16 * d)
            }
            val edit = com.google.android.material.textfield.TextInputEditText(box.context).apply {
                setText(p.text); setSingleLine(); setSelection(text?.length ?: 0)
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            }
            box.addView(edit)
            val wrap = android.widget.FrameLayout(ctx).apply {
                setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0)
                addView(box)
            }
            val dlg = VelaDialogBuilder(ctx)
                .setTitle(p.title)
                .setView(wrap)
                .setPositiveButton("Save") { _, _ ->
                    val v = edit.text?.toString()?.trim().orEmpty()
                    if (p.callChangeListener(v)) p.text = v
                }
                .setNegativeButton("Cancel", null)
                .create()
            edit.setOnEditorActionListener { _, _, _ -> dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.performClick(); true }
            dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            dlg.show()
            edit.requestFocus()
            return true
        }
    }
    return false
}

/** Scrolls a settings screen to [key] and briefly highlights its row with the theme accent. */
private fun revealPreference(f: PreferenceFragmentCompat, key: String) {
    val pref = f.findPreference<Preference>(key) ?: return
    f.scrollToPreference(pref)
    val list = f.listView ?: return
    list.postDelayed({
        val pos = (list.adapter as? PreferenceGroup.PreferencePositionCallback)?.getPreferenceAdapterPosition(pref) ?: return@postDelayed
        if (pos < 0) return@postDelayed
        val row = list.findViewHolderForAdapterPosition(pos)?.itemView ?: return@postDelayed
        val accent = ThemeManager.primary(f.requireContext())
        val flash = android.graphics.drawable.ColorDrawable(accent and 0x00FFFFFF or 0x40000000)
        val old = row.foreground
        row.foreground = flash
        android.animation.ValueAnimator.ofInt(0x40, 0x40, 0).apply {
            duration = 1600
            addUpdateListener { flash.color = (accent and 0x00FFFFFF) or ((it.animatedValue as Int) shl 24) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { row.foreground = old }
            })
            start()
        }
    }, 250)
}

/**
 * Settings search. The index is built at runtime by parsing the settings XML files, so any setting added to
 * a prefs_*.xml file is searchable automatically. Matches titles, summaries, list choices, category names and
 * a few common alternative words.
 */
object SettingsSearch {
    data class Item(
        val key: String, val title: String, val summary: String?, val category: String?,
        val section: String?, val sectionTitle: String, val haystack: String,
        /** True for a whole category (opens its screen without highlighting a row). */
        val isSection: Boolean = false,
    )

    /** Extra words a search term should also find. */
    private val SYNONYMS = mapOf(
        "dark" to listOf("theme", "appearance"), "light" to listOf("theme", "appearance"), "night" to listOf("theme", "appearance"),
        "mode" to listOf("theme", "appearance"), "color" to listOf("accent", "theme"), "colour" to listOf("accent", "theme"),
        "accent" to listOf("theme"), "speed" to listOf("speed", "fast", "slow"), "fast" to listOf("speed"), "slow" to listOf("speed"),
        "subtitle" to listOf("subtitle", "caption"), "subtitles" to listOf("subtitle"), "caption" to listOf("subtitle"), "captions" to listOf("subtitle"), "cc" to listOf("subtitle"), "srt" to listOf("subtitle"),
        "ts" to listOf(".ts", "transport"), "transport" to listOf(".ts"), "focus" to listOf("focus"), "goal" to listOf("limit", "goal"), "limit" to listOf("goal", "limit"),
        "screenshot" to listOf("screenshot", "capture", "three-finger", "three finger"), "capture" to listOf("screenshot"),
        "boost" to listOf("volume", "boost", "amplif"), "loud" to listOf("volume", "boost"), "louder" to listOf("volume", "boost"), "amplify" to listOf("boost", "volume"),
        "sound" to listOf("audio", "volume"), "zoom" to listOf("aspect", "pinch", "zoom"), "stretch" to listOf("aspect"), "crop" to listOf("aspect"),
        "pip" to listOf("picture-in-picture"), "rotate" to listOf("orientation", "rotation"), "rotation" to listOf("orientation"),
        "dnd" to listOf("do not disturb"), "silent" to listOf("do not disturb"), "grid" to listOf("view", "columns"), "list" to listOf("view"),
        "resume" to listOf("position", "remember", "left off"), "continue" to listOf("position", "left off"), "start" to listOf("open the app"),
        "hidden" to listOf("hide"), "language" to listOf("lang", "language"), "swipe" to listOf("gesture", "swipe"), "gesture" to listOf("swipe", "gesture"),
        "skip" to listOf("seek", "skip"), "seek" to listOf("skip", "seek"), "thumbnail" to listOf("thumbnail", "thumbs"), "decoder" to listOf("hardware", "software"),
        "vault" to listOf("vault", "hidden", "hide"), "private" to listOf("vault", "hidden"), "hide" to listOf("hide", "vault"),
        "pin" to listOf("security", "pin"), "password" to listOf("security", "pin", "pattern"), "passcode" to listOf("pin", "security"),
        "fingerprint" to listOf("fingerprint", "biometric"), "biometric" to listOf("fingerprint", "biometric"), "pattern" to listOf("pattern", "security"),
        "lock" to listOf("lock", "security"), "scroll" to listOf("scroll"), "tabs" to listOf("tab", "swipe"), "tab" to listOf("tab", "swipe"),
        "loop" to listOf("repeat"), "repeat" to listOf("loop"), "shuffle" to listOf("random"), "autoplay" to listOf("auto-play", "next"),
    )

    fun build(c: android.content.Context, xml: Map<String, Int>, titles: Map<String, String>): List<Item> {
        val out = ArrayList<Item>()
        parse(c, R.xml.preferences, null, "Settings", out)
        xml.forEach { (section, res) -> parse(c, res, section, titles[section] ?: section, out) }
        // One entry per key (a setting is never listed twice).
        return out.distinctBy { it.key }
    }

    private fun parse(c: android.content.Context, res: Int, section: String?, sectionTitle: String, out: MutableList<Item>) {
        val p: XmlResourceParser = try { c.resources.getXml(res) } catch (_: Exception) { return }
        val categories = ArrayList<String?>()
        try {
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    val tag = p.name.substringAfterLast('.')
                    if (tag == "PreferenceCategory") categories += str(c, p, "title")
                    else if (tag != "PreferenceScreen") {
                        val key = str(c, p, "key")
                        val title = str(c, p, "title")
                        if (!key.isNullOrBlank() && !title.isNullOrBlank()) {
                            val summary = str(c, p, "summary") ?: str(c, p, "dialogTitle")
                            val entries = arr(c, p, "entries")
                            val category = categories.lastOrNull { !it.isNullOrBlank() }
                            // Root items that open a category point at that category itself.
                            val isSec = section == null && key.startsWith("sec_")
                            val target = if (isSec) key.removePrefix("sec_") else section
                            val tTitle = if (isSec) title else sectionTitle
                            val hay = listOfNotNull(title, summary, category, tTitle, str(c, p, "dialogMessage"), key.replace('_', ' '))
                                .plus(entries).joinToString(" \u0000 ").lowercase()
                            out += Item(key, title, summary, if (isSec) null else category, target, tTitle, hay, isSec)
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG && p.name.substringAfterLast('.') == "PreferenceCategory") {
                    if (categories.isNotEmpty()) categories.removeAt(categories.lastIndex)
                }
                ev = p.next()
            }
        } catch (_: Exception) { } finally { p.close() }
    }

    /** Reads an attribute by local name (app: or android:), resolving @string references. */
    private fun str(c: android.content.Context, p: XmlResourceParser, name: String): String? {
        for (i in 0 until p.attributeCount) {
            if (p.getAttributeName(i) != name) continue
            val v = p.getAttributeValue(i) ?: return null
            if (v.startsWith("@")) {
                val id = p.getAttributeResourceValue(i, 0)
                return if (id != 0) try { c.getString(id) } catch (_: Exception) { null } else null
            }
            return v
        }
        return null
    }

    private fun arr(c: android.content.Context, p: XmlResourceParser, name: String): List<String> {
        for (i in 0 until p.attributeCount) {
            if (p.getAttributeName(i) != name) continue
            val id = p.getAttributeResourceValue(i, 0)
            return if (id != 0) try { c.resources.getTextArray(id).map { it.toString() } } catch (_: Exception) { emptyList() } else emptyList()
        }
        return emptyList()
    }

    /** Every word of the query must match (directly or through a synonym). Best matches first. */
    fun search(index: List<Item>, query: String): List<Item> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val q = query.trim().lowercase()
        return index.mapNotNull { item ->
            var score = 0
            for (w in words) {
                val direct = item.haystack.contains(w)
                val viaSyn = !direct && (SYNONYMS[w] ?: SYNONYMS[w.trimEnd('s')])?.any { item.haystack.contains(it) } == true
                if (!direct && !viaSyn) return@mapNotNull null
                score += if (direct) 2 else 1
            }
            val t = item.title.lowercase()
            score += when { t.startsWith(q) -> 30; t.contains(q) -> 20; (item.summary ?: "").lowercase().contains(q) -> 8; else -> 0 }
            if (item.isSection) score += 5 // a whole category named like the query
            item to score
        }.sortedByDescending { it.second }.map { it.first }.take(60)
    }
}

/**
 * v2.6 fix: settings screens used to be transparent, so during the switch animation both screens' text showed on top
 * of each other (and on Back the old screen was drawn under the new one). Each screen now has a solid background,
 * and the screen sliding away on Back is lifted above the one coming back (iOS-style pop).
 */
private fun prepareNavScreen(f: PreferenceFragmentCompat, view: View) {
    // v2.12: still opaque (for the nav animation), but with the window-aligned atmosphere instead of a flat colour,
    // and every group of rows sits in one large glass panel.
    Atmosphere.viewBackground(view)
    SettingsPanelDecoration.attach(f)
    f.listView?.let { l ->
        // v2.8: toggling a switch or a dependency used to cross-fade the whole row (DefaultItemAnimator change
        // animation), which read as a flicker. Rows now update in place; inserts/removals still animate.
        (l.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        l.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        l.clipToPadding = false
        l.setPadding(l.paddingLeft, l.paddingTop, l.paddingRight, (24 * l.resources.displayMetrics.density).toInt())
        VelaScroller.attach(l)
    }
}
private fun navAnimZ(view: View?, nextAnim: Int) { view?.translationZ = if (nextAnim == R.anim.nav_pop_exit) 0.5f else 0f }

/** v2.7: load the nav animation ourselves so the screen renders into a GPU layer while it moves/fades
 *  (alpha on a full RecyclerView otherwise redraws every row each frame = stutter), and honour reduced motion. */
private fun navAnim(f: androidx.fragment.app.Fragment, nextAnim: Int): android.view.animation.Animation? {
    val v = f.view
    navAnimZ(v, nextAnim)
    if (nextAnim == 0) return null
    val ctx = f.context ?: return null
    val scale = android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    if (scale == 0f) return android.view.animation.AlphaAnimation(1f, 1f).apply { duration = 0 }
    val a = android.view.animation.AnimationUtils.loadAnimation(ctx, nextAnim)
    a.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
        override fun onAnimationStart(an: android.view.animation.Animation) { v?.setLayerType(View.LAYER_TYPE_HARDWARE, null) }
        override fun onAnimationEnd(an: android.view.animation.Animation) { v?.post { v.setLayerType(View.LAYER_TYPE_NONE, null); v.translationZ = 0f } }
        override fun onAnimationRepeat(an: android.view.animation.Animation) {}
    })
    return a
}
