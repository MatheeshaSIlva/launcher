package dev.launcher.engine.model

import dev.launcher.engine.kdl.KdlParser
import org.junit.Assert.*
import org.junit.Test

class KnockoutTest {
    private fun codes(body: String, extra: String = ""): List<String> {
        val result = Validator(TokenCheck { true }).load(KdlParser.parse("""
            component "test" {
              state { enabled #true }
              view { $body }
              $extra
            }
        """.trimIndent()))
        return if (result is ReadResult.Invalid) result.diagnostics.map { it.code } else emptyList()
    }
    @Test fun rootAndUnisolatedKnockoutAreRefused() {
        for (body in listOf("text \"1\" blend=knockout", "stack { text \"1\" blend=knockout }",
            "stack layer=#false { text \"1\" blend=knockout }", "text \"1\" layer=#true blend=knockout"))
            assertEquals(listOf("knockout-needs-layer"), codes(body))
    }
    @Test fun enabledOuterLayerSurvivesAnIntermediateFalseLayer() {
        assertTrue(codes("stack layer=#true { stack layer=#false { text \"1\" blend=knockout } }").isEmpty())
        assertTrue(codes("stack layer=#true { stack layer=#true { text \"1\" blend=knockout } }").isEmpty())
    }
    @Test fun fixedSafetyAttributesRefuseAllBindingForms() {
        for (binding in listOf("\"${'$'}enabled\"", "\"@enabled\"", "\"{#true}\""))
            assertTrue(codes("stack layer=$binding { text \"1\" }").contains("not-bindable"))
        assertTrue(codes("stack layer=#true { text \"1\" blend=\"{'knockout'}\" }").contains("not-bindable"))
    }
    @Test fun fallbackAndVariantsHaveTheirOwnLayerProof() {
        assertTrue(codes("stack layer=#true", "fallback { text \"1\" blend=knockout }").contains("knockout-needs-layer"))
        assertTrue(codes("stack; variant \"small\" { text \"1\" blend=knockout }").contains("knockout-needs-layer"))
        assertTrue(codes("stack; variant \"small\" { stack layer=#true { text \"1\" blend=knockout } }").isEmpty())
    }
    @Test fun schemaAcceptsRevealAndCentreLineStack() {
        assertTrue(codes("stack align=centre-line layer=#true { box reveal=0.5 fill=\"#FFFFFF\"; text \"1\" blend=knockout }").isEmpty())
        assertEquals(listOf("bad-call"), codes("box reveal=\"{clamp(1, 2, 0)}\""))
    }
    @Test fun sourcesInsideFunctionArgumentsStillRequireUses() {
        assertEquals(listOf("source-not-in-uses"), codes("box fill=\"{opaque(network.color)}\""))
    }

}
