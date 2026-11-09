package dev.launcher.engine.ui

import android.graphics.Canvas
import android.graphics.RenderNode
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import dev.launcher.engine.expr.Compiled
import dev.launcher.engine.expr.Deps
import dev.launcher.engine.expr.Env
import dev.launcher.engine.expr.Value
import dev.launcher.engine.kdl.KdlValue
import dev.launcher.engine.layout.Align
import dev.launcher.engine.layout.Arranged
import dev.launcher.engine.layout.Child
import dev.launcher.engine.layout.Constraints
import dev.launcher.engine.layout.Frame
import dev.launcher.engine.layout.Justify
import dev.launcher.engine.layout.Layout
import dev.launcher.engine.layout.Measured
import dev.launcher.engine.layout.Measurer
import dev.launcher.engine.layout.Units
import dev.launcher.engine.model.AttrValue
import dev.launcher.engine.model.ComponentDef
import dev.launcher.engine.model.ViewNode

/** Draws one primitive's own content (fill, text, symbol) with Android's canvas; its children draw themselves. */
interface Painter {
    /** What the drawing depends on, read from [node]'s values: the content is recorded again only when it changes. */
    fun release(node: HostNode) {}
    fun key(node: HostNode): Any?
    fun paint(canvas: Canvas, node: HostNode, width: Int, height: Int)
    /** Exact drawing bounds; legacy painters still receive the allocation through the Int overload. */
    fun paint(canvas: Canvas, node: HostNode, width: Float, height: Float) =
        paint(canvas, node, kotlin.math.ceil(width).toInt(), kotlin.math.ceil(height).toInt())
    /** A leaf's own size (text, icon), or null for a container, which its layout sizes. */
    fun measure(node: HostNode, maxWidth: Int, maxHeight: Int): Measured? = null
}

/**
 * One attribute's value: a literal, or a compiled expression ([compiled], with its dependency set) whose latest value
 * is [state], one state per binding. Layout reads it in measuring and painters in drawing, never composition, so a
 * change measures or draws again and never composes.
 */
class Binding internal constructor(val compiled: Compiled?, initial: Value) {
    val state: MutableState<Value> = mutableStateOf(initial)
    val deps: Deps? get() = compiled?.deps
}

/**
 * Moves a primitive and everything in it by its own RenderNode's properties, never Compose state: a change asks
 * [frame] for a frame and records nothing.
 */
class Motion internal constructor(private val apply: (Motion) -> Unit, private val frame: () -> Unit) {
    private var offsetX = 0f
    private var offsetY = 0f
    var translationX = 0f; set(v) { field = v; applyTranslation(); frame() }
    var translationY = 0f; set(v) { field = v; applyTranslation(); frame() }
    var alpha = 1f; set(v) { field = v; apply(this); frame() }
    var scale = 1f; set(v) { field = v; apply(this); frame() }
    internal fun place(x: Float, y: Float) { offsetX = x; offsetY = y; applyTranslation() }
    internal val drawX get() = translationX + offsetX
    internal val drawY get() = translationY + offsetY
    private fun applyTranslation() { apply(this) }
}

