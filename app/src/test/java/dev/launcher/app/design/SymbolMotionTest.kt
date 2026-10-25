package dev.launcher.app.design

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B-036 slice 2: [SymbolMotion] must equal the builder's own evaluator (`evaluate` in `tools/icons/build_icon_set.py`).
 * `src/test/resources/icon_play/fixture_play.json` is that evaluator's output for `tools/tests/fixtures/icon_play/fixture_on.xml`
 * (and `fixture_clip_play.json` for `fixture_clip.xml`: a clip under a non-root group, sequential tracks): the play itself and,
 * at fixed times, every layer's matrix, path numbers, alphas, stroke width, trim and clips (matrix and path). Regenerate them
 * with `tools/tests/gen_symbol_motion_resources.sh` after changing the fixture or the builder; the numbers are rounded to
 * 5 places there, and compared here within 1e-3.
 */
class SymbolMotionTest {
    private val eps = 1e-3f
    private val fixture = JSONObject(File("src/test/resources/icon_play/fixture_play.json").readText())   // (fixture_clip_play.json too)

    private fun floats(a: JSONArray) = FloatArray(a.length()) { a.getDouble(it).toFloat() }

    private fun assertNear(what: String, want: FloatArray, got: FloatArray, at: Int = 0) {
        for (i in want.indices) assertEquals("$what[$i]", want[i], got[at + i], eps)
    }

    /** One synthetic play: one layer on one group, [tracks] as JSON text. */
    private fun motion(tracks: String, layer: String = """{"n":"l","g":0,"d":"M0 0L1 1"}""", groups: String = """{"n":"g","parent":-1}""") =
        SymbolMotion(JSONObject("""{"ms":100,"groups":[$groups],"layers":[$layer],"tracks":[$tracks]}"""))

    private fun resource(name: String) = JSONObject(File("src/test/resources/icon_play/$name").readText())

    /** The numbers of a path the builder wrote (absolute M, L, C, Z: what a morphing layer starts from before its track). */
    private fun numbersOf(d: String) = Regex("""-?\d+(?:\.\d+)?(?:[eE]-?\d+)?""").findAll(d).map { it.value.toFloat() }.toList().toFloatArray()

    /** Path numbers: the builder's `p` (null before a delayed morph begins: then the layer's own `d`), or nothing if it does not morph. */
    private fun assertPath(at: String, p: JSONArray?, d: String, got: FloatArray?) {
        if (p != null) { assertNotNull("$at path", got); assertNear("$at path", floats(p), got!!) }
        else if (got != null) assertNear("$at base path", numbersOf(d), got)
    }

    private fun crossCheck(file: String) {
        val data = resource(file)
        val m = SymbolMotion(data.getJSONObject("play"))
        val pose = m.newPose()
        val expected = data.getJSONArray("expected")
        assertTrue(expected.length() >= 8)
        for (n in 0 until expected.length()) {
            val e = expected.getJSONObject(n)
            val t = e.getDouble("t").toFloat()
            m.evaluate(t, pose)
            val layers = e.getJSONArray("layers")
            assertEquals(m.layerCount, layers.length())
            for (i in 0 until layers.length()) {
                val l = layers.getJSONObject(i)
                val at = "$file t=$t ${l.getString("n")}"
                assertNear("$at matrix", floats(l.getJSONArray("m")), pose.matrix, i * 6)
                assertPath(at, l.optJSONArray("p"), l.getString("d"), pose.path[i])
                assertEquals("$at fill", l.getDouble("a").toFloat(), pose.fill[i], eps)
                assertEquals("$at stroke alpha", l.getDouble("sa").toFloat(), pose.stroke[i], eps)
                assertEquals("$at width", l.getDouble("s").toFloat(), pose.width[i], eps)
                assertNear("$at trim", floats(l.getJSONArray("trim")), pose.trim, i * 3)
                val clips = l.getJSONArray("clip")
                assertEquals("$at clips", clips.length(), m.layerClips[i].size)
                for (j in 0 until clips.length()) {
                    val c = clips.getJSONObject(j); val idx = m.layerClips[i][j]
                    assertNear("$at clip $j matrix", floats(c.getJSONArray("m")), pose.clipMatrix, idx * 6)
                    assertPath("$at clip $j", c.optJSONArray("p"), c.getString("d"), pose.clipPath[idx])
                }
            }
        }
    }

