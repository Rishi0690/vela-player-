package com.hark.velaplayer

import android.Manifest
import android.app.Activity
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
import android.widget.CheckBox
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * v2.5 Hidden Vault storage modes.
 *
 * "Hide in Vela only" ([Vault.MODE_APP]): nothing on disk changes; other apps still see the file.
 * "Move to private storage" ([Vault.MODE_PRIVATE]): copy into filesDir/vault, verify the copy byte count, write the
 * vault record (pending), then ask Android to delete the original (system dialog on Android 11+, storage permission
 * below). Only when the original is really gone does the record become final. If the user declines, the copy is
 * removed and the original is untouched. The original is never deleted before a verified copy exists.
 *
 * Restore copies the private file back into shared storage (original folder when Android allows it, otherwise
 * Movies/Vela Restored), reuses an identical file already there instead of making a duplicate, verifies it, re-points
 * playlists/favorites, and only then deletes the private copy.
 *
 * Private files are NOT encrypted. Android removes them when Vela is uninstalled or its storage is cleared.
 */
object VaultStorage {
    private const val DIR = "vault"
    private const val MIN_FREE = 200L * 1024 * 1024
    @Volatile var busy = false
        private set
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun dir(c: Context): File = File(c.filesDir, DIR).apply {
        if (!exists()) mkdirs()
        val nm = File(this, ".nomedia"); if (!nm.exists()) try { nm.createNewFile() } catch (_: Exception) { }
    }
    fun fileOf(i: Vault.Item): File? = if (i.isPrivate) Uri.parse(i.uri).path?.let { File(it) } else null
    fun exists(i: Vault.Item) = fileOf(i)?.exists() == true
    fun privateItems(c: Context) = Vault.items(c).filter { it.isPrivate }
    fun privateBytes(c: Context) = privateItems(c).sumOf { fileOf(it)?.length() ?: 0L }

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
    }

    // ------------------------------------------------------------------ dialogs

    private const val APP_TEXT = "The file stays where it is. Vela's lists stop showing it, but Gallery, file managers and other apps can " +
        "still see it. Nothing is copied or deleted.\nIf you uninstall Vela: the video stays on your phone and is simply visible as normal."
    private const val PRIVATE_TEXT = "Vela copies the video into its own private storage, checks the copy, and then Android asks you to " +
        "allow deleting the original. After that, Gallery and other apps can't see it. It is not encrypted. Needs free space for the copy.\n" +
        "If you uninstall Vela or clear its storage: Android deletes these videos and they can't be recovered. Unhide them first. " +
        "(Some phones offer to keep app data when uninstalling, but don't rely on it.)"
    private const val CONFIRM_TEXT = "I understand: uninstalling Vela or clearing its storage permanently deletes videos moved here."

    /**
     * Hide flow used everywhere videos enter the vault. [allowApp] false = only offer the private move (for videos
     * already hidden in Vela only).
     */
    fun chooseAndHide(act: Activity, req: Requester, entries: List<Entry>, allowApp: Boolean,
                      onAppHide: (List<Entry>) -> Unit, onPrivateDone: (Int) -> Unit) {
        val list = entries.distinctBy { it.uri }
        if (list.isEmpty()) return
        val n = list.size
        val d = act.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(px(22), px(8), px(22), 0) }
        fun body(t: String) = TextView(act).apply {
            text = t; alpha = 0.75f; textSize = 13f; setPadding(px(32), 0, 0, px(10))
        }
        val group = RadioGroup(act)
        val rbApp = RadioButton(act).apply { id = View.generateViewId(); text = "Hide in Vela only"; setTypeface(typeface, Typeface.BOLD) }
        val rbPriv = RadioButton(act).apply { id = View.generateViewId(); text = "Move to Vela's private storage"; setTypeface(typeface, Typeface.BOLD) }
        val appBody = body(APP_TEXT); val privBody = body(PRIVATE_TEXT)
        val check = CheckBox(act).apply { text = CONFIRM_TEXT; textSize = 13f }
        if (allowApp) { group.addView(rbApp); group.addView(appBody) }
        group.addView(rbPriv); group.addView(privBody)
        col.addView(group); col.addView(check)
        val scroll = ScrollView(act).apply { addView(col) }

        val dlg = MaterialAlertDialogBuilder(act)
            .setTitle(if (n == 1) "Hide this video" else "Hide $n videos")
            .setView(scroll)
            .setPositiveButton("Hide", null)
            .setNegativeButton("Cancel", null).create()
        fun sync() {
            val priv = group.checkedRadioButtonId == rbPriv.id
            check.visibility = if (priv) View.VISIBLE else View.GONE
            dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                text = if (priv) "Move" else "Hide"
                isEnabled = group.checkedRadioButtonId != -1 && (!priv || check.isChecked)
            }
        }
        group.setOnCheckedChangeListener { _, _ -> sync() }
        check.setOnCheckedChangeListener { _, _ -> sync() }
        dlg.setOnShowListener {
            group.check(if (allowApp) rbApp.id else rbPriv.id); sync()
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val priv = group.checkedRadioButtonId == rbPriv.id
                dlg.dismiss()
                if (priv) moveToPrivate(act, req, list, onPrivateDone) else onAppHide(list)
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
            dlg = MaterialAlertDialogBuilder(act).setTitle(title).setView(col).setCancelable(false).create()
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
        MaterialAlertDialogBuilder(act).setTitle(title).setMessage(msg.trim()).setPositiveButton("OK", null).show()
    }

    // ------------------------------------------------------------------ move into private storage

    private sealed class R<out T> { data class Ok<T>(val v: T) : R<T>(); data class Fail(val why: String) : R<Nothing>() }

    fun moveToPrivate(act: Activity, req: Requester, entries: List<Entry>, done: (Int) -> Unit) {
        if (busy) { report(act, "Please wait", 0, listOf("Another vault move is still running"), ""); return }
        val start = {
            busy = true
            val app = act.applicationContext
            val prog = Progress(act, "Moving to private storage")
            io.execute {
                val made = ArrayList<Vault.Item>(); val fails = ArrayList<String>()
                entries.forEachIndexed { i, e ->
                    prog.set(i, entries.size, "Copying")
                    when (val r = copyIn(app, e)) {
                        is R.Ok -> { Vault.addPendingPrivate(app, r.v); made += Vault.item(app, r.v.uri) ?: r.v }
                        is R.Fail -> fails += "${e.title}: ${r.why}"
                    }
                }
                prog.close()
                main.post {
                    if (made.isEmpty()) { busy = false; report(act, "Nothing was moved", 0, fails, ""); done(0); return@post }
                    deleteOriginals(act, req, made) { ok -> finishMove(act, app, made, ok, fails, done) }
                }
            }
        }
        if (Build.VERSION.SDK_INT < 30) req.legacyWrite { ok ->
            if (ok) start() else report(act, "Nothing was moved", 0, listOf("Storage permission is needed to remove the original file"), "")
        } else start()
    }

    /** Android 11+: one system dialog for all originals. Older: delete directly with the storage permission. */
    private fun deleteOriginals(act: Activity, req: Requester, made: List<Vault.Item>, then: (Boolean) -> Unit) {
        val uris = made.mapNotNull { it.orig }.map { Uri.parse(it) }
        if (Build.VERSION.SDK_INT >= 30) {
            try { req.sender(MediaStore.createDeleteRequest(act.contentResolver, uris).intentSender) { ok -> then(ok) } }
            catch (_: Exception) { then(false) }
        } else io.execute {
            for (it in made) try {
                val u = Uri.parse(it.orig)
                val path = dataPath(act.applicationContext, u)
                path?.let { p -> File(p).delete() }
                act.contentResolver.delete(u, null, null)
            } catch (_: Exception) { }
            main.post { then(true) }
        }
    }

    private fun finishMove(act: Activity, app: Context, made: List<Vault.Item>, granted: Boolean, fails: MutableList<String>, done: (Int) -> Unit) {
        io.execute {
            var moved = 0
            for (it in made) {
                when (originalExists(app, it.orig)) {
                    false -> { Vault.confirmPrivate(app, it.uri); moved++ }
                    true -> { Vault.revertPrivate(app, it.uri); fileOf(it)?.delete()
                        fails += "${it.title}: ${if (granted) "Android didn't remove the original" else "you didn't allow removing the original"}, so the copy was removed and nothing changed" }
                    null -> { Vault.confirmPrivate(app, it.uri); moved++ } // can't tell: keep the copy (never risk losing the video)
                }
            }
            main.post {
                busy = false
                report(act, if (moved > 0) "Moved to private storage" else "Nothing was moved", moved, fails,
                    "$moved video${if (moved == 1) " is" else "s are"} now in Vela's private storage. Gallery and other apps can't see " +
                        "${if (moved == 1) "it" else "them"}. Remember: uninstalling Vela deletes ${if (moved == 1) "it" else "them"}. Unhide first.")
                done(moved)
            }
        }
    }

    private fun copyIn(c: Context, e: Entry): R<Vault.Item> {
        val uri = Uri.parse(e.uri)
        if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY) return R.Fail("only videos from the phone's media library can be moved")
        var name: String? = null; var mime: String? = null; var size = 0L; var dur = 0L; var data: String? = null; var rel: String? = null
        try {
            val proj = arrayListOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE,
                MediaStore.Video.VideoColumns.DURATION, MediaStore.MediaColumns.DATA)
            if (Build.VERSION.SDK_INT >= 29) proj += MediaStore.MediaColumns.RELATIVE_PATH
            c.contentResolver.query(uri, proj.toTypedArray(), null, null, null)?.use { cur ->
                if (!cur.moveToFirst()) return R.Fail("the file wasn't found")
                name = cur.getString(0); mime = cur.getString(1); size = cur.getLong(2); dur = cur.getLong(3); data = cur.getString(4)
                if (Build.VERSION.SDK_INT >= 29) rel = cur.getString(5)
            } ?: return R.Fail("the file wasn't found")
        } catch (_: SecurityException) { return R.Fail("Vela isn't allowed to read this file")
        } catch (_: Exception) { return R.Fail("the file couldn't be read") }
        val dir = dir(c)
        val free = try { StatFs(dir.path).availableBytes } catch (_: Exception) { Long.MAX_VALUE }
        if (size > 0 && free < size + MIN_FREE) return R.Fail("not enough free space (needs ${Formatter.formatShortFileSize(c, size)})")
        val fname = name ?: (e.title + ".mp4")
        val ext = fname.substringAfterLast('.', "").takeIf { it.isNotBlank() && it.length <= 5 }?.let { ".$it" } ?: ".mp4"
        val dest = File(dir, UUID.randomUUID().toString() + ext)
        val part = File(dir, dest.name + ".part")
        var count = 0L
        try {
            val input = c.contentResolver.openInputStream(uri) ?: return R.Fail("the file couldn't be opened")
            input.use { ins -> FileOutputStream(part).use { out ->
                val buf = ByteArray(256 * 1024)
                while (true) { val r = ins.read(buf); if (r < 0) break; out.write(buf, 0, r); count += r }
                out.fd.sync()
            } }
        } catch (_: Exception) { part.delete(); return R.Fail(if (count > 0) "copying stopped (storage full?)" else "the file couldn't be read") }
        if (count == 0L || (size > 0 && count != size)) { part.delete(); return R.Fail("the copy didn't match the original, so nothing was changed") }
        if (!part.renameTo(dest)) { part.delete(); return R.Fail("the copy couldn't be saved") }
        val origDir = rel ?: data?.let { File(it).parent }
        return R.Ok(Vault.Item(Uri.fromFile(dest).toString(), e.title, System.currentTimeMillis(), Vault.MODE_PRIVATE,
            orig = e.uri, origDir = origDir, origName = fname, mime = mime ?: "video/mp4", size = count, dur = dur))
    }

    private fun dataPath(c: Context, uri: Uri): String? = try {
        c.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (_: Exception) { null }

    /** true / false, or null when it can't be told (then nothing is deleted). */
    private fun originalExists(c: Context, orig: String?): Boolean? {
        if (orig == null) return false
        return try {
            c.contentResolver.query(Uri.parse(orig), arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count > 0 } ?: null
        } catch (_: SecurityException) { null } catch (_: Exception) { null }
    }

    // ------------------------------------------------------------------ restore to shared storage

    fun restore(act: Activity, req: Requester, items: List<Vault.Item>, done: (Int) -> Unit) {
        val list = items.filter { it.isPrivate }
        if (list.isEmpty()) { done(0); return }
        if (busy) { report(act, "Please wait", 0, listOf("Another vault move is still running"), ""); return }
        val start = {
            busy = true
            val app = act.applicationContext
            val prog = Progress(act, "Restoring videos")
            io.execute {
                var ok = 0; val fails = ArrayList<String>(); val where = LinkedHashSet<String>()
                list.forEachIndexed { i, it ->
                    prog.set(i, list.size, "Restoring")
                    when (val r = restoreOne(app, it)) {
                        is R.Ok -> { Vault.finishRestore(app, it.uri, r.v.first); fileOf(it)?.delete(); ok++; where += r.v.second }
                        is R.Fail -> fails += "${it.title}: ${r.why}. It's still safe in the vault."
                    }
                }
                prog.close()
                main.post {
                    busy = false
                    report(act, if (ok > 0) "Restored" else "Nothing was restored", ok, fails,
                        "$ok video${if (ok == 1) " is" else "s are"} back in phone storage (${where.joinToString(", ")}) and in the normal library.")
                    done(ok)
                }
            }
        }
        if (Build.VERSION.SDK_INT < 29) req.legacyWrite { g ->
            if (g) start() else report(act, "Nothing was restored", 0, listOf("Storage permission is needed to put videos back in phone storage"), "")
        } else start()
    }

    private val allowedTop = listOf("DCIM/", "Movies/", "Pictures/")
    private const val FALLBACK = "Movies/Vela Restored/"

    /** Returns (new uri, folder shown to the user). */
    private fun restoreOne(c: Context, it: Vault.Item): R<Pair<String, String>> {
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
                    if (cur.moveToFirst()) return R.Ok(android.content.ContentUris.withAppendedId(coll, cur.getLong(0)).toString() to d.trimEnd('/'))
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
     * Run off the main thread before showing the vault. Finishes moves interrupted by the app being closed:
     * a pending item whose original still exists is rolled back (copy removed, original untouched); one whose
     * original is gone is kept. Private files with no record are adopted (never deleted). Half-written copies
     * (.part, original not yet touched) are removed.
     */
    fun reconcile(c: Context) {
        if (busy) return
        val app = c.applicationContext
        for (it in Vault.items(app)) if (it.isPrivate && it.pending) {
            if (!exists(it)) { Vault.revertPrivate(app, it.uri); continue }
            when (originalExists(app, it.orig)) {
                true -> { Vault.revertPrivate(app, it.uri); fileOf(it)?.delete() }
                false -> Vault.confirmPrivate(app, it.uri)
                null -> { }
            }
        }
        val dir = File(app.filesDir, DIR); if (!dir.exists()) return
        val known = Vault.items(app).filter { it.isPrivate }.mapNotNull { fileOf(it)?.name }.toHashSet()
        dir.listFiles()?.forEach { f ->
            when {
                f.name == ".nomedia" -> { }
                f.name.endsWith(".part") -> f.delete()
                f.name !in known -> Vault.adoptOrphan(app, Vault.Item(Uri.fromFile(f).toString(), "Recovered video ${f.name.take(8)}",
                    f.lastModified(), Vault.MODE_PRIVATE, origName = "Recovered ${f.name.take(8)}.${f.extension.ifBlank { "mp4" }}", size = f.length()))
            }
        }
    }
}
