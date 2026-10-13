package com.hark.velaplayer

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.media3.common.Player
import java.util.concurrent.Executors

/**
 * v2.14: keeps every placed widget in step with the app.
 *
 * - Playback: [attach]ed to the real ExoPlayer by [PlayerHolder]; media item / play state / queue changes re-render
 *   Now Playing (debounced), and while playing with the screen on a cheap partial update refreshes time + progress
 *   every 15 s (the time itself ticks in a widget Chronometer, so nothing runs every second).
 * - Theme: [VelaApp] listens to the theme prefs (preset, custom colour, light/dark) and calls [requestAll]; so do app
 *   start, a night-mode configuration change and MY_PACKAGE_REPLACED.
 * - Data: Store / Vault changes refresh the list widgets (throttled while a video is saving its position).
 */
object WidgetUpdater {
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private var app: Context? = null
    private var attached: Player? = null
    /** True while PlayerActivity is started (visible, incl. picture-in-picture). Set by PlayerActivity. */
    @Volatile var playerVisible = false
        set(v) { field = v; app?.let { requestNowPlaying(it, 150) } }
    private const val TICK_MS = 15_000L
    private const val DATA_GAP_MS = 20_000L
    private var lastData = 0L

    fun init(c: Context) { if (app == null) app = c.applicationContext }

