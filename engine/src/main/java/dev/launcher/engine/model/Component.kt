package dev.launcher.engine.model

import dev.launcher.engine.kdl.KdlValue
import dev.launcher.engine.kdl.Span

/**
 * A component as a file declares it (docs/ENGINE_SPEC.md, "Component model"; docs/E2_PLAN.md §b), read from a KDL document
 * by [ComponentReader] and checked by [Validator]. Plain data, no Android: the editor and the phone read the same model.
 */
data class ComponentDef(
    val name: String,
    val version: String?,
    val props: List<PropDef>,
    val uses: Uses,
    val state: List<StateDef>,
    /** The view's one root primitive. */
    val view: ViewNode,
    /** `variant "name" { ... }` blocks inside `view`: attribute overrides the host chooses by name. */
    val variants: List<Variant>,
    val fallback: ViewNode?,
    /** The script block's source as written (it runs in E3's script worker; nothing here reads it). */
    val script: String?,
    val span: Span,
)

/** A public knob: `size type=dp default=48`. */
data class PropDef(val name: String, val type: AttrType, val default: KdlValue?, val span: Span)

/** A name as written, with where it was written. */
data class Named(val name: String, val span: Span)

/** What the component reads and does: `uses { source battery; action intent; token comp.statusbar.time-size }`. */
data class Uses(val sources: List<Named>, val actions: List<Named>, val tokens: List<Named>)

/** A value the component owns, with its initial value: `level 100`. */
data class StateDef(val name: String, val initial: KdlValue, val span: Span)

/** A primitive in the view tree: its positional arguments, its attributes by name, its children. */
data class ViewNode(
    val primitive: String,
    val args: List<AttrValue>,
    val attrs: Map<String, Attr>,
    val children: List<ViewNode>,
    val span: Span,
    val nameSpan: Span,
    val motion: NodeMotionSpec? = null,
)

data class Attr(val name: String, val keySpan: Span, val value: AttrValue)

/**
 * An attribute's value, sorted at read time: a literal, a whole `$state` or `@prop`/`@token` reference, or an
 * expression (a string holding `{...}`, kept as text until the expression parser of E2 slice 4).
 */
sealed class AttrValue {
    abstract val span: Span

    data class Literal(val value: KdlValue) : AttrValue() { override val span get() = value.span }
    data class State(val name: String, override val span: Span) : AttrValue()
    data class Ref(val name: String, override val span: Span) : AttrValue()
    data class Expr(val text: String, override val span: Span) : AttrValue()
}

data class Variant(val name: String, val nodes: List<ViewNode>, val span: Span)

/** One finding: where, a stable [code] (tests and the editor key on it), what is wrong, and what to write instead. */
data class Diagnostic(val span: Span, val code: String, val message: String, val hint: String? = null)

/** A component read from a document, or why it could not be. */
sealed class ReadResult {
    data class Ok(val component: ComponentDef) : ReadResult()
    data class Invalid(val diagnostics: List<Diagnostic>) : ReadResult()
}

/** The closest of [known] to [word] within two edits (Levenshtein), or null. */
internal fun nearest(word: String, known: Collection<String>): String? =
    known.map { it to distance(word, it) }.filter { it.second <= 2 }.minByOrNull { it.second }?.first

private fun distance(a: String, b: String): Int {
    var row = IntArray(b.length + 1) { it }
    for (i in a.indices) {
        val next = IntArray(b.length + 1).also { it[0] = i + 1 }
        for (j in b.indices) next[j + 1] = minOf(row[j + 1] + 1, next[j] + 1, row[j] + if (a[i] == b[j]) 0 else 1)
        row = next
    }
    return row[b.length]
}

/** "Unknown WHAT 'NAME'" with the nearest known name as its hint. */
internal fun unknown(span: Span, code: String, what: String, name: String, known: Collection<String>) =
    Diagnostic(span, code, "Unknown $what '$name'", nearest(name, known)?.let { "did you mean '$it'?" })
