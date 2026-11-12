package dev.launcher.engine.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T-015: `blob`'s schema: a container with a fill and a smooth reach; its shapes are its `box` children. */
class BlobSchemaTest {
    private fun codes(body: String) = Validator(TokenCheck { false }).check(ModelTexts.component("""
        component "c" {
            view { $body }
        }
    """)).map { it.code }

    @Test fun aBlobHoldsBoxesWithAFillAndAReach() {
        assertTrue(codes("""blob fill="#000000" smooth=(dp)24 { box width=(dp)132 height=(dp)32 radius="full"; box width=(dp)200 height=(dp)60 }""").isEmpty())
        assertTrue("its other children are allowed", codes("""blob fill="#000000" { box width=10 height=10; text "x" }""").isEmpty())
        assertTrue(Primitives.ALL.getValue("blob").container)
    }

    @Test fun itsValuesAreChecked() {
        assertEquals(listOf("wrong-type"), codes("""blob fill=12"""))
        assertTrue(codes("""blob smooth="wide"""").isNotEmpty())
    }
}
