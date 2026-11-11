package dev.launcher.engine.model

import dev.launcher.engine.kdl.KdlParser
import org.junit.Assert.*
import org.junit.Test

class PlaceTest {
    private fun text(body: String, uses: String = "", fallback: String = "") =
        "component \"test\" {\n$uses\nstate { anchor \"window\" }\nview { $body }\n$fallback\n}"
    private fun findings(text: String): List<Diagnostic> = when (val r = Validator(TokenCheck { true }).load(KdlParser.parse(text))) {
        is ReadResult.Ok -> emptyList()
        is ReadResult.Invalid -> r.diagnostics
    }
    private fun refused(text: String, code: String, value: String) {
        val d = findings(text).single { it.code == code }
        assertEquals(value, text.substring(d.span.start, d.span.end))
        assertTrue(d.span.line > 0 && d.span.col > 0)
    }

    @Test fun windowDefaultAndBothLiteralRoots() {
        assertEquals("window", Primitives.ALL.getValue("box").attrs.getValue("place").default)
        for (root in listOf("box", "box place=window", "box place=cutout"))
            assertTrue(findings(text(root, "uses { source device }", "fallback { box place=cutout }")).isEmpty())
        assertTrue(findings(text("box place=window")).isEmpty())
    }

    @Test fun cutoutRequiresDeviceWithReasonOnViewAndFallback() {
        for (t in listOf(text("box place=cutout"), text("box", fallback = "fallback { box place=cutout }"))) {
            refused(t, "place-needs-device", "cutout")
            val d = findings(t).single()
            assertTrue(d.message.contains("geometry"))
            assertEquals("uses { source device }", d.hint)
        }
        assertTrue(findings(text("box place=cutout", "uses { source battery }")).any { it.code == "place-needs-device" })
    }

    @Test fun childrenAndVariantOverridesAreNotRoots() {
        for (body in listOf("box { box place=window }", "box; variant \"compact\" { box place=window }",
            "box; variant \"compact\" { box { box place=window } }", "unknown { box place=window }"))
            refused(text(body), "place-root-only", "window")
        refused(text("box", fallback = "fallback { box { box place=window } }"), "place-root-only", "window")
        val t = text("box; variant \"compact\" { box place=cutout }")
        refused(t, "place-root-only", "cutout")
        refused(t, "place-needs-device", "cutout")
    }

    @Test fun invalidLiteralsAndEveryBindingFormHaveValueSpans() {
        for (value in listOf("centre", "#true", "42", "#null")) refused(text("box place=$value"), "wrong-type", value)
        for (value in listOf("\"${'$'}anchor\"", "\"@anchor\"", "\"{'cutout'}\"")) {
            refused(text("box place=$value"), "not-bindable", value)
            refused(text("box", fallback = "fallback { box place=$value }"), "not-bindable", value)
            refused(text("box; variant \"compact\" { box place=$value }"), "not-bindable", value)
        }
    }

    @Test fun declaredDeviceCanBeReadInExpressions() {
        assertTrue(findings(text("box width=\"{device.width}\"", "uses { source device }")).isEmpty())
        assertEquals(listOf("source-not-in-uses"), findings(text("box width=\"{device.width}\"")).map { it.code })
    }
}
