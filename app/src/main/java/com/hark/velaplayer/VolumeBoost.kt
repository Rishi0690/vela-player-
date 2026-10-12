package com.hark.velaplayer

import android.media.audiofx.LoudnessEnhancer
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Player-only amplification above 100% (v2.1), using Android's LoudnessEnhancer on the player's audio session.
 *
 * 101–200% maps to a real gain of up to about +6 dB (200% = twice the signal amplitude). The device's
 * system volume is never touched here. LoudnessEnhancer has a built-in limiter, which keeps clipping down,
 * but it can't make a speaker louder than its hardware allows; loud scenes may be compressed rather than louder.
 * Some devices/audio pipelines (e.g. passthrough/offload) don't support the effect: [set] returns false then.
 */
class VolumeBoost {
    private var enhancer: LoudnessEnhancer? = null
    private var session = 0
    /** 0..100 extra percent (0 = normal, 100 = "200%"). */
    var boostPct = 0
        private set
    var unsupported = false
        private set

    /** Applies [pct] (0..100) on [audioSessionId]. Returns false when the device can't do it. */
    fun set(audioSessionId: Int, pct: Int): Boolean {
        val p = pct.coerceIn(0, 100)
        if (p == 0) {
            boostPct = 0
            try { enhancer?.setTargetGain(0); enhancer?.enabled = false } catch (_: Exception) { }
            return true
        }
        if (unsupported || audioSessionId == 0) return false
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
        if (p > 0) set(audioSessionId, p)
    }

    fun release() {
        try { enhancer?.release() } catch (_: Exception) { }
        enhancer = null
        session = 0
    }

    private fun gainMb(pct: Int): Int = (2000.0 * log10(1.0 + pct / 100.0)).roundToInt() // millibels; 100% extra ≈ 602 mB
}
