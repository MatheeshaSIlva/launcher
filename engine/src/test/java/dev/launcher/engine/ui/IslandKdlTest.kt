package dev.launcher.engine.ui

import android.graphics.Canvas
import dev.launcher.engine.expr.Env
import dev.launcher.engine.expr.Value
import dev.launcher.engine.kdl.KdlParser
import dev.launcher.engine.layout.Units
import dev.launcher.engine.model.CurveSpec
import dev.launcher.engine.model.ReadResult
import dev.launcher.engine.model.TokenCheck
import dev.launcher.engine.model.Validator
import java.io.File
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shipped island's declarations and bindings; real ink, baseline and clearance belong to IslandProof. */
class IslandKdlTest {
    // The island has no token references yet: refusing all tokens also detects an accidental dependency.
    private val tokens = object : TokenCheck { override fun exists(key: String) = false }
    private fun component() = (Validator(tokens).load(KdlParser.parse(
        File("src/main/assets/kdl/components/island.kdl").readText()
    )) as ReadResult.Ok).component
    private val painter = object : Painter {
        override fun key(node: HostNode): Any? = null
        override fun paint(canvas: Canvas, node: HostNode, width: Int, height: Int) {}
    }

    private fun host(hour24: Boolean = true, hour: Int = 13, minute: Int = 5, level: Int = 64,
        units: Units = Units(1f, 1f), playing: Boolean = false, art: Boolean = false, plugged: Boolean = false,
        arriving: Boolean = false, others: Int = 3, mode: String = "minimal"): ComponentHost {
        val env = object : Env {
            override fun state(name: String) = Value.Null
            override fun prop(name: String) = when (name) {
                "hour24" -> Value.Bool.of(hour24); "title-scroll" -> Value.Bool.TRUE; "mode" -> Value.Str(mode); "shown" -> Value.Bool.TRUE
                else -> Value.Null
            }
            override fun token(key: String) = Value.Null
            override fun source(path: String) = when (path) {
                "battery.level" -> Value.Num(level.toDouble())
                "media.playing" -> Value.Bool.of(playing)
                "media.title" -> if (playing) Value.Str("Halftone") else Value.Null
                "media.has-art" -> Value.Bool.of(art)
                "battery.plugged" -> Value.Bool.of(plugged)
                "notifications.arriving" -> Value.Bool.of(arriving)
                "notifications.arrival.name" -> if (arriving) Value.Str("Kasun") else Value.Null
                "notifications.arrival.others" -> if (arriving) Value.Num(others.toDouble()) else Value.Null
                "media.art" -> if (art) dev.launcher.engine.source.MediaState.of(true, "t", null, "p", dev.launcher.engine.source.MediaArt("s1", "1")).member("art") else Value.Null
                else -> Value.Null
            }
            override fun now() = hour * 3_600_000L + minute * 60_000L
            override val locale: Locale = Locale.US
            override val zone: TimeZone = TimeZone.getTimeZone("UTC")
        }
        return ComponentHost(component(), env, units, { painter })
    }

    /** The pill: the island's states (the root is the blob holding it and the banner, T-015). */
    private val ComponentHost.pill get() = root.children.single { it.value("label") == Value.Str("Pill") }

    private fun HostNode.labelled(l: String) = children.single { it.value("label") == Value.Str(l) }
    private fun idle(root: HostNode) = root.labelled("Idle")

    @Test fun validAsShippedWithOnlyTheDeclaredSourcesAndNoCameraArtwork() {
        val c = component()
        assertEquals("island", c.name)
        assertEquals(listOf("device", "time", "battery", "media", "notifications"), c.uses.sources.map { it.name })
        assertEquals(listOf("hour24", "title-scroll", "mode", "shown"), c.props.map { it.name })
        // The island is a blob (T-015): the pill and the banner's shape, filled as one, the pill on the camera.
        val island = host().root
        assertEquals("blob", island.view.primitive)
        assertEquals(Value.Str("cutout"), island.value("place"))
        assertEquals(Value.Str("#000000"), island.value("fill"))
        assertEquals(listOf("Pill", "Banner"), island.children.map { (it.value("label") as Value.Str).value })
        val root = island.labelled("Pill")
        assertEquals("box", root.view.primitive)
        assertEquals(Value.Str("full"), root.value("radius"))
        assertEquals(Value.Str("centre"), root.value("align"))
        assertEquals(Value.Bool.TRUE, root.value("clip"))
        // The idle row is there only while nothing plays; the media row always holds its slots (its pieces come and go).
        assertEquals(listOf("Idle", "Media", "Charging", "Notification"), root.children.map { (it.value("label") as Value.Str).value })
        assertEquals(listOf("Media", "Charging", "Notification"), host(playing = true).pill.children.map { (it.value("label") as Value.Str).value })
        val row = idle(root)
        assertEquals("row", row.view.primitive)
        assertEquals(Value.Str("centre-line"), row.value("align"))
        assertEquals(listOf("text", "spacer", "text"), row.children.map { it.view.primitive })
        assertEquals(1f, row.children[1].flex)
    }

