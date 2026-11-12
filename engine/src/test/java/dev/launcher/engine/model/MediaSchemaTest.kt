package dev.launcher.engine.model

import org.junit.Assert.*
import org.junit.Test

class MediaSchemaTest {
    private fun codes(body: String) = Validator(TokenCheck { false }).check(ModelTexts.component("""
        component "c" {
            uses { source media }
            view { $body }
        }
    """)).map { it.code }

    @Test fun acceptedBindingsAndDefaults() {
        assertTrue(codes("""box height=(dp)4 loop-from=(dp)4 loop-to=(dp)14 loop-period=1000 loop-phase=0.25 loop-run="{media.playing}"""").isEmpty())
        assertTrue(codes("""text "title" overflow=fade fade=(dp)12 scroll="{media.playing}"""").isEmpty())
        assertTrue(codes("""image picture="{media.art}" size=(dp)20 radius=(dp)4""").isEmpty())
        assertEquals("cover", Primitives.ALL.getValue("image").attrs.getValue("fit").default)
    }

    @Test fun refusesLiteralsAndUndeclaredSources() {
        for (v in listOf("forged", "123", "#null", "#true"))
            assertEquals(listOf("picture-not-literal"), codes("image picture=$v"))
        assertEquals(listOf("source-not-in-uses"), codes("""image picture="{notifications.art}""""))
        assertEquals(listOf("misplaced"), codes("image { text x }"))
        assertEquals(listOf("wrong-type"), codes("image fit=contain"))
    }

    @Test fun refusesInvalidLoopAndTitleAttributes() {
        for (attr in listOf("loop-period=199", "loop-period=10001", "loop-phase=-0.1", "loop-phase=1.1", "loop-from=-1"))
            assertTrue(codes("box loop-from=4 loop-to=14 $attr".replace("loop-from=4 loop-to=14 loop-from=-1", "loop-from=-1 loop-to=14")).contains("motion-range"))
        assertEquals(listOf("loop-endpoints"), codes("box loop-run=#true"))
        assertEquals(listOf("wrong-type"), codes("text x scroll=1"))
        assertEquals(listOf("wrong-type"), codes("text x overflow=ellipsis"))
        assertEquals(listOf("motion-range"), codes("text x fade=-1"))
        assertTrue(codes("text x loop-from=4").contains("unknown-attribute"))
    }
}
