package com.hark.velaplayer

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import java.util.Locale

/**
 * Owns the playback engine (Media3 ExoPlayer), separate from the player UI.
 * Built from current settings (decoder, buffer, preferred languages) each time playback starts.
 */
object PlayerHolder {
    var player: ExoPlayer? = null
        private set

    fun create(ctx: Context): ExoPlayer {
        release()
        val c = ctx.applicationContext
        val sp = Prefs.sp(c)

        val renderers = DefaultRenderersFactory(c).setEnableDecoderFallback(true)
        if (sp.getString("decoder", "hw") == "sw") {
            renderers.setMediaCodecSelector(MediaCodecSelector { mime, secure, tunneling ->
                val all = MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
                all.filter { it.softwareOnly }.ifEmpty { all }
            })
        }

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
        return p
    }

    fun release() {
        player?.release()
        player = null
    }
}
