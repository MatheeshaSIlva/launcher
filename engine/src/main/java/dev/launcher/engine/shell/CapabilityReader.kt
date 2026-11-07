package dev.launcher.engine.shell

import java.io.IOException
import java.io.InputStream

/** Pure line parser. Starter must supply deadline-aware input; a clock cannot bound a blocking read. */
object CapabilityReader {
    /**
     * Reads exactly 64 lowercase hex bytes followed by LF, without waiting for EOF or closing input.
     * Stops at LF and does not read the next line. At most 65 bytes are read, below the 128-byte ceiling.
     */
    fun read(input: InputStream): String {
        val line = StringBuilder(64)
        repeat(64) {
            val byte = input.read()
            if (byte !in '0'.code..'9'.code && byte !in 'a'.code..'f'.code) throw IOException("Invalid capability line")
            line.append(byte.toChar())
        }
        if (input.read() != '\n'.code) throw IOException("Invalid capability line")
        return line.toString()
    }
}
