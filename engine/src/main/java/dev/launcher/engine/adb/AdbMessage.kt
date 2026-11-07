package dev.launcher.engine.adb

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One framed message on the Android Debug Bridge transport. */
class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    payload: ByteArray = ByteArray(0),
) {
    private val payloadBytes = payload.copyOf()

    init {
        require(payloadBytes.size <= AdbMessageCodec.MAX_PAYLOAD_BYTES) {
            "ADB payload is ${payloadBytes.size} bytes; maximum is ${AdbMessageCodec.MAX_PAYLOAD_BYTES}"
        }
    }

    val payload: ByteArray get() = payloadBytes.copyOf()

    override fun equals(other: Any?): Boolean = other is AdbMessage &&
        command == other.command && arg0 == other.arg0 && arg1 == other.arg1 &&
        payloadBytes.contentEquals(other.payloadBytes)

    override fun hashCode(): Int {
        var result = command
        result = 31 * result + arg0
        result = 31 * result + arg1
        result = 31 * result + payloadBytes.contentHashCode()
        return result
    }

    /** Deliberately reports only payload size: ADB payloads can contain commands and key material. */
    override fun toString(): String =
        "AdbMessage(command=0x${command.toUInt().toString(16).padStart(8, '0')}, arg0=$arg0, arg1=$arg1, " +
            "payloadLength=${payloadBytes.size})"
}

class AdbProtocolException(message: String) : IOException(message)

/**
 * Bounds ADB framing before allocating a peer-controlled payload. The caller supplies the negotiated
 * transport version; checksum skipping starts at [VERSION_SKIP_CHECKSUM], independently of payload bounds.
 */
object AdbMessageCodec {
    const val HEADER_BYTES = 24
    const val MAX_PAYLOAD_BYTES = 4096
    const val VERSION_SKIP_CHECKSUM = 0x01000001

    fun encode(message: AdbMessage, negotiatedVersion: Int): ByteArray {
        val payload = message.payload
        return ByteBuffer.allocate(HEADER_BYTES + payload.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(message.command)
            putInt(message.arg0)
            putInt(message.arg1)
            putInt(payload.size)
            putInt(if (negotiatedVersion < VERSION_SKIP_CHECKSUM) checksum(payload) else 0)
            putInt(message.command xor -1)
            put(payload)
        }.array()
    }

    fun decode(input: InputStream, negotiatedVersion: Int, negotiateConnection: Boolean = false): AdbMessage {
        val header = ByteBuffer.wrap(readExactly(input, HEADER_BYTES, "ADB message header"))
            .order(ByteOrder.LITTLE_ENDIAN)
        val command = header.int
        val arg0 = header.int
        val arg1 = header.int
        val payloadLength = header.int
        val expectedChecksum = header.int
        val magic = header.int

        if (payloadLength < 0) throw AdbProtocolException("Negative ADB payload length: $payloadLength")
        if (payloadLength > MAX_PAYLOAD_BYTES) {
            throw AdbProtocolException("ADB payload length $payloadLength exceeds $MAX_PAYLOAD_BYTES")
        }
        if (magic != command xor -1) throw AdbProtocolException("Invalid ADB command magic")

        val payload = readExactly(input, payloadLength, "ADB message payload")
        val checksumVersion = if (negotiateConnection && command == AdbCommands.CNXN) arg0 else negotiatedVersion
        if (checksumVersion < VERSION_SKIP_CHECKSUM && checksum(payload) != expectedChecksum) {
            throw AdbProtocolException("Invalid ADB payload checksum")
        }
        return AdbMessage(command, arg0, arg1, payload)
    }

    private fun checksum(payload: ByteArray): Int = payload.sumOf { it.toInt() and 0xff }

    private fun readExactly(input: InputStream, size: Int, what: String): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(bytes, offset, size - offset)
            if (count < 0) throw AdbProtocolException("Truncated $what: read $offset of $size bytes")
            if (count == 0) {
                val byte = input.read()
                if (byte < 0) throw AdbProtocolException("Truncated $what: read $offset of $size bytes")
                bytes[offset++] = byte.toByte()
            } else {
                offset += count
            }
        }
        return bytes
    }
}
