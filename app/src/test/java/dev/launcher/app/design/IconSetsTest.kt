package dev.launcher.app.design

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Production metadata/list/cache/file selection, using the app's existing real org.json test dependency.
 * Private I/O seams avoid Android Context/AssetManager and AppLog stubs; glyph parsing is emulator coverage.
 */
class IconSetsTest {
    @get:Rule val temp = TemporaryFolder()
    @Before fun resetBefore() = IconSets.clear()
    @After fun resetAfter() = IconSets.clear()

    private fun invoke(name: String, types: Array<Class<*>>, vararg args: Any): Any? =
        IconSets::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(IconSets, *args)

    private fun decode(id: String, text: String) = invoke("decode", arrayOf(String::class.java, String::class.java), id, text) as IconSets.IconSet
    private fun get(id: String, load: (String) -> IconSets.IconSet?) =
        invoke("get", arrayOf(String::class.java, Function1::class.java), id, load) as IconSets.IconSet?
    @Suppress("UNCHECKED_CAST")
    private fun listed(ids: List<String>, load: (String) -> IconSets.IconSet?) =
        invoke("readList", arrayOf(List::class.java, Function1::class.java), ids, load) as List<Pair<String, String>>
    private fun readText(file: File, asset: () -> String) =
        invoke("readText", arrayOf(File::class.java, Function0::class.java), file, asset) as String
    private fun json(flag: Boolean? = null, name: String = "Test") = JSONObject().put("name", name)
        .put("icons", JSONObject()).apply { if (flag != null) put("listed", flag) }.toString()

    @Test fun omittedListingDefaultsToTrue() {
        assertTrue(decode("old", json()).listed)
        assertTrue(IconSets.IconSet("old", "Old", JSONObject()).listed)
    }

    @Test fun explicitTrueAndFalseAreRead() {
        assertTrue(decode("shown", json(true)).listed)
        assertFalse(decode("hidden", json(false)).listed)
    }

    @Test fun generatedSetMetadataUsesProductionDecoder() {
        val set = decode("statusbar", File("../engine/src/main/assets/icons/statusbar.json").readText())
        assertFalse(set.listed)
        assertEquals(12, set.names.size)
        assertTrue(set.names.containsAll(listOf("moon", "bolt", "airplane", "wifi.3", "cell.4")))
    }

    @Test fun listFiltersHiddenButGetAndPeekStillFindIt() {
        val sets = mapOf("old" to decode("old", json()), "shown" to decode("shown", json(true)),
            "hidden" to decode("hidden", json(false)))
        val rows = listed(listOf("old", "shown", "hidden", "old")) { id -> get(id) { sets[it] } }
        assertEquals(listOf("launcher" to "Launcher", "old" to "Test", "shown" to "Test"), rows)
        assertSame(sets["hidden"], get("hidden") { error("hidden set must stay cached") })
        assertSame(sets["hidden"], IconSets.peek("hidden"))
    }

    @Test fun fileOverridesAssetIncludingListingInBothDirections() {
        for (fileListed in listOf(false, true)) {
            IconSets.clear()
            val file = File(temp.root, "override.json").apply { writeText(json(fileListed, "File")) }
            val asset = json(!fileListed, "Asset")
            val rows = listed(listOf("override", "override")) { id ->
                get(id) { decode(it, readText(file) { error("asset must not be opened: $asset") }) }
            }
            assertEquals("File", IconSets.peek("override")!!.name)
            assertEquals(fileListed, rows.any { it.first == "override" })
        }
    }

    @Test fun absentFileUsesAssetMetadata() {
        val text = readText(File(temp.root, "absent.json")) { json(false, "Asset") }
        val set = decode("asset", text)
        assertEquals("Asset", set.name)
        assertFalse(set.listed)
    }
}
