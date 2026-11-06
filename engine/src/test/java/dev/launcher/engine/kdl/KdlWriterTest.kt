package dev.launcher.engine.kdl

import org.junit.Assert.assertEquals
import org.junit.Test

/** E2 slice 2b: KdlWriter's canonical form (kdl-org's test suite rules; the suite itself is KdlSuiteTest). */
class KdlWriterTest {
    @Test fun writerCanonicalForm() {
        val doc = KdlParser.parse("(t)\"my node\" 0x1F 1_0.5e3 #\"raw \"q\"\"# z=1 a=\"x\\ny\" { child; /-gone }\n// c\nempty {}")
        assertEquals("(t)\"my node\" 31 10.5E+3 \"raw \\\"q\\\"\" a=\"x\\ny\" z=1 {\n    child\n}\nempty\n", KdlWriter.write(doc))
        assertEquals("\n", KdlWriter.write(KdlParser.parse("// nothing")))
        assertEquals("plain", KdlWriter.string("plain"))
        assertEquals("\"1abc\"", KdlWriter.string("1abc"))
        assertEquals("\"true\"", KdlWriter.string("true"))
        assertEquals("\"\"", KdlWriter.string(""))
        assertEquals("\"a\\u{b}b\"", KdlWriter.string("a\u000Bb"))
    }
}
