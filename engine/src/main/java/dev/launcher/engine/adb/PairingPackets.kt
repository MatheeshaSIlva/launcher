package dev.launcher.engine.adb

import java.io.DataInputStream
import java.io.InputStream

/** Stage-specific bounds are checked before allocating the packet's payload. */
internal object PairingPackets {
    const val PEER_INFO_BYTES = 8192
    fun read(stream: InputStream, expected: PairingPacketType, maximum: Int): ByteArray {
        val input = DataInputStream(stream)
        val header = PairingPacketHeader.decode(ByteArray(PairingPacketHeader.HEADER_BYTES).also { input.readFully(it) })
        if (header.type != expected || header.payloadLength > maximum) throw AdbProtocolException("Unexpected pairing packet")
        return ByteArray(header.payloadLength).also { input.readFully(it) }
    }

    fun validatePeerInfo(bytes: ByteArray) {
        if (bytes.size != PEER_INFO_BYTES) throw AdbProtocolException("Invalid peer-info size")
        if (bytes[0] != 1.toByte()) throw AdbProtocolException("Invalid peer-info type")
    }
}
