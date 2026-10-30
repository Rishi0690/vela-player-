package com.hark.velaplayer

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.MediaStore
import android.text.format.Formatter
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Hidden Vault storage (v2.5, reworked in v2.21).
 *
 * "Hide in Vela only" ([Vault.MODE_APP]): nothing on disk changes; other apps still see the file.
 * "Hide from Gallery too" ([Vault.MODE_FOLDER], v2.21): the video is MOVED (renamed in place, no copy, no delete)
 * into `Movies/Vela Hidden/` (see [HiddenFolder]) which has a `.nomedia` file, a recovery index and a README. The
 * folder is in shared storage, so uninstalling Vela or clearing its data never deletes hidden videos.
 *  - Android 11+: one system "allow Vela to modify" dialog (MediaStore.createWriteRequest), then a MediaStore
 *    RELATIVE_PATH update moves the file. Vela reads the folder through a one-time folder grant (Storage Access
 *    Framework). If a move is refused by the system, Vela falls back to copy → verify (size + SHA-256) → system
 *    delete dialog for the original; declining it removes only Vela's copy.
 *  - Android 7–10: File.renameTo with the storage permission (legacy storage); a cross-volume (SD card) move falls
 *    back to copy → verify → delete the original.
 * Unhide puts the file back in its original folder (or Movies/Vela Restored), never overwriting a file there, and the
 * media library picks it up again. v2.5 "private storage" items are migrated by [VaultMigration].
 */
object VaultStorage {
    private const val MIN_FREE = 200L * 1024 * 1024
    @Volatile var busy = false
        private set
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** v2.5 private storage (read-only now: only migrated / restored from). */
    fun privateDir(c: Context): File = File(c.filesDir, "vault")
    fun fileOf(i: Vault.Item): File? = if (i.isPrivate) Uri.parse(i.uri).path?.let { File(it) } else null

    /** Is the stored file there? (null = can't tell right now, e.g. folder access not granted yet.) */
    fun exists(c: Context, i: Vault.Item): Boolean? = when {
        i.isPrivate -> fileOf(i)?.exists() == true
        i.isFolder -> {
            val u = Uri.parse(i.uri)
            if (u.scheme == "file") File(u.path ?: "").isFile
            else if (HiddenFolder.grantedTree(c) == null) null
            else SafHiddenDir(c.contentResolver, HiddenFolder.grantedTree(c)!!).sizeOf(u) >= 0
        }
        else -> null
    }

    // ------------------------------------------------------------------ activity hooks

