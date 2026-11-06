package dev.launcher.engine.kdl

/** Where a piece of a KDL document is: [start, end) as offsets into the text, and the 1-based line and column of its start. */
data class Span(val start: Int, val end: Int, val line: Int, val col: Int)

/** A value: an argument or a property's value. Every one keeps the span it was written at. */
sealed class KdlValue {
    abstract val span: Span

    data class Str(val value: String, override val span: Span) : KdlValue()
    data class Bool(val value: Boolean, override val span: Span) : KdlValue()
    data class Null(override val span: Span) : KdlValue()

    /** A number as written ([raw], with its sign, radix prefix and underscores; `#inf`, `#-inf`, `#nan` for those). */
    data class Num(val raw: String, override val span: Span) : KdlValue() {
        private val digits get() = raw.replace("_", "")
        private val radix get() = digits.removePrefix("-").removePrefix("+").let {
            when { it.startsWith("0x") -> 16; it.startsWith("0o") -> 8; it.startsWith("0b") -> 2; else -> 10 }
        }
        /** The value as a Long, or null for a fraction, an exponent, a keyword number or one too large for a Long. */
        val long: Long? get() = if (radix == 10) digits.toLongOrNull() else {
            val neg = digits.startsWith("-")
            digits.trimStart('+', '-').drop(2).toLongOrNull(radix)?.let { if (neg) -it else it }
        }
        /** The value as a Double (always). */
        val double: Double get() = when (raw) {
            "#inf" -> Double.POSITIVE_INFINITY
            "#-inf" -> Double.NEGATIVE_INFINITY
            "#nan" -> Double.NaN
            else -> if (radix == 10) digits.toDouble() else long?.toDouble() ?: java.math.BigInteger(
                digits.trimStart('+', '-').drop(2), radix).let { if (digits.startsWith("-")) it.negate() else it }.toDouble()
        }
    }
}

/** A property: its key (and where it was written) and its value. */
data class KdlProp(val key: String, val keySpan: Span, val value: KdlValue)

/** A node: its name, its arguments in order, its properties (the rightmost of a repeated key wins), its children. */
data class KdlNode(
    val name: String,
    val nameSpan: Span,
    val args: List<KdlValue>,
    val props: Map<String, KdlProp>,
    val children: List<KdlNode>,
    /** From the node's name to the end of its last entry or of its children block. */
    val span: Span,
)

data class KdlDocument(val nodes: List<KdlNode>)

/** A syntax error: the message ends with "at LINE:COL", the place it was found (1-based). */
class KdlException(message: String, val line: Int, val col: Int) : Exception(message)
