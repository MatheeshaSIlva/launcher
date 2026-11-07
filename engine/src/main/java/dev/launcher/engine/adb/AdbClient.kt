package dev.launcher.engine.adb

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/** Wireless ADB only: a peer which offers plaintext CNXN/AUTH is refused. One bounded shell operation. */
class AdbClient(private val endpoint: AdbEndpoint, private val key: AdbKey) : Closeable {
    private val transport = AdbTransport()
    private val session = AdbSession({ transport.input }, { transport.output }) {
        transport.startTls(endpoint, key.sslContext)
    }

    fun connect() {
        try { transport.connect(endpoint); session.connect() }
        catch (error: Exception) { close(); throw error }
    }

    fun shell(command: String): ByteArray = try { session.shell(command) } finally { close() }
    override fun close() = transport.close()
}

internal object AdbCommands {
    const val CNXN = 0x4e584e43
    const val STLS = 0x534c5453
    const val OPEN = 0x4e45504f
    const val OKAY = 0x59414b4f
    const val WRTE = 0x45545257
    const val CLSE = 0x45534c43
    const val TLS_VERSION = 0x01000000
}

/** Protocol state, separated from Android sockets so hostile transcripts can be tested on the JVM. */
internal class AdbSession(
    private val input: () -> InputStream,
    private val output: () -> OutputStream,
    private val upgradeTls: () -> Unit,
) {
    private var version = 0x01000000
    private var peerMax = AdbMessageCodec.MAX_PAYLOAD_BYTES
    private var connected = false
    private var used = false

    fun connect() {
        check(!connected)
        send(AdbCommands.CNXN, AdbMessageCodec.VERSION_SKIP_CHECKSUM, AdbMessageCodec.MAX_PAYLOAD_BYTES, nul("host::"))
        val tls = receive()
        if (tls.command != AdbCommands.STLS || tls.arg0 != AdbCommands.TLS_VERSION || tls.arg1 != 0 || tls.payload.isNotEmpty()) {
            throw AdbProtocolException("Wireless ADB requires STLS")
        }
        send(AdbCommands.STLS, AdbCommands.TLS_VERSION, 0)
        upgradeTls()
        val hello = AdbMessageCodec.decode(input(), version, negotiateConnection = true)
        if (hello.command != AdbCommands.CNXN || hello.arg0 < 0x01000000 || hello.arg1 <= 0) {
            throw AdbProtocolException("Invalid ADB connection response")
        }
        version = minOf(hello.arg0, AdbMessageCodec.VERSION_SKIP_CHECKSUM)
        peerMax = minOf(hello.arg1, AdbMessageCodec.MAX_PAYLOAD_BYTES)
        connected = true
    }

    fun shell(command: String): ByteArray {
        check(connected && !used)
        used = true
        require(command.length <= AdbMessageCodec.MAX_PAYLOAD_BYTES) { "Shell service exceeds limit" }
        val service = nul("shell:$command")
        if (service.size > peerMax) throw AdbProtocolException("Shell service exceeds peer maxdata")
        val local = 1
        send(AdbCommands.OPEN, local, 0, service)
        val opened = receive()
        if (opened.command != AdbCommands.OKAY || opened.arg0 == 0 || opened.arg1 != local || opened.payload.isNotEmpty()) {
            throw AdbProtocolException("ADB stream refused or invalid acknowledgement")
        }
        val remote = opened.arg0
        val result = java.io.ByteArrayOutputStream()
        while (true) {
            val message = receive()
            if (message.arg0 != remote || message.arg1 != local) throw AdbProtocolException("ADB stream ID mismatch")
            when (message.command) {
                AdbCommands.WRTE -> {
                    val bytes = message.payload
                    if (result.size() + bytes.size > MAX_SHELL_OUTPUT) throw AdbProtocolException("Shell output exceeds limit")
                    result.write(bytes)
                    send(AdbCommands.OKAY, local, remote)
                }
                AdbCommands.CLSE -> {
                    if (message.payload.isNotEmpty()) throw AdbProtocolException("Invalid ADB close")
                    send(AdbCommands.CLSE, local, remote)
                    return result.toByteArray()
                }
                else -> throw AdbProtocolException("Unexpected ADB stream packet")
            }
        }
    }

    private fun receive() = AdbMessageCodec.decode(input(), version)
    private fun send(command: Int, arg0: Int, arg1: Int, bytes: ByteArray = ByteArray(0)) {
        output().apply { write(AdbMessageCodec.encode(AdbMessage(command, arg0, arg1, bytes), version)); flush() }
    }
    private fun nul(value: String): ByteArray {
        require('\u0000' !in value) { "Embedded NUL in ADB service" }
        return (value + '\u0000').toByteArray(Charsets.UTF_8)
    }
    companion object { const val MAX_SHELL_OUTPUT = 1_048_576 }
}
