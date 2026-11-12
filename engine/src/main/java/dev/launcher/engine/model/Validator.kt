package dev.launcher.engine.model

import dev.launcher.engine.expr.Compiled
import dev.launcher.engine.expr.Deps
import dev.launcher.engine.expr.ExprException
import dev.launcher.engine.expr.ExprParser
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
 * in `uses`, no prop named like a token, each primitive in a parent that may hold it. Expressions are parsed
 * ([ExprParser]) and their references come from their dependency sets ([Deps]); a finding inside one points at its place
 * in the file where the value is a plain quoted string on one line, else at the whole value. Every finding is a
 * [Diagnostic]; nothing throws.
 */
class Validator(private val tokens: TokenCheck) {
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
        c.variants.forEach { v -> v.nodes.forEach { node(it, null, emptySet(), place = false, root = false) } }
        c.fallback?.let { node(it, null, emptySet(), place = true) }
    }.d

    private inner class Run(val c: ComponentDef) {
        val d = ArrayList<Diagnostic>()
        val props = c.props.map { it.name }.toSet()
        val states = c.state.map { it.name }
        val sources = c.uses.sources.map { it.name }

        fun node(n: ViewNode, parent: PrimitiveDef?, scope: Set<String>, place: Boolean, layered: Boolean = false,
                 root: Boolean = true) {
            n.attrs["place"]?.let { a ->
                if (!root) d += Diagnostic(a.value.span, "place-root-only", "'place' is allowed only on a view or fallback root, not a child or variant override")
                val anchor = ((a.value as? AttrValue.Literal)?.value as? KdlValue.Str)?.value
                if (anchor == "cutout" && "device" !in sources)
                    d += Diagnostic(a.value.span, "place-needs-device", "'place=\"cutout\"' reads display geometry from device, which uses does not declare",
                        "uses { source device }")
            }
            // How near the screen's top a root on the camera may come (T-012): with `place="cutout"` on the same root only.
            n.attrs["place-gap"]?.let { a ->
                if (!root) d += Diagnostic(a.value.span, "place-root-only", "'place-gap' is allowed only on a view or fallback root, not a child or variant override")
                else if (((n.attrs["place"]?.value as? AttrValue.Literal)?.value as? KdlValue.Str)?.value != "cutout")
                    d += Diagnostic(a.value.span, "place-gap-needs-cutout", "'place-gap' keeps a root placed on the camera off the screen's top edge; this root is not placed on the camera",
                        "place=\"cutout\"")
            }
            val def = Primitives.ALL[n.primitive]
            if (def == null) {   // its children are still checked, for every finding at once
                d += unknown(n.nameSpan, "unknown-primitive", "primitive", n.primitive, Primitives.ALL.keys)
                return n.children.forEach { node(it, null, scope, place = false, layered = layered, root = false) }
            }
            if (place && parent != null && !parent.container)
                d += Diagnostic(n.nameSpan, "misplaced", "'${parent.name}' cannot hold children: put '${n.primitive}' beside it in a row or a stack")
            else if (place && def.parents != null && parent?.name !in def.parents)
                d += Diagnostic(n.nameSpan, "misplaced", "'${def.name}' goes only in ${def.parents.joinToString(" or ")}, not in ${parent?.name ?: "the root"}")
            mediaAttributes(n)
            val each = n.attrs["each"]
            val aliasAttr = n.attrs["as"]
            val alias = ((aliasAttr?.value as? AttrValue.Literal)?.value as? KdlValue.Str)?.value
            if (each != null && parent == null && place)
                d += Diagnostic(each.value.span, "root-each", "The component must have one root; repeat a child instead")
            if (each != null && alias == null || each == null && aliasAttr != null)
                d += Diagnostic(n.nameSpan, "each-alias", "'each' and a literal 'as' alias must be given together")
            val validAlias = alias != null && Regex("[A-Za-z_][A-Za-z0-9_]*").matches(alias) &&
                alias !in scope && alias !in states && alias !in sources && alias !in props && alias != "now"
            if (alias != null && !validAlias)
                d += Diagnostic(aliasAttr!!.value.span, "invalid-alias", "Alias must be an identifier and must not shadow an existing name")
            val inner = if (each != null && validAlias) scope + alias!! else scope
            n.args.forEachIndexed { k, a ->
                val arg = def.arg
                if (arg != null && k == 0) value(arg, a, inner)
                else d += Diagnostic(a.span, "unexpected-argument", if (arg == null) "'${def.name}' takes no argument: name its attributes (key=value)"
                    else "'${def.name}' takes one argument, its ${arg.name}")
            }
            d += MotionGrammar.read(n.attrs.filterKeys { it in MotionGrammar.ATTRS }.mapValues { it.value.value }).diagnostics
            if (n.primitive != "text") n.attrs["roll"]?.let {
                d += Diagnostic(it.value.span, "motion-roll-not-text", "'roll' is supported only on text, not '${n.primitive}'")
            }
            for (a in n.attrs.values) {
                if (a.name in MotionGrammar.ATTRS) continue
                val ad = def.attrs[a.name]
                if (ad == null) d += unknown(a.keySpan, "unknown-attribute", "attribute of '${def.name}'", a.name, def.attrs.keys)
                else value(ad, a.value, if (a.name == "each") scope else inner)
            }
            val blend = ((n.attrs["blend"]?.value as? AttrValue.Literal)?.value as? KdlValue.Str)?.value
            if (blend == "knockout" && !layered)
                d += Diagnostic(n.attrs.getValue("blend").value.span, "knockout-needs-layer",
                    "Knockout text needs an ancestor with layer=#true")
            val enabled = ((n.attrs["layer"]?.value as? AttrValue.Literal)?.value as? KdlValue.Bool)?.value == true
            n.children.forEach { node(it, def, inner, place = true, layered = layered || enabled, root = false) }
        }

        /** Literals are checked here; runtime bindings must be bounded again by the drawing state. */
        fun mediaAttributes(n: ViewNode) {
            fun number(name: String, low: Double, high: Double) {
                val v = ((n.attrs[name]?.value as? AttrValue.Literal)?.value as? KdlValue.Num) ?: return
                if (!v.double.isFinite() || v.double !in low..high || v.type != null && name in setOf("loop-period", "loop-phase"))
                    d += Diagnostic(v.span, "motion-range", "'$name' must be in $low..$high")
            }
            if (n.primitive == "box") {
                val loop = n.attrs.keys.any { it.startsWith("loop-") }
                if (loop && ("loop-from" !in n.attrs || "loop-to" !in n.attrs))
                    d += Diagnostic(n.nameSpan, "loop-endpoints", "A loop needs both loop-from and loop-to")
                number("loop-from", 0.0, Double.MAX_VALUE)
                number("loop-to", 0.0, Double.MAX_VALUE)
                number("loop-period", 200.0, 10000.0)
                number("loop-phase", 0.0, 1.0)
            }
            if (n.primitive == "text") {
                number("fade", 0.0, Double.MAX_VALUE)
                number("decode", 0.0, dev.launcher.engine.ui.DecodeText.MAX_MS.toDouble())
            }
        }

        fun value(ad: AttrDef, v: AttrValue, scope: Set<String>) {
            if (!ad.bindable && (v !is AttrValue.Literal))
                d += Diagnostic(v.span, "not-bindable", "'${ad.name}' is fixed when the component loads: it takes ${ad.type.name}, not a binding")
            when (v) {
                is AttrValue.Literal -> literal(ad, v.value)
                is AttrValue.State -> state(v.name, v.span, scope)
                is AttrValue.Ref -> ref(v.name, v.span)
                is AttrValue.Expr -> expression(v, scope)
            }
        }

        fun literal(ad: AttrDef, v: KdlValue) {
            if (ad.type === AttrType.PICTURE) {
                d += Diagnostic(v.span, "picture-not-literal", "A picture must come from a source binding; literals cannot name pictures")
                return
            }
            val s = (v as? KdlValue.Str)?.value
            if (s != null && (s.startsWith("$") || s.startsWith("@")))
                d += Diagnostic(v.span, "expression-syntax", "'$s' is neither a reference nor plain text",
                    "a reference alone is \$name or @name; anything more goes in braces: \"{\$level}%\", \"{@size * 3}\"")
            else if (!ad.type.accepts(v))
                d += Diagnostic(v.span, "wrong-type", "'${ad.name}' takes ${ad.type.name}, found ${shown(v)}", ad.default?.let { "for example ${ad.name}=${if (it is Boolean) "#$it" else it}" })
        }

        fun expression(v: AttrValue.Expr, scope: Set<String>) {
            val t = try { ExprParser.template(v.text).also { Compiled.of(it, props) } } catch (e: ExprException) {
                return run { d += Diagnostic(inside(v, e.at), e.code, e.message!!, e.hint) }
            }
            val deps = Deps.of(t, props)
            deps.states.forEach { (name, at) -> state(name, inside(v, at), scope) }
            deps.tokens.forEach { (name, at) -> ref(name, inside(v, at)) }
            for ((path, at) in deps.sources) {
                val source = path.substringBefore('.')
                if (source !in scope && source !in sources) d += Diagnostic(inside(v, at), "source-not-in-uses",
                    "'$path' reads the source '$source', which uses does not declare", "uses { source $source }")
            }
        }

        /** Where offset [at] of an expression's text is in the file: exact for a plain quoted string on one line. */
        fun inside(v: AttrValue.Expr, at: Int): Span = v.span.let {
            if (it.end - it.start != v.text.length + 2 || '\n' in v.text) it
            else Span(it.start + 1 + at, it.start + 2 + at, it.line, it.col + 1 + at)
        }

        fun state(name: String, span: Span, scope: Set<String>) {
            if (name !in states) d += unknown(span, "unknown-state", "state", name, states)
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
