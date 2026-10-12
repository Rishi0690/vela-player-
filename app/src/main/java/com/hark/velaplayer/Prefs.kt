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

    // ---- remembered track preferences (each independent) ----
    fun rememberAudioLang(c: Context) = sp(c).getBoolean("remember_audio_lang", true)
    fun rememberSubLang(c: Context) = sp(c).getBoolean("remember_sub_lang", true)
    fun rememberSubSelection(c: Context) = sp(c).getBoolean("remember_sub_selection", true)
    fun rememberSubStyle(c: Context) = sp(c).getBoolean("remember_sub_appearance", true)

    /** Audio language to prefer: the last one picked (if remembered), else the fixed preference from Settings. */
    fun preferredAudioLang(c: Context): String? {
        val s = sp(c)
        val last = if (rememberAudioLang(c)) s.getString("last_audio_lang", null)?.takeIf { it.isNotBlank() } else null
        return last ?: s.getString("pref_audio_lang", "")?.trim()?.takeIf { it.isNotEmpty() }
    }
    fun saveAudioLang(c: Context, lang: String?) {
        if (rememberAudioLang(c) && !lang.isNullOrBlank() && lang != "und") sp(c).edit().putString("last_audio_lang", lang).apply()
    }

    fun preferredSubLang(c: Context): String? {
        val s = sp(c)
        val last = if (rememberSubLang(c)) s.getString("last_sub_lang", null)?.takeIf { it.isNotBlank() } else null
        return last ?: s.getString("pref_sub_lang", "")?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** "on", "off" or null (nothing remembered / remembering disabled). */
    fun rememberedSubState(c: Context): String? =
        if (rememberSubSelection(c)) sp(c).getString("last_sub_state", null) else null
    fun rememberedSubLabel(c: Context): String? =
        if (rememberSubSelection(c)) sp(c).getString("last_sub_label", null)?.takeIf { it.isNotBlank() } else null

    fun saveSubChoice(c: Context, on: Boolean, label: String?, lang: String?) {
        val e = sp(c).edit()
        if (rememberSubSelection(c)) {
            e.putString("last_sub_state", if (on) "on" else "off")
            if (on) e.putString("last_sub_label", label ?: "")
        }
        if (on && rememberSubLang(c) && !lang.isNullOrBlank() && lang != "und") e.putString("last_sub_lang", lang)
        e.apply()
    }

    fun subDelay(c: Context): Long = if (rememberSubStyle(c)) sp(c).getLong("last_sub_delay", 0L) else 0L
    fun saveSubDelay(c: Context, ms: Long) { if (rememberSubStyle(c)) sp(c).edit().putLong("last_sub_delay", ms).apply() }

    // ---- gesture sides: "left" or "right" ----
    fun brightnessSide(c: Context) = sp(c).getString("brightness_side", "left") ?: "left"
    fun volumeSide(c: Context) = sp(c).getString("volume_side", "right") ?: "right"

    // ---- home screen ----
    /** "folders" or "all" — the section the home screen should open on. */
    fun homeStartSection(c: Context): String {
        val s = sp(c)
        return when (s.getString("home_default", "folders")) {
            "all" -> "all"
            "last" -> s.getString("last_home_section", "folders") ?: "folders"
            else -> "folders"
        }
    }
    fun saveHomeSection(c: Context, section: String) = sp(c).edit().putString("last_home_section", section).apply()
    fun hideSystemFolders(c: Context) = sp(c).getBoolean("hide_system_folders", true)
    fun folderSort(c: Context) = sp(c).getString("folder_sort", "name") ?: "name"
    fun showWatchStats(c: Context) = sp(c).getBoolean("show_watch_stats", true)

    // ---- picture-in-picture ----
    fun pipEnabled(c: Context) = sp(c).getBoolean("pip_enabled", true)
    /** "auto" (prev/next when there is a queue, else seek), "seek" or "track". */
    fun pipControls(c: Context) = sp(c).getString("pip_controls", "auto") ?: "auto"

    // ---- Focus Mode ----
    fun focusFeature(c: Context) = sp(c).getBoolean("focus_feature", true)
    fun focusAutoStart(c: Context) = sp(c).getBoolean("focus_auto_start", false)
    fun focusShowTimer(c: Context) = sp(c).getBoolean("focus_show_timer", true)
    fun focusMinimal(c: Context) = sp(c).getBoolean("focus_minimal", true)
    fun focusSummary(c: Context) = sp(c).getBoolean("focus_summary", true)
    fun focusGoalMs(c: Context): Long = (sp(c).getString("focus_goal_min", "0")?.toLongOrNull() ?: 0L) * 60_000L
    /** 0 when break reminders are off. */
    fun focusBreakMs(c: Context): Long =
        if (!sp(c).getBoolean("focus_breaks", false)) 0L else (sp(c).getString("focus_break_min", "45")?.toLongOrNull() ?: 45L) * 60_000L

    // ---- library views ----
    /** "grid" or "list" for one section (all, folder, playlist, favorites, folders, playlists); falls back to the default view. */
    fun viewMode(c: Context, section: String): String =
        sp(c).getString("view_$section", null) ?: sp(c).getString("default_view", "list") ?: "list"
    fun setViewMode(c: Context, section: String, mode: String) = sp(c).edit().putString("view_$section", mode).apply()
    /** 0 = automatic (by screen width). */
    fun gridColumns(c: Context): Int = sp(c).getString("grid_columns", "0")?.toIntOrNull() ?: 0
    fun smartThumbs(c: Context) = sp(c).getBoolean("smart_thumbnails", true)
    /** "options" (show the menu) or "select" (start selecting). */
    fun longPressAction(c: Context) = sp(c).getString("long_press_action", "options") ?: "options"
    /** Where new videos go in a playlist: "end" or "start". */
    fun playlistAddAt(c: Context) = sp(c).getString("playlist_add_at", "end") ?: "end"
    fun playlistCovers(c: Context) = sp(c).getBoolean("playlist_collage", true)

    fun applyTheme(c: Context) {
        val mode = when (sp(c).getString("theme", "dark")) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "system" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    // ---- v2.1 library ----
    /** "last" (reopen exactly where the user left off — the default), "folders" or "all". */
    fun homeOpenMode(c: Context) = sp(c).getString("home_default", "last") ?: "last"
    /** Hide MPEG transport-stream (.ts) files from the library. The player's navigator reads the same "hide_ts" key. */
    fun hideTs(c: Context) = sp(c).getBoolean("hide_ts", true)
    /** True for a file whose extension is exactly ".ts" (case-insensitive). ".mts" / ".m2ts" are NOT matched. */
    fun isTsName(name: String?): Boolean = name != null && name.trimEnd().endsWith(".ts", ignoreCase = true)

    /** Daily focus limit/goal in minutes (0 = no limit). Same key the Focus settings and the player use. */
    fun focusGoalMin(c: Context): Long = sp(c).getString("focus_goal_min", "0")?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
    fun setFocusGoalMin(c: Context, minutes: Long) = sp(c).edit().putString("focus_goal_min", minutes.coerceAtLeast(0L).toString()).apply()

    /** Focus Mode switched on by the user; survives closing the app (separate from the session timer). */
    fun focusModeOn(c: Context) = sp(c).getBoolean("focus_mode_on", false)
    fun setFocusModeOn(c: Context, on: Boolean) = sp(c).edit().putBoolean("focus_mode_on", on).apply()
}
