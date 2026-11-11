package dev.launcher.engine.model

import dev.launcher.engine.kdl.KdlDocument
import dev.launcher.engine.kdl.KdlNode
import dev.launcher.engine.kdl.KdlValue
import dev.launcher.engine.kdl.Span

/**
 * Reads a [ComponentDef] from a parsed document: the file's shape (one `component`, its sections, props with known
 * types, state with one value each, `view` and `fallback` with one root primitive). It never throws for what a file gets
 * wrong: every finding is a [Diagnostic], all of them at once. What the view's primitives and values mean is
 * [Validator]'s.
 */
object ComponentReader {
    private val SECTIONS = listOf("props", "uses", "state", "view", "fallback", "script")
    private val USES = listOf("source", "action", "token")
    private val STATE = Regex("\\$[A-Za-z_][\\w-]*")
    private val REF = Regex("@[A-Za-z_][\\w.-]*")

    fun read(doc: KdlDocument): ReadResult {
        val d = ArrayList<Diagnostic>()
        val root = doc.nodes.firstOrNull()
        val name = (root?.args?.firstOrNull() as? KdlValue.Str)?.value
        when {
            root == null -> d += Diagnostic(Span(0, 0, 1, 1), "not-a-component", "The file is empty: a component file holds component \"name\" { ... }")
            doc.nodes.size > 1 -> d += Diagnostic(doc.nodes[1].nameSpan, "not-a-component", "A component file holds one node, its component: '${doc.nodes[1].name}' is a second")
            root.name != "component" -> d += unknown(root.nameSpan, "not-a-component", "top node", root.name, listOf("component"))
            name == null -> d += Diagnostic(root.nameSpan, "not-a-component", "A component needs a name: component \"name\" { ... }")
        }
        if (d.isNotEmpty() || root == null || name == null) return ReadResult.Invalid(d)

        val sections = HashMap<String, KdlNode>()
        for (s in root.children) when {
            s.name !in SECTIONS -> d += unknown(s.nameSpan, "unknown-section", "section", s.name, SECTIONS)
            sections.put(s.name, s) != null -> d += Diagnostic(s.nameSpan, "duplicate-section", "'${s.name}' is written twice: one ${s.name} block per component")
        }
        val props = sections["props"]?.children.orEmpty().mapNotNull { prop(it, d) }
        val uses = sections["uses"]?.children.orEmpty().onEach { u ->
            if (u.name !in USES) d += unknown(u.nameSpan, "unknown-use", "use", u.name, USES)
        }.let { all -> USES.map { kind -> all.filter { it.name == kind }.flatMap { u -> u.args.map { Named(text(it), it.span) } } } }
        val state = sections["state"]?.children.orEmpty().mapNotNull { s ->
            s.args.singleOrNull()?.takeIf { s.props.isEmpty() && s.children.isEmpty() }?.let { StateDef(s.name, it, s.nameSpan) }
                ?: null.also { d += Diagnostic(s.nameSpan, "bad-state", "A state value is its name and one initial value: level 100") }
        }
        val viewSection = sections["view"]
        if (viewSection == null) d += Diagnostic(root.nameSpan, "missing-view", "A component needs a view { ... } block")
        val view = viewSection?.let { rootOf(it, d) }
        val variants = viewSection?.children.orEmpty().filter { it.name == "variant" }.map {
            Variant((it.args.firstOrNull() as? KdlValue.Str)?.value ?: "", it.children.map(::node), it.span)
        }
        val fallback = sections["fallback"]?.let { rootOf(it, d) }
        val script = (sections["script"]?.args?.firstOrNull() as? KdlValue.Str)?.value
        if (d.isNotEmpty() || view == null) return ReadResult.Invalid(d)
        val version = (root.props["version"]?.value as? KdlValue.Str)?.value
        return ReadResult.Ok(ComponentDef(name, version, props, Uses(uses[0], uses[1], uses[2]), state, view, variants,
            fallback, script, root.span))
    }

    private fun text(v: KdlValue) = (v as? KdlValue.Str)?.value ?: (v as? KdlValue.Num)?.raw ?: v.toString()

    private fun prop(n: KdlNode, d: MutableList<Diagnostic>): PropDef? {
        val typeValue = n.props["type"]?.value
        val typeName = (typeValue as? KdlValue.Str)?.value
        val type = typeName?.let { AttrType.BY_NAME[it] }
        when {
            typeValue == null -> d += Diagnostic(n.nameSpan, "missing-type", "Prop '${n.name}' needs a type: ${n.name} type=string")
            type == null -> d += unknown(typeValue.span, "unknown-type", "prop type", typeName ?: text(typeValue), AttrType.BY_NAME.keys)
        }
        val default = n.props["default"]?.value
        if (type != null && default != null && !type.accepts(default))
            d += Diagnostic(default.span, "wrong-type", "Prop '${n.name}' is ${type.name}: its default does not fit")
        return type?.let { PropDef(n.name, it, default, n.nameSpan) }
    }

    /** The one primitive a view or fallback holds (variants apart). */
    private fun rootOf(section: KdlNode, d: MutableList<Diagnostic>): ViewNode? {
        val roots = section.children.filter { section.name != "view" || it.name != "variant" }
        if (roots.size != 1) d += Diagnostic(section.nameSpan, "view-root",
            "'${section.name}' holds one primitive (put several in a row or a stack): found ${roots.size}")
        return roots.singleOrNull()?.let(::node)
    }

    private fun node(n: KdlNode): ViewNode = ViewNode(n.name, n.args.map(::value),
        n.props.mapValues { (k, p) -> Attr(k, p.keySpan, if (k in MotionGrammar.ATTRS) AttrValue.Literal(p.value) else value(p.value)) },
        n.children.map(::node), n.span, n.nameSpan,
        MotionGrammar.read(n.props.filterKeys { it in MotionGrammar.ATTRS }.mapValues { AttrValue.Literal(it.value.value) }).spec)

    /** Sorts a value: a whole `$name` or `@name` is a reference, a string with `{` an expression, the rest literals. */
    internal fun value(v: KdlValue): AttrValue {
        val s = (v as? KdlValue.Str)?.value
        return when {
            s == null || v.type != null -> AttrValue.Literal(v)
            STATE.matches(s) -> AttrValue.State(s.drop(1), v.span)
            REF.matches(s) -> AttrValue.Ref(s.drop(1), v.span)
            '{' in s -> AttrValue.Expr(s, v.span)
            else -> AttrValue.Literal(v)
        }
    }
}
