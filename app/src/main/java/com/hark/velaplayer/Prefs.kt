package com.hark.velaplayer

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager

/** Typed access to user settings (all persisted in default SharedPreferences). */
object Prefs {
    fun sp(c: Context): SharedPreferences = PreferenceManager.getDefaultSharedPreferences(c)

    fun defaultAspect(c: Context): AspectMode {
        val s = sp(c)
        val def = s.getString("default_aspect", "FIT")
        val key = if (s.getBoolean("remember_aspect", true)) s.getString("last_aspect", def) else def
        return AspectMode.from(key)
    }
    fun saveAspect(c: Context, m: AspectMode) = sp(c).edit().putString("last_aspect", m.name).apply()

    fun speed(c: Context): Float {
        val s = sp(c)
        val def = s.getString("default_speed", "1.0")?.toFloatOrNull() ?: 1f
        return if (s.getBoolean("remember_speed", true)) s.getFloat("last_speed", def) else def
    }
    fun saveSpeed(c: Context, v: Float) = sp(c).edit().putFloat("last_speed", v).apply()

    fun seekInterval(c: Context): Long = (sp(c).getString("seek_interval", "10")?.toLongOrNull() ?: 10L) * 1000L
    fun resume(c: Context) = sp(c).getBoolean("resume", true)
    fun autoplayNext(c: Context) = sp(c).getBoolean("autoplay_next", true)
    fun orientation(c: Context) = sp(c).getString("default_orientation", "auto") ?: "auto"
    fun keepScreenOn(c: Context) = sp(c).getBoolean("keep_screen_on", true)
    fun gesture(c: Context, key: String) = sp(c).getBoolean(key, true)
    fun backgroundAudio(c: Context) = sp(c).getBoolean("background_audio", false)
    fun autoPip(c: Context) = sp(c).getBoolean("auto_pip", false)

    fun applyTheme(c: Context) {
        val mode = when (sp(c).getString("theme", "dark")) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "system" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
