package com.limpe.tengerium

import com.limpe.tengerium.ui.call.CallProtocol
import com.limpe.tengerium.ui.call.IceCandidateInfo
import com.limpe.tengerium.ui.call.SdpAssembler
import com.limpe.tengerium.util.MsnTextFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Call signalling has to survive the chat channel: markers must parse back to the same
 * signal, stay free of spaces (so the formatting layer passes them through), and a
 * session description must reassemble byte-for-byte from its compressed chunks.
 */
class CallProtocolTest {

    private val callId = "a1b2c3d4e5"

    @Test
    fun controlSignalsRoundTrip() {
        val signals = listOf(
            CallProtocol.invite(callId, video = false) to CallProtocol.Signal.Invite(callId, false),
            CallProtocol.invite(callId, video = true) to CallProtocol.Signal.Invite(callId, true),
            CallProtocol.accept(callId) to CallProtocol.Signal.Accept(callId),
            CallProtocol.decline(callId) to CallProtocol.Signal.Decline(callId),
            CallProtocol.busy(callId) to CallProtocol.Signal.Busy(callId),
            CallProtocol.end(callId) to CallProtocol.Signal.End(callId)
        )
        signals.forEach { (marker, expected) ->
            assertTrue("$marker is not a signal", CallProtocol.isSignal(marker))
            assertTrue("$marker must not contain spaces", !marker.contains(' '))
            assertEquals(expected, CallProtocol.parse(marker))
        }
        assertNull(CallProtocol.parse("hello there"))
        assertNull(CallProtocol.parse("[CALL:short:ACC]"))
    }

    @Test
    fun sessionDescriptionReassemblesFromChunks() {
        // Real offers carry unique fingerprints, ufrag/pwd values and ICE candidates, which
        // barely compress; build something similarly incompressible.
        val random = kotlin.random.Random(7)
        fun hex(length: Int) = buildString {
            repeat(length) { append("0123456789abcdef"[random.nextInt(16)]) }
        }
        val sdp = buildString {
            append("v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n")
            append("m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n")
            append("a=fingerprint:sha-256 ${hex(95)}\r\n")
            append("a=ice-ufrag:${hex(8)}\r\n")
            append("a=ice-pwd:${hex(24)}\r\n")
            append("m=video 9 UDP/TLS/RTP/SAVPF 96\r\n")
            append("a=fingerprint:sha-256 ${hex(95)}\r\n")
            repeat(20) { append("a=candidate:${hex(60)}\r\n") }
        }

        val chunks = CallProtocol.sdp(callId, sdp)
        assertTrue("an offer should be chunked", chunks.size >= 2)
        chunks.forEach { marker ->
            assertTrue("chunk too long: ${marker.length}", marker.length <= 1300)
            val parsed = CallProtocol.parse(marker)
            if (parsed !is CallProtocol.Signal.Sdp) throw AssertionError("not an SDP chunk: $marker")
            assertEquals(callId, parsed.callId)
            assertEquals(chunks.size, parsed.total)
        }

        val assembler = SdpAssembler()
        var assembled: String? = null
        chunks.forEach { marker ->
            val signal = CallProtocol.parse(marker) as CallProtocol.Signal.Sdp
            assembled = assembler.accept(signal) ?: assembled
        }
        assertNotNull(assembled)
        assertEquals(sdp, assembled)
    }

    @Test
    fun iceCandidateRoundTrips() {
        val candidate = IceCandidateInfo(
            "candidate:1 1 udp 2122260223 192.168.1.4 54321 typ host",
            "0",
            0
        )
        val marker = CallProtocol.ice(callId, candidate)
        assertTrue(marker.length < 400)
        assertTrue(!marker.contains(' '))
        val parsed = CallProtocol.parse(marker)
        assertTrue(parsed is CallProtocol.Signal.Ice)
        val decoded = CallProtocol.decodeCandidate((parsed as CallProtocol.Signal.Ice).payload)
        assertEquals(candidate, decoded)
    }

    @Test
    fun markersPassThroughTheFormattingLayerUntouched() {
        val marker = CallProtocol.invite(callId, video = true)
        assertEquals(marker, MsnTextFormat.fromWire(marker, false, false, false, null))
    }
}
