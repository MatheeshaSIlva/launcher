package dev.launcher.engine.model

import dev.launcher.app.design.Curve
import dev.launcher.engine.expr.Compiled
import dev.launcher.engine.expr.Env
import dev.launcher.engine.expr.Value
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

    @Test fun radiusChannelIncludesValueAndUsesSizePrecedence() {
        val node = CurveSpec.Role("motion.node")
        assertEquals(setOf(MotionChannel.SIZE, MotionChannel.RADIUS),
            spec("size radius spring(0.4,0.8)").transitions.single().channels)
        assertEquals(CurveSpec.Literal(Curve.Spring(.4f,.8f)),
            spec("size radius spring(0.4,0.8)").copy(spring=node).curveFor(MotionChannel.RADIUS))
        assertEquals(ease(0f,0f,.58f,1f), spec("value 200ms").curveFor(MotionChannel.RADIUS))
        assertEquals(CurveSpec.Role("motion.move"), spec("radius").curveFor(MotionChannel.RADIUS))
        assertEquals(node, spec("radius").copy(spring=node).curveFor(MotionChannel.RADIUS))
        assertNull(NodeMotionSpec(emptyList(), node).curveFor(MotionChannel.RADIUS))
        assertNull(spec("size").copy(spring=node).curveFor(MotionChannel.RADIUS))
    }

    @Test fun radiusChannelRefusalsHaveExactSpans() {
        for ((text, token, code) in listOf(
            Triple("radius,radius", "radius", "motion-duplicate-channel"),
            Triple("value,radius", "radius", "motion-duplicate-channel"),
            Triple("radius,value", "value", "motion-duplicate-channel"),
            Triple("raduis", "raduis", "motion-unknown-channel"),
            Triple("radius 0ms", "0ms", "motion-bad-duration")
        )) {
            val d = parse(text).diagnostics.single()
            val at = text.lastIndexOf(token)
            assertEquals(code, d.code)
            assertEquals(Span(11 + at, 11 + at + token.length, 2, 4 + at), d.span)
            assertTrue(d.message.isNotBlank())
        }
        assertEquals("did you mean 'radius'?", parse("raduis").diagnostics.single().hint)
        for (key in listOf("enter", "exit"))
            assertEquals("motion-presence-channel", parse("radius", key).diagnostics.single().code)
    }

    @Test fun radiusSchemaAcceptsLengthsAndFullAcrossPrimitives() {
        for (primitive in Primitives.ALL.keys) for (value in listOf("0", "-2", "2.5", "(pt)8", "(dp)8", "(px)8", "\"full\"")) {
            val view = if (primitive == "spacer") "row { spacer radius=$value; }" else "$primitive radius=$value"
            val s = """component "c" { view { $view; }; }"""
            val result = Validator(TokenCheck { false }).load(KdlParser.parse(s))
            assertTrue("$s: $result", result is ReadResult.Ok)
        }
        assertSame(AttrType.LENGTH, Primitives.ALL.getValue("box").attrs.getValue("width").type)
    }

    @Test fun radiusLiteralRefusalsHaveReasonsAndValueSpans() {
        for (value in listOf("\"pill\"", "\"Full\"", "\"full \"", "\"8\"", "#true", "#false", "#null", "(sp)8", "(dp)\"full\"")) {
            val s = """component "c" { view { box radius=$value; }; }"""
            val d = (Validator(TokenCheck { false }).load(KdlParser.parse(s)) as ReadResult.Invalid).diagnostics.single()
            val token = value.substringAfter(')', value)
            val at = s.indexOf(value) + value.length - token.length
            assertEquals(value, "wrong-type", d.code)
            assertEquals(value, Span(at, at + token.length, 1, at + 1), d.span)
            assertTrue(d.message.contains("length or \"full\""))
        }
        val s = """component "c" { view { box width="full"; }; }"""
        assertEquals("wrong-type", (Validator(TokenCheck { false }).load(KdlParser.parse(s)) as ReadResult.Invalid).diagnostics.single().code)
    }

    @Test fun radiusBindingsKeepFullStringOrLengthExpressionValues() {
        var pill = true
        val env = object : Env {
            override fun state(name: String) = if (name == "pill") Value.Bool.of(pill) else Value.Str("full")
            override fun prop(name: String) = Value.Str("full")
            override fun token(key: String) = Value.Str("full")
            override fun source(path: String) = Value.Null
            override fun now() = 0L
        }
        for (binding in listOf("\$r", "@r", "{\$pill ? 'full' : 8dp}")) {
            val s = """component "c" { props { r type=string default="full"; }; state { r "full"; pill #true; }; view { box radius="$binding"; }; }"""
            assertTrue(Validator(TokenCheck { false }).load(KdlParser.parse(s)) is ReadResult.Ok)
        }
        val compiled = Compiled.of("{\$pill ? 'full' : 8dp}", emptySet())
        assertEquals(Value.Str("full"), compiled.value(env))
        pill = false
        assertEquals(Value.Num(8.0, "dp"), compiled.value(env))
    }

    @Test fun rollBooleansAndCurvePrecedence() {
        fun bool(enabled: Boolean) = MotionGrammar.read(mapOf("roll" to
            AttrValue.Literal(KdlValue.Bool(enabled, Span(0, 5, 1, 1))))).let {
            assertTrue(it.diagnostics.isEmpty()); it.spec!!
        }
        val node = CurveSpec.Role("motion.node")
        assertEquals(CurveSpec.Role("motion.text.roll"), bool(true).rollCurve())
        assertEquals(node, bool(true).copy(spring=node).rollCurve())
        assertNull(bool(false).rollCurve())
        assertNull(bool(false).copy(spring=node).rollCurve())
        assertNull(NodeMotionSpec(emptyList(), node).rollCurve())
        for (curve in listOf("@motion.text.roll", "200ms", "200ms ease-out", "200ms ease-in",
            "200ms ease-in-out", "200ms linear", "cubic-bezier(0.1,-2,0.9,3) 220ms",
            "spring(stiff)", "spring(0.4,0.8)")) {
            val s = spec(curve, "roll")
            val expected = spec("place $curve").curveFor(MotionChannel.PLACE)
            assertEquals(curve, expected, s.rollCurve())
            assertEquals(curve, expected, s.copy(spring=node).rollCurve())
            assertTrue(s.transitions.isEmpty())
        }
    }
    @Test fun rollRefusalsHaveReasonsAndSpans() {
        for ((text, code, token) in listOf(
            Triple("200ms stagger 24ms", "motion-not-supported", "stagger"),
            Triple("up 200ms", "motion-not-supported", "up"),
            Triple("200ms down", "motion-not-supported", "down"),
            Triple("true", "motion-bad-curve", "true"),
            Triple("", "motion-bad-curve", ""),
            Triple("0ms", "motion-bad-duration", "0ms"),
            Triple("5001ms", "motion-bad-duration", "5001ms"),
            Triple("200ms ease-ot", "motion-unknown-easing", "ease-ot"),
            Triple("spring(0,1)", "motion-bad-spring", "spring(0,1)"),
            Triple("cubic-bezier(2,0,1,1) 200ms", "motion-bad-bezier", "cubic-bezier(2,0,1,1) 200ms"),
            Triple("\$state", "not-bindable", "\$state"),
            Triple("{1+2}", "not-bindable", "{1+2}"),
            Triple("@ink", "not-bindable", "@ink")
        )) {
            val r = parse(text, "roll")
            assertNull(text, r.spec)
            val d = r.diagnostics.single()
            assertEquals(text, code, d.code)
            assertTrue(d.message.isNotBlank())
            val at = text.indexOf(token)
            assertEquals(text, if (code == "not-bindable") Span(10, 12 + text.length, 2, 3)
                else Span(11 + at, 11 + at + token.length, 2, 4 + at), d.span)
            if (code == "motion-not-supported") assertTrue(d.message.contains("not yet supported"))
        }
        val bounded = "200ms" + " ".repeat(4091)
        assertNotNull(spec(bounded, "roll"))
        val d = parse(bounded + " ", "roll").diagnostics.single()
        assertEquals("motion-limit", d.code)
        assertEquals(Span(10, 4109, 2, 3), d.span)
        // Direction-like role/spring names are still valid curve names.
        assertEquals(CurveSpec.Role("motion.up"), spec("@motion.up", "roll").rollCurve())
        assertEquals(CurveSpec.Role("motion.spring.down"), spec("spring(down)", "roll").rollCurve())
    }
    @Test fun readerAndValidatorUseRollMotionPathAndRefuseOtherPrimitives() {
        fun source(primitive: String, value: String, extra: String = "") =
            """component "c" { view { $primitive roll=$value $extra; }; }"""
        fun load(s: String) = Validator(TokenCheck { false }).load(KdlParser.parse(s))
        for ((value, expected) in listOf("#true" to CurveSpec.Role("motion.text.roll"), "#false" to null,
            "\"@motion.own\"" to CurveSpec.Role("motion.own"))) {
            val c = (load(source("text", value)) as ReadResult.Ok).component
            assertEquals(expected, c.view.motion!!.rollCurve())
        }
        assertEquals(CurveSpec.Role("motion.spring.stiff"),
            (load(source("text", "#true", "spring=\"stiff\"")) as ReadResult.Ok).component.view.motion!!.rollCurve())
        for (primitive in Primitives.ALL.keys - "text") for (value in listOf("#true", "#false", "\"200ms\"")) {
            // spacer needs a row parent for its ordinary placement rule.
            val s = if (primitive == "spacer") """component "c" { view { row { spacer roll=$value; }; }; }"""
                else source(primitive, value)
            val d = (load(s) as ReadResult.Invalid).diagnostics.single()
            assertEquals("motion-roll-not-text", d.code)
            assertTrue(d.message.contains("only on text"))
            val at = s.indexOf(value)
            assertEquals(Span(at, at + value.length, 1, at + 1), d.span)
        }
        for ((value, code) in listOf("\"\$state\"" to "not-bindable", "\"{1+2}\"" to "not-bindable",
            "\"@ink\"" to "not-bindable", "1" to "motion-literal", "#null" to "motion-literal",
            "(bool)#true" to "motion-literal", "(string)\"200ms\"" to "motion-literal")) {
            val s = source("text", value)
            val d = (load(s) as ReadResult.Invalid).diagnostics.single()
            assertEquals(value, code, d.code)
            val token = value.substringAfter(')', value)
            val at = s.indexOf(value) + value.length - token.length
            assertEquals(value, Span(at, at + token.length, 1, at + 1), d.span)
        }
    }

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
    @Test fun presenceAcceptsEffectsAndEveryCurveForm() {
        val curves = listOf("", "@motion.arrive", "240ms", "240ms ease-out", "240ms ease-in",
            "240ms ease-in-out", "240ms linear", "cubic-bezier(0.1, -2, 0.9, 3) 220ms",
            "spring(stiff)", "spring(0.4, 0.8)")
        for (key in listOf("enter", "exit")) for (curve in curves) {
            for (effect in PresenceEffect.entries) {
                val text = "${effect.name.lowercase()} $curve".trimEnd()
                val s = spec(text, key)
                val p = (if (key == "enter") s.enter else s.exit)!!
                assertEquals(text, setOf(effect), p.effects)
                assertEquals(text, Span(11, 11 + text.length, 2, 4), p.span)
                assertEquals(text, if (curve.isEmpty()) null else spec("place $curve").transitions.single().curve, p.curve)
                // Reconstruct the declaration from its effects and original curve, then read it again.
                val reread = spec(p.effects.joinToString(" ") { it.name.lowercase() } + " $curve", key)
                val again = if (key == "enter") reread.enter!! else reread.exit!!
                assertEquals(p.effects, again.effects)
                assertEquals(p.curve, again.curve)
            }
        }
        for (key in listOf("enter", "exit")) for (curve in curves) {
            val p = (if (key == "enter") spec("fade-up grow scale slide blur $curve", key).enter
                else spec("fade-up grow scale slide blur $curve", key).exit)!!
            assertEquals(PresenceEffect.entries.toSet(), p.effects)
            assertEquals(if (curve.isEmpty()) null else spec("place $curve").transitions.single().curve, p.curve)
        }
        assertEquals(setOf(PresenceEffect.FADE, PresenceEffect.RISE), spec("fade-up 240ms", "enter").enter!!.effects)
        assertEquals(ease(0f,0f,.58f,1f,240f), spec("fade-up 240ms", "enter").enterCurve())
        assertEquals(ease(0f,0f,.58f,1f,120f), spec("fade 120ms", "exit").exitCurve())
    }
    @Test fun presenceCurvePrecedenceAndAbsence() {
        val enter = spec("fade @motion.own", "enter").enter!!
        val exit = spec("grow @motion.own", "exit").exit!!
        val own = CurveSpec.Role("motion.own")
        val node = CurveSpec.Role("motion.node")
        val s = NodeMotionSpec(emptyList(), node, enter, exit)
        assertEquals(own, s.enterCurve())
        assertEquals(own, s.exitCurve())
        val inherited = s.copy(enter=enter.copy(curve=null), exit=exit.copy(curve=null))
        assertEquals(node, inherited.enterCurve())
        assertEquals(node, inherited.exitCurve())
        assertEquals(CurveSpec.Role("motion.enter"), inherited.copy(spring=null).enterCurve())
        assertEquals(CurveSpec.Role("motion.exit"), inherited.copy(spring=null).exitCurve())
        assertNull(s.copy(enter=null).enterCurve())
        assertNull(s.copy(exit=null).exitCurve())
        assertNull(s.curveFor(MotionChannel.SIZE))
    }
    @Test fun presenceRefusalsHaveCodesReasonsAndExactSpans() {
        for (key in listOf("enter", "exit")) for ((text, code, token) in listOf(
            Triple("fdae", "motion-unknown-effect", "fdae"),
            Triple("fade rize", "motion-unknown-effect", "rize"),
            Triple("fade fade", "motion-duplicate-effect", "fade"),
            Triple("fade-up fade-up", "motion-duplicate-effect", "fade-up"),
            Triple("fade-up fade", "motion-duplicate-effect", "fade"),
            Triple("fade-up rise", "motion-duplicate-effect", "rise"),
            Triple("rise fade-up", "motion-duplicate-effect", "fade-up"),
            Triple("fade fade-up", "motion-duplicate-effect", "fade-up"),
            Triple("", "motion-no-effect", ""),
            Triple("  ", "motion-no-effect", ""),
            Triple("240ms", "motion-no-effect", "240ms"),
            Triple("@motion.enter", "motion-no-effect", "@motion.enter"),
            Triple("spring(stiff)", "motion-no-effect", "spring(stiff)"),
            Triple("fade, grow", "motion-presence-comma", ","),
            Triple("fade spring(0.4, 0.8), rise", "motion-presence-comma", ","),
            Triple("place", "motion-presence-channel", "place"),
            Triple("size", "motion-presence-channel", "size"),
            Triple("opacity", "motion-presence-channel", "opacity"),
            Triple("value", "motion-presence-channel", "value"),
            Triple("fade value", "motion-presence-channel", "value"),
            Triple("slide-up", "motion-unknown-effect", "slide-up"),
            Triple("rotate", "motion-not-supported", "rotate"),
            Triple("keyframes", "motion-not-supported", "keyframes"),
            Triple("stagger", "motion-not-supported", "stagger"),
            Triple("fade delay", "motion-not-supported", "delay"),
            Triple("fade 200ms delay", "motion-not-supported", "delay"),
            Triple("fade delay(1200ms)", "motion-bad-delay", "delay(1200ms)"),
            Triple("fade delay(10ms) delay(20ms)", "motion-duplicate-delay", "delay(20ms)"),
            Triple("fade 0ms", "motion-bad-duration", "0ms"),
            Triple("fade spring(0,1)", "motion-bad-spring", "spring(0,1)"),
            Triple("fade cubic-bezier(2,0,1,1) 200ms", "motion-bad-bezier", "cubic-bezier(2,0,1,1) 200ms"),
            Triple("fade 200ms ease-ot", "motion-unknown-easing", "ease-ot")
        )) {
            val r = parse(text, key)
            assertNull(text, r.spec)
            val d = r.diagnostics.single()
            assertEquals(text, code, d.code)
            assertTrue(d.message.isNotBlank())
            val at = if (token.isEmpty()) text.length else text.lastIndexOf(token)
            assertEquals(text, Span(11 + at, 11 + at + token.length, 2, 4 + at), d.span)
            if (code == "motion-presence-channel") assertTrue(d.message.contains("transition"))
        }
        assertEquals("did you mean 'rise'?", parse("rize", "enter").diagnostics.single().hint)
        for (key in listOf("enter", "exit")) {
            for (text in listOf("\$state", "fade {1+2}", "@ink", "fade @ink")) {
                val d = parse(text, key).diagnostics.single()
                assertEquals("not-bindable", d.code)
                assertEquals(Span(10, 12 + text.length, 2, 3), d.span)
            }
            val bounded = "fade" + " ".repeat(4092)
            assertNotNull(spec(bounded, key))
            val d = parse(bounded + " ", key).diagnostics.single()
            assertEquals("motion-limit", d.code)
            assertEquals(Span(10, 4109, 2, 3), d.span)
        }
    }
    @Test fun readerAndValidatorRecognizePresenceOnlyNodes() {
        for (key in listOf("enter", "exit")) {
            val doc = KdlParser.parse("""component "c" { view { box $key="fade @motion.custom"; }; }""")
            val c = (Validator(TokenCheck { false }).load(doc) as ReadResult.Ok).component
            val motion = c.view.motion!!
            assertEquals(CurveSpec.Role("motion.custom"), if (key == "enter") motion.enterCurve() else motion.exitCurve())
            for ((value, code) in listOf("\"\$state\"" to "not-bindable", "\"{1+2}\"" to "not-bindable",
                "\"@ink\"" to "not-bindable", "1" to "motion-literal", "#true" to "motion-literal")) {
                val bad = KdlParser.parse("""component "c" { view { box $key=$value; }; }""")
                assertEquals(code, (Validator(TokenCheck { false }).load(bad) as ReadResult.Invalid).diagnostics.single().code)
            }
            val binding = AttrValue.State("x", Span(0,4,1,1))
            val d = MotionGrammar.read(mapOf(key to binding)).diagnostics.single()
            assertEquals("motion-literal", d.code)
            assertEquals(binding.span, d.span)
        }
    }


    /** T-013: a presence holds its start for `delay(Nms)` (0..1000), the curve read as before around it. */
    @Test fun presenceTakesADelay() {
        for (key in listOf("enter", "exit")) {
            val p = spec("fade scale spring(0.5, 0.8) delay(80ms)", key).let { if (key == "enter") it.enter else it.exit }!!
            assertEquals(80f, p.delayMs)
            assertEquals(setOf(PresenceEffect.FADE, PresenceEffect.SCALE), p.effects)
            assertEquals(CurveSpec.Literal(Curve.Spring(0.5f, 0.8f)), p.curve)
            assertEquals(120f, spec("fade delay(120ms) 200ms", key).let { if (key == "enter") it.enter else it.exit }!!.delayMs)
            assertEquals(0f, spec("fade", key).let { if (key == "enter") it.enter else it.exit }!!.delayMs)
        }
        assertNull(parse("place 200ms delay(80ms)").spec)   // a transition takes no delay
    }
}
