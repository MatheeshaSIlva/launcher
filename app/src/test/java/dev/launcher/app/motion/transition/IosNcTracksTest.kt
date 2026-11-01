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

    @Test fun overAnAppTheBarStays() {
        player.begin(motion("app"))
        player.pull(0)
        for (t in 0L..200L step 17) assertEquals(1f, at(0.7f, t)[Prop.BAR_OPACITY.ordinal], eps)
    }
}
