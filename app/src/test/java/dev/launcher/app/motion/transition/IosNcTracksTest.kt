package dev.launcher.app.motion.transition

import dev.launcher.app.design.Theme
import dev.launcher.app.design.Value
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * iOS 27's Notification Center as the preset writes it (`assets/motion/ios27.json`, `motion.transition.nc.*`), played the
 * way the shade plays it (step 2b): the choreography's shape, on even clocks (the real roles' curves are the shade's).
 */
class IosNcTracksTest {
    private val eps = 1e-4f
    private val tokens = Theme.parse(File("src/main/assets/motion/ios27.json").readText()).entries
    private val player = PanelPlayer { RoleClock.linear(100f) }
    private val theme = FloatArray(Prop.entries.size) { 1f }
    private val out = FloatArray(Prop.entries.size)

    /** The most specific variant the preset has, as the shade picks it. */
    private fun spec(conn: String, place: String, bg: String = "live"): TransitionSpec? {
        for (k in listOf("$conn.$place.$bg", "$conn.$place", "$conn.$bg", conn))
            (tokens["motion.transition.nc.$k"]?.value as? Value.Trans)?.let { return it.spec }
        return null
    }

    private fun motion(place: String, bg: String = "live"): PanelMotion {
        fun own(c: String) = (spec(c, place, bg) as? TransitionSpec.Own)?.transition
        return PanelMotion(own("open")!!, own("release"), spec("close", place, bg)!!, "motion.nc.open", "motion.nc.close", own("fallback"), own("after-close"))
    }

    private fun at(p: Float, now: Long): FloatArray { player.evaluate(p, now, theme, out); return out }

    /** Pulled to 0.7 and held, let go open, landed, at rest, then closed with the edge rising evenly. */
    private fun openThenClose(place: String) {
        player.begin(motion(place))
        player.pull(0)
        for (t in 0L..1000L step 17) at(0.7f, t)
        player.release(1000)
        for (t in 1000L..1300L step 17) at(0.7f + 0.3f * (t - 1000) / 300f, t)
        player.landed(1300)
        for (t in 1300L..3000L step 17) at(1f, t)
        assertEquals("$place: undimmed at rest", 0f, at(1f, 3000)[Prop.DIM_BELOW.ordinal], eps)
        assertEquals("$place: the content arrived", 1f, out[Prop.CONTENT_OPACITY.ordinal], eps)
        player.close(3000)
        for (i in 0..30) {
            val p = 1f - i / 30f
            val v = at(p, 3000L + i * 17L)
            assertEquals("$place: the close's dim is whole at its start and clears with the edge (p = $p)", p, v[Prop.DIM_BELOW.ordinal], 1e-3f)
        }
    }

    @Test fun overAnAppTheCloseDimClearsWithTheEdge() = openThenClose("app")

    @Test fun overHomeTheCloseDimClearsWithTheEdge() = openThenClose("home")

    @Test fun overHomeTheBarFadesWhilePulledAndComesBackOnRelease() {
        player.begin(motion("home"))
        player.pull(0)
        for (t in 0L..200L step 17) at(0.7f, t)
        assertEquals(0f, out[Prop.BAR_OPACITY.ordinal], eps)
        player.release(200)
        for (t in 200L..400L step 17) at(1f, t)
        assertEquals(1f, out[Prop.BAR_OPACITY.ordinal], eps)
    }

    /** Over a live blur the dim has cleared with the edge when the sheet goes: after the close it stays clear (no flash). */
    @Test fun afterTheCloseTheDimDoesNotComeBack() {
        for (place in listOf("home", "app")) {
            player.begin(motion(place))
            player.pull(0)
            for (t in 0L..200L step 17) at(0.7f, t)
            player.release(200)
            for (t in 200L..500L step 17) at(1f, t)
            player.landed(500)
            for (t in 500L..1000L step 17) at(1f, t)
            player.close(1000)
            for (i in 0..30) at(1f - i / 30f, 1000L + i * 17L)
            player.ended(1510)
            for (t in 1510L..1900L step 17)
                assertEquals("$place: the dim at ${t - 1510} ms after the close", 0f, at(0f, t)[Prop.DIM_BELOW.ordinal], 1e-3f)
        }
    }

