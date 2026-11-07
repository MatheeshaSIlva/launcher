package dev.launcher.engine.adb

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

object AdbTimeouts {
    const val CONNECT_MS = 5000
    const val HANDSHAKE_MS = 10000
    const val READ_MS = 10000
    const val OPERATION_MS = 30000L
}

/** Owns the socket before connect so cancellation also closes stalled connect/handshake/write calls. */
internal class AdbTransport : Closeable {
    private val lock = Any()
    private var closed = false
    private var raw: Socket? = null
    private var tls: SSLSocket? = null
    private val deadline = timer.schedule({ close() }, AdbTimeouts.OPERATION_MS, TimeUnit.MILLISECONDS)

    val input: InputStream get() = synchronized(lock) { checkNotNull(tls ?: raw).getInputStream() }
    val output: OutputStream get() = synchronized(lock) { checkNotNull(tls ?: raw).getOutputStream() }

    fun connect(endpoint: AdbEndpoint) {
        val socket = synchronized(lock) {
            check(!closed && raw == null) { "ADB transport closed or already used" }
            (endpoint.network?.socketFactory?.createSocket() ?: Socket()).also { raw = it }
        }
        socket.tcpNoDelay = true
        socket.soTimeout = AdbTimeouts.READ_MS
        socket.connect(InetSocketAddress(endpoint.address, endpoint.port), AdbTimeouts.CONNECT_MS)
    }

    fun startTls(endpoint: AdbEndpoint, context: SSLContext): SSLSocket {
        val socket = synchronized(lock) {
            check(!closed)
            (context.socketFactory.createSocket(checkNotNull(raw), endpoint.address.hostAddress, endpoint.port, true)
                as SSLSocket).also { tls = it }
        }
        socket.enabledProtocols = arrayOf("TLSv1.3")
        socket.useClientMode = true
        socket.soTimeout = AdbTimeouts.HANDSHAKE_MS
        socket.startHandshake()
        socket.soTimeout = AdbTimeouts.READ_MS
        return socket
    }

    override fun close() {
        val sockets = synchronized(lock) {
            if (closed) return
            closed = true
            listOfNotNull(raw, tls)
        }
        // Raw first: an SSLSocket close may otherwise wait for a blocked TLS write.
        sockets.forEach { try { it.close() } catch (_: Exception) { } }
        deadline.cancel(false)
    }

    companion object {
        private val timer = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "adb-operation-deadline").apply { isDaemon = true }
        }
    }
}
