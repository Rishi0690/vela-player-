package com.hark.velaplayer

import android.net.Uri
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import com.hark.velaplayer.mkv.VelaMatroskaExtractor

/**
 * v2.19.2: [DefaultExtractorsFactory] with every Media3 `MatroskaExtractor` swapped for [VelaMatroskaExtractor]
 * (same flags/subtitle setup as DefaultExtractorsFactory 1.3.1 uses), which can also seek MKV/WebM files whose
 * Cues are only listed in a nested SeekHead, and files without usable Cues (cluster binary search). Every other
 * extractor, and the extractor order/sniffing, is exactly DefaultExtractorsFactory's.
 */
class VelaExtractorsFactory(private val delegate: DefaultExtractorsFactory) : ExtractorsFactory {
    private var textTrackTranscoding = false
    private var subtitleParserFactory: SubtitleParser.Factory = DefaultSubtitleParserFactory()

    @Synchronized
    override fun experimentalSetTextTrackTranscodingEnabled(enabled: Boolean): ExtractorsFactory {
        delegate.experimentalSetTextTrackTranscodingEnabled(enabled)
        textTrackTranscoding = enabled
        return this
    }

    @Synchronized
    override fun setSubtitleParserFactory(factory: SubtitleParser.Factory): ExtractorsFactory {
        delegate.setSubtitleParserFactory(factory)
        subtitleParserFactory = factory
        return this
    }

    override fun createExtractors(): Array<Extractor> = swap(delegate.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        swap(delegate.createExtractors(uri, responseHeaders))

    @Synchronized
    private fun swap(extractors: Array<Extractor>): Array<Extractor> = Array(extractors.size) { i ->
        val e = extractors[i]
        if (e.underlyingImplementation is MatroskaExtractor) {
            VelaMatroskaExtractor(
                subtitleParserFactory,
                if (textTrackTranscoding) 0 else VelaMatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA)
        } else e
    }
}