    @Test fun matchesTheBuildersValuesAtEveryReferenceTime() = crossCheck("fixture_play.json")

    /** A clip under a non-root group morphed by a delayed track; two sequential tracks on a rotation and on a path. */
    @Test fun matchesTheBuildersValuesForAClipUnderAGroupAndSequentialTracks() = crossCheck("fixture_clip_play.json")

    @Test fun theSecondFixtureReallyMovesItsClipAndItsSequentialTracks() {
        val data = resource("fixture_clip_play.json")
        val m = SymbolMotion(data.getJSONObject("play"))
        val pose = m.newPose()
        assertEquals(1, m.layerClips[0].size)
        assertTrue("the clip sits under a non-root group", m.clipGroup[0] >= 0)
        m.evaluate(50f, pose); val early = pose.clipPath[0]!!.copyOf()      // before the delayed morph: its base
        m.evaluate(200f, pose); val mid = pose.clipPath[0]!!.copyOf()
        m.evaluate(200f, pose)
        assertTrue("the clip morphs after its delay", early.indices.any { Math.abs(early[it] - mid[it]) > 0.5f })
        val turned = pose.clipMatrix.copyOf()
        m.evaluate(50f, pose)
        assertTrue("the clip's matrix follows the turning group", (0 until 6).any { Math.abs(turned[it] - pose.clipMatrix[it]) > 0.05f })
        val tracks = data.getJSONObject("play").getJSONArray("tracks")
        val rotations = (0 until tracks.length()).map { tracks.getJSONObject(it) }.filter { it.getString("p") == "rotation" }
        val paths = (0 until tracks.length()).map { tracks.getJSONObject(it) }.filter { it.getString("p") == "pathData" && it.getString("k") == "l" }
        assertEquals(2, rotations.size); assertEquals(2, paths.size)
        assertEquals(rotations[0].getInt("at") + rotations[0].getInt("ms"), rotations[1].getInt("at"))
        assertEquals(paths[0].getInt("at") + paths[0].getInt("ms"), paths[1].getInt("at"))
    }

    @Test fun layersCarryTheirCapJoinAndFillRule() {
        val m = SymbolMotion(JSONObject("""{"ms":0,"groups":[],"layers":[{"n":"a","g":-1,"d":"M0 0L1 1"},
            {"n":"b","g":-1,"d":"M0 0L1 1","cap":"round","join":"bevel","even":1},{"n":"c","g":-1,"d":"M0 0L1 1","cap":"square","join":"round"}],"tracks":[]}"""))
        assertEquals(listOf(0, 1, 2), m.layerCap.toList())
        assertEquals(listOf(0, 2, 1), m.layerJoin.toList())
        assertEquals(listOf(false, true, false), m.layerEvenOdd.toList())
    }

    @Test fun anUnknownCapOrJoinIsABadFileNotAButtOrMiter() {
        for (bad in listOf("\"cap\":\"zigzag\"", "\"join\":\"sharp\"")) {
            val threw = try { SymbolMotion(JSONObject("{\"ms\":0,\"groups\":[],\"layers\":[{\"n\":\"a\",\"g\":-1,\"d\":\"M0 0L1 1\",$bad}],\"tracks\":[]}")); false } catch (_: IllegalArgumentException) { true }
            assertTrue(bad, threw)
        }
    }

    @Test fun theFixtureCoversTheHardParts() {
        // (so that the comparison above means something: delay, overshoot, motion path, morph, group at scale 0)
        val play = fixture.getJSONObject("play")
        val props = (0 until play.getJSONArray("tracks").length()).map { play.getJSONArray("tracks").getJSONObject(it).getString("p") }
        for (p in listOf("rotation", "translateXY", "pathData", "strokeWidth", "trimPathEnd", "fillAlpha", "scaleX")) assertTrue(p, p in props)
        val e = (0 until play.getJSONArray("tracks").length()).map { play.getJSONArray("tracks").getJSONObject(it).get("e") }
        assertTrue("an overshoot", e.any { it is JSONArray && it.getDouble(1) > 1.0 })
        assertTrue("accdec", e.any { it == "accdec" })
    }

