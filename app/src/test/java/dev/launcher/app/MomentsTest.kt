package dev.launcher.app

import dev.launcher.app.design.Theme
import dev.launcher.app.motion.MomentPart
import dev.launcher.app.motion.Moments
import dev.launcher.app.motion.MotionAdjust
import dev.launcher.app.motion.MotionRoles
import dev.launcher.app.motion.MotionTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Motion as moments (docs/PLAN_SETTINGS_V2.md, V2): every animation in one moment, as the plan's table has them. */
class MomentsTest {
    private val editable = MotionRoles.EDITABLE.flatMap { g -> g.roles.map { it.key } }

    @Test fun everyAnimationBelongsToExactlyOneMoment() {
        val held = Moments.ALL.flatMap { it.all }
        assertEquals("in two moments: ${held.groupBy { it }.filter { it.value.size > 1 }.keys}", held.size, held.toSet().size)
        assertEquals("in no moment: ${editable.toSet() - held.toSet()}", emptySet<String>(), editable.toSet() - held.toSet())
        assertEquals("not an animation the settings retime: ${held.toSet() - editable.toSet()}", emptySet<String>(), held.toSet() - editable.toSet())
        for (k in held) assertEquals(k, k, Moments.of(k)?.all?.firstOrNull { it == k })
    }

    @Test fun theMomentsAreThePlansTable() {
        assertEquals(24, Moments.ALL.size)
        val perPart = Moments.ALL.groupingBy { it.part }.eachCount()
        assertEquals(mapOf(MomentPart.APPS to 4, MomentPart.HOME to 6, MomentPart.LIBRARY to 2, MomentPart.SHADE to 7,
            MomentPart.STATUS_BAR to 3, MomentPart.EVERYWHERE to 2), perPart)
        assertEquals(listOf(5, 4, 2, 9, 4, 2, 4, 12, 9, 4, 8, 9, 5, 7, 9, 12, 10, 4, 5, 5, 5, 4, 3, 4), Moments.ALL.map { it.roles.size })
        // The glue: animations nobody sees on their own follow their moment, unlisted.
        assertEquals(21, Moments.ALL.sumOf { it.glue.size })
        assertEquals("ids twice", Moments.ALL.size, Moments.ALL.map { it.id }.toSet().size)
    }

    @Test fun aCharacterMovesNumbersOfItsOwnMoment() {
        for (m in Moments.ALL) {
            val c = m.character ?: continue
            for ((k, _) in c.keys) {
                assertTrue("${m.id}: $k is not its own", k in m.roles)
                assertTrue("${m.id}: $k is not a number", k in MotionTokens.NUMBERS)
            }
        }
    }

    @Test fun thePresetHoldsEveryMomentsFeel() {
        val keys = Theme.parse(File("src/main/assets/motion/ios27.json").readText()).entries.keys
        for (m in Moments.ALL) {
            assertTrue("${m.speed.name} missing", m.speed.name in keys)
            assertTrue("${m.bounce.name} missing", m.bounce.name in keys)
        }
        val feels = keys.filter { it.startsWith("motion.adjust.") && it.count { c -> c == '.' } == 3 }
        assertEquals("a feel of no moment", Moments.ALL.size * 2, feels.size)
    }

    @Test fun everyPresetGivesAStyleTheAppKnows() {
        val dir = File("src/main/assets/motion")
        for (f in dir.listFiles()!!.filter { it.name.endsWith(".json") }) {
            val t = Theme.parse(f.readText())
            for (k in listOf(dev.launcher.app.motion.AppStyles.OPENING.name, dev.launcher.app.motion.AppStyles.CLOSING.name)) {
                val v = t.entries[k]?.value ?: continue
                val id = (v as dev.launcher.app.design.Value.Choice).option
                assertTrue("${f.name}: $k is '$id'", dev.launcher.app.motion.AppStyle.entries.any { it.id == id })
            }
        }
    }

    @Test fun appsOpenAndGoHomeInStyles() {
        // Only the two moments of apps have a style, each its own key.
        assertEquals(listOf(Moments.OPENING to dev.launcher.app.motion.AppStyles.OPENING, Moments.CLOSING to dev.launcher.app.motion.AppStyles.CLOSING),
            Moments.ALL.filter { it.style != null }.map { it to it.style })
        // Sliding and fading leave the icon on home (no icon hidden, none to land in); the others come out of it and go back.
        for (s in dev.launcher.app.motion.AppStyle.entries) {
            val leaves = s == dev.launcher.app.motion.AppStyle.SLIDE || s == dev.launcher.app.motion.AppStyle.FADE
            assertEquals("$s", !leaves, s.fromIcon)
        }
        // An unknown id (an older preset, a typo) is the zoom.
        assertEquals(dev.launcher.app.motion.AppStyle.ZOOM, dev.launcher.app.motion.AppStyle.of("warp"))
        assertEquals(dev.launcher.app.motion.AppStyle.ZOOM, dev.launcher.app.motion.AppStyle.of(null))
    }

