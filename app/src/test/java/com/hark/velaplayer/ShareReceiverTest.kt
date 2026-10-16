package com.hark.velaplayer

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
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
import org.robolectric.shadows.ShadowToast
import java.io.File

/** v2.20.1 Sharesheet target: parsing, forwarding (URIs + read grant) and manifest resolution. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk = [34])
class ShareReceiverTest {
    private val c: Context = ApplicationProvider.getApplicationContext()
    private val v1 = Uri.parse("content://com.example.files/share/Movie%20One.mp4")
    private val v2 = Uri.parse("content://com.example.files/share/clip.mkv")
    private val v3 = Uri.parse("content://media/external/video/media/42")

    private fun send(type: String) = Intent(Intent.ACTION_SEND).setType(type)

    private fun play(r: ShareIntents.Result): ShareIntents.Result.Play {
        assertTrue("expected Play, got $r", r is ShareIntents.Result.Play)
        return r as ShareIntents.Result.Play
    }

    @Test fun singleStream() {
        val r = play(ShareIntents.parse(c, send("video/mp4").putExtra(Intent.EXTRA_STREAM, v1)))
        assertEquals(listOf(v1), r.uris)
        assertEquals(listOf("Movie One.mp4"), r.titles)
    }

    @Test fun mediaStoreUriWithoutExtensionUsesShareType() {
        // MediaStore ids have no extension; the share's video type decides (resolver type unknown here).
        assertEquals(listOf(v3), play(ShareIntents.parse(c, send("video/*").putExtra(Intent.EXTRA_STREAM, v3))).uris)
    }

    @Test fun multipleStreamsKeepOrder() {
        val i = Intent(Intent.ACTION_SEND_MULTIPLE).setType("video/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(v2, v1, v3))
        val r = play(ShareIntents.parse(c, i))
        assertEquals(listOf(v2, v1, v3), r.uris)
        assertEquals(0, r.skipped)
    }

    @Test fun clipDataOnly() {
        val i = send("video/x-matroska")
        i.clipData = ClipData.newRawUri("", v2)
        assertEquals(listOf(v2), play(ShareIntents.parse(c, i)).uris)
    }

    @Test fun clipDataAndExtraAreNotDuplicated() {
        val i = send("video/mp4").putExtra(Intent.EXTRA_STREAM, v1)
        i.clipData = ClipData.newRawUri("", v1)
        assertEquals(listOf(v1), play(ShareIntents.parse(c, i)).uris)
    }

    @Test fun textWithUrl() {
        val i = send("text/plain").putExtra(Intent.EXTRA_TEXT, "Watch this (https://cdn.example.com/show/ep1/master.m3u8?token=abc).")
        val r = play(ShareIntents.parse(c, i))
        assertEquals("https://cdn.example.com/show/ep1/master.m3u8?token=abc", r.uris.single().toString())
    }

    @Test fun textMediaLinkBeatsPageLink() {
        assertEquals("http://x.example/v.mkv",
            ShareIntents.extractUrl("blog https://site.example/post.html and file http://x.example/v.mkv"))
        assertEquals("https://r2.example/obj/abc123?sig=1", ShareIntents.extractUrl("link: https://r2.example/obj/abc123?sig=1"))
        assertNull(ShareIntents.extractUrl("only a page https://site.example/index.html"))
    }

    @Test fun textWithoutUrlIsRejected() {
        val r = ShareIntents.parse(c, send("text/plain").putExtra(Intent.EXTRA_TEXT, "hello, no link here www-ish"))
        assertEquals(ShareIntents.Result.Reject(ShareIntents.NOTHING_PLAYABLE), r)
    }

    @Test fun unsupportedMimeIsRejected() {
        val r = ShareIntents.parse(c, send("image/png").putExtra(Intent.EXTRA_STREAM, Uri.parse("content://com.example.files/p/photo.png")))
        assertTrue(r is ShareIntents.Result.Reject)
    }

    @Test fun mixedShareSkipsNonVideos() {
        val pdf = Uri.parse("content://com.example.files/d/notes.pdf")
        val i = Intent(Intent.ACTION_SEND_MULTIPLE).setType("video/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(v1, pdf))
        val r = play(ShareIntents.parse(c, i))
        assertEquals(listOf(v1), r.uris); assertEquals(1, r.skipped)
    }

    @Test fun ownPrivateFilesAreNeverAccepted() {
        val f = File(c.filesDir, "vault/secret.mp4")
        val r = ShareIntents.parse(c, send("video/mp4").putExtra(Intent.EXTRA_STREAM, Uri.fromFile(f)))
        assertTrue(r is ShareIntents.Result.Reject)
    }

    @Test fun forwardCarriesUrisAndGrant() {
        val fw = ShareIntents.forward(c, ShareIntents.Result.Play(listOf(v1, v2), listOf("a", "b")))
        assertEquals(PlayerActivity::class.java.name, fw.component?.className)
        assertEquals(arrayListOf(v1.toString(), v2.toString()), fw.getStringArrayListExtra(PlayerActivity.EXTRA_URIS))
        assertEquals(arrayListOf("a", "b"), fw.getStringArrayListExtra(PlayerActivity.EXTRA_TITLES))
        assertEquals(PlayerSession.SOURCE_EXTERNAL, fw.getStringExtra(PlayerSession.EXTRA_SOURCE))
        assertTrue(fw.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertNotNull(fw.clipData); val clip = fw.clipData!!
        assertEquals(2, clip.itemCount)
        assertEquals(v1, clip.getItemAt(0).uri); assertEquals(v2, clip.getItemAt(1).uri)
    }

    @Test fun forwardLinkHasNoGrant() {
        val fw = ShareIntents.forward(c, ShareIntents.Result.Play(listOf(Uri.parse("https://x.example/a.mp4")), listOf("a")))
        assertNull(fw.clipData)
        assertEquals(0, fw.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Test fun activityForwardsAndFinishes() {
        val i = Intent(Intent.ACTION_SEND_MULTIPLE).setType("video/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(v1, v2))
        val a = Robolectric.buildActivity(ShareReceiverActivity::class.java, i).create().get()
        val started = shadowOf(a).nextStartedActivity
        assertEquals(PlayerActivity::class.java.name, started.component?.className)
        assertEquals(2, started.getStringArrayListExtra(PlayerActivity.EXTRA_URIS)?.size)
        assertTrue(started.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(a.isFinishing)
    }

    @Test fun activityToastsWhenNothingPlayable() {
        val a = Robolectric.buildActivity(ShareReceiverActivity::class.java, send("text/plain").putExtra(Intent.EXTRA_TEXT, "no link")).create().get()
        assertNull(shadowOf(a).nextStartedActivity)
        assertEquals(ShareIntents.NOTHING_PLAYABLE, ShadowToast.getTextOfLatestToast())
        assertTrue(a.isFinishing)
    }

    private fun velaTargets(i: Intent) = c.packageManager.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY)
        .filter { it.activityInfo.packageName == c.packageName }.map { it.activityInfo.name }

    @Test fun manifestResolvesSharesToOneComponent() {
        val share = ShareReceiverActivity::class.java.name
        for (t in listOf("video/mp4", "video/x-matroska", "application/x-mpegURL", "application/vnd.apple.mpegurl", "application/dash+xml", "text/plain"))
            assertEquals("SEND $t", listOf(share), velaTargets(Intent(Intent.ACTION_SEND).setType(t)))
        assertEquals(listOf(share), velaTargets(Intent(Intent.ACTION_SEND_MULTIPLE).setType("video/*")))
        assertEquals(listOf(share), velaTargets(Intent(Intent.ACTION_SEND_MULTIPLE).setType("video/mp4")))
        assertEquals(emptyList<String>(), velaTargets(Intent(Intent.ACTION_SEND).setType("image/png")))
        // ACTION_VIEW is unchanged: still the player itself.
        assertEquals(listOf(PlayerActivity::class.java.name),
            velaTargets(Intent(Intent.ACTION_VIEW).setDataAndType(v1, "video/mp4")))
    }
}
