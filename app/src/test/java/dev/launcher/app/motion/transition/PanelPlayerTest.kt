package dev.launcher.app.motion.transition

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelPlayerTest {
    private val eps = 1e-4f
    /** Every role a straight line over its length (ms), so a value tells exactly how far its clock is. */
    private val lengths = mapOf("motion.open" to 300f, "motion.close" to 400f, "motion.reveal" to 200f, "motion.undim" to 100f, "motion.dim" to 100f)
    private val player = PanelPlayer { role -> RoleClock.linear(lengths.getValue(role)) }
    private val theme = FloatArray(Prop.entries.size) { 1f }.also { it[Prop.DIM_BELOW.ordinal] = 0.3f }
    private val out = FloatArray(Prop.entries.size)

    private fun k(at: Float, v: Float) = Keyframe(at, v)
    private fun progress(p: Prop, vararg keys: Keyframe) = Track(p, keys.toList())
    private fun clock(p: Prop, role: String, cue: Cue, vararg keys: Keyframe, delay: Float = 0f) = Track(p, keys.toList(), TrackTiming.Clock(role, cue, delay))
    private fun custom(vararg tracks: Track, drive: Drive = Drive.FINGER) = Transition(TransitionType.CUSTOM, Direction.DOWN, drive, tracks.toList())

    /** iOS 27's Notification Center in miniature: a clear slab while held, its content and dim on clocks of their own. */
    private val open = custom(
        progress(Prop.PANEL_EDGE, k(0f, 0f), k(1f, 1f)),
        progress(Prop.CONTENT_OPACITY, k(0f, 0f)),
        clock(Prop.DIM_BELOW, "motion.dim", Cue.START, k(0f, 0f), k(1f, 1f)))
    private val release = custom(
        clock(Prop.CONTENT_OPACITY, "motion.reveal", Cue.START, k(0f, 0f), k(1f, 1f)),
        clock(Prop.DIM_BELOW, "motion.undim", Cue.LANDED, k(0f, 1f), k(1f, 0f)))
    private val close = custom(
        progress(Prop.PANEL_EDGE, k(0f, 1f), k(1f, 0f)),
        progress(Prop.CONTENT_OPACITY, k(0f, 1f), k(1f, 0f)))

    private fun at(p: Float, now: Long): FloatArray { player.evaluate(p, now, theme, out); return out }
    private fun begin(m: PanelMotion = PanelMotion(open, release, TransitionSpec.Own(close), "motion.open", "motion.close")) = player.begin(m)

    @Test fun pulledItFollowsTheFingerAndItsClocksRunFromThePull() {
        begin()
        player.pull(0)
        val v = at(0.3f, 50)
        assertEquals(0.3f, v[Prop.PANEL_EDGE.ordinal], eps)
        assertEquals("the slab shows no content", 0f, v[Prop.CONTENT_OPACITY.ordinal], eps)
        assertEquals("half of the dim's 100 ms, of the layout's 0.3", 0.15f, v[Prop.DIM_BELOW.ordinal], eps)
    }

    @Test fun releasedItsTracksRunOnTheirOwnClocksOverTheOpening() {
        begin()
        player.pull(0)
        at(0.7f, 900)
        player.release(1000)
        val a = at(0.8f, 1100)
        assertEquals("the edge still lands on the panel's progress", 0.8f, a[Prop.PANEL_EDGE.ordinal], eps)
        assertEquals("half of the reveal's 200 ms", 0.5f, a[Prop.CONTENT_OPACITY.ordinal], eps)
        assertEquals("the dim holds until the landing", 0.3f, a[Prop.DIM_BELOW.ordinal], eps)
        player.landed(1200)
        assertEquals(0.15f, at(1f, 1250)[Prop.DIM_BELOW.ordinal], eps)
        assertEquals(0f, at(1f, 1400)[Prop.DIM_BELOW.ordinal], eps)
        assertEquals(1f, at(1f, 1400)[Prop.CONTENT_OPACITY.ordinal], eps)
    }

    @Test fun aClockWaitsForItsDelay() {
        player.begin(PanelMotion(custom(clock(Prop.BAR_OPACITY, "motion.dim", Cue.START, k(0f, 1f), k(1f, 0f), delay = 40f)), null,
            TransitionSpec.ReverseOfOpening, "motion.open", "motion.close"))
        player.pull(0)
        assertEquals(1f, at(0.5f, 30)[Prop.BAR_OPACITY.ordinal], eps)
        assertEquals(0.5f, at(0.5f, 90)[Prop.BAR_OPACITY.ordinal], eps)
    }

    @Test fun aCloseDuringTheRevealStartsFromWhatShowedAndEndsOnTheClose() {
        begin()
        player.pull(0)
        player.release(1000)
        assertEquals(0.5f, at(1f, 1100)[Prop.CONTENT_OPACITY.ordinal], eps)
        player.close(1100)
        assertEquals("no jump at the close's first frame", 0.5f, at(1f, 1100)[Prop.CONTENT_OPACITY.ordinal], eps)
        assertEquals("the difference goes as the close goes", 0.25f, at(0.5f, 1200)[Prop.CONTENT_OPACITY.ordinal], eps)
        assertEquals("and it ends on the close's own end", 0f, at(0f, 1300)[Prop.CONTENT_OPACITY.ordinal], eps)
    }

    @Test fun aPullCaughtOnItsWayOutStartsFromWhereItWas() {
        begin()
        player.pull(0)
        player.release(0)
        at(1f, 1000)
        player.close(1000)
        val before = at(0.6f, 1100)[Prop.CONTENT_OPACITY.ordinal]
        player.pull(1100)
        assertEquals(before, at(0.6f, 1100)[Prop.CONTENT_OPACITY.ordinal], eps)
        assertEquals("the slab's no content once the pull goes on to its end", 0f, at(1f, 1150)[Prop.CONTENT_OPACITY.ordinal], eps)
    }

    @Test fun drivenOnReleaseNothingMovesUntilTheFingerLetsGo() {
        val push = TransitionTypes.opening(TransitionType.PUSH, Direction.DOWN, Drive.RELEASE)
        player.begin(PanelMotion(push, null, TransitionSpec.ReverseOfOpening, "motion.open", "motion.close"))
        player.pull(0)
        assertEquals("the pull only decides", -1f, at(0.6f, 100)[Prop.PANEL_Y.ordinal], eps)
        player.release(1000)
        assertEquals("then it plays on the panel's own spring", -0.5f, at(0.9f, 1150)[Prop.PANEL_Y.ordinal], eps)
        assertEquals(0f, at(1f, 1400)[Prop.PANEL_Y.ordinal], eps)
    }

    @Test fun theReverseOfTheOpeningIsMadeFromIt() {
        val dissolve = TransitionTypes.opening(TransitionType.DISSOLVE, Direction.DOWN)
        val m = PanelMotion(dissolve, null, TransitionSpec.ReverseOfOpening, "motion.open", "motion.close")
        assertEquals(TransitionTypes.closingFor(dissolve), m.close)
        player.begin(m)
        player.pull(0)
        player.release(0)
        at(1f, 500)
        player.close(500)
        assertEquals(0.25f, at(0.25f, 600)[Prop.PANEL_OPACITY.ordinal], eps)
    }

    @Test fun aNewOpeningCarriesNothingOver() {
        begin()
        player.pull(0)
        player.release(0)
        at(1f, 100)
        player.close(100)
        at(0.5f, 150)
        player.closed()
        begin()
        player.pull(1000)
        assertEquals(0.2f, at(0.2f, 1000)[Prop.PANEL_EDGE.ordinal], eps)
        assertEquals(0f, at(0.2f, 1000)[Prop.CONTENT_OPACITY.ordinal], eps)
    }

    @Test fun aClocksTrackMayStartFromTheValueShowing() {
        // The bar fades out over home while pulled, and the release brings it back from wherever it got to.
        val o = custom(clock(Prop.BAR_OPACITY, "motion.dim", Cue.START, k(0f, 1f), k(1f, 0f)))
        val r = custom(Track(Prop.BAR_OPACITY, listOf(Keyframe(0f, 0f, fromCurrent = true), k(1f, 1f)), TrackTiming.Clock("motion.reveal", Cue.START)))
        player.begin(PanelMotion(o, r, TransitionSpec.ReverseOfOpening, "motion.open", "motion.close"))
        player.pull(0)
        assertEquals(0.6f, at(0.7f, 40)[Prop.BAR_OPACITY.ordinal], eps)
        player.release(40)
        assertEquals("from the 0.6 that showed, not from 0", 0.6f, at(0.8f, 40)[Prop.BAR_OPACITY.ordinal], eps)
        assertEquals(0.8f, at(0.9f, 140)[Prop.BAR_OPACITY.ordinal], eps)
        assertEquals(1f, at(1f, 240)[Prop.BAR_OPACITY.ordinal], eps)
    }

    @Test fun aTrackLaidOverWaitsForItsClockAndTheValueUnderShowsUntilThen() {
        val o = custom(clock(Prop.DIM_BELOW, "motion.dim", Cue.START, k(0f, 0f), k(1f, 1f)))
        val r = custom(Track(Prop.DIM_BELOW, listOf(Keyframe(0f, 0f, fromCurrent = true), k(1f, 0f)), TrackTiming.Clock("motion.undim", Cue.LANDED)))
        player.begin(PanelMotion(o, r, TransitionSpec.ReverseOfOpening, "motion.open", "motion.close"))
        player.pull(0)
        at(0.7f, 30)
        player.release(30)
        assertEquals("the pull's dim goes on under it (0.3 of 1 at 60 ms)", 0.6f * 0.3f, at(0.9f, 60)[Prop.DIM_BELOW.ordinal], eps)
        assertEquals(0.3f, at(1f, 150)[Prop.DIM_BELOW.ordinal], eps)
        player.landed(150)
        assertEquals("then from what showed to nothing", 0.15f, at(1f, 200)[Prop.DIM_BELOW.ordinal], eps)
    }

    @Test fun aFallbackLaysItsTracksOverTheOpening() {
        val o = custom(progress(Prop.PANEL_EDGE, k(0f, 0f), k(1f, 1f)), clock(Prop.DIM_BELOW, "motion.dim", Cue.START, k(0f, 0f), k(1f, 1f)))
        val f = custom(Track(Prop.DIM_BELOW, listOf(Keyframe(0f, 0f, fromCurrent = true), k(1f, 0f)), TrackTiming.Clock("motion.undim", Cue.START)))
        player.begin(PanelMotion(o, null, TransitionSpec.ReverseOfOpening, "motion.open", "motion.close", fallback = f))
        player.pull(0)
        at(0.3f, 200)
        player.fallBack(200)
        val v = at(0.2f, 250)
        assertEquals(0.2f, v[Prop.PANEL_EDGE.ordinal], eps)
        assertEquals("half way from the full dim to none", 0.15f, v[Prop.DIM_BELOW.ordinal], eps)
    }

    @Test fun whatFinishesAfterThePanelIsGoneStartsAtItsEndAndKeepsItBusy() {
        val c = custom(progress(Prop.PANEL_EDGE, k(0f, 1f), k(1f, 0f)),
            Track(Prop.CLOCK_LIFT, listOf(Keyframe(0f, 0f), k(1f, 1f)), TrackTiming.Clock("motion.reveal", Cue.END)))
        player.begin(PanelMotion(open, release, TransitionSpec.Own(c), "motion.open", "motion.close"))
        player.pull(0); player.release(0); at(1f, 500)
        player.close(500)
        assertEquals(0f, at(0.5f, 600)[Prop.CLOCK_LIFT.ordinal], eps)
        assertTrue("nothing runs yet but the panel's spring", !player.busy(600))
        player.ended(700)
        assertTrue(player.busy(750))
        assertEquals(0.5f, at(0f, 800)[Prop.CLOCK_LIFT.ordinal], eps)
        assertTrue("done at its end", !player.busy(900))
        assertEquals(1f, at(0f, 900)[Prop.CLOCK_LIFT.ordinal], eps)
    }

    @Test fun busyWhileAReleasesClockRuns() {
        begin()
        player.pull(0)
        assertTrue("the pull's dim (100 ms)", player.busy(50))
        assertTrue(!player.busy(150))
        player.release(1000)
        assertTrue("the reveal (200 ms)", player.busy(1100))
        assertTrue(!player.busy(1300))
        player.landed(1300)
        assertTrue("the undim after the landing (100 ms)", player.busy(1350))
    }

    /** iOS 27's close dim: whole at the close's start, clearing in step with the edge from where it was. */
    private val dimClose = custom(progress(Prop.PANEL_EDGE, k(0f, 1f), k(1f, 0f)),
        Track(Prop.DIM_BELOW, listOf(k(0f, 1f), k(1f, 0f)), TrackTiming.Relative))

    @Test fun aRelativeTrackTakesItsWholeWayFromWhereTheCloseBegan() {
        player.begin(PanelMotion(open, release, TransitionSpec.Own(dimClose), "motion.open", "motion.close"))
        player.pull(0); player.release(0); at(1f, 300); player.landed(300); at(1f, 500)
        assertEquals("undimmed at rest", 0f, at(1f, 500)[Prop.DIM_BELOW.ordinal], eps)
        player.close(500)
        assertEquals("whole at the close's start (not handed over from the 0 that showed)", 0.3f, at(1f, 500)[Prop.DIM_BELOW.ordinal], eps)
        assertEquals(0.15f, at(0.5f, 600)[Prop.DIM_BELOW.ordinal], eps)
        assertEquals(0f, at(0f, 700)[Prop.DIM_BELOW.ordinal], eps)
    }

    @Test fun aRelativeTrackOfACloseThatInterruptsTheLandingScalesFromThere() {
        player.begin(PanelMotion(open, release, TransitionSpec.Own(dimClose), "motion.open", "motion.close"))
        player.pull(0); player.release(0)
        at(0.8f, 50)
        player.close(50)
        assertEquals(0.3f, at(0.8f, 50)[Prop.DIM_BELOW.ordinal], eps)
        assertEquals("half the way from where it began: half the dim", 0.15f, at(0.4f, 150)[Prop.DIM_BELOW.ordinal], eps)
    }

    @Test fun theLayerAfterTheCloseStartsWhenThePanelIsGone() {
        val c = custom(progress(Prop.PANEL_EDGE, k(0f, 1f), k(1f, 0f)),
            Track(Prop.BAR_OPACITY, listOf(Keyframe(0f, 0f, fromCurrent = true), k(1f, 0f)), TrackTiming.Clock("motion.reveal", Cue.START)))
        val after = custom(Track(Prop.BAR_OPACITY, listOf(Keyframe(0f, 0f, fromCurrent = true), k(1f, 1f)), TrackTiming.Clock("motion.reveal", Cue.START)))
        player.begin(PanelMotion(open, release, TransitionSpec.Own(c), "motion.open", "motion.close", afterClose = after))
        player.pull(0); player.release(0); at(1f, 300)
        player.close(300)
        at(0.5f, 400)
        assertEquals("faded out by the close", 0f, at(0f, 500)[Prop.BAR_OPACITY.ordinal], eps)
        player.ended(500)
        assertTrue(player.busy(550))
        assertEquals("back from what showed after it", 0.5f, at(0f, 600)[Prop.BAR_OPACITY.ordinal], eps)
        assertEquals(1f, at(0f, 700)[Prop.BAR_OPACITY.ordinal], eps)
        assertTrue(!player.busy(700))
    }

    @Test fun aRelativeTrackRoundTripsThroughTheFile() {
        val t = TransitionJson.parse(JSONObject("""{"type": "custom", "tracks": {"dim.below": {"progress": "relative", "keys": [{"at": 0, "v": 1}, {"at": 1, "v": 0}]}}}"""))
        assertEquals(TrackTiming.Relative, (t as TransitionSpec.Own).transition.track(Prop.DIM_BELOW)!!.timing)
        assertEquals(t, TransitionJson.parse(JSONObject(TransitionJson.write(t).toString())))
    }

    @Test fun timedTracksRoundTripThroughTheFile() {
        val t = TransitionJson.parse(JSONObject("""{"type": "custom", "tracks": {
            "clock.drop": {"clock": {"role": "motion.nc.clock-drop", "cue": "start", "delay": 30}, "keys": [{"at": 0, "v": "current"}, {"at": 1, "v": 0}]},
            "dim.below": {"clock": {"role": "motion.nc.pull-undim", "cue": "landed"}, "keys": [{"at": 0, "v": 1}, {"at": 1, "v": 0}]},
            "panel.edge": [{"at": 0, "v": 0}, {"at": 1, "v": 1}]}}"""))
        val tr = (t as TransitionSpec.Own).transition
        assertEquals(TrackTiming.Clock("motion.nc.clock-drop", Cue.START, 30f), tr.track(Prop.CLOCK_DROP)!!.timing)
        assertTrue("from the value showing", tr.track(Prop.CLOCK_DROP)!!.fromCurrent)
        assertEquals(TrackTiming.Clock("motion.nc.pull-undim", Cue.LANDED, 0f), tr.track(Prop.DIM_BELOW)!!.timing)
        assertEquals(TrackTiming.Progress, tr.track(Prop.PANEL_EDGE)!!.timing)
        assertEquals(t, TransitionJson.parse(JSONObject(TransitionJson.write(t).toString())))
    }

    @Test fun aClockThatIsNotAMotionRoleOrHasNoneIsRefused() {
        fun refused(s: String, why: String) {
            val e = assertThrows(IllegalArgumentException::class.java) { TransitionJson.parse(JSONObject(s)) }
            assertTrue("'${e.message}' says '$why'", e.message!!.contains(why))
        }
        refused("""{"type": "custom", "tracks": {"clock.drop": {"keys": [{"at": 0, "v": 0}]}}}""", "has a clock")
        refused("""{"type": "custom", "tracks": {"clock.drop": {"clock": {"role": "comp.nc.x"}, "keys": [{"at": 0, "v": 0}]}}}""", "motion role")
        refused("""{"type": "custom", "tracks": {"clock.drop": {"clock": {"role": "motion.a", "cue": "later"}, "keys": [{"at": 0, "v": 0}]}}}""", "no cue 'later'")
        refused("""{"type": "custom", "tracks": {"panel.y": [{"at": 0, "v": "current"}]}}""", "only a clock's first keyframe")
    }
}