    @Test fun clockAndBatteryBindingsCoverWidthChangingValues() {
        for ((hour, minute, formatted) in listOf(Triple(9, 59, "9:59"), Triple(10, 0, "10:00"),
            Triple(23, 59, "23:59"), Triple(0, 0, "0:00"))) {
            assertEquals(Value.Str(formatted), host(hour = hour, minute = minute).pill.let { idle(it) }.children.first().value("content"))
        }
        assertEquals(Value.Str("13:05"), host().pill.let { idle(it) }.children.first().value("content"))
        assertEquals(Value.Str("1:05"), host(hour24 = false).pill.let { idle(it) }.children.first().value("content"))
        assertEquals(Value.Str("12:00"), host(hour24 = false, hour = 0, minute = 0).pill.let { idle(it) }.children.first().value("content"))
        for (level in listOf(9, 10, 64, 99, 100))
            assertEquals(Value.Num(level.toDouble()), host(level = level).pill.let { idle(it) }.children.last().value("content"))
    }

    @Test fun geometryAndTypographyStayInDpAcrossDensitiesAndLauncherScales() {
        for (density in listOf(1f, 2.625f)) {
            val units = Units(3f, density)
            val root = host(units = units).pill
            for (scale in listOf(3f, 4.5f)) {
                units.unitPx = scale
                assertEquals(132f * density, root.length("width"))
                assertEquals(32f * density, root.length("height"))
                val row = idle(root)
                assertEquals(14f * density, row.length("padding-start"))
                assertEquals(12f * density, row.length("padding-end"))
                val texts = row.children.filter { it.view.primitive == "text" }
                assertEquals(2, texts.size)
                for (text in texts) {
                    assertEquals(Value.Str("jetbrains-mono"), text.value("font"))
                    assertEquals(13f * density, text.length("size"))
                    assertEquals(400f, text.number("weight"))
                    assertEquals(Value.Str("#F2F2F2"), text.value("color"))
                    assertTrue(text.view.motion?.roll != null)
                    assertEquals(CurveSpec.Role("motion.text.roll"), text.view.motion?.rollCurve())
                }
            }
        }
    }

    /** T-013: draft 4's media state (Figma 41:276) in dp, with fixed slots 14 | 90 | 28 | 92 | 12 around the camera. */
    @Test fun theMediaStateIsDraft4sInFixedSlots() {
        for (density in listOf(1f, 2.625f)) {
            val root = host(units = Units(3f, density), playing = true).pill
            assertEquals(236f * density, root.length("width"))
            assertEquals(32f * density, root.length("height"))
            val media = root.labelled("Media")
            assertEquals(14f * density, media.length("padding-start"))
            assertEquals(12f * density, media.length("padding-end"))
            assertEquals(listOf("row", "spacer", "row"), media.children.map { it.view.primitive })
            val left = media.children[0]
            assertEquals(90f * density, left.length("width"))
            assertEquals(6f * density, left.length("gap"))
            assertEquals(28f * density, media.children[1].length("size"))
            assertEquals(92f * density, media.children[2].length("width"))
            assertEquals(236f, 14f + 90f + 28f + 92f + 12f)
            assertEquals(20f * density, left.labelled("Cover slot").length("width"))
            assertEquals(listOf<HostNode>(), left.labelled("Cover slot").children)   // no art yet: the slot stays, empty
            val cover = host(units = Units(3f, density), playing = true, art = true).pill.labelled("Media").labelled("Left").labelled("Cover slot").labelled("Cover")
            assertEquals("image", cover.view.primitive)
            assertEquals(20f * density, cover.length("size"))
            assertEquals(4f * density, cover.length("radius"))
            assertEquals(64f * density, left.labelled("Title slot").length("width"))
            val title = left.labelled("Title slot").labelled("Title")
            assertEquals(Value.Str("Halftone"), title.value("content"))
            assertEquals(Value.Str("plex-sans"), title.value("font"))
            assertEquals(13f * density, title.length("size"))
            assertEquals(64f * density, title.length("width"))
            assertEquals(20f + 6f + 64f, 90f)
            assertEquals(Value.Str("fade"), title.value("overflow"))
            assertEquals(Value.Bool.TRUE, title.value("scroll"))
            val bars = media.children[2].labelled("Bars")
            assertEquals(2f * density, bars.length("gap"))
            assertEquals(listOf(6f, 12f, 8f, 14f, 10f).map { it * density }, bars.children.map { it.length("height") })
            for (b in bars.children) {
                assertEquals(2f * density, b.length("width"))
                assertEquals(Value.Str("#D9865F"), b.value("fill"))
                assertEquals(Value.Bool.TRUE, b.value("loop-run"))
            }
        }
        val paused = host(playing = false).pill
        assertEquals(132f, paused.length("width"))
        assertEquals(listOf("Idle", "Media", "Charging", "Notification"), paused.children.map { (it.value("label") as Value.Str).value })
        val still = paused.labelled("Media")
        assertEquals(listOf<HostNode>(), still.labelled("Left").labelled("Title slot").children)   // nothing shown, slots kept
        assertEquals(listOf("Left", "Right"), still.children.filter { it.value("label") is Value.Str }.map { (it.value("label") as Value.Str).value })
    }

