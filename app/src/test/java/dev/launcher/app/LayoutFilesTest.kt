package dev.launcher.app

import dev.launcher.app.layout.Element
import dev.launcher.app.layout.LayoutFile
import dev.launcher.app.layout.LayoutFileError
import dev.launcher.app.layout.Layouts
import dev.launcher.app.layout.Option
import dev.launcher.app.layout.ShadeStructure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Layout files (docs/LAYOUT_FILES.md): what ships loads, the iOS shade is today's shade, and the reader is strict. */
class LayoutFilesTest {
    private val dir = File("src/main/assets/layouts")
    private val iosShade get() = File(dir, "shade/ios.json").readText()

    @Test fun everyShippedLayoutFileLoads() {
        val files = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".json") }.toList()
        assertTrue("a layout file ships", files.isNotEmpty())
        for (f in files) {
            val l = LayoutFile.parse(f.readText())
            assertEquals("${f.path}: its folder is its element", f.parentFile.name, l.spec.element.id)
            assertEquals("${f.path}: its name is its id", f.name.removeSuffix(".json"), l.spec.id)
        }
        TestLayouts.load()
        assertEquals("ios", Layouts.default(Element.SHADE).id)
    }

    /** What the code declared before the file (Layouts.IOS_SHADE): the settings show the same layout. */
    @Test fun theIosShadeFileIsTheLayoutTheCodeDescribed() {
        val s = LayoutFile.parse(iosShade).spec
        assertEquals(Element.SHADE, s.element)
        assertEquals(listOf("ios", "iOS", "iOS 27", "Notification Center and Control Center as two panels."), listOf(s.id, s.title, s.origin, s.description))
        assertEquals(1, s.placements.size)
        val p = s.placements.single()
        assertEquals("split-top", p.id)
        assertEquals("Pull from the top: notifications on the left, controls on the right", p.title)
        assertEquals("Pull down from the top edge left of the camera for notifications, right of it for the controls.", p.description)
        val o = s.options.single() as Option.Count
        assertEquals(listOf<Any>("cc-columns", "Control columns", "Controls across Control Center.", 4, 4, 6),
            listOf<Any>(o.key, o.title, o.description, o.default, o.min, o.max))
    }

    /** The structure is the shade as its panels draw it today. */
    @Test fun theIosShadeIsTodaysShade() {
        val st = LayoutFile.parse(iosShade).shade!!
        assertEquals(ShadeStructure.Kind.NOTIFICATION_CENTER, st.pulledFrom(ShadeStructure.Pull.LEFT_OF_CAMERA))
        assertEquals(ShadeStructure.Kind.CONTROL_CENTER, st.pulledFrom(ShadeStructure.Pull.RIGHT_OF_CAMERA))
        assertEquals(ShadeStructure.Opens.STACKED, st.opens)
        assertTrue(st.ncClockBesideList)
        assertEquals("cc-columns", st.controlGrid.columns.option?.key)
        assertNull(st.controlGrid.columns.fixed)
        assertEquals("in-place", st.controlGrid.editor)
        assertEquals("flow", st.controlCenter.landscape)
    }

    @Test fun theChoicesTheShadeHasNowAreRead() {
        val st = LayoutFile.parse(iosShade
            .replace("\"opens\": \"stacked\"", "\"opens\": \"list\"")
            .replace("\"landscape\": \"clock-beside-list\"", "\"landscape\": \"as-portrait\"")
            .replace("{\"option\": \"cc-columns\"}", "5")).shade!!
        assertEquals(ShadeStructure.Opens.LIST, st.opens)
        assertTrue(!st.ncClockBesideList)
        assertEquals(5, st.controlGrid.columns.fixed)
        assertEquals(5, st.controlGrid.columns.value)
    }

    private fun refused(json: String, why: String) {
        try { LayoutFile.parse(json); fail("accepted: $why") } catch (e: LayoutFileError) {
            assertTrue("$why: the reason names it (${e.message})", e.message!!.isNotBlank())
        }
    }

    @Test fun mistakesAreRefusedWithTheirReason() {
        val ok = iosShade
        refused("{", "not JSON")
        refused(ok.replace("\"format\": 1", "\"format\": 2"), "another format")
        refused(ok.replace("\"element\": \"shade\"", "\"element\": \"toaster\""), "no such element")
        refused(ok.replace("\"id\": \"ios\"", "\"id\": \"iOS Shade\""), "an id with capitals and a space")
        refused(ok.replace("\"origin\": \"iOS 27\",", "\"origin\": \"iOS 27\", \"colour\": \"blue\","), "an unknown key")
        refused(ok.replace("\"opens\": \"stacked\"", "\"open\": \"stacked\""), "a misspelt parameter")
        refused(ok.replace("\"opens\": \"stacked\"", "\"opens\": \"sideways\""), "an unknown value")
        refused(ok.replace("{\"block\": \"lock-clock\"},", "{\"block\": \"weather\"},"), "an unknown block")
        refused(ok.replace("{\"block\": \"lock-clock\"},", ""), "a block left out (not yet the file's to choose)")
        refused(ok.replace("\"pull\": \"left-of-camera\"", "\"pull\": \"right-of-camera\""), "both panels pulled from one side")
        refused(ok.replace("{\"option\": \"cc-columns\"}", "{\"option\": \"rows\"}"), "a count bound to no option")
        refused(ok.replace("{\"option\": \"cc-columns\"}", "4.5"), "a count that is not whole")
        refused(ok.replace("\"editor\": \"in-place\"", "\"editor\": \"page\""), "an editor the panel does not have")
        refused(ok.replace("\"landscape\": \"flow\"", "\"landscape\": \"as-portrait\""), "a landscape Control Center does not have")
        refused(ok.replace("\"default\": 4", "\"default\": 9"), "a default out of its range")
        refused(ok.replace("\"kind\": \"count\"", "\"kind\": \"slider\""), "an option of no kind")
        refused(ok.replace("\"element\": \"shade\"", "\"element\": \"drawer\""), "a structure for an element that has none as data")
    }

    @Test fun yourOwnFileWinsIfItIsValid() {
        val path = "layouts/shade/ios.json"
        Layouts.load(TestLayouts.source(mapOf(path to iosShade.replace("\"opens\": \"stacked\"", "\"opens\": \"list\""))))
        assertEquals(ShadeStructure.Opens.LIST, Layouts.shadeOf("ios")!!.opens)
        // One that is not valid is left aside: the shipped one is used.
        Layouts.load(TestLayouts.source(mapOf(path to iosShade.replace("\"opens\": \"stacked\"", "\"opens\": \"sideways\""))))
        assertEquals(ShadeStructure.Opens.STACKED, Layouts.shadeOf("ios")!!.opens)
        // So is one that describes another layout.
        Layouts.load(TestLayouts.source(mapOf(path to iosShade.replace("\"id\": \"ios\"", "\"id\": \"mine\""))))
        assertEquals("ios", Layouts.default(Element.SHADE).id)
        TestLayouts.load()
    }
}
