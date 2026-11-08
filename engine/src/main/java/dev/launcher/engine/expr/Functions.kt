package dev.launcher.engine.expr

/** Fixed allowlist: calls never resolve methods or names from a package. Invalid dynamic input is Null. */
internal object Functions {
    val arities = mapOf("mix" to 3, "opaque" to 1, "withAlpha" to 2, "alphaOf" to 1, "scaleAlpha" to 3, "clamp" to 3)

    fun apply(name: String, a: List<Value>): Value {
        fun number(k: Int): Double? = (a[k] as? Value.Num)?.takeIf { it.unit == null && it.value.isFinite() }?.value
        // Reject values outside Long before conversion; multiply only after checking overflow.
        fun integer(k: Int): Long? = number(k)?.takeIf { it >= 0 && it < Long.MAX_VALUE.toDouble() && it % 1.0 == 0.0 }?.toLong()
        if (name == "clamp") {
            val ns = a.map { it as? Value.Num ?: return Value.Null }
            if (ns.any { !it.value.isFinite() } || ns.map { it.unit }.distinct().size != 1 ||
                ns[0].unit !in setOf(null, "pt", "dp", "px") || ns[1].value > ns[2].value) return Value.Null
            return Value.Num(ns[0].value.coerceIn(ns[1].value, ns[2].value), ns[0].unit)
        }
        val c = (a[0] as? Value.Color)?.argb ?: return Value.Null
        val alpha = c ushr 24
        fun with(n: Int) = Value.Color((c and 0xFFFFFF) or (n shl 24))
        return when (name) {
            "opaque" -> with(255)
            "alphaOf" -> Value.Num(alpha.toDouble())
            "withAlpha" -> integer(1)?.takeIf { it <= 255 }?.let { with(it.toInt()) } ?: Value.Null
            "scaleAlpha" -> {
                val num = integer(1) ?: return Value.Null
                val den = integer(2)?.takeIf { it > 0 } ?: return Value.Null
                if (alpha > 0 && num > Long.MAX_VALUE / alpha) return Value.Null
                with((alpha.toLong() * num / den).coerceAtMost(255).toInt())
            }
            "mix" -> {
                val b = (a[1] as? Value.Color)?.argb ?: return Value.Null
                val t = number(2) ?: return Value.Null
                if (t <= 0) return Value.Color(c)
                if (t >= 1) return Value.Color(b)
                var out = 0
                for (shift in listOf(0, 8, 16, 24)) {
                    val x = (c ushr shift) and 255
                    val y = (b ushr shift) and 255
                    out = out or ((x + (y - x) * t.toFloat() + 0.5f).toInt() shl shift)
                }
                Value.Color(out)
            }
            else -> Value.Null
        }
    }
}

internal fun call(n: Node.Call, args: List<Fn>): Fn {
    fun bad(message: String): Nothing = throw ExprException(message, n.at, code = "bad-call")
    val arity = Functions.arities[n.name] ?: bad("Unknown function '${n.name}'")
    if (args.size != arity) bad("'${n.name}' takes $arity arguments")
    // Partial checks reject known wrong types even when another argument reads live data.
    fun kind(x: Node): String? = when (x) {
        is Node.Num -> "number"
        is Node.Unary -> if (x.op == "!") "bool" else "number"
        is Node.Str -> "string"
        is Node.Bool -> "bool"
        is Node.Null -> "null"
        is Node.Pipe -> "string"
        is Node.Cond -> kind(x.then)?.takeIf { it == kind(x.otherwise) }
        is Node.Call -> if (x.name in setOf("clamp", "alphaOf")) "number" else "color"
        is Node.Binary -> if (x.op in setOf("+", "-", "*", "/", "%")) "number" else "bool"
        else -> null
    }
    val constants = n.args.mapIndexed { k, x ->
        val deps = Deps.of(Template(listOf("", ""), listOf(x)), emptySet())
        if (deps.states.isEmpty() && deps.tokens.isEmpty() && deps.sources.isEmpty() && !deps.clock)
            args[k].value(EmptyEnv) else null
    }
    n.args.forEachIndexed { k, x ->
        val expected = if (n.name == "clamp" || k > 0 && !(n.name == "mix" && k == 1)) "number" else "color"
        if (kind(x)?.let { it != expected } == true) bad("'${n.name}' argument ${k + 1} takes $expected")
        val v = constants[k]
        if (v is Value.Num) {
            if (!v.value.isFinite() || n.name != "clamp" && v.unit != null) bad("'${n.name}' needs finite unitless factors")
            if (n.name in setOf("withAlpha", "scaleAlpha") &&
                (v.value % 1.0 != 0.0 || v.value < 0 || v.value >= Long.MAX_VALUE.toDouble() ||
                    n.name == "withAlpha" && v.value > 255 || n.name == "scaleAlpha" && k == 2 && v.value == 0.0))
                bad("'${n.name}' has an invalid integer argument")
        }
    }
    if (constants.all { it != null } && Functions.apply(n.name, constants.filterNotNull()) == Value.Null)
        bad("Invalid arguments to '${n.name}'")
    if (n.name == "clamp") {
        val knownUnits = args.indices.filter { args[it].unit != null || constants[it] is Value.Num }
            .map { args[it].unit ?: (constants[it] as? Value.Num)?.unit }
        if (knownUnits.distinct().size > 1) bad("'clamp' needs compatible lengths")
        val lo = constants[1] as? Value.Num
        val hi = constants[2] as? Value.Num
        if (lo != null && hi != null && lo.value > hi.value) bad("'clamp' lower bound exceeds upper bound")
    }
    return object : Fn() {
        override fun value(env: Env) = Functions.apply(n.name, args.map { it.value(env) })
    }
}

private object EmptyEnv : Env {
    override fun state(name: String) = Value.Null
    override fun prop(name: String) = Value.Null
    override fun token(key: String) = Value.Null
    override fun source(path: String) = Value.Null
    override fun now() = 0L
}
