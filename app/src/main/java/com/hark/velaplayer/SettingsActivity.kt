package com.hark.velaplayer

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hark.velaplayer.databinding.ActivitySettingsBinding

/** Settings home with six categories; each opens its own screen. */
class SettingsActivity : ThemedActivity() {
    companion object {
        const val EXTRA_SECTION = "section"
        private val TITLES = mapOf(
            "appearance" to "Appearance & personalization", "library" to "Library & display", "gestures" to "Gestures",
            "playback" to "Playback", "playlists" to "Playlists & selection", "focus" to "Focus Mode"
        )
        private val XML = mapOf(
            "appearance" to R.xml.prefs_appearance, "library" to R.xml.prefs_library, "gestures" to R.xml.prefs_gestures,
            "playback" to R.xml.prefs_playback, "playlists" to R.xml.prefs_playlists, "focus" to R.xml.prefs_focus
        )
    }

    private lateinit var b: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        supportFragmentManager.addOnBackStackChangedListener { updateTitle() }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.container, RootFragment()).commit()
            intent.getStringExtra(EXTRA_SECTION)?.takeIf { XML.containsKey(it) }?.let { open(it, addToBack = false) }
        }
        updateTitle()
    }

    fun open(section: String, addToBack: Boolean = true) {
        val t = supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.act_open_enter, R.anim.act_open_exit, R.anim.act_close_enter, R.anim.act_close_exit)
            .replace(R.id.container, SectionFragment().apply { arguments = Bundle().apply { putString(EXTRA_SECTION, section) } }, section)
        if (addToBack) t.addToBackStack(section)
        t.commit()
        b.toolbar.title = TITLES[section]
    }

    private fun updateTitle() {
        val f = supportFragmentManager.findFragmentById(R.id.container)
        b.toolbar.title = (f as? SectionFragment)?.arguments?.getString(EXTRA_SECTION)?.let { TITLES[it] } ?: "Settings"
    }

    class RootFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            listOf("appearance", "library", "gestures", "playback", "playlists", "focus").forEach { s ->
                findPreference<Preference>("sec_$s")?.setOnPreferenceClickListener { (activity as SettingsActivity).open(s); true }
            }
            findPreference<Preference>("reset_all")?.setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Reset all settings?")
                    .setMessage("Playback, gesture, subtitle, layout, Focus Mode and theme settings go back to defaults. Playlists, favorites, resume points, saved themes and focus history are kept.")
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

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val section = arguments?.getString(EXTRA_SECTION) ?: "playback"
            setPreferencesFromResource(XML[section] ?: R.xml.prefs_playback, rootKey)
            bind()
        }

        override fun onResume() {
            super.onResume()
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
            findPreference<Preference>("focus_dashboard")?.setOnPreferenceClickListener { startActivity(Intent(ctx, FocusActivity::class.java)); true }
            findPreference<Preference>("focus_clear")?.setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(ctx).setTitle("Clear focus history?")
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
                MaterialAlertDialogBuilder(ctx).setTitle("Allow Do Not Disturb access?")
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
