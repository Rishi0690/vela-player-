package com.hark.velaplayer

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.content.IntentCompat
import java.io.File

/**
 * v2.20.1: the ONE Android Sharesheet target ("Vela Player"). Other apps share a video (ACTION_SEND with a video type or a
 * stream mime type), several videos (ACTION_SEND_MULTIPLE of videos) or a link (ACTION_SEND text/plain). This invisible
 * trampoline parses the share ([ShareIntents.parse]), forwards it to [PlayerActivity] through the SAME contract as
 * the library's Play all (EXTRA_URIS/EXTRA_TITLES, source = external), so ACTION_VIEW's whole playback path applies
 * (decoder fallback, MKV fixes, MP4 repair, system engine fallback, StreamPolicy), then finishes.
 *
 * URI grants: the Sharesheet grants temporary read access to THIS activity only (via ClipData). The forwarded intent
 * carries every content uri again as ClipData + FLAG_GRANT_READ_URI_PERMISSION so the grant moves to the player and
 * outlives this trampoline. Persistable grants are taken when the sender offered one (document uris only).
 */
class ShareReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        try {
            when (val r = ShareIntents.parse(this, intent)) {
                is ShareIntents.Result.Play -> {
                    ShareIntents.takePersistable(this, intent, r.uris)
                    startActivity(ShareIntents.forward(this, r))
                    if (r.skipped > 0) toast("Skipped ${r.skipped} item(s) that aren't videos")
                }
                is ShareIntents.Result.Reject -> toast(r.message)
            }
        } catch (_: Exception) {
            toast(ShareIntents.NOTHING_PLAYABLE)
        }
        finish()
        if (Build.VERSION.SDK_INT < 34) @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    private fun toast(msg: String) = Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
}

/** v2.20.1: Sharesheet intent parsing + forwarding (unit-tested in `ShareReceiverTest`). */
object ShareIntents {
    const val NOTHING_PLAYABLE = "Nothing playable shared"

    sealed class Result {
        /** [uris] in shared order; [titles] parallel; [skipped] = shared items that were not videos. */
        data class Play(val uris: List<Uri>, val titles: List<String>, val skipped: Int = 0) : Result()
        data class Reject(val message: String) : Result()
    }

    private val STREAM_MIMES = setOf(
        "application/x-mpegurl", "application/vnd.apple.mpegurl", "audio/mpegurl", "audio/x-mpegurl",
        "application/dash+xml", "application/x-matroska", "application/mp4", "application/vnd.rn-realmedia",
    )
    private val VIDEO_EXTS = setOf(
        "mp4", "m4v", "mkv", "webm", "mov", "avi", "ts", "m2ts", "mts", "flv", "3gp", "3g2", "ogv", "wmv", "mpg",
        "mpeg", "vob", "m3u8", "mpd", "f4v", "divx", "asf", "rmvb",
    )
    private val STREAM_EXTS = setOf("m3u8", "mpd", "mp4", "m4v", "mkv", "webm", "mov", "ts", "flv", "3gp", "ogv", "avi", "m2ts", "mpg", "mpeg")
    /** Links that are clearly not media (a web page, an image, a document): never sent to the player. */
    private val NON_MEDIA_EXTS = setOf(
        "html", "htm", "php", "asp", "aspx", "jsp", "jpg", "jpeg", "png", "gif", "webp", "svg", "bmp", "heic", "pdf",
        "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "zip", "rar", "7z", "apk", "json", "xml", "css", "js",
    )
    private val URL = Regex("""(?i)\bhttps?://[^\s<>"'`]+""")

    fun isVideoMime(m: String?): Boolean {
        val t = m?.lowercase()?.substringBefore(';')?.trim() ?: return false
        return t.startsWith("video/") || t in STREAM_MIMES
    }

