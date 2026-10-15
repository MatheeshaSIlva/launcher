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
        assertEquals(22, Moments.ALL.size)
        val perPart = Moments.ALL.groupingBy { it.part }.eachCount()
        assertEquals(mapOf(MomentPart.APPS to 4, MomentPart.HOME to 6, MomentPart.LIBRARY to 2, MomentPart.SHADE to 6,
            MomentPart.STATUS_BAR to 2, MomentPart.EVERYWHERE to 2), perPart)
        assertEquals(listOf(5, 4, 2, 9, 4, 2, 4, 12, 9, 4, 8, 7, 5, 7, 9, 12, 4, 5, 5, 5, 3, 4), Moments.ALL.map { it.roles.size })
        // The glue: animations nobody sees on their own follow their moment, unlisted.
        assertEquals(13, Moments.ALL.sumOf { it.glue.size })
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
