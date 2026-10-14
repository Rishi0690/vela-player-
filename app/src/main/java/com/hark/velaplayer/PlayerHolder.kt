package com.hark.velaplayer

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import java.util.Locale

/**
 * Owns the playback engine (Media3 ExoPlayer), separate from the player UI.
 * Built from current settings (decoder preference, buffer, preferred languages) each time playback starts.
 */
object PlayerHolder {
    var player: ExoPlayer? = null
        private set

    /** v2.19: decoder ordering + per-video fallback overrides; lives for the app process so overrides survive a new player. */
    val decoders = DecoderPolicy()

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

        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setUserAgent("VelaPlayer/1.0")
        val sourceFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(c, http))

        val p = ExoPlayer.Builder(c, renderers)
            .setLoadControl(loadControl)
            .setTrackSelector(selector)
            .setMediaSourceFactory(sourceFactory)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player = p
        WidgetUpdater.attach(c, p) // v2.14: Now Playing widgets follow this player
        return p
    }

    fun release() {
        if (player != null) WidgetUpdater.detach()
        player?.release()
        player = null
    }
}
