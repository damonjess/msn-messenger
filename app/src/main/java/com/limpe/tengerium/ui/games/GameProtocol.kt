package com.limpe.tengerium.ui.games

import kotlin.random.Random

/**
 * Activity signalling travels through the normal chat channel as bracketed markers, exactly
 * like call setup, so the MSNP server needs nothing extra. A marker never contains a space,
 * which keeps it intact through the text formatting layer.
 *
 * Format: `[GAME:<id>:<type>[:<argument>]]`
 */
object GameProtocol {

    private const val PREFIX = "[GAME:"
    private val ID_PATTERN = Regex("^[a-z0-9]{8,20}$")
    private const val MAX_ARGUMENT_CHARS = 64

    /** A pool shot carries the whole resulting table, so it gets its own budget. */
    private const val MAX_SHOT_CHARS = 900

    sealed class Signal {
        data class Invite(val gameId: String, val kind: GameKind) : Signal()
        data class Accept(val gameId: String) : Signal()
        data class Decline(val gameId: String) : Signal()
        data class Cancel(val gameId: String) : Signal()
        data class Busy(val gameId: String) : Signal()
        data class Move(val gameId: String, val move: String) : Signal()
        data class Shot(val gameId: String, val payload: String) : Signal()
        data class Rematch(val gameId: String) : Signal()
        data class NextRound(val gameId: String) : Signal()
        data class End(val gameId: String) : Signal()
    }

    fun gameId(): String = buildString(10) {
        val alphabet = "0123456789abcdef"
        repeat(10) { append(alphabet[Random.nextInt(alphabet.length)]) }
    }

    fun isSignal(text: String): Boolean =
        text.length > PREFIX.length + 2 && text.startsWith(PREFIX) && text.endsWith("]")

    fun invite(gameId: String, kind: GameKind) = "$PREFIX$gameId:INV:${kind.code}]"
    fun accept(gameId: String) = "$PREFIX$gameId:ACC]"
    fun decline(gameId: String) = "$PREFIX$gameId:DEC]"
    fun cancel(gameId: String) = "$PREFIX$gameId:CAN]"
    fun busy(gameId: String) = "$PREFIX$gameId:BSY]"
    fun move(gameId: String, move: String) = "$PREFIX$gameId:MOV:$move]"
    fun shot(gameId: String, payload: String) = "$PREFIX$gameId:SH:$payload]"
    fun rematch(gameId: String) = "$PREFIX$gameId:RST]"
    fun nextRound(gameId: String) = "$PREFIX$gameId:NRD]"
    fun end(gameId: String) = "$PREFIX$gameId:END]"

    fun parse(text: String): Signal? {
        if (!isSignal(text)) return null
        val inner = text.substring(PREFIX.length, text.length - 1)
        val parts = inner.split(':')
        if (parts.size < 2) return null
        val id = parts[0]
        if (!ID_PATTERN.matches(id)) return null
        val argument = parts.getOrNull(2)?.takeIf { it.length <= MAX_ARGUMENT_CHARS }
        return when (parts[1]) {
            "INV" -> argument?.let { GameKind.fromCode(it) }?.let { Signal.Invite(id, it) }
            "ACC" -> Signal.Accept(id)
            "DEC" -> Signal.Decline(id)
            "CAN" -> Signal.Cancel(id)
            "BSY" -> Signal.Busy(id)
            "MOV" -> argument?.takeIf { it.isNotEmpty() }?.let { Signal.Move(id, it) }
            "SH" -> inner.substringAfter("SH:", "").takeIf { it.isNotEmpty() && it.length <= MAX_SHOT_CHARS }
                ?.let { Signal.Shot(id, it) }
            "RST" -> Signal.Rematch(id)
            "NRD" -> Signal.NextRound(id)
            "END" -> Signal.End(id)
            else -> null
        }
    }
}
