package dev.launcher.engine.adb

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AdbTransportTest {
    @Test fun stalledTlsHandshakeHitsHandshakeTimeout() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val transport = AdbTransport()
            try {
                val endpoint = AdbEndpoint(InetAddress.getLoopbackAddress(), server.localPort, null)
                transport.connect(endpoint)
                server.accept().use {
                    val start = System.nanoTime()
                    assertThrows(SocketTimeoutException::class.java) {
                        transport.startTls(endpoint, javax.net.ssl.SSLContext.getDefault())
                    }
                    val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                    assertTrue(elapsed >= AdbTimeouts.HANDSHAKE_MS - 1000)
                    assertTrue(elapsed < AdbTimeouts.OPERATION_MS)
                }
            } finally { transport.close() }
        }
    }
    @Test fun stalledPeerHitsReadTimeoutAndRepeatedCleanupIsSafe() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val transport = AdbTransport()
            try {
                transport.connect(AdbEndpoint(InetAddress.getLoopbackAddress(), server.localPort, null))
                server.accept().use {
                    val start = System.nanoTime()
                    assertThrows(SocketTimeoutException::class.java) { transport.input.read() }
                    val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                    assertTrue(elapsed >= AdbTimeouts.READ_MS - 1000)
                    assertTrue(elapsed < AdbTimeouts.OPERATION_MS)
                }
            } finally { transport.close(); transport.close() }
        }
    }

    @Test fun closeCancelsAnActiveRead() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val transport = AdbTransport()
            transport.connect(AdbEndpoint(InetAddress.getLoopbackAddress(), server.localPort, null))
            server.accept().use {
                val reading = CountDownLatch(1)
                val finished = CountDownLatch(1)
                val reader = Thread {
                    reading.countDown()
                    try { transport.input.read() } catch (_: java.io.IOException) { } finally { finished.countDown() }
                }
                reader.start()
                assertTrue(reading.await(1, TimeUnit.SECONDS))
                transport.close()
                assertTrue(finished.await(2, TimeUnit.SECONDS))
                reader.join()
            }
        }
    }

    @Test fun totalDeadlineStopsPeerWhichTricklesBeforeEachReadTimeout() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val transport = AdbTransport()
            val start = System.nanoTime()
            transport.connect(AdbEndpoint(InetAddress.getLoopbackAddress(), server.localPort, null))
            server.accept().use { peer ->
                val stop = CountDownLatch(1)
                val writer = Thread {
                    try {
                        while (!stop.await(2, TimeUnit.SECONDS)) { peer.getOutputStream().write(1); peer.getOutputStream().flush() }
                    } catch (_: java.io.IOException) { }
                }
                writer.start()
                try {
                    try { while (transport.input.read() >= 0) { } } catch (_: java.io.IOException) { }
                    val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                    assertTrue(elapsed >= AdbTimeouts.OPERATION_MS - 1000)
                    assertTrue(elapsed < AdbTimeouts.OPERATION_MS + 5000)
                } finally { stop.countDown(); transport.close(); writer.join(3000) }
            }
        }
    }
}
