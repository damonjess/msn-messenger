package com.limpe.tengerium.util

/**
 * Bridges the app's inline [b]/[i]/[u] tags and the per-message bold/italic/underline
 * flags that the MSN protocol actually carries.
 *
 * MSN formatting applies to a whole message, so:
 *  - outgoing: tags are always stripped from the wire text. A style flag is only set when
 *    that style covers the entire message; partial emphasis is sent as plain text instead of
 *    leaking literal "[b]" tags to other clients.
 *  - incoming: the flags are turned back into tags so this app renders them.
 */
object MsnTextFormat {

    data class Wire(
        val text: String,
        val bold: Boolean,
        val italic: Boolean,
        val underline: Boolean,
        val color: String = "000000"
    )

    // Colour travels inside the stored message as an invisible prefix:
    // U+2063, 24 zero-width characters (one per bit of RRGGBB), U+2063.
    // If a screen ever fails to strip it, nothing visible is shown.
    private const val MARK = '\u2063'
    private const val ZERO = '\u200B'
    private const val ONE = '\u200C'

    data class ColorSplit(val color: Int?, val text: String)

    fun withColor(text: String, rgb: Int): String {
        val value = rgb and 0xFFFFFF
        if (value == 0) return text
        val sb = StringBuilder().append(MARK)
        for (bit in 23 downTo 0) sb.append(if (((value shr bit) and 1) == 1) ONE else ZERO)
        return sb.append(MARK).append(text).toString()
    }

    fun splitColor(text: String): ColorSplit {
        if (text.length < 26 || text[0] != MARK || text[25] != MARK) return ColorSplit(null, text)
        var value = 0
        for (i in 1..24) {
            value = value shl 1
            when (text[i]) {
                ONE -> value = value or 1
                ZERO -> {}
                else -> return ColorSplit(null, text)
            }
        }
        return ColorSplit(value, text.substring(26))
    }

    private val ALL_TAGS = Regex("\\[/?[biu]]", RegexOption.IGNORE_CASE)

    fun toWire(stored: String): Wire {
        val split = splitColor(stored)
        val text = split.text
        val colorHex = if (split.color != null) "%06X".format(split.color) else "000000"
        if (!text.contains('[')) return Wire(text, false, false, false, colorHex)
        if (text.replace(ALL_TAGS, "").isBlank()) return Wire(text, false, false, false, colorHex)
        return Wire(
            text = text.replace(ALL_TAGS, ""),
            bold = coversWholeMessage(text, 'b'),
            italic = coversWholeMessage(text, 'i'),
            underline = coversWholeMessage(text, 'u'),
            color = colorHex
        )
    }

    private fun coversWholeMessage(text: String, tag: Char): Boolean {
        // drop the other two styles' tags, then require one [tag]...[/tag] around everything
        val others = "biu".replace(tag.toString(), "")
        val stripped = text.replace(Regex("\\[/?[$others]]", RegexOption.IGNORE_CASE), "").trim()
        val m = Regex("^\\[$tag](.*)\\[/$tag]$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .matchEntire(stripped) ?: return false
        val inner = m.groupValues[1]
        return inner.isNotBlank() && !inner.contains("[$tag]", ignoreCase = true) &&
            !inner.contains("[/$tag]", ignoreCase = true)
    }

    fun fromWire(
        text: String, bold: Boolean, italic: Boolean, underline: Boolean, color: String? = null
    ): String {
        if (text.isEmpty()) return text
        // never touch protocol-style markers such as [NUDGE] or [WINK:id]
        if (text.startsWith("[") && text.endsWith("]") && !text.contains(' ')) return text
        var out = text
        if (bold) out = "[b]$out[/b]"
        if (italic) out = "[i]$out[/i]"
        if (underline) out = "[u]$out[/u]"
        val rgb = color?.trim()?.removePrefix("#")?.takeIf { it.length == 6 }?.toIntOrNull(16)
        if (rgb != null) out = withColor(out, rgb)
        return out
    }
}