    fun ext(name: String?): String? = name?.substringBefore('?')?.substringBefore('#')?.substringAfterLast('/')
        ?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() && it.length <= 5 }

    /** First http(s) link in shared text: a media-looking link (by extension) wins, else the first non-page link. */
    fun extractUrl(text: CharSequence?): String? {
        if (text.isNullOrBlank()) return null
        val urls = URL.findAll(text).map { it.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '>', '"', '\'') }
            .filter { Uri.parse(it).host.isNullOrEmpty().not() }.toList()
        if (urls.isEmpty()) return null
        urls.firstOrNull { ext(Uri.parse(it).path) in STREAM_EXTS }?.let { return it }
        return urls.firstOrNull { ext(Uri.parse(it).path) !in NON_MEDIA_EXTS }
    }

    fun parse(c: Context, intent: Intent?): Result {
        if (intent == null) return Result.Reject(NOTHING_PLAYABLE)
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return Result.Reject(NOTHING_PLAYABLE)
        // 1. Shared files: EXTRA_STREAM (single or list) + ClipData items (the Sharesheet's grant channel).
        val streams = LinkedHashSet<Uri>()
        if (action == Intent.ACTION_SEND) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { streams += it }
        } else {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { l -> l.filterNotNull().forEach { streams += it } }
        }
        val clipTexts = ArrayList<CharSequence>()
        intent.clipData?.let { cd -> for (i in 0 until cd.itemCount) { val it = cd.getItemAt(i); it.uri?.let { u -> streams += u }; it.text?.let { t -> clipTexts += t } } }
        val uris = ArrayList<Uri>(); val titles = ArrayList<String>(); var skipped = 0
        for (u in streams) {
            val name = when (u.scheme?.lowercase()) {
                "content" -> displayName(c, u)
                "file" -> if (ownPrivate(c, u)) null else (u.lastPathSegment ?: "Video")
                "http", "https" -> StreamPolicy.titleFor(u.toString())
                else -> null
            }
            if (name == null || !acceptable(c, u, name, intent.type)) { skipped++; continue }
            uris += u; titles += name
        }
        if (uris.isNotEmpty()) return Result.Play(uris, titles, skipped)
        // 2. A shared link (text/plain, also some apps' video shares that only carry a URL).
        val text = sequenceOf(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)).plus(clipTexts.asSequence()).filterNotNull()
        for (t in text) {
            val url = extractUrl(t) ?: continue
            return Result.Play(listOf(Uri.parse(url)), listOf(StreamPolicy.titleFor(url)))
        }
        return Result.Reject(NOTHING_PLAYABLE)
    }

    /** A shared item is played when its own type (or name) says video; an unknown type falls back to the share's type. */
    private fun acceptable(c: Context, u: Uri, name: String, shareType: String?): Boolean {
        if (u.scheme == "http" || u.scheme == "https") return ext(u.path) !in NON_MEDIA_EXTS
        val own = if (u.scheme == "content") try { c.contentResolver.getType(u) } catch (_: Exception) { null } else null
        if (isVideoMime(own)) return true
        val e = ext(name)
        if (e in VIDEO_EXTS) return true
        val unknown = own == null || own == "application/octet-stream" || own == "*/*"
        return unknown && isVideoMime(shareType) && (e == null || e !in NON_MEDIA_EXTS)
    }

    /** null = this uri can't be read at all (no grant / gone). */
    private fun displayName(c: Context, u: Uri): String? = try {
        c.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) cur.getString(0) else null
        } ?: u.lastPathSegment?.substringAfterLast('/') ?: "Video"
    } catch (_: SecurityException) { null } catch (_: Exception) { u.lastPathSegment?.substringAfterLast('/') ?: "Video" }

    /** file:// into Vela's own data dir (incl. Vault private storage) is never accepted from another app. */
    private fun ownPrivate(c: Context, u: Uri): Boolean {
        val p = u.path ?: return true
        return try {
            val f = File(p).canonicalPath
            val roots = listOfNotNull(c.applicationInfo.dataDir, c.filesDir?.parent, if (Build.VERSION.SDK_INT >= 24) c.dataDir?.path else null)
                .map { File(it).canonicalPath }
            roots.any { f == it || f.startsWith("$it/") }
        } catch (_: Exception) { true }
    }

    /** The intent for [PlayerActivity]: same extras as the library's Play all, content grants re-attached as ClipData. */
    fun forward(c: Context, r: Result.Play): Intent {
        val i = Intent(c, PlayerActivity::class.java)
            .putStringArrayListExtra(PlayerActivity.EXTRA_URIS, ArrayList(r.uris.map { it.toString() }))
            .putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, ArrayList(r.titles))
            .putExtra(PlayerActivity.EXTRA_INDEX, 0)
            .putExtra(PlayerSession.EXTRA_SOURCE, PlayerSession.SOURCE_EXTERNAL)
        val content = r.uris.filter { it.scheme == "content" }
        if (content.isNotEmpty()) {
            val clip = ClipData.newRawUri("shared", content[0])
            for (k in 1 until content.size) clip.addItem(ClipData.Item(content[k]))
            i.clipData = clip
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return i
    }

    /** Keeps access across restarts when the sender offered a persistable grant (document uris only; best effort). */
    fun takePersistable(c: Context, intent: Intent, uris: List<Uri>) {
        if (intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0) return
        for (u in uris) {
            if (u.scheme != "content") continue
            try {
                if (DocumentsContract.isDocumentUri(c, u)) c.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { }
        }
    }
}
