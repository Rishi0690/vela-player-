package com.hark.velaplayer

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * v2.21 Hidden Vault folder state machine (VaultMigration + HiddenFolder) with the File API folder implementation:
 * migration success / failure / crash at every step (idempotent, nothing deleted without a verified copy),
 * collision naming, index + README + .nomedia, recovery after a reinstall.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk = [34], application = android.app.Application::class)
class VaultFolderTest {
    private val c get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var folder: File
    private lateinit var dir: FileHiddenDir

    @Before fun setUp() {
        c.getSharedPreferences("vela_vault", Context.MODE_PRIVATE).edit().clear().commit()
        VaultMigration.privateDir(c).deleteRecursively()
        folder = File(c.cacheDir, "Movies/Vela Hidden").apply { deleteRecursively(); mkdirs() }
        dir = FileHiddenDir(folder)
    }

    private fun bytes(n: Int, seed: Int = 7) = ByteArray(n) { ((it * 31 + seed) and 0xff).toByte() }

    /** A v2.5 private item: its only copy is in files/vault. */
    private fun privateItem(name: String, data: ByteArray, title: String = name.substringBeforeLast('.')): Vault.Item {
        val f = File(VaultMigration.privateDir(c).apply { mkdirs() }, "uuid-$name"); f.writeBytes(data)
        val it = Vault.Item(Uri.fromFile(f).toString(), title, 1234L, Vault.MODE_PRIVATE, orig = "content://media/external/video/media/9",
            origDir = "DCIM/Camera/", origName = name, mime = "video/mp4", size = data.size.toLong())
        Vault.adoptOrphan(c, it)
        return it
    }

    private fun videos() = dir.list().filter { HiddenFolder.isVideoFile(it) }.map { it.name }.sorted()

    // ------------------------------------------------------------------ names

    @Test fun uniqueNamesNeverOverwrite() {
        assertEquals("a.mp4", HiddenFolder.uniqueName("a.mp4", listOf("b.mp4")))
        assertEquals("a (1).mp4", HiddenFolder.uniqueName("a.mp4", listOf("a.mp4")))
        assertEquals("a (2).mp4", HiddenFolder.uniqueName("a.mp4", listOf("A.MP4", "a (1).mp4")))
        assertEquals("a (1).mp4", HiddenFolder.uniqueName("a.mp4", listOf("a.mp4.vela-part")))
        assertEquals("noext (1)", HiddenFolder.uniqueName("noext", listOf("noext")))
        assertEquals("x_y.mp4", HiddenFolder.sanitize("x/y.mp4"))
        assertEquals("hidden.mkv", HiddenFolder.sanitize("..hidden.mkv"))
    }

    // ------------------------------------------------------------------ migration

    @Test fun migrationCopiesVerifiesUpdatesRecordThenDeletesPrivateCopy() {
        val data = bytes(300_000)
        val item = privateItem("clip.mp4", data)
        Vault.savePosition(c, item.uri, 5_000L, 100_000L)
        val res = VaultMigration.migrateAll(c, dir)
        assertEquals(1, res.size)
        val m = res[0].second as VaultMigration.Result.Migrated
        assertEquals(listOf("clip.mp4"), videos())
        assertTrue(File(folder, "clip.mp4").readBytes().contentEquals(data))
        assertFalse("private copy removed only after the verified copy", File(Uri.parse(item.uri).path!!).exists())
        val rec = Vault.items(c).single()
        assertEquals(Vault.MODE_FOLDER, rec.mode)
        assertEquals(m.item.uri, rec.uri)
        assertEquals("uuid-clip.mp4", rec.migratedFrom)
        assertEquals("DCIM/Camera/", rec.origDir)
        assertEquals("resume point moved with the record", 5_000L, Vault.position(c, rec.uri))
        assertTrue("still hidden from the library", Vault.isHidden(c, "content://media/external/video/media/9"))
        // support files
        assertNotNull(dir.find(HiddenFolder.NOMEDIA))
        assertTrue(HiddenFolder.readText(dir, HiddenFolder.README)!!.contains("Movies > Vela Hidden"))
        val idx = HiddenFolder.parseIndex(HiddenFolder.readText(dir, HiddenFolder.INDEX))
        assertEquals("clip.mp4", idx.single().file)
        assertEquals("clip", idx.single().title)
        assertEquals("DCIM/Camera/", idx.single().origDir)
        assertTrue("nothing left to migrate", VaultMigration.pendingMigrations(c).isEmpty())
        assertTrue("idempotent", VaultMigration.migrateAll(c, dir).isEmpty())
    }

