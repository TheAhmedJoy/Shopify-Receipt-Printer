package com.example.shopify_receipt_printer

import java.io.ByteArrayOutputStream
import java.text.Normalizer

/** Tiny ESC/POS command builder. Text is reduced to plain ASCII so any code page prints it. */
class EscPos {
    enum class Align(val code: Int) { LEFT(0), CENTER(1), RIGHT(2) }

    private val out = ByteArrayOutputStream()

    fun init() = cmd(ESC, '@'.code)

    fun align(align: Align) = cmd(ESC, 'a'.code, align.code)

    fun bold(on: Boolean) = cmd(ESC, 'E'.code, if (on) 1 else 0)

    /** Character magnification, 1 or 2 in each direction. */
    fun size(width: Int = 1, height: Int = 1) =
        cmd(GS, '!'.code, ((width - 1).coerceIn(0, 7) shl 4) or (height - 1).coerceIn(0, 7))

    fun text(s: String) = apply { out.write(toAscii(s).toByteArray(Charsets.US_ASCII)) }

    fun line(s: String = "") = text(s).apply { out.write(LF) }

    fun feed(lines: Int) = cmd(ESC, 'd'.code, lines.coerceIn(0, 255))

    /** Feed past the cutter and do a partial cut. */
    fun cut() = cmd(GS, 'V'.code, 66, 3)

    fun bytes(): ByteArray = out.toByteArray()

    private fun cmd(vararg b: Int) = apply { b.forEach { out.write(it) } }

    companion object {
        private const val ESC = 0x1B
        private const val GS = 0x1D
        private const val LF = 0x0A

        private val REPLACEMENTS = mapOf(
            '\u2018' to "'", '\u2019' to "'", '\u201C' to "\"", '\u201D' to "\"",
            '\u2013' to "-", '\u2014' to "-", '\u2026' to "...", '\u00A0' to " ",
            '\u00D7' to "x", '\t' to " ",
        )

        /** Strips accents, swaps typographic punctuation, and replaces anything else outside ASCII. */
        fun toAscii(s: String): String {
            val sb = StringBuilder()
            // Walk code points, not chars, so an emoji (a surrogate pair) becomes one "?" and not two
            Normalizer.normalize(s, Normalizer.Form.NFD).codePoints().forEach { cp ->
                when {
                    Character.getType(cp) == Character.NON_SPACING_MARK.toInt() -> Unit
                    cp in 0x20..0x7E -> sb.append(cp.toChar())
                    cp <= 0xFFFF -> sb.append(REPLACEMENTS[cp.toChar()] ?: "?")
                    else -> sb.append('?')
                }
            }
            return sb.toString()
        }

        /** Greedy word wrap that also hard-breaks words longer than [width]. Keeps explicit newlines. */
        fun wrap(text: String, width: Int): List<String> {
            val lines = mutableListOf<String>()
            for (paragraph in text.replace("\r\n", "\n").split('\n')) {
                var current = StringBuilder()
                for (word in paragraph.split(' ').filter { it.isNotEmpty() }) {
                    var w = word
                    while (w.length > width) {
                        if (current.isNotEmpty()) {
                            lines += current.toString(); current = StringBuilder()
                        }
                        lines += w.take(width); w = w.drop(width)
                    }
                    if (current.isEmpty()) current.append(w)
                    else if (current.length + 1 + w.length <= width) current.append(' ').append(w)
                    else {
                        lines += current.toString(); current = StringBuilder(w)
                    }
                }
                lines += current.toString()
            }
            // Drop trailing blank lines but keep intentional blank lines in the middle
            while (lines.size > 1 && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
            return lines
        }
    }
}
