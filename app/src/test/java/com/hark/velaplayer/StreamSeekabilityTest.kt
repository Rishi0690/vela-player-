package com.hark.velaplayer

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.metadata.MetadataRenderer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket

/**
 * v2.19.1: real ExoPlayer (no decoders: one metadata renderer) against a local HTTP server, using the app's own
 * PlayerHolder HTTP + extractor setup. Server modes:
 *  - "chunkedrange": plain request -> 200 without Content-Length; Range request -> 206 with Content-Range
 *  - "chunked": never a length, ignores Range
 *  - "plain": 200 with Content-Length, ignores Range
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
@Config(sdk = [34], application = android.app.Application::class)
class StreamSeekabilityTest {
    private lateinit var ss: ServerSocket
    private lateinit var base: String

    private fun bytesOf(name: String): ByteArray = javaClass.classLoader!!.getResourceAsStream("streams/$name")!!.use { it.readBytes() }

    @Before fun startServer() {
        ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        base = "http://127.0.0.1:${ss.localPort}"
        Thread {
            while (!ss.isClosed) {
                val sock = try { ss.accept() } catch (_: Exception) { break }
                Thread {
                    try {
                        sock.use { so ->
                            val inp = so.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                            val req = inp.readLine() ?: return@use
                            var range: String? = null
                            while (true) { val l = inp.readLine() ?: break; if (l.isEmpty()) break; if (l.lowercase().startsWith("range:")) range = l.substringAfter(':').trim() }
                            val (mode, file) = req.split(' ')[1].trimStart('/').split('/', limit = 2)
                            val bytes = bytesOf(file)
                            val out: OutputStream = so.getOutputStream()
                            if (mode == "chunkedrange" && range != null) {
                                val m = Regex("bytes=(\\d+)-(\\d*)").find(range)!!
                                val from = m.groupValues[1].toInt(); val to = m.groupValues[2].toIntOrNull() ?: (bytes.size - 1)
                                out.write("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $from-$to/${bytes.size}\r\nContent-Length: ${to - from + 1}\r\nConnection: close\r\n\r\n".toByteArray())
                                out.write(bytes, from, to - from + 1)
                            } else if (mode == "plain") {
                                out.write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); out.write(bytes)
                            } else {
                                out.write("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n".toByteArray()); out.write(bytes)
                            }
                            out.flush()
                        }
                    } catch (_: Exception) { }
                }.start()
            }
        }.start()
    }

    @After fun stopServer() { ss.close() }

    private class Result(val mode: StreamPolicy.SeekMode, val durationMs: Long, val error: PlaybackException?, val afterSeekBuffered: Long)

    private fun open(url: String, seekTo: Long = -1): Result {
        val c = ApplicationProvider.getApplicationContext<android.content.Context>()
        val p = ExoPlayer.Builder(c, RenderersFactory { h, _, _, _, m -> arrayOf(MetadataRenderer(m, h.looper)) })
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(c, PlayerHolder.httpFactory()), PlayerHolder.extractors()))
            .build()
        var err: PlaybackException? = null
        p.addListener(object : Player.Listener { override fun onPlayerError(error: PlaybackException) { err = error } })
        p.setMediaItem(MediaItem.fromUri(url)); p.prepare()
        val main = shadowOf(Looper.getMainLooper())
        fun placeholder() = p.currentTimeline.isEmpty || p.currentTimeline.getWindow(0, Timeline.Window()).isPlaceholder
        fun waitFor(ms: Long, done: () -> Boolean) {
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end && err == null && !done()) { main.idle(); Thread.sleep(30) }
            main.idle()
        }
        waitFor(8000) { p.playbackState == Player.STATE_READY || (!placeholder() && p.isLoading.not()) }
        val mode = StreamPolicy.seekMode(p.isCurrentMediaItemSeekable, p.isCurrentMediaItemLive, p.duration, placeholder())
        val dur = p.duration
        var buffered = -1L
        if (seekTo >= 0 && err == null) {
            p.seekTo(seekTo)
            waitFor(4000) { p.bufferedPosition >= dur - 500 }
            buffered = p.bufferedPosition
        }
        val e = err
        p.release(); main.idle()
        return Result(mode, if (dur == C.TIME_UNSET) -1 else dur, e, buffered)
    }

    @Test fun tsWithoutContentLengthBecomesSeekableWhenServerSupportsRanges() {
        val r = open("$base/chunkedrange/s.ts", seekTo = 5_000)
        assertNull(r.error)
        assertEquals(StreamPolicy.SeekMode.SEEKABLE, r.mode)
        assertTrue("duration ${r.durationMs}", r.durationMs in 7_000..9_000)
        assertTrue("buffered after seek ${r.afterSeekBuffered}", r.afterSeekBuffered >= 7_000) // read the right bytes after seeking
    }

    @Test fun adtsAacCanSeekWithConstantBitrateSeeking() {
        val r = open("$base/plain/s.aac", seekTo = 4_000)
        assertNull(r.error)
        assertEquals(StreamPolicy.SeekMode.SEEKABLE, r.mode)
    }

    @Test fun lengthlessStreamWithoutRangeSupportIsHandledAsLive() {
        val r = open("$base/chunked/s.ts")
        assertNull(r.error)
        assertEquals(StreamPolicy.SeekMode.LIVE, r.mode) // no seek bar thumb, LIVE badge, not saved to Continue Watching
    }

    @Test fun plainProgressiveStaysSeekable() {
        val r = open("$base/plain/s.ts", seekTo = 5_000)
        assertNull(r.error)
        assertEquals(StreamPolicy.SeekMode.SEEKABLE, r.mode)
    }
}
