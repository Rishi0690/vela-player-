package com.hark.velaplayer

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * v2.14 home-screen widgets: what exists ([WidgetKind]), how each placed widget is set up ([WidgetConfig], saved per
 * appWidgetId) and the colours it is drawn with ([WidgetPalette], resolved from the app's ONE theme source,
 * [ThemeManager], every time a widget is rendered — there is no separate widget theme).
 */
enum class WidgetKind(
    val key: String, val title: String, val blurb: String,
    /** Size chips shown in the gallery: label to (widthDp, heightDp) of the in-app preview. */
    val sizes: List<Triple<String, Int, Int>>,
    val icon: Int,
) {
    NOW_PLAYING("now_playing", "Now Playing",
        "Artwork, title, live time and progress with real previous / play / next controls. Resumes your last video when nothing is playing.",
        listOf(Triple("Compact", 300, 72), Triple("Square", 160, 160), Triple("Medium", 300, 150), Triple("Large", 300, 250)), R.drawable.wgi_play),
    CONTINUE("continue", "Continue Watching",
        "Your unfinished videos with thumbnails and real progress. Tap one to pick up exactly where you left off.",
        listOf(Triple("Medium", 300, 160), Triple("Large", 300, 300)), R.drawable.wgi_continue),
    QUICK("quick", "Quick Access",
        "Four glass shortcuts you choose: videos, folders, playlists, favorites, continue watching, resume or the Vault.",
        listOf(Triple("Row", 300, 76), Triple("Grid", 160, 160), Triple("Wide", 300, 150)), R.drawable.wgi_tune),
    LIBRARY("library", "Recently Added",
        "The newest videos on your phone as a thumbnail grid. Two columns when compact, more when you make it bigger.",
        listOf(Triple("Compact", 160, 200), Triple("Large", 300, 300)), R.drawable.wgi_library),
    COLLECTION("collection", "Playlist & Favorites",
        "One playlist or your favorites, with Play all and Shuffle. Choose which one when you add it.",
        listOf(Triple("Medium", 300, 160), Triple("Large", 300, 300)), R.drawable.wgi_star);

    fun provider(): Class<out AppWidgetProvider> = when (this) {
        NOW_PLAYING -> NowPlayingWidget::class.java
        CONTINUE -> ContinueWidget::class.java
        QUICK -> QuickAccessWidget::class.java
        LIBRARY -> LibraryWidget::class.java
        COLLECTION -> CollectionWidget::class.java
    }
    fun component(c: Context) = ComponentName(c, provider())
    fun ids(c: Context): IntArray = try { AppWidgetManager.getInstance(c).getAppWidgetIds(component(c)) } catch (_: Exception) { IntArray(0) }
    val isList get() = this == CONTINUE || this == LIBRARY || this == COLLECTION

    companion object {
        fun of(key: String?) = values().firstOrNull { it.key == key }
        fun forProvider(cls: String?) = values().firstOrNull { it.provider().name == cls }
    }
}

/** Shortcut targets of the Quick Access widget. Vault is opt-in and always opens through the vault's own lock. */
enum class Shortcut(val key: String, val label: String, val icon: Int) {
    RESUME("resume", "Resume", R.drawable.wgi_resume),
    CONTINUE("continue", "Continue", R.drawable.wgi_continue),
    ALL("all", "Videos", R.drawable.wgi_library),
    FOLDERS("folders", "Folders", R.drawable.wgi_folder),
    FAVORITES("favorites", "Favorites", R.drawable.wgi_star),
    PLAYLISTS("playlists", "Playlists", R.drawable.wgi_playlist),
    VAULT("vault", "Vault", R.drawable.wgi_lock);
    companion object { fun of(k: String) = values().firstOrNull { it.key == k } }
}

