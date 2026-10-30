package com.hark.velaplayer

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * v2.21 Hidden Vault folder: `Movies/Vela Hidden/` in the phone's shared storage.
 *
 * Why: the v2.5 "private storage" mode kept the ONLY copy of a hidden video inside Vela's app storage, which Android
 * deletes on uninstall / Clear data. Shared storage is never touched by uninstall, so hidden videos now live here:
 * - `.nomedia` makes MediaStore (Android 11+: MEDIA_TYPE_NONE, out of every Video/Images query) and the legacy media
 *   scanner (Android 10 and older) skip the folder, so Gallery apps that use MediaStore don't list the videos;
 * - `vela-vault-index.json` lists every hidden file with its title and original folder (a reinstalled Vela re-adopts
 *   them through "Recover hidden videos");
 * - `README - how to unhide.txt` tells a person with only a file manager how to get the videos back.
 *
 * Access: Android 10 and older use the File API (WRITE_EXTERNAL_STORAGE, legacy storage). Android 11+ use the Storage
 * Access Framework: the user grants this ONE folder once (system folder picker); the grant is persisted. Through it
 * Vela can read/write/rename files there even though the files were made by other apps (camera, downloads) and even
 * after MediaStore stopped listing them.
 */
interface HiddenDir {
    data class Doc(val name: String, val uri: Uri, val size: Long, val isDir: Boolean = false)

    /** Uri of the folder itself (tree uri on 11+, file:// below) — for display/diagnostics. */
    val uri: Uri
    fun list(): List<Doc>
    fun find(name: String): Doc? = list().firstOrNull { it.name == name }
    /** Creates an EMPTY new file with exactly [name] (fails / returns null rather than overwrite). */
    fun create(name: String, mime: String): Doc?
    fun openIn(doc: Doc): InputStream?
    fun openOut(doc: Doc): OutputStream?
    /** Renames [doc] to [newName] (no overwrite). Returns the renamed doc, or null on failure. */
    fun rename(doc: Doc, newName: String): Doc?
    fun delete(doc: Doc): Boolean
    /** Size of an existing document uri in this folder, or -1 when it doesn't exist. */
    fun sizeOf(uri: Uri): Long
}

/** File API folder (Android 10 and older, and unit tests). */
class FileHiddenDir(val dir: File) : HiddenDir {
    override val uri: Uri get() = Uri.fromFile(dir)
    private fun doc(f: File) = HiddenDir.Doc(f.name, Uri.fromFile(f), if (f.isFile) f.length() else 0L, f.isDirectory)
    override fun list(): List<HiddenDir.Doc> = (dir.listFiles() ?: emptyArray()).map { doc(it) }
    override fun create(name: String, mime: String): HiddenDir.Doc? {
        if (!dir.exists() && !dir.mkdirs()) return null
        val f = File(dir, name)
        return try { if (f.createNewFile()) doc(f) else null } catch (_: Exception) { null }
    }
    override fun openIn(doc: HiddenDir.Doc): InputStream? = try { FileInputStream(File(doc.uri.path!!)) } catch (_: Exception) { null }
    override fun openOut(doc: HiddenDir.Doc): OutputStream? = try { FileOutputStream(File(doc.uri.path!!)) } catch (_: Exception) { null }
    override fun rename(doc: HiddenDir.Doc, newName: String): HiddenDir.Doc? {
        val src = File(doc.uri.path ?: return null); val dst = File(dir, newName)
        if (dst.exists()) return null
        return if (src.renameTo(dst)) doc(dst) else null
    }
    override fun delete(doc: HiddenDir.Doc): Boolean { val p = doc.uri.path ?: return false; return File(p).delete() }
    override fun sizeOf(uri: Uri): Long { val f = File(uri.path ?: return -1); return if (f.isFile) f.length() else -1 }
}

/** Storage Access Framework folder (Android 11+): a persisted tree grant on Movies/Vela Hidden. */
class SafHiddenDir(private val cr: ContentResolver, private val tree: Uri) : HiddenDir {
    override val uri: Uri get() = tree
    private val treeDocId = DocumentsContract.getTreeDocumentId(tree)
    private val dirDoc = DocumentsContract.buildDocumentUriUsingTree(tree, treeDocId)

