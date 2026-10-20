package dev.launcher.app

import dev.launcher.app.layout.Element
import dev.launcher.app.layout.LayoutFile
import dev.launcher.app.layout.LayoutFileError
import dev.launcher.app.layout.Layouts
import dev.launcher.app.layout.Option
import dev.launcher.app.layout.ShadeStructure
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Layout files (docs/LAYOUT_FILES.md): what ships loads, the iOS shade is today's shade, and the reader is strict. */
class LayoutFilesTest {
    private val dir = File("src/main/assets/layouts")
    // (A Windows checkout has CRLF line endings: the edits below look for "\n".)
    private val iosShade get() = File(dir, "shade/ios.json").readText().replace("\r\n", "\n")

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
        // Notifications on the left (iOS's, the first: the default) or Control Center there.
        assertEquals(listOf("split-top", "split-top-mirrored"), s.placements.map { it.id })
        assertEquals(listOf("Notifications on the left", "Control Center on the left"), s.placements.map { it.title })
        assertEquals("Pull down left of the camera for notifications, right of it for Control Center.", s.placements[0].description)
        val o = s.options.single() as Option.Count
        assertEquals(listOf<Any>("cc-columns", "Control columns", "Controls across Control Center.", 4, 4, 6),
            listOf<Any>(o.key, o.title, o.description, o.default, o.min, o.max))
    }

    /** The structure is the shade as its panels draw it today. */
    @Test fun theIosShadeIsTodaysShade() {
        val st = LayoutFile.parse(iosShade).shade!!
        assertEquals(ShadeStructure.Kind.NOTIFICATION_CENTER, st.pulledFrom("split-top", ShadeStructure.Pull.LEFT_OF_CAMERA))
        assertEquals(ShadeStructure.Kind.CONTROL_CENTER, st.pulledFrom("split-top", ShadeStructure.Pull.RIGHT_OF_CAMERA))
        assertEquals(ShadeStructure.Kind.CONTROL_CENTER, st.pulledFrom("split-top-mirrored", ShadeStructure.Pull.LEFT_OF_CAMERA))
        assertEquals(ShadeStructure.Kind.NOTIFICATION_CENTER, st.pulledFrom("split-top-mirrored", ShadeStructure.Pull.RIGHT_OF_CAMERA))
        assertEquals(listOf(ShadeStructure.Kind.NOTIFICATION_CENTER, ShadeStructure.Kind.CONTROL_CENTER), st.panels.map { it.kind })
        assertEquals(ShadeStructure.Opens.STACKED, st.opens)
        assertTrue(st.ncClockBesideList)
        assertEquals("cc-columns", st.controlGrid!!.columns.option?.key)
        assertNull(st.controlGrid!!.columns.fixed)
        assertEquals("in-place", st.controlGrid!!.editor)
        assertEquals("flow", st.controlCenter!!.landscape)
        assertTrue(st.ncHasClock && st.ncHasButtons && st.ccHasButtons && st.ccHasStatusRow)
    }

    @Test fun theChoicesTheShadeHasNowAreRead() {
        val st = LayoutFile.parse(iosShade
            .replace("\"opens\": \"stacked\"", "\"opens\": \"list\"")
            .replace("\"landscape\": \"clock-beside-list\"", "\"landscape\": \"as-portrait\"")
            .replace("{\"option\": \"cc-columns\"}", "5")).shade!!
        assertEquals(ShadeStructure.Opens.LIST, st.opens)
        assertTrue(!st.ncClockBesideList)
        assertEquals(5, st.controlGrid!!.columns.fixed)
        assertEquals(5, st.controlGrid!!.columns.value)
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
        refused(ok.replace("{\"block\": \"lock-clock\"},", ""), "the clock left out, landscape still beside it")
        refused(ok.replace("{\"block\": \"notifications\", \"opens\": \"stacked\"},", ""), "Notification Center without its notifications")
        refused(ok.replace("{\"block\": \"lock-clock\"},\n     {\"block\": \"notifications\", \"opens\": \"stacked\"},",
            "{\"block\": \"notifications\", \"opens\": \"stacked\"},\n     {\"block\": \"lock-clock\"},"), "blocks out of their order")
        refused(ok.replace("{\"block\": \"corner-buttons\"}", "{\"block\": \"corner-buttons\"}, {\"block\": \"corner-buttons\"}"), "a block twice")
        refused(ok.replace("{\"block\": \"status-row\"},", ""), "the status row left out, + and power still in the bar's band")
        refused(ok.replace("{\"block\": \"status-row\"},\n     {\"block\": \"control-grid\", \"columns\": {\"option\": \"cc-columns\"}, \"editor\": \"in-place\"}",
            "{\"block\": \"status-row\"}"), "Control Center without its controls")
        refused(ok.replace("{\"block\": \"top-buttons\"},\n     {\"block\": \"status-row\"},", "{\"block\": \"status-row\"},\n     {\"block\": \"top-buttons\"},"),
            "Control Center's blocks out of their order")
        refused(ok.replace("{\"block\": \"corner-buttons\"}", "{\"block\": \"top-buttons\"}"), "a block of Control Center's in Notification Center")
        refused(ok.replace("\"from\": \"left-of-camera\"", "\"from\": \"right-of-camera\""), "both panels pulled from one side")
        refused(ok.replace("{\"from\": \"right-of-camera\", \"panel\": \"control-center\"}", "{\"from\": \"right-of-camera\", \"panel\": \"notification-center\"}"),
            "one panel from both sides, the other from none")
        refused(ok.replace("\"from\": \"left-of-camera\"", "\"from\": \"top\""), "two panels, one of them from the whole edge")
        refused(ok.replace("\"from\": \"left-of-camera\"", "\"from\": \"left\""), "a part of the edge there is not")
        refused(ok.replace(",\n    {\"from\": \"right-of-camera\", \"panel\": \"control-center\"}", ""), "a panel no place pulls")
        refused(ok.replace("\"panel\": \"control-center\"}", "\"panel\": \"control-centre\"}"), "a pull naming no panel of the structure")
        refused(ok.replace("\"panel\": \"notification-center\",\n", "\"panel\": \"notification-center\",\n    \"pull\": \"left-of-camera\",\n"),
            "a panel's own pull (the place says it)")
        refused(ok.replace(",\n   \"pulls\": [\n    {\"from\": \"left-of-camera\", \"panel\": \"notification-center\"},\n    {\"from\": \"right-of-camera\", \"panel\": \"control-center\"}\n   ]", ""),
            "a place that says nothing of the pulls")
        refused(ok.replace("{\"option\": \"cc-columns\"}", "{\"option\": \"rows\"}"), "a count bound to no option")
        refused(ok.replace("{\"option\": \"cc-columns\"}", "4.5"), "a count that is not whole")
        refused(ok.replace("\"editor\": \"in-place\"", "\"editor\": \"page\""), "an editor the panel does not have")
        refused(ok.replace("\"landscape\": \"flow\"", "\"landscape\": \"as-portrait\""), "a landscape Control Center does not have")
        refused(ok.replace("\"default\": 4", "\"default\": 9"), "a default out of its range")
        refused(ok.replace("\"kind\": \"count\"", "\"kind\": \"slider\""), "an option of no kind")
        refused(ok.replace("\"element\": \"shade\"", "\"element\": \"drawer\""), "a structure for an element that has none as data")
    }

    /** Notification Center's clock and buttons are the file's to leave out; the list takes their room. */
    @Test fun notificationCenterWithoutItsClockOrButtons() {
        val bare = LayoutFile.parse(iosShade
            .replace("{\"block\": \"lock-clock\"},", "")
            .replace(",\n     {\"block\": \"corner-buttons\"}", "")
            .replace("\"landscape\": \"clock-beside-list\"", "\"landscape\": \"as-portrait\"")).shade!!
        assertTrue(!bare.ncHasClock && !bare.ncHasButtons)
        assertEquals(listOf("notifications"), bare.notificationCenter.blocks.map { it.id })
        val ios = LayoutFile.parse(iosShade).shade!!
        assertTrue(ios.ncHasClock && ios.ncHasButtons)
    }

    /** Control Center's "+" and power and its status row are the file's to leave out (the buttons only with the row). */
    @Test fun controlCenterWithoutItsButtonsOrStatusRow() {
        val noButtons = LayoutFile.parse(iosShade.replace("{\"block\": \"top-buttons\"},", "")).shade!!
        assertTrue(!noButtons.ccHasButtons && noButtons.ccHasStatusRow)
        val bare = LayoutFile.parse(iosShade.replace("{\"block\": \"top-buttons\"},", "").replace("{\"block\": \"status-row\"},", "")).shade!!
        assertTrue(!bare.ccHasButtons && !bare.ccHasStatusRow)
        assertEquals(listOf("control-grid"), bare.controlCenter!!.blocks.map { it.id })
    }

    /** Where each panel is pulled from is the place's: the sides either way round, and as many places as the file names. */
    @Test fun eachPlaceSaysWhereEachPanelIsPulledFrom() {
        val st = LayoutFile.parse(iosShade).shade!!
        // A place the layout does not have is its first.
        assertEquals(ShadeStructure.Kind.NOTIFICATION_CENTER, st.pulledFrom("nowhere", ShadeStructure.Pull.LEFT_OF_CAMERA))
        // (The settings' stage plays the strokes in the file's order.)
        assertEquals(listOf(ShadeStructure.Pull.LEFT_OF_CAMERA, ShadeStructure.Pull.RIGHT_OF_CAMERA), st.pullsOf("split-top-mirrored").map { it.from })
        val reordered = LayoutFile.parse(variant { places(it)[1].put("pulls", pulls("right-of-camera" to "notification-center", "left-of-camera" to "control-center")) }).shade!!
        assertEquals(listOf(ShadeStructure.Pull.RIGHT_OF_CAMERA, ShadeStructure.Pull.LEFT_OF_CAMERA), reordered.pullsOf("split-top-mirrored").map { it.from })
        assertEquals(ShadeStructure.Kind.CONTROL_CENTER, reordered.pulledFrom("split-top-mirrored", ShadeStructure.Pull.LEFT_OF_CAMERA))
    }

    /** A layout's places may use different panels (together, or separately: the user's choice, as One UI's Quick panel). */
    @Test fun placesMayUseDifferentPanels() {
        val st = LayoutFile.parse(variant { places(it)[1].put("pulls", pulls("top" to "notification-center")) }).shade!!
        assertTrue(st.uses("split-top", ShadeStructure.Kind.CONTROL_CENTER))
        assertTrue(!st.uses("split-top-mirrored", ShadeStructure.Kind.CONTROL_CENTER))
        for (side in listOf(ShadeStructure.Pull.LEFT_OF_CAMERA, ShadeStructure.Pull.RIGHT_OF_CAMERA))
            assertEquals(ShadeStructure.Kind.NOTIFICATION_CENTER, st.pulledFrom("split-top-mirrored", side))
        // Every panel is opened in some place.
        refused(variant { for (p in places(it)) p.put("pulls", pulls("top" to "notification-center")) }, "a panel no place opens")
    }

    /** One panel, pulled from anywhere along the top edge: the layout has no Control Center. */
    @Test fun onePanelFromTheWholeEdge() {
        val one = LayoutFile.parse(onePanel()).shade!!
        assertEquals(listOf(ShadeStructure.Kind.NOTIFICATION_CENTER), one.panels.map { it.kind })
        for (side in listOf(ShadeStructure.Pull.LEFT_OF_CAMERA, ShadeStructure.Pull.RIGHT_OF_CAMERA))
            assertEquals(ShadeStructure.Kind.NOTIFICATION_CENTER, one.pulledFrom("top", side))
        assertNull(one.controlCenter)
        assertNull(one.controlGrid)
        assertTrue(!one.uses("top", ShadeStructure.Kind.CONTROL_CENTER) && !one.ccHasButtons && !one.ccHasStatusRow)
        refused(onePanel().replace("\"from\": \"top\"", "\"from\": \"left-of-camera\""), "one panel from a side only")
        refused(onePanel { places(it)[0].put("pulls", pulls("top" to "notification-center", "top" to "notification-center")) }, "the edge twice")
        // The notifications need a panel in every place; a panel once.
        refused(variant { places(it)[1].put("pulls", pulls("top" to "control-center")) }, "a place with Control Center alone: no panel for the notifications")
        refused(variant { panels(it).put(JSONObject(panels(it).getJSONObject(0).toString())) }, "Notification Center twice")
    }

    /** Only the shade's places say where its panels are pulled from. */
    @Test fun pullsBelongToTheShadesPlaces() {
        val drawer = """{"format": 1, "element": "drawer", "id": "mine", "title": "Mine", "origin": "Me", "description": "A drawer.",
            "placements": [{"id": "swipe-up", "title": "Swipe up", "description": "Up from anywhere."}]}"""
        assertEquals("swipe-up", LayoutFile.parse(drawer).spec.defaultPlacement.id)
        refused(drawer.replace("\"Up from anywhere.\"}", "\"Up from anywhere.\", \"pulls\": []}"), "pulls on a drawer's place")
    }

    /** The iOS file changed by [edit] (on its JSON). */
    private fun variant(edit: (JSONObject) -> Unit): String = JSONObject(iosShade).also(edit).toString(1)

    private fun places(o: JSONObject): List<JSONObject> = o.getJSONArray("placements").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private fun panels(o: JSONObject): JSONArray = o.getJSONObject("structure").getJSONArray("panels")

    /** A place's pulls: each part of the edge and the panel it opens. */
    private fun pulls(vararg p: Pair<String, String>): JSONArray =
        JSONArray().also { a -> for ((from, panel) in p) a.put(JSONObject().put("from", from).put("panel", panel)) }

    /** The iOS file with only Notification Center, pulled from anywhere on the top edge (one place: "top"), then [edit]. */
    private fun onePanel(edit: (JSONObject) -> Unit = {}): String = variant { o ->
        o.put("placements", JSONArray().put(JSONObject().put("id", "top").put("title", "From the top").put("description", "Anywhere along the top.")
            .put("pulls", pulls("top" to "notification-center"))))
        val ps = panels(o)
        for (i in ps.length() - 1 downTo 0) if (ps.getJSONObject(i).getString("panel") == "control-center") ps.remove(i)
        edit(o)
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
