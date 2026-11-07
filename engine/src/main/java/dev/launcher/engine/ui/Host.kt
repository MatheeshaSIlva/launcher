package dev.launcher.engine.ui

import android.graphics.Canvas
import android.graphics.RenderNode
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
    fun key(node: HostNode): Any?
    fun paint(canvas: Canvas, node: HostNode, width: Int, height: Int)
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
class Motion internal constructor(private val node: () -> RenderNode, private val frame: () -> Unit) {
    var translationX: Float get() = node().translationX; set(v) { node().translationX = v; frame() }
    var translationY: Float get() = node().translationY; set(v) { node().translationY = v; frame() }
    var alpha: Float get() = node().alpha; set(v) { node().alpha = v; frame() }
    var scale: Float get() = node().scaleX; set(v) { node().scaleX = v; node().scaleY = v; frame() }
}

/** A primitive of the hosted tree: its bindings, children and painter, and the two RenderNodes it draws through. */
class HostNode internal constructor(
    val view: ViewNode,
    val bindings: Map<String, Binding>,
    val children: List<HostNode>,
    val painter: Painter,
    private val units: Units,
    requestFrame: () -> Unit,
    /** Read in measuring and drawing: bumped when what no binding holds changed (the tokens: a font, a theme). */
    internal val epoch: MutableIntState,
) {
    /** The painter's recording, kept until its key changes. */
    internal val own by lazy { RenderNode("${view.primitive}-own") }
    /** The primitive with its children: what [motion] moves. */
    internal val frame by lazy { RenderNode(view.primitive) }
    internal var ownKey: Any? = Unrecorded
    /** How often the painter's content and the frame were recorded (E2 slice 6's measure of the draw node's cost). */
    var ownRecords = 0; internal set
    /** Its centre line at its last measure, in px from its top, unrounded (layout's alignment lines are whole pixels). */
    var centre = 0f; internal set
    var frameRecords = 0; internal set
    val motion = Motion({ frame }, requestFrame)

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
        "stack" -> Layout.stackAlign(word("align") ?: "center").let { (x, y) -> Layout.stack(frame(), c, x, y, kids) }
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
) {
    private val props = def.props.map { it.name }.toSet()
    private val all = ArrayList<Binding>()
    private val epoch = mutableIntStateOf(0)
    val root: HostNode = build(def.view)

    /** Every primitive measured and drawn again (the tokens changed under them: a font, a theme, light or dark). */
    fun invalidateAll() { epoch.intValue++ }

    /** Bindings whose dependencies [reads] picks are evaluated again; a state is written only when its value changed. */
    fun refresh(reads: (Deps) -> Boolean = { true }) {
        for (b in all) if (b.compiled != null && reads(b.deps!!)) b.compiled.value(env).let { if (it != b.state.value) b.state.value = it }
    }

    private fun build(v: ViewNode): HostNode {
        val arg = dev.launcher.engine.model.Primitives.ALL[v.primitive]?.arg?.name
        val named = v.attrs.mapValues { it.value.value } + listOfNotNull(arg?.let { a -> v.args.firstOrNull()?.let { a to it } })
        val bindings = named.mapValues { (_, a) -> bind(a).also { all += it } }
        return HostNode(v, bindings, v.children.map(::build), painters(v), units, requestFrame, epoch)
    }

    private fun bind(a: AttrValue): Binding = when (a) {
        is AttrValue.Literal -> Binding(null, literal(a.value))
        is AttrValue.State -> compiled("{\$${a.name}}")
        is AttrValue.Ref -> compiled("{@${a.name}}")
        is AttrValue.Expr -> compiled(a.text)
    }

    private fun compiled(text: String) = Compiled.of(text, props).let { Binding(it, it.value(env)) }

    private fun literal(v: KdlValue): Value = when (v) {
        is KdlValue.Str -> Value.Str(v.value)
        is KdlValue.Num -> Value.Num(v.double, v.type)
        is KdlValue.Bool -> Value.Bool.of(v.value)
        is KdlValue.Null -> Value.Null
    }
}
