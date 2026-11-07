package dev.launcher.engine.shell

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException

class CapabilityReaderTest {
    private val capability = "0123456789abcdef".repeat(4)

    @Test fun exactLineStopsAtLfWithoutWaitingForEofOrClosingInput() {
        val bytes = (capability + "\n").toByteArray().inputStream()
        val guarded = object : InputStream() {
            override fun read(): Int {
                check(bytes.available() > 0) { "Must not wait for EOF" }
                return bytes.read()
            }
            override fun close() = error("Adapter owns the descriptor")
        }
        assertEquals(capability, CapabilityReader.read(guarded))
        val twoLines = (capability + "\nnext").byteInputStream()
        assertEquals(capability, CapabilityReader.read(twoLines))
        assertEquals('n'.code, twoLines.read())
    }

    @Test fun refusesEmptyShortLongMalformedAndMissingLf() {
        for (line in listOf("", "\n", capability.take(63) + "\n", capability + "0\n", capability,
            capability + "\r\n", capability.uppercase() + "\n", "g" + capability.drop(1) + "\n",
            "\u0000" + capability.drop(1) + "\n", "é" + capability.drop(1) + "\n")) {
            assertThrows(IOException::class.java) { CapabilityReader.read(line.byteInputStream()) }
        }
    }

    @Test fun unendingInputIsRefusedWithin65Reads() {
        var reads = 0
        val input = object : InputStream() {
            override fun read(): Int { reads++; return 'a'.code }
        }
        assertThrows(IOException::class.java) { CapabilityReader.read(input) }
        assertEquals(65, reads)
    }

    @Test fun zeroAndPartialLineDeadlineFailuresPropagate() {
        for (prefix in listOf("", capability.take(32), capability)) {
            val bytes = prefix.byteInputStream()
            val input = object : InputStream() {
                override fun read(): Int = if (bytes.available() > 0) bytes.read() else throw SocketTimeoutException("test deadline")
            }
            assertThrows(SocketTimeoutException::class.java) { CapabilityReader.read(input) }
        }
    }
}
