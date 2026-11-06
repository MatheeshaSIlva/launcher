package dev.launcher.engine.adb

import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PairingPacketHeaderTest {
    private fun refusal(bytes: ByteArray): AdbProtocolException = try {
        PairingPacketHeader.decode(bytes)
        fail("header was accepted")
        throw AssertionError()
    } catch (e: AdbProtocolException) {
        e
    }

    @Test fun roundTripsBothTypesAndBoundaryLengths() {
        for (header in listOf(
            PairingPacketHeader(PairingPacketType.SPAKE2_MESSAGE, 1),
            PairingPacketHeader(PairingPacketType.PEER_INFO, PairingPacketHeader.MAX_PAYLOAD_BYTES),
        )) {
            val decoded = PairingPacketHeader.decode(header.encode())
            assertEquals(header.type, decoded.type)
            assertEquals(header.payloadLength, decoded.payloadLength)
        }
    }

    @Test fun refusesUnsupportedVersionAndType() {
        assertTrue(refusal(header(version = 2)).message!!.startsWith("Unsupported pairing header version"))
        assertTrue(refusal(header(type = 2)).message!!.startsWith("Unsupported pairing packet type"))
    }

    @Test fun refusesNonPositiveOversizedAndTruncatedPayloadHeaders() {
        assertTrue(refusal(header(payloadLength = 0)).message!!.contains("outside 1..16384"))
        assertTrue(refusal(header(payloadLength = -1)).message!!.contains("outside 1..16384"))
        assertTrue(refusal(header(payloadLength = 16_385)).message!!.contains("outside 1..16384"))
        assertTrue(refusal(ByteArray(5)).message!!.startsWith("Truncated pairing header"))
    }

    @Test fun headerToStringContainsNoPacketBytes() {
        val packetBytes = "pairing-secret".toByteArray()
        val text = PairingPacketHeader(PairingPacketType.PEER_INFO, packetBytes.size).toString()
        assertFalse(text.contains("pairing-secret"))
        assertFalse(text.contains(packetBytes.contentToString()))
    }

    @Test fun endpointKeepsResolvedAddressAndAllowsMissingNetwork() {
        val address = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 20))
        val endpoint = AdbEndpoint(address, 37_777, null)

        assertEquals(address, endpoint.address)
        assertEquals(37_777, endpoint.port)
        assertNull(endpoint.network)
    }

    private fun header(
        version: Int = PairingPacketHeader.VERSION,
        type: Int = PairingPacketType.SPAKE2_MESSAGE.wireValue,
        payloadLength: Int = 1,
    ): ByteArray = ByteBuffer.allocate(PairingPacketHeader.HEADER_BYTES).order(ByteOrder.BIG_ENDIAN).apply {
        put(version.toByte())
        put(type.toByte())
        putInt(payloadLength)
    }.array()
}