    override fun list(): List<HiddenDir.Doc> {
        val out = ArrayList<HiddenDir.Doc>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeDocId)
        try {
            cr.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    out += HiddenDir.Doc(c.getString(1) ?: id.substringAfterLast('/'), DocumentsContract.buildDocumentUriUsingTree(tree, id),
                        if (c.isNull(2)) 0L else c.getLong(2), c.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR)
                }
            }
        } catch (_: Exception) { }
        return out
    }

    override fun create(name: String, mime: String): HiddenDir.Doc? {
        if (find(name) != null) return null
        val u = try { DocumentsContract.createDocument(cr, dirDoc, mime, name) } catch (_: Exception) { null } ?: return null
        val made = list().firstOrNull { it.uri == u } ?: HiddenDir.Doc(name, u, 0L)
        // The provider may adjust the name (extension from the MIME type, " (1)" on a clash): put it back exactly or give up.
        if (made.name != name) {
            val fixed = rename(made, name)
            if (fixed == null) { delete(made); return null }
            return fixed
        }
        return made
    }

    override fun openIn(doc: HiddenDir.Doc): InputStream? = try { cr.openInputStream(doc.uri) } catch (_: Exception) { null }
    override fun openOut(doc: HiddenDir.Doc): OutputStream? = try { cr.openOutputStream(doc.uri, "wt") ?: cr.openOutputStream(doc.uri, "w") } catch (_: Exception) { null }
    override fun rename(doc: HiddenDir.Doc, newName: String): HiddenDir.Doc? {
        if (find(newName) != null) return null
        val u = try { DocumentsContract.renameDocument(cr, doc.uri, newName) } catch (_: Exception) { null } ?: doc.uri
        return list().firstOrNull { it.uri == u && it.name == newName } ?: list().firstOrNull { it.name == newName }
    }
    override fun delete(doc: HiddenDir.Doc): Boolean = try { DocumentsContract.deleteDocument(cr, doc.uri) } catch (_: Exception) { false }
    override fun sizeOf(uri: Uri): Long = try {
        cr.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else -1L } ?: -1L
    } catch (_: Exception) { -1L }
}

object HiddenFolder {
    const val NAME = "Vela Hidden"
    const val RELATIVE_PATH = "Movies/Vela Hidden/"
    const val NOMEDIA = ".nomedia"
    const val INDEX = "vela-vault-index.json"
    const val README = "README - how to unhide.txt"
    const val PART = ".vela-part"
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val PRIMARY_DOC_ID = "primary:Movies/Vela Hidden"

    val usesSaf get() = Build.VERSION.SDK_INT >= 30

