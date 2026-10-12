package com.hark.velaplayer

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock

/**
 * Measures one Focus Mode session in the player.
 *
 * Time counts only while focus is on AND the video is really playing AND the player is on screen
 * (full screen or picture-in-picture — it's one screen, so nothing is counted twice).
 * Paused, buffering, ended or backgrounded playback doesn't count. It uses the phone's real clock,
 * so watching at 2x still counts real minutes.
 */
class FocusTracker(private val ctx: Context) {
    private val store = FocusStore.get(ctx)
    var sessionId = -1L; private set
    val active get() = sessionId >= 0
    private var countingSince = -1L      // elapsedRealtime when the current playing stretch started
    private var savedMs = 0L             // already written to the store for this session
    private var uri: String? = null
    private var title: String? = null
    private var mediaMs = 0L

    fun start() {
        if (active) return
        store.closeAbandoned()
        sessionId = store.startSession(System.currentTimeMillis())
        savedMs = 0L; countingSince = -1L
        Dnd.enterIfAllowed(ctx)
    }

    /** Ends the session; returns its id (or -1 if nothing was recorded). */
    fun stop(): Long {
        if (!active) return -1
        setCounting(false)
        val id = sessionId
        store.endSession(id, System.currentTimeMillis())
        sessionId = -1
        Dnd.restore(ctx)
        return id
    }

    fun setCounting(on: Boolean) {
        if (!active) { countingSince = -1; return }
        if (on && countingSince < 0) countingSince = SystemClock.elapsedRealtime()
        else if (!on && countingSince >= 0) { flush(); countingSince = -1 }
    }

    fun setVideo(newUri: String?, newTitle: String?, durationMs: Long) {
        if (newUri != uri) { flush(); mediaMs = 0 }
        uri = newUri; title = newTitle
        if (durationMs > 0) mediaMs = durationMs
    }
    fun updateDuration(ms: Long) { if (ms > 0) mediaMs = ms }

    fun markCompleted() { val u = uri ?: return; if (active) { flush(); store.markCompleted(sessionId, u, title, mediaMs) } }

    /** Writes the time played since the last save. Called every few seconds and on every state change. */
    fun flush() {
        if (!active || countingSince < 0) return
        val now = SystemClock.elapsedRealtime()
        // A single stretch can't be longer than the save interval by much; guard against clock jumps.
        val delta = (now - countingSince).coerceIn(0L, 5 * 60_000L)
        countingSince = now
        if (delta > 0) { store.addTime(sessionId, uri, title, mediaMs, delta, System.currentTimeMillis()); savedMs += delta }
    }

    /** Playing time not yet written to the store. */
    fun unsavedMs(): Long = if (countingSince >= 0) (SystemClock.elapsedRealtime() - countingSince).coerceAtLeast(0) else 0L

    /** Real focus time in this session so far, including the stretch not yet saved. */
    fun elapsedMs(): Long = savedMs + if (countingSince >= 0) (SystemClock.elapsedRealtime() - countingSince).coerceAtLeast(0) else 0L
}

/**
 * Optional Do Not Disturb while focusing. Only used when the user turned it on in Settings and granted
 * Android's "Do Not Disturb access". It's switched back off when focus ends — and only if we were the
 * ones who turned it on and nobody changed it since.
 */
object Dnd {
    private const val KEY = "focus_dnd_applied"
    fun nm(c: Context) = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    fun hasAccess(c: Context) = Build.VERSION.SDK_INT >= 23 && nm(c).isNotificationPolicyAccessGranted
    fun wanted(c: Context) = Prefs.sp(c).getBoolean("focus_dnd", false)

    fun enterIfAllowed(c: Context): Boolean {
        if (!wanted(c) || !hasAccess(c)) return false
        return try {
            val m = nm(c)
            if (m.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
                m.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                Prefs.sp(c).edit().putBoolean(KEY, true).apply()
                true
            } else false
        } catch (_: Exception) { false }
    }

    fun restore(c: Context) {
        if (!Prefs.sp(c).getBoolean(KEY, false)) return
        Prefs.sp(c).edit().putBoolean(KEY, false).apply()
        try {
            val m = nm(c)
            if (hasAccess(c) && m.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                m.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        } catch (_: Exception) { }
    }
    fun isApplied(c: Context) = Prefs.sp(c).getBoolean(KEY, false)
}
