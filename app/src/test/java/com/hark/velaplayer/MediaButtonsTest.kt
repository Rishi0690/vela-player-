package com.hark.velaplayer

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.metadata.MetadataRenderer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.session.MediaSession
import androidx.media.MediaSessionManager.RemoteUserInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Proxy

/**
 * v2.21 Bluetooth / headset media buttons: key handling (MediaKeys), what the session exposes (SessionPlayer),
 * and the session callback wiring (MediaButtons.Callback through a real Media3 MediaSession).
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
@Config(sdk = [34], application = android.app.Application::class)
class MediaButtonsTest {

    // ------------------------------------------------------------------ a recording fake Player

    private class Rec { val calls = ArrayList<String>(); var playWhenReady = false; var state = Player.STATE_READY }

    private fun fakePlayer(r: Rec): Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, m, args ->
        when (m.name) {
            "getPlayWhenReady" -> r.playWhenReady
            "getPlaybackState" -> r.state
            "play" -> { r.calls += "play"; r.playWhenReady = true; null }
            "pause" -> { r.calls += "pause"; r.playWhenReady = false; null }
            "hashCode" -> 1; "equals" -> args?.get(0) === null; "toString" -> "fake"
            else -> { r.calls += m.name; when (m.returnType) { java.lang.Boolean.TYPE -> false; Integer.TYPE -> 0; java.lang.Long.TYPE -> 0L; java.lang.Float.TYPE -> 0f; else -> null } }
        }
    } as Player

    private class Clock { val pending = ArrayList<Runnable>(); fun run() { val l = pending.toList(); pending.clear(); l.forEach { it.run() } } }

    private fun keys(r: Rec, clock: Clock) = MediaKeys(fakePlayer(r), { run, _ -> clock.pending += run }, { run -> clock.pending.remove(run) })

    private fun press(k: MediaKeys, code: Int, external: Boolean = true, repeat: Int = 0): Boolean {
        val down = k.onKey(code, KeyEvent.ACTION_DOWN, repeat, external)
        k.onKey(code, KeyEvent.ACTION_UP, 0, external)
        return down
    }

    @Test fun headsetSingleClickTogglesAfterTheMultiClickWindow() {
        val r = Rec(); val c = Clock(); val k = keys(r, c)
        assertTrue(press(k, KeyEvent.KEYCODE_HEADSETHOOK))
        assertTrue("waits for a possible second click", r.calls.isEmpty())
        c.run()
        assertEquals(listOf("play"), r.calls)
        press(k, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); c.run()
        assertEquals(listOf("play", "pause"), r.calls)
    }

    @Test fun doubleClickIsNextTripleIsPrevious() {
        val r = Rec(); val c = Clock(); val k = keys(r, c)
        press(k, KeyEvent.KEYCODE_HEADSETHOOK); press(k, KeyEvent.KEYCODE_HEADSETHOOK); c.run()
        assertEquals(listOf("seekToNext"), r.calls)
        r.calls.clear()
        press(k, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); press(k, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); press(k, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        assertEquals("third click runs at once", listOf("seekToPrevious"), r.calls)
        assertTrue(c.pending.isEmpty())
    }

    @Test fun longPressDoesNotToggleRepeatedly() {
        val r = Rec(); val c = Clock(); val k = keys(r, c)
        k.onKey(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.ACTION_DOWN, 0, true)
        repeat(5) { k.onKey(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.ACTION_DOWN, it + 1, true) }
        c.run()
        assertEquals(listOf("play"), r.calls)
    }

    @Test fun notificationOrInAppPlayPauseIsImmediate() {
        val r = Rec(); val c = Clock(); val k = keys(r, c)
        press(k, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, external = false)
        assertEquals(listOf("play"), r.calls)
    }

    @Test fun dedicatedKeys() {
        val r = Rec(); val c = Clock(); val k = keys(r, c)
        press(k, KeyEvent.KEYCODE_MEDIA_PLAY); press(k, KeyEvent.KEYCODE_MEDIA_PAUSE)
        press(k, KeyEvent.KEYCODE_MEDIA_NEXT); press(k, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        press(k, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD); press(k, KeyEvent.KEYCODE_MEDIA_REWIND)
        press(k, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD); press(k, KeyEvent.KEYCODE_MEDIA_STOP)
        assertEquals(listOf("play", "pause", "seekToNext", "seekToPrevious", "seekForward", "seekBack", "seekToNext", "stop"), r.calls)
        assertFalse("other keys are left alone", press(k, KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test fun anotherKeyFlushesAPendingClickFirst() {
        val r = Rec(); val c = Clock(); val k = keys(r, c)
        press(k, KeyEvent.KEYCODE_HEADSETHOOK)
        press(k, KeyEvent.KEYCODE_MEDIA_NEXT)
        assertEquals(listOf("play", "seekToNext"), r.calls)
    }

    // ------------------------------------------------------------------ SessionPlayer on a real ExoPlayer

    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private var exo: ExoPlayer? = null
    private val main get() = shadowOf(Looper.getMainLooper())

    @After fun tearDown() { MediaButtons.hooks = null; exo?.release(); main.idle() }

    private fun fixture(name: String): String {
        val f = File(ctx.cacheDir, name)
        javaClass.classLoader!!.getResourceAsStream("streams/$name")!!.use { i -> f.outputStream().use { i.copyTo(it) } }
        return android.net.Uri.fromFile(f).toString()
    }

    private fun player(vararg names: String): ExoPlayer {
        val p = ExoPlayer.Builder(ctx, RenderersFactory { h, _, _, _, m -> arrayOf(MetadataRenderer(m, h.looper)) })
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx), PlayerHolder.extractors()))
            .build()
        exo = p
        p.setMediaItems(names.map { MediaItem.Builder().setUri(fixture(it)).setMediaId(it).build() })
        p.prepare()
        val end = System.currentTimeMillis() + 8000
        fun ready() = p.playbackState == Player.STATE_READY ||
            (!p.currentTimeline.isEmpty && !p.isLoading && p.duration != androidx.media3.common.C.TIME_UNSET)
        while (System.currentTimeMillis() < end && !ready() && p.playerError == null) { main.idle(); Thread.sleep(20) }
        main.idle()
        assertTrue("prepared: state=${p.playbackState} err=${p.playerError}", ready())
        assertTrue(p.isCurrentMediaItemSeekable)
        return p
    }

    private class Hooks(var allowed: Boolean = true, val interval: Long = 10_000L) : MediaButtons.Hooks {
        var jumps = 0
        override fun beforeQueueJump() { jumps++ }
        override fun playAllowed() = allowed
        override fun seekIntervalMs() = interval
    }

    @Test fun singleVideoStillOffersNextAndPreviousAndNextSkipsForward() {
        val p = player("normal.mkv")
        val h = Hooks(interval = 3_000L); MediaButtons.hooks = h
        assertFalse("plain ExoPlayer: no next on a single item (why neckband 'next' did nothing)", p.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        val s = SessionPlayer(p)
        assertTrue(s.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(s.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
        assertTrue(s.availableCommands.contains(Player.COMMAND_STOP))
        s.seekToNext(); main.idle()
        assertEquals(3_000L, p.currentPosition)
        s.seekForward(); main.idle()
        assertEquals(6_000L, p.currentPosition)
        s.seekBack(); main.idle()
        assertEquals(3_000L, p.currentPosition)
        assertEquals(3_000L, s.seekForwardIncrement)
        assertEquals(0, h.jumps)
    }

    @Test fun queueNextAndPreviousMoveInTheQueueAndSaveFirst() {
        val p = player("normal.mkv", "nocues.mkv")
        val h = Hooks(); MediaButtons.hooks = h
        val s = SessionPlayer(p)
        s.seekToNext(); main.idle()
        assertEquals(1, p.currentMediaItemIndex)
        assertEquals(1, h.jumps)
        s.seekToPrevious(); main.idle() // at the start of item 2 → previous item
        assertEquals(0, p.currentMediaItemIndex)
        assertEquals(2, h.jumps)
    }

    @Test fun stopPausesInsteadOfUnloading() {
        val p = player("normal.mkv")
        MediaButtons.hooks = Hooks()
        val s = SessionPlayer(p)
        s.play(); main.idle(); assertTrue(p.playWhenReady)
        s.stop(); main.idle()
        assertFalse(p.playWhenReady)
        assertTrue("still loaded (not IDLE)", p.playbackState != Player.STATE_IDLE)
        assertEquals(1, p.mediaItemCount)
    }

    @Test fun playIsRefusedWhileBackgroundPlaybackIsNotAllowed() {
        val p = player("normal.mkv")
        val h = Hooks(allowed = false); MediaButtons.hooks = h
        val s = SessionPlayer(p)
        s.play(); s.playWhenReady = true; main.idle()
        assertFalse(p.playWhenReady)
        h.allowed = true
        s.play(); main.idle()
        assertTrue(p.playWhenReady)
        s.pause(); main.idle(); assertFalse(p.playWhenReady)
    }

    // ------------------------------------------------------------------ the real session + callback

    @Test fun sessionCallbackHandlesBluetoothKeys() {
        val p = player("normal.mkv", "nocues.mkv")
        MediaButtons.hooks = Hooks()
        val session = PlayerHolder.createSession(ctx, p)
        assertNotNull("session is created with the player (no service needed)", session)
        try {
            val cb = MediaButtons.Callback(MediaKeys(session!!.player, { r, _ -> r.run() }, { }, multiClickMs = 0))
            val bt = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
                RemoteUserInfo(RemoteUserInfo.LEGACY_CONTROLLER, 0, 0), MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION, 0, false, android.os.Bundle.EMPTY)
            fun key(code: Int) = Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, code))
            assertTrue(cb.onMediaButtonEvent(session, bt, key(KeyEvent.KEYCODE_MEDIA_NEXT))); main.idle()
            assertEquals(1, p.currentMediaItemIndex)
            assertTrue(cb.onMediaButtonEvent(session, bt, key(KeyEvent.KEYCODE_MEDIA_PLAY))); main.idle()
            assertTrue(p.playWhenReady)
            assertTrue(cb.onMediaButtonEvent(session, bt, key(KeyEvent.KEYCODE_MEDIA_PAUSE))); main.idle()
            assertFalse(p.playWhenReady)
            assertFalse(cb.onMediaButtonEvent(session, bt, key(KeyEvent.KEYCODE_VOLUME_DOWN)))
            assertTrue("the session exposes the wrapper", session.player is SessionPlayer)
        } finally { session?.release(); main.idle() }
    }
}
