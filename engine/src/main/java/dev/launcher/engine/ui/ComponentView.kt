package dev.launcher.engine.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.launcher.engine.expr.Env
import dev.launcher.engine.expr.Value
import dev.launcher.engine.kdl.KdlValue
import dev.launcher.engine.layout.Units
import dev.launcher.engine.model.ComponentDef
import dev.launcher.engine.model.ViewNode
import dev.launcher.engine.source.SourceBinder
import dev.launcher.engine.source.Sources
import dev.launcher.engine.token.Tokens
import kotlin.math.floor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * A component shown in a window (docs/E2_PLAN.md §b, "Hosting inside the shade's window"): a [ComposeView] on the
 * window's own thread (the shade's: gesture nav's `nav-ui`), with a [ThreadOwner], its sources bound per frame by a
 * [SourceBinder] on that thread, the tokens' changes followed while attached, and the component placed so its centre
 * line sits at [centreY]. Its owner calls [dispose] when the window goes (never a view detach alone: Android tears a
 * removed window down only later, when the next is added): it stops everything it started, attached or not, and it is
 * not reused.
 *
 * It starts at alpha 0, composed and drawn but not seen (the first composition is slow: E2 slice 6's warm-up); [show]
 * brings it in. Every call comes from the window's thread.
 */
class ComponentView(
    context: Context,
    val def: ComponentDef,
    private val tokens: Tokens,
    private val sources: Sources,
    private val units: Units,
    painters: (ViewNode, Tokens) -> Painter = ::defaultPainter,
) {
    private val handler = Handler(Looper.myLooper() ?: error("ComponentView needs a Looper thread"))
    private val owner = ThreadOwner()
    /**
     * Its own recomposer on this thread's frames: Compose would otherwise make one for the whole window from its root
     * view's lifecycle owner, and a component is a view among others in a window it does not own (the shade's).
     */
    private val recomposeScope = CoroutineScope(AndroidUiDispatcher.CurrentThread)
    private val recomposer = Recomposer(recomposeScope.coroutineContext)
    private val binder = SourceBinder.onLooper(sources, handler.looper)
    private val props = HashMap<String, Value>()
    private val centre = mutableFloatStateOf(0f)
    private var disposed = false
    private val textPainters = ArrayList<TextPainter>()

    private val base = object : Env {
        private val states = def.state.associate { it.name to literal(it.initial) }
        private val defaults = def.props.associate { it.name to (it.default?.let(::literal) ?: Value.Null) }
        override fun state(name: String) = states[name] ?: Value.Null
        override fun prop(name: String) = props[name] ?: defaults[name] ?: Value.Null
        override fun token(key: String) = tokens.token(key)
        override fun source(path: String) = Value.Null
        override fun now() = System.currentTimeMillis()
    }

    private val host: ComponentHost
    private val tokensChanged: () -> Unit = { handler.post { if (!disposed) { host.refresh { it.tokens.isNotEmpty() }; host.invalidateAll(); applied() } } }

    val view = ComposeView(context)

    init {
        // The binder first: the host's first values read what the sources hold now (SourcesTest's order).
        val env = binder.env(base)
        var built: ComponentHost? = null
        binder.start(def.uses.sources.map { it.name }) { reads -> built?.let { it.refresh(reads); applied() } }
        host = ComponentHost(def, env, units, { v -> painters(v, tokens).also { if (it is TextPainter) textPainters += it } }, { view.invalidate() })
        built = host
        tokens.onChange(tokensChanged)
        owner.start()
        recomposeScope.launch(start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        view.setParentCompositionContext(recomposer)
        view.alpha = 0f
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            Layout(content = { host.Content() }) { measurables, constraints ->
                val p = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
                // The centre line exactly on [centreY]: placed at whole pixels, the rest moved by the root's own node (a
                // baseline rounded differently from StatusBarView's moved a glyph by a pixel in a font whose ascent is
                // not whole, measured on the emulator).
                val top = centre.floatValue - host.root.centre
                val whole = floor(top)
                if (host.root.motion.translationY != top - whole) host.root.motion.translationY = top - whole
                layout(constraints.maxWidth, constraints.maxHeight) { p.place(0, whole.toInt()) }
            }
        }
    }

    /** Where the component's centre line sits, in px from the window's top (read in layout: a change places it again). */
    var centreY: Float
        get() = centre.floatValue
        set(v) { if (v != centre.floatValue) { centre.floatValue = v; applied() } }

    /** A prop's value from the window (the clock's pattern, the bar's tone); bindings reading it are evaluated again. */
    fun setProp(name: String, value: Value) {
        if (props[name] == value) return
        props[name] = value
        host.refresh { name in it.props }
        applied()
    }

    /** The unit (px a point) now: every primitive measured and drawn again when it changed. */
    fun setUnit(unitPx: Float) {
        if (unitPx == units.unitPx) return
        units.unitPx = unitPx
        host.invalidateAll()
        applied()
    }

    /** Seen or not (the view's alpha: composed and drawn either way; never the window's parameters). */
    fun show(on: Boolean) { view.alpha = if (on) 1f else 0f }

    /** What the warm-up and the draw node did: text paints on the GPU and elsewhere, recordings of content and frames. */
    fun counters(): String {
        val nodes = generateSequence(listOf(host.root)) { level -> level.flatMap { it.children }.takeIf { it.isNotEmpty() } }.flatten().toList()
        return "hardware paints ${textPainters.sumOf { it.hardwarePaints }}, software ${textPainters.sumOf { it.softwarePaints }}, " +
            "content recorded ${nodes.sumOf { it.ownRecords }}, frames recorded ${nodes.sumOf { it.frameRecords }}"
    }

    val isDisposed get() = disposed
    private val onDispose = ArrayList<() -> Unit>()

    /** [f] runs once at [dispose] (what the window registered for this component: observers, listeners). */
    fun addOnDispose(f: () -> Unit) { if (disposed) f() else onDispose += f }

    /** Stops everything this component started (sources, tokens, recomposer, lifecycle); once, and safe before attaching. */
    fun dispose() {
        if (disposed) return
        disposed = true
        onDispose.forEach { it() }
        onDispose.clear()
        tokens.removeOnChange(tokensChanged)
        binder.stop()
        owner.stop()
        recomposer.cancel()
        recomposeScope.cancel()
    }

    /** State written off the main thread reaches the composition at once, not at main's next frame (step 0). */
    private fun applied() = Snapshot.sendApplyNotifications()

    companion object {
        fun defaultPainter(v: ViewNode, tokens: Tokens): Painter = when (v.primitive) {
            "text" -> TextPainter(tokens)
            else -> Nothing
        }

        /** Containers draw nothing of their own yet (`box`'s fill and `icon` come in E2 slices 9 and 10). */
        private object Nothing : Painter {
            override fun key(node: HostNode) = null
            override fun paint(canvas: android.graphics.Canvas, node: HostNode, width: Int, height: Int) {}
        }

        private fun literal(v: KdlValue): Value = when (v) {
            is KdlValue.Str -> Value.Str(v.value)
            is KdlValue.Num -> Value.Num(v.double, v.type)
            is KdlValue.Bool -> Value.Bool.of(v.value)
            is KdlValue.Null -> Value.Null
        }
    }
}
