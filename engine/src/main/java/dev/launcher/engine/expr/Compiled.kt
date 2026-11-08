package dev.launcher.engine.expr

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A value an expression reads or makes. */
sealed class Value {
    data class Num(val value: Double, val unit: String? = null) : Value()
    data class Str(val value: String) : Value()
    class Bool private constructor(val value: Boolean) : Value() {
        override fun toString() = "Bool($value)"
        companion object {
            val TRUE = Bool(true)
            val FALSE = Bool(false)
            fun of(b: Boolean) = if (b) TRUE else FALSE
        }
    }
    /** A colour as ARGB, as the launcher's design system gives it (a token at the current light/dark blend). */
    data class Color(val argb: Int) : Value()
    object Null : Value()
}

/** What a compiled expression reads, at each evaluation. */
interface Env {
    fun state(name: String): Value
    fun prop(name: String): Value
    fun token(key: String): Value
    fun source(path: String): Value
    /** The clock, in milliseconds since the epoch. */
    fun now(): Long
    val locale: Locale get() = Locale.getDefault()
    val zone: TimeZone get() = TimeZone.getDefault()
}

/**
 * An attribute's [Template] compiled once into a tree of evaluators. Numbers and booleans travel as primitives between
 * them ([Fn.num], [Fn.bool]): an arithmetic or comparison tree allocates nothing per evaluation, and [number] and
 * [bool] read a whole expression without a [Value]; [value] makes one only at the end (and [text] a string).
 */
class Compiled private constructor(val template: Template, val deps: Deps, private val holes: Array<Fn>) {
    companion object {
        /** [props]: the component's prop names (an `@name` that is not one reads a token). */
        fun of(t: Template, props: Set<String>) = Compiled(t, Deps.of(t, props), Array(t.holes.size) { fn(t.holes[it], props) })
        fun of(text: String, props: Set<String>) = of(ExprParser.template(text), props)
    }

    private val whole = template.whole != null

    /** A whole `{expression}` keeps its type; text with holes is a string. */
    fun value(env: Env): Value = if (whole) holes[0].value(env) else Value.Str(text(env))
    fun number(env: Env): Double = if (whole) holes[0].num(env) else Double.NaN
    fun bool(env: Env): Boolean = if (whole) holes[0].bool(env) else text(env).isNotEmpty()
    fun text(env: Env): String = buildString {
        for (k in holes.indices) append(template.texts[k]).append(show(holes[k].value(env)))
        append(template.texts.last())
    }
}

/** How a value is written into text: whole numbers without a fraction, #null as nothing. */
internal fun show(v: Value): String = when (v) {
    is Value.Num -> if (v.value % 1.0 == 0.0 && Math.abs(v.value) < 1e15) v.value.toLong().toString() else v.value.toString()
    is Value.Str -> v.value
    is Value.Bool -> v.value.toString()
    is Value.Color -> "#%08X".format(v.argb)
    Value.Null -> ""
}

internal fun truthy(v: Value): Boolean = when (v) {
    is Value.Num -> v.value != 0.0 && !v.value.isNaN()
    is Value.Str -> v.value.isNotEmpty()
    is Value.Bool -> v.value
    is Value.Color -> true
    Value.Null -> false
}

/** One node's evaluator. [numeric]: it always makes a number (so `==` compares as numbers, with no [Value]). */
internal abstract class Fn(val numeric: Boolean = false, val unit: String? = null) {
    abstract fun value(env: Env): Value
    open fun num(env: Env): Double = (value(env) as? Value.Num)?.value ?: Double.NaN
    open fun bool(env: Env): Boolean = truthy(value(env))
    /** The unit of what it makes: a literal's, else the value's (a prop or a token in dp or pt) when [value] is asked. */
    open fun unitAt(env: Env): String? = unit ?: (value(env) as? Value.Num)?.unit
}

private class Const(val v: Value) : Fn(v is Value.Num, (v as? Value.Num)?.unit) {
    val n = (v as? Value.Num)?.value ?: Double.NaN
    val b = truthy(v)
    override fun value(env: Env) = v
    override fun num(env: Env) = n
    override fun bool(env: Env) = b
}

private class Read(val read: (Env) -> Value) : Fn() { override fun value(env: Env) = read(env) }

/** A number computed from numbers: [num] is the work, [value] wraps it, in the unit of the first of [parts] that has one. */
private abstract class NumFn(unit: String?, private vararg val parts: Fn) : Fn(true, unit) {
    override fun unitAt(env: Env): String? = unit ?: parts.firstNotNullOfOrNull { it.unitAt(env) }
    override fun value(env: Env): Value = Value.Num(num(env), unitAt(env))
    override fun bool(env: Env) = num(env).let { it != 0.0 && !it.isNaN() }
}

/** A boolean computed: [bool] is the work, [value] one of two singletons. */
private abstract class BoolFn : Fn() { override fun value(env: Env): Value = Value.Bool.of(bool(env)) }

