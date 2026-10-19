package com.example.shopify_receipt_printer

/**
 * Turns the ESC/POS bytes the app sends into a plain text picture of the paper, 48 columns wide.
 * Double-width text is spread out ("O R D E R") so it takes the space it would on paper. Bold and
 * double height can't be shown in plain text and are ignored.
 */
object EscPosPreview {
    private const val ESC = 0x1B
    private const val GS = 0x1D
    private const val LF = 0x0A

    fun render(bytes: ByteArray, width: Int = TicketFormatter.WIDTH): String {
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        var align = 0
        var doubleWidth = false

        fun flush() {
            val text = current.toString().trimEnd()
            val pad = when (align) {
                1 -> (width - text.length) / 2
                2 -> width - text.length
                else -> 0
            }.coerceAtLeast(0)
            lines += " ".repeat(pad) + text
            current.clear()
        }

        var i = 0
        fun next(): Int = bytes.getOrNull(i++)?.toInt()?.and(0xFF) ?: 0
        while (i < bytes.size) {
            when (val b = next()) {
                ESC -> when (next().toChar()) {
                    '@' -> { align = 0; doubleWidth = false }
                    'a' -> align = next()
                    'E' -> next()
                    'd' -> repeat(next()) { flush() }
                }
                GS -> when (next().toChar()) {
                    '!' -> doubleWidth = (next() shr 4) > 0
                    'V' -> {
                        if (next() >= 65) next()
                        lines += "- ".repeat(width / 2 - 3) + "CUT"
                    }
                }
                LF -> flush()
                else -> {
                    current.append(b.toChar())
                    if (doubleWidth) current.append(' ')
                }
            }
        }
        if (current.isNotEmpty()) flush()

        val border = "+" + "-".repeat(width + 2) + "+"
        return (listOf(border) + lines.map { "| ${it.padEnd(width)} |" } + border).joinToString("\n")
    }
}
