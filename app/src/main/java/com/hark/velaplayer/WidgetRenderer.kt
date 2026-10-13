package com.hark.velaplayer

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews

/**
 * v2.14: builds every widget's RemoteViews. The same code renders the real home-screen widget and the in-app
 * gallery/detail previews ([Spec.preview] = true: lists are filled inline because there is no adapter service in the app).
 *
 * Only RemoteViews-safe classes are used in the layouts (FrameLayout, LinearLayout, ImageView, TextView, Chronometer,
 * ListView, GridView). Colours are resolved ints from [WidgetPalette]; on Android 12+ with the app in "System" mode
 * both light and dark values are sent (setColorInt) so the launcher switches them with the system, even when Vela is
 * not running.
 */
object WidgetRenderer {
    class Spec(
        val c: Context, val id: Int, val kind: WidgetKind, val cfg: WidgetConfig,
        val pal: WidgetPalette, val day: WidgetPalette?, val night: WidgetPalette?,
        val wDp: Int, val hDp: Int, val preview: Boolean,
    ) {
        val dual get() = day != null && night != null && Build.VERSION.SDK_INT >= 31
        val density get() = WidgetArt.density(c)
        fun px(dp: Float) = (dp * density).toInt()
    }

    /** Spec for a placed widget: size from the launcher's options, palette from the theme (both modes when needed). */
    fun spec(c: Context, kind: WidgetKind, id: Int, cfg: WidgetConfig = WidgetStore.get(c, id)): Spec {
        val opts = try { AppWidgetManager.getInstance(c).getAppWidgetOptions(id) } catch (_: Exception) { null }
        val def = kind.sizes.first()
        // Portrait: min width x max height (the usual launcher reading); fall back to the first gallery size.
        val w = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)?.takeIf { it > 0 } ?: def.second
        val h = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)?.takeIf { it > 0 }
            ?: opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)?.takeIf { it > 0 } ?: def.third
        val night = WidgetPalette.nightNow(c)
        val pal = WidgetPalette.resolve(c, night, cfg)
        return if (WidgetPalette.followsSystem(c)) {
            val other = WidgetPalette.resolve(c, !night, cfg)
            Spec(c, id, kind, cfg, pal, if (night) other else pal, if (night) pal else other, w, h, false)
        } else Spec(c, id, kind, cfg, pal, null, null, w, h, false)
    }

    /** Spec for an in-app preview, drawn in the mode the app is showing right now. */
    fun previewSpec(c: Context, kind: WidgetKind, cfg: WidgetConfig, wDp: Int, hDp: Int, night: Boolean) =
        Spec(c, AppWidgetManager.INVALID_APPWIDGET_ID, kind, cfg, WidgetPalette.resolve(c, night, cfg), null, null, wDp, hDp, true)

    // ------------------------------------------------------------------ colour helpers

    private fun RemoteViews.color(s: Spec, id: Int, method: String, pick: (WidgetPalette) -> Int) {
        if (s.dual && Build.VERSION.SDK_INT >= 31) setColorInt(id, method, pick(s.day!!), pick(s.night!!))
        else setInt(id, method, pick(s.pal))
    }
    private fun RemoteViews.tint(s: Spec, id: Int, alpha: Int = 255, pick: (WidgetPalette) -> Int) {
        color(s, id, "setColorFilter", pick)
        setInt(id, "setImageAlpha", alpha.coerceIn(0, 255))
    }
    private fun RemoteViews.text(s: Spec, id: Int, pick: (WidgetPalette) -> Int) = color(s, id, "setTextColor", pick)

    private val FILL = mapOf("r1" to R.drawable.wg_fill_r1, "r2" to R.drawable.wg_fill_r2, "r3" to R.drawable.wg_fill_r3)
    private val WASH = mapOf("r1" to R.drawable.wg_wash_r1, "r2" to R.drawable.wg_wash_r2, "r3" to R.drawable.wg_wash_r3)
    private val GLOW = mapOf("r1" to R.drawable.wg_glow_r1, "r2" to R.drawable.wg_glow_r2, "r3" to R.drawable.wg_glow_r3)
    private val GLOW2 = mapOf("r1" to R.drawable.wg_glow2_r1, "r2" to R.drawable.wg_glow2_r2, "r3" to R.drawable.wg_glow2_r3)
    private val SHEEN = mapOf("r1" to R.drawable.wg_sheen_r1, "r2" to R.drawable.wg_sheen_r2, "r3" to R.drawable.wg_sheen_r3)
    private val STROKE = mapOf("r1" to R.drawable.wg_stroke_r1, "r2" to R.drawable.wg_stroke_r2, "r3" to R.drawable.wg_stroke_r3)

    /** Outer corner radius in px for the chosen style (Android 12+ "Launcher" = the launcher's own widget radius). */
    fun outerRadiusPx(s: Spec): Float {
        val dp = when (s.cfg.corners) { "r2" -> 34f; "r3" -> 12f; else -> 24f }
        if (s.cfg.corners == "r1" && Build.VERSION.SDK_INT >= 31) {
            try { return s.c.resources.getDimension(android.R.dimen.system_app_widget_background_radius) * s.density / s.c.resources.displayMetrics.density } catch (_: Exception) { }
        }
        return dp * s.density
    }
    private fun innerRadiusPx(s: Spec, padDp: Float) = (outerRadiusPx(s) - padDp * s.density).coerceAtLeast(7f * s.density)

    /** The glass: fill + bottom wash in the theme's atmosphere, two light pools, sheen and hairline. */
    private fun glass(rv: RemoteViews, s: Spec) {
        val k = s.cfg.corners
        rv.setImageViewResource(R.id.wgFill, FILL[k]!!); rv.setImageViewResource(R.id.wgWash, WASH[k]!!)
        rv.setImageViewResource(R.id.wgGlow, GLOW[k]!!); rv.setImageViewResource(R.id.wgGlow2, GLOW2[k]!!)
        rv.setImageViewResource(R.id.wgSheen, SHEEN[k]!!); rv.setImageViewResource(R.id.wgStroke, STROKE[k]!!)
        val o = s.cfg.opacityFactor
        rv.tint(s, R.id.wgFill, (255 * o).toInt()) { it.top }
        rv.tint(s, R.id.wgWash, (255 * o).toInt()) { it.bottom }
        rv.tint(s, R.id.wgGlow, (s.pal.glowAlpha * (0.55f + 0.45f * o)).toInt()) { it.glow }
        rv.tint(s, R.id.wgGlow2, (s.pal.glowAlpha * 0.75f * (0.55f + 0.45f * o)).toInt()) { it.glow2 }
        rv.tint(s, R.id.wgSheen, s.pal.sheenAlpha) { it.sheen }
        rv.tint(s, R.id.wgStroke, s.pal.strokeAlpha) { it.stroke }
    }

    // ------------------------------------------------------------------ intents

    private fun flags(mutable: Boolean = false) = PendingIntent.FLAG_UPDATE_CURRENT or
        (if (mutable && Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)

    /** Opens [WidgetLaunchActivity] (a no-UI trampoline that builds the real queue / destination at tap time). */
    fun launch(c: Context, action: String, widgetId: Int, slot: String = "", extras: Intent.() -> Unit = {}): PendingIntent {
        val i = Intent(c, WidgetLaunchActivity::class.java).setAction(action)
            .setData(Uri.parse("vela-widget://launch/$action/$widgetId/${Uri.encode(slot)}"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        i.extras()
        return PendingIntent.getActivity(c, 0, i, flags())
    }

    /** Media control broadcast to [WidgetActionReceiver] (the real player via the player's own control path). */
    fun control(c: Context, action: String, widgetId: Int): PendingIntent {
        val i = Intent(c, WidgetActionReceiver::class.java).setAction(action)
            .setData(Uri.parse("vela-widget://control/$action/$widgetId"))
        return PendingIntent.getBroadcast(c, 0, i, flags())
    }

    // ------------------------------------------------------------------ build

    fun build(s: Spec, now: NowState? = null): RemoteViews = when (s.kind) {
        WidgetKind.NOW_PLAYING -> nowPlaying(s, now ?: NowState(NowState.EMPTY, null, "Nothing playing", 0, 0, false, false, false, false))
        WidgetKind.QUICK -> quick(s)
        WidgetKind.CONTINUE, WidgetKind.COLLECTION -> list(s, R.layout.wg_list)
        WidgetKind.LIBRARY -> list(s, R.layout.wg_grid)
    }

    // ---- Now Playing (merges the Playback controls widget: one widget, four sizes) ----

    enum class NpSize { BAR, SQUARE, MEDIUM, LARGE }
    fun npSize(wDp: Int, hDp: Int) = when {
        hDp < 100 -> NpSize.BAR
        wDp < 200 -> NpSize.SQUARE
        hDp < 200 -> NpSize.MEDIUM
        else -> NpSize.LARGE
    }

    private fun nowPlaying(s: Spec, st: NowState): RemoteViews {
        val c = s.c
        val size = npSize(s.wDp, s.hDp)
        val rv = RemoteViews(c.packageName, when (size) {
            NpSize.BAR -> R.layout.wg_np_bar; NpSize.SQUARE -> R.layout.wg_np_square
            NpSize.MEDIUM -> R.layout.wg_np_medium; NpSize.LARGE -> R.layout.wg_np_large
        })
        glass(rv, s)
        val live = st.mode == NowState.LIVE
        val hasMedia = st.mode != NowState.EMPTY

        // Text
        rv.setTextViewText(R.id.npTitle, if (hasMedia) st.title else "Nothing playing yet")
        rv.text(s, R.id.npTitle) { if (size == NpSize.SQUARE && hasMedia) android.graphics.Color.WHITE else it.text }
        val label = when { live && st.playing -> "NOW PLAYING"; live -> "PAUSED"; st.mode == NowState.RESUME -> "CONTINUE WATCHING"; else -> "VELA PLAYER" }
        if (size == NpSize.MEDIUM || size == NpSize.LARGE) {
            rv.setTextViewText(R.id.npLabel, label)
            rv.text(s, R.id.npLabel) { it.accent }
        }
        val queue = if (live && st.count > 1) "  ·  ${st.index + 1} of ${st.count}" else ""
        val total = if (st.durationMs > 0) Fmt.time(st.durationMs) else ""
        val chrono = (size == NpSize.MEDIUM || size == NpSize.LARGE) && hasMedia && st.durationMs > 0
        val sub = when {
            chrono -> " / $total$queue"
            !hasMedia -> "Tap to open your library"
            live && st.playing -> if (size == NpSize.SQUARE) "NOW PLAYING" else "Now playing$queue"
            live -> "Paused · " + Fmt.time(st.positionMs) + (if (total.isNotEmpty()) " / $total" else "")
            st.positionMs > 0 -> (if (size == NpSize.SQUARE) "RESUME · " else "Resume at ") + Fmt.time(st.positionMs)
            else -> if (size == NpSize.SQUARE) "RESUME" else "Tap play to resume"
        }
        rv.setTextViewText(R.id.npSub, sub)
        rv.text(s, R.id.npSub) { if (size == NpSize.SQUARE && hasMedia) 0xCCFFFFFF.toInt() else it.textDim }
        if (size == NpSize.MEDIUM || size == NpSize.LARGE) {
            if (chrono) {
                rv.setViewVisibility(R.id.npElapsed, View.VISIBLE)
                // A widget Chronometer counts on its own: live time without waking the app every second.
                val elapsedNow = st.positionMs + if (live && st.playing) (SystemClock.elapsedRealtime() - st.at) else 0L
                rv.setChronometer(R.id.npElapsed, SystemClock.elapsedRealtime() - elapsedNow, null, live && st.playing && st.speed == 1f)
                rv.text(s, R.id.npElapsed) { it.textDim }
            } else rv.setViewVisibility(R.id.npElapsed, View.GONE)
            if (s.cfg.showProgress && hasMedia && st.durationMs > 0) {
                rv.setViewVisibility(R.id.npProgress, View.VISIBLE)
                val wDp = if (size == NpSize.MEDIUM) s.wDp - 24 - 96 - 12 else s.wDp - 24
                rv.setImageViewBitmap(R.id.npProgress, WidgetArt.progress(s.px(wDp.toFloat().coerceAtLeast(60f)), s.px(8f), st.positionMs.toFloat() / st.durationMs, s.pal))
            } else rv.setViewVisibility(R.id.npProgress, View.GONE)
        }

        // Artwork
        val art = when (size) {
            NpSize.BAR -> WidgetArt.nowArt(c, st, s.px(44f), s.px(44f), 12f * s.density, s.pal, false)
            NpSize.SQUARE -> {
                // Full-bleed: drawn at the widget's own aspect (fitXY never distorts it), at most 330 px on its long side.
                val k = minOf(s.density, 330f / maxOf(s.wDp, s.hDp, 1))
                WidgetArt.nowArt(c, st, (s.wDp * k).toInt(), (s.hDp * k).toInt(), outerRadiusPx(s) * k / s.density, s.pal, hasMedia)
            }
            NpSize.MEDIUM -> WidgetArt.nowArt(c, st, s.px(96f), s.px(96f), innerRadiusPx(s, 8f), s.pal, false)
            NpSize.LARGE -> { val w = s.px((s.wDp - 24).toFloat()).coerceAtMost(400); WidgetArt.nowArt(c, st, w, w * 9 / 16, innerRadiusPx(s, 8f), s.pal, false) }
        }
        rv.setImageViewBitmap(R.id.npArt, art)
        if (size == NpSize.SQUARE) {
            val k = s.cfg.corners
            rv.setImageViewResource(R.id.npEdge, STROKE[k]!!)
            rv.tint(s, R.id.npEdge, s.pal.strokeAlpha) { it.stroke }
        }

        // Play / pause: the real player when it exists; otherwise resume through the app (never a dead button).
        rv.setImageViewResource(R.id.npPlay, if (live && st.playing) R.drawable.wgi_pause else R.drawable.wgi_play)
        rv.setContentDescription(R.id.npPlay, if (live && st.playing) "Pause" else if (live) "Play" else if (hasMedia) "Resume" else "Open Vela")
        rv.tint(s, R.id.npPlayBg) { it.accent }
        rv.tint(s, R.id.npPlay) { it.onAccent }
        if (size == NpSize.LARGE) rv.tint(s, R.id.npPlayGlow, if (live && st.playing) 0x8C else 0x4D) { it.accent }

        // Previous / next only drive a real queue; dimmed (and inert) at the ends, hidden when there is no player.
        val skip = live && s.cfg.showSkip && size != NpSize.SQUARE && !(size == NpSize.BAR && s.wDp < 230)
        if (size != NpSize.SQUARE) {
            rv.setViewVisibility(R.id.npPrev, if (skip) View.VISIBLE else View.GONE)
            rv.setViewVisibility(R.id.npNext, if (skip) View.VISIBLE else View.GONE)
            rv.tint(s, R.id.npPrev, if (st.hasPrev) 255 else 80) { it.text }
            rv.tint(s, R.id.npNext, if (st.hasNext) 255 else 80) { it.text }
        }
        if (!s.preview) {
            val play = when {
                live && st.playing -> control(c, WidgetActionReceiver.ACTION_PAUSE, s.id)
                live && WidgetUpdater.canPlayInPlace(c) -> control(c, WidgetActionReceiver.ACTION_PLAY, s.id)
                live -> launch(c, WidgetLaunchActivity.ACTION_PLAYER, s.id, "play") { putExtra(PlayerActivity.EXTRA_WIDGET_PLAY, true) }
                hasMedia -> launch(c, WidgetLaunchActivity.ACTION_RESUME, s.id)
                else -> launch(c, WidgetLaunchActivity.ACTION_OPEN, s.id, "home") { putExtra(WidgetLaunchActivity.EXTRA_DEST, "home") }
            }
            rv.setOnClickPendingIntent(R.id.npPlay, play)
            if (size != NpSize.SQUARE) {
                rv.setOnClickPendingIntent(R.id.npPrev, if (skip && st.hasPrev) control(c, WidgetActionReceiver.ACTION_PREV, s.id) else null)
                rv.setOnClickPendingIntent(R.id.npNext, if (skip && st.hasNext) control(c, WidgetActionReceiver.ACTION_NEXT, s.id) else null)
            }
            val open = when {
                live -> launch(c, WidgetLaunchActivity.ACTION_PLAYER, s.id, "open")
                hasMedia -> launch(c, WidgetLaunchActivity.ACTION_RESUME, s.id, "open")
                else -> launch(c, WidgetLaunchActivity.ACTION_OPEN, s.id, "home") { putExtra(WidgetLaunchActivity.EXTRA_DEST, "home") }
            }
            rv.setOnClickPendingIntent(R.id.npArt, open)
            rv.setOnClickPendingIntent(R.id.npText, open)
        }
        return rv
    }

    /** Cheap 15 s refresh while playing: only time + progress (the artwork bitmap is not re-sent). */
    fun nowPlayingTick(s: Spec, st: NowState): RemoteViews? {
        val size = npSize(s.wDp, s.hDp)
        if (st.mode != NowState.LIVE || (size != NpSize.MEDIUM && size != NpSize.LARGE)) return null
        val rv = RemoteViews(s.c.packageName, if (size == NpSize.MEDIUM) R.layout.wg_np_medium else R.layout.wg_np_large)
        if (st.durationMs > 0) {
            val elapsedNow = st.positionMs
            rv.setChronometer(R.id.npElapsed, SystemClock.elapsedRealtime() - elapsedNow, null, st.playing && st.speed == 1f)
            if (s.cfg.showProgress) {
                val wDp = if (size == NpSize.MEDIUM) s.wDp - 24 - 96 - 12 else s.wDp - 24
                rv.setImageViewBitmap(R.id.npProgress, WidgetArt.progress(s.px(wDp.toFloat().coerceAtLeast(60f)), s.px(8f), st.positionMs.toFloat() / st.durationMs, s.pal))
            }
        }
        return rv
    }

    // ---- Quick Access ----

    private val SLOTS = listOf(
        intArrayOf(R.id.qs1, R.id.qs1Bg, R.id.qs1Sheen, R.id.qs1Stroke, R.id.qs1Tap, R.id.qs1Icon, R.id.qs1Label),
        intArrayOf(R.id.qs2, R.id.qs2Bg, R.id.qs2Sheen, R.id.qs2Stroke, R.id.qs2Tap, R.id.qs2Icon, R.id.qs2Label),
        intArrayOf(R.id.qs3, R.id.qs3Bg, R.id.qs3Sheen, R.id.qs3Stroke, R.id.qs3Tap, R.id.qs3Icon, R.id.qs3Label),
        intArrayOf(R.id.qs4, R.id.qs4Bg, R.id.qs4Sheen, R.id.qs4Stroke, R.id.qs4Tap, R.id.qs4Icon, R.id.qs4Label),
    )

    fun quickIsRow(wDp: Int, hDp: Int) = hDp < 100

    private fun quick(s: Spec): RemoteViews {
        val c = s.c
        val row = quickIsRow(s.wDp, s.hDp)
        val rv = RemoteViews(c.packageName, if (row) R.layout.wg_quick_row else R.layout.wg_quick_grid)
        glass(rv, s)
        val list = s.cfg.shortcuts.mapNotNull { Shortcut.of(it) }.distinct().take(4).ifEmpty { listOf(Shortcut.CONTINUE, Shortcut.ALL, Shortcut.FOLDERS, Shortcut.PLAYLISTS) }
        SLOTS.forEachIndexed { i, ids ->
            val sc = list.getOrNull(i)
            if (sc == null) { rv.setViewVisibility(ids[0], View.GONE); return@forEachIndexed }
            rv.setViewVisibility(ids[0], View.VISIBLE)
            val hero = i == 0
            // The first shortcut is the hero: a faint accent glass instead of neutral glass.
            if (hero) rv.tint(s, ids[1], if (s.pal.dark) 0x38 else 0x40) { it.accent } else rv.tint(s, ids[1], s.pal.chipAlpha) { it.chip }
            rv.tint(s, ids[2], if (s.pal.dark) 0x1A else 0x99) { it.sheen }
            rv.tint(s, ids[3], s.pal.strokeAlpha) { if (hero) it.accent else it.stroke }
            rv.setImageViewResource(ids[5], sc.icon)
            rv.tint(s, ids[5]) { it.accent }
            rv.setTextViewText(ids[6], sc.label)
            rv.text(s, ids[6]) { it.text }
            rv.setContentDescription(ids[4], sc.label)
            if (!s.preview) rv.setOnClickPendingIntent(ids[4], when (sc) {
                Shortcut.RESUME -> launch(c, WidgetLaunchActivity.ACTION_RESUME, s.id, "q$i")
                Shortcut.VAULT -> launch(c, WidgetLaunchActivity.ACTION_VAULT, s.id, "q$i")
                else -> launch(c, WidgetLaunchActivity.ACTION_OPEN, s.id, "q$i:${sc.key}") { putExtra(WidgetLaunchActivity.EXTRA_DEST, sc.key) }
            })
        }
        return rv
    }

    // ---- lists: Continue Watching, Playlist & Favorites, Recently Added ----

    private fun list(s: Spec, layout: Int): RemoteViews {
        val c = s.c
        val rv = RemoteViews(c.packageName, layout)
        glass(rv, s)
        val perm = WidgetData.hasVideoPermission(c)
        val items = WidgetData.items(c, s.kind, s.cfg, if (s.preview) 6 else 50)
        // header
        val (title, icon) = when (s.kind) {
            WidgetKind.CONTINUE -> "Continue watching" to R.drawable.wgi_continue
            WidgetKind.LIBRARY -> "Recently added" to R.drawable.wgi_library
            else -> {
                val (name, l) = WidgetData.collection(c, s.cfg.source)
                (if (l == null) "Playlist removed" else name) to (if (s.cfg.source.startsWith("playlist:")) R.drawable.wgi_playlist else R.drawable.wgi_star)
            }
        }
        rv.setTextViewText(R.id.hdrTitle, title)
        rv.text(s, R.id.hdrTitle) { it.text }
        val n = items.size
        val sub = when (s.kind) {
            WidgetKind.CONTINUE -> if (n == 0) "All caught up" else if (n == 1) "1 unfinished video" else "$n unfinished videos"
            WidgetKind.LIBRARY -> if (!perm) "Needs video access" else if (n == 0) "No videos yet" else "Newest on this phone"
            else -> {
                val total = items.sumOf { it.durationMs }
                (if (n == 1) "1 video" else "$n videos") + if (total > 0) " · " + Fmt.time(total) else ""
            }
        }
        rv.setTextViewText(R.id.hdrSub, sub)
        rv.text(s, R.id.hdrSub) { it.textDim }
        rv.setImageViewResource(R.id.hdrIcon, icon)
        rv.tint(s, R.id.hdrChip, if (s.pal.dark) 0x33 else 0x2E) { it.accent }
        rv.tint(s, R.id.hdrIcon) { it.accent }
        rv.tint(s, R.id.hdrAction1Bg) { it.accent }
        rv.tint(s, R.id.hdrAction1) { it.onAccent }
        val isCollection = s.kind == WidgetKind.COLLECTION
        rv.setImageViewResource(R.id.hdrAction1, if (isCollection) R.drawable.wgi_play else R.drawable.wgi_open)
        rv.setContentDescription(R.id.hdrAction1, if (isCollection) "Play all" else "Open in Vela")
        rv.setViewVisibility(R.id.hdrAction1Wrap, if (isCollection && n == 0) View.GONE else View.VISIBLE)
        rv.setViewVisibility(R.id.hdrAction2, if (isCollection && n > 1) View.VISIBLE else View.GONE)
        rv.tint(s, R.id.hdrAction2) { it.text }

        // empty state (real reasons only)
        val emptyText = when (s.kind) {
            WidgetKind.CONTINUE -> "Nothing half-watched. Videos you stop partway through appear here."
            WidgetKind.LIBRARY -> if (!perm) "Open Vela and allow video access to see your newest videos." else "No videos on this phone yet."
            else -> if (WidgetData.collection(c, s.cfg.source).second == null) "This playlist was deleted. Tap to choose another." else
                if (s.cfg.source.startsWith("playlist:")) "This playlist is empty." else "Long-press any video in Vela and choose Add to favorites."
        }
        rv.setTextViewText(R.id.wgEmptyText, emptyText)
        rv.text(s, R.id.wgEmptyText) { it.textDim }
        rv.tint(s, R.id.wgEmptyArt, if (s.pal.dark) 0xE6 else 0xCC) { it.accent }

        if (!s.preview) {
            val dest = when (s.kind) { WidgetKind.CONTINUE -> "continue"; WidgetKind.LIBRARY -> "all"; else -> if (s.cfg.source.startsWith("playlist:")) "playlists" else "favorites" }
            val openApp = launch(c, WidgetLaunchActivity.ACTION_OPEN, s.id, "hdr:$dest") {
                putExtra(WidgetLaunchActivity.EXTRA_DEST, dest)
                if (s.cfg.source.startsWith("playlist:")) putExtra(WidgetLaunchActivity.EXTRA_PLAYLIST, s.cfg.source.removePrefix("playlist:"))
            }
            rv.setOnClickPendingIntent(R.id.hdrText, openApp)
            if (isCollection) {
                rv.setOnClickPendingIntent(R.id.hdrAction1, launch(c, WidgetLaunchActivity.ACTION_PLAY, s.id, "all") {
                    putExtra(WidgetLaunchActivity.EXTRA_KIND, s.kind.key); putExtra(WidgetLaunchActivity.EXTRA_SOURCE, s.cfg.source) })
                rv.setOnClickPendingIntent(R.id.hdrAction2, launch(c, WidgetLaunchActivity.ACTION_PLAY, s.id, "shuffle") {
                    putExtra(WidgetLaunchActivity.EXTRA_KIND, s.kind.key); putExtra(WidgetLaunchActivity.EXTRA_SOURCE, s.cfg.source)
                    putExtra(WidgetLaunchActivity.EXTRA_SHUFFLE, true) })
            } else rv.setOnClickPendingIntent(R.id.hdrAction1, openApp)
            // A deleted playlist: tapping the message reopens the widget's settings to pick another one.
            val emptyTap = if (isCollection && WidgetData.collection(c, s.cfg.source).second == null)
                PendingIntent.getActivity(c, 0, Intent(c, WidgetDetailActivity::class.java)
                    .setData(Uri.parse("vela-widget://configure/${s.id}"))
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, s.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags())
            else if (s.kind == WidgetKind.LIBRARY && !perm) launch(c, WidgetLaunchActivity.ACTION_OPEN, s.id, "perm") { putExtra(WidgetLaunchActivity.EXTRA_DEST, "all") }
            else openApp
            rv.setOnClickPendingIntent(R.id.wgEmpty, emptyTap)

            val svc = Intent(c, WidgetListService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, s.id).putExtra(WidgetLaunchActivity.EXTRA_KIND, s.kind.key)
                .setData(Uri.parse("vela-widget://list/${s.kind.key}/${s.id}"))
            @Suppress("DEPRECATION") rv.setRemoteAdapter(R.id.wgList, svc)
            rv.setEmptyView(R.id.wgList, R.id.wgEmpty)
            val template = Intent(c, WidgetLaunchActivity::class.java).setAction(WidgetLaunchActivity.ACTION_PLAY)
                .setData(Uri.parse("vela-widget://item/${s.kind.key}/${s.id}"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, s.id).putExtra(WidgetLaunchActivity.EXTRA_KIND, s.kind.key)
                .putExtra(WidgetLaunchActivity.EXTRA_SOURCE, s.cfg.source).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            rv.setPendingIntentTemplate(R.id.wgList, PendingIntent.getActivity(c, 0, template, flags(mutable = true)))
        } else {
            rv.setViewVisibility(R.id.wgList, View.GONE)
            if (items.isEmpty()) rv.setViewVisibility(R.id.wgEmpty, View.VISIBLE)
            else {
                rv.setViewVisibility(R.id.previewItems, View.VISIBLE)
                if (s.kind == WidgetKind.LIBRARY) {
                    val cols = ((s.wDp - 20 + 6) / (128 + 6)).coerceAtLeast(1)
                    val rowsFit = if (s.hDp >= 260) 2 else 1
                    items.take(cols * rowsFit).forEachIndexed { i, it ->
                        val thumb = WidgetArt.thumb(c, it.uri, it.mediaId, it.durationMs)
                        rv.addView(if (i < cols) R.id.previewRow1 else R.id.previewRow2, gridItem(s, it, thumb))
                    }
                } else {
                    val fit = ((s.hDp - 52) / 64).coerceIn(1, 4)
                    items.take(fit).forEach { rv.addView(R.id.previewItems, listItem(s, it, WidgetArt.thumb(c, it.uri, it.mediaId, it.durationMs))) }
                }
            }
        }
        return rv
    }

    fun listItem(s: Spec, it: WItem, thumb: Bitmap?, index: Int = 0): RemoteViews {
        val rv = RemoteViews(s.c.packageName, R.layout.wg_list_item)
        val showProg = s.kind == WidgetKind.CONTINUE || it.progress > 0f
        rv.setImageViewBitmap(R.id.itThumb, WidgetArt.card(s.c, thumb, s.px(88f), s.px(50f), 9f * s.density, s.pal,
            progress = if (showProg && s.cfg.showProgress) it.progress else 0f))
        rv.setTextViewText(R.id.itTitle, it.title)
        rv.text(s, R.id.itTitle) { p -> p.text }
        val meta = when {
            s.kind == WidgetKind.CONTINUE && it.durationMs > 0 -> {
                val left = (it.durationMs - it.positionMs).coerceAtLeast(0)
                (if (left >= 60_000) "${left / 60_000} min left" else "Almost done") + " · " + Fmt.time(it.durationMs)
            }
            s.kind == WidgetKind.CONTINUE && it.positionMs > 0 -> "Resume at " + Fmt.time(it.positionMs)
            it.durationMs > 0 -> Fmt.time(it.durationMs) + if (it.progress > 0f) " · resume " + Fmt.time(it.positionMs) else ""
            else -> "Video"
        }
        rv.setTextViewText(R.id.itMeta, meta)
        rv.text(s, R.id.itMeta) { p -> p.textDim }
        rv.tint(s, R.id.itPlay, 0xB3) { p -> p.accent }
        if (!s.preview) rv.setOnClickFillInIntent(R.id.itRoot, Intent().putExtra(WidgetLaunchActivity.EXTRA_URI, it.uri).putExtra(WidgetLaunchActivity.EXTRA_INDEX, index))
        return rv
    }

    fun gridItem(s: Spec, it: WItem, thumb: Bitmap?, index: Int = 0): RemoteViews {
        val rv = RemoteViews(s.c.packageName, R.layout.wg_grid_item)
        val wPx = s.px(128f).coerceAtMost(320)
        rv.setImageViewBitmap(R.id.itThumb, WidgetArt.card(s.c, thumb, wPx, wPx * 9 / 16, 10f * s.density, s.pal,
            progress = if (s.cfg.showProgress) it.progress else 0f, duration = if (it.durationMs > 0) Fmt.time(it.durationMs) else null))
        rv.setTextViewText(R.id.itTitle, it.title)
        rv.text(s, R.id.itTitle) { p -> p.text }
        if (!s.preview) rv.setOnClickFillInIntent(R.id.itRoot, Intent().putExtra(WidgetLaunchActivity.EXTRA_URI, it.uri).putExtra(WidgetLaunchActivity.EXTRA_INDEX, index))
        return rv
    }
}

