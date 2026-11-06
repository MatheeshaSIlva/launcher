package dev.launcher.engine.adb

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AdbMessageTest {
    private val legacyVersion = 0x01000000

    private fun refusal(bytes: ByteArray, version: Int = legacyVersion): AdbProtocolException = try {
        AdbMessageCodec.decode(ByteArrayInputStream(bytes), version)
        fail("frame was accepted")
        throw AssertionError()
    } catch (e: AdbProtocolException) {
        e
    }

    @Test fun roundTripsEmptyAndMaximumFramesWithoutOverreading() {
        val first = AdbMessage(0x4e584e43, 0x01000000, 4096)
        val payload = ByteArray(AdbMessageCodec.MAX_PAYLOAD_BYTES) { (it and 0xff).toByte() }
        val second = AdbMessage(0x45545257, 7, 9, payload)
        val input = ByteArrayInputStream(
            AdbMessageCodec.encode(first, legacyVersion) + AdbMessageCodec.encode(second, legacyVersion),
        )

        assertEquals(first, AdbMessageCodec.decode(input, legacyVersion))
        val decodedSecond = AdbMessageCodec.decode(input, legacyVersion)
        assertEquals(second, decodedSecond)
        assertArrayEquals(payload, decodedSecond.payload)
        assertEquals(0, input.available())
    }

    @Test fun refusesNegativeAndOversizedLengthsBeforePayloadAllocation() {
        assertTrue(refusal(header(payloadLength = -1)).message!!.startsWith("Negative ADB payload length"))
        assertTrue(refusal(header(payloadLength = 4097)).message!!.contains("exceeds 4096"))
    }

    @Test fun refusesTruncatedHeaderAndPayload() {
        assertTrue(refusal(ByteArray(23)).message!!.startsWith("Truncated ADB message header"))
        assertTrue(refusal(header(payloadLength = 3) + byteArrayOf(1, 2)).message!!
            .startsWith("Truncated ADB message payload"))
    }

    @Test fun refusesBadMagicAndChecksum() {
        assertEquals("Invalid ADB command magic", refusal(header(magic = 0)).message)
        assertEquals("Invalid ADB payload checksum", refusal(header(payloadLength = 1) + byteArrayOf(1)).message)
    }

    @Test fun encodingUsesChecksumOnlyBelowNegotiatedCutoff() {
        val message = AdbMessage(0x45545257, 7, 9, byteArrayOf(1, 0xff.toByte(), 2))
        for (version in listOf(legacyVersion, 0x01000001, 0x01000002)) {
            val encoded = AdbMessageCodec.encode(message, version)
            val checksum = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN).getInt(16)
            assertEquals(if (version == legacyVersion) 258 else 0, checksum)
            assertEquals(message, AdbMessageCodec.decode(ByteArrayInputStream(encoded), version))
        }
    }

    @Test fun modernFramesIgnoreChecksumAtAndAboveNegotiatedCutoff() {
        val payload = byteArrayOf(1, 0xff.toByte(), 2)
        for (version in listOf(0x01000001, 0x01000002)) {
            for (checksum in listOf(0, 258, -1)) {
                val frame = header(payloadLength = payload.size, checksum = checksum) + payload
                assertArrayEquals(payload, AdbMessageCodec.decode(ByteArrayInputStream(frame), version).payload)
            }
            assertEquals("Invalid ADB command magic", refusal(header(magic = 0), version).message)
            assertTrue(refusal(header(payloadLength = 4097), version).message!!.contains("exceeds 4096"))
            assertTrue(refusal(header(payloadLength = -1), version).message!!.startsWith("Negative"))
            assertTrue(refusal(ByteArray(23), version).message!!.startsWith("Truncated ADB message header"))
            assertTrue(refusal(header(payloadLength = 1), version).message!!
                .startsWith("Truncated ADB message payload"))
        }
    }

    @Test fun legacyFramesRequireCorrectChecksumIncludingUnsignedBytes() {
        val payload = byteArrayOf(1, 0xff.toByte(), 2)
        val frame = header(payloadLength = payload.size, checksum = 258) + payload
        assertArrayEquals(payload, AdbMessageCodec.decode(ByteArrayInputStream(frame), legacyVersion).payload)
        for (checksum in listOf(0, 257, -1)) {
            assertEquals("Invalid ADB payload checksum", refusal(
                header(payloadLength = payload.size, checksum = checksum) + payload,
            ).message)
        }
    }

    @Test fun payloadIsDefensivelyCopiedAndNeverPrinted() {
        val secret = "private-key-material".toByteArray()
        val message = AdbMessage(0x48545541, 1, 2, secret)
        secret.fill(0)
        val exposed = message.payload
        exposed.fill(0)

        assertArrayEquals("private-key-material".toByteArray(), message.payload)
        assertFalse(message.toString().contains("private-key-material"))
        assertFalse(message.toString().contains(message.payload.contentToString()))
    }

    private fun header(
        command: Int = 0x4e584e43,
        payloadLength: Int = 0,
        checksum: Int = 0,
        magic: Int = command xor -1,
    ): ByteArray = ByteBuffer.allocate(AdbMessageCodec.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(command)
        putInt(1)
        putInt(2)
        putInt(payloadLength)
        putInt(checksum)
        putInt(magic)
    }.array()
}
