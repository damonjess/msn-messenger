package com.limpe.tengerium.ui.call

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

/**
 * Call signalling travels through the normal chat channel as bracketed markers, so no
 * extra server support is needed. Each marker is a single MSNP message; SDP offers are
 * compressed and split over several messages to stay below the protocol payload limit.
 *
 * Format: `[CALL:<id>:<type>[:<arguments>]]` — never contains spaces, so the text
 * formatting layer passes it through untouched.
 */
object CallProtocol {

    private const val PREFIX = "[CALL:"
    private const val CHUNK_SIZE = 700
    private val ID_PATTERN = Regex("^[a-z0-9]{8,20}$")

    /** Largest argument accepted from a peer, to keep a rogue marker from allocating wildly. */
    private const val MAX_ARGUMENT_CHARS = 1200

    sealed class Signal {
        data class Invite(val callId: String, val video: Boolean) : Signal()
        data class Accept(val callId: String) : Signal()
        data class Decline(val callId: String) : Signal()
        data class Busy(val callId: String) : Signal()
        data class End(val callId: String) : Signal()
        data class Sdp(val callId: String, val sequence: Int, val total: Int, val chunk: String) : Signal()
        data class Ice(val callId: String, val payload: String) : Signal()
    }

    fun callId(): String = buildString(10) {
        val alphabet = "0123456789abcdef"
        repeat(10) { append(alphabet[Random.nextInt(alphabet.length)]) }
    }

    fun isSignal(text: String): Boolean =
        text.length > PREFIX.length + 2 && text.startsWith(PREFIX) && text.endsWith("]")

    fun invite(callId: String, video: Boolean) = "$PREFIX$callId:INV:${if (video) "V" else "A"}]"
    fun accept(callId: String) = "$PREFIX$callId:ACC]"
    fun decline(callId: String) = "$PREFIX$callId:DEC]"
    fun busy(callId: String) = "$PREFIX$callId:BSY]"
    fun end(callId: String) = "$PREFIX$callId:END]"

    /** One message per chunk of the compressed session description. */
    fun sdp(callId: String, sessionDescription: String): List<String> {
        val packed = Base64.encodeToString(gzip(sessionDescription), Base64.NO_WRAP or Base64.URL_SAFE)
        val total = (packed.length + CHUNK_SIZE - 1) / CHUNK_SIZE
        return (0 until total).map { index ->
            val start = index * CHUNK_SIZE
            val end = minOf(packed.length, start + CHUNK_SIZE)
            "$PREFIX$callId:SDP:$index:$total:${packed.substring(start, end)}]"
        }
    }

    fun ice(callId: String, candidate: IceCandidateInfo): String {
        val encoded = Base64.encodeToString(candidate.toJson().toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)
        return "$PREFIX$callId:ICE:$encoded]"
    }

    fun parse(text: String): Signal? {
        if (!isSignal(text)) return null
        val inner = text.substring(PREFIX.length, text.length - 1)
        if (inner.length > 8 * MAX_ARGUMENT_CHARS) return null
        val parts = inner.split(':')
        if (parts.size < 2) return null
        val id = parts[0]
        if (!ID_PATTERN.matches(id)) return null
        return when (parts[1]) {
            "INV" -> if (parts.size < 3) null else Signal.Invite(id, parts[2].contains('V'))
            "ACC" -> Signal.Accept(id)
            "DEC" -> Signal.Decline(id)
            "BSY" -> Signal.Busy(id)
            "END" -> Signal.End(id)
            "SDP" -> {
                val sequence = parts.getOrNull(2)?.toIntOrNull()
                val total = parts.getOrNull(3)?.toIntOrNull()
                val chunk = parts.drop(4).joinToString(":")
                if (sequence == null || total == null || chunk.isEmpty() || total !in 1..64) null
                else Signal.Sdp(id, sequence, total, chunk)
            }
            "ICE" -> {
                val payload = parts.drop(2).joinToString(":")
                if (payload.isEmpty() || payload.length > MAX_ARGUMENT_CHARS) null else Signal.Ice(id, payload)
            }
            else -> null
        }
    }

    fun decodeSdp(callId: String, chunks: List<String>): String? {
        if (chunks.isEmpty()) return null
        return runCatching {
            val packed = chunks.joinToString("")
            val bytes = Base64.decode(packed, Base64.NO_WRAP or Base64.URL_SAFE)
            String(gunzip(bytes), Charsets.UTF_8)
        }.getOrNull()
    }

    fun decodeCandidate(payload: String): IceCandidateInfo? = runCatching {
        val bytes = Base64.decode(payload, Base64.NO_WRAP or Base64.URL_SAFE)
        IceCandidateInfo.fromJson(String(bytes, Charsets.UTF_8))
    }.getOrNull()

    private fun gzip(value: String): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(value.toByteArray()) }
        return output.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPInputStream(bytes.inputStream()).use { input ->
            val buffer = ByteArray(4096)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }
}

/** An ICE candidate reduced to what the peer needs to recreate it. */
data class IceCandidateInfo(
    val sdp: String,
    val sdpMid: String?,
    val sdpMLineIndex: Int
) {
    fun toJson(): String = JSONObject()
        .put("c", sdp)
        .put("m", sdpMid ?: JSONObject.NULL)
        .put("i", sdpMLineIndex)
        .toString()

    companion object {
        fun fromJson(json: String): IceCandidateInfo? {
            val objectValue = JSONObject(json)
            val sdp = objectValue.optString("c").takeIf { it.isNotEmpty() } ?: return null
            val mid = if (objectValue.isNull("m")) null else objectValue.optString("m")
            return IceCandidateInfo(sdp, mid, objectValue.optInt("i", 0))
        }
    }
}

/** Collects the chunks of one compressed session description. */
class SdpAssembler {
    private var callId: String? = null
    private var total = 0
    private val chunks = HashMap<Int, String>()

    fun reset() {
        callId = null
        total = 0
        chunks.clear()
    }

    /** Returns the complete description once every chunk has arrived. */
    fun accept(signal: CallProtocol.Signal.Sdp): String? {
        if (signal.total > 64 || signal.sequence !in 0 until signal.total) return null
        if (callId != signal.callId || total != signal.total) {
            reset()
            callId = signal.callId
            total = signal.total
        }
        chunks[signal.sequence] = signal.chunk
        if (chunks.size < total) return null
        val ordered = (0 until total).mapNotNull { chunks[it] }
        reset()
        return CallProtocol.decodeSdp(signal.callId, ordered)
    }
}