/** A primitive of the hosted tree: its bindings, children and painter, and the two RenderNodes it draws through. */
class HostNode internal constructor(
    val view: ViewNode,
    val bindings: Map<String, Binding>,
    children: List<HostNode>,
    val painter: Painter,
    private val units: Units,
    requestFrame: () -> Unit,
    /** Read in measuring and drawing: bumped when what no binding holds changed (the tokens: a font, a theme). */
    internal val epoch: MutableIntState,
) {
    /** The painter's recording, kept until its key changes. */
    private val ownLazy = lazy { RenderNode("${view.primitive}-own") }
    internal val own by ownLazy
    /** The primitive with its children: what [motion] moves. */
    private val frameLazy = lazy { RenderNode(view.primitive) }
    internal val frame by frameLazy
    /** The root's `visible` (a root cannot be planned out: a component has one): it and all under it draw nothing. */
    internal val hidden = mutableStateOf(false)
    private val childState = mutableStateOf(children)
    val children: List<HostNode> get() = childState.value
    internal fun children(value: List<HostNode>) { childState.value = value }
    internal fun release() {
        painter.release(this)
        if (ownLazy.isInitialized()) own.discardDisplayList()
        if (frameLazy.isInitialized()) frame.discardDisplayList()
    }
    internal var ownKey: Any? = Unrecorded
    /** How often the painter's content and the frame were recorded (E2 slice 6's measure of the draw node's cost). */
    var ownRecords = 0; internal set
    /** Its centre line at its last measure, in px from its top, unrounded (layout's alignment lines are whole pixels). */
    var centre = 0f; internal set
    var measured = Measured(dev.launcher.engine.layout.Size(0, 0)); internal set
    val exactWidth get() = measured.exactWidth
    val exactHeight get() = measured.exactHeight
    var frameRecords = 0; internal set
    val motion = Motion({ m ->
        frame.translationX = m.drawX; frame.translationY = m.drawY
        frame.alpha = m.alpha; frame.scaleX = m.scale; frame.scaleY = m.scale
    }, requestFrame)

    /** An attribute's current value (an argument under its schema name: text's `content`, icon's `symbol`). */
    fun value(name: String): Value = bindings[name]?.state?.value ?: Value.Null
    fun number(name: String): Float? = (value(name) as? Value.Num)?.value?.toFloat()
    fun length(name: String): Float? = (value(name) as? Value.Num)?.let { units.px(it.value, it.unit) }
    private fun word(name: String) = (value(name) as? Value.Str)?.value

    val flex: Float get() = number("flex") ?: 0f

    fun frame() = Frame(length("width"), length("height"), length("min-width") ?: 0f, length("max-width") ?: Float.POSITIVE_INFINITY,
        length("min-height") ?: 0f, length("max-height") ?: Float.POSITIVE_INFINITY,
        length("padding-start") ?: length("padding") ?: 0f, length("padding-top") ?: length("padding") ?: 0f,
        length("padding-end") ?: length("padding") ?: 0f, length("padding-bottom") ?: length("padding") ?: 0f)

    /** Lays this primitive out over its children's measures (5a's maths). */
    fun arrange(c: Constraints, kids: List<Child>): Arranged = when (view.primitive) {
        "row" -> Layout.row(frame(), c, length("gap") ?: 0f, Justify.valueOf(word("justify").enumName("START")),
            Align.valueOf(word("align").enumName("CENTER")), kids)
        "stack" -> Layout.stackAlign(word("align") ?: "center").let { (x, y) -> Layout.stack(frame(), c, x, y, kids, word("align") == "centre-line") }
        "box" -> Layout.box(frame(), c, kids)
        "spacer" -> Arranged(Layout.spacer(length("size") ?: 0f, c), emptyList(), emptyList())
        else -> Layout.leaf(frame(), c, Measurer { w, h -> painter.measure(this, w, h) ?: Measured(dev.launcher.engine.layout.Size(0, 0)) })
    }

    private fun String?.enumName(default: String) = this?.uppercase()?.replace('-', '_') ?: default

    internal object Unrecorded
}

/**
 * A component made ready to show: its view tree with every attribute bound ([Binding]), the literals as values, the
 * rest compiled once. [refresh] evaluates bindings again; [Content] (ComposeHost.kt) shows the tree.
 */
