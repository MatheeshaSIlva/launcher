package dev.launcher.engine.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** E2.14d: `decode` on text: a duration in ms (0..3000) and a glyph pool, both fixed when the component loads. */
class DecodeSchemaTest {
    private fun codes(body: String) = Validator(TokenCheck { false }).check(ModelTexts.component("""
        component "c" {
            props { t type=string default="a" }
            view { $body }
        }
    """)).map { it.code }

    @Test fun aTextDecodesWithADurationAndAPool() {
        assertTrue(codes("""text "{@t}" decode=400 decode-glyphs="#*+"""").isEmpty())
        assertTrue(codes("""text "{@t}" decode=0""").isEmpty())
    }

    @Test fun itsValuesAreBoundedAndFixed() {
        assertEquals(listOf("motion-range"), codes("""text "x" decode=5000"""))
        assertEquals(listOf("motion-range"), codes("""text "x" decode=-1"""))
        assertEquals(listOf("not-bindable"), codes("""text "x" decode="{@t}""""))
        assertTrue("only text decodes", codes("""box decode=400""").isNotEmpty())
    }
}