    /** Play can act on the existing player without opening the screen: background audio on, or the player is visible. */
    fun canPlayInPlace(c: Context) = playerVisible || Prefs.backgroundAudio(c)

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_MEDIA_METADATA_CHANGED, Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_PLAYBACK_PARAMETERS_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    Player.EVENT_REPEAT_MODE_CHANGED)) app?.let { requestNowPlaying(it) }
        }
    }

    /** Main thread (called from PlayerHolder.create). */
    fun attach(c: Context, p: Player) {
        init(c)
        attached?.removeListener(listener)
        attached = p
        p.addListener(listener)
        requestNowPlaying(c)
    }

    /** Main thread (called from PlayerHolder.release, before the player is released). */
    fun detach() {
        attached?.removeListener(listener)
        attached = null
        main.removeCallbacks(tick)
        app?.let { requestNowPlaying(it, 500) } // shows "Continue watching" once the player is gone
    }

    // ---------------------------------------------------------------- requests

    private val npRun = Runnable { app?.let { renderNow(it, NowState.snapshot(it), WidgetKind.NOW_PLAYING.ids(it), null) } }
    fun requestNowPlaying(c: Context, delay: Long = 250L) {
        init(c); main.removeCallbacks(npRun); main.postDelayed(npRun, delay)
    }

    private val allRun = Runnable {
        val c = app ?: return@Runnable
        WidgetArt.clear()
        WidgetKind.values().forEach { k -> val ids = k.ids(c); if (ids.isNotEmpty()) render(c, k, ids, null, dataChanged = true) }
    }
    /** Re-render every widget (theme, light/dark, app start/update). */
    fun requestAll(c: Context, delay: Long = 350L) { init(c); main.removeCallbacks(allRun); main.postDelayed(allRun, delay) }

    private val dataRun = Runnable {
        val c = app ?: return@Runnable
        lastData = SystemClock.elapsedRealtime()
        // The library grid reads MediaStore, so it refreshes on app start / theme / its own period, not on every save.
        listOf(WidgetKind.CONTINUE, WidgetKind.COLLECTION).forEach { k -> val ids = k.ids(c); if (ids.isNotEmpty()) render(c, k, ids, null, dataChanged = true) }
        if (PlayerHolder.player == null) requestNowPlaying(c, 0)
    }
    /** Library state changed (resume points, favorites, playlists, vault). At most every 20 s while it keeps changing. */
    fun requestData(c: Context) {
        init(c)
        main.removeCallbacks(dataRun)
        val since = SystemClock.elapsedRealtime() - lastData
        main.postDelayed(dataRun, if (since >= DATA_GAP_MS) 1_200L else DATA_GAP_MS - since)
    }

    // ---------------------------------------------------------------- rendering

    /** Renders [ids] of [kind]. Main thread entry for Now Playing (the player is read here); work runs on a background thread. */
    fun render(c: Context, kind: WidgetKind, ids: IntArray, done: (() -> Unit)?, dataChanged: Boolean = false) {
        init(c)
        if (kind == WidgetKind.NOW_PLAYING) {
            if (Looper.myLooper() == Looper.getMainLooper()) renderNow(c, NowState.snapshot(c), ids, done)
            else main.post { renderNow(c, NowState.snapshot(c), ids, done) }
            return
        }
        val app = c.applicationContext
        exec.execute {
            val mgr = AppWidgetManager.getInstance(app)
            ids.forEach { id ->
                try { mgr.updateAppWidget(id, WidgetRenderer.build(WidgetRenderer.spec(app, kind, id))) } catch (_: Exception) { }
            }
            if (dataChanged && kind.isList) try { @Suppress("DEPRECATION") mgr.notifyAppWidgetViewDataChanged(ids, R.id.wgList) } catch (_: Exception) { }
            done?.invoke()
        }
    }

    private fun renderNow(c: Context, st: NowState, ids: IntArray, done: (() -> Unit)?) {
        scheduleTick(st)
        if (ids.isEmpty()) { done?.invoke(); return }
        val app = c.applicationContext
        exec.execute {
            val mgr = AppWidgetManager.getInstance(app)
            ids.forEach { id -> try { mgr.updateAppWidget(id, WidgetRenderer.build(WidgetRenderer.spec(app, WidgetKind.NOW_PLAYING, id), st)) } catch (_: Exception) { } }
            done?.invoke()
        }
    }

    private fun scheduleTick(st: NowState) {
        main.removeCallbacks(tick)
        if (st.mode == NowState.LIVE && st.playing) main.postDelayed(tick, TICK_MS)
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            val c = app ?: return
            val ids = WidgetKind.NOW_PLAYING.ids(c)
            if (ids.isEmpty()) return
            val st = NowState.snapshot(c)
            if (st.mode != NowState.LIVE || !st.playing) return
            val pm = c.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm?.isInteractive != false) {
                exec.execute {
                    val mgr = AppWidgetManager.getInstance(c)
                    ids.forEach { id ->
                        try { WidgetRenderer.nowPlayingTick(WidgetRenderer.spec(c, WidgetKind.NOW_PLAYING, id), st)?.let { mgr.partiallyUpdateAppWidget(id, it) } } catch (_: Exception) { }
                    }
                }
            }
            main.postDelayed(this, TICK_MS)
        }
    }

    // ---------------------------------------------------------------- app hooks

    /** Theme keys written by ThemeManager / ThemeSwitch / Settings. Kept as a field so the listener is not garbage collected. */
    private val THEME_KEYS = setOf("theme", "theme_key", "accent_seed", "theme_secondary", "theme_base", "theme_version", "background_audio")
    private var themeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var storeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** Called once from VelaApp.onCreate. */
    fun install(c: Context) {
        init(c)
        val a = c.applicationContext
        themeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "background_audio" -> requestNowPlaying(a)
                "hide_ts", "smart_thumbnails" -> requestData(a)
                in THEME_KEYS -> requestAll(a)
            }
        }.also { Prefs.sp(a).registerOnSharedPreferenceChangeListener(it) }
        storeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> requestData(a) }.also {
            Store.registerListener(a, it)
            a.getSharedPreferences("vela_vault", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(it)
        }
        if (WidgetKind.values().any { it.ids(a).isNotEmpty() }) requestAll(a, 1_500L)
    }
}

/** Base provider: config adoption, size changes, deletion and backup restore for every Vela widget. */
abstract class VelaWidgetProvider(private val kind: WidgetKind) : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        // A widget added from the gallery adopts what was set up there (the pin callback may arrive later; same result).
        ids.forEach { id -> if (!WidgetStore.has(context, id)) WidgetStore.takePending(context, kind)?.let { WidgetStore.put(context, id, it) } }
        val pr = goAsync()
        WidgetUpdater.render(context, kind, ids, { try { pr.finish() } catch (_: Exception) { } }, dataChanged = true)
    }
    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle?) {
        val pr = goAsync()
        WidgetUpdater.render(context, kind, intArrayOf(id), { try { pr.finish() } catch (_: Exception) { } })
    }
    override fun onDeleted(context: Context, ids: IntArray) = WidgetStore.remove(context, ids)
    override fun onRestored(context: Context, oldIds: IntArray, newIds: IntArray) = WidgetStore.move(context, oldIds, newIds)
}