    /** Over the still wallpaper the dim stays whole under the closing sheet, then gives way once it has gone. */
    @Test fun overTheWallpaperTheDimGivesWayAfterTheClose() {
        player.begin(motion("home", "wallpaper"))
        player.pull(0)
        for (t in 0L..200L step 17) at(0.7f, t)
        player.release(200)
        for (t in 200L..500L step 17) at(1f, t)
        player.landed(500)
        for (t in 500L..1000L step 17) at(1f, t)
        player.close(1000)
        for (i in 0..30) at(1f - i / 30f, 1000L + i * 17L)
        assertEquals(1f, out[Prop.DIM_BELOW.ordinal], eps)
        player.ended(1510)
        assertEquals("whole as the sheet goes", 1f, at(0f, 1510)[Prop.DIM_BELOW.ordinal], eps)
        assertEquals("halfway", 0.5f, at(0f, 1560)[Prop.DIM_BELOW.ordinal], 1e-3f)
        assertEquals("gone", 0f, at(0f, 1610)[Prop.DIM_BELOW.ordinal], eps)
    }

    /** The sheet's edge is the pull, opening and closing, wherever it opens (the view drew it from the progress before). */
    @Test fun theEdgeFollowsThePull() {
        for (place in listOf("home", "app", "lock")) {
            val m = motion(place)
            assertEquals(true, m.sheetEdge)
            player.begin(m)
            player.pull(0)
            for (i in 0..12) assertEquals("$place pulled", i * 0.05f, at(i * 0.05f, i * 17L)[Prop.PANEL_EDGE.ordinal], 1e-4f)
            player.release(204)
            for (i in 0..10) assertEquals("$place opening", 0.6f + i * 0.04f, at(0.6f + i * 0.04f, 204L + i * 17L)[Prop.PANEL_EDGE.ordinal], 1e-4f)
            player.landed(400)
            for (t in 400L..900L step 17) at(1f, t)
            player.close(900)
            for (i in 0..20) assertEquals("$place closing", 1f - i * 0.05f, at(1f - i * 0.05f, 900L + i * 17L)[Prop.PANEL_EDGE.ordinal], 1e-4f)
        }
    }

    /** One UI's sheet blur and veil: none under the clear slab, in with the content at the commit, held as the sheet rises. */
    @Test fun theSheetsOwnBlurComesInAtTheCommitAndStaysThroughTheClose() {
        player.begin(motion("app"))
        player.pull(0)
        for (t in 0L..200L step 17) assertEquals(0f, at(0.7f, t)[Prop.BG_BLUR.ordinal], eps)
        player.release(204)
        assertEquals("from nothing", 0f, at(0.7f, 204)[Prop.BG_BLUR.ordinal], eps)
        assertEquals("halfway on its clock", 0.5f, at(0.85f, 254)[Prop.BG_BLUR.ordinal], 1e-3f)
        for (t in 254L..600L step 17) at(1f, t)
        assertEquals(1f, out[Prop.BG_BLUR.ordinal], eps)
        assertEquals(1f, out[Prop.BG_VEIL.ordinal], eps)
        player.landed(600)
        player.close(700)
        for (i in 0..20) assertEquals("closing", 1f, at(1f - i * 0.05f, 700L + i * 17L)[Prop.BG_BLUR.ordinal], eps)
    }

    @Test fun aFigmaTypeMovesTheFrameNotTheEdge() {
        val m = PanelMotion(TransitionTypes.opening(TransitionType.MOVE_IN, Direction.DOWN), null, TransitionSpec.ReverseOfOpening,
            "motion.nc.open", "motion.nc.close")
        assertEquals(false, m.sheetEdge)
        player.begin(m)
        player.pull(0)
        val v = at(0.25f, 17)
        assertEquals("the edge whole", 1f, v[Prop.PANEL_EDGE.ordinal], eps)
        assertEquals("the frame a quarter of the way down", -0.75f, v[Prop.PANEL_Y.ordinal], eps)
        assertEquals("the content there", 1f, v[Prop.CONTENT_OPACITY.ordinal], eps)
    }

    @Test fun overAnAppTheBarStays() {
        player.begin(motion("app"))
        player.pull(0)
        for (t in 0L..200L step 17) assertEquals(1f, at(0.7f, t)[Prop.BAR_OPACITY.ordinal], eps)
    }
}