private fun fn(n: Node, props: Set<String>): Fn = when (n) {
    is Node.Num -> Const(Value.Num(n.value, n.unit))
    is Node.Str -> Const(Value.Str(n.value))
    is Node.Bool -> Const(Value.Bool.of(n.value))
    is Node.Null -> Const(Value.Null)
    is Node.State -> Read { it.state(n.name) }
    is Node.Ref -> if (n.name in props) Read { it.prop(n.name) } else Read { it.token(n.name) }
    is Node.Path -> if (n.path == "now") object : NumFn(null) { override fun num(env: Env) = env.now().toDouble() }
        else Read { it.source(n.path) }
    is Node.Unary -> fn(n.operand, props).let { a ->
        if (n.op == "!") object : BoolFn() { override fun bool(env: Env) = !a.bool(env) }
        else object : NumFn(a.unit, a) { override fun num(env: Env) = -a.num(env) }
    }
    is Node.Binary -> {
        val a = fn(n.left, props)
        val b = fn(n.right, props)
        if (n.op in SAME_UNITS && a.unit != null && b.unit != null && a.unit != b.unit)
            throw ExprException("Mixed units: ${a.unit} ${n.op} ${b.unit}", n.at, "write both in one unit", "unit-mismatch")
        binary(n.op, a, b)
    }
    is Node.Cond -> Triple(fn(n.test, props), fn(n.then, props), fn(n.otherwise, props)).let { (t, a, b) ->
        object : Fn(a.numeric && b.numeric, a.unit ?: b.unit) {
            override fun value(env: Env) = if (t.bool(env)) a.value(env) else b.value(env)
            override fun num(env: Env) = if (t.bool(env)) a.num(env) else b.num(env)
            override fun bool(env: Env) = if (t.bool(env)) a.bool(env) else b.bool(env)
        }
    }
    is Node.Call -> call(n, n.args.map { fn(it, props) })
    is Node.Pipe -> Formatter(fn(n.value, props), n.formatter, n.arg)
}

/** Where both sides must be in one unit when both have one (a number without a unit combines with anything). */
private val SAME_UNITS = setOf("+", "-", "<", "<=", ">", ">=", "==", "!=")

private fun binary(op: String, a: Fn, b: Fn): Fn = when (op) {
    "&&" -> object : BoolFn() { override fun bool(env: Env) = a.bool(env) && b.bool(env) }
    "||" -> object : BoolFn() { override fun bool(env: Env) = a.bool(env) || b.bool(env) }
    "==", "!=" -> object : BoolFn() {
        val numbers = a.numeric || b.numeric
        override fun bool(env: Env) = (if (numbers) a.num(env) == b.num(env) else a.value(env) == b.value(env)) == (op == "==")
    }
    "<" -> object : BoolFn() { override fun bool(env: Env) = a.num(env) < b.num(env) }
    "<=" -> object : BoolFn() { override fun bool(env: Env) = a.num(env) <= b.num(env) }
    ">" -> object : BoolFn() { override fun bool(env: Env) = a.num(env) > b.num(env) }
    ">=" -> object : BoolFn() { override fun bool(env: Env) = a.num(env) >= b.num(env) }
    else -> object : NumFn(a.unit ?: b.unit, a, b) {
        val code = op[0]
        override fun num(env: Env): Double {
            val x = a.num(env)
            val y = b.num(env)
            return when (code) { '+' -> x + y; '-' -> x - y; '*' -> x * y; '/' -> x / y; else -> x % y }
        }
    }
}

/**
 * `date:'pattern'` (milliseconds since the epoch, in the environment's zone), `percent` (a fraction: 0.87 is 87 %),
 * `pad:n` (a whole number, zero-padded to n digits), `number:n` (n decimal places), each in the environment's locale.
 * A formatter keeps what it built for the last locale and zone.
 */
private class Formatter(val input: Fn, val name: String, arg: Node?) : Fn() {
    val pattern = (arg as? Node.Str)?.value ?: ""
    val digits = ((arg as? Node.Num)?.value ?: 0.0).toInt()
    var builtFor: Pair<Locale, TimeZone>? = null
    var format: Any? = null

    override fun value(env: Env): Value {
        val x = input.num(env)
        if (name == "pad") return Value.Str(if (x.isNaN()) "" else x.toLong().toString().padStart(digits, '0'))
        val key = env.locale to env.zone
        if (key != builtFor) {
            builtFor = key
            format = when (name) {
                "date" -> SimpleDateFormat(pattern, env.locale).apply { timeZone = env.zone }
                "percent" -> NumberFormat.getPercentInstance(env.locale)
                else -> NumberFormat.getNumberInstance(env.locale).apply { minimumFractionDigits = digits; maximumFractionDigits = digits }
            }
        }
        val f = format
        return Value.Str(if (f is SimpleDateFormat) f.format(Date(x.toLong())) else (f as NumberFormat).format(x))
    }
}
