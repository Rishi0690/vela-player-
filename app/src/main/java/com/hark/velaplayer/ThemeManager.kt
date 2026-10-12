package com.hark.velaplayer

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Build
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import org.json.JSONArray
import org.json.JSONObject

/**
 * One place for the app's colors. Every screen asks this object for its accent, so a change in the
 * Theme editor reaches buttons, switches, sliders, chips, progress bars and highlights everywhere.
 *
 * - Presets: hand-tuned Material 3 palettes (light + dark) generated from each seed color.
 * - Custom colors: Android 12+ builds a full Material 3 palette from the exact color picked.
 *   Android 11 and older can't build palettes at runtime, so the closest preset palette is used.
 */
object ThemeManager {
    data class Preset(val key: String, val label: String, val seed: Int, val style: Int, val playerStyle: Int)

    val presets = listOf(
        Preset("violet", "Vela Violet", 0xFF8B6CFF.toInt(), R.style.Accent_Violet, R.style.AccentDark_Violet),
        Preset("ocean", "Ocean", 0xFF2F7DF6.toInt(), R.style.Accent_Ocean, R.style.AccentDark_Ocean),
        Preset("teal", "Teal", 0xFF00A896.toInt(), R.style.Accent_Teal, R.style.AccentDark_Teal),
        Preset("emerald", "Emerald", 0xFF2EAD5B.toInt(), R.style.Accent_Emerald, R.style.AccentDark_Emerald),
        Preset("sunset", "Sunset", 0xFFFF7A2F.toInt(), R.style.Accent_Sunset, R.style.AccentDark_Sunset),
        Preset("crimson", "Crimson", 0xFFE53950.toInt(), R.style.Accent_Crimson, R.style.AccentDark_Crimson),
        Preset("rose", "Rose", 0xFFF0609E.toInt(), R.style.Accent_Rose, R.style.AccentDark_Rose),
        Preset("amber", "Amber", 0xFFF2B705.toInt(), R.style.Accent_Amber, R.style.AccentDark_Amber),
        Preset("slate", "Slate", 0xFF6F7FA0.toInt(), R.style.Accent_Slate, R.style.AccentDark_Slate),
    )

    const val DEFAULT_SEED = 0xFF8B6CFF.toInt()
    private const val KEY_SEED = "accent_seed"
    private const val KEY_CUSTOM = "custom_themes"
    private const val KEY_VERSION = "theme_version"

    /** True when this phone can build a palette from any color (Android 12+). */
    val exactCustomColors: Boolean get() = Build.VERSION.SDK_INT >= 31 && DynamicColors.isDynamicColorAvailable()

    fun seed(c: Context): Int = Prefs.sp(c).getInt(KEY_SEED, DEFAULT_SEED)
    fun version(c: Context): Int = Prefs.sp(c).getInt(KEY_VERSION, 0)

    fun setSeed(c: Context, seed: Int) {
        val s = Prefs.sp(c)
        s.edit().putInt(KEY_SEED, seed or 0xFF000000.toInt()).putInt(KEY_VERSION, version(c) + 1).apply()
    }
    /** Bumps the version so open screens recreate themselves (used when light/dark mode changes too). */
    fun touch(c: Context) = Prefs.sp(c).edit().putInt(KEY_VERSION, version(c) + 1).apply()

    fun presetFor(seed: Int): Preset? = presets.firstOrNull { (it.seed or 0xFF000000.toInt()) == (seed or 0xFF000000.toInt()) }

    /** Closest preset by hue, saturation and brightness (used on Android 11 and older for custom colors). */
    fun nearestPreset(seed: Int): Preset {
        val a = FloatArray(3); Color.colorToHSV(seed, a)
        return presets.minByOrNull { p ->
            val b = FloatArray(3); Color.colorToHSV(p.seed, b)
            val dh = Math.abs(a[0] - b[0]).let { if (it > 180) 360 - it else it } / 180f
            // Grey-ish colors match Slate; hue matters less when saturation is low.
            val satW = (a[1] + b[1]) / 2f
            dh * dh * 4f * satW + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]) * 0.5f
        } ?: presets[0]
    }

    /** Applies the accent to an activity. Call in onCreate before setContentView. */
    fun apply(activity: Activity, player: Boolean = false) {
        val seed = seed(activity)
        val exact = presetFor(seed)
        val base = exact ?: nearestPreset(seed)
        activity.theme.applyStyle(if (player) base.playerStyle else base.style, true)
        if (exact == null && exactCustomColors) {
            DynamicColors.applyToActivityIfAvailable(activity, DynamicColorsOptions.Builder().setContentBasedSource(seed).build())
        }
    }

    // ---- saved custom themes ----
    data class Custom(val name: String, val seed: Int)

    fun customThemes(c: Context): List<Custom> = try {
        val a = JSONArray(Prefs.sp(c).getString(KEY_CUSTOM, "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it); Custom(o.getString("name"), o.getInt("seed")) }
    } catch (_: Exception) { emptyList() }

    fun saveCustom(c: Context, name: String, seed: Int) {
        val list = customThemes(c).filter { !it.name.equals(name, true) && it.seed != seed }.toMutableList()
        list.add(0, Custom(name, seed or 0xFF000000.toInt()))
        writeCustom(c, list.take(12))
    }
    fun deleteCustom(c: Context, seed: Int) = writeCustom(c, customThemes(c).filter { it.seed != seed })
    private fun writeCustom(c: Context, l: List<Custom>) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("name", it.name).put("seed", it.seed)) }
        Prefs.sp(c).edit().putString(KEY_CUSTOM, a.toString()).apply()
    }

    fun hex(color: Int) = String.format("#%06X", color and 0xFFFFFF)
    fun parseHex(s: String): Int? {
        val t = s.trim().removePrefix("#")
        val full = when (t.length) { 3 -> t.map { "$it$it" }.joinToString(""); 6 -> t; else -> return null }
        return full.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
    }

    /** Reads a theme color attribute (e.g. colorPrimary) from any themed context. */
    fun attr(c: Context, attr: Int): Int {
        val tv = android.util.TypedValue()
        return if (c.theme.resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) androidx.core.content.ContextCompat.getColor(c, tv.resourceId) else tv.data
        } else DEFAULT_SEED
    }
    fun primary(c: Context) = attr(c, androidx.appcompat.R.attr.colorPrimary)
}

/** Base for screens that follow the theme: applies it, and recreates when it changes elsewhere. */
abstract class ThemedActivity : androidx.appcompat.app.AppCompatActivity() {
    private var themeVersion = -1
    protected open val isPlayerTheme = false
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        ThemeManager.apply(this, isPlayerTheme)
        themeVersion = ThemeManager.version(this)
        super.onCreate(savedInstanceState)
    }
    override fun onResume() {
        super.onResume()
        if (themeVersion != ThemeManager.version(this)) { themeVersion = ThemeManager.version(this); recreate() }
    }
}