    @Test fun groupsComposeParentFirst() {
        // Child: pivot (0,0), translate (10,0); parent: pivot (0,0), rotate 90. A point of the child moves 10 along x, then the
        // parent turns it onto y: (1,0) -> (11,0) -> (0,11). Composed the other way it would land on (10,1).
        val m = SymbolMotion(JSONObject("""{"ms":0,"groups":[{"n":"p","parent":-1,"rotate":90},{"n":"c","parent":0,"translate":[10,0]}],
            "layers":[{"n":"l","g":1,"d":"M0 0L1 0"}],"tracks":[]}"""))
        val pose = m.newPose()
        m.evaluate(0f, pose)
        val x = pose.matrix[0] * 1f + pose.matrix[1] * 0f + pose.matrix[2]
        val y = pose.matrix[3] * 1f + pose.matrix[4] * 0f + pose.matrix[5]
        assertEquals(0f, x, eps)
        assertEquals(11f, y, eps)
    }

    @Test fun aGroupTurnsAboutItsPivotAndScalesBeforeItTurns() {
        val m = motion("", groups = """{"n":"g","parent":-1,"pivot":[4,4],"scale":[2,1],"rotate":90}""")
        val pose = m.newPose()
        m.evaluate(0f, pose)
        // (6,4): 2 right of the pivot, scaled to 4, turned onto +y: (4, 8).
        assertEquals(4f, pose.matrix[0] * 6 + pose.matrix[1] * 4 + pose.matrix[2], eps)
        assertEquals(8f, pose.matrix[3] * 6 + pose.matrix[4] * 4 + pose.matrix[5], eps)
    }

