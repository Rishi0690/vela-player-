package com.hark.velaplayer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import java.util.Locale

/**
 * Owns the playback engine (Media3 ExoPlayer), separate from the player UI.
 * Built from current settings (decoder preference, buffer, preferred languages) each time playback starts.
 */
object PlayerHolder {
    var player: ExoPlayer? = null
        private set

    /**
     * v2.21: the ONE media session (Bluetooth / headset buttons, lock screen, notification). Lives exactly as long as
     * [player], whether or not background audio is on; [PlaybackService] only adopts it for the notification.
     * Released with the player so other apps get their media buttons back as soon as Vela's player closes.
     */
    var session: MediaSession? = null
        private set

    /** v2.19: decoder ordering + per-video fallback overrides; lives for the app process so overrides survive a new player. */
    val decoders = DecoderPolicy()

    /**
     * v2.19.1: media ids that failed to open as a local file and are re-opened ONCE with MP4/fMP4 edit lists ignored
     * (broken `elst` boxes make ExoPlayer reject files other players open). In memory for the app process, per item:
     * every other video keeps the normal extractors. [PlayerActivity.tryContainerRepair] adds ids here.
     */
    val repaired: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    /**
     * Normal extractors; constant-bitrate seeking lets MP3/AAC(ADTS)/AMR without a seek index still seek (streams too).
     * v2.19.2: MKV/WebM go through [VelaExtractorsFactory] (VelaMatroskaExtractor: nested SeekHead → Cues, and a
     * cluster binary search when there are no usable Cues) for both the normal and the MP4-repair factory.
     */
    fun extractors(repair: Boolean = false): ExtractorsFactory = VelaExtractorsFactory(defaultExtractors(repair))

    /** The plain Media3 factory with Vela's settings (used inside [VelaExtractorsFactory]; tests compare against it). */
    fun defaultExtractors(repair: Boolean = false): DefaultExtractorsFactory = DefaultExtractorsFactory()
        .setConstantBitrateSeekingEnabled(true)
        .apply {
            if (repair) {
                setMp4ExtractorFlags(Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)
                setFragmentedMp4ExtractorFlags(FragmentedMp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)
            }
        }

    /**
     * HTTP for user-entered streams. v2.19.1: the FIRST request of a resource asks for `Range: bytes=0-`. Servers that
     * support ranges answer 206 with the total size even when they'd stream a plain 200 without Content-Length, and a
     * known length is what makes a progressive .ts/.aac/fMP4 stream seekable instead of being treated as live.
     * DefaultHttpDataSource puts its own Range header over this one on every later request (seeks, resumes), and
     * servers without range support just answer 200 as before.
     */
    fun httpFactory(): DefaultHttpDataSource.Factory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setUserAgent("VelaPlayer/1.0")
        .setDefaultRequestProperties(mapOf("Range" to "bytes=0-"))

    /** Picks the repair factory only for media ids in [repaired]; everything else goes through the normal one. */
    private class RepairingSourceFactory(
        private val normal: DefaultMediaSourceFactory,
        private val repair: DefaultMediaSourceFactory,
    ) : MediaSource.Factory {
        override fun setDrmSessionManagerProvider(p: DrmSessionManagerProvider): MediaSource.Factory {
            normal.setDrmSessionManagerProvider(p); repair.setDrmSessionManagerProvider(p); return this
        }
        override fun setLoadErrorHandlingPolicy(p: LoadErrorHandlingPolicy): MediaSource.Factory {
            normal.setLoadErrorHandlingPolicy(p); repair.setLoadErrorHandlingPolicy(p); return this
        }
        override fun getSupportedTypes(): IntArray = normal.supportedTypes
        override fun createMediaSource(mediaItem: MediaItem): MediaSource =
            if (mediaItem.mediaId in repaired) repair.createMediaSource(mediaItem) else normal.createMediaSource(mediaItem)
    }

    fun create(ctx: Context): ExoPlayer {
        release()
        val c = ctx.applicationContext
        val sp = Prefs.sp(c)

        // v2.19: the saved choice orders the decoders (preferred kind first, the other kind kept as fallback);
        // per-video automatic switching lives in [decoders]. The saved "decoder" setting is only read here, never written.
        decoders.setPreferred(sp.getString("decoder", "hw"))
        val renderers = DefaultRenderersFactory(c).setEnableDecoderFallback(true).setMediaCodecSelector(decoders)

        val (minBuf, maxBuf) = when (sp.getString("buffer", "normal")) {
            "low" -> 15_000 to 30_000
            "high" -> 60_000 to 150_000
            else -> 50_000 to 50_000
        }
        val loadControl = DefaultLoadControl.Builder().setBufferDurationsMs(minBuf, maxBuf, 2_500, 5_000).build()

        val selector = DefaultTrackSelector(c)
        val params = selector.buildUponParameters()
        // Preferred languages: what the user picked last time (when remembered), otherwise the fixed Settings value.
        // If the language isn't in a video, ExoPlayer quietly falls back to its normal default track.
        Prefs.preferredAudioLang(c)?.let { params.setPreferredAudioLanguage(it) }
        val subLang = Prefs.preferredSubLang(c)
        when (Prefs.rememberedSubState(c)) {
            "off" -> params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            "on" -> {
                params.setPreferredTextLanguage(subLang ?: Locale.getDefault().language)
                params.setSelectUndeterminedTextLanguage(true)
            }
            else -> if (sp.getBoolean("sub_auto", false)) {
                params.setPreferredTextLanguage(subLang ?: Locale.getDefault().language)
                params.setSelectUndeterminedTextLanguage(true)
            } else if (subLang != null) {
                params.setPreferredTextLanguage(subLang)
            }
        }
        selector.setParameters(params)

        val http = httpFactory()
        val dataSource = DefaultDataSource.Factory(c, http)
        val sourceFactory = RepairingSourceFactory(
            DefaultMediaSourceFactory(dataSource, extractors()),
            DefaultMediaSourceFactory(dataSource, extractors(repair = true)))

        val p = ExoPlayer.Builder(c, renderers)
            .setLoadControl(loadControl)
            .setTrackSelector(selector)
            .setMediaSourceFactory(sourceFactory)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player = p
        session = createSession(c, p)
        WidgetUpdater.attach(c, p) // v2.14: Now Playing widgets follow this player
        return p
    }

    /** Builds the session around [SessionPlayer] (see MediaButtons.kt). Never throws: playback works without it. */
    fun createSession(c: Context, p: ExoPlayer): MediaSession? = try {
        val wrapped = SessionPlayer(p)
        val open = PendingIntent.getActivity(
            c, 0, Intent(c, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        MediaSession.Builder(c, wrapped)
            .setId(SESSION_ID)
            .setSessionActivity(open)
            .setCallback(MediaButtons.Callback(MediaKeys.forMainLooper(wrapped)))
            .build()
    } catch (e: Exception) { android.util.Log.w("Vela", "media session unavailable", e); null }

    const val SESSION_ID = "vela_player"

    fun release() {
        if (player != null) WidgetUpdater.detach()
        // Session first (it still talks to the player while releasing), then the engine.
        session?.let { s -> PlaybackService.instance?.dropSession(s); runCatching { s.release() } }
        session = null
        player?.release()
        player = null
    }
}
