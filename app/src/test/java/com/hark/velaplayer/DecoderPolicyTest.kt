package com.hark.velaplayer

import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import com.hark.velaplayer.DecoderPolicy.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** v2.19 smart decoder switching: selector ordering + per-video override rules (JVM, no real codecs). */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk = [34])
class DecoderPolicyTest {

    private fun codec(name: String, mime: String, sw: Boolean) =
        MediaCodecInfo.newInstance(name, mime, mime, null, !sw, sw, !sw, false, false)

    private val avc = listOf(
        codec("c2.qti.avc.decoder", MimeTypes.VIDEO_H264, sw = false),
        codec("c2.android.avc.decoder", MimeTypes.VIDEO_H264, sw = true),
        codec("OMX.qcom.video.decoder.avc", MimeTypes.VIDEO_H264, sw = false),
    )
    private val aac = listOf(codec("c2.android.aac.decoder", MimeTypes.AUDIO_AAC, sw = true))
    private val hevcHwOnly = listOf(codec("c2.qti.hevc.decoder", MimeTypes.VIDEO_H265, sw = false))

    private fun policy(pref: String) = DecoderPolicy { mime, _, _ ->
        when (mime) { MimeTypes.VIDEO_H264 -> avc; MimeTypes.AUDIO_AAC -> aac; MimeTypes.VIDEO_H265 -> hevcHwOnly; else -> emptyList() }
    }.also { it.setPreferred(pref) }

    private fun names(p: DecoderPolicy, mime: String = MimeTypes.VIDEO_H264) = p.getDecoderInfos(mime, false, false).map { it.name }

    @Test fun hardwarePreference_listsHardwareFirst_keepsSoftwareAsFallback() {
        val p = policy("hw"); p.activate("a")
        assertEquals(listOf("c2.qti.avc.decoder", "OMX.qcom.video.decoder.avc", "c2.android.avc.decoder"), names(p))
    }

    @Test fun softwarePreference_listsSoftwareFirst_keepsHardwareAsFallback() {
        val p = policy("sw"); p.activate("a")
        assertEquals(listOf("c2.android.avc.decoder", "c2.qti.avc.decoder", "OMX.qcom.video.decoder.avc"), names(p))
        // no software decoder for this codec at all: hardware is still offered
        assertEquals(listOf("c2.qti.hevc.decoder"), names(p, MimeTypes.VIDEO_H265))
    }

    @Test fun hardwareFailure_switchesThisVideoOnlyToSoftware_strict() {
        val p = policy("hw"); p.activate("a")
        val target = p.onDecoderError("a", video = true, failing = Kind.HW) { p.hasKind(MimeTypes.VIDEO_H264, it) }
        assertEquals(Kind.SW, target)
        assertEquals(listOf("c2.android.avc.decoder"), names(p)) // strict: the failed hardware decoders can't come back
        assertTrue(p.isOverridden("a", video = true))
        assertFalse(p.isOverridden("a", video = false))
        // audio keeps the user's preference ordering
        assertEquals(listOf("c2.android.aac.decoder"), names(p, MimeTypes.AUDIO_AAC))
        assertEquals(Kind.HW, p.preferred) // saved preference untouched
    }

    @Test fun nextVideo_usesPreference_andOverrideComesBackForSameVideo() {
        val p = policy("hw"); p.activate("a")
        p.onDecoderError("a", true, Kind.HW) { true }
        assertTrue("moving away from an overridden video re-creates codecs", p.activate("b"))
        assertEquals("c2.qti.avc.decoder", names(p).first())
        assertFalse("same ordering again: no re-prepare", p.activate("c"))
        assertTrue(p.activate("a"))
        assertEquals(listOf("c2.android.avc.decoder"), names(p))
        assertFalse("repeat-one of the same video keeps its codecs", p.activate("a"))
    }

    @Test fun eachKindTriedOnce_thenGivesUp_noLoop() {
        val p = policy("hw"); p.activate("a")
        assertEquals(Kind.SW, p.onDecoderError("a", true, Kind.HW) { true })
        assertNull(p.onDecoderError("a", true, Kind.SW) { true })
        assertNull(p.onDecoderError("a", true, null) { true })
        assertTrue(p.exhausted("a", true))
    }

    @Test fun softwareFailure_switchesToHardware_unknownFailingKindUsesActiveOrder() {
        val p = policy("sw"); p.activate("a")
        assertEquals(Kind.HW, p.onDecoderError("a", true, null) { true })
        assertEquals(listOf("c2.qti.avc.decoder", "OMX.qcom.video.decoder.avc"), names(p))
    }

    @Test fun noDecoderOfOtherKind_noSwitch() {
        val p = policy("hw"); p.activate("h")
        assertNull(p.onDecoderError("h", true, Kind.HW) { p.hasKind(MimeTypes.VIDEO_H265, it) })
        assertEquals(listOf("c2.qti.hevc.decoder"), names(p, MimeTypes.VIDEO_H265))
    }

    @Test fun failingFallbackKind_switchesBackToPreferredKindStrictly() {
        // Hardware preferred, but ExoPlayer picked software (hardware didn't declare support) and software failed:
        // retry with hardware only, so the format-support sort can't put software first again.
        val p = policy("hw"); p.activate("a")
        assertEquals(Kind.HW, p.onDecoderError("a", true, Kind.SW) { true })
        assertEquals(listOf("c2.qti.avc.decoder", "OMX.qcom.video.decoder.avc"), names(p))
        assertTrue(p.isOverridden("a", true))
    }

    @Test fun audioFailure_isTrackedSeparatelyFromVideo() {
        val p = policy("hw"); p.activate("a")
        assertNull(p.onDecoderError("a", video = false, failing = Kind.SW) { p.hasKind(MimeTypes.AUDIO_AAC, it) })
        assertFalse(p.isOverridden("a", true))
        assertEquals(Kind.SW, p.onDecoderError("a", true, Kind.HW) { true })
    }

    @Test fun newPlayer_keepsSessionOverrides() {
        val p = policy("hw"); p.activate("a")
        p.onDecoderError("a", true, Kind.HW) { true }
        p.setPreferred("hw") // PlayerHolder.create builds a new player with the same policy
        p.activate("a")
        assertEquals(listOf("c2.android.avc.decoder"), names(p))
    }
}