    @Test fun theContainerTransformFadesThrough() {
        val s = dev.launcher.app.motion.AppStyles
        // The icon only at the start, the app only at the end, never both at once.
        assertEquals(1f, s.containerIcon(0f), 0f); assertEquals(0f, s.containerIcon(1f), 0f)
        assertEquals(0f, s.containerContent(0f), 0f); assertEquals(1f, s.containerContent(1f), 0f)
        var q = 0f
        while (q <= 1f) { assertTrue("at $q", s.containerIcon(q) == 0f || s.containerContent(q) == 0f); q += 0.01f }
    }

    @Test fun theCircleRevealCoversTheWholeScreen() {
        val s = dev.launcher.app.motion.AppStyles
        // From half the icon to the farthest corner from it.
        assertEquals(60f, s.revealRadius(0f, 100f, 2000f, 120f, 1080f, 2340f), 0.01f)
        val far = kotlin.math.hypot(1080f - 100f, 2000f)
        assertEquals(far, s.revealRadius(1f, 100f, 2000f, 120f, 1080f, 2340f), 0.5f)
    }

    @Test fun theGeniePoursTheAppIntoItsIcon() {
        val icon = dev.launcher.app.motion.Box(130f, 2100f, 230f, 2200f)
        val app = dev.launcher.app.motion.Box(0f, 0f, 1080f, 2340f)
        val cols = dev.launcher.app.motion.AppStyles.GENIE_COLS
        val rows = dev.launcher.app.motion.AppStyles.GENIE_ROWS
        val out = FloatArray((cols + 1) * (rows + 1) * 2)
        val last = out.size - 2
        for ((p, r) in listOf(0f to app, 1f to icon)) {
            dev.launcher.app.motion.AppStyles.genie(out, cols, rows, app, icon, p)
            // Its corners where the rect's are: the app as it is, then all of it in the icon.
            assertEquals("$p", r.left, out[0], 0.01f); assertEquals("$p", r.top, out[1], 0.01f)
            assertEquals("$p", r.right, out[last], 0.01f); assertEquals("$p", r.bottom, out[last + 1], 0.01f)
        }
        // On the way, a funnel: the bottom (nearest the icon below) is narrow and already at the icon, the top still wide.
        dev.launcher.app.motion.AppStyles.genie(out, cols, rows, app, icon, 0.4f)
        val topRowWidth = out[cols * 2] - out[0]
        val bottomStart = rows * (cols + 1) * 2
        val bottomRowWidth = out[bottomStart + cols * 2] - out[bottomStart]
        assertTrue("top $topRowWidth, bottom $bottomRowWidth", topRowWidth > bottomRowWidth * 3)
        assertTrue("bottom at ${out[bottomStart + 1]}", out[bottomStart + 1] > 2100f)
        // Its sides curve: a quarter of the way down, the left edge is nearer the app's than a straight line would be.
        val mid = (rows / 4) * (cols + 1) * 2
        val straight = out[0] + (out[bottomStart] - out[0]) * ((out[mid + 1] - out[1]) / (out[bottomStart + 1] - out[1]))
        assertTrue("left ${out[mid]}, straight $straight", out[mid] < straight)
        // An icon above the app: its top edge goes first.
        val high = dev.launcher.app.motion.Box(130f, 200f, 230f, 300f)
        dev.launcher.app.motion.AppStyles.genie(out, cols, rows, app, high, 0.4f)
        assertTrue("top row at ${out[1]}", out[1] < 300f)
    }

    @Test fun aMomentsFeelGoesOnTopOfThePresets() {
        assertEquals(1f, MotionAdjust.speed(1f, 1f), 0f)
        assertEquals(2f, MotionAdjust.speed(1f, 2f), 1e-6f)
        assertEquals(1f, MotionAdjust.speed(2f, 0.5f), 1e-6f)
        assertEquals(0f, MotionAdjust.bounce(1f, 0f), 0f)
        assertEquals(1.5f, MotionAdjust.bounce(0.75f, 2f), 1e-6f)
        // Each within its own range.
        assertEquals(MotionAdjust.SPEED_MAX * MotionAdjust.MOMENT_SPEED_MAX, MotionAdjust.speed(9f, 9f), 1e-6f)
    }
}
