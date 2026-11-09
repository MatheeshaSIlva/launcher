package dev.launcher.app

import android.graphics.Canvas
import androidx.compose.runtime.snapshots.Snapshot
import dev.launcher.app.statusbar.BarFit
import dev.launcher.engine.expr.Env
import dev.launcher.engine.expr.Value
import dev.launcher.engine.kdl.KdlParser
import dev.launcher.engine.layout.Measured
import dev.launcher.engine.layout.Size
import dev.launcher.engine.layout.Units
import dev.launcher.engine.model.ComponentReader
import dev.launcher.engine.model.ReadResult
import dev.launcher.engine.ui.ComponentHost
import dev.launcher.engine.ui.HostNode
import dev.launcher.engine.ui.Painter
import java.io.File
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T-007, codex's round three: the shipped file with [BarFit] as the window drives it. Whatever changes together (a
 * notification, the time's width, the camera), no published state pairs new icons with an old count.
 */
class BarFitCoherenceTest {
    private var nowMs = 9L * 3_600_000 + 59 * 60_000
    private var icons = 0
    private var maxIcons = 3
    private var camera = 10_000f
    private var moon = false

    private val env = object : Env {
        override fun state(name: String) = Value.Null
        override fun prop(name: String) = when (name) { "max-icons" -> Value.Num(maxIcons.toDouble()); "hour24" -> Value.Bool.TRUE; else -> Value.Null }
        override fun token(key: String) = Value.Null
        override fun source(path: String): Value = when (path) {
            "notifications.icons" -> Value.List(List(icons) { Value.Record(mapOf("id" to Value.Str("a$it"), "rev" to Value.Str("1"))) })
            "dnd.on" -> Value.Bool.of(moon)
            else -> Value.Null
        }
        override fun now() = nowMs
        override val locale: Locale = Locale.US
        override val zone: TimeZone = TimeZone.getTimeZone("UTC")
    }
    /** Ten units a character: "9:59" is 40 wide, "10:00" 50. */
    private val painter = object : Painter {
        override fun key(node: HostNode) = node.value("content")
        override fun paint(canvas: Canvas, node: HostNode, width: Int, height: Int) {}
        override fun measure(node: HostNode, maxWidth: Int, maxHeight: Int): Measured? =
            if (node.view.primitive == "text") (node.value("content") as? Value.Str)?.value.orEmpty().length.let { Measured(Size(it * 10, 10), 5f, it * 10f, 10f) } else null
    }
    private val file = listOf("../engine", "engine").map { File(it, "src/main/assets/kdl/components/status-bar.kdl") }.first { it.exists() }
    private val host = ComponentHost((ComponentReader.read(KdlParser.parse(file.readText())) as ReadResult.Ok).component, env, Units(1f, 1f), { painter })

    private fun nodes(n: HostNode = host.root): List<HostNode> = listOf(n) + n.children.flatMap(::nodes)
    private fun left() = nodes().first { (it.value("label") as? Value.Str)?.value == "Notifications" }
    private fun timeWidth() = painter.measure(nodes().first { (it.value("label") as? Value.Str)?.value == "Time" }, 0, 0)!!.exactWidth

    /** What the bar shows on the left: icons, and whether the dot is there. */
    private fun shown(): Pair<Int, Boolean> = left().children.count { it.view.primitive == "icon" && it.value("picture") != Value.Null } to
        left().children.any { it.view.primitive == "box" }

    private val published = ArrayList<Pair<Int, Boolean>>()

    /** One change, as `ComponentView.settle` does it; [published] holds what a reader saw while it settled. */
    private fun change() = host.settled({ true }) {
        published += Snapshot.global { shown() }
        val n = BarFit.count(camera, timeWidth(), moon, 1f)
        (n != maxIcons).also { maxIcons = n }
    }
    private fun assertRight() {
        val want = minOf(icons, maxIcons) to (icons > maxIcons)
        assertEquals(want, shown())
    }
    private val room2At40 = 34f + 40f + 7f + 2 * 14.5f + 4.5f + 4.5f + 3.4f + 4.5f + 8f

    @Test fun aNotificationArrivingAsTheTimeWidens() {
        camera = room2At40
        change(); assertRight()
        assertEquals(0 to false, shown())
        published.clear()
        icons = 2
        nowMs = 10L * 3_600_000             // "10:00": one icon's room gone, in the same change as the arrival
        change()
        assertEquals(listOf(0 to false), published)     // nothing of it while it settled
        assertEquals(1 to true, shown())                // one icon and the dot: never two
    }
    @Test fun aNotificationLeavingAsTheCameraMoves() {
        icons = 3
        change(); assertRight()
        assertEquals(3 to false, shown())
        published.clear()
        icons = 2
        camera = room2At40 - 1f
        change()
        assertEquals(listOf(3 to false), published)
        assertEquals(1 to true, shown())
    }
    @Test fun theMoonTakesRoomInTheChangeThatBringsIt() {
        icons = 2
        camera = room2At40
        change()
        assertEquals(2 to false, shown())
        moon = true
        change()
        assertEquals(1 to true, shown())
    }
    @Test fun aLayoutChangeAloneRefits() {
        icons = 2
        change()
        assertEquals(2 to false, shown())
        camera = 0f
        change()
        assertEquals(0 to true, shown())
    }
}