    @Test fun aLayerWithoutAGroupHasTheIdentity() {
        val m = SymbolMotion(JSONObject("""{"ms":0,"groups":[],"layers":[{"n":"l","g":-1,"d":"M0 0L1 1"}],"tracks":[]}"""))
        val pose = m.newPose()
        m.evaluate(0f, pose)
        assertNear("identity", floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f), pose.matrix)
    }

    @Test fun aGroupAtScaleZeroIsHiddenUntilItsDelayedTrackBegins() {
        val m = motion("""{"k":"g","i":0,"p":"scaleX","at":50,"ms":50,"e":[0,0,1,1],"from":0,"to":1}""",
            groups = """{"n":"g","parent":-1,"scale":[0,1]}""")
        val pose = m.newPose()
        m.evaluate(49f, pose); assertEquals(0f, pose.matrix[0], eps)
        m.evaluate(75f, pose); assertEquals(0.5f, pose.matrix[0], eps)
        m.evaluate(500f, pose); assertEquals(1f, pose.matrix[0], eps)   // held at its end
    }

    @Test fun accelerateDecelerateIsACosineHalfwayAtHalf() {
        val m = motion("""{"k":"l","i":0,"p":"strokeWidth","at":0,"ms":100,"e":"accdec","from":0,"to":10}""")
        val pose = m.newPose()
        for ((t, want) in listOf(0f to 0f, 25f to 10 * (0.5f - 0.5f * Math.cos(Math.PI / 4).toFloat()), 50f to 5f, 100f to 10f)) {
            m.evaluate(t, pose)
            assertEquals("t=$t", want, pose.width[0], eps)
        }
    }

    @Test fun aCubicEaseIsSolvedForXAndOvershootsUnclamped() {
        // Android's overshoot (0.26, 1.5, 0.27, 1) on a rotation: it goes past its end, then settles.
        val m = motion("""{"k":"g","i":0,"p":"rotation","at":0,"ms":100,"e":[0.26,1.5,0.27,1],"from":0,"to":90}""")
        val pose = m.newPose()
        var lowest = 1f
        for (t in 0..100) { m.evaluate(t.toFloat(), pose); lowest = minOf(lowest, pose.matrix[0]) }
        assertTrue("turned past 90 degrees (cosine below 0): $lowest", lowest < -0.01f)
        m.evaluate(100f, pose)
        assertEquals(0f, pose.matrix[0], eps)
        assertEquals(1f, pose.matrix[3], eps)
    }

    @Test fun alphasAreClampedAndWidthNeverNegative() {
        val layer = """{"n":"l","g":0,"d":"M0 0L1 1","a":0.5,"s":1,"sa":0.5}"""
        val m = motion("""{"k":"l","i":0,"p":"fillAlpha","at":0,"ms":100,"e":[0,0,1,1],"from":0.5,"to":2},
            {"k":"l","i":0,"p":"strokeAlpha","at":0,"ms":100,"e":[0,0,1,1],"from":0.5,"to":-1},
            {"k":"l","i":0,"p":"strokeWidth","at":0,"ms":100,"e":[0,0,1,1],"from":1,"to":-3}""", layer)
        val pose = m.newPose()
        m.evaluate(100f, pose)
        assertEquals(1f, pose.fill[0], 0f)
        assertEquals(0f, pose.stroke[0], 0f)
        assertEquals(0f, pose.width[0], 0f)
        m.evaluate(50f, pose)
        assertEquals(1f, pose.fill[0], 0f)        // 1.25 clamped
        assertEquals(0f, pose.stroke[0], 0f)      // -0.25 clamped
        assertEquals(0f, pose.width[0], 0f)       // -1 up to 0
    }

    @Test fun trimIsNotClamped() {
        val m = motion("""{"k":"l","i":0,"p":"trimPathEnd","at":0,"ms":100,"e":[0,0,1,1],"from":0,"to":2}""")
        val pose = m.newPose()
        m.evaluate(100f, pose)
        assertEquals(2f, pose.trim[1], eps)
    }

    @Test fun aMotionPathWalksItsLengthAndGoesStraightOnPastItsEnds() {
        // An L of 10 right then 10 down (lengths 0, 10, 20); linear in time, then past 1 it carries on down, before 0 it would go left.
        val m = motion("""{"k":"g","i":0,"p":"translateXY","at":0,"ms":100,"e":[0,0,1,1],"pts":[0,0,10,0,10,10],"len":[0,10,20]}""")
        val pose = m.newPose()
        for ((t, xy) in listOf(0f to (0f to 0f), 25f to (5f to 0f), 50f to (10f to 0f), 75f to (10f to 5f), 100f to (10f to 10f))) {
            m.evaluate(t, pose)
            assertEquals("x at $t", xy.first, pose.matrix[2], eps)
            assertEquals("y at $t", xy.second, pose.matrix[5], eps)
        }
        val e = motion("""{"k":"g","i":0,"p":"translateXY","at":0,"ms":100,"e":[0.3,2,0.7,1],"pts":[0,0,10,0,10,10],"len":[0,10,20]}""")
        val ep = e.newPose()
        var maxY = 0f
        for (t in 0..100) { e.evaluate(t.toFloat(), ep); maxY = maxOf(maxY, ep.matrix[5]) }
        assertTrue("overshoot continues down the last piece: $maxY", maxY > 10f + eps)
    }

    @Test fun aMorphKeepsItsCommandsAndBlendsItsNumbers() {
        val layer = """{"n":"l","g":0,"d":"M0 0L10 0C1 2 3 4 5 6Z"}"""
        val m = motion("""{"k":"l","i":0,"p":"pathData","at":0,"ms":100,"e":[0,0,1,1],"from":[0,0,10,0,1,2,3,4,5,6],"to":[2,2,20,0,3,4,5,6,7,8]}""", layer)
        assertEquals(listOf(0, 1, 2, 3), m.layerCmds[0]!!.toList())
        val pose = m.newPose()
        m.evaluate(50f, pose)
        assertNear("path", floatArrayOf(1f, 1f, 15f, 0f, 2f, 3f, 4f, 5f, 6f, 7f), pose.path[0]!!)
    }

    @Test fun evaluateIsRepeatable() {
        // (a pose is rewritten from the base every time: nothing left over from an earlier time)
        val m = SymbolMotion(fixture.getJSONObject("play"))
        val pose = m.newPose(); val again = m.newPose()
        m.evaluate(400f, pose); m.evaluate(120f, pose); m.evaluate(260f, pose)
        m.evaluate(260f, again)
        assertNear("matrix", pose.matrix, again.matrix)
        assertNear("trim", pose.trim, again.trim)
    }

    @Test fun everyShippedPlayParsesAndEvaluatesFiniteAndEndsOnItsStaticLook() {
        val icons = JSONObject(File("src/main/assets/icons/pixel.json").readText()).getJSONObject("icons")
        var plays = 0
        for (name in icons.keys()) {
            val icon = icons.getJSONObject(name)
            val play = icon.optJSONObject("play") ?: continue
            plays++
            val m = SymbolMotion(play)
            val pose = m.newPose()
            for (t in listOf(0f, m.ms / 3, m.ms / 2, m.ms, m.ms * 2)) {
                m.evaluate(t, pose)
                for (a in listOf(pose.matrix, pose.fill, pose.stroke, pose.width, pose.trim, pose.clipMatrix))
                    assertTrue("$name t=$t", a.all { it.isFinite() })
                for (p in pose.path + pose.clipPath) assertTrue("$name path t=$t", p == null || p.all { it.isFinite() })
            }
            // At its end it draws what the symbol's static layers draw: the same count of shown paths, each on the same matrix.
            m.evaluate(m.ms, pose)
            val shown = (0 until m.layerCount).filter { i ->
                (pose.fill[i] > 0.004f || (pose.width[i] > 0f && pose.stroke[i] > 0.004f)) && Math.abs(pose.trim[i * 3 + 1] - pose.trim[i * 3]) >= 0.001f
            }
            val statics = icon.getJSONArray("layers")
            assertEquals("$name: paths shown at the end", statics.length(), shown.size)
            for ((n, i) in shown.withIndex()) {
                val sm = statics.getJSONObject(n).optJSONArray("m")?.let { floats(it) } ?: floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)
                assertNear("$name layer $i matrix", sm, pose.matrix, i * 6)
                // B-047: the same fill rule, and (for a stroke that draws) the same cap and join, as the static layer draws.
                val st = statics.getJSONObject(n)
                assertEquals("$name layer $i fill rule", st.optInt("even", 0) == 1, m.layerEvenOdd[i])
                if (pose.width[i] > 0f && pose.stroke[i] > 0.004f) {
                    assertEquals("$name layer $i cap", listOf("butt", "round", "square").indexOf(st.optString("cap", "butt")), m.layerCap[i])
                    assertEquals("$name layer $i join", listOf("miter", "round", "bevel").indexOf(st.optString("join", "miter")), m.layerJoin[i])
                }
            }
        }
        assertTrue("the Pixel set carries plays: $plays", plays >= 15)
    }

    @Test fun refusesPlaysThatDoNotAddUp() {
        for (bad in listOf(
            """{"k":"l","i":3,"p":"fillAlpha","at":0,"ms":10,"e":"accdec","from":0,"to":1}""",        // no such layer
            """{"k":"g","i":0,"p":"fillAlpha","at":0,"ms":10,"e":"accdec","from":0,"to":1}""",        // a group has no fill
            """{"k":"l","i":0,"p":"pathData","at":0,"ms":10,"e":"accdec","from":[1,2],"to":[1,2]}""",  // numbers differ from the layer's
        )) {
            val threw = try { motion(bad); false } catch (_: Exception) { true }
            assertTrue(bad, threw)
        }
        assertNotNull(motion("""{"k":"l","i":0,"p":"fillAlpha","at":0,"ms":10,"e":"accdec","from":0,"to":1}"""))
    }

    /** Every play the shipped sets carry, evaluated at every 1/120 s step (and past its end): built, finite, within its alphas, never a throw. */
    @Test fun everyShippedPlayEvaluatesFinite() {
        for (file in File("src/main/assets/icons").listFiles { f -> f.name.endsWith(".json") }!!) {
            val icons = JSONObject(file.readText()).getJSONObject("icons")
            for (name in icons.keys()) {
                val play = icons.getJSONObject(name).optJSONObject("play") ?: continue
                val m = SymbolMotion(play)
                val pose = m.newPose()
                var t = -8.333f
                while (t < m.ms + 100f) {
                    m.evaluate(t, pose)
                    val all = listOf(pose.matrix, pose.fill, pose.stroke, pose.width, pose.trim) + pose.path.filterNotNull() + pose.clipMatrix + pose.clipPath.filterNotNull()
                    for (a in all) for (v in a) assertTrue("${file.name}/$name at $t ms: $v", v.isFinite())
                    t += 8.3333f
                }
            }
        }
    }
}
