package com.hark.velaplayer

import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.annotation.VisibleForTesting
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.session.MediaSession

/**
 * v2.21 Bluetooth / wired headset / neckband buttons.
 *
 * Root cause of "neckband buttons don't work" (v2.20.1 and older):
 * 1. The ONLY MediaSession lived inside [PlaybackService], which is started only when "Background audio" is on
 *    (default off). With the default settings Vela had NO media session at all, so Android routed Bluetooth AVRCP
 *    commands and media keys to whichever other app last had a session (or dropped them).
 * 2. Even with background audio on, the session wrapped the raw ExoPlayer. A single video (the usual case) has no
 *    next item, so ExoPlayer reports COMMAND_SEEK_TO_NEXT unavailable; Media3 then silently ignores "next" from the
 *    neckband (AVRCP calls the session's transport controls, which are command-checked). Fast-forward/rewind used
 *    ExoPlayer's fixed 15 s / 5 s increments instead of the user's skip interval, and STOP put the player in IDLE
 *    (black screen with the error-free "nothing loaded" state) instead of pausing.
 *
 * Now [PlayerHolder] creates ONE session together with the player (for as long as the player exists, background
 * audio or not), wrapping the player in [SessionPlayer], and [PlaybackService] only adopts that session for the
 * notification. [MediaKeys] handles raw key events itself (single/double/triple click), so behaviour no longer
 * depends on which Media3 internal path a key event took.
 */
object MediaButtons {
    /** Hooks the player screen provides; everything has a safe default when the screen is gone. */
    interface Hooks {
        /** Called before the session moves to another queue item, so the current position is saved. */
        fun beforeQueueJump() {}
        /** May a session command start playback now? (false: background audio off and the player isn't visible). */
        fun playAllowed(): Boolean = true
        /** Skip interval in ms (the user's "seek interval" setting). */
        fun seekIntervalMs(): Long = 10_000L
    }

    @Volatile var hooks: Hooks? = null
    private val defaultHooks = object : Hooks {}
    internal fun h(): Hooks = hooks ?: defaultHooks

    /** Session callback: raw media button events from Bluetooth / wired headsets go through [MediaKeys]. */
    class Callback(private val keys: MediaKeys) : MediaSession.Callback {
        override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean {
            // Taps on Vela's own media notification keep Media3's path (they are already plain commands).
            if (session.isMediaNotificationController(controllerInfo)) return false
            val event = keyEventOf(intent) ?: return false
            // Only key events from outside (Bluetooth, wired headset, other apps) get multi-click detection.
            val external = controllerInfo.controllerVersion == MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION
            return keys.onKey(event.keyCode, event.action, event.repeatCount, external)
        }
    }

    @Suppress("DEPRECATION")
    fun keyEventOf(intent: Intent): KeyEvent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
}

/**
 * Turns media key events into player actions. Pure logic + a scheduler so it is unit-testable.
 * HEADSETHOOK / PLAY_PAUSE from a headset: 1 click = play/pause, 2 = next, 3 = previous (Android's headset
 * convention; the first click waits [multiClickMs] for a second one). Long press (repeat) is ignored.
 */
