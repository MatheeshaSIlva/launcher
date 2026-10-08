package dev.launcher.app

import dev.launcher.app.shade.AppTiles
import dev.launcher.app.shade.CcLayout
import dev.launcher.app.shade.Control
import dev.launcher.app.shade.ControlId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AppTilesTest {
    /** As One UI's SystemUI dumps its tiles (S24, Android 16): a label with a line break, a short component, repeats. */
    private val dump = """
    QSTileHost:
    WifiTile:    QSTile${'$'}State[spec=Wifi,icon=AnimationIcon[resId=0x7e081a45],label=Wi-Fi,secondaryLabel=Home,contentDescription=Wi-Fi,state=2,tileClassName=null]
        QSTile${'$'}State[spec=custom(com.samsung.android.lool/com.samsung.android.sm.battery.ui.mode.BatteryModeTile),icon=null,nextIcon=null,iconSupplier=com.android.systemui.qs.external.CustomTile${'$'}${'$'}ExternalSyntheticLambda3@9cfb619,label=Power
    saving,secondaryLabel=null,contentDescription=Power saving,stateDescription=null,disabledByPolicy=false,state=1,sideViewCustomDrawable=null,tileClassName=BatteryMode]
        QSTile${'$'}State[spec=custom(com.samsung.android.app.sharelive/.presentation.quickpanel.DeviceVisibilityTileService),icon=null,label=Quick Share,secondaryLabel=No one,contentDescription=Quick Share,stateDescription=null,state=2,tileClassName=ShareLive]
        QSTile${'$'}State[spec=custom(com.samsung.android.app.sharelive/.presentation.quickpanel.DeviceVisibilityTileService),icon=null,label=Stale,secondaryLabel=null,contentDescription=Quick Share,state=0]
    """.trimIndent()

    @Test fun readsSystemUisTileStates() {
        val m = AppTiles.parse(dump)
        assertEquals(2, m.size)
        val power = m["com.samsung.android.lool/com.samsung.android.sm.battery.ui.mode.BatteryModeTile"]
        assertNotNull(power)
        assertEquals("Power saving", power!!.label)   // the line break is a space
        assertNull(power.secondary)                   // "null" is none
        assertEquals(1, power.state)
        // A short component is named in full; the first entry counts.
        val share = m["com.samsung.android.app.sharelive/com.samsung.android.app.sharelive.presentation.quickpanel.DeviceVisibilityTileService"]
        assertEquals("No one", share!!.secondary)
        assertEquals(2, share.state)
    }

    @Test fun componentsInFull() {
        assertEquals("a.b/a.b.C", AppTiles.flat("a.b/.C"))
        assertEquals("a.b/x.y.C", AppTiles.flat("a.b/x.y.C"))
        assertNull(AppTiles.flat("nonsense"))
    }

    @Test fun tilesOnThePageAreSavedByTheirComponent() {
        val l = CcLayout(4, 6, mutableListOf())
        val a = ControlId(Control.APP_TILE, "p.q/p.q.TileA")
        val b = ControlId(Control.APP_TILE, "p.q/p.q.TileB")
        l.add(a); l.add(b); l.add(Control.FLASHLIGHT)
        val back = CcLayout.fromJson(l.toJson(), 4, 6)!!
        assertEquals(listOf(a, b, ControlId(Control.FLASHLIGHT)), back.items.map { it.id })
        // An app's tile saved without its component, and a second copy of one, are dropped.
        val odd = CcLayout.fromJson("""[{"c":"APP_TILE","x":0,"y":0,"w":1,"h":1},{"c":"APP_TILE","t":"p.q/p.q.TileA","x":1,"y":0},{"c":"APP_TILE","t":"p.q/p.q.TileA","x":2,"y":0}]""", 4, 6)!!
        assertEquals(listOf(a), odd.items.map { it.id })
    }
}
