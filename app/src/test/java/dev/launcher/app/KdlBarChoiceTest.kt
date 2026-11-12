package dev.launcher.app

import dev.launcher.app.statusbar.KdlBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** T-012: the developer switch shows only allowlisted components, each with a file that ships. */
class KdlBarChoiceTest {
    @Test fun noNameIsTheStatusBar() {
        assertEquals(KdlBar.STATUS_BAR, KdlBar.choose(null))
        assertEquals(KdlBar.STATUS_BAR, KdlBar.choose(""))
        assertEquals(KdlBar.STATUS_BAR, KdlBar.choose("  "))
    }

    @Test fun allowedNamesAreThemselves() {
        assertEquals(KdlBar.STATUS_BAR, KdlBar.choose("status-bar"))
        assertEquals(KdlBar.ISLAND, KdlBar.choose("island"))
    }

    @Test fun anythingElseIsRefused() {
        for (n in listOf("Island", "island ", "../island", "kdl/components/island.kdl", "shade", "status_bar")) assertNull(n, KdlBar.choose(n))
    }

    @Test fun everyAllowedComponentShips() {
        val assets = listOf("../engine", "engine").map { File(it, "src/main/assets") }.first { it.exists() }
        for ((name, file) in KdlBar.COMPONENTS) assertTrue("$name: $file", File(assets, file).isFile)
    }
}