data class WidgetConfig(
    /** true (default) = colours follow the app theme, including custom themes; false = [accent] replaces the accent. */
    val followTheme: Boolean = true,
    val accent: Int = 0,
    /** 0 Clear, 1 Balanced, 2 Frosted (default), 3 Solid. */
    val opacity: Int = 2,
    /** r1 = match launcher, r2 = extra round, r3 = crisp (generated drawable sets). */
    val corners: String = "r1",
    val showProgress: Boolean = true,
    val showSkip: Boolean = true,
    val shortcuts: List<String> = listOf("continue", "all", "folders", "playlists"),
    /** Playlist & Favorites widget: "favorites" or "playlist:<name>". */
    val source: String = "favorites",
) {
    val opacityFactor get() = when (opacity) { 0 -> 0.55f; 1 -> 0.74f; 3 -> 1f; else -> 0.88f }

    fun toJson(): String = JSONObject().put("follow", followTheme).put("accent", accent).put("opacity", opacity)
        .put("corners", corners).put("progress", showProgress).put("skip", showSkip)
        .put("shortcuts", JSONArray(shortcuts)).put("source", source).toString()

    companion object {
        val OPACITY_LABELS = listOf("Clear", "Balanced", "Frosted", "Solid")
        val CORNER_KEYS = listOf("r1", "r2", "r3")
        val CORNER_LABELS = listOf("Launcher", "Round", "Crisp")
        fun fromJson(s: String?): WidgetConfig {
            if (s.isNullOrBlank()) return WidgetConfig()
            return try {
                val o = JSONObject(s)
                val sc = o.optJSONArray("shortcuts")?.let { a -> (0 until a.length()).map { a.getString(it) } }?.filter { Shortcut.of(it) != null }
                WidgetConfig(o.optBoolean("follow", true), o.optInt("accent", 0), o.optInt("opacity", 2).coerceIn(0, 3),
                    o.optString("corners", "r1").takeIf { it in CORNER_KEYS } ?: "r1",
                    o.optBoolean("progress", true), o.optBoolean("skip", true),
                    if (sc.isNullOrEmpty()) WidgetConfig().shortcuts else sc.take(4), o.optString("source", "favorites"))
            } catch (_: Exception) { WidgetConfig() }
        }
    }
}

/** Per-widget settings, kept in their own prefs file so "Reset all settings" never touches placed widgets. */
object WidgetStore {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("vela_widgets", Context.MODE_PRIVATE)
    fun get(c: Context, id: Int): WidgetConfig = WidgetConfig.fromJson(sp(c).getString("cfg:$id", null))
    fun has(c: Context, id: Int) = sp(c).contains("cfg:$id")
    fun put(c: Context, id: Int, cfg: WidgetConfig) = sp(c).edit().putString("cfg:$id", cfg.toJson()).apply()
    fun remove(c: Context, ids: IntArray) { val e = sp(c).edit(); ids.forEach { e.remove("cfg:$it") }; e.apply() }
    /** Restored from a backup on a new launcher: the ids change, the settings move with them. */
    fun move(c: Context, old: IntArray, new: IntArray) {
        val s = sp(c); val e = s.edit()
        old.forEachIndexed { i, o -> s.getString("cfg:$o", null)?.let { v -> new.getOrNull(i)?.let { e.putString("cfg:$it", v) }; e.remove("cfg:$o") } }
        e.apply()
    }
    /** What the user set up in the gallery before pressing "Add to home screen"; adopted by the new widget id. */
    fun putPending(c: Context, kind: WidgetKind, cfg: WidgetConfig) = sp(c).edit().putString("pending:${kind.key}", cfg.toJson()).apply()
    fun takePending(c: Context, kind: WidgetKind): WidgetConfig? {
        val s = sp(c).getString("pending:${kind.key}", null) ?: return null
        sp(c).edit().remove("pending:${kind.key}").apply()
        return WidgetConfig.fromJson(s)
    }
    fun peekPending(c: Context, kind: WidgetKind): WidgetConfig? = sp(c).getString("pending:${kind.key}", null)?.let { WidgetConfig.fromJson(it) }
}

/**
 * Widget colours, resolved to plain ints because launcher-inflated RemoteViews cannot read the app's theme
 * attributes. Everything comes from [ThemeManager] (presets AND custom colours) for an explicit light/dark state.
 */