class ComponentHost(
    val def: ComponentDef,
    private val env: Env,
    private val units: Units,
    private val painters: (ViewNode) -> Painter,
    private val requestFrame: () -> Unit = {},
    private val onRemoved: (HostNode) -> Unit = {},
) {
    private val props = def.props.map { it.name }.toSet()
    private val epoch = mutableIntStateOf(0)
    private val compiled = HashMap<AttrValue, Compiled>()
    private data class Plan(val view: ViewNode, val key: List<String>, val scope: Map<String, Value>, val children: List<Plan>)
    private data class Instance(val node: HostNode, var scope: Map<String, Value>)
    private var instances = LinkedHashMap<List<String>, Instance>()
    private var count = 0
    private var rootShown = true
    private var disposed = false
    /** At most one data-only error per refresh; invalid repeats disappear whole and may recover. */
    var diagnostics: List<String> = emptyList(); private set
    val root: HostNode

    init { root = reconcile(plan(), { true }) }

    fun invalidateAll() { epoch.intValue++ }

    fun refresh(reads: (Deps) -> Boolean = { true }) = settled(reads, null)

    /** [refresh], then [settle] in the same snapshot; true from it: a prop it worked out changed, and those reading props are evaluated again. */
    fun settled(reads: (Deps) -> Boolean, settle: (() -> Boolean)?) {
        if (!disposed) Snapshot.withMutableSnapshot {
            reconcile(plan(), reads)
            // In one snapshot with the change that caused it: no frame sees the new values with an old derived prop (T-007).
            if (settle?.invoke() == true) reconcile(plan()) { it.props.isNotEmpty() }
        }
    }

    /** Releases every removed node exactly once, including the root on component teardown. */
    fun dispose() {
        if (disposed) return
        disposed = true
        instances.values.forEach { release(it.node) }
        instances.clear()
        compiled.clear()
    }

    private fun release(node: HostNode) { node.release(); onRemoved(node) }
    private class InvalidRepeat(val limit: Boolean = false) : RuntimeException()
    private fun error() { diagnostics = listOf("Invalid repeat or component expansion limit (256 items, 4096 nodes)") }
    private fun scoped(scope: Map<String, Value>) = object : Env by env {
        override fun source(path: String): Value {
            val name = path.substringBefore('.')
            val item = scope[name] ?: return env.source(path)
            return if (path == name) item else item.member(path.substringAfter('.'))
        }
    }

    private fun expression(a: AttrValue): Compiled = compiled.getOrPut(a) {
        Compiled.of(when (a) {
            is AttrValue.Expr -> a.text
            is AttrValue.State -> "{\$${a.name}}"
            is AttrValue.Ref -> "{@${a.name}}"
            is AttrValue.Literal -> error("literal is not an expression")
        }, props)
    }
    private fun evaluate(a: AttrValue, scope: Map<String, Value>): Value =
        if (a is AttrValue.Literal) literal(a.value) else expression(a).value(scoped(scope))

    /** Preflight plain plans before allocating any HostNode or painter, including nested products. */
    private fun plan(): Plan {
        count = 0; diagnostics = emptyList()
        return try {
            if ("each" in def.view.attrs) throw InvalidRepeat()
            rootShown = shown(def.view, emptyMap())
            if (rootShown) planNode(def.view, emptyList(), emptyMap()) else Plan(def.view, emptyList(), emptyMap(), emptyList())
        } catch (_: InvalidRepeat) {
            error()
            Plan(def.view, emptyList(), emptyMap(), emptyList())
        }
    }
    private fun planNode(v: ViewNode, key: List<String>, scope: Map<String, Value>): Plan {
        if (++count > 4096 || key.size > 128) throw InvalidRepeat(limit = true)
        val children = v.children.flatMapIndexed { index, child ->
            val childKey = key + index.toString()
            val each = child.attrs["each"]?.value
            if (each == null) { if (shown(child, scope)) listOf(planNode(child, childKey, scope)) else emptyList() } else {
                val before = count
                try {
                    val alias = ((child.attrs["as"]?.value as? AttrValue.Literal)?.value as? KdlValue.Str)?.value
                        ?: throw InvalidRepeat()
                    val items = (evaluate(each, scope) as? Value.List)?.items ?: throw InvalidRepeat()
                    if (items.size > 256) throw InvalidRepeat(limit = true)
                    val ids = HashSet<String>()
                    items.mapNotNull { item ->
                        val id = (item.member("id") as? Value.Str)?.value ?: throw InvalidRepeat()
                        if (id.isEmpty() || id.length > 256 || !ids.add(id)) throw InvalidRepeat()
                        val inner = scope + (alias to item)
                        if (shown(child, inner)) planNode(child, childKey + id, inner) else null
                    }
                } catch (e: InvalidRepeat) {
                    count = before
                    if (e.limit && scope.isNotEmpty()) throw e
                    error(); emptyList()
                }
            }
        }
        return Plan(v, key, scope, children)
    }

    /** `visible`: a node that is not shown is not there (its slot's arrival and leaving are motion: slice 13). */
    private fun shown(v: ViewNode, scope: Map<String, Value>): Boolean =
        v.attrs["visible"]?.value?.let { evaluate(it, scope) != Value.Bool.FALSE } ?: true

    private fun reconcile(plan: Plan, reads: (Deps) -> Boolean): HostNode {
        val next = LinkedHashMap<List<String>, Instance>()
        fun visit(p: Plan): HostNode {
            val old = instances[p.key]
            val instance = old ?: run {
                val arg = dev.launcher.engine.model.Primitives.ALL[p.view.primitive]?.arg?.name
                val named = p.view.attrs.filterKeys { it != "each" && it != "as" }.mapValues { it.value.value } +
                    listOfNotNull(arg?.let { a -> p.view.args.firstOrNull()?.let { a to it } })
                val bindings = named.mapValues { (_, a) ->
                    Binding(if (a is AttrValue.Literal) null else expression(a), evaluate(a, p.scope))
                }
                Instance(HostNode(p.view, bindings, emptyList(), painters(p.view), units, requestFrame, epoch), p.scope)
            }
            val changed = instance.scope != p.scope
            instance.scope = p.scope
            if (old != null) for (b in instance.node.bindings.values) {
                val c = b.compiled ?: continue
                if (changed || reads(c.deps)) {
                    val value = c.value(scoped(p.scope))
                    if (value != b.state.value) b.state.value = value
                }
            }
            next[p.key] = instance
            instance.node.children(p.children.map(::visit))
            return instance.node
        }
        val node = visit(plan)
        if (node.hidden.value == rootShown) node.hidden.value = !rootShown
        instances.filterKeys { it !in next }.values.forEach { release(it.node) }
        instances = next
        return node
    }

    private fun literal(v: KdlValue): Value = when (v) {
        is KdlValue.Str -> Value.Str(v.value)
        is KdlValue.Num -> Value.Num(v.double, v.type)
        is KdlValue.Bool -> Value.Bool.of(v.value)
        is KdlValue.Null -> Value.Null
    }
}
