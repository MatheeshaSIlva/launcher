package dev.launcher.engine.model

import dev.launcher.engine.kdl.KdlParser
import dev.launcher.engine.model.ModelTexts.component
import dev.launcher.engine.model.ModelTexts.only
import dev.launcher.engine.model.ModelTexts.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** E2 slice 3b: the validator against the primitives' schemas; every code with its place and hint. */
class ValidatorTest {
    @Test fun repeatsCheckOuterInputAndInnerArgumentsAndRefuseAmbiguousAliases() {
        assertEquals(emptyList<Diagnostic>(), findings(view("""
            row { text "{n.title}" each="{battery.icons}" as=n { }
            }
        """)))
        for ((body, code) in listOf(
            """row each="{battery.icons}" as=n""" to "root-each",
            """row { text "x" as=n }""" to "each-alias",
            """row { text "x" each="{battery.icons}" }""" to "each-alias",
            """row { text "x" each="{battery.icons}" as="bad.name" }""" to "invalid-alias",
            """row { text "x" each="{battery.icons}" as=battery }""" to "invalid-alias",
            """row { text "x" each="{battery.icons}" as=level }""" to "invalid-alias",
            """row { text "{n.title}" each="{n.items}" as=n }""" to "source-not-in-uses",
            """row { row each="{battery.icons}" as=n { text "x" each="{n.items}" as=n } }""" to "invalid-alias"
        )) assertTrue("$body needs $code", findings(view(body)).any { it.code == code })
    }
    private val tokens = TokenCheck { it in setOf("ink", "signal", "comp.statusbar.time-size", "comp.statusbar.label") }
    private fun findings(kdl: String) = Validator(tokens).check(component(kdl))

    private fun view(body: String, head: String = "") = """
        component "c" {
          props { size type=dp default=12 }
          state { level 50; on #true }
          uses { source battery }
          $head
          view {
            $body
          }
        }"""

    @Test fun theSpecsBatteryRingNeedsOnlyARingPrimitive() {
        // The spec's example since D-081 (one expression syntax): ring is not one of E2's six primitives yet.
        assertEquals(listOf("unknown-primitive"), findings(ModelTexts.BATTERY_RING).map { it.code })
    }

    @Test fun theBatteryRingWithoutBracesIsRefused() {
        // The example before D-081: "^level%" and "@thickness*3" are expressions written without braces.
        assertEquals(listOf("unknown-primitive", "expression-syntax", "expression-syntax"),
            findings(ModelTexts.BATTERY_RING_UNBRACED).map { it.code })
    }

    @Test fun aComponentWrittenAsTheBarWillBeHasNoFindings() {
        val kdl = """
            component "status-left" version="1.0.0" {
              props { inset type=dp default=16; show-percent type=bool default=#true }
              uses { source time; source battery; token comp.statusbar.time-size }
              state { seconds #false }
              view {
                row gap=(pt)6 align=centre-line padding-start="@inset" {
                  text "{time.now | date:'H:mm'}" size="@comp.statusbar.time-size" weight=600 tnum=#true roll=#true
                  spacer flex=1
                  stack {
                    box width="{battery.level * 0.27}" height=(pt)11 radius=(pt)3 fill="#FFFFFFFF"
                    text "{battery.level}%" visible="@show-percent" color="@comp.statusbar.label"
                  }
                  icon bolt visible="{battery.charging && !^seconds}"
                }
                variant "compact" { row gap=(pt)4 }
              }
              fallback { text "--" }
            }"""
        val c = component(kdl)
        assertEquals(listOf("compact"), c.variants.map { it.name })
        assertEquals(AttrValue.Expr("{battery.level * 0.27}", c.view.children[2].children[0].attrs.getValue("width").value.span),
            c.view.children[2].children[0].attrs.getValue("width").value)
        assertEquals(emptyList<Diagnostic>(), Validator(tokens).check(c))
        assertTrue(Validator(tokens).load(KdlParser.parse(text(kdl))) is ReadResult.Ok)
    }

    @Test fun unknownPrimitiveAndAttribute() {
        val p = view("rwo { text x }")
        only(findings(p), "unknown-primitive", p, "rwo", "did you mean 'row'?")
        val a = view("text x colr=\"#FFF\"")
        only(findings(a), "unknown-attribute", a, "colr", "did you mean 'color'?")
        val far = view("text x wobble=1")
        only(findings(far), "unknown-attribute", far, "wobble")
    }

