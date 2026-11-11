package dev.launcher.engine.model

import dev.launcher.app.design.Curve
import dev.launcher.engine.kdl.KdlParser
import dev.launcher.engine.kdl.KdlValue
import dev.launcher.engine.kdl.Span
import org.junit.Assert.*
import org.junit.Test

class MotionGrammarTest {
    private fun parse(s: String, key: String = "transition") = MotionGrammar.read(mapOf(key to
        AttrValue.Literal(KdlValue.Str(s, Span(10, 12 + s.length, 2, 3)))))
    private fun spec(s: String, key: String = "transition"): NodeMotionSpec {
        val r = parse(s, key)
        assertEquals(emptyList<Diagnostic>(), r.diagnostics)
        return r.spec!!
    }
    private fun ease(a: Float, b: Float, c: Float, d: Float, ms: Float = 200f) = CurveSpec.Literal(Curve.Ease(a,b,c,d,ms))

    @Test fun acceptsAllCurveFormsAndCssNumbers() {
        for ((text, expected) in listOf(
            "200ms" to ease(0f,0f,.58f,1f), "200ms ease-out" to ease(0f,0f,.58f,1f),
            "200ms ease-in-out" to ease(.42f,0f,.58f,1f), "200ms ease-in" to ease(.42f,0f,1f,1f),
            "200ms linear" to ease(0f,0f,1f,1f),
            "cubic-bezier(0.1, -2, 0.9, 3) 200ms" to ease(.1f,-2f,.9f,3f),
            "spring(stiff)" to CurveSpec.Role("motion.spring.stiff"),
            "spring(0.4, 0.8)" to CurveSpec.Literal(Curve.Spring(.4f,.8f)),
            "@motion.move" to CurveSpec.Role("motion.move")
        )) assertEquals(text, expected, spec("place $text").curveFor(MotionChannel.PLACE))
        for (text in listOf("stiff", "@motion.spring.stiff", "motion.spring.stiff", "spring(stiff)"))
            assertEquals(CurveSpec.Role("motion.spring.stiff"), spec(text, "spring").spring)
        assertEquals(CurveSpec.Literal(Curve.Spring(10f,2f)), spec("spring(10,2)", "spring").spring)
        for (duration in listOf(1,5000)) assertEquals(ease(0f,0f,.58f,1f,duration.toFloat()), spec("size ${duration}ms").curveFor(MotionChannel.SIZE))
    }
    @Test fun channelsClausesAndSpecExample() {
        assertEquals(CurveSpec.Role("motion.move"), spec("place size").curveFor(MotionChannel.SIZE))
        val s = spec("place size @motion.move, opacity 120ms, scale spring(stiff)")
        assertEquals(3, s.transitions.size)
        assertEquals(setOf(MotionChannel.PLACE, MotionChannel.SIZE), s.transitions[0].channels)
        assertEquals(ease(0f,0f,.58f,1f,120f), s.curveFor(MotionChannel.OPACITY))
        assertEquals(CurveSpec.Role("motion.spring.stiff"), s.curveFor(MotionChannel.SCALE))
        assertEquals(MotionChannel.entries.toSet(), spec("value 200ms ease-out").transitions.single().channels)
        assertEquals(CurveSpec.Role("motion.spring.stiff"), spec("stiff", "spring").spring)
    }
    @Test fun precedenceAndAbsentChannels() {
        val span = Span(0,1,1,1)
        val s = NodeMotionSpec(listOf(Transition(setOf(MotionChannel.PLACE), CurveSpec.Role("motion.explicit"), span),
            Transition(setOf(MotionChannel.SIZE, MotionChannel.OPACITY), null, span)), CurveSpec.Role("motion.node"))
        assertEquals(CurveSpec.Role("motion.explicit"), s.curveFor(MotionChannel.PLACE))
        assertEquals(CurveSpec.Role("motion.node"), s.curveFor(MotionChannel.SIZE))
        assertNull(s.curveFor(MotionChannel.SCALE))
        assertEquals(CurveSpec.Role("motion.move"), s.copy(spring=null).curveFor(MotionChannel.SIZE))
        assertEquals(CurveSpec.Role("motion.state"), s.copy(spring=null).curveFor(MotionChannel.OPACITY))
        assertNull(NodeMotionSpec(emptyList(), s.spring).curveFor(MotionChannel.PLACE))
    }
    @Test fun refusalsHaveStableCodesAndSourceSpans() {
        for ((text, code) in listOf(
            "plase" to "motion-unknown-channel", "place 200ms ease-ot" to "motion-unknown-easing",
            "place,place" to "motion-duplicate-channel", "value,size" to "motion-duplicate-channel",
            "" to "motion-empty-clause", "place," to "motion-empty-clause", ",place" to "motion-empty-clause",
            "place 0ms" to "motion-bad-duration", "place 120fms" to "motion-bad-duration", "place 5001ms" to "motion-bad-duration", "place NaNms" to "motion-bad-duration",
            "place cubic-bezier(2,0,1,1) 200ms" to "motion-bad-bezier",
            "place cubic-bezier(0,NaN,1,1) 200ms" to "motion-bad-bezier",
            "place cubic-bezier(0,0,1) 200ms" to "motion-bad-bezier",
            "place cubic-bezier(0,0,1,1) 0ms" to "motion-bad-duration",
            "place spring(0,1)" to "motion-bad-spring", "place spring(11,1)" to "motion-bad-spring",
            "place spring(1,0)" to "motion-bad-spring", "place spring(1,3)" to "motion-bad-spring",
            "place spring(Infinity,1)" to "motion-bad-spring", "place spring(1e-99,1)" to "motion-bad-spring", "place spring(1,1,1)" to "motion-bad-spring",
            "place nope" to "motion-unknown-channel", "place sise @motion.move" to "motion-unknown-channel", "place spring(" to "motion-bad-curve", "{state}" to "not-bindable", "\$state" to "not-bindable",
            "@ink" to "not-bindable",
            "keyframes" to "motion-not-supported", "stagger 20ms" to "motion-not-supported",
            "progress=0.5" to "motion-not-supported", "rotate 200ms" to "motion-not-supported",
            "place spring(stiffness=20 damping=2 mass=1)" to "motion-not-supported",
            "place spring(duration=200 bounce=1)" to "motion-not-supported",
            "place 200ms size 300ms" to "motion-not-supported"
        )) {
            val r = parse(text)
            assertNull(text, r.spec)
            assertTrue("$text: ${r.diagnostics}", r.diagnostics.any { it.code == code })
            val token = when (code) {
                "not-bindable" -> null
                "motion-unknown-channel" -> if (text.startsWith("place ")) text.substringAfter("place ").substringBefore(' ') else "plase"
                "motion-unknown-easing" -> "ease-ot"
                "motion-duplicate-channel" -> text.substringAfter(',')
                "motion-empty-clause" -> ""
                "motion-not-supported" -> when {
                    "stiffness" in text -> "stiffness"
                    "duration=" in text -> "duration"
                    text.startsWith("progress") -> "progress="
                    text.startsWith("place") -> text.substringAfter("place ")
                    else -> text.substringBefore(' ')
                }
                else -> text.substringAfter("place ")
            }
            val at = when {
                token == null -> 0
                code == "motion-duplicate-channel" -> text.lastIndexOf(token)
                code == "motion-empty-clause" -> if (text.startsWith(',')) 0 else text.length
                else -> text.indexOf(token)
            }
            val expectedSpan = if (token == null) Span(10,12 + text.length,2,3)
                else Span(11 + at,11 + at + token.length,2,4 + at)
            assertEquals(text, expectedSpan, r.diagnostics.single().span)
        }
        assertEquals(Span(11,16,2,4), parse("plase").diagnostics.single().span)
        assertEquals("did you mean 'place'?", parse("plase").diagnostics.single().hint)
        assertEquals("did you mean 'ease-out'?", parse("place 200ms ease-ot").diagnostics.single().hint)
        assertEquals("motion-limit", parse("a".repeat(4097)).diagnostics.single().code)
    }
    @Test fun preciseSpansAndConservativeEscapedSpans() {
        for ((text, token) in listOf("  plase" to "plase", "place 200ms ease-ot" to "ease-ot",
            "place, size size" to "size", "place 0ms" to "0ms", "place spring(0,1)" to "spring(0,1)",
            "place cubic-bezier(2,0,1,1) 200ms" to "cubic-bezier(2,0,1,1) 200ms",
            "place nope" to "nope", "rotate 200ms" to "rotate")) {
            val at = if (text == "place, size size") text.lastIndexOf(token) else text.indexOf(token)
            assertEquals(text, Span(11 + at, 11 + at + token.length, 2, 4 + at), parse(text).diagnostics.single().span)
        }
        assertEquals(Span(17,17,2,10), parse("place,").diagnostics.single().span)
        val escaped = AttrValue.Literal(KdlValue.Str("nope", Span(0,20,1,1)))
        assertEquals(escaped.span, MotionGrammar.read(mapOf("transition" to escaped)).diagnostics.single().span)
        val binding = AttrValue.State("x", Span(0,4,1,1))
        assertEquals("motion-literal", MotionGrammar.read(mapOf("spring" to binding)).diagnostics.single().code)
        assertEquals(CurveSpec.Role("motion.spring.stiff"), spec("  stiff  ", "spring").spring)
        assertEquals(CurveSpec.Role("motion.spring.bounce"), spec("place spring(bounce)").curveFor(MotionChannel.PLACE))
        assertEquals(CurveSpec.Role("motion.spring.bounce"), spec("bounce", "spring").spring)
        assertEquals(CurveSpec.Role("motion.bounce"), spec("place @motion.bounce").curveFor(MotionChannel.PLACE))
        for (text in listOf("place 5000.0001ms", "place spring(10.0000001,1)",
            "place cubic-bezier(1.00000001,0,1,1) 200ms")) assertNull(text, parse(text).spec)
    }
    @Test fun readerAndValidatorUseMotionPathBeforeTokensAndExpressions() {
        val doc = KdlParser.parse("""component "c" { view { box transition="value 200ms ease-out" spring="@motion.spring.stiff"; }; }""")
        val c = (Validator(TokenCheck { false }).load(doc) as ReadResult.Ok).component
        assertNotNull(c.view.motion)
        assertEquals(CurveSpec.Role("motion.spring.stiff"), c.view.motion!!.spring)
        for (value in listOf("\$state", "{1+2}", "@ink", "place nope")) {
            val bad = KdlParser.parse("""component "c" { view { box transition="$value"; }; }""")
            assertEquals(if (value == "place nope") "motion-unknown-channel" else "not-bindable",
                (Validator(TokenCheck { false }).load(bad) as ReadResult.Invalid).diagnostics.single().code)
        }
        val bad = KdlParser.parse("""component "c" { view { box spring=1; }; }""")
        assertEquals("motion-literal", (Validator(TokenCheck { false }).load(bad) as ReadResult.Invalid).diagnostics.single().code)
    }
}
