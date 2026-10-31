package dev.launcher.app.motion.transition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionTest {
    private val eps = 1e-4f
    /** The theme's values by property: blur 20 pt, veil 0.5, corners 30 pt, the rest 1. */
    private val theme = FloatArray(Prop.entries.size) { 1f }.also {
        it[Prop.BG_BLUR.ordinal] = 20f; it[Prop.BG_VEIL.ordinal] = 0.5f; it[Prop.PANEL_CORNER.ordinal] = 30f
    }
    private val out = FloatArray(Prop.entries.size)

    private fun track(p: Prop, vararg k: Keyframe) = Track(p, k.toList())

    @Test fun interpolatesBetweenKeyframesWithTheEasingOfTheFirst() {
        val t = track(Prop.PANEL_Y, Keyframe(0f, -1f), Keyframe(0.5f, 0f, ease = Easing.Hold), Keyframe(1f, 1f))
        assertEquals(-1f, t.valueAt(0f, 1f), eps)
        assertEquals(-0.5f, t.valueAt(0.25f, 1f), eps)
        assertEquals(0f, t.valueAt(0.5f, 1f), eps)
        assertEquals("held until the next keyframe", 0f, t.valueAt(0.99f, 1f), eps)
        assertEquals(1f, t.valueAt(1f, 1f), eps)
    }

    @Test fun holdsBeforeTheFirstAndAfterTheLastKeyframeInside01() {
        val t = track(Prop.CONTENT_OPACITY, Keyframe(0.4f, 0f), Keyframe(0.8f, 1f))
        assertEquals(0f, t.valueAt(0.1f, 1f), eps)
        assertEquals(0.5f, t.valueAt(0.6f, 1f), eps)
        assertEquals(1f, t.valueAt(0.95f, 1f), eps)
        assertEquals("an overshoot past a keyframe that is not at 1 holds", 1f, t.valueAt(1.2f, 1f), eps)
    }

    @Test fun anOvershootCarriesTheEndSegmentOnSoASpringBounces() {
        val t = track(Prop.PANEL_Y, Keyframe(0f, -1f), Keyframe(1f, 0f))
        assertEquals(0.1f, t.valueAt(1.1f, 1f), eps)
        assertEquals(-1.1f, t.valueAt(-0.1f, 1f), eps)
    }

    @Test fun everyValueStaysWithinItsPropertysBounds() {
        val t = track(Prop.PANEL_OPACITY, Keyframe(0f, 0f), Keyframe(1f, 1f))
        assertEquals(1f, t.valueAt(1.5f, 1f), eps)
        assertEquals(0f, t.valueAt(-0.5f, 1f), eps)
        val back = track(Prop.CONTENT_OPACITY, Keyframe(0f, 0f, ease = Easing.EASE_OUT_BACK), Keyframe(1f, 1f))
        for (i in 0..100) assertTrue(back.valueAt(i / 100f, 1f) in 0f..1f)
    }

    @Test fun aLooksKeyframeIsAShareOfTheThemeUnlessAbsolute() {
        val t = Transition(TransitionType.CUSTOM, Direction.DOWN, Drive.FINGER, listOf(
            track(Prop.BG_BLUR, Keyframe(0f, 0f), Keyframe(1f, 12f, absolute = true)),
            track(Prop.BG_VEIL, Keyframe(0f, 0f), Keyframe(1f, 1f))))
        t.evaluate(1f, theme, out)
        assertEquals("absolute: the theme's 20 is ignored", 12f, out[Prop.BG_BLUR.ordinal], eps)
        assertEquals("a share of the theme's 0.5", 0.5f, out[Prop.BG_VEIL.ordinal], eps)
        t.evaluate(0.5f, theme, out)
        assertEquals("between a share and an absolute value, in real units", 6f, out[Prop.BG_BLUR.ordinal], eps)
    }

    @Test fun aPropertyWithNoTrackRestsAndALooksRestIsTheThemesValue() {
        val t = Transition(TransitionType.CUSTOM, Direction.DOWN, Drive.FINGER, emptyList())
        t.evaluate(0.3f, theme, out)
        assertEquals(1f, out[Prop.PANEL_OPACITY.ordinal], eps)
        assertEquals(0f, out[Prop.PANEL_Y.ordinal], eps)
        assertEquals(20f, out[Prop.BG_BLUR.ordinal], eps)
        assertEquals(30f, out[Prop.PANEL_CORNER.ordinal], eps)
        assertEquals(0f, out[Prop.BEHIND_DIM.ordinal], eps)
    }

    @Test fun easingsMatchTheirDefinitions() {
        assertEquals(0.5f, Easing.Linear.at(0.5f), eps)
        assertEquals(0f, Easing.Hold.at(0.999f), eps)
        assertEquals(1f, Easing.Jump.at(0.001f), eps)
        assertEquals(0f, Easing.Jump.at(0f), eps)
        assertEquals(Math.sqrt(0.3).toFloat(), Easing.Power(0.5f, out = false).at(0.3f), eps)
        assertEquals(1f - 0.7f * 0.7f, Easing.Power(2f, out = true).at(0.3f), eps)
        assertEquals(0.5f, Easing.EASE_IN_OUT.at(0.5f), 1e-3f)
        assertTrue("ease in is slow at first", Easing.EASE_IN.at(0.25f) < 0.25f)
        assertTrue("ease out is fast at first", Easing.EASE_OUT.at(0.25f) > 0.25f)
        assertTrue("ease out back overshoots", (0..100).any { Easing.EASE_OUT_BACK.at(it / 100f) > 1f })
    }

    @Test fun aMirroredEasingPlaysTheSameCurveBackwards() {
        val all = listOf(Easing.Linear, Easing.EASE_IN, Easing.EASE_OUT_BACK, Easing.Bezier(0.2f, 0f, 0f, 1f), Easing.Power(0.5f, false), Easing.Power(3f, true))
        for (e in all) for (i in 1..99) {
            val u = i / 100f
            assertEquals("$e at $u", 1f - e.at(1f - u), e.mirrored().at(u), 2e-3f)
        }
    }

    @Test fun aReversedTrackRetracesTheSameValues() {
        val t = track(Prop.PANEL_Y, Keyframe(0f, -1f, ease = Easing.EASE_IN), Keyframe(0.3f, -0.4f, ease = Easing.Power(2f, true)), Keyframe(1f, 0f))
        val r = t.reversed()
        for (i in 0..100) {
            val x = i / 100f
            assertEquals("at $x", t.valueAt(1f - x, 1f), r.valueAt(x, 1f), 2e-3f)
        }
    }

    @Test fun aReversedHoldCutsAtTheOtherEnd() {
        val t = track(Prop.PANEL_OPACITY, Keyframe(0f, 0f, ease = Easing.Hold), Keyframe(1f, 1f))
        val r = t.reversed()
        assertEquals(1f, r.valueAt(0f, 1f), eps)
        assertEquals("forward it was 0 until the very end, so backwards it is 0 from the very start", 0f, r.valueAt(0.01f, 1f), eps)
    }

    @Test fun malformedTracksAreRefused() {
        assertThrows(IllegalArgumentException::class.java) { track(Prop.PANEL_Y) }
        assertThrows(IllegalArgumentException::class.java) { track(Prop.PANEL_Y, Keyframe(0.5f, 0f), Keyframe(0.5f, 1f)) }
        assertThrows(IllegalArgumentException::class.java) { track(Prop.PANEL_Y, Keyframe(0.6f, 0f), Keyframe(0.2f, 1f)) }
        assertThrows(IllegalArgumentException::class.java) { track(Prop.PANEL_Y, Keyframe(1.2f, 0f)) }
        assertThrows("only a look can be absolute", IllegalArgumentException::class.java) { track(Prop.PANEL_Y, Keyframe(0f, 0f, absolute = true)) }
        assertThrows(IllegalArgumentException::class.java) { Track(Prop.PANEL_Y, List(Transition.MAX_KEYS + 1) { Keyframe(it / 20f, 0f) }) }
        assertThrows(IllegalArgumentException::class.java) {
            Transition(TransitionType.CUSTOM, Direction.DOWN, Drive.FINGER, listOf(track(Prop.PANEL_Y, Keyframe(0f, 0f)), track(Prop.PANEL_Y, Keyframe(0f, 1f))))
        }
    }

    @Test fun editingTracksMakesItCustomAndRemembersItsType() {
        val push = TransitionTypes.opening(TransitionType.PUSH, Direction.DOWN)
        val mine = push.withTracks(push.tracks + track(Prop.BEHIND_DIM, Keyframe(0f, 0f), Keyframe(1f, 0.4f)))
        assertEquals(TransitionType.CUSTOM, mine.type)
        assertEquals(TransitionType.PUSH, mine.from)
        assertEquals("edited again it still came from push", TransitionType.PUSH, mine.withTracks(mine.tracks).from)
        assertFalse(mine.behindOver)
        val out = TransitionTypes.opening(TransitionType.SLIDE_OUT, Direction.UP)
        assertTrue(out.behindOver)
        assertTrue("a custom slide out keeps what is behind over the panel", out.withTracks(out.tracks).behindOver)
    }
}
