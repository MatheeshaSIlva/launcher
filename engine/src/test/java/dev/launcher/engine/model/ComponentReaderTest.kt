package dev.launcher.engine.model

import dev.launcher.engine.kdl.KdlValue
import dev.launcher.engine.model.ModelTexts.component
import dev.launcher.engine.model.ModelTexts.only
import dev.launcher.engine.model.ModelTexts.readFindings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** E2 slice 3a: the component model and its reader; every finding of the file's shape with its place and hint. */
class ComponentReaderTest {
    @Test fun theSpecsBatteryRingAsStep0CorrectedItReadsIntoAComponentDef() {
        val kdl = ModelTexts.BATTERY_RING
        val c = component(kdl)
        assertEquals("battery-ring", c.name)
        assertEquals("1.2.0", c.version)
        assertEquals(listOf("size", "thickness", "warn"), c.props.map { it.name })
        assertEquals(listOf(AttrType.LENGTH, AttrType.LENGTH, AttrType.INT), c.props.map { it.type })
        assertEquals(listOf("48", "4", "20"), c.props.map { (it.default as KdlValue.Num).raw })
        assertEquals(listOf("battery"), c.uses.sources.map { it.name })
        assertEquals(listOf("level", "charging", "color"), c.state.map { it.name })
        assertEquals("ring", c.view.primitive)
        assertEquals(AttrValue.Ref("size", c.view.attrs.getValue("size").value.span), c.view.attrs.getValue("size").value)
        assertEquals(AttrValue.State("level", c.view.attrs.getValue("value").value.span), c.view.attrs.getValue("value").value)
        assertEquals(listOf("text", "icon"), c.view.children.map { it.primitive })
        assertTrue(c.script!!.startsWith("on(\"battery\""))
    }

    @Test fun notAComponent() {
        assertEquals("not-a-component", readFindings("// nothing").single().code)
        only(readFindings("component \"a\" { view { text x } }\nextra"), "not-a-component", "component \"a\" { view { text x } }\nextra", "extra")
        only(readFindings("compnent \"a\""), "not-a-component", "compnent \"a\"", "compnent", "did you mean 'component'?")
        only(readFindings("component { view { text x } }"), "not-a-component", "component { view { text x } }", "component")
    }

    @Test fun sections() {
        val unknown = "component \"c\" {\n  veiw { text x }\n  view { text x }\n}"
        only(readFindings(unknown), "unknown-section", unknown, "veiw", "did you mean 'view'?")
        val twice = "component \"c\" {\n  view { text x }\n  view { text y }\n}"
        only(readFindings(twice), "duplicate-section", twice, "view", n = 1)
        val none = "component \"c\" { props { } }"
        only(readFindings(none), "missing-view", none, "component")
        val two = "component \"c\" { view { text x; text y } }"
        assertEquals("'view' holds one primitive (put several in a row or a stack): found 2",
            only(readFindings(two), "view-root", two, "view").message)
        val fallback = "component \"c\" { view { text x }; fallback { } }"
        only(readFindings(fallback), "view-root", fallback, "fallback")
    }

    @Test fun usesStateAndProps() {
        val use = "component \"c\" {\n  uses { sorce battery }\n  view { text x }\n}"
        only(readFindings(use), "unknown-use", use, "sorce", "did you mean 'source'?")
        val state = "component \"c\" {\n  state { level 1 2 }\n  view { text x }\n}"
        only(readFindings(state), "bad-state", state, "level")
        val untyped = "component \"c\" {\n  props { size default=4 }\n  view { text x }\n}"
        only(readFindings(untyped), "missing-type", untyped, "size")
        val badType = "component \"c\" {\n  props { size type=dpp }\n  view { text x }\n}"
        only(readFindings(badType), "unknown-type", badType, "dpp", "did you mean 'dp'?")
        val badDefault = "component \"c\" {\n  props { size type=int default=\"big\" }\n  view { text x }\n}"
        only(readFindings(badDefault), "wrong-type", badDefault, "\"big\"")
    }
}
