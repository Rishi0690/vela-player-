package com.hark.velaplayer

import android.net.Uri

/**
 * v2.19.1 network streams: pure decisions shared by the player and the library (JVM-tested in StreamPolicyTest).
 *
 * - [seekMode]: what the timeline / double-tap / swipe / skip buttons may do for the current item.
 * - [canResume]: whether a resume position may be saved (Continue Watching) for it.
 * - [titleFor]: a readable title for a user-entered URL ("master.m3u8?token=…" is not a title).
 */
object StreamPolicy {
    enum class SeekMode {
        /** Normal on-demand media with a known duration: seek bar, double-tap, swipe all work. */
        SEEKABLE,
        /** Live stream with a seekable DVR window: seeking inside the window works, timeline shows LIVE. */
        LIVE_DVR,
        /** Live stream without a seekable window: no seeking, LIVE badge. */
        LIVE,
        /** Nothing known yet (item still preparing): seeking stays off silently. */
        LOADING,
        /** On-demand but the source can't seek (no index, server without byte ranges, unknown length…). */
        UNSEEKABLE;

        val canSeek: Boolean get() = this == SEEKABLE || this == LIVE_DVR
        val isLive: Boolean get() = this == LIVE || this == LIVE_DVR
    }

    /**
     * [seekable] = Player.isCurrentMediaItemSeekable, [live] = isCurrentMediaItemLive, [durationMs] = Player.duration
     * (C.TIME_UNSET is any negative value here), [placeholder] = the current timeline window is still ExoPlayer's
     * placeholder (or the timeline is empty), i.e. the source hasn't reported anything yet.
     */
    fun seekMode(seekable: Boolean, live: Boolean, durationMs: Long, placeholder: Boolean): SeekMode {
        val known = durationMs > 0
        return when {
            live && seekable && known -> SeekMode.LIVE_DVR
            live -> SeekMode.LIVE
            placeholder -> SeekMode.LOADING
            seekable && known -> SeekMode.SEEKABLE
            else -> SeekMode.UNSEEKABLE
        }
    }

    /** A resume point is only useful when we can seek back to it later; live streams never go to Continue Watching. */
    fun canResume(mode: SeekMode): Boolean = mode == SeekMode.SEEKABLE

    fun isNetwork(uri: String?): Boolean {
        if (uri == null) return false
        val i = uri.indexOf(':')
        if (i <= 0) return false
        val s = uri.substring(0, i).lowercase()
        return s == "http" || s == "https"
    }

    private val GENERIC = setOf("index", "master", "playlist", "manifest", "stream", "video", "media", "chunklist", "live", "play", "output", "main", "mono", "prog_index", "hls", "dash", "hd", "sd", "720p", "1080p", "480p", "360p")
    private val MEDIA_EXT = Regex("\\.(m3u8|mpd|mp4|m4v|mkv|webm|mov|ts|avi|flv|3gp|mp3|aac|m4a|ism)$", RegexOption.IGNORE_CASE)

    /** Readable title for a stream URL: last meaningful path segment without its extension, else the host. */
    fun titleFor(url: String): String = titleFor(url.substringBefore('#').substringBefore('?').let { path ->
        val noScheme = path.substringAfter("://", path)
        val host = noScheme.substringBefore('/').substringAfter('@').substringBefore(':')
        host to noScheme.substringAfter('/', "").split('/').filter { it.isNotBlank() }
    })

    private fun titleFor(parts: Pair<String, List<String>>): String {
        val (host, segs) = parts
        for (raw in segs.asReversed()) {
            val seg = try { Uri.decode(raw) } catch (_: Exception) { raw }
            val base = seg.replace(MEDIA_EXT, "").trim()
            if (base.isEmpty()) continue
            if (base.lowercase() in GENERIC) continue
            if (base.length > 40 && base.none { it == ' ' || it == '_' || it == '-' || it == '.' }) continue // token / hash
            return base.replace('_', ' ').replace('+', ' ').trim()
        }
        return host.ifBlank { "Stream" }
    }
}