class MediaKeys(
    private val player: Player,
    private val schedule: (Runnable, Long) -> Unit,
    private val unschedule: (Runnable) -> Unit,
    private val multiClickMs: Long = DEFAULT_MULTI_CLICK_MS,
) {
    companion object {
        const val DEFAULT_MULTI_CLICK_MS = 350L
        fun forMainLooper(player: Player): MediaKeys {
            val handler = Handler(Looper.getMainLooper())
            return MediaKeys(player, { r, d -> handler.postDelayed(r, d) }, { r -> handler.removeCallbacks(r) })
        }
    }

    @VisibleForTesting var clicks = 0
        private set

    private val flush = Runnable { val n = clicks; clicks = 0; runClicks(n) }

    private fun runClicks(n: Int) = when {
        n <= 0 -> Unit
        n == 1 -> togglePlayPause()
        n == 2 -> next()
        else -> previous()
    }

    /** Returns true when the key was consumed. Only ACTION_DOWN acts; ACTION_UP of a handled key is consumed too. */
    fun onKey(keyCode: Int, action: Int, repeatCount: Int, external: Boolean): Boolean {
        val handled = keyCode in HANDLED
        if (!handled) return false
        if (action != KeyEvent.ACTION_DOWN) return true
        if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (repeatCount > 0) return true // long press: no repeated toggles
            if (!external) { flushNow(); togglePlayPause(); return true }
            clicks++
            unschedule(flush)
            if (clicks >= 3) flushNow() else schedule(flush, multiClickMs)
            return true
        }
        // Any other key first completes a pending click sequence, so keys run in order.
        flushNow()
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> play()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> player.pause()
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> next()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> previous()
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_STEP_FORWARD -> if (repeatCount % 3 == 0) player.seekForward()
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> if (repeatCount % 3 == 0) player.seekBack()
            KeyEvent.KEYCODE_MEDIA_STOP -> player.stop()
        }
        return true
    }

    fun flushNow() { unschedule(flush); if (clicks > 0) flush.run() }

    private fun togglePlayPause() { if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) player.pause() else play() }
    private fun play() { player.play() }
    private fun next() { player.seekToNext() }
    private fun previous() { player.seekToPrevious() }

    private val HANDLED = setOf(
        KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_STEP_FORWARD,
        KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD, KeyEvent.KEYCODE_MEDIA_STOP,
    )
}

/**
 * What the media session (Bluetooth, headset, lock screen, notification, Android Auto-like controllers) sees.
 * Playback itself is the shared ExoPlayer; only the session-facing behaviour differs:
 * - next / previous are always available: with a queue neighbour they move in the queue (position saved first);
 *   without one, next = skip forward by the user's interval and previous = Media3's normal "restart / previous"
 *   (back to 0 once past 3 s), so a neckband's next/prev does something useful on a single video;
 * - fast-forward / rewind = the user's skip interval (same as the on-screen buttons), clamped to the video;
 * - stop = pause (keeps the video loaded and the position; the player screen never goes blank);
 * - play is refused while [MediaButtons.Hooks.playAllowed] is false (background audio off and the player is in the
 *   background): a pocket press must not start sound the user switched off.
 */
class SessionPlayer(private val p: Player) : ForwardingPlayer(p) {
    private val h get() = MediaButtons.h()

    override fun getAvailableCommands(): Player.Commands {
        val base = super.getAvailableCommands()
        if (p.currentTimeline.isEmpty) return base
        return base.buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_STOP)
            .build()
    }

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    override fun play() {
        if (!h.playAllowed()) return
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        if (p.playbackState == Player.STATE_ENDED) p.seekToDefaultPosition()
        super.play()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) play() else super.setPlayWhenReady(false)
    }

    override fun stop() { p.pause() }

    override fun seekToNext() {
        if (p.hasNextMediaItem()) { h.beforeQueueJump(); p.seekToNextMediaItem() }
        else if (p.isCurrentMediaItemLive) super.seekToNext()
        else seekByInterval(1)
    }

    override fun seekToNextMediaItem() {
        if (p.hasNextMediaItem()) { h.beforeQueueJump(); p.seekToNextMediaItem() }
    }

    override fun seekToPrevious() {
        if (p.hasPreviousMediaItem() && p.currentPosition <= p.maxSeekToPreviousPosition) { h.beforeQueueJump(); p.seekToPreviousMediaItem() }
        else super.seekToPrevious()
    }

    override fun seekToPreviousMediaItem() {
        if (p.hasPreviousMediaItem()) { h.beforeQueueJump(); p.seekToPreviousMediaItem() }
    }

    override fun getSeekForwardIncrement(): Long = h.seekIntervalMs()
    override fun getSeekBackIncrement(): Long = h.seekIntervalMs()
    override fun seekForward() = seekByInterval(1)
    override fun seekBack() = seekByInterval(-1)

    private fun seekByInterval(sign: Int) {
        if (!p.isCurrentMediaItemSeekable) return
        val dur = p.duration
        var t = p.currentPosition + sign * h.seekIntervalMs()
        if (t < 0) t = 0
        if (dur != C.TIME_UNSET && t > dur) t = dur
        p.seekTo(t)
    }
}
