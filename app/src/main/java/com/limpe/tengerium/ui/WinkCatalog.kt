package com.limpe.tengerium.ui

/**
 * A classic MSN-style wink. Winks travel through chat as the marker
 * [WINK:id] so the same catalogue can render them on both ends.
 */
data class Wink(val id: String, val emoji: String, val name: String)

object WinkCatalog {

    const val MARKER_PREFIX = "[WINK:"

    val all = listOf(
        Wink("boy", "\uD83D\uDC66", "Boy"),
        Wink("alien", "\uD83D\uDC7D", "Alien"),
        Wink("pig", "\uD83D\uDC37", "Pig"),
        Wink("hug", "\uD83E\uDD17", "Hug"),
        Wink("frog", "\uD83D\uDC38", "Frog"),
        Wink("idea", "\uD83D\uDCA1", "Idea"),
        Wink("disco", "\uD83D\uDCBF", "Disco"),
        Wink("kiss", "\uD83D\uDC8B", "Kiss"),
        Wink("smile", "\uD83D\uDE04", "Smile"),
        Wink("nerd", "\uD83E\uDD13", "Nerd"),
        Wink("run", "\uD83C\uDFC3", "Run"),
        Wink("heart", "\uD83D\uDC97", "Heart"),
        Wink("fist", "\u270A", "Fist"),
        Wink("monkey", "\uD83D\uDC35", "Monkey"),
        Wink("party", "\uD83C\uDF89", "Party")
    )

    val fallback = Wink("wink", "\uD83D\uDE09", "Wink")

    fun byId(id: String?): Wink? = all.firstOrNull { it.id.equals(id, ignoreCase = true) }

    fun marker(wink: Wink): String = "$MARKER_PREFIX${wink.id}]"

    /** Returns the wink encoded in [text], or null when it is not a wink message. */
    fun fromMessage(text: String?): Wink? {
        if (text == null || !text.startsWith(MARKER_PREFIX) || !text.endsWith("]")) return null
        return byId(text.removePrefix(MARKER_PREFIX).removeSuffix("]"))
    }

    fun isWinkMessage(text: String?): Boolean = text != null && text.startsWith(MARKER_PREFIX)
}