    @Test fun wrongTypes() {
        val weight = view("text x weight=1.5")
        assertEquals("'weight' takes a whole number, found 1.5",
            only(findings(weight), "wrong-type", weight, "1.5", "for example weight=400").message)
        only(findings(view("row gap=\"wide\"")), "wrong-type", view("row gap=\"wide\""), "\"wide\"")
        only(findings(view("row gap=(em)2")), "wrong-type", view("row gap=(em)2"), "2", n = 1)
        only(findings(view("text x color=red")), "wrong-type", view("text x color=red"), "red")
        only(findings(view("row align=middle")), "wrong-type", view("row align=middle"), "middle", "for example align=center")
        only(findings(view("text x visible=1")), "wrong-type", view("text x visible=1"), "1", "for example visible=#true", n = 1)
        assertEquals(emptyList<Diagnostic>(), findings(view("box width=(dp)4 height=(px)2 fill=\"#80FFFFFF\" stroke-color=\"#abc\"")))
    }

    @Test fun bindings() {
        val state = view("text \"^levle\"")
        only(findings(state), "unknown-state", state, "\"^levle\"", "did you mean 'level'?")
        val inExpr = view("text \"{^levl}%\"")
        only(findings(inExpr), "unknown-state", inExpr, "^levl", "did you mean 'level'?")
        val token = view("text x color=\"@siez\"")
        only(findings(token), "unknown-token", token, "\"@siez\"", "did you mean '@size'?")
        assertEquals(emptyList<Diagnostic>(), findings(view("text x color=\"@ink\" size=\"@size\" visible=\"^on\"")))
        val fixed = view("text x transition=\"^level\"")
        only(findings(fixed), "not-bindable", fixed, "\"^level\"")
        val source = view("text \"{network.wifi.level}\"")
        only(findings(source), "source-not-in-uses", source, "network", "uses { source network }")
        val units = view("box width=\"{4pt + 2dp}\"")
        assertEquals("Mixed units: pt + dp", only(findings(units), "unit-mismatch", units, "+", "write both in one unit").message)
        val bad = view("text \"{battery.level | percnt}\"")
        assertEquals("Unknown formatter 'percnt'", only(findings(bad), "bad-expression", bad, "percnt", "did you mean 'percent'?").message)
        val escaped = view("text \"\\\"{1 +}\"")   // an escape before it: the finding points at the whole value
        only(findings(escaped), "bad-expression", escaped, "\"\\\"{1 +}\"")
        assertEquals(emptyList<Diagnostic>(), findings(view("text \"{battery.level | percent} of {'a.b'}\"")))
        val loop = view("row { text \"{n.title}\" each=\"^level\" as=n }")
        assertEquals(emptyList<Diagnostic>(), findings(loop))
        val old = view("text \"^level%\"")
        only(findings(old), "expression-syntax", old, "\"^level%\"",
            "a reference alone is \$name or @name; anything more goes in braces: \"{\$level}%\", \"{@size * 3}\"")
        val each = view("row { text each=3 as=n }")
        only(findings(each), "wrong-type", each, "3")
    }

    @Test fun tokensAndProps() {
        val shadow = "component \"c\" {\n  props { ink type=color }\n  view { text x }\n}"
        only(findings(shadow), "prop-shadows-token", shadow, "ink", "rename the prop")
        val declared = "component \"c\" {\n  props { label type=string }\n  uses { token label }\n  view { text x }\n}"
        assertEquals(listOf("prop-shadows-token", "unknown-token"), findings(declared).map { it.code })
        val missing = "component \"c\" {\n  uses { token comp.nope }\n  view { text x }\n}"
        only(findings(missing), "unknown-token", missing, "comp.nope")
    }

    @Test fun placesAndArguments() {
        val leaf = view("text x { icon y }")
        assertEquals("'text' cannot hold children: put 'icon' beside it in a row or a stack",
            only(findings(leaf), "misplaced", leaf, "icon").message)
        val spacer = view("stack { spacer }")
        assertEquals("'spacer' goes only in row or column, not in stack", only(findings(spacer), "misplaced", spacer, "spacer").message)
        only(findings(view("spacer")), "misplaced", view("spacer"), "spacer")
        val args = view("row 3")
        only(findings(args), "unexpected-argument", args, "3")
        val two = view("text first second")
        only(findings(two), "unexpected-argument", two, "second").let { assertEquals("'text' takes one argument, its content", it.message) }
        assertTrue(findings(view("text \"{^level}\"", head = "")).isEmpty())
    }
}
