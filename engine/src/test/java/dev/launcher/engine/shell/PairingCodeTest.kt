package dev.launcher.engine.shell

import org.junit.Assert.*
import org.junit.Test

class PairingCodeTest {
    @Test fun preservesLeadingZeroesAndSupportsMutableFieldInput() {
        assertEquals("000123", PairingCode.parse(StringBuilder("000123")))
        assertEquals("000000", PairingCode.parse("000000"))
        assertEquals("999999", PairingCode.parse("999999"))
    }
    @Test fun refusesMissingWhitespaceWrongLengthAndNonAsciiDigits() {
        for (input in listOf(null, "", "12345", "1234567", " 123456", "123456 ", "123 56", "12345\n",
            "\uff11\uff12\uff13\uff14\uff15\uff16", "\u0661\u0662\u0663\u0664\u0665\u0666", "abcdef", "123\u000056")) {
            assertNull(PairingCode.parse(input))
        }
    }
    @Test fun rejectsOversizeBeforeReadingOrConvertingInput() {
        val input = object : CharSequence {
            override val length = Int.MAX_VALUE
            override fun get(index: Int): Char = error("Must not read oversized input")
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = error("Must not copy")
            override fun toString(): String = error("Must not convert")
        }
        assertNull(PairingCode.parse(input))
    }
    @Test fun readsOnlySixCharactersWithoutConvertingTheField() {
        var reads = 0
        val input = object : CharSequence {
            override val length = 6
            override fun get(index: Int): Char { reads++; return '0' }
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = error("Must not copy")
            override fun toString(): String = error("Must not convert")
        }
        assertEquals("000000", PairingCode.parse(input))
        assertEquals(6, reads)
    }
}