    @Test fun collisionGetsANewNameAndExistingFileIsUntouched() {
        val other = bytes(1000, seed = 1)
        File(folder, "clip.mp4").writeBytes(other)
        privateItem("clip.mp4", bytes(2000))
        VaultMigration.migrateAll(c, dir)
        assertEquals(listOf("clip (1).mp4", "clip.mp4"), videos())
        assertTrue(File(folder, "clip.mp4").readBytes().contentEquals(other))
        assertTrue(Vault.items(c).single().uri.endsWith("clip%20(1).mp4"))
    }

    /** Fails writes after [failAfter] bytes (storage full) or corrupts the copy. */
    private class FlakyDir(val d: FileHiddenDir, val failAfter: Long = -1, val corrupt: Boolean = false, val failRename: Boolean = false) : HiddenDir by d {
        override fun openOut(doc: HiddenDir.Doc): OutputStream? {
            val o = d.openOut(doc) ?: return null
            return object : OutputStream() {
                var n = 0L
                override fun write(b: Int) { write(byteArrayOf(b.toByte()), 0, 1) }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (failAfter >= 0 && n + len > failAfter && doc.name.endsWith(HiddenFolder.PART)) throw java.io.IOException("ENOSPC")
                    if (corrupt && doc.name.endsWith(HiddenFolder.PART)) { val x = b.copyOfRange(off, off + len); x[0] = (x[0] + 1).toByte(); o.write(x) } else o.write(b, off, len)
                    n += len
                }
                override fun close() = o.close()
            }
        }
        override fun rename(doc: HiddenDir.Doc, newName: String): HiddenDir.Doc? =
            if (failRename && doc.name.endsWith(HiddenFolder.PART)) null else d.rename(doc, newName)
    }

    @Test fun failuresKeepThePrivateCopyAndRecordAndRetryLater() {
        val data = bytes(400_000)
        val item = privateItem("a.mp4", data)
        for (flaky in listOf(FlakyDir(dir, failAfter = 100_000), FlakyDir(dir, corrupt = true), FlakyDir(dir, failRename = true))) {
            val r = VaultMigration.migrateAll(c, flaky).single().second
            assertTrue("$r", r is VaultMigration.Result.Failed)
            assertTrue(File(Uri.parse(item.uri).path!!).exists())
            assertEquals(Vault.MODE_PRIVATE, Vault.items(c).single().mode)
            assertTrue("no partial or bad copy left", videos().isEmpty())
            assertTrue(dir.list().none { it.name.endsWith(HiddenFolder.PART) })
        }
        // Retry on a healthy folder succeeds.
        assertTrue(VaultMigration.migrateAll(c, dir).single().second is VaultMigration.Result.Migrated)
        assertEquals(listOf("a.mp4"), videos())
    }

    @Test fun crashAfterCopyBeforeRecordUpdateDoesNotCopyTwice() {
        val data = bytes(50_000)
        val item = privateItem("b.mp4", data)
        // State an interrupted run leaves: target remembered, verified file renamed into place, record not switched.
        Vault.replaceItem(c, item.uri, item.copy(migrateTo = "b.mp4"))
        File(folder, "b.mp4").writeBytes(data)
        val r = VaultMigration.migrateAll(c, dir).single().second
        assertTrue(r is VaultMigration.Result.Migrated)
        assertEquals("reused, not copied again", listOf("b.mp4"), videos())
        assertFalse(File(Uri.parse(item.uri).path!!).exists())
    }

    @Test fun crashLeftADifferentFileUnderTheRememberedNameItIsNeverTouched() {
        val item = privateItem("c.mp4", bytes(50_000))
        Vault.replaceItem(c, item.uri, item.copy(migrateTo = "c.mp4"))
        val foreign = bytes(50_000, seed = 99)
        File(folder, "c.mp4").writeBytes(foreign)
        VaultMigration.migrateAll(c, dir)
        assertTrue(File(folder, "c.mp4").readBytes().contentEquals(foreign))
        assertEquals(listOf("c (1).mp4", "c.mp4"), videos())
    }

    @Test fun crashAfterRecordUpdateBeforeDeleteIsFinishedByReconcileOnlyWhenTheCopyIsThere() {
        val data = bytes(10_000)
        val pf = File(VaultMigration.privateDir(c).apply { mkdirs() }, "uuid-d.mp4").apply { writeBytes(data) }
        val copy = File(folder, "d.mp4").apply { writeBytes(data) }
        Vault.adoptAll(c, listOf(Vault.Item(Uri.fromFile(copy).toString(), "d", 1L, Vault.MODE_FOLDER, migratedFrom = pf.name, size = data.size.toLong())))
        // Copy size mismatch → keep the private file.
        copy.writeBytes(bytes(9_000))
        VaultMigration.reconcile(c, dir)
        assertTrue(pf.exists())
        // Copy present with the right size → finish the migration.
        copy.writeBytes(data)
        VaultMigration.reconcile(c, dir)
        assertFalse(pf.exists())
        // VaultStorage.reconcile never adopts a migrated private file as a "Recovered video".
        assertEquals(1, Vault.items(c).size)
    }

    @Test fun reconcileWithoutFolderAccessDeletesNothing() {
        val pf = File(VaultMigration.privateDir(c).apply { mkdirs() }, "uuid-e.mp4").apply { writeBytes(bytes(100)) }
        Vault.adoptAll(c, listOf(Vault.Item(Uri.fromFile(File(folder, "e.mp4")).toString(), "e", 1L, Vault.MODE_FOLDER, migratedFrom = pf.name)))
        VaultMigration.reconcile(c, null)
        assertTrue(pf.exists())
    }

    // ------------------------------------------------------------------ recovery after reinstall / clear data

    @Test fun recoverReadoptsEveryVideoWithIndexTitles() {
        File(folder, "one.mp4").writeBytes(bytes(10))
        File(folder, "two.mkv").writeBytes(bytes(20))
        File(folder, "half.mp4" + HiddenFolder.PART).writeBytes(bytes(5))
        HiddenFolder.writeSupportFiles(dir, listOf(HiddenFolder.IndexEntry("one.mp4", "Holiday", "DCIM/Camera/", "VID_1.mp4", 99L, 10L, "video/mp4")))
        // Fresh install: no records.
        assertEquals(2, VaultMigration.adopt(c, dir))
        val items = Vault.items(c).sortedBy { it.title }
        assertEquals(listOf("Holiday", "two"), items.map { it.title })
        assertEquals("DCIM/Camera/", items[0].origDir)
        assertEquals("video/x-matroska", items[1].mime)
        assertTrue(items.all { it.isFolder })
        assertEquals("second run finds nothing new", 0, VaultMigration.adopt(c, dir))
        assertEquals(2, HiddenFolder.parseIndex(HiddenFolder.readText(dir, HiddenFolder.INDEX)).size)
    }

    @Test fun indexWriteIsAtomicAndReadable() {
        assertTrue(HiddenFolder.writeSupportFiles(dir, emptyList()))
        assertTrue(HiddenFolder.writeText(dir, HiddenFolder.INDEX, "application/json", HiddenFolder.indexJson(listOf(
            HiddenFolder.IndexEntry("x.mp4", "X", null, null, 1L, 2L, null)))))
        assertNull(dir.find(HiddenFolder.INDEX + ".tmp"))
        assertEquals("X", HiddenFolder.parseIndex(HiddenFolder.readText(dir, HiddenFolder.INDEX)).single().title)
        assertTrue(HiddenFolder.parseIndex("not json").isEmpty())
    }

    @Test fun copyInVerifiesAgainstTheSourceHash() {
        val data = bytes(70_000)
        val ok = HiddenFolder.copyIn(dir, "v.mp4", "video/mp4", { data.inputStream() }, data.size.toLong(), HiddenFolder.sha256(data.inputStream()))
        assertTrue(ok is HiddenFolder.Copy.Ok)
        val bad = HiddenFolder.copyIn(dir, "w.mp4", "video/mp4", { data.inputStream() }, data.size.toLong(), "0".repeat(64))
        assertTrue(bad is HiddenFolder.Copy.Fail)
        val short = HiddenFolder.copyIn(dir, "w.mp4", "video/mp4", { data.copyOf(10).inputStream() as InputStream }, data.size.toLong(), null)
        assertTrue(short is HiddenFolder.Copy.Fail)
        assertEquals(listOf("v.mp4"), videos())
    }
}
