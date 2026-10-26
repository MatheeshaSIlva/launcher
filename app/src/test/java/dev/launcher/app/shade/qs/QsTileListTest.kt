package dev.launcher.app.shade.qs

import dev.launcher.app.shade.qs.QsTileList.Entry
import dev.launcher.app.shade.qs.QsTileList.Parsed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** MF slice 2: the text form of ours' tile list over adb (`reset` | `KEY[:w],...`). */
class QsTileListTest {
    /** The list [QsTileList.parse] made of [text] (it must be a list). */
    private fun tiles(text: String): List<Entry> = (QsTileList.parse(text) as Parsed.Tiles).list
    private val comp = "com.example.app/com.example.app.tiles.Foo\$Bar.Tile"

    @Test fun resetParsesToResetAndFormatsBack() {
        assertEquals(Parsed.Reset, QsTileList.parse("reset"))
        assertEquals(Parsed.Reset, QsTileList.parse("  reset \n"))
        assertEquals("reset", QsTileList.format(null))
    }

    @Test fun aListRoundTripsWithWideAndAppTiles() {
        val text = "WIFI:w,BLUETOOTH:w,CELLULAR:w,FOCUS:w,AIRPLANE,ROTATION_LOCK,FLASHLIGHT,app:$comp:w,app:$comp.Two"
        val list = tiles(text)
        assertEquals(9, list.size)
        assertEquals(Entry("WIFI", true), list[0])
        assertEquals(Entry("AIRPLANE", false), list[4])
        assertEquals(Entry("app:$comp", true), list[7])
        assertEquals(comp, list[7].app)
        assertEquals(Entry("app:$comp.Two", false), list[8])
        assertEquals(text, QsTileList.format(list))
        assertEquals(list, tiles(QsTileList.format(list)))
    }

    @Test fun anAppComponentKeepsItsSlashDollarAndDots() {
        val e = tiles("app:$comp").single()
        assertEquals("app:$comp", e.key)
        assertEquals(false, e.wide)
        assertEquals(dev.launcher.app.shade.Control.APP_TILE, e.control)
    }

    @Test fun unknownAndEmptyEntriesAreSkipped() {
        val list = tiles("WIFI,,NOT_A_CONTROL, ,app:,APP_TILE,BLUETOOTH:w,wifi")
        assertEquals(listOf(Entry("WIFI", false), Entry("BLUETOOTH", true)), list)
        assertEquals("WIFI,BLUETOOTH:w", QsTileList.format(list))
    }

    @Test fun whitespaceAroundEntriesIsTolerated() {
        val list = tiles("  WIFI:w ,\tBLUETOOTH , app:$comp:w \n")
        assertEquals(listOf(Entry("WIFI", true), Entry("BLUETOOTH", false), Entry("app:$comp", true)), list)
    }

    @Test fun aTileNamedTwiceKeepsItsFirstPlace() {
        assertEquals(listOf(Entry("WIFI", true), Entry("BLUETOOTH", false)), tiles("WIFI:w,BLUETOOTH,WIFI"))
    }

    @Test fun anEmptyTextIsAnEmptyListNotReset() {
        val list = tiles("")
        assertTrue(list.isEmpty())
        assertEquals("", QsTileList.format(list))
        assertEquals(Parsed.Tiles(emptyList()), QsTileList.parse("  \n"))
    }

    @Test fun aTextWithNoValidEntryIsNothingValidNotAnEmptyList() {
        for (typo in listOf("WIFY,BLUETOOTH2", "wifi", "app:", ",,", "NOT_A_CONTROL, APP_TILE")) {
            assertEquals(typo, Parsed.NothingValid, QsTileList.parse(typo))
        }
        assertEquals(listOf(Entry("WIFI", false)), tiles("WIFY,WIFI"))
    }

    @Test fun theStoredJsonReadsAsTheStoreReadsIt() {
        val json = """[{"c":"WIFI","w":true},{"c":"BOGUS","w":true},{"c":"APP_TILE","w":false,"t":"$comp"},{"c":"APP_TILE","w":true},
            |{"c":"WIFI","w":false},{"c":"FLASHLIGHT"}]""".trimMargin()
        val list = QsTileList.fromStored(json)
        assertEquals(listOf(Entry("WIFI", true), Entry("app:$comp", false), Entry("FLASHLIGHT", false)), list)
        assertEquals(list, tiles(QsTileList.format(list)))
    }
}
