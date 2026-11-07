package dev.launcher.engine.adb

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class PairingPacketsTest {
    @Test fun refusesWrongStageAndOversizedSpakeBeforeReadingPayload() {
        for (header in listOf(PairingPacketHeader(PairingPacketType.PEER_INFO, 1),
            PairingPacketHeader(PairingPacketType.SPAKE2_MESSAGE, 33))) {
            assertThrows(AdbProtocolException::class.java) {
                PairingPackets.read(header.encode().inputStream(), PairingPacketType.SPAKE2_MESSAGE, 32)
            }
        }
    }
    @Test fun readsBoundedSpakeAndRefusesTruncation() {
        val header = PairingPacketHeader(PairingPacketType.SPAKE2_MESSAGE, 32).encode()
        assertEquals(32, PairingPackets.read((header + ByteArray(32)).inputStream(), PairingPacketType.SPAKE2_MESSAGE, 32).size)
        assertThrows(java.io.EOFException::class.java) {
            PairingPackets.read((header + ByteArray(31)).inputStream(), PairingPacketType.SPAKE2_MESSAGE, 32)
        }
    }
    @Test fun validatesExactPeerInfoSizeAndType() {
        for (size in listOf(0, 8191, 8193)) assertThrows(AdbProtocolException::class.java) {
            PairingPackets.validatePeerInfo(ByteArray(size))
        }
        assertThrows(AdbProtocolException::class.java) { PairingPackets.validatePeerInfo(ByteArray(8192)) }
        PairingPackets.validatePeerInfo(ByteArray(8192).apply { this[0] = 1 })
    }
    @Test fun invalidCodesFailBeforeOpeningSocket() {
        val rsa = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val key = AdbKey(rsa.private as java.security.interfaces.RSAPrivateCrtKey, "test")
        for (code in listOf("", "12345", "1234567", "12345a", "１２３４５６")) {
            AdbPairingClient(AdbEndpoint(InetAddress.getLoopbackAddress(), 1, null), key).use {
                assertThrows(IllegalArgumentException::class.java) { it.pair(code) }
            }
        }
    }
}
