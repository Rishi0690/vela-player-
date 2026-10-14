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
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.ChunkIndex
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import com.hark.velaplayer.mkv.VelaMatroskaExtractor
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

    private fun open(url: String, seekTo: Long = -1, extractors: ExtractorsFactory = PlayerHolder.extractors()): Result {
        val c = ApplicationProvider.getApplicationContext<android.content.Context>()
        val p = ExoPlayer.Builder(c, RenderersFactory { h, _, _, _, m -> arrayOf(MetadataRenderer(m, h.looper)) })
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(c, PlayerHolder.httpFactory()), extractors))
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

    // ---- v2.19.2 MKV: nested SeekHead (Cues only listed in an end-of-file SeekHead) and MKV without Cues ----

    @Test fun mkvNestedSeekHeadFixtureIsUnseekableWithPlainMedia3() {
        // Proves the fixture reproduces the reported bug: Media3 1.3.1 never finds the Cues.
        val r = open("$base/chunkedrange/nested.mkv", extractors = PlayerHolder.defaultExtractors())
        assertNull(r.error)
        assertEquals(StreamPolicy.SeekMode.UNSEEKABLE, r.mode)
    }

    @Test fun mkvNestedSeekHeadIsSeekableOverHttpRanges() {
        val r = open("$base/chunkedrange/nested.mkv", seekTo = 6_000)
        assertNull(r.error)
        assertEquals(StreamPolicy.SeekMode.SEEKABLE, r.mode)
        assertTrue("duration ${r.durationMs}", r.durationMs in 9_000..11_000)
        assertTrue("buffered after seek ${r.afterSeekBuffered}", r.afterSeekBuffered >= 9_000)
    }

    @Test fun mkvNormalStaysSeekable() {
        val r = open("$base/chunkedrange/normal.mkv", seekTo = 6_000)
        assertNull(r.error)
        assertEquals(StreamPolicy.SeekMode.SEEKABLE, r.mode)
        assertEquals(StreamPolicy.SeekMode.SEEKABLE, open("$base/plain/normal.mkv", extractors = PlayerHolder.defaultExtractors()).mode)
    }

    @Test fun mkvWithoutCuesFixtureIsUnseekableWithPlainMedia3() {
        val r = open("$base/chunkedrange/nocues.mkv", extractors = PlayerHolder.defaultExtractors())
        assertNull(r.error)
        assertEquals(StreamPolicy.SeekMode.UNSEEKABLE, r.mode)
    }

    @Test fun mkvWithoutCuesIsSeekableOverHttpRanges() {
        val r = open("$base/chunkedrange/nocues.mkv", seekTo = 14_000)
        assertNull(r.error)
        assertEquals(StreamPolicy.SeekMode.SEEKABLE, r.mode)
        assertTrue("duration ${r.durationMs}", r.durationMs in 19_000..21_000)
        assertTrue("buffered after seek ${r.afterSeekBuffered}", r.afterSeekBuffered >= 19_000)
    }

    @Test fun mkvWithoutCuesIsSeekableAsLocalFile() {
        val f = java.io.File.createTempFile("nocues", ".mkv").apply { writeBytes(bytesOf("nocues.mkv")); deleteOnExit() }
        val r = open(android.net.Uri.fromFile(f).toString(), seekTo = 12_000)
        assertNull(r.error)
        assertEquals(StreamPolicy.SeekMode.SEEKABLE, r.mode)
    }

    /** Extractor-level check (no player): where does reading resume after a seek? First sample time after seeking. */
    private class Capture : ExtractorOutput {
        var seekMap: SeekMap? = null
        val times = ArrayList<Long>()
        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
            override fun format(format: Format) {}
            override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                val buf = ByteArray(length); return input.read(buf, 0, length)
            }
            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) { data.skipBytes(length) }
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                if (flags and C.BUFFER_FLAG_KEY_FRAME != 0 || times.isEmpty()) times += timeUs
            }
        }
        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) { this.seekMap = seekMap }
    }

    /** Drives [e] like ProgressiveMediaPeriod: reopens the input on RESULT_SEEK. Returns the number of reopen seeks. */
    private fun drive(e: Extractor, bytes: ByteArray, start: Long, until: () -> Boolean): Int {
        var pos = start
        var seeks = 0
        val holder = PositionHolder()
        while (true) {
            val src = ByteArrayDataSource(bytes)
            src.open(DataSpec.Builder().setUri(android.net.Uri.EMPTY).setPosition(pos).build())
            val input = DefaultExtractorInput(src, pos, bytes.size.toLong())
            while (true) {
                when (e.read(input, holder)) {
                    Extractor.RESULT_CONTINUE -> if (until()) return seeks
                    Extractor.RESULT_SEEK -> { pos = holder.position; seeks++; break }
                    else -> return seeks
                }
            }
            if (seeks > 200) throw AssertionError("too many seeks")
        }
    }

    private fun firstSampleAfterSeek(e: Extractor, bytes: ByteArray, cap: Capture, targetUs: Long): Pair<Long, Int> {
        val point = cap.seekMap!!.getSeekPoints(targetUs).first
        e.seek(point.position, targetUs)
        cap.times.clear()
        val seeks = drive(e, bytes, point.position) { cap.times.isNotEmpty() }
        return cap.times.first() to seeks
    }

    private fun prepared(e: Extractor, bytes: ByteArray): Capture {
        val cap = Capture()
        e.init(cap)
        drive(e, bytes, 0) { cap.seekMap != null && cap.times.isNotEmpty() }
        return cap
    }

    @Test fun velaMatroskaSeeksIntoNestedSeekHeadCues() {
        val bytes = bytesOf("nested.mkv")
        val plain = prepared(MatroskaExtractor(DefaultSubtitleParserFactory()), bytes)
        assertTrue("plain media3 should be unseekable", !plain.seekMap!!.isSeekable)
        val e = VelaMatroskaExtractor(DefaultSubtitleParserFactory())
        val cap = prepared(e, bytes)
        assertTrue(cap.seekMap is ChunkIndex) // the real Cues, not a fallback
        for (t in longArrayOf(2_500_000, 6_000_000, 9_000_000)) {
            val (first, _) = firstSampleAfterSeek(e, bytes, cap, t)
            assertTrue("seek $t landed at $first", first in (t - 1_100_000)..t)
        }
    }

    @Test fun velaMatroskaBinarySearchWithoutCues() {
        val bytes = bytesOf("nocues.mkv")
        assertTrue(!prepared(MatroskaExtractor(DefaultSubtitleParserFactory()), bytes).seekMap!!.isSeekable)
        val e = VelaMatroskaExtractor(DefaultSubtitleParserFactory())
        val cap = prepared(e, bytes)
        val map = cap.seekMap!!
        assertTrue(map.isSeekable)
        assertTrue("duration ${map.durationUs}", map.durationUs in 19_000_000..21_000_000)
        for (t in longArrayOf(1_500_000, 7_300_000, 12_000_000, 17_900_000, 19_900_000)) {
            val (first, seeks) = firstSampleAfterSeek(e, bytes, cap, t)
            assertTrue("seek $t landed at $first", first in (t - 1_100_000)..t)
            assertTrue("seek $t took $seeks reopen seeks", seeks <= 12)
        }
        // Back to the start.
        val (first, _) = firstSampleAfterSeek(e, bytes, cap, 0)
        assertTrue("seek 0 landed at $first", first < 100_000)
    }

    @Test fun velaMatroskaNormalFileUsesCues() {
        val bytes = bytesOf("normal.mkv")
        val e = VelaMatroskaExtractor(DefaultSubtitleParserFactory())
        val cap = prepared(e, bytes)
        assertTrue(cap.seekMap is ChunkIndex)
        val (first, _) = firstSampleAfterSeek(e, bytes, cap, 5_000_000)
        assertTrue("landed at $first", first in 3_900_000..5_000_000)
    }
}
