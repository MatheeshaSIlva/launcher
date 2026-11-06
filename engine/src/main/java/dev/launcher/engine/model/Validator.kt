package dev.launcher.engine.model

import dev.launcher.engine.kdl.KdlDocument
import dev.launcher.engine.kdl.KdlValue
import dev.launcher.engine.kdl.Span

/** Whether a design token exists (the launcher's resolver from E2 slice 8; a fake in tests). */
fun interface TokenCheck {
    fun exists(key: String): Boolean
}

/**
 * Checks what a [ComponentDef]'s view means against the primitives' schemas ([Primitives]): every primitive and attribute
 * known, literals of the attribute's type, `$state` declared, `@name` a prop or a token, the sources an expression reads
 * in `uses`, no prop named like a token, each primitive in a parent that may hold it. Expressions are not parsed yet (E2
 * slice 4): their `$state`, `@name` and `source.path` references are found by a scan, and their findings point at the
 * whole value. Every finding is a [Diagnostic]; nothing throws.
 */
class Validator(private val tokens: TokenCheck) {
    private companion object {
        val BRACES = Regex("\\{([^}]*)}")
        val NAMES = Regex("'[^']*'|\"[^\"]*\"|([\$@]?)([A-Za-z_][\\w-]*(?:\\.[A-Za-z_][\\w-]*)*)")
    }

    /** Reads and checks: the component when both find nothing, else every finding of either. */
    fun load(doc: KdlDocument): ReadResult = when (val r = ComponentReader.read(doc)) {
        is ReadResult.Invalid -> r
        is ReadResult.Ok -> check(r.component).let { if (it.isEmpty()) r else ReadResult.Invalid(it) }
    }

    fun check(c: ComponentDef): List<Diagnostic> = Run(c).apply {
        for (p in c.props) if (tokens.exists(p.name) || c.uses.tokens.any { it.name == p.name })
            d += Diagnostic(p.span, "prop-shadows-token", "Prop '${p.name}' has a token's name: @${p.name} would mean both", "rename the prop")
        for (t in c.uses.tokens) if (!tokens.exists(t.name)) d += Diagnostic(t.span, "unknown-token", "No token '${t.name}'")
        node(c.view, null, emptySet(), place = true)
        c.variants.forEach { v -> v.nodes.forEach { node(it, null, emptySet(), place = false) } }
        c.fallback?.let { node(it, null, emptySet(), place = true) }
    }.d

    private inner class Run(val c: ComponentDef) {
        val d = ArrayList<Diagnostic>()
        val props = c.props.map { it.name }
        val states = c.state.map { it.name }
        val sources = c.uses.sources.map { it.name }

        fun node(n: ViewNode, parent: PrimitiveDef?, scope: Set<String>, place: Boolean) {
            val def = Primitives.ALL[n.primitive]
            if (def == null) {   // its children are still checked, for every finding at once
                d += unknown(n.nameSpan, "unknown-primitive", "primitive", n.primitive, Primitives.ALL.keys)
                return n.children.forEach { node(it, null, scope, place = false) }
            }
            if (place && parent != null && !parent.container)
                d += Diagnostic(n.nameSpan, "misplaced", "'${parent.name}' cannot hold children: put '${n.primitive}' beside it in a row or a stack")
            else if (place && def.parents != null && parent?.name !in def.parents)
                d += Diagnostic(n.nameSpan, "misplaced", "'${def.name}' goes only in ${def.parents.joinToString(" or ")}, not in ${parent?.name ?: "the root"}")
            n.args.forEachIndexed { k, a ->
                val arg = def.arg
                if (arg != null && k == 0) value(arg, a, scope)
                else d += Diagnostic(a.span, "unexpected-argument", if (arg == null) "'${def.name}' takes no argument: name its attributes (key=value)"
                    else "'${def.name}' takes one argument, its ${arg.name}")
            }
            val inner = scope + listOfNotNull(((n.attrs["as"]?.value as? AttrValue.Literal)?.value as? KdlValue.Str)?.value)
            for (a in n.attrs.values) {
                val ad = def.attrs[a.name]
                if (ad == null) d += unknown(a.keySpan, "unknown-attribute", "attribute of '${def.name}'", a.name, def.attrs.keys)
                else value(ad, a.value, inner)
            }
            n.children.forEach { node(it, def, inner, place = true) }
        }

        fun value(ad: AttrDef, v: AttrValue, scope: Set<String>) {
            if (!ad.bindable && (v is AttrValue.State || v is AttrValue.Expr))
                d += Diagnostic(v.span, "not-bindable", "'${ad.name}' is fixed when the component loads: it takes ${ad.type.name}, not a binding")
            when (v) {
                is AttrValue.Literal -> literal(ad, v.value)
                is AttrValue.State -> state(v.name, v.span, scope)
                is AttrValue.Ref -> ref(v.name, v.span)
                is AttrValue.Expr -> BRACES.findAll(v.text).forEach { b ->
                    NAMES.findAll(b.groupValues[1]).forEach { m -> reference(m.groupValues[1], m.groupValues[2], v.span, scope) }
                }
            }
        }

        fun literal(ad: AttrDef, v: KdlValue) {
            val s = (v as? KdlValue.Str)?.value
            if (s != null && (s.startsWith("$") || s.startsWith("@")))
                d += Diagnostic(v.span, "expression-syntax", "'$s' is neither a reference nor plain text",
                    "a reference alone is \$name or @name; anything more goes in braces: \"{\$level}%\", \"{@size * 3}\"")
            else if (!ad.type.accepts(v))
                d += Diagnostic(v.span, "wrong-type", "'${ad.name}' takes ${ad.type.name}, found ${shown(v)}", ad.default?.let { "for example ${ad.name}=${if (it is Boolean) "#$it" else it}" })
        }

        fun reference(prefix: String, name: String, span: Span, scope: Set<String>) = when {
            name.isEmpty() -> {}
            prefix == "$" -> state(name.substringBefore('.'), span, scope)
            prefix == "@" -> ref(name, span)
            '.' in name && name.substringBefore('.') !in scope && name.substringBefore('.') !in sources -> {
                val source = name.substringBefore('.')
                d += Diagnostic(span, "source-not-in-uses", "'$name' reads the source '$source', which uses does not declare", "uses { source $source }")
            }
            else -> {}
        }

        fun state(name: String, span: Span, scope: Set<String>) {
            if (name !in states && name !in scope) d += unknown(span, "unknown-state", "state", name, states)
        }

        fun ref(name: String, span: Span) {
            if (name !in props && !tokens.exists(name))
                d += Diagnostic(span, "unknown-token", "'@$name' is neither a prop of this component nor a token",
                    nearest(name, props)?.let { "did you mean '@$it'?" })
        }

        fun shown(v: KdlValue): String = (v.type?.let { "($it)" } ?: "") + when (v) {
            is KdlValue.Str -> "\"${v.value}\""
            is KdlValue.Num -> v.raw
            is KdlValue.Bool -> "#${v.value}"
            is KdlValue.Null -> "#null"
        }
    }
}
