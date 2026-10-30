package com.hark.velaplayer

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * v2.21 state machine for the Hidden Vault folder (no UI, no system dialogs; unit-tested with [FileHiddenDir]).
 *
 * Migration of a v2.5 private item (only copy in files/vault/, deleted by uninstall) to the folder:
 *  1. pick a free name in the folder and REMEMBER it in the record (`migrateTo`) before copying;
 *  2. copy through `<name>.vela-part`, verify byte count + SHA-256 of the source AND of the written copy, rename;
 *  3. swap the record to MODE_FOLDER (new uri, `migratedFrom` = private file name; resume/watched keys moved);
 *  4. rewrite the folder index;
 *  5. only now delete the private copy.
 * Any failure before step 3 leaves the record and the private copy exactly as they were (retried next time).
 * A crash after step 2 is resumed without a second copy (the remembered file is re-verified by hash);
 * a crash after step 3 is finished by [reconcile] (private copy deleted only when the folder file is there
 * with the same size). Nothing is ever deleted unless a verified copy exists.
 */
object VaultMigration {
    sealed class Result {
        data class Migrated(val item: Vault.Item) : Result()
        data class Failed(val why: String) : Result()
        object Skipped : Result()
    }

    fun privateDir(c: Context) = File(c.applicationContext.filesDir, "vault")
    fun privateFile(i: Vault.Item): File? = if (i.isPrivate) Uri.parse(i.uri).path?.let { File(it) } else null

    fun pendingMigrations(c: Context) = Vault.items(c).filter { it.isPrivate && !it.pending }

    fun migrateAll(c: Context, dir: HiddenDir, progress: (Int, Int) -> Unit = { _, _ -> }): List<Pair<Vault.Item, Result>> {
        val todo = pendingMigrations(c)
        if (todo.isEmpty()) return emptyList()
        HiddenFolder.writeSupportFiles(dir, null)
        return todo.mapIndexed { i, it -> progress(i, todo.size); it to migrateOne(c, dir, it) }
    }

    fun migrateOne(c: Context, dir: HiddenDir, item: Vault.Item): Result {
        if (!item.isPrivate) return Result.Skipped
        val src = privateFile(item) ?: return Result.Failed("the private copy is missing")
        if (!src.isFile) return Result.Failed("the private copy is missing")
        val size = src.length()
        val hash = try { HiddenFolder.sha256(src.inputStream()) } catch (_: Exception) { return Result.Failed("the private copy couldn't be read") }
        if (!HiddenFolder.writeSupportFiles(dir, null)) return Result.Failed("couldn't prepare the hidden folder")

        // 1. target name, remembered before anything is written
        val existing = dir.list()
        var target = item.migrateTo
        var done: HiddenDir.Doc? = null
        if (target != null) {
            val there = existing.firstOrNull { it.name == target }
            if (there != null) {
                // An earlier run got this far: reuse it only if it is byte-identical; otherwise never touch it.
                val sameSize = there.size == size || dir.sizeOf(there.uri) == size
                val same = sameSize && (dir.openIn(there)?.let { HiddenFolder.sha256(it) } == hash)
                if (same) done = there else target = null
            }
        }
        if (target == null) {
            target = HiddenFolder.uniqueName(item.origName ?: (item.title + "." + (src.extension.ifBlank { "mp4" })), existing.map { it.name })
            if (!Vault.replaceItem(c, item.uri, item.copy(migrateTo = target))) return Result.Failed("the vault record changed")
        }
        // 2. copy + verify
        if (done == null) {
            when (val r = HiddenFolder.copyIn(dir, target, item.mime ?: "video/mp4", { src.inputStream() }, size, hash)) {
                is HiddenFolder.Copy.Ok -> done = r.doc
                is HiddenFolder.Copy.Fail -> return Result.Failed(r.why)
            }
        }
        val doc = done!!
        // 3. record → folder item (keys for resume/watched/order move with the uri)
        val rec = item.copy(uri = doc.uri.toString(), mode = Vault.MODE_FOLDER, size = size, pending = false,
            migrateTo = null, migratedFrom = src.name)
        if (!Vault.replaceItem(c, item.uri, rec)) return Result.Failed("the vault record changed")
        // 4. index
        writeIndex(c, dir)
        // 5. the verified folder copy exists and the record points at it: the private copy can go
        src.delete()
        return Result.Migrated(rec)
    }

    fun writeIndex(c: Context, dir: HiddenDir): Boolean =
        HiddenFolder.writeSupportFiles(dir, HiddenFolder.entriesOf(Vault.items(c), dir))

    /**
     * Startup check (off the main thread). Finishes an interrupted migration cleanup: a private file whose folder
     * record (`migratedFrom`) exists AND whose folder copy is present with the same size is removed. Every other
     * private file without a record is adopted as before (never deleted).
     */
    fun reconcile(c: Context, dir: HiddenDir?) {
        val items = Vault.items(c)
        val pdir = privateDir(c); if (!pdir.isDirectory) return
        val migrated = items.filter { it.isFolder && it.migratedFrom != null }.associateBy { it.migratedFrom!! }
        val known = items.filter { it.isPrivate }.mapNotNull { privateFile(it)?.name }.toHashSet()
        pdir.listFiles()?.forEach { f ->
            if (f.name == ".nomedia" || f.name.endsWith(".part") || f.name in known) return@forEach
            val rec = migrated[f.name]
            if (rec != null) {
                val sz = dir?.sizeOf(Uri.parse(rec.uri)) ?: -1L
                if (sz >= 0 && sz == f.length()) f.delete()
            }
        }
    }

    /**
     * "Recover hidden videos": every video in the folder that has no vault record becomes a folder item again,
     * with its title / original folder from the index when listed there. Returns how many were added.
     */
    fun adopt(c: Context, dir: HiddenDir): Int {
        val index = HiddenFolder.parseIndex(HiddenFolder.readText(dir, HiddenFolder.INDEX)).associateBy { it.file }
        val have = Vault.items(c).map { it.uri }.toHashSet()
        val now = System.currentTimeMillis()
        val recs = dir.list().filter { HiddenFolder.isVideoFile(it) && it.uri.toString() !in have }.map { d ->
            val e = index[d.name]
            Vault.Item(d.uri.toString(), e?.title ?: d.name.substringBeforeLast('.').ifEmpty { d.name }, e?.hiddenAt?.takeIf { it > 0 } ?: now,
                Vault.MODE_FOLDER, origDir = e?.origDir, origName = e?.origName ?: d.name,
                mime = e?.mime ?: mimeOf(d.name), size = d.size)
        }
        val n = Vault.adoptAll(c, recs)
        writeIndex(c, dir)
        return n
    }

    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mkv" -> "video/x-matroska"; "webm" -> "video/webm"; "3gp" -> "video/3gpp"; "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"; "ts" -> "video/mp2t"; "flv" -> "video/x-flv"; "wmv" -> "video/x-ms-wmv"
        else -> "video/mp4"
    }
}