class NowPlayingWidget : VelaWidgetProvider(WidgetKind.NOW_PLAYING)
class ContinueWidget : VelaWidgetProvider(WidgetKind.CONTINUE)
class QuickAccessWidget : VelaWidgetProvider(WidgetKind.QUICK)
class LibraryWidget : VelaWidgetProvider(WidgetKind.LIBRARY)
class CollectionWidget : VelaWidgetProvider(WidgetKind.COLLECTION)

/**
 * Widget media buttons and system events. Controls go through the player's own control path (the same broadcast its
 * picture-in-picture buttons use), so saving the position, queue rules and Focus Mode behave exactly as in the app.
 */
class WidgetActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_PLAY = "com.hark.velaplayer.widget.PLAY"
        const val ACTION_PAUSE = "com.hark.velaplayer.widget.PAUSE"
        const val ACTION_PREV = "com.hark.velaplayer.widget.PREV"
        const val ACTION_NEXT = "com.hark.velaplayer.widget.NEXT"
        const val ACTION_PINNED = "com.hark.velaplayer.widget.PINNED"
        const val EXTRA_KIND = "kind"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val ctrl = when (intent.action) {
            ACTION_PLAY -> PlayerActivity.CTRL_PLAY
            ACTION_PAUSE -> PlayerActivity.CTRL_PAUSE
            ACTION_PREV -> PlayerActivity.CTRL_PREV
            ACTION_NEXT -> PlayerActivity.CTRL_NEXT
            else -> 0
        }
        if (ctrl != 0) {
            if (PlayerHolder.player != null) {
                context.sendBroadcast(Intent(PlayerActivity.ACTION_PIP).setPackage(context.packageName).putExtra(PlayerActivity.EXTRA_CONTROL, ctrl))
            }
            // Either way the widget re-reads the real state (a stale widget fixes itself on tap).
            WidgetUpdater.requestNowPlaying(context, 300)
            return
        }
        when (intent.action) {
            ACTION_PINNED -> {
                val kind = WidgetKind.of(intent.getStringExtra(EXTRA_KIND)) ?: return
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
                WidgetStore.takePending(context, kind)?.let { WidgetStore.put(context, id, it) }
                WidgetUpdater.render(context, kind, intArrayOf(id), null, dataChanged = true)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_LOCALE_CHANGED -> WidgetUpdater.requestAll(context, 0)
        }
    }
}

/** Fills the list/grid widgets (one factory per placed widget). */
class WidgetListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext, intent)

    private class Factory(private val c: Context, intent: Intent) : RemoteViewsFactory {
        private val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        private val kind = WidgetKind.of(intent.getStringExtra(WidgetLaunchActivity.EXTRA_KIND)) ?: WidgetKind.CONTINUE
        private var items: List<WItem> = emptyList()
        private var spec: WidgetRenderer.Spec? = null

        override fun onCreate() {}
        override fun onDataSetChanged() {
            try {
                val cfg = WidgetStore.get(c, id)
                spec = WidgetRenderer.spec(c, kind, id, cfg)
                items = WidgetData.items(c, kind, cfg, 50)
            } catch (_: Exception) { items = emptyList() }
        }
        override fun onDestroy() { items = emptyList() }
        override fun getCount() = items.size
        override fun getViewAt(position: Int): RemoteViews {
            val s = spec
            val it = items.getOrNull(position)
            if (s == null || it == null) return RemoteViews(c.packageName, if (kind == WidgetKind.LIBRARY) R.layout.wg_grid_item else R.layout.wg_list_item)
            val thumb = WidgetArt.thumb(c, it.uri, it.mediaId, it.durationMs)
            return if (kind == WidgetKind.LIBRARY) WidgetRenderer.gridItem(s, it, thumb, position) else WidgetRenderer.listItem(s, it, thumb, position)
        }
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = items.getOrNull(position)?.uri?.hashCode()?.toLong() ?: position.toLong()
        override fun hasStableIds() = true
    }
}
