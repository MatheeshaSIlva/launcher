package dev.launcher.engine.expr

/**
 * An expression's tree (docs/E2_PLAN.md §b, "Expressions"). Every node keeps [at], its offset in the attribute's text,
 * where an error or a dependency points.
 */
sealed class Node {
    abstract val at: Int

    data class Num(val value: Double, val unit: String?, override val at: Int) : Node()
    data class Str(val value: String, override val at: Int) : Node()
    data class Bool(val value: Boolean, override val at: Int) : Node()
    data class Null(override val at: Int) : Node()
    /** `$name`: the component's state. */
    data class State(val name: String, override val at: Int) : Node()
    /** `@name`: a prop of the component, or a token (`@comp.statusbar.time-size`). */
    data class Ref(val name: String, override val at: Int) : Node()
    /** A source path (`battery.level`, `network.wifi.level`); `now` alone is the clock. */
    data class Path(val path: String, override val at: Int) : Node()
    data class Call(val name: String, val args: List<Node>, override val at: Int) : Node()
    data class Unary(val op: String, val operand: Node, override val at: Int) : Node()
    data class Binary(val op: String, val left: Node, val right: Node, override val at: Int) : Node()
    data class Cond(val test: Node, val then: Node, val otherwise: Node, override val at: Int) : Node()
    /** `value | formatter` or `value | formatter:arg`. */
    data class Pipe(val value: Node, val formatter: String, val arg: Node?, override val at: Int) : Node()
}

/** An attribute's text: [texts] around [holes] (`{expression}`), interleaved, so `texts.size == holes.size + 1`. */
data class Template(val texts: List<String>, val holes: List<Node>) {
    /** The one expression when the text is nothing but `{expression}`: its value keeps its type. */
    val whole: Node? get() = holes.singleOrNull()?.takeIf { texts.all { it.isEmpty() } }
}

/**
 * An error at offset [at] of the attribute's text, with what to write instead when there is a better word. [code] is the
 * validator's: "bad-expression" for syntax, "unit-mismatch" for a sum or comparison of two different units.
 */
class ExprException(message: String, val at: Int, val hint: String? = null, val code: String = "bad-expression") :
    Exception(message)
