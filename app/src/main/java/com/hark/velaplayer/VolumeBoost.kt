package com.hark.velaplayer

import android.media.audiofx.AudioEffect
import android.media.audiofx.LoudnessEnhancer
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Player-only amplification above 100% (v2.1, always on since v2.2), using Android's LoudnessEnhancer on the
 * player's audio session.
 *
 * 101–200% maps to a real gain of up to about +6 dB (200% = twice the signal amplitude), on an amplitude-linear
 * (logarithmic in dB) curve so the first steps above 100% are gentle. The gain is hard-capped at [MAX_GAIN_MB].
 * The device's system volume is never touched here. LoudnessEnhancer has a built-in limiter, which keeps clipping
 * down, but it can't make a speaker louder than its hardware allows; loud scenes may be compressed rather than louder.
 * Some devices/audio pipelines (e.g. passthrough/offload) don't support the effect: [probe]/[set] mark it
 * [unsupported] and the UI then caps at 100% (no fake 200%).
 */
class VolumeBoost {
    companion object {
        /** Hard ceiling for the extra gain, in millibels (+6 dB ≈ 2× amplitude). */
        const val MAX_GAIN_MB = 600

        /** True when the platform lists a loudness enhancer effect at all (cheap static check). */
        fun deviceHasEffect(): Boolean = try {
            AudioEffect.queryEffects()?.any { it.type == AudioEffect.EFFECT_TYPE_LOUDNESS_ENHANCER } ?: true
        } catch (_: Throwable) { true } // unknown: let the real attempt decide
    }

    private var enhancer: LoudnessEnhancer? = null
    private var session = 0
    /** 0..100 extra percent (0 = normal, 100 = "200%"). */
    var boostPct = 0
        private set
    var unsupported = false
        private set

    /**
     * Checks up front (gain 0, effect disabled) whether the effect can attach to [audioSessionId], so the volume UI
     * never offers a boost range that can't work. Returns true when boost is available.
     */
    fun probe(audioSessionId: Int): Boolean {
        if (unsupported) return false
        if (!deviceHasEffect()) { unsupported = true; return false }
        if (audioSessionId <= 0) return true // not known yet; decided on first use
        if (enhancer != null && session == audioSessionId) return true
        return try {
            release()
            enhancer = LoudnessEnhancer(audioSessionId).also { it.setTargetGain(0); it.enabled = false }
            session = audioSessionId
            true
        } catch (_: Throwable) {
            unsupported = true; release(); false
        }
    }

    /** Applies [pct] (0..100) on [audioSessionId]. Returns false when the device can't do it. */
    fun set(audioSessionId: Int, pct: Int): Boolean {
        val p = pct.coerceIn(0, 100)
        if (p == 0) {
            boostPct = 0
            try { enhancer?.setTargetGain(0); enhancer?.enabled = false } catch (_: Exception) { }
            return true
        }
        if (unsupported || audioSessionId <= 0) return false
        try {
            if (enhancer == null || session != audioSessionId) {
                release()
                enhancer = LoudnessEnhancer(audioSessionId)
                session = audioSessionId
            }
            val e = enhancer ?: return false
            e.setTargetGain(gainMb(p))
            if (!e.enabled) e.enabled = true
            boostPct = p
            return true
        } catch (t: Throwable) {
            unsupported = true
            release()
            boostPct = 0
            return false
        }
    }

    /** Re-attaches the current boost after the player's audio session changed. */
    fun reattach(audioSessionId: Int) {
        val p = boostPct
        release()
        if (p > 0) set(audioSessionId, p) else probe(audioSessionId)
    }

    fun release() {
        try { enhancer?.release() } catch (_: Exception) { }
        enhancer = null
        session = 0
    }

    // Amplitude-linear: 150% = 1.5× amplitude (≈ +3.5 dB), 200% = 2× (≈ +6 dB). Capped.
    private fun gainMb(pct: Int): Int = (2000.0 * log10(1.0 + pct / 100.0)).roundToInt().coerceIn(0, MAX_GAIN_MB)
}
