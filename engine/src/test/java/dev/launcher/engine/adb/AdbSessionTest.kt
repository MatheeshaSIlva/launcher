package dev.launcher.engine.adb

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AdbSessionTest {
    private val old = 0x01000000
    private val modern = AdbMessageCodec.VERSION_SKIP_CHECKSUM
    private fun msg(command: Int, a: Int = 0, b: Int = 0, text: String = "") =
        AdbMessage(command, a, b, text.toByteArray())
    private fun hello(max: Int = 4096, version: Int = modern) = msg(AdbCommands.CNXN, version, max, "device::\u0000")
    private fun stls() = msg(AdbCommands.STLS, old)
    private class Transcript(val session: AdbSession, val output: ByteArrayOutputStream, val upgraded: () -> Boolean)
    private fun transcript(vararg messages: AdbMessage, version: Int = modern): Transcript {
        val bytes = messages.flatMapIndexed { i, m -> AdbMessageCodec.encode(m, if (i == 0) old else version).toList() }.toByteArray()
        val input = ByteArrayInputStream(bytes)
        val output = ByteArrayOutputStream()
        var tls = false
        return Transcript(AdbSession({ input }, { output }) { tls = true }, output) { tls }
    }

    @Test fun tlsNegotiationShellIdsNulsAcknowledgementsAndClose() {
        val t = transcript(stls(), hello(), msg(AdbCommands.OKAY, 42, 1), msg(AdbCommands.WRTE, 42, 1, "uid=2000(shell)"), msg(AdbCommands.CLSE, 42, 1))
        t.session.connect()
        assertTrue(t.upgraded())
        assertEquals("uid=2000(shell)", t.session.shell("id").toString(Charsets.UTF_8))
        val out = t.output.toByteArray().inputStream()
        val connect = AdbMessageCodec.decode(out, old)
        assertEquals(4096, connect.arg1)
        assertEquals(modern, connect.arg0)
        assertEquals("host::\u0000", connect.payload.toString(Charsets.UTF_8))
        assertEquals(AdbCommands.STLS, AdbMessageCodec.decode(out, old).command)
        val open = AdbMessageCodec.decode(out, modern)
        assertEquals("shell,raw:id\u0000", open.payload.toString(Charsets.UTF_8))
        assertEquals(1, open.arg0)
        assertEquals(AdbMessage(AdbCommands.OKAY, 1, 42), AdbMessageCodec.decode(out, modern))
        assertEquals(AdbMessage(AdbCommands.CLSE, 1, 42), AdbMessageCodec.decode(out, modern))
    }

    @Test fun supportsLegacyChecksumsAfterTls() {
        val t = transcript(stls(), hello(version = old), msg(AdbCommands.OKAY, 42, 1), msg(AdbCommands.WRTE, 42, 1, "ok"), msg(AdbCommands.CLSE, 42, 1), version = old)
        t.session.connect()
        assertEquals("ok", t.session.shell("id").toString(Charsets.UTF_8))
    }

    @Test fun initialAndLegacyConnectionChecksumsAreValidated() {
        val initial = AdbMessageCodec.encode(stls(), old).apply { this[16] = 1 }
        assertThrows(AdbProtocolException::class.java) {
            AdbSession({ initial.inputStream() }, { ByteArrayOutputStream() }) { error("Must not upgrade") }.connect()
        }
        val corruptHello = AdbMessageCodec.encode(hello(version = old), old).apply { this[16] = 0 }
        val input = (AdbMessageCodec.encode(stls(), old) + corruptHello).inputStream()
        assertThrows(AdbProtocolException::class.java) {
            AdbSession({ input }, { ByteArrayOutputStream() }) { }.connect()
        }
    }

    @Test fun refusesPlaintextAndAuthBeforeTls() {
        for (m in listOf(hello(), msg(0x48545541, 1))) {
            val t = transcript(m)
            assertThrows(AdbProtocolException::class.java) { t.session.connect() }
            assertFalse(t.upgraded())
        }
    }

    @Test fun peerMaxdataLimitsServiceBeforeOpen() {
        val t = transcript(stls(), hello(max = 5))
        t.session.connect()
        assertThrows(AdbProtocolException::class.java) { t.session.shell("id") }
    }

    @Test fun refusesZeroPeerMaxdata() {
        assertThrows(AdbProtocolException::class.java) { transcript(stls(), hello(0)).session.connect() }
    }

    @Test fun refusesWrongOpenIdsPrematureCloseAndMissingAcknowledgement() {
        for (packet in listOf(msg(AdbCommands.OKAY, 0, 1), msg(AdbCommands.OKAY, 42, 2),
            msg(AdbCommands.CLSE, 0, 1), msg(AdbCommands.WRTE, 42, 1, "early"))) {
            val t = transcript(stls(), hello(), packet)
            t.session.connect()
            assertThrows(AdbProtocolException::class.java) { t.session.shell("id") }
        }
    }

    @Test fun refusesChangedStreamIdUnexpectedPacketAndTruncatedStream() {
        for (packet in listOf(msg(AdbCommands.WRTE, 43, 1), msg(AdbCommands.CLSE, 42, 2), msg(AdbCommands.OKAY, 42, 1))) {
            val t = transcript(stls(), hello(), msg(AdbCommands.OKAY, 42, 1), packet)
            t.session.connect()
            assertThrows(AdbProtocolException::class.java) { t.session.shell("id") }
        }
        val t = transcript(stls(), hello(), msg(AdbCommands.OKAY, 42, 1))
        t.session.connect()
        assertThrows(AdbProtocolException::class.java) { t.session.shell("id") }
    }

    @Test fun boundedOutputAndEmbeddedNulAreRefused() {
        val messages = mutableListOf(stls(), hello(), msg(AdbCommands.OKAY, 42, 1))
        repeat(257) { messages += msg(AdbCommands.WRTE, 42, 1, "x".repeat(4096)) }
        val t = transcript(*messages.toTypedArray())
        t.session.connect()
        assertThrows(AdbProtocolException::class.java) { t.session.shell("id") }
        val nul = transcript(stls(), hello())
        nul.session.connect()
        assertThrows(IllegalArgumentException::class.java) { nul.session.shell("id\u0000ignored") }
    }

    @Test fun stdinChunksWaitForAcknowledgementsWhileOutputIsInterleaved() {
        val incoming = listOf(stls(), hello(13), msg(AdbCommands.OKAY, 42, 1),
            msg(AdbCommands.WRTE, 42, 1, "out"), msg(AdbCommands.OKAY, 42, 1),
            msg(AdbCommands.OKAY, 42, 1), msg(AdbCommands.OKAY, 42, 1), msg(AdbCommands.CLSE, 42, 1))
        val output = ByteArrayOutputStream()
        val sent = mutableListOf<AdbMessage>()
        var readOffset = 0
        var packet = 0
        val session = AdbSession({
            val bytes = output.toByteArray()
            val unread = bytes.copyOfRange(readOffset, bytes.size).inputStream()
            while (unread.available() > 0) sent += AdbMessageCodec.decode(unread, if (packet < 2) old else modern)
            readOffset = bytes.size
            // A second chunk must not be sent while the first one's ACK is pending.
            if (packet == 3 || packet == 4) assertEquals(1, sent.count { it.command == AdbCommands.WRTE })
            if (packet == 5) {
                assertEquals(listOf(AdbCommands.WRTE, AdbCommands.OKAY, AdbCommands.WRTE), sent.takeLast(3).map { it.command })
            }
            AdbMessageCodec.encode(incoming[packet], if (packet++ == 0) old else modern).inputStream()
        }, { output }) { }
        session.connect()
        assertEquals("out", session.shell("id", "abcdefghijklmnopqrstuvwxyz\n".toByteArray()).toString(Charsets.UTF_8))
        val writes = sent.filter { it.command == AdbCommands.WRTE }
        assertEquals(listOf("abcdefghijklm", "nopqrstuvwxyz", "\n"), writes.map { it.payload.toString(Charsets.UTF_8) })
        assertTrue(writes.all { it.arg0 == 1 && it.arg1 == 42 })
    }

    @Test fun refusesPrematureCloseBadAckAndChangedIdsDuringStdin() {
        for (packet in listOf(msg(AdbCommands.CLSE, 42, 1), msg(AdbCommands.OKAY, 43, 1),
            msg(AdbCommands.OKAY, 42, 2), msg(AdbCommands.OKAY, 42, 1, "bad"), msg(AdbCommands.CLSE, 42, 1, "bad"))) {
            val t = transcript(stls(), hello(13), msg(AdbCommands.OKAY, 42, 1), packet)
            t.session.connect()
            assertThrows(AdbProtocolException::class.java) { t.session.shell("id", ByteArray(14)) }
        }
    }

    @Test fun acknowledgementStallPropagatesWithoutSendingAnotherChunk() {
        val output = ByteArrayOutputStream()
        val bytes = (AdbMessageCodec.encode(stls(), old) + AdbMessageCodec.encode(hello(13), modern) +
            AdbMessageCodec.encode(msg(AdbCommands.OKAY, 42, 1), modern)).inputStream()
        val stalled = object : java.io.InputStream() {
            override fun read(): Int = if (bytes.available() > 0) bytes.read() else throw java.net.SocketTimeoutException("test stall")
        }
        val session = AdbSession({ stalled }, { output }) { }
        session.connect()
        assertThrows(java.net.SocketTimeoutException::class.java) { session.shell("id", ByteArray(14)) }
        val out = output.toByteArray().inputStream()
        val sent = mutableListOf<AdbMessage>()
        repeat(4) { sent += AdbMessageCodec.decode(out, if (it < 2) old else modern) }
        assertEquals(listOf(AdbCommands.CNXN, AdbCommands.STLS, AdbCommands.OPEN, AdbCommands.WRTE), sent.map { it.command })
        assertEquals(13, sent.last().payload.size)
        assertEquals(0, out.available())
    }

    @Test fun stdinLimitIsCheckedBeforeOpen() {
        val t = transcript(stls(), hello())
        t.session.connect()
        val before = t.output.size()
        assertThrows(IllegalArgumentException::class.java) { t.session.shell("id", ByteArray(4097)) }
        assertEquals(before, t.output.size())
    }
}
