package com.hark.velaplayer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hark.velaplayer.StreamPolicy.SeekMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** v2.19.1 network streams: seekability decisions, stream titles, Continue Watching storage for stream URLs. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk = [34], application = android.app.Application::class)
class StreamPolicyTest {
    private val unset = Long.MIN_VALUE + 1 // C.TIME_UNSET
    private lateinit var c: Context

    @Before fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        c.getSharedPreferences("vela_store", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // ---- seek mode

    @Test fun onDemandWithDurationIsSeekable() {
        assertEquals(SeekMode.SEEKABLE, StreamPolicy.seekMode(seekable = true, live = false, durationMs = 600_000, placeholder = false))
        assertTrue(SeekMode.SEEKABLE.canSeek)
        assertTrue(StreamPolicy.canResume(SeekMode.SEEKABLE))
    }

    @Test fun placeholderIsLoadingNotUnseekable() {
        assertEquals(SeekMode.LOADING, StreamPolicy.seekMode(false, false, unset, placeholder = true))
        assertFalse(SeekMode.LOADING.canSeek)
        assertFalse(StreamPolicy.canResume(SeekMode.LOADING))
    }

    @Test fun preparedButNotSeekableIsUnseekable() {
        assertEquals(SeekMode.UNSEEKABLE, StreamPolicy.seekMode(false, false, 600_000, placeholder = false))
        assertEquals(SeekMode.UNSEEKABLE, StreamPolicy.seekMode(false, false, unset, placeholder = false))
        // "seekable" without any duration can't drive a timeline either
        assertEquals(SeekMode.UNSEEKABLE, StreamPolicy.seekMode(true, false, unset, placeholder = false))
        assertFalse(StreamPolicy.canResume(SeekMode.UNSEEKABLE))
    }

    @Test fun liveStreams() {
        // DVR window: seekable inside the window, still live, never saved for resume
        val dvr = StreamPolicy.seekMode(true, true, 120_000, placeholder = false)
        assertEquals(SeekMode.LIVE_DVR, dvr)
        assertTrue(dvr.canSeek); assertTrue(dvr.isLive); assertFalse(StreamPolicy.canResume(dvr))
        val live = StreamPolicy.seekMode(false, true, unset, placeholder = false)
        assertEquals(SeekMode.LIVE, live)
        assertFalse(live.canSeek); assertTrue(live.isLive); assertFalse(StreamPolicy.canResume(live))
        // live is reported even while the window is a placeholder
        assertEquals(SeekMode.LIVE, StreamPolicy.seekMode(false, true, unset, placeholder = true))
    }

    // ---- network detection + titles

    @Test fun networkDetection() {
        assertTrue(StreamPolicy.isNetwork("https://example.com/a.mp4"))
        assertTrue(StreamPolicy.isNetwork("HTTP://example.com/a.m3u8"))
        assertFalse(StreamPolicy.isNetwork("content://media/external/video/media/12"))
        assertFalse(StreamPolicy.isNetwork("file:///sdcard/a.mp4"))
        assertFalse(StreamPolicy.isNetwork(null))
        assertFalse(StreamPolicy.isNetwork("/sdcard/http.mp4"))
    }

    @Test fun streamTitles() {
        assertEquals("Big Buck Bunny", StreamPolicy.titleFor("https://cdn.example.com/movies/Big_Buck_Bunny.mp4?token=abc"))
        assertEquals("sintel", StreamPolicy.titleFor("https://cdn.example.com/sintel/master.m3u8"))
        assertEquals("show 1", StreamPolicy.titleFor("https://cdn.example.com/show%201/hls/index.m3u8#t=3"))
        assertEquals("cdn.example.com", StreamPolicy.titleFor("https://cdn.example.com/"))
        assertEquals("cdn.example.com", StreamPolicy.titleFor("https://user@cdn.example.com:8443/playlist.m3u8"))
        assertEquals("news", StreamPolicy.titleFor("http://tv.example.com/news/manifest.mpd"))
        assertEquals("tv.example.com", StreamPolicy.titleFor("http://tv.example.com/0123456789abcdef0123456789abcdef0123456789abcdef/index.m3u8"))
    }

    // ---- Continue Watching for stream URLs

    @Test fun streamProgressAppearsInContinueWatching() {
        val url = "https://cdn.example.com/movies/Big_Buck_Bunny.mp4?token=abc"
        Store.savePosition(c, url, "Big Buck Bunny", 125_000, 600_000)
        val list = Store.continueList(c)
        assertEquals(listOf(Entry(url, "Big Buck Bunny")), list)
        assertEquals(125_000L, Store.position(c, url))
        assertEquals(600_000L, Store.duration(c, url))
        assertTrue(url in Store.inProgressSet(c))
    }

    @Test fun streamFinishedLeavesContinueWatching() {
        val url = "https://cdn.example.com/a/index.m3u8"
        Store.savePosition(c, url, "a", 125_000, 600_000)
        Store.savePosition(c, url, "a", 595_000, 600_000)
        assertTrue(Store.continueList(c).isEmpty())
        assertTrue(url in Store.watchedSet(c))
    }

    @Test fun removeStreamFromContinueWatching() {
        val url = "http://10.0.0.2:8080/live/ch1.ts"
        Store.savePosition(c, url, "ch1", 60_000, 0)
        assertEquals(1, Store.continueList(c).size)
        Store.clearPosition(c, url)
        assertTrue(Store.continueList(c).isEmpty())
    }

    @Test fun hideTsNeverDropsStreams() {
        assertFalse(VideoRepository.isTsEntry(Entry("http://10.0.0.2:8080/live/ch1.ts", "ch1.ts"), null))
        assertTrue(VideoRepository.isTsEntry(Entry("file:///sdcard/rec.ts", "rec.ts"), null))
    }
}
