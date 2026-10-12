package com.hark.velaplayer

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hark.velaplayer.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.setNavigationOnClickListener { finish() }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.container, SettingsFragment()).commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            bind()
        }

        private fun bind() {
            findPreference<Preference>("theme")?.setOnPreferenceChangeListener { _, _ ->
                view?.post { Prefs.applyTheme(requireContext()) }
                true
            }
            findPreference<Preference>("reset_all")?.setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Reset all settings?")
                    .setMessage("Playback, gesture, subtitle and theme settings go back to defaults. Playlists, favorites and resume points are kept.")
                    .setPositiveButton("Reset") { _, _ ->
                        PreferenceManager.getDefaultSharedPreferences(requireContext()).edit().clear().commit()
                        preferenceScreen = null
                        setPreferencesFromResource(R.xml.preferences, null)
                        bind()
                        Prefs.applyTheme(requireContext())
                    }
                    .setNegativeButton("Cancel", null).show()
                true
            }
        }
    }
}
