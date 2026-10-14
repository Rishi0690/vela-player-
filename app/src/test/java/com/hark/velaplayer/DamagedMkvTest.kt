package com.hark.velaplayer

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.metadata.MetadataRenderer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.test.core.app.ApplicationProvider
import com.hark.velaplayer.mkv.VelaMatroskaExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile

/**
 * v2.19.3: MKV content named `.mp4` and tail-/index-damaged MKVs (fixtures from tools/mkvfixtures/damage.py).
 * The reported file failed with "ERROR_CODE_IO_UNSPECIFIED (2000) UnexpectedLoaderException: Unexpected
 * ArrayIndexOutOfBoundsException: src.length=65536 srcPos=-1643236477 dst.length=65536 dstPos=0 length=1643236477"
 * = Media3's DefaultEbmlReader skipFully((int) size) with size 2,651,730,819 wrapped negative.
 * Each case: plain Media3 1.3.1 fails (proving the fixture reproduces it), the app's factory plays and seeks.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
@Config(sdk = [34], application = android.app.Application::class)
class DamagedMkvTest {

    private fun bytesOf(name: String): ByteArray = javaClass.classLoader!!.getResourceAsStream("streams/$name")!!.use { it.readBytes() }

    private fun fileOf(name: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "vela-damaged").apply { mkdirs() }
        return File(dir, name).apply { writeBytes(bytesOf(name)); deleteOnExit() }
    }

    // ------------------------------------------------------------------ real ExoPlayer (no decoders)

    private class Result(val error: PlaybackException?, val seekable: Boolean, val durationMs: Long, val ready: Boolean,
                         val loadedMs: Long, val afterSeekMs: Long)

    /** Prepares [uri], waits until the whole (small) file is loaded or an error, optionally seeks. */
    private fun play(uri: String, extractors: ExtractorsFactory = PlayerHolder.extractors(), seekTo: Long = -1, waitMs: Long = 15_000): Result {
        val c = ApplicationProvider.getApplicationContext<android.content.Context>()
        val p = ExoPlayer.Builder(c, RenderersFactory { h, _, _, _, m -> arrayOf(MetadataRenderer(m, h.looper)) })
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(c, PlayerHolder.httpFactory()), extractors))
            .build()
        var err: PlaybackException? = null
        p.addListener(object : Player.Listener { override fun onPlayerError(error: PlaybackException) { err = error } })
        p.setMediaItem(MediaItem.fromUri(uri)); p.prepare()
        val main = shadowOf(Looper.getMainLooper())
        fun placeholder() = p.currentTimeline.isEmpty || p.currentTimeline.getWindow(0, Timeline.Window()).isPlaceholder
        fun waitFor(ms: Long, done: () -> Boolean) {
            val end = System.currentTimeMillis() + ms
            // idleFor advances Robolectric's SystemClock: ExoPlayer's doSomeWork and the loader's retry backoff are
            // delayed messages that never run while the fake clock stands still.
            while (System.currentTimeMillis() < end && err == null && !done()) { main.idleFor(java.time.Duration.ofMillis(50)); Thread.sleep(10) }
            main.idle()
        }
        waitFor(waitMs) { p.playbackState == Player.STATE_READY && !placeholder() && !p.isLoading }
        val ready = p.playbackState == Player.STATE_READY
        val seekable = p.isCurrentMediaItemSeekable
        val dur = p.duration
        val loaded = p.bufferedPosition
        var after = -1L
        if (seekTo >= 0 && err == null && ready) {
            p.seekTo(seekTo)
            waitFor(5_000) { p.playbackState == Player.STATE_READY && !p.isLoading }
            after = if (p.playbackState == Player.STATE_READY) p.currentPosition else -1
        }
        val e = err
        p.release(); main.idle()
        return Result(e, seekable, if (dur == C.TIME_UNSET) -1 else dur, ready, loaded, after)
    }

    private fun fileUri(name: String) = Uri.fromFile(fileOf(name)).toString()

    private fun assertPlays(r: Result, seekable: Boolean = true, minLoadedMs: Long = 9_000) {
        assertNull("error: ${r.error?.let { errorText(it) }}", r.error)
        assertTrue("not ready", r.ready)
        assertEquals("seekable", seekable, r.seekable)
        assertTrue("duration ${r.durationMs}", r.durationMs in 9_000..11_000)
        assertTrue("loaded only to ${r.loadedMs}", r.loadedMs >= minLoadedMs)
    }

    private fun errorText(e: PlaybackException): String {
        val sb = StringBuilder("${e.errorCodeName}")
        var c: Throwable? = e.cause
        while (c != null) { sb.append(" / ").append(c.javaClass.simpleName).append(": ").append(c.message); c = c.cause.takeIf { it !== c } }
        return sb.toString()
    }

    private fun rootCause(e: Throwable): Throwable { var c = e; while (c.cause != null && c.cause !== c) c = c.cause!!; return c }

    // ---- 1. MKV content named .mp4

    @Test fun mkvNamedMp4PlaysAndSeeksAsFile() {
        val r = play(fileUri("normal.mp4"), seekTo = 6_000)
        assertPlays(r)
        assertTrue("after seek ${r.afterSeekMs}", r.afterSeekMs in 5_000..7_000)
        // Plain Media3 also sniffs it correctly (the MP4 extractor is tried first and rejects it).
        assertPlays(play(fileUri("normal.mp4"), PlayerHolder.defaultExtractors()))
    }

    @Test fun mkvNamedMp4PlaysThroughTheMp4RepairFactory() {
        assertPlays(play(fileUri("normal.mp4"), PlayerHolder.extractors(repair = true), seekTo = 6_000))
    }

    @Test fun mkvNamedMp4PlaysFromAContentUriServedAsVideoMp4() {
        // Like MediaStore: content://… without an extension, provider says video/mp4.
        TestFiles.dir = File(System.getProperty("java.io.tmpdir"), "vela-damaged")
        fileOf("normal.mp4"); fileOf("staleptr.mp4")
        Robolectric.setupContentProvider(TestFiles::class.java, TestFiles.AUTHORITY)
        assertPlays(play("content://${TestFiles.AUTHORITY}/normal.mp4", seekTo = 6_000))
        // and the reported crash case through ContentDataSource
        val plain = play("content://${TestFiles.AUTHORITY}/staleptr.mp4", PlayerHolder.defaultExtractors())
        assertNotNull(plain.error)
        assertEquals(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, plain.error!!.errorCode)
        assertPlays(play("content://${TestFiles.AUTHORITY}/staleptr.mp4", seekTo = 6_000))
    }

    // ---- 2. The reported crash: a huge (> 2^31) element where the extractor reads after a seek

    @Test fun staleCuesTargetReproducesTheReportedCrashWithPlainMedia3() {
        val r = play(fileUri("staleptr.mp4"), PlayerHolder.defaultExtractors())
        val e = r.error
        assertNotNull("plain media3 should fail", e)
        assertEquals(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, e!!.errorCode)
        val root = rootCause(e)
        assertTrue("root ${root.javaClass} ${root.message}", root is ArrayIndexOutOfBoundsException)
        // The user's numbers (ART prints "src.length=65536 srcPos=-1643236477 … length=1643236477"; the JVM
        // prints "arraycopy: source index -1643236477 out of bounds for byte[65536]").
        assertTrue(root.message, root.message!!.contains("-1643236477") && root.message!!.contains("65536"))
    }

    @Test fun staleCuesTargetPlaysAndSeeksWithVela() {
        val r = play(fileUri("staleptr.mp4"), seekTo = 6_000)
        assertPlays(r)
        assertTrue("after seek ${r.afterSeekMs}", r.afterSeekMs in 5_000..7_000)
    }

    @Test fun leadingJunkBeforeEbmlHeader() {
        val plain = play(fileUri("leadjunk.mp4"), PlayerHolder.defaultExtractors())
        assertNotNull("plain media3 should fail", plain.error)
        assertEquals(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, plain.error!!.errorCode)
        assertTrue(rootCause(plain.error!!) is ArrayIndexOutOfBoundsException)
        val r = play(fileUri("leadjunk.mp4"), seekTo = 6_000)
        assertPlays(r)
    }

    @Test fun legitimateHugeVoidBeforeTheFirstCluster_sparse2_65GB() {
        val f = File(System.getProperty("java.io.tmpdir"), "vela-damaged/bigvoid.mkv").apply { parentFile!!.mkdirs(); delete(); deleteOnExit() }
        val head = bytesOf("bigvoid.head"); val tail = bytesOf("bigvoid.tail")
        RandomAccessFile(f, "rw").use { it.write(head); it.seek(head.size + 2_651_730_819L); it.write(tail) }
        try {
            val plain = play(Uri.fromFile(f).toString(), PlayerHolder.defaultExtractors())
            assertNotNull("plain media3 should fail", plain.error)
            assertEquals(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, plain.error!!.errorCode)
            assertTrue(rootCause(plain.error!!) is ArrayIndexOutOfBoundsException)
            val r = play(Uri.fromFile(f).toString(), seekTo = 6_000)
            assertPlays(r) // the Void is skipped with one seek; no Cues -> cluster binary search
            assertTrue("after seek ${r.afterSeekMs}", r.afterSeekMs in 5_000..7_000)
        } finally { f.delete() }
    }

    // ---- 3. Tail / index damage

    @Test fun cuesCutOffMidElement() {
        val plain = play(fileUri("cutcues.mp4"), PlayerHolder.defaultExtractors())
        assertNotNull("plain media3 should fail", plain.error)
        assertEquals(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, plain.error!!.errorCode) // EOFException after retries
        assertPlays(play(fileUri("cutcues.mp4"), seekTo = 6_000))
    }

    @Test fun cuesZeroedOut() {
        val plain = play(fileUri("zerocues.mp4"), PlayerHolder.defaultExtractors())
        assertNotNull("plain media3 should fail", plain.error)
        assertEquals(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, plain.error!!.errorCode)
        assertPlays(play(fileUri("zerocues.mp4"), seekTo = 6_000))
    }

    @Test fun cuesPointerIntoClusterData() {
        val plain = play(fileUri("badcuesptr.mp4"), PlayerHolder.defaultExtractors(), waitMs = 10_000)
        assertTrue("plain media3 should not play it", plain.error != null || !plain.ready)
        assertPlays(play(fileUri("badcuesptr.mp4"), seekTo = 6_000))
    }

    @Test fun cuesPointerPastEndOfFile() {
        val plain = play(fileUri("seekpast.mp4"), PlayerHolder.defaultExtractors())
        assertNotNull("plain media3 should fail", plain.error)
        assertPlays(play(fileUri("seekpast.mp4"), seekTo = 6_000))
    }

    @Test fun truncatedDownload() {
        // Cut at 60 %: Cues listed but gone, last Cluster cut. Plays what exists, seeks (binary search).
        val plain = play(fileUri("truncated.mp4"), PlayerHolder.defaultExtractors())
        assertNotNull("plain media3 should fail", plain.error)
        val r = play(fileUri("truncated.mp4"), seekTo = 3_000)
        assertNull("error: ${r.error?.let { errorText(it) }}", r.error)
        assertTrue(r.ready); assertTrue(r.seekable)
        assertTrue("after seek ${r.afterSeekMs}", r.afterSeekMs in 2_000..4_000)
    }

    // ------------------------------------------------------------------ extractor level (whole file, no player)

    private class Run(var seekMap: SeekMap? = null, var samples: Int = 0, var lastUs: Long = -1, var error: Throwable? = null)

    private fun runExtractor(e: Extractor, bytes: ByteArray): Run {
        val run = Run()
        e.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
                override fun format(format: Format) {}
                override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                    // Like SampleQueue: an end of input mid-sample is an EOFException unless allowed.
                    val buf = ByteArray(length); val n = input.read(buf, 0, length)
                    if (n == C.RESULT_END_OF_INPUT) { if (allowEndOfInput) return n; throw java.io.EOFException() }
                    return n
                }
                override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) { data.skipBytes(length) }
                override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                    run.samples++; run.lastUs = maxOf(run.lastUs, timeUs)
                }
            }
            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) { run.seekMap = seekMap }
        })
        var pos = 0L
        val holder = PositionHolder()
        var seeks = 0
        try {
            outer@ while (true) {
                val src = ByteArrayDataSource(bytes)
                src.open(DataSpec.Builder().setUri(Uri.EMPTY).setPosition(pos).build())
                val input = DefaultExtractorInput(src, pos, bytes.size.toLong())
                while (true) {
                    when (e.read(input, holder)) {
                        Extractor.RESULT_CONTINUE -> {}
                        Extractor.RESULT_SEEK -> { pos = holder.position; if (++seeks > 100) throw AssertionError("seek loop"); continue@outer }
                        else -> break@outer
                    }
                }
            }
        } catch (t: Throwable) { if (t is AssertionError) throw t; run.error = t }
        return run
    }

    private fun vela(local: Boolean = true) = VelaMatroskaExtractor(DefaultSubtitleParserFactory(),
        if (local) VelaMatroskaExtractor.FLAG_LOCAL_INPUT else 0)

    @Test fun fileCutInsideTheLastCluster_endsNormallyWhenLocal() {
        val bytes = bytesOf("cuttail.mp4")
        val plain = runExtractor(MatroskaExtractor(DefaultSubtitleParserFactory()), bytes)
        assertTrue("plain: ${plain.error}", plain.error is java.io.EOFException) // -> ERROR_CODE_IO_UNSPECIFIED
        val local = runExtractor(vela(local = true), bytes)
        assertNull("vela: ${local.error}", local.error)
        assertTrue("last sample ${local.lastUs}", local.lastUs >= 8_500_000)
        assertTrue(local.seekMap!!.isSeekable)
        // Network inputs keep Media3's behaviour (EOF -> retry/error), no silent early end.
        assertTrue(runExtractor(vela(local = false), bytes).error is java.io.EOFException)
    }

    @Test fun garbageHugeElementMidFile_isResyncedNotCrashing() {
        val bytes = bytesOf("hugeelem.mp4")
        val plain = runExtractor(MatroskaExtractor(DefaultSubtitleParserFactory()), bytes)
        assertTrue("plain: ${plain.error}", plain.error is ArrayIndexOutOfBoundsException)
        val run = runExtractor(vela(), bytes)
        assertNull("vela: ${run.error}", run.error)
        assertTrue("last sample ${run.lastUs}", run.lastUs >= 9_000_000) // continued after the bad Cluster
    }

    @Test fun everyDamagedFixtureReadsToTheEndWithVela() {
        for (name in listOf("normal.mp4", "cutcues.mp4", "zerocues.mp4", "badcuesptr.mp4", "seekpast.mp4", "staleptr.mp4",
                "leadjunk.mp4", "hugeelem.mp4", "truncated.mp4", "cuttail.mp4", "normal.mkv", "nested.mkv", "nocues.mkv")) {
            val run = runExtractor(vela(), bytesOf(name))
            assertNull("$name: ${run.error}", run.error)
            assertTrue("$name seekable", run.seekMap!!.isSeekable)
            assertTrue("$name samples ${run.samples}", run.samples > 50)
        }
    }

    @Test fun localUriDetection() {
        assertTrue(VelaExtractorsFactory.isLocal(Uri.parse("content://media/external/video/media/12")))
        assertTrue(VelaExtractorsFactory.isLocal(Uri.parse("file:///sdcard/a.mp4")))
        assertTrue(VelaExtractorsFactory.isLocal(Uri.parse("/sdcard/a.mp4")))
        assertTrue(!VelaExtractorsFactory.isLocal(Uri.parse("https://x/y.mkv")))
        assertTrue(!VelaExtractorsFactory.isLocal(Uri.parse("http://x/y.mkv")))
    }
}

/** Serves files from [dir] like MediaStore: no extension in the logic, type video/mp4. */
class TestFiles : ContentProvider() {
    companion object { const val AUTHORITY = "com.hark.velaplayer.testfiles"; var dir: File? = null }
    override fun onCreate() = true
    override fun getType(uri: Uri) = "video/mp4"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(File(dir, uri.lastPathSegment!!), ParcelFileDescriptor.MODE_READ_ONLY)
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
