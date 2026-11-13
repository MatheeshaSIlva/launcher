package dev.launcher.engine.model

import dev.launcher.app.design.Curve
import dev.launcher.engine.kdl.KdlValue
import dev.launcher.engine.kdl.Span

/** A presence delay (T-013): `delay(80ms)`, milliseconds only. */
private val DELAY = Regex("delay\\(\\s*([0-9]+(?:\\.[0-9]+)?)\\s*ms\\s*\\)")

/** Fixed, data-only motion declarations. No token lookup, expression evaluation or reflection. */
object MotionGrammar {
    val ATTRS = setOf("transition", "spring", "enter", "exit", "roll")
    data class Result(val spec: NodeMotionSpec?, val diagnostics: List<Diagnostic>)
    // `value` expands this complete set, including radius.
    private val channels = MotionChannel.entries.associateBy { it.name.lowercase() }
    private val effects = PresenceEffect.entries.associateBy { it.name.lowercase() }
    private val easings = mapOf(
        "ease-out" to listOf(0f, 0f, .58f, 1f), "ease-in-out" to listOf(.42f, 0f, .58f, 1f),
        "ease-in" to listOf(.42f, 0f, 1f, 1f), "linear" to listOf(0f, 0f, 1f, 1f))
    private val role = Regex("@motion\\.[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*")
    private val number = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
    private val name = Regex("[A-Za-z_][A-Za-z0-9_-]*")

