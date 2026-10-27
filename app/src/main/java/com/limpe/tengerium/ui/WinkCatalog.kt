package com.limpe.tengerium.ui

/**
 * A classic MSN-style wink. Winks travel through chat as the marker
 * [WINK:id] so the same catalogue can render them on both ends.
 */
data class Wink(val id: String, val emoji: String, val name: String)

/**
 * An MSN Messenger 7-inspired set of Winks, with original scene artwork.
 * Each id has a matching animation in [WinkOverlayView] and, where available, a sound effect
 * in [com.limpe.tengerium.util.WinkSoundPlayer].
 */
object WinkCatalog {

    const val MARKER_PREFIX = "[WINK:"

    const val GUITAR = "guitar"
    const val KISS = "kiss"
    const val LAUGH = "laugh"
    const val KNOCK = "knock"
    const val SLAP = "slap"
    const val PEEK = "peek"
    const val ICECREAM = "icecream"
    const val BALLOON = "balloon"
    const val PIG = "pig"
    const val UFO = "ufo"
    const val FROG = "frog"
    const val HEART = "heart"
    const val IDEA = "idea"
    const val DANCE = "dance"

    val all = listOf(
        Wink(GUITAR, "\uD83C\uDFB8", "Guitar Smash"),
        Wink(KISS, "\uD83D\uDC8B", "Kiss"),
        Wink(LAUGH, "\uD83D\uDE02", "Hysterical Laugh"),
        Wink(KNOCK, "\u270A", "Knocking"),
        Wink(SLAP, "\uD83D\uDE03", "Cheering Smiley"),
        Wink(PEEK, "\uD83D\uDE1C", "Peeking Character"),
        Wink(ICECREAM, "\uD83C\uDF66", "Sad Boy: Dropped Ice Cream"),
        Wink(BALLOON, "\uD83C\uDF88", "Balloon Pop"),
        Wink(PIG, "\uD83D\uDC37", "Dancing Pig"),
        Wink(DANCE, "\uD83D\uDC83", "Dancing Boy"),
        Wink(UFO, "\uD83D\uDEF8", "UFO"),
        Wink(FROG, "\uD83D\uDC38", "Frog Eating Fly"),
        Wink(HEART, "\uD83D\uDC96", "Sparkling Heart"),
        Wink(IDEA, "\uD83D\uDCA1", "New Idea")
    )

    /** Ids used by earlier builds, mapped onto their closest classic wink. */
    private val aliases = mapOf(
        "alien" to UFO,
        "boy" to PEEK,
        "disco" to SLAP,
        "smile" to SLAP,
        "hug" to HEART
    )

    /** Winks from earlier builds that no classic wink replaces; still rendered as a card. */
    private val retired = listOf(
        Wink("nerd", "\uD83E\uDD13", "Nerd"),
        Wink("run", "\uD83C\uDFC3", "Run"),
        Wink("fist", "\u270A", "Fist"),
        Wink("monkey", "\uD83D\uDC35", "Monkey"),
        Wink("party", "\uD83C\uDF89", "Party")
    )

    val fallback = Wink("wink", "\uD83D\uDE09", "Wink")

    /** The same original vector scene is previewed in the picker and in chat. */
    fun artworkRes(id: String): Int? = null

    fun byId(id: String?): Wink? {
        if (id == null) return null
        all.firstOrNull { it.id.equals(id, ignoreCase = true) }?.let { return it }
        val aliasId = aliases.entries
            .firstOrNull { it.key.equals(id, ignoreCase = true) }?.value
        if (aliasId != null) {
            all.firstOrNull { it.id == aliasId }?.let { return it }
        }
        return retired.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }

    fun marker(wink: Wink): String = "$MARKER_PREFIX${wink.id}]"

    /** Returns the wink encoded in [text], or null when it is not a wink message. */
    fun fromMessage(text: String?): Wink? {
        if (text == null || !text.startsWith(MARKER_PREFIX) || !text.endsWith("]")) return null
        return byId(text.removePrefix(MARKER_PREFIX).removeSuffix("]"))
    }

    fun isWinkMessage(text: String?): Boolean = text != null && text.startsWith(MARKER_PREFIX)
}
