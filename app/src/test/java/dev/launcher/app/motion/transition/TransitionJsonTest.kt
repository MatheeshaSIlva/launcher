package dev.launcher.app.motion.transition

import dev.launcher.app.design.Entry
import dev.launcher.app.design.Provenance
import dev.launcher.app.design.Theme
import dev.launcher.app.design.Value
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionJsonTest {
    private fun parse(s: String) = TransitionJson.parse(JSONObject(s))
    private fun own(s: String) = (parse(s) as TransitionSpec.Own).transition

    @Test fun aFigmaTypeIsWrittenByNameAndItsTracksAreMade() {
        val t = own("""{"type": "push", "direction": "left", "drive": "release"}""")
        assertEquals(TransitionTypes.opening(TransitionType.PUSH, Direction.LEFT, Drive.RELEASE), t)
        val written = TransitionJson.write(TransitionSpec.Own(t))
        assertFalse("its tracks are not written", written.has("tracks"))
    }

    @Test fun directionAndDriveHaveDefaults() {
        val t = own("""{"type": "dissolve"}""")
        assertEquals(Direction.DOWN, t.direction)
        assertEquals(Drive.FINGER, t.drive)
    }

    @Test fun theReverseOfTheOpening() {
        assertSame(TransitionSpec.ReverseOfOpening, parse("""{"reverse": true}"""))
        assertTrue(TransitionJson.write(TransitionSpec.ReverseOfOpening).getBoolean("reverse"))
    }

    @Test fun aCustomTransitionKeepsEveryKeyframeEaseAndAbsoluteValue() {
        val t = own("""{"type": "custom", "from": "dissolve", "direction": "down", "drive": "release", "tracks": {
            "bg.blur": [{"at": 0, "v": 0, "ease": "ease-out"}, {"at": 1, "v": 12, "abs": true}],
            "content.opacity": [{"at": 0.4, "v": 0, "ease": {"bezier": [0.2, 0, 0, 1]}}, {"at": 0.7, "v": 0.8, "ease": {"power": 0.5, "out": true}},
                                {"at": 1, "v": 1, "ease": "hold"}]}}""")
        assertEquals(TransitionType.CUSTOM, t.type)
        assertEquals(TransitionType.DISSOLVE, t.from)
        val blur = t.track(Prop.BG_BLUR)!!
        assertEquals(Easing.EASE_OUT, blur.keys[0].ease)
        assertTrue(blur.keys[1].absolute)
        val c = t.track(Prop.CONTENT_OPACITY)!!
        assertEquals(Easing.Bezier(0.2f, 0f, 0f, 1f), c.keys[0].ease)
        assertEquals(Easing.Power(0.5f, true), c.keys[1].ease)
        val back = TransitionJson.parse(JSONObject(TransitionJson.write(TransitionSpec.Own(t)).toString()))
        assertEquals("written and read back the same", TransitionSpec.Own(t), back)
    }

    @Test fun everyNamedEaseRoundTrips() {
        for (name in listOf("linear", "hold", "jump", "ease-in", "ease-out", "ease-in-out", "ease-in-back", "ease-out-back", "ease-in-out-back")) {
            val t = own("""{"type": "custom", "tracks": {"panel.y": [{"at": 0, "v": -1, "ease": "$name"}, {"at": 1, "v": 0}]}}""")
            val w = TransitionJson.write(TransitionSpec.Own(t))
            val k = w.getJSONObject("tracks").getJSONArray("panel.y").getJSONObject(0)
            assertEquals("written by its name (linear by leaving it out)", name, if (k.has("ease")) k.getString("ease") else "linear")
            assertEquals(TransitionSpec.Own(t), TransitionJson.parse(w))
        }
    }

    @Test fun mistakesAreRefusedWithTheReason() {
        fun refused(s: String, why: String) {
            val e = assertThrows(IllegalArgumentException::class.java) { parse(s) }
            assertTrue("'${e.message}' says '$why'", e.message!!.contains(why))
        }
        refused("""{"type": "wobble"}""", "no transition type 'wobble'")
        refused("""{"type": "push", "direction": "diagonal"}""", "no direction 'diagonal'")
        refused("""{"type": "push", "tracks": {}}""", "write it as custom")
        refused("""{"type": "custom", "tracks": {"panel.z": [{"at": 0, "v": 0}]}}""", "no property 'panel.z'")
        refused("""{"type": "custom", "tracks": {"panel.y": [{"at": 0, "v": 0, "ease": "wiggle"}]}}""", "no ease 'wiggle'")
        refused("""{"type": "custom", "tracks": {"panel.y": [{"at": 0, "v": 0, "abs": true}]}}""", "only a look can be absolute")
        refused("""{"type": "custom", "from": "custom"}""", "comes from a Figma type")
    }

    /** As a preset file holds it: through the theme reader, the way the launcher loads `assets/motion/`. */
    @Test fun aPresetTokenReadsAndWritesThroughTheThemeFile() {
        val theme = Theme.parse("""{"name": "t", "tokens": {
            "motion.transition.nc.open": {"transition": {"type": "slide-in", "direction": "down"}, "src": "judged:test"},
            "motion.transition.nc.close": {"transition": {"reverse": true}, "src": "judged:test"}}}""")
        val open = theme.entries.getValue("motion.transition.nc.open")
        assertEquals(Value.Trans(TransitionSpec.Own(TransitionTypes.opening(TransitionType.SLIDE_IN, Direction.DOWN))), open.value)
        assertEquals(Value.Trans(TransitionSpec.ReverseOfOpening), theme.entries.getValue("motion.transition.nc.close").value)
        val again = Theme.write(Entry(open.value, Provenance.User))
        assertEquals(open.value, Theme.parse("""{"tokens": {"k": $again}}""").entries.getValue("k").value)
        val bad = assertThrows(IllegalArgumentException::class.java) {
            Theme.parse("""{"tokens": {"motion.transition.x": {"transition": {"type": "nope"}}}}""")
        }
        assertTrue("the token is named in the error: ${bad.message}", bad.message!!.contains("motion.transition.x"))
    }
}