    private fun HostNode.shown(): List<String> = generateSequence(listOf(this)) { l -> l.flatMap { it.children }.takeIf { it.isNotEmpty() } }
        .flatten().mapNotNull { (it.value("label") as? Value.Str)?.value }.toList()

    /** T-014: one state at a time by rank (a notification, charging, media, idle), each at draft 4's width. */
    @Test fun theRankingPicksOneStateAtATime() {
        fun state(playing: Boolean = false, plugged: Boolean = false, arriving: Boolean = false): Pair<Float?, List<String>> {
            val root = host(playing = playing, plugged = plugged, arriving = arriving).pill
            return root.length("width") to root.shown()
        }
        val idle = state()
        assertEquals(132f, idle.first)
        assertTrue("Time" in idle.second && "Charging time" !in idle.second && "Sender" !in idle.second && "Title" !in idle.second)
        val media = state(playing = true)
        assertEquals(236f, media.first)
        assertTrue("Title" in media.second && "Time" !in media.second)
        val charging = state(playing = true, plugged = true)
        assertEquals("charging over media", 164f, charging.first)
        assertTrue("Charging time" in charging.second && "Charging level" in charging.second && "Title" !in charging.second)
        val notif = state(playing = true, plugged = true, arriving = true)
        assertEquals("a notification over everything", 200f, notif.first)
        assertTrue("Sender" in notif.second && "Dot" in notif.second && "Others" in notif.second)
        assertTrue("Charging time" !in notif.second && "Title" !in notif.second && "Time" !in notif.second)
        assertTrue("no count without others", "Others" !in host(arriving = true, others = 0).pill.shown())
    }

    /** T-014: the charging and notification states in draft 4's dp, the camera slot on the pill's centre. */
    @Test fun chargingAndNotificationAreDraft4sInFixedSlots() {
        val density = 2.625f
        val c = host(units = Units(3f, density), plugged = true).pill.labelled("Charging")
        assertEquals(164f * density, c.length("width"))
        assertEquals(14f + 54f + 28f + 56f + 12f, 164f)
        assertEquals(54f * density, c.labelled("Charging left").length("width"))
        assertEquals(56f * density, c.labelled("Charging right").length("width"))
        val level = c.labelled("Charging right").labelled("Level")
        assertEquals(6f * density, level.length("gap"))
        assertEquals(Value.Str("#7DBE93"), level.labelled("Charging level").value("color"))
        assertEquals(Value.Str("64%"), level.labelled("Charging level").value("content"))
        val n = host(units = Units(3f, density), arriving = true).pill.labelled("Notification")
        assertEquals(200f * density, n.length("width"))
        assertEquals(14f + 72f + 28f + 74f + 12f, 200f)
        val left = n.labelled("Notification left")
        assertEquals(72f * density, left.length("width"))
        assertEquals(6f * density, left.labelled("Dot slot").labelled("Dot").length("width"))
        assertEquals(Value.Str("#D9865F"), left.labelled("Dot slot").labelled("Dot").value("fill"))
        val sender = left.labelled("Sender slot").labelled("Sender")
        assertEquals(Value.Str("Kasun"), sender.value("content"))
        assertEquals(Value.Str("plex-sans"), sender.value("font"))
        assertEquals(Value.Str("fade"), sender.value("overflow"))
        val others = n.labelled("Notification right").labelled("Others")
        assertEquals(Value.Str("+3"), others.value("content"))
        assertEquals(Value.Str("#8A8A8A"), others.value("color"))
    }

    /** T-015: detailed mode: the pill keeps its own state; the banner's shape grows below it and its content shows. */
    @Test fun detailedModeGrowsTheBannerBelowAndLeavesThePillAlone() {
        val density = 2.625f
        val h = host(units = Units(3f, density), arriving = true, mode = "detailed")
        assertEquals("the pill stays idle", 132f * density, h.pill.length("width"))
        assertTrue("Time" in h.pill.shown() && "Sender" !in h.pill.shown())
        val banner = h.root.labelled("Banner")
        assertEquals(380f * density, banner.length("width"))
        assertEquals(76f * density, banner.length("height"))
        assertEquals(44f * density, banner.length("translate-y"))
        assertEquals(listOf("Mark", "Banner sender", "Banner text", "Banner time"), banner.children.map { (it.value("label") as Value.Str).value })
        // Minimal mode: the banner's shape rests at no size at the pill's top (no part in the union), empty.
        val m = host(units = Units(3f, density), arriving = true).root.labelled("Banner")
        assertEquals(0f, m.length("width"))
        assertEquals(0f, m.length("height"))
        assertEquals(12f * density, m.length("translate-y"))
        assertEquals(listOf<HostNode>(), m.children)
    }
}
