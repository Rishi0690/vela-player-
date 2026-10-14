package com.hark.velaplayer

import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import java.util.concurrent.ConcurrentHashMap

/**
 * v2.19 smart decoder switching (per video, never touches the saved "decoder" setting).
 *
 * - It is the ExoPlayer [MediaCodecSelector]. The user's preference (Settings › Video decoder) decides which KIND of
 *   decoder (hardware / software) is listed first, but the other kind is always listed after it. ExoPlayer then
 *   (a) sorts decoders that declare support for the exact format first (so a format the preferred kind can't handle
 *   goes straight to the other kind) and (b) with decoder fallback on, moves down the list when a decoder fails to start.
 * - Runtime failures (a decoder that started but then failed, or every listed decoder failing) reach the player UI as
 *   a [androidx.media3.common.PlaybackException]. [onDecoderError] then switches THAT video (and that track type: video
 *   or audio) to the other kind, strictly (only that kind is listed, so the format-support sort can't bring back the
 *   decoder that just failed). Each kind is tried at most once per video and track type, so there is no retry loop.
 * - Overrides live in memory for this app process only and are keyed by media id (uri). A video that needed the other
 *   decoder keeps it when it comes back in this session; every other video uses the user's preference.
 *
 * Threading: [getDecoderInfos] runs on ExoPlayer's playback thread, everything else on the main thread. The active modes
 * are single volatile immutable values and the override map is concurrent.
 */
class DecoderPolicy(
    /** Decoder lookup; replaceable for JVM tests. */
    private val source: (mime: String, secure: Boolean, tunneling: Boolean) -> List<MediaCodecInfo> =
        { mime, secure, tunneling -> MediaCodecUtil.getDecoderInfos(mime, secure, tunneling) },
) : MediaCodecSelector {

    enum class Kind {
        HW, SW;
        val other: Kind get() = if (this == HW) SW else HW
        val label: String get() = if (this == HW) "hardware" else "software"
    }

    /** [first] kind is listed first; [strict] = only that kind (unless the device has none, then everything). */
    data class Mode(val first: Kind, val strict: Boolean = false)

    private class ItemState {
        @Volatile var video: Kind? = null
        @Volatile var audio: Kind? = null
        val triedVideo: MutableSet<Kind> = ConcurrentHashMap.newKeySet()
        val triedAudio: MutableSet<Kind> = ConcurrentHashMap.newKeySet()
    }

    private data class Active(val id: String?, val video: Mode, val audio: Mode)

    @Volatile var preferred: Kind = Kind.HW
        private set

    private val items = ConcurrentHashMap<String, ItemState>()

    @Volatile private var active = Active(null, Mode(Kind.HW), Mode(Kind.HW))

    /** From the saved setting ("hw" / "sw"). Only called when a player is built; never writes the setting. */
    fun setPreferred(pref: String?) {
        preferred = if (pref == "sw") Kind.SW else Kind.HW
        active = Active(null, Mode(preferred), Mode(preferred))
    }

    /** Modes a video should use: its session override, otherwise the user's preference. */
    fun modesFor(id: String?): Pair<Mode, Mode> {
        val st = id?.let { items[it] }
        val v = st?.video?.let { Mode(it, strict = true) } ?: Mode(preferred)
        val a = st?.audio?.let { Mode(it, strict = true) } ?: Mode(preferred)
        return v to a
    }

    /**
     * The player moved to [id]. Returns true when the decoder ordering changed, i.e. codecs created (or kept) under the
     * old ordering should be re-created for this video.
     */
    fun activate(id: String?): Boolean {
        val (v, a) = modesFor(id)
        val old = active
        active = Active(id, v, a)
        return old.video != v || old.audio != a
    }

    val activeId: String? get() = active.id
    fun activeMode(video: Boolean): Mode = if (video) active.video else active.audio

    /** True when [id] currently plays with an automatic override for that track type. */
    fun isOverridden(id: String?, video: Boolean): Boolean {
        val st = id?.let { items[it] } ?: return false
        return (if (video) st.video else st.audio) != null
    }

    /**
     * A decoder for [id] failed. [failing] = kind of the decoder that failed (null when unknown: the first kind of the
     * active ordering is assumed). [hasKind] says whether the device has any decoder of a kind for this format.
     * Returns the kind this video now switches to, or null when nothing is left to try (show the error).
     */
    fun onDecoderError(id: String, video: Boolean, failing: Kind?, hasKind: (Kind) -> Boolean): Kind? {
        val st = items.getOrPut(id) { ItemState() }
        val tried = if (video) st.triedVideo else st.triedAudio
        val current = (if (video) st.video else st.audio) ?: activeMode(video).first
        val bad = failing ?: current
        tried.add(bad)
        val target = bad.other
        if (target in tried || !hasKind(target)) return null
        tried.add(target)
        if (video) st.video = target else st.audio = target
        if (active.id == id) activate(id)
        return target
    }

    /** True when both kinds were already tried for this video (used to word the final error). */
    fun exhausted(id: String?, video: Boolean): Boolean {
        val st = id?.let { items[it] } ?: return false
        return (if (video) st.triedVideo else st.triedAudio).size >= 2
    }

    /** Forget all session overrides (not called by the app today; kept for tests and a future "reset"). */
    fun clear() { items.clear(); active = Active(null, Mode(preferred), Mode(preferred)) }

    // ------------------------------------------------------------------ MediaCodecSelector (playback thread)

    override fun getDecoderInfos(mimeType: String, requiresSecureDecoder: Boolean, requiresTunnelingDecoder: Boolean): List<MediaCodecInfo> {
        val all = source(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        val a = active
        return order(all, if (MimeTypes.isAudio(mimeType)) a.audio else a.video)
    }

    /** Whether the device has a decoder of [kind] for [mime] (main thread; MediaCodecUtil caches the query). */
    fun hasKind(mime: String?, kind: Kind): Boolean {
        if (mime == null) return true
        return try { source(mime, false, false).any { kindOf(it) == kind } } catch (e: Exception) { true }
    }

    /** Kind of the decoder called [name] for [mime], or null when it can't be found. */
    fun kindOfName(name: String?, mime: String?): Kind? {
        if (name == null || mime == null) return null
        return try { source(mime, false, false).firstOrNull { it.name == name }?.let { kindOf(it) } } catch (e: Exception) { null }
    }

    companion object {
        fun kindOf(info: MediaCodecInfo): Kind = if (info.softwareOnly) Kind.SW else Kind.HW

        /** Preferred kind first (device order kept inside each kind); strict = only that kind when it exists. */
        fun order(all: List<MediaCodecInfo>, mode: Mode): List<MediaCodecInfo> {
            val (first, rest) = all.partition { kindOf(it) == mode.first }
            if (mode.strict) return first.ifEmpty { all }
            return first + rest
        }
    }
}