    /** The folder as a File (always the same place; on 11+ only used to create it / check that it exists). */
    @Suppress("DEPRECATION")
    fun file(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), NAME)

    /** Where the folder picker should open (Android 8+). */
    fun initialPickerUri(): Uri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, PRIMARY_DOC_ID)

    /** Is [tree] the Vela Hidden folder (or, for [recovery], any folder that holds a Vela index)? */
    fun isOurTree(cr: ContentResolver, tree: Uri, recovery: Boolean = false): Boolean {
        val id = try { DocumentsContract.getTreeDocumentId(tree) } catch (_: Exception) { return false }
        if (tree.authority == EXTERNAL_STORAGE_AUTHORITY && id.substringAfter(':').trimEnd('/').equals(PRIMARY_DOC_ID.substringAfter(':'), true)) return true
        if (id.trimEnd('/').substringAfterLast('/').substringAfterLast(':').equals(NAME, true)) return true
        return recovery && SafHiddenDir(cr, tree).find(INDEX) != null
    }

    /** The persisted grant for the folder (Android 11+), or null. */
    fun grantedTree(c: Context): Uri? = try {
        c.contentResolver.persistedUriPermissions.firstOrNull { it.isReadPermission && it.isWritePermission && isOurTree(c.contentResolver, it.uri) }?.uri
    } catch (_: Exception) { null }

    fun takeGrant(c: Context, tree: Uri) {
        try { c.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) { }
    }

    /** Opens the folder if Vela may use it now; null = needs the folder grant (11+) or the storage permission (10-). */
    fun open(c: Context): HiddenDir? {
        if (usesSaf) return grantedTree(c)?.let { SafHiddenDir(c.contentResolver, it) }
        val f = file()
        if (!f.exists() && !f.mkdirs()) return null
        return if (f.canWrite()) FileHiddenDir(f) else null
    }

    /** Best effort: make the folder so the picker can open right there. Harmless when it already exists. */
    fun ensureDirExists(): Boolean = try { val f = file(); f.isDirectory || f.mkdirs() } catch (_: Exception) { false }

    /** Is there a Vela Hidden folder on the phone (e.g. from an earlier install)? */
    fun looksPresent(): Boolean = try { file().isDirectory } catch (_: Exception) { false }

    // ------------------------------------------------------------------ names

    private val reserved = setOf(NOMEDIA, INDEX, README)
    fun isVideoFile(d: HiddenDir.Doc) = !d.isDir && d.name !in reserved && !d.name.endsWith(PART) && !d.name.startsWith(".")

    /** [desired] if free, otherwise "name (1).ext", "name (2).ext"… — never an existing name (no overwrite). */
    fun uniqueName(desired: String, taken: Collection<String>): String {
        val clean = sanitize(desired)
        val lower = taken.map { it.lowercase() }.toHashSet()
        if (clean.lowercase() !in lower && (clean + PART).lowercase() !in lower) return clean
        val dot = clean.lastIndexOf('.')
        val base = if (dot > 0) clean.substring(0, dot) else clean
        val ext = if (dot > 0) clean.substring(dot) else ""
        var k = 1
        while (true) {
            val n = "$base ($k)$ext"
            if (n.lowercase() !in lower && (n + PART).lowercase() !in lower) return n
            k++
        }
    }

    fun sanitize(name: String): String {
        val n = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trimStart('.')
        return n.ifEmpty { "video.mp4" }.take(180)
    }

    // ------------------------------------------------------------------ copy + verify

    fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        input.use { ins -> val buf = ByteArray(256 * 1024); while (true) { val r = ins.read(buf); if (r < 0) break; md.update(buf, 0, r) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Copies [src] into a new file [name] in [dir] via a `.vela-part` temp, verifying size + SHA-256 before the final rename. */
    sealed class Copy { data class Ok(val doc: HiddenDir.Doc) : Copy(); data class Fail(val why: String) : Copy() }

    fun copyIn(dir: HiddenDir, name: String, mime: String, open: () -> InputStream?, expectedSize: Long, expectedHash: String?): Copy {
        dir.find(name + PART)?.let { dir.delete(it) } // our own leftover partial from an interrupted run (the source still exists)
        val part = dir.create(name + PART, "application/octet-stream") ?: return Copy.Fail("couldn't create a file in the hidden folder")
        var count = 0L
        val md = MessageDigest.getInstance("SHA-256")
        try {
            val ins = open() ?: run { dir.delete(part); return Copy.Fail("the video couldn't be read") }
            val out = dir.openOut(part) ?: run { ins.close(); dir.delete(part); return Copy.Fail("couldn't write to the hidden folder") }
            ins.use { i -> out.use { o ->
                val buf = ByteArray(256 * 1024)
                while (true) { val r = i.read(buf); if (r < 0) break; o.write(buf, 0, r); md.update(buf, 0, r); count += r }
                o.flush(); (o as? FileOutputStream)?.fd?.sync()
            } }
        } catch (_: Exception) { dir.delete(part); return Copy.Fail(if (count > 0) "copying stopped (storage full?)" else "the video couldn't be read") }
        val hash = md.digest().joinToString("") { "%02x".format(it) }
        if (count == 0L || (expectedSize > 0 && count != expectedSize) || (expectedHash != null && hash != expectedHash)) {
            dir.delete(part); return Copy.Fail("the copy didn't match the original, so nothing was changed")
        }
        // Read the written copy back: what's on disk must be what we read.
        val back = dir.openIn(part)?.let { sha256(it) }
        if (back != hash) { dir.delete(part); return Copy.Fail("the copy couldn't be verified, so nothing was changed") }
        val fin = dir.rename(part, name) ?: run { dir.delete(part); return Copy.Fail("the copy couldn't be saved") }
        return Copy.Ok(fin)
    }

    // ------------------------------------------------------------------ index + readme

    data class IndexEntry(val file: String, val title: String, val origDir: String?, val origName: String?, val hiddenAt: Long, val size: Long, val mime: String?)

    fun indexJson(entries: List<IndexEntry>): String {
        val a = JSONArray()
        entries.forEach { e ->
            a.put(JSONObject().put("file", e.file).put("title", e.title).put("originalFolder", e.origDir ?: "")
                .put("originalName", e.origName ?: "").put("hiddenAt", e.hiddenAt).put("size", e.size).put("mime", e.mime ?: ""))
        }
        return JSONObject().put("app", "Vela Player").put("format", 1)
            .put("about", "Videos hidden by Vela Player's Hidden Vault. See \"$README\" in this folder.")
            .put("items", a).toString(2)
    }

    fun parseIndex(text: String?): List<IndexEntry> = if (text == null) emptyList() else try {
        val a = JSONObject(text).optJSONArray("items") ?: JSONArray()
        (0 until a.length()).mapNotNull { i -> val o = a.optJSONObject(i) ?: return@mapNotNull null
            IndexEntry(o.optString("file").ifEmpty { return@mapNotNull null }, o.optString("title", "Video"),
                o.optString("originalFolder").ifEmpty { null }, o.optString("originalName").ifEmpty { null },
                o.optLong("hiddenAt", 0L), o.optLong("size", 0L), o.optString("mime").ifEmpty { null }) }
    } catch (_: Exception) { emptyList() }

    val README_TEXT = """
        VELA PLAYER - HIDDEN VAULT FOLDER
        =================================

        The videos in this folder were hidden with Vela Player's Hidden Vault ("Hide from Gallery too").
        They are normal, unencrypted video files. Vela never deletes them on its own, and uninstalling Vela
        or clearing its data does NOT delete them.

        Why Gallery doesn't show them: this folder contains a file named ".nomedia". Android and most Gallery
        apps skip folders that contain it.

        HOW TO GET THE VIDEOS BACK
        1. With Vela: open the Hidden Vault and use Unhide. Vela moves each video back to its original folder
           (or to Movies/Vela Restored) and Gallery shows it again.
           After reinstalling Vela: Hidden Vault > ⋮ > "Recover hidden videos", choose this folder
           ("Movies/Vela Hidden") and tap "Use this folder". Vela lists the videos again.
        2. Without Vela: open any file manager (for example the "Files" app), go to Movies > Vela Hidden and
           either MOVE the videos to another folder (for example Movies or DCIM), or delete the ".nomedia"
           file (turn on "Show hidden files" to see it). Gallery shows the videos again after a moment
           (restarting the phone forces a rescan).

        "$INDEX" lists every hidden video with its original name and folder.
        Do not delete this folder unless you want to delete the videos in it.
    """.trimIndent()

    /** Writes .nomedia (first, so nothing is ever indexed), the README and the index. Returns false if .nomedia is missing. */
    fun writeSupportFiles(dir: HiddenDir, entries: List<IndexEntry>?): Boolean {
        if (dir.find(NOMEDIA) == null) dir.create(NOMEDIA, "application/octet-stream")
        if (dir.find(README) == null) writeText(dir, README, "text/plain", README_TEXT)
        if (entries != null) writeText(dir, INDEX, "application/json", indexJson(entries))
        return dir.find(NOMEDIA) != null
    }

    /** Replaces a small text file safely: write a temp, then swap (the old index survives a failed write). */
    fun writeText(dir: HiddenDir, name: String, mime: String, text: String): Boolean {
        val tmpName = "$name.tmp"
        dir.find(tmpName)?.let { dir.delete(it) }
        val tmp = dir.create(tmpName, "application/octet-stream") ?: return false
        val ok = try { dir.openOut(tmp)?.use { it.write(text.toByteArray()) } != null } catch (_: Exception) { false }
        if (!ok) { dir.delete(tmp); return false }
        dir.find(name)?.let { dir.delete(it) }
        return dir.rename(tmp, name) != null
    }

    fun readText(dir: HiddenDir, name: String): String? = dir.find(name)?.let { d -> try { dir.openIn(d)?.use { it.readBytes().toString(Charsets.UTF_8) } } catch (_: Exception) { null } }

    /** Index entries for every folder item in the vault records. */
    fun entriesOf(items: List<Vault.Item>, dir: HiddenDir): List<IndexEntry> {
        val byUri = dir.list().associateBy { it.uri.toString() }
        return items.filter { it.isFolder }.mapNotNull { i ->
            val name = byUri[i.uri]?.name ?: Uri.parse(i.uri).let { u -> nameFromUri(u) } ?: return@mapNotNull null
            IndexEntry(name, i.title, i.origDir, i.origName, i.hiddenAt, i.size, i.mime)
        }
    }

    /** Last path segment of a file:// or SAF document uri ("primary:Movies/Vela Hidden/x.mp4" → "x.mp4"). */
    fun nameFromUri(u: Uri): String? = try {
        if (u.scheme == "file") u.lastPathSegment
        else DocumentsContract.getDocumentId(u).substringAfterLast('/').substringAfterLast(':')
    } catch (_: Exception) { u.lastPathSegment?.substringAfterLast('/') }
}