    fun read(attrs: Map<String, AttrValue>): Result {
        if (attrs.keys.none { it in ATTRS }) return Result(null, emptyList())
        val d = ArrayList<Diagnostic>()
        val transitions = ArrayList<Transition>()
        var spring: CurveSpec? = null
        var roll: CurveSpec? = null
        val presence = HashMap<String, Presence>()
        val seen = HashSet<MotionChannel>()
        for (key in ATTRS) attrs[key]?.let { value ->
            val literal = (value as? AttrValue.Literal)?.value
            if (key == "roll" && literal is KdlValue.Bool && literal.type == null) {
                roll = if (literal.value) CurveSpec.Role(ROLL_DEFAULT_MARKER) else null
                return@let
            }
            val raw = ((value as? AttrValue.Literal)?.value as? KdlValue.Str)
            if (raw == null || raw.type != null) {
                d += Diagnostic(value.span, "motion-literal", "'$key' needs a literal motion string${if (key == "roll") " or #true/#false" else ""}, never a binding or expression")
                return@let
            }
            val run = Parse(raw, d)
            if (!run.allowed(defaultSpring = key == "spring", presence = key == "enter" || key == "exit", roll = key == "roll")) return@let
            if (key == "spring") spring = run.curve(raw.value, 0, defaultSpring = true)
            else if (key == "roll") roll = run.curve(raw.value, 0)
            else if (key == "enter" || key == "exit") run.presence()?.let { presence[key] = it }
            else for ((text, offset) in run.clauses()) {
                val words = Regex("\\S+").findAll(text).toList()
                val selected = linkedSetOf<MotionChannel>()
                var k = 0
                while (k < words.size) {
                    val word = words[k].value
                    val next = if (word == "value") channels.values.toSet() else channels[word]?.let { setOf(it) } ?: break
                    for (channel in next) {
                        if (!seen.add(channel)) run.error("motion-duplicate-channel", "Channel '${channel.name.lowercase()}' is declared twice", offset + words[k].range.first, word.length)
                        selected += channel
                    }
                    k++
                }
                if (selected.isEmpty()) {
                    val word = words.firstOrNull()?.value.orEmpty()
                    if (word.isEmpty()) run.error("motion-empty-clause", "A transition clause cannot be empty", offset, 0)
                    else d += unknown(run.at(offset + words.first().range.first, word.length), "motion-unknown-channel", "motion channel", word, channels.keys + "value")
                } else {
                    val start = words.getOrNull(k)?.range?.first
                    val word = words.getOrNull(k)?.value
                    val curve = if (word != null && name.matches(word) && !word.endsWith("ms")) {
                        d += unknown(run.at(offset + start!!, word.length), "motion-unknown-channel", "motion channel", word, channels.keys + "value")
                        null
                    } else start?.let { run.curve(text.substring(it), offset + it) }
                    transitions += Transition(selected, curve, run.at(offset, text.length))
                }
            }
        }
        return Result(if (d.isEmpty()) NodeMotionSpec(transitions, spring, presence["enter"], presence["exit"], roll) else null, d)
    }

    private class Parse(val raw: KdlValue.Str, val d: MutableList<Diagnostic>) {
        val text = raw.value
        fun at(offset: Int, length: Int): Span = raw.span.let {
            // Escapes, raw strings and multiline values cannot be mapped from decoded offsets safely.
            if (it.end - it.start != text.length + 2 || '\n' in text || '\r' in text) it
            else Span(it.start + 1 + offset, it.start + 1 + offset + length, it.line, it.col + 1 + offset)
        }
        fun error(code: String, reason: String, offset: Int, length: Int) { d += Diagnostic(at(offset, length), code, reason) }
        fun allowed(defaultSpring: Boolean, presence: Boolean, roll: Boolean): Boolean {
            if (text.length > 4096) {
                d += Diagnostic(raw.span, "motion-limit", "Motion declarations are limited to 4096 characters")
                return false
            }
            if (text.any { it == '$' || it == '{' || it == '}' } ||
                text.trim().startsWith("@") && !text.trim().startsWith("@motion.") ||
                presence && Regex("(?<!\\S)@(?!motion\\.)").containsMatchIn(text)) {
                d += Diagnostic(raw.span, "not-bindable", "Motion declarations cannot contain bindings or expressions")
                return false
            }
            if (defaultSpring && name.matches(text.trim())) return true
            val inspected = Regex("spring\\(\\s*[A-Za-z_][A-Za-z0-9_-]*\\s*\\)").replace(text) { " ".repeat(it.value.length) }
            if (roll) {
                val later = Regex("(?<![A-Za-z0-9_.-])(?:stagger|up|down)(?![A-Za-z0-9_.-])").find(inspected)
                if (later != null) {
                    error("motion-not-supported", "'${later.value}' is not yet supported in E2 roll", later.range.first, later.value.length)
                    return false
                }
            }
            if (presence) {
                // `delay(Nms)` is a presence word (T-013); any other use of "delay" is not supported yet.
                val later = Regex("(?<![A-Za-z0-9_.-])delay(?![A-Za-z0-9_.-])").find(DELAY.replace(inspected) { " ".repeat(it.value.length) })
                if (later != null) {
                    error("motion-not-supported", "'${later.value}' is not yet supported in E2 motion", later.range.first, later.value.length)
                    return false
                }
            }
            val unsupported = Regex("(?<![A-Za-z0-9_.-])(?:keyframes?|stagger|rotate|stiffness|damping|mass|duration|bounce)(?![A-Za-z0-9_.-])|[a-z-]+\\s*=").find(inspected)
            if (unsupported != null) {
                d += Diagnostic(at(unsupported.range.first, unsupported.value.length), "motion-not-supported", "'${unsupported.value}' is not yet supported in E2 motion")
                return false
            }
            return true
        }
        fun clauses(): List<Pair<String, Int>> {
            val out = ArrayList<Pair<String, Int>>()
            var depth = 0
            var start = 0
            for (i in text.indices) when (text[i]) {
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 0) { out += text.substring(start, i) to start; start = i + 1 }
            }
            out += text.substring(start) to start
            return out
        }
        fun presence(): Presence? {
            // `delay(Nms)` anywhere in the clause, at most once; read and blanked (offsets kept) before the rest is read.
            val delays = DELAY.findAll(text).toList()
            if (delays.size > 1) { error("motion-duplicate-delay", "Presence takes one delay", delays[1].range.first, delays[1].value.length); return null }
            val delayMs = delays.firstOrNull()?.let { m ->
                val v = m.groupValues[1].toFloatOrNull()
                if (v == null || !v.isFinite() || v < 0f || v > Presence.MAX_DELAY_MS) {
                    error("motion-bad-delay", "A delay is 0 to ${Presence.MAX_DELAY_MS.toInt()} ms", m.range.first, m.value.length); return null
                }
                v
            } ?: 0f
            val text = delays.firstOrNull()?.let { m -> text.replaceRange(m.range, " ".repeat(m.value.length)) } ?: text
            val clauses = clauses()
            if (clauses.size > 1) {
                val comma = clauses.first().first.length
                error("motion-presence-comma", "Presence takes one clause; commas only belong inside curves", comma, 1)
                return null
            }
            val words = Regex("\\S+").findAll(text).toList()
            val selected = linkedSetOf<PresenceEffect>()
            var k = 0
            while (k < words.size) {
                val word = words[k].value
                val next = if (word == "fade-up") setOf(PresenceEffect.FADE, PresenceEffect.RISE)
                    else effects[word]?.let { setOf(it) } ?: break
                if (next.any { it in selected }) {
                    error("motion-duplicate-effect", "Effect '$word' is declared twice (fade-up includes fade and rise)", words[k].range.first, word.length)
                    return null
                }
                selected += next
                k++
            }
            val rest = words.getOrNull(k)
            if (rest != null) {
                val word = rest.value
                val offset = rest.range.first
                if (word in channels || word == "value") {
                    error("motion-presence-channel", "'$word' belongs to transition; presence needs effects", offset, word.length)
                    return null
                }
                if (name.matches(word) && !word.endsWith("ms")) {
                    d += unknown(at(offset, word.length), "motion-unknown-effect", "presence effect", word, effects.keys + "fade-up")
                    return null
                }
            }
            if (selected.isEmpty()) {
                error("motion-no-effect", "Presence needs at least one of fade, grow, scale, rise, slide or blur", rest?.range?.first ?: text.length, rest?.value?.length ?: 0)
                return null
            }
            val curve = rest?.let { curve(text.substring(it.range.first), it.range.first) }
            return Presence(selected, curve, at(0, text.length), delayMs)
        }
        fun curve(input: String, offset: Int, defaultSpring: Boolean = false): CurveSpec? {
            val s = input.trim()
            val pos = offset + input.indexOf(s)
            fun bad(code: String, reason: String): CurveSpec? { error(code, reason, pos, s.length); return null }
            if (role.matches(s)) return CurveSpec.Role(s.drop(1))
            if (defaultSpring && s.startsWith("motion.") && role.matches("@$s")) return CurveSpec.Role(s)
            if (defaultSpring && name.matches(s)) return CurveSpec.Role("motion.spring.$s")
            if (s.startsWith("spring(" ) && s.endsWith(")")) {
                val args = s.substring(7, s.length - 1).split(',').map { it.trim() }
                if (args.size == 1 && name.matches(args[0])) return CurveSpec.Role("motion.spring.${args[0]}")
                val numbers = args.mapNotNull { number(it) }
                if (args.size != 2 || numbers.size != 2 || numbers[0] <= 0f || numbers[0] > 10f || numbers[1] <= 0f || numbers[1] > 2f || numbers.any { it.toFloat() <= 0f })
                    return bad("motion-bad-spring", "Spring needs a name or finite response (0,10] seconds and damping (0,2]")
                return CurveSpec.Literal(Curve.Spring(numbers[0].toFloat(), numbers[1].toFloat()))
            }
            val bezier = Regex("cubic-bezier\\(([^()]*)\\)\\s+([^ ]+)").matchEntire(s)
            if (bezier != null) {
                val args = bezier.groupValues[1].split(',').map { number(it.trim()) }
                if (args.size != 4 || args.any { it == null || !it.isFinite() || !it.toFloat().isFinite() } || args[0]!! !in 0.0..1.0 || args[2]!! !in 0.0..1.0)
                    return bad("motion-bad-bezier", "Bezier needs four finite numbers; x controls must be in [0,1]")
                val ms = duration(bezier.groupValues[2]) ?: return bad("motion-bad-duration", "Duration must be 1..5000 ms")
                return CurveSpec.Literal(Curve.Ease(args[0]!!.toFloat(), args[1]!!.toFloat(), args[2]!!.toFloat(), args[3]!!.toFloat(), ms))
            }
            val parts = s.split(Regex("\\s+"))
            if (parts.first().endsWith("ms")) {
                val ms = duration(parts[0]) ?: return bad("motion-bad-duration", "Duration must be 1..5000 ms")
                if (parts.size > 2) return bad("motion-not-supported", "Per-property durations beyond clauses are not yet supported")
                val easing = parts.getOrElse(1) { "ease-out" }
                val points = easings[easing] ?: return null.also {
                    d += unknown(at(pos + s.indexOf(easing), easing.length), "motion-unknown-easing", "easing", easing, easings.keys)
                }
                return CurveSpec.Literal(Curve.Ease(points[0], points[1], points[2], points[3], ms))
            }
            return bad("motion-bad-curve", "Expected @motion.role, duration and easing, cubic-bezier(a,b,c,d) duration, or spring(name/response,damping)")
        }
        fun number(s: String): Double? = if (number.matches(s)) s.toDoubleOrNull()?.takeIf { it.isFinite() && it.toFloat().isFinite() } else null
        fun duration(s: String): Float? = number(s.removeSuffix("ms"))?.takeIf { s.endsWith("ms") && it in 1.0..5000.0 }?.toFloat()
    }
}
