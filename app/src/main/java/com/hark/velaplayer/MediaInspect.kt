package com.hark.velaplayer

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import java.util.concurrent.Executors

/**
 * v2.19.1: technical details (container, codecs, profile, bit depth, HDR, frame rate, audio format)
 * plus which device decoders (hardware / software) can play each track. Shown in the Details dialog so
 * a video that won't play can be diagnosed. Runs off the main thread; never throws.
 */
object MediaInspect {
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Shows [base] immediately, then appends the technical section when it's ready. */
    fun attach(ctx: Context, dialog: AlertDialog, base: String, uri: Uri) {
        dialog.setMessage("$base\n\nTechnical: reading…")
        exec.execute {
            val tech = try { inspect(ctx.applicationContext, uri) } catch (t: Throwable) { "Couldn't read this file's tracks (${t.javaClass.simpleName}: ${t.message}). The file may be damaged, incomplete, or in a format Android can't open." }
            main.post { if (dialog.isShowing) dialog.setMessage("$base\n\nTechnical\n$tech") }
        }
    }

    fun inspect(ctx: Context, uri: Uri): String {
        val sb = StringBuilder()
        try {
            MediaMetadataRetriever().apply {
                try {
                    setDataSource(ctx, uri)
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.let { sb.append("Container: ").append(it).append('\n') }
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let { sb.append("Overall bitrate: ").append(it / 1000).append(" kb/s\n") }
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.takeIf { it != "0" }?.let { sb.append("Rotation: ").append(it).append("°\n") }
                } finally { try { release() } catch (_: Throwable) {} }
            }
        } catch (_: Throwable) {}
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            if (ex.trackCount == 0) sb.append("No tracks found.\n")
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: "unknown"
                sb.append('\n').append("Track ").append(i + 1).append(": ").append(mime).append('\n')
                when {
                    mime.startsWith("video/") -> video(f, mime, sb)
                    mime.startsWith("audio/") -> audio(f, sb)
                    else -> f.getString(MediaFormat.KEY_LANGUAGE)?.let { sb.append("  Language: ").append(it).append('\n') }
                }
                if (mime.startsWith("video/") || mime.startsWith("audio/")) sb.append("  Decoders: ").append(decoders(f, mime)).append('\n')
            }
        } finally { try { ex.release() } catch (_: Throwable) {} }
        return sb.toString().trim()
    }

    private fun int(f: MediaFormat, k: String): Int? = try { if (f.containsKey(k)) f.getInteger(k) else null } catch (_: Throwable) { null }

    private fun video(f: MediaFormat, mime: String, sb: StringBuilder) {
        val w = int(f, MediaFormat.KEY_WIDTH); val h = int(f, MediaFormat.KEY_HEIGHT)
        if (w != null && h != null) sb.append("  Size: ").append(w).append(" × ").append(h).append('\n')
        val fr = try { if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) f.getNumber(MediaFormat.KEY_FRAME_RATE)?.toFloat() else null } catch (_: Throwable) { null }
        fr?.let { sb.append("  Frame rate: ").append(String.format("%.3f", it).trimEnd('0').trimEnd('.')).append(" fps\n") }
        int(f, "profile")?.let { p -> sb.append("  Profile: ").append(profileName(mime, p)).append('\n') }
        int(f, "level")?.let { sb.append("  Level: ").append(it).append('\n') }
        int(f, MediaFormat.KEY_BIT_RATE)?.let { sb.append("  Bitrate: ").append(it / 1000).append(" kb/s\n") }
        if (Build.VERSION.SDK_INT >= 24) {
            when (int(f, MediaFormat.KEY_COLOR_TRANSFER)) {
                MediaFormat.COLOR_TRANSFER_ST2084 -> sb.append("  HDR: PQ (HDR10/HDR10+)\n")
                MediaFormat.COLOR_TRANSFER_HLG -> sb.append("  HDR: HLG\n")
            }
            if (int(f, MediaFormat.KEY_COLOR_STANDARD) == MediaFormat.COLOR_STANDARD_BT2020) sb.append("  Colour: BT.2020\n")
        }
    }

    private fun audio(f: MediaFormat, sb: StringBuilder) {
        int(f, MediaFormat.KEY_CHANNEL_COUNT)?.let { sb.append("  Channels: ").append(it).append('\n') }
        int(f, MediaFormat.KEY_SAMPLE_RATE)?.let { sb.append("  Sample rate: ").append(it).append(" Hz\n") }
        int(f, MediaFormat.KEY_BIT_RATE)?.let { sb.append("  Bitrate: ").append(it / 1000).append(" kb/s\n") }
        f.getString(MediaFormat.KEY_LANGUAGE)?.takeIf { it != "und" }?.let { sb.append("  Language: ").append(it).append('\n') }
    }

    private fun profileName(mime: String, p: Int): String = when (mime) {
        "video/avc" -> when (p) {
            MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline -> "Baseline"
            MediaCodecInfo.CodecProfileLevel.AVCProfileMain -> "Main"
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh -> "High (8-bit)"
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10 -> "High 10 (10-bit — most phones can't decode this)"
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh422 -> "High 4:2:2"
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh444 -> "High 4:4:4"
            else -> "code $p"
        }
        "video/hevc" -> when (p) {
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain -> "Main (8-bit)"
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 -> "Main 10 (10-bit)"
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 -> "Main 10 HDR10"
            4096 -> "Main 10 HDR10+"
            else -> "code $p (range extension?)"
        }
        "video/dolby-vision" -> "Dolby Vision profile code $p"
        else -> "code $p"
    }

    /** Lists device decoders for this track, split into hardware/software, and whether they claim this exact format. */
    private fun decoders(f: MediaFormat, mime: String): String {
        return try {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
            if (list.isEmpty()) return "none on this device for $mime"
            val exact = try { MediaCodecList(MediaCodecList.ALL_CODECS).findDecoderForFormat(stripRate(f)) } catch (_: Throwable) { null }
            val parts = list.map { c ->
                val sw = if (Build.VERSION.SDK_INT >= 29) c.isSoftwareOnly else c.name.startsWith("OMX.google.") || c.name.startsWith("c2.android.")
                c.name + (if (sw) " (sw)" else " (hw)")
            }
            parts.joinToString(", ") + if (exact != null) "\n  Supports this exact format: $exact" else "\n  No decoder claims this exact profile/size/level"
        } catch (t: Throwable) { "unknown (${t.javaClass.simpleName})" }
    }

    // findDecoderForFormat rejects formats carrying KEY_FRAME_RATE on API 21.
    private fun stripRate(f: MediaFormat): MediaFormat {
        if (Build.VERSION.SDK_INT == 21 && f.containsKey(MediaFormat.KEY_FRAME_RATE)) f.setString(MediaFormat.KEY_FRAME_RATE, null)
        return f
    }
}
