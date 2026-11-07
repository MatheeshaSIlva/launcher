package dev.launcher.engine.adb

import android.net.ssl.SSLSockets
import androidx.annotation.RequiresApi
import java.io.Closeable
import java.io.IOException

class AdbPairingRejectedException : IOException("ADB pairing authentication failed")

/** One pairing attempt; no code, exporter, peer identity or payload enters logs. */
@RequiresApi(31)
class AdbPairingClient(private val endpoint: AdbEndpoint, private val key: AdbKey) : Closeable {
    private val transport = AdbTransport()

    fun pair(code: String) {
        try {
            require(code.length == 6 && code.all { it in '0'..'9' }) { "Pairing code must be six ASCII digits" }
            transport.connect(endpoint)
            val tls = transport.startTls(endpoint, key.sslContext)
            val exporter = SSLSockets.exportKeyingMaterial(tls, "adb-label\u0000", null, 64)
            if (exporter == null || exporter.size != 64) throw AdbProtocolException("TLS exporter unavailable")
            val password = code.toByteArray(Charsets.US_ASCII) + exporter
            val context = try { PairingContext(password) } finally { password.fill(0); exporter.fill(0) }
            context.use {
                write(PairingPacketType.SPAKE2_MESSAGE, it.message())
                if (!it.initCipher(read(PairingPacketType.SPAKE2_MESSAGE, PairingContext.MAX_MESSAGE_BYTES))) {
                    throw AdbPairingRejectedException()
                }
                val peer = ByteArray(PairingPackets.PEER_INFO_BYTES)
                key.adbPublicKey.copyInto(peer, 1) // type 0: ADB_RSA_PUB_KEY
                val encrypted = it.encrypt(peer) ?: throw AdbPairingRejectedException()
                write(PairingPacketType.PEER_INFO, encrypted)
                val decrypted = it.decrypt(read(PairingPacketType.PEER_INFO, PairingPackets.PEER_INFO_BYTES + 16))
                    ?: throw AdbPairingRejectedException()
                // adbd's device GUID type is 1; it is intentionally neither retained nor logged.
                PairingPackets.validatePeerInfo(decrypted)
            }
        } finally { close() }
    }

    private fun write(type: PairingPacketType, bytes: ByteArray) {
        transport.output.apply { write(PairingPacketHeader(type, bytes.size).encode()); write(bytes); flush() }
    }
    private fun read(expected: PairingPacketType, maximum: Int): ByteArray =
        PairingPackets.read(transport.input, expected, maximum)
    override fun close() = transport.close()
}
