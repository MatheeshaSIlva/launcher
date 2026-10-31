package dev.launcher.app.motion.transition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionTypesTest {
    private val eps = 1e-4f
    private val theme = FloatArray(Prop.entries.size) { 1f }.also { it[Prop.BG_BLUR.ordinal] = 20f }
    private val out = FloatArray(Prop.entries.size)

    private fun at(t: Transition, x: Float): FloatArray { t.evaluate(x, theme, out); return out.copyOf() }

    private val figma = TransitionType.entries.filter { it != TransitionType.CUSTOM }

    @Test fun everyTypeInEveryDirectionEndsOnThePanelOpenInPlace() {
        for (type in figma) for (d in Direction.entries) {
            val v = at(TransitionTypes.opening(type, d), 1f)
            val name = "${type.id} ${d.id}"
            assertEquals(name, 0f, v[Prop.PANEL_X.ordinal], eps)
            assertEquals(name, 0f, v[Prop.PANEL_Y.ordinal], eps)
            assertEquals(name, 1f, v[Prop.PANEL_OPACITY.ordinal], eps)
            assertEquals(name, 1f, v[Prop.PANEL_SCALE.ordinal], eps)
            assertEquals("$name: its background whole", 20f, v[Prop.BG_BLUR.ordinal], eps)
            assertEquals(name, 1f, v[Prop.CONTENT_OPACITY.ordinal], eps)
        }
    }

    @Test fun everyTypeStartsWithNothingOfThePanelShowing() {
        for (type in figma) for (d in Direction.entries) {
            val t = TransitionTypes.opening(type, d)
            val v = at(t, 0f)
            val name = "${type.id} ${d.id}"
            val off = kotlin.math.abs(v[Prop.PANEL_X.ordinal]) >= 1f || kotlin.math.abs(v[Prop.PANEL_Y.ordinal]) >= 1f
            val clear = v[Prop.PANEL_OPACITY.ordinal] == 0f
            // (Out-types: the panel waits in place under what is behind, which still covers the whole screen.)
            val covered = t.behindOver && v[Prop.BEHIND_X.ordinal] == 0f && v[Prop.BEHIND_Y.ordinal] == 0f
            assertTrue("$name: off the screen, clear, or covered", off || clear || covered)
        }
    }

    @Test fun movingTypesTravelAlongTheirArrow() {
        val down = at(TransitionTypes.opening(TransitionType.MOVE_IN, Direction.DOWN), 0f)
        assertEquals("Move in down: from above the screen", -1f, down[Prop.PANEL_Y.ordinal], eps)
        val left = at(TransitionTypes.opening(TransitionType.MOVE_IN, Direction.LEFT), 0f)
        assertEquals("Move in left: from the right", 1f, left[Prop.PANEL_X.ordinal], eps)
        val out = at(TransitionTypes.opening(TransitionType.MOVE_OUT, Direction.UP), 1f)
        assertEquals("Move out up: what is behind leaves upwards", -1f, out[Prop.BEHIND_Y.ordinal], eps)
    }

    @Test fun pushMovesBothFramesTogether() {
        val t = TransitionTypes.opening(TransitionType.PUSH, Direction.RIGHT)
        for (i in 0..10) {
            val v = at(t, i / 10f)
            assertEquals("the gap between them never opens", 1f, v[Prop.BEHIND_X.ordinal] - v[Prop.PANEL_X.ordinal], eps)
        }
    }

    @Test fun slidesMoveTheFrameThatMakesWayOnlyALittle() {
        val sIn = at(TransitionTypes.opening(TransitionType.SLIDE_IN, Direction.DOWN), 1f)
        assertEquals(TransitionTypes.SLIDE_PARALLAX, sIn[Prop.BEHIND_Y.ordinal], eps)
        val sOut = at(TransitionTypes.opening(TransitionType.SLIDE_OUT, Direction.DOWN), 0f)
        assertEquals(-TransitionTypes.SLIDE_PARALLAX, sOut[Prop.PANEL_Y.ordinal], eps)
    }

    @Test fun dissolveFadesInPlaceAndInstantCutsAtOnce() {
        val d = at(TransitionTypes.opening(TransitionType.DISSOLVE, Direction.DOWN), 0.25f)
        assertEquals(0.25f, d[Prop.PANEL_OPACITY.ordinal], eps)
        assertEquals(5f, d[Prop.BG_BLUR.ordinal], eps)
        assertEquals(0f, d[Prop.PANEL_Y.ordinal], eps)
        val i = TransitionTypes.opening(TransitionType.INSTANT, Direction.DOWN)
        assertEquals(0f, at(i, 0f)[Prop.PANEL_OPACITY.ordinal], eps)
        assertEquals(1f, at(i, 0.01f)[Prop.PANEL_OPACITY.ordinal], eps)
    }

    @Test fun anAxisThatDoesNotMoveHasNoTrack() {
        val t = TransitionTypes.opening(TransitionType.MOVE_IN, Direction.DOWN)
        assertNull(t.track(Prop.PANEL_X))
        assertNull(t.track(Prop.BEHIND_Y))
    }

    @Test fun theDefaultCloseRetracesTheOpening() {
        for (type in figma.filter { it != TransitionType.INSTANT }) for (d in Direction.entries) {
            val open = TransitionTypes.opening(type, d)
            val close = TransitionTypes.closingFor(open)
            assertEquals(d.opposite, close.direction)
            for (i in 0..20) {
                val x = i / 20f
                val o = at(open, 1f - x)
                val c = at(close, x)
                for (p in Prop.entries) assertEquals("${type.id} ${d.id} ${p.id} at $x", o[p.ordinal], c[p.ordinal], 2e-3f)
            }
        }
    }

    @Test fun anInstantCloseIsGoneAtOnce() {
        val close = TransitionTypes.closingFor(TransitionTypes.opening(TransitionType.INSTANT, Direction.DOWN))
        assertEquals(1f, at(close, 0f)[Prop.PANEL_OPACITY.ordinal], eps)
        assertEquals(0f, at(close, 0.01f)[Prop.PANEL_OPACITY.ordinal], eps)
    }
}