data class WidgetPalette(
    val dark: Boolean,
    val top: Int, val bottom: Int, val glow: Int, val glow2: Int, val glowAlpha: Int,
    val sheenAlpha: Int, val sheen: Int, val stroke: Int, val strokeAlpha: Int,
    val text: Int, val textDim: Int, val accent: Int, val onAccent: Int,
    val chip: Int, val chipAlpha: Int, val track: Int,
) {
    companion object {
        /** The app's theme mode: "dark" (default), "light" or "system". */
        fun mode(c: Context) = Prefs.sp(c).getString("theme", "dark") ?: "dark"
        fun systemNight(): Boolean =
            (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        /** Light/dark the app is drawn in right now (same rule as Prefs.applyTheme). */
        fun nightNow(c: Context): Boolean = when (mode(c)) { "light" -> false; "system" -> systemNight(); else -> true }
        /** Android 12+ can switch widget colours with the system's dark mode on its own, even while Vela isn't running. */
        fun followsSystem(c: Context) = mode(c) == "system" && Build.VERSION.SDK_INT >= 31

        fun resolve(c: Context, night: Boolean, cfg: WidgetConfig): WidgetPalette {
            val conf = Configuration(c.resources.configuration)
            conf.uiMode = (conf.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            val cc = c.createConfigurationContext(conf)
            val sel = ThemeManager.selection(c)
            val p = try {
                if (sel.preset != null) ThemeManager.palette(cc, sel.preset)
                else ThemeManager.customPalette(cc, sel.seed, sel.secondary, sel.base, night)
            } catch (_: Exception) { null }
            return from(p, night, if (cfg.followTheme || cfg.accent == 0) null else cfg.accent)
        }

        private fun opaque(c: Int) = c or 0xFF000000.toInt()
        private fun lum(c: Int): Double {
            fun ch(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
            return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
        }
        fun contrast(a: Int, b: Int): Double { val la = lum(a); val lb = lum(b); return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05) }
        fun onColor(bg: Int) = if (contrast(bg, Color.WHITE) >= contrast(bg, 0xFF0B0D16.toInt())) Color.WHITE else 0xFF0B0D16.toInt()

        fun from(p: ThemeManager.Palette?, night: Boolean, accentOverride: Int?): WidgetPalette {
            val fallbackAccent = 0xFF8FA8FF.toInt()
            val top = opaque(p?.atmoTop ?: if (night) 0xFF03040A.toInt() else 0xFFF5F7FB.toInt())
            val bottom0 = opaque(p?.atmoBottom ?: if (night) 0xFF0C0F1E.toInt() else 0xFFDBE2EE.toInt())
            var accent = opaque(accentOverride ?: p?.primary ?: fallbackAccent)
            val text = opaque(p?.text ?: if (night) 0xFFF2F4FA.toInt() else 0xFF141826.toInt())
            val dim = opaque(p?.textDim ?: if (night) 0xFFA3A9BC.toInt() else 0xFF5D6378.toInt())
            // A custom accent must stay readable on this glass: nudge it towards white/black until it reaches 3:1.
            if (accentOverride != null) {
                var i = 0
                while (contrast(accent, top) < 3.0 && i < 8) { accent = Atmosphere.mix(accent, if (night) Color.WHITE else Color.BLACK, 0.15f); i++ }
            }
            val glow = if (accentOverride != null) accent else opaque(p?.glowA ?: accent)
            val glow2 = if (accentOverride != null) Atmosphere.mix(accent, top, 0.35f) else opaque(p?.glowB ?: accent)
            val bottom = Atmosphere.mix(bottom0, glow, if (night) 0.10f else 0.06f)
            return WidgetPalette(
                dark = night, top = top, bottom = bottom, glow = glow, glow2 = glow2, glowAlpha = if (night) 0x5A else 0x48,
                sheenAlpha = if (night) 0x1F else 0xC0, sheen = Color.WHITE,
                stroke = if (night) Color.WHITE else 0xFF1B2133.toInt(), strokeAlpha = if (night) 0x2E else 0x1C,
                text = text, textDim = dim, accent = accent,
                onAccent = if (accentOverride != null || p == null) onColor(accent) else opaque(p.onPrimary).let { if (contrast(it, accent) < 3.0) onColor(accent) else it },
                chip = if (night) Color.WHITE else Color.WHITE, chipAlpha = if (night) 0x14 else 0xA8,
                track = Atmosphere.alpha(text, if (night) 0x33 else 0x26),
            )
        }
    }
}
