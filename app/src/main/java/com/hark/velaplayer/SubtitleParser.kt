package com.hark.velaplayer

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Minimal SRT / WebVTT parser, used for external files so timing (delay) can be shifted. */
object SubtitleParser {
    data class Line(val startMs: Long, val endMs: Long, val text: String)

    private val timeRe = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})[,.](\d{1,3})\s*-->\s*(?:(\d+):)?(\d{1,2}):(\d{2})[,.](\d{1,3})""")
    private val stripTags = Regex("""<(?!/?(?:i|b|u|font)\b)[^>]*>""", RegexOption.IGNORE_CASE)
    private val assTags = Regex("""\{\\[^}]*\}""")

    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }

    fun parse(text: String): List<Line> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        val out = ArrayList<Line>()
        var i = 0
        while (i < lines.size) {
            val m = timeRe.find(lines[i])
            if (m == null) { i++; continue }
            val start = ms(m, 1)
            val end = ms(m, 5)
            i++
            val sb = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank() && !timeRe.containsMatchIn(lines[i])) {
                if (sb.isNotEmpty()) sb.append("<br>")
                sb.append(lines[i].trim())
                i++
            }
            val clean = assTags.replace(stripTags.replace(sb.toString(), ""), "")
            if (clean.isNotBlank() && end > start) out += Line(start, end, clean)
        }
        out.sortBy { it.startMs }
        return out
    }

    private fun ms(m: MatchResult, g: Int): Long {
        val h = m.groupValues[g].ifEmpty { "0" }.toLong()
        val mi = m.groupValues[g + 1].toLong()
        val s = m.groupValues[g + 2].toLong()
        val f = m.groupValues[g + 3].padEnd(3, '0').take(3).toLong()
        return ((h * 60 + mi) * 60 + s) * 1000 + f
    }
}
