package dev.launcher.engine.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class PairingPacketType(val wireValue: Int) {
    SPAKE2_MESSAGE(0),
    PEER_INFO(1),
}

/** The six-byte, network-order header preceding an ADB pairing packet. */
class PairingPacketHeader(val type: PairingPacketType, val payloadLength: Int) {
    init {
        require(payloadLength in 1..MAX_PAYLOAD_BYTES) {
            "Pairing payload length $payloadLength is outside 1..$MAX_PAYLOAD_BYTES"
        }
    }

    fun encode(): ByteArray = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN).apply {
        put(VERSION.toByte())
        put(type.wireValue.toByte())
        putInt(payloadLength)
    }.array()

    override fun toString(): String =
        "PairingPacketHeader(version=$VERSION, type=$type, payloadLength=$payloadLength)"

    companion object {
        const val HEADER_BYTES = 6
        const val MAX_PAYLOAD_BYTES = 16_384
        const val VERSION = 1

        fun decode(bytes: ByteArray): PairingPacketHeader {
            if (bytes.size != HEADER_BYTES) {
                throw AdbProtocolException("Truncated pairing header: expected $HEADER_BYTES bytes, got ${bytes.size}")
            }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val version = buffer.get().toInt() and 0xff
            val typeValue = buffer.get().toInt() and 0xff
            val payloadLength = buffer.int
            if (version != VERSION) throw AdbProtocolException("Unsupported pairing header version: $version")
            val type = PairingPacketType.entries.firstOrNull { it.wireValue == typeValue }
                ?: throw AdbProtocolException("Unsupported pairing packet type: $typeValue")
            if (payloadLength <= 0 || payloadLength > MAX_PAYLOAD_BYTES) {
                throw AdbProtocolException("Pairing payload length $payloadLength is outside 1..$MAX_PAYLOAD_BYTES")
            }
            return PairingPacketHeader(type, payloadLength)
        }
    }
}