    /** Create as an Activity property (registers result launchers before onCreate). */
    class Requester(private val act: ComponentActivity) {
        /** Called right before a system screen opens (the vault uses it so it doesn't lock itself meanwhile). */
        var beforeLaunch: (() -> Unit)? = null
        private var senderCb: ((Boolean) -> Unit)? = null
        private val senderLauncher = act.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
            val cb = senderCb; senderCb = null; cb?.invoke(r.resultCode == Activity.RESULT_OK)
        }
        private var permCb: ((Boolean) -> Unit)? = null
        private val permLauncher = act.registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            val cb = permCb; permCb = null; cb?.invoke(ok)
        }
        private var treeCb: ((Uri?) -> Unit)? = null
        private val treeLauncher = act.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
            val cb = treeCb; treeCb = null; cb?.invoke(u)
        }
        fun sender(s: IntentSender, cb: (Boolean) -> Unit) {
            senderCb = cb; beforeLaunch?.invoke()
            try { senderLauncher.launch(IntentSenderRequest.Builder(s).build()) } catch (_: Exception) { senderCb = null; cb(false) }
        }
        /** Storage write permission (only exists up to Android 10). */
        fun legacyWrite(cb: (Boolean) -> Unit) {
            if (Build.VERSION.SDK_INT >= 30 || ContextCompat.checkSelfPermission(act, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) { cb(true); return }
            permCb = cb; beforeLaunch?.invoke()
            try { permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) } catch (_: Exception) { permCb = null; cb(false) }
        }
        /** System folder picker, opened at Movies/Vela Hidden. */
        fun pickTree(cb: (Uri?) -> Unit) {
            treeCb = cb; beforeLaunch?.invoke()
            HiddenFolder.ensureDirExists()
            try { treeLauncher.launch(if (Build.VERSION.SDK_INT >= 26) HiddenFolder.initialPickerUri() else null) } catch (_: Exception) { treeCb = null; cb(null) }
        }
    }

    /**
     * Gets the hidden folder ready: Android 10-: storage permission; Android 11+: the one-time folder grant (explained
     * first, validated after: only "Movies/Vela Hidden" is accepted, so .nomedia never lands in another folder).
     * [recovery] also accepts any folder that holds a Vela index (moved/renamed by the user).
     */
    fun withFolder(act: Activity, req: Requester, recovery: Boolean = false, then: (HiddenDir) -> Unit) {
        if (!HiddenFolder.usesSaf) {
            req.legacyWrite { ok ->
                val d = if (ok) HiddenFolder.open(act) else null
                if (d != null) then(d) else report(act, "Storage permission needed", 0,
                    listOf("Vela needs the storage permission to move videos into Movies/${HiddenFolder.NAME}"), "")
            }
            return
        }
        HiddenFolder.open(act)?.let { then(it); return }
        val msg = if (recovery)
            "In the next screen, open Movies › ${HiddenFolder.NAME} (the folder with your hidden videos) and tap \"Use this folder\", then Allow.\n\n" +
                "Vela will list the hidden videos it finds there again. Nothing is moved or deleted."
        else
            "Hidden videos are kept in the folder Movies/${HiddenFolder.NAME} on your phone. Gallery doesn't show that folder, " +
                "and uninstalling Vela never deletes it.\n\nAndroid asks once for access to this ONE folder: in the next screen tap " +
                "\"Use this folder\", then Allow (if Movies has no \"${HiddenFolder.NAME}\" folder yet, create it there with the picker's " +
                "New folder option). Vela gets no access to your other folders."
        VelaDialogBuilder(act).setTitle(if (recovery) "Recover hidden videos" else "Allow the hidden folder")
            .setMessage(msg)
            .setPositiveButton("Continue") { _, _ ->
                req.pickTree { tree ->
                    if (tree == null) return@pickTree
                    if (!HiddenFolder.isOurTree(act.contentResolver, tree, recovery)) {
                        VelaDialogBuilder(act).setTitle("That's a different folder")
                            .setMessage("Please choose Movies › ${HiddenFolder.NAME}. Vela only works in that folder, so it never hides or changes anything else.")
                            .setPositiveButton("Try again") { _, _ -> withFolder(act, req, recovery, then) }
                            .setNegativeButton("Cancel", null).show()
                        return@pickTree
                    }
                    HiddenFolder.takeGrant(act, tree)
                    then(SafHiddenDir(act.contentResolver, tree))
                }
            }.setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------------ dialogs

    private const val APP_TEXT = "The file stays where it is. Vela's lists stop showing it, but Gallery, file managers and other apps can " +
        "still see it. Nothing is moved, copied or deleted."
    private const val FOLDER_TEXT = "Vela moves the video into the folder Movies/${HiddenFolder.NAME} on your phone. Gallery apps don't " +
        "show that folder. Nothing is copied or deleted, and it's not encrypted (file managers can still open the folder).\n" +
        "Uninstalling Vela or clearing its data does NOT delete these videos: they stay in that folder. Reinstall Vela and use " +
        "\"Recover hidden videos\", or move them out with any file manager (a README in the folder explains how)."

    /**
     * Hide flow used everywhere videos enter the vault. [allowApp] false = only offer "Hide from Gallery too"
     * (for videos already hidden in Vela only).
     */
    fun chooseAndHide(act: Activity, req: Requester, entries: List<Entry>, allowApp: Boolean,
                      onAppHide: (List<Entry>) -> Unit, onPrivateDone: (Int) -> Unit) {
        val list = entries.distinctBy { it.uri }
        if (list.isEmpty()) return
        val n = list.size
        val d = act.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(px(22), px(8), px(22), 0) }
        fun body(t: String) = TextView(act).apply { text = t; alpha = 0.75f; textSize = 13f; setPadding(px(32), 0, 0, px(10)) }
        val group = RadioGroup(act)
        val rbApp = RadioButton(act).apply { id = View.generateViewId(); text = "Hide in Vela only"; setTypeface(typeface, Typeface.BOLD) }
        val rbFolder = RadioButton(act).apply { id = View.generateViewId(); text = "Hide from Gallery too"; setTypeface(typeface, Typeface.BOLD) }
        if (allowApp) { group.addView(rbApp); group.addView(body(APP_TEXT)) }
        group.addView(rbFolder); group.addView(body(FOLDER_TEXT))
        col.addView(group)
        val scroll = ScrollView(act).apply { addView(col) }
        val dlg = VelaDialogBuilder(act)
            .setTitle(if (n == 1) "Hide this video" else "Hide $n videos")
            .setView(scroll)
            .setPositiveButton("Hide", null)
            .setNegativeButton("Cancel", null).create()
        dlg.setOnShowListener {
            group.check(if (allowApp) rbApp.id else rbFolder.id)
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val folder = group.checkedRadioButtonId == rbFolder.id
                dlg.dismiss()
                if (folder) hideInFolder(act, req, list, onPrivateDone) else onAppHide(list)
            }
        }
        dlg.show()
    }

    private class Progress(act: Activity, title: String) {
        private val bar = LinearProgressIndicator(act).apply { isIndeterminate = false; max = 1000 }
        private val label = TextView(act).apply { alpha = 0.75f }
        val dlg: AlertDialog
        init {
            val px = (22 * act.resources.displayMetrics.density).toInt()
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(px, px / 2, px, 0); addView(label); addView(bar) }
            dlg = VelaDialogBuilder(act).setTitle(title).setView(col).setCancelable(false).create()
            dlg.show()
        }
        fun set(i: Int, n: Int, what: String) = main.post { label.text = "$what ${i + 1} of $n…"; bar.setProgressCompat(i * 1000 / n.coerceAtLeast(1), true) }
        fun close() = main.post { try { dlg.dismiss() } catch (_: Exception) { } }
    }

    private fun report(act: Activity, title: String, done: Int, failures: List<String>, okText: String) {
        if (act.isFinishing || act.isDestroyed) return
        val msg = buildString {
            if (done > 0) append(okText)
            if (failures.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Not done for ${failures.size}:\n")
                failures.take(8).forEach { append("• ").append(it).append('\n') }
                if (failures.size > 8) append("…and ${failures.size - 8} more\n")
            }
        }
        VelaDialogBuilder(act).setTitle(title).setMessage(msg.trim()).setPositiveButton("OK", null).show()
    }

    private sealed class R<out T> { data class Ok<T>(val v: T) : R<T>(); data class Fail(val why: String) : R<Nothing>() }

    // ------------------------------------------------------------------ hide: move into Movies/Vela Hidden

    /** What we know about an original before moving it. */
    private data class Orig(val e: Entry, val uri: Uri, val name: String, val mime: String, val size: Long, val dur: Long,
                            val data: String?, val rel: String?, var target: String = "")

    private fun queryOrig(c: Context, e: Entry): R<Orig> {
        val uri = Uri.parse(e.uri)
        if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY) return R.Fail("only videos from the phone's media library can be hidden from Gallery")
        try {
            val proj = arrayListOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE,
                MediaStore.Video.VideoColumns.DURATION, MediaStore.MediaColumns.DATA)
            if (Build.VERSION.SDK_INT >= 29) proj += MediaStore.MediaColumns.RELATIVE_PATH
            c.contentResolver.query(uri, proj.toTypedArray(), null, null, null)?.use { cur ->
                if (!cur.moveToFirst()) return R.Fail("the file wasn't found")
                val name = cur.getString(0) ?: (e.title + ".mp4")
                return R.Ok(Orig(e, uri, name, cur.getString(1) ?: "video/mp4", cur.getLong(2), cur.getLong(3), cur.getString(4),
                    if (Build.VERSION.SDK_INT >= 29) cur.getString(5) else null))
            } ?: return R.Fail("the file wasn't found")
        } catch (_: SecurityException) { return R.Fail("Vela isn't allowed to read this file")
        } catch (_: Exception) { return R.Fail("the file couldn't be read") }
    }

    private fun recordOf(o: Orig, newUri: String, size: Long) = Vault.Item(newUri, o.e.title, System.currentTimeMillis(), Vault.MODE_FOLDER,
        orig = o.e.uri, origDir = o.rel ?: o.data?.let { File(it).parent }, origName = o.name, mime = o.mime,
        size = if (size > 0) size else o.size, dur = o.dur)

    /** Record the moved video (converting a Vela-only item in place, resume point carried over). */
    private fun commit(app: Context, rec: Vault.Item) { Vault.addPendingPrivate(app, rec); Vault.confirmPrivate(app, rec.uri) }

    fun hideInFolder(act: Activity, req: Requester, entries: List<Entry>, done: (Int) -> Unit) {
        if (busy) { report(act, "Please wait", 0, listOf("Another vault move is still running"), ""); return }
        withFolder(act, req) { dir ->
            busy = true
            val app = act.applicationContext
            io.execute {
                val fails = ArrayList<String>(); val origs = ArrayList<Orig>()
                if (!HiddenFolder.writeSupportFiles(dir, null)) fails += "The hidden folder couldn't be prepared (.nomedia)"
                else {
                    val taken = dir.list().map { it.name }.toMutableList()
                    for (e in entries) when (val r = queryOrig(app, e)) {
                        is R.Ok -> { r.v.target = HiddenFolder.uniqueName(r.v.name, taken); taken += r.v.target; origs += r.v }
                        is R.Fail -> fails += "${e.title}: ${r.why}"
                    }
                }
                main.post {
                    if (origs.isEmpty()) { busy = false; report(act, "Nothing was hidden", 0, fails, ""); done(0); return@post }
                    if (HiddenFolder.usesSaf) {
                        // One system dialog: "Allow Vela to modify these videos?"
                        val s = try { MediaStore.createWriteRequest(act.contentResolver, origs.map { it.uri }).intentSender } catch (_: Exception) { null }
                        if (s == null) { moveAll(act, req, app, dir, origs, false, fails, done); return@post }
                        req.sender(s) { ok ->
                            if (!ok) { busy = false; report(act, "Nothing was hidden", 0, listOf("You didn't allow Vela to move the videos, so nothing changed"), ""); done(0) }
                            else moveAll(act, req, app, dir, origs, true, fails, done)
                        }
                    } else moveAll(act, req, app, dir, origs, true, fails, done)
                }
            }
        }
    }

    /** Set when the system reported a move but the file couldn't be matched by name; the folder is re-scanned after. */
    @Volatile private var movedUnverified = false

    private fun moveAll(act: Activity, req: Requester, app: Context, dir: HiddenDir, origs: List<Orig>, granted: Boolean,
                        fails: MutableList<String>, done: (Int) -> Unit) {
        val prog = Progress(act, "Hiding from Gallery")
        io.execute {
            var moved = 0
            val copyNeeded = ArrayList<Orig>()
            origs.forEachIndexed { i, o ->
                prog.set(i, origs.size, "Moving")
                val doc = if (granted) moveInPlace(app, dir, o) else null
                if (doc != null) { commit(app, recordOf(o, doc.uri.toString(), doc.size)); moved++ } else copyNeeded += o
            }
            // Fallback for anything the system wouldn't move: copy → verify → delete the original (with consent).
            val copies = ArrayList<Pair<Orig, Vault.Item>>()
            copyNeeded.forEachIndexed { i, o ->
                prog.set(i, copyNeeded.size, "Copying")
                val free = try { StatFs(Environment.getExternalStorageDirectory().path).availableBytes } catch (_: Exception) { Long.MAX_VALUE }
                if (o.size > 0 && free < o.size + MIN_FREE) { fails += "${o.e.title}: couldn't be moved, and there isn't enough free space to copy it"; return@forEachIndexed }
                when (val r = HiddenFolder.copyIn(dir, o.target, o.mime, { app.contentResolver.openInputStream(o.uri) }, o.size, null)) {
                    is HiddenFolder.Copy.Ok -> { val rec = recordOf(o, r.doc.uri.toString(), r.doc.size); Vault.addPendingPrivate(app, rec); copies += o to rec }
                    is HiddenFolder.Copy.Fail -> fails += "${o.e.title}: ${r.why}"
                }
            }
            prog.close()
            if (movedUnverified) { movedUnverified = false; moved += VaultMigration.adopt(app, dir) }
            VaultMigration.writeIndex(app, dir)
            main.post {
                if (copies.isEmpty()) { finishHide(act, moved, fails, done); return@post }
                deleteOriginals(act, req, copies.map { it.first }) { ok ->
                    io.execute {
                        for ((o, rec) in copies) when (originalExists(app, o.e.uri, o.data)) {
                            false, null -> { Vault.confirmPrivate(app, rec.uri); moved++ }
                            true -> {
                                // The original is still there: drop only Vela's extra copy (the original is untouched).
                                Vault.revertPrivate(app, rec.uri)
                                dir.list().firstOrNull { it.uri.toString() == rec.uri }?.let { dir.delete(it) }
                                fails += "${o.e.title}: ${if (ok) "Android didn't remove the original" else "you didn't allow removing the original"}, so nothing changed"
                            }
                        }
                        VaultMigration.writeIndex(app, dir)
                        main.post { finishHide(act, moved, fails, done) }
                    }
                }
            }
        }
    }

    private fun finishHide(act: Activity, moved: Int, fails: List<String>, done: (Int) -> Unit) {
        busy = false
        report(act, if (moved > 0) "Hidden from Gallery" else "Nothing was hidden", moved, fails,
            "$moved video${if (moved == 1) " is" else "s are"} now in Movies/${HiddenFolder.NAME}. Gallery apps don't show " +
                "${if (moved == 1) "it" else "them"}, and uninstalling Vela won't delete ${if (moved == 1) "it" else "them"}.")
        done(moved)
    }

    /** Renames the original into the hidden folder (no copy). Returns the moved file, verified by name + size, or null. */
    private fun moveInPlace(c: Context, dir: HiddenDir, o: Orig): HiddenDir.Doc? {
        if (Build.VERSION.SDK_INT >= 30) {
            val n = try {
                c.contentResolver.update(o.uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, HiddenFolder.RELATIVE_PATH)
                    put(MediaStore.MediaColumns.DISPLAY_NAME, o.target)
                }, null, null)
            } catch (_: Exception) { 0 }
            if (n <= 0) return null
            // A rename keeps the bytes; find the moved file (the provider may have adjusted the name on a clash).
            val stem = o.target.substringBeforeLast('.')
            val d = dir.find(o.target) ?: dir.list().firstOrNull { HiddenFolder.isVideoFile(it) && it.name.startsWith(stem) && (o.size <= 0 || it.size == o.size) }
            if (d == null) movedUnverified = true // it IS in the folder; VaultMigration.adopt lists it afterwards
            // Make sure MediaStore re-reads it right away as a file in a .nomedia folder (MEDIA_TYPE_NONE: out of Gallery).
            @Suppress("DEPRECATION")
            scan(c, File(File(Environment.getExternalStorageDirectory(), HiddenFolder.RELATIVE_PATH), d?.name ?: o.target).path, null)
            return d
        }
        val src = File(o.data ?: return null)
        val dst = File((dir as? FileHiddenDir)?.dir ?: return null, o.target)
        if (dst.exists() || !src.isFile) return null
        if (!src.renameTo(dst)) return null
        if (dst.length() != src.length() && o.size > 0 && dst.length() != o.size) return null
        scan(c, src.path, null) // drops the old path from the media library
        return HiddenDir.Doc(dst.name, Uri.fromFile(dst), dst.length())
    }

    /** Android 11+: one system dialog for all originals. Older: delete directly with the storage permission. */
    private fun deleteOriginals(act: Activity, req: Requester, list: List<Orig>, then: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT >= 30) {
            try { req.sender(MediaStore.createDeleteRequest(act.contentResolver, list.map { it.uri }).intentSender) { ok -> then(ok) } }
            catch (_: Exception) { then(false) }
        } else io.execute {
            for (o in list) try {
                o.data?.let { File(it).delete() }
                act.contentResolver.delete(o.uri, null, null)
            } catch (_: Exception) { }
            main.post { then(true) }
        }
    }

    /** true / false, or null when it can't be told (then nothing is deleted). */
    private fun originalExists(c: Context, orig: String?, data: String? = null): Boolean? {
        if (orig == null) return false
        if (Build.VERSION.SDK_INT < 30 && data != null) return File(data).exists()
        return try {
            c.contentResolver.query(Uri.parse(orig), arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count > 0 }
        } catch (_: SecurityException) { null } catch (_: Exception) { null }
    }

    private fun scan(c: Context, path: String, mime: String?): Uri? {
        val latch = CountDownLatch(1); var out: Uri? = null
        try { MediaScannerConnection.scanFile(c, arrayOf(path), mime?.let { arrayOf(it) }) { _, u -> out = u; latch.countDown() } } catch (_: Exception) { return null }
        latch.await(15, TimeUnit.SECONDS)
        return out
    }

    // ------------------------------------------------------------------ unhide: back to the original folder

    /** Unhide stored items (folder items and not-yet-migrated v2.5 private items). Vela-only items use [Vault.unhide]. */
    fun restore(act: Activity, req: Requester, items: List<Vault.Item>, done: (Int) -> Unit) {
        val list = items.filter { it.isStored }
        if (list.isEmpty()) { done(0); return }
        if (busy) { report(act, "Please wait", 0, listOf("Another vault move is still running"), ""); return }
        val run = { dir: HiddenDir? ->
            busy = true
            val app = act.applicationContext
            val prog = Progress(act, "Unhiding videos")
            io.execute {
                var ok = 0; val fails = ArrayList<String>(); val where = LinkedHashSet<String>()
                list.forEachIndexed { i, it ->
                    prog.set(i, list.size, "Unhiding")
                    val r = if (it.isPrivate) restorePrivate(app, it) else if (dir != null) restoreFolder(app, dir, it) else R.Fail("no access to the hidden folder")
                    when (r) {
                        is R.Ok -> { Vault.finishRestore(app, it.uri, r.v.first); ok++; where += r.v.second; if (it.isPrivate) fileOf(it)?.delete() }
                        is R.Fail -> fails += "${it.title}: ${r.why}. It's still safe in the vault."
                    }
                }
                dir?.let { VaultMigration.writeIndex(app, it) }
                prog.close()
                main.post {
                    busy = false
                    report(act, if (ok > 0) "Unhidden" else "Nothing was unhidden", ok, fails,
                        "$ok video${if (ok == 1) " is" else "s are"} back in phone storage (${where.joinToString(", ")}) and visible in Gallery and the library again.")
                    done(ok)
                }
            }
        }
        val needFolder = list.any { it.isFolder }
        val needLegacy = Build.VERSION.SDK_INT < 29
        when {
            needFolder -> withFolder(act, req) { d -> run(d) }
            needLegacy -> req.legacyWrite { g -> if (g) run(null) else report(act, "Nothing was unhidden", 0, listOf("Storage permission is needed to put videos back in phone storage"), "") }
            else -> run(null)
        }
    }

    /** Returns (new uri, folder shown to the user). Never overwrites: a name already taken gets " (1)". */
    private fun restoreFolder(c: Context, dir: HiddenDir, it: Vault.Item): R<Pair<String, String>> {
        val u = Uri.parse(it.uri)
        val doc = dir.list().firstOrNull { d -> d.uri == u } ?: dir.find(HiddenFolder.nameFromUri(u) ?: "") ?: return R.Fail("the hidden file is missing")
        val name = it.origName ?: doc.name
        val mime = it.mime ?: VaultMigration.mimeOf(doc.name)
        if (u.scheme == "file" || dir is FileHiddenDir) {
            // Android 10 and older: rename back (no copy).
            val src = File(u.path ?: return R.Fail("the hidden file is missing"))
            @Suppress("DEPRECATION")
            val fallback = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Vela Restored")
            val orig = it.origDir?.takeIf { d -> d.startsWith("/") }?.let { d -> File(d) }
            val target = listOfNotNull(orig, fallback).firstOrNull { d -> (d.isDirectory || d.mkdirs()) && d.canWrite() }
                ?: return R.Fail("no writable folder in phone storage")
            val dest = File(target, HiddenFolder.uniqueName(name, target.list()?.toList() ?: emptyList()))
            if (!src.renameTo(dest)) {
                // Different volume: copy, verify, then remove the hidden file.
                val h = try { HiddenFolder.sha256(src.inputStream()) } catch (_: Exception) { return R.Fail("the hidden file couldn't be read") }
                try { src.inputStream().use { ins -> FileOutputStream(dest).use { out -> ins.copyTo(out, 256 * 1024); out.fd.sync() } } }
                catch (_: Exception) { dest.delete(); return R.Fail("copying back failed (storage full?)") }
                if (dest.length() != src.length() || HiddenFolder.sha256(dest.inputStream()) != h) { dest.delete(); return R.Fail("copying back failed") }
                src.delete()
            }
            val scanned = scan(c, dest.path, mime)
            return R.Ok((scanned ?: Uri.fromFile(dest)).toString() to target.path)
        }
        // Android 11+: new media item in the original folder, copy, verify (size + SHA-256 read back), then remove the hidden file.
        val len = if (doc.size > 0) doc.size else dir.sizeOf(doc.uri)
        val free = try { StatFs(Environment.getExternalStorageDirectory().path).availableBytes } catch (_: Exception) { Long.MAX_VALUE }
        if (len > 0 && free < len + MIN_FREE) return R.Fail("not enough free space to put it back (needs ${Formatter.formatShortFileSize(c, len)})")
        val cr = c.contentResolver
        val coll = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val origRel = it.origDir?.let { d -> if (d.endsWith("/")) d else "$d/" }?.takeIf { d -> !d.startsWith("/") && allowedTop.any { t -> d.startsWith(t) } && !d.startsWith(HiddenFolder.RELATIVE_PATH) }
        var target: Uri? = null; var usedDir = ""
        for (d in listOfNotNull(origRel, FALLBACK).distinct()) {
            target = try {
                cr.insert(coll, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, d); put(MediaStore.MediaColumns.IS_PENDING, 1)
                })
            } catch (_: Exception) { null }
            if (target != null) { usedDir = d; break }
        }
        val t = target ?: return R.Fail("Android didn't allow creating the file")
        var count = 0L
        val md = java.security.MessageDigest.getInstance("SHA-256")
        try {
            (cr.openOutputStream(t, "w") ?: error("no stream")).use { out -> (dir.openIn(doc) ?: error("no input")).use { ins ->
                val buf = ByteArray(256 * 1024)
                while (true) { val r = ins.read(buf); if (r < 0) break; out.write(buf, 0, r); md.update(buf, 0, r); count += r }
            } }
        } catch (_: Exception) { }
        val h = md.digest().joinToString("") { b -> "%02x".format(b) }
        val back = try { cr.openInputStream(t)?.let { s -> HiddenFolder.sha256(s) } } catch (_: Exception) { null }
        if (count == 0L || (len > 0 && count != len) || back != h) {
            // Only Vela's new, incomplete copy is removed; the hidden original is untouched.
            try { cr.delete(t, null, null) } catch (_: Exception) { }
            return R.Fail("copying back failed (storage full?)")
        }
        try { cr.update(t, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) } catch (_: Exception) { }
        if (!dir.delete(doc)) {
            // Restored and visible; the hidden duplicate stays (never lose data). Rename it so it isn't re-adopted silently.
            dir.rename(doc, doc.name + ".restored-copy")
        }
        return R.Ok(t.toString() to usedDir.trimEnd('/'))
    }

    // ------------------------------------------------------------------ v2.5 private items: migration + legacy restore

    /** Migrates every v2.5 private item into the hidden folder (see [VaultMigration]). [quiet] = no dialog when nothing failed. */
    fun migrate(act: Activity, req: Requester, done: (Int) -> Unit) {
        if (busy) return
        if (VaultMigration.pendingMigrations(act).isEmpty()) { done(0); return }
        withFolder(act, req) { dir ->
            busy = true
            val app = act.applicationContext
            val prog = Progress(act, "Moving hidden videos to the safe folder")
            io.execute {
                val res = VaultMigration.migrateAll(app, dir) { i, n -> prog.set(i, n, "Moving") }
                prog.close()
                val ok = res.count { it.second is VaultMigration.Result.Migrated }
                val fails = res.mapNotNull { (i, r) -> (r as? VaultMigration.Result.Failed)?.let { "${i.title}: ${it.why}. It stays in Vela's private storage for now; Vela tries again next time." } }
                main.post {
                    busy = false
                    Prefs.sp(act).edit().putBoolean(PREF_MIGRATION_NOTICE, true).apply()
                    report(act, if (ok > 0) "Hidden videos are safe now" else "Not moved yet", ok, fails,
                        "$ok hidden video${if (ok == 1) " was" else "s were"} moved from Vela's private storage to Movies/${HiddenFolder.NAME}. " +
                            "They're still hidden from Gallery, and uninstalling Vela or clearing its data no longer deletes them.")
                    done(ok)
                }
            }
        }
    }

    const val PREF_MIGRATION_NOTICE = "vault_v221_migration_notice"
    const val PREF_RECOVER_OFFERED = "vault_v221_recover_offered"

    /**
     * Called when the unlocked vault shows. v2.5 private items (deleted on uninstall) → explain once and migrate;
     * when access is already there (Android 10-: permission; 11+: folder grant) it migrates without asking.
     * Otherwise, on a fresh install with a Vela Hidden folder on the phone → offer "Recover hidden videos" once.
     */
    fun onVaultShown(act: Activity, req: Requester, reload: () -> Unit) {
        if (busy) return
        val pending = VaultMigration.pendingMigrations(act)
        if (pending.isNotEmpty()) {
            if (HiddenFolder.open(act) != null) { migrate(act, req) { if (it > 0) reload() }; return }
            VelaDialogBuilder(act).setTitle("Make hidden videos safe")
                .setMessage("${pending.size} hidden video${if (pending.size == 1) " is" else "s are"} in Vela's private storage, which Android deletes " +
                    "if Vela is uninstalled or its data is cleared.\n\nVela now keeps hidden videos in Movies/${HiddenFolder.NAME} instead: still hidden from " +
                    "Gallery, and never deleted by uninstalling. Vela copies each video there, checks the copy, and only then removes the private copy.")
                .setPositiveButton("Move now") { _, _ -> migrate(act, req) { if (it > 0) reload() } }
                .setNegativeButton("Later", null).show()
            return
        }
        val sp = Prefs.sp(act)
        if (!sp.getBoolean(PREF_RECOVER_OFFERED, false) && Vault.items(act).none { it.isFolder } && HiddenFolder.looksPresent()) {
            sp.edit().putBoolean(PREF_RECOVER_OFFERED, true).apply()
            VelaDialogBuilder(act).setTitle("Hidden videos found")
                .setMessage("There's a ${HiddenFolder.NAME} folder on this phone, probably from an earlier Vela install. Vela can list the videos in it again.")
                .setPositiveButton("Recover") { _, _ -> recover(act, req, reload) }
                .setNegativeButton("Not now", null).show()
        }
    }

    /** "Recover hidden videos": re-adopt every video in the folder (after a reinstall / Clear data). */
    fun recover(act: Activity, req: Requester, reload: () -> Unit) {
        if (busy) return
        withFolder(act, req, recovery = true) { dir ->
            busy = true
            val app = act.applicationContext
            io.execute {
                val n = try { VaultMigration.adopt(app, dir) } catch (_: Exception) { -1 }
                main.post {
                    busy = false
                    VelaDialogBuilder(act).setTitle(if (n > 0) "Recovered" else "Nothing new found")
                        .setMessage(when {
                            n > 0 -> "$n hidden video${if (n == 1) " is" else "s are"} back in the vault."
                            n == 0 -> "Every video in that folder is already in the vault."
                            else -> "Vela couldn't read that folder."
                        }).setPositiveButton("OK", null).show()
                    if (n > 0) reload()
                }
            }
        }
    }

    private val allowedTop = listOf("DCIM/", "Movies/", "Pictures/")
    private const val FALLBACK = "Movies/Vela Restored/"

    /** v2.5 private item → shared storage (unchanged v2.5 logic). Returns (new uri, folder shown to the user). */
    private fun restorePrivate(c: Context, it: Vault.Item): R<Pair<String, String>> {
        val src = fileOf(it) ?: return R.Fail("the private copy is missing")
        if (!src.exists()) return R.Fail("the private copy is missing")
        val name = it.origName ?: (it.title + ".mp4")
        val mime = it.mime ?: "video/mp4"
        val len = src.length()
        return if (Build.VERSION.SDK_INT >= 29) {
            val cr = c.contentResolver
            val coll = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val orig = it.origDir?.let { d -> if (d.endsWith("/")) d else "$d/" }
            val dirs = listOfNotNull(orig?.takeIf { d -> allowedTop.any { d.startsWith(it) } }, FALLBACK).distinct()
            // Same name and size already there (e.g. restored before): reuse it instead of making a duplicate.
            for (d in dirs) try {
                cr.query(coll, arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.SIZE}=?",
                    arrayOf(d, name, len.toString()), null)?.use { cur ->
                    if (cur.moveToFirst()) return R.Ok(ContentUris.withAppendedId(coll, cur.getLong(0)).toString() to d.trimEnd('/'))
                }
            } catch (_: Exception) { }
            var target: Uri? = null; var usedDir = ""
            for (d in dirs) {
                target = try {
                    cr.insert(coll, ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, d); put(MediaStore.MediaColumns.IS_PENDING, 1)
                    })
                } catch (_: Exception) { null }
                if (target != null) { usedDir = d; break }
            }
            val t = target ?: return R.Fail("Android didn't allow creating the file")
            var count = 0L
            try {
                (cr.openOutputStream(t, "w") ?: error("no stream")).use { out -> src.inputStream().use { ins ->
                    val buf = ByteArray(256 * 1024)
                    while (true) { val r = ins.read(buf); if (r < 0) break; out.write(buf, 0, r); count += r }
                } }
            } catch (_: Exception) { }
            if (count != len) { try { cr.delete(t, null, null) } catch (_: Exception) { }; return R.Fail("copying back failed (storage full?)") }
            try { cr.update(t, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) } catch (_: Exception) { }
            R.Ok(t.toString() to usedDir.trimEnd('/'))
        } else {
            @Suppress("DEPRECATION")
            val fallback = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Vela Restored")
            val origDir = it.origDir?.takeIf { d -> d.startsWith("/") }?.let { File(it) }
            val dir = listOfNotNull(origDir, fallback).firstOrNull { d -> (d.exists() || d.mkdirs()) && d.canWrite() }
                ?: return R.Fail("no writable folder in phone storage")
            var dest = File(dir, name)
            if (!(dest.exists() && dest.length() == len)) {
                val base = name.substringBeforeLast('.'); val ext = name.substringAfterLast('.', "").let { e -> if (e.isEmpty()) "" else ".$e" }
                var k = 1; while (dest.exists()) { dest = File(dir, "$base ($k)$ext"); k++ }
                try { src.inputStream().use { ins -> FileOutputStream(dest).use { out -> ins.copyTo(out, 256 * 1024); out.fd.sync() } } }
                catch (_: Exception) { dest.delete(); return R.Fail("copying back failed (storage full?)") }
                if (dest.length() != len) { dest.delete(); return R.Fail("copying back failed (storage full?)") }
            }
            val latch = CountDownLatch(1); var scanned: Uri? = null
            MediaScannerConnection.scanFile(c, arrayOf(dest.path), arrayOf(mime)) { _, u -> scanned = u; latch.countDown() }
            latch.await(15, TimeUnit.SECONDS)
            R.Ok((scanned ?: Uri.fromFile(dest)).toString() to dir.path)
        }
    }


    // ------------------------------------------------------------------ startup check

    /**
     * Run off the main thread before showing the vault.
     * - Folder items left `pending` by a copy-fallback hide that was interrupted: original still there → drop the record
     *   (Vela's extra copy stays in the folder and can be re-adopted; nothing is deleted); original gone → keep it.
     * - v2.5 private items left pending: unchanged v2.5 rules (original still there → private copy removed).
     * - Interrupted migration cleanups are finished by [VaultMigration.reconcile]; private files with no record are
     *   adopted (never deleted).
     */
    fun reconcile(c: Context) {
        if (busy) return
        val app = c.applicationContext
        for (it in Vault.items(app)) if (it.pending) {
            if (it.isFolder) when (originalExists(app, it.orig)) {
                true -> Vault.revertPrivate(app, it.uri)
                false -> Vault.confirmPrivate(app, it.uri)
                null -> { }
            } else if (it.isPrivate) {
                if (fileOf(it)?.exists() != true) { Vault.revertPrivate(app, it.uri); continue }
                when (originalExists(app, it.orig)) {
                    true -> { Vault.revertPrivate(app, it.uri); fileOf(it)?.delete() }
                    false -> Vault.confirmPrivate(app, it.uri)
                    null -> { }
                }
            }
        }
        val dir = privateDir(app); if (!dir.exists()) return
        // Only touch the hidden folder when a migration cleanup is waiting (never creates the folder for nothing).
        if (Vault.items(app).any { it.isFolder && it.migratedFrom != null && File(dir, it.migratedFrom).exists() })
            VaultMigration.reconcile(app, try { HiddenFolder.open(app) } catch (_: Exception) { null })
        val known = Vault.items(app).filter { it.isPrivate }.mapNotNull { fileOf(it)?.name }.toHashSet()
        val migrated = Vault.items(app).mapNotNull { it.migratedFrom }.toHashSet()
        dir.listFiles()?.forEach { f ->
            when {
                f.name == ".nomedia" -> { }
                f.name.endsWith(".part") -> f.delete()
                f.name in migrated -> { } // migrated; its private copy is removed by VaultMigration.reconcile once verified
                f.name !in known -> Vault.adoptOrphan(app, Vault.Item(Uri.fromFile(f).toString(), "Recovered video ${f.name.take(8)}",
                    f.lastModified(), Vault.MODE_PRIVATE, origName = "Recovered ${f.name.take(8)}.${f.extension.ifBlank { "mp4" }}", size = f.length()))
            }
        }
    }
}
