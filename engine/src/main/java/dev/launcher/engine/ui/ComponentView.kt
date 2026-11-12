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
    painters: (ViewNode, Tokens, GlyphCache<Symbol>) -> Painter = ::defaultPainter,
    /** On a worker: a symbol of a set, or null (no such set or symbol). Default: the app's icon sets ([loadSymbol]). */
    symbols: (set: String, symbol: String) -> Symbol? = { set, symbol -> loadSymbol(context, set, symbol) },
    /** The capabilities this component has been given; default none, so a source needing one reads as absent. */
    granted: Set<String> = emptySet(),
    /** Pictures for `icon picture`, given only to a component that declares `notifications` and was granted its capability. */
    pictures: PictureSource? = null,
    /**
     * Pictures for `image` by the source that makes them ([Value.Picture.source]), e.g. `media`'s covers (T-013). Each is
     * given only if the component declares that source and was granted its capability; one never answers for another.
     */
    imagePictures: Map<String, PictureSource> = emptyMap(),
    /** Its motion's frames and time (ns): the display's by default; a proof steps them at exact times (13a-1b). */
    frames: dev.launcher.engine.ui.motion.FrameSource = dev.launcher.engine.ui.motion.ChoreographerSource(),
    private val clock: () -> Long = System::nanoTime,
) {
    private val allowedImages = ImageAccess.allowed(imagePictures, def.uses.sources.map { it.name }, sources, granted)
    private val allowedPictures = pictures?.takeIf { dev.launcher.engine.source.Access.allowed(def.uses.sources.map { it.name }, "notifications", "notifications.read", granted) }
    private val handler = Handler(Looper.myLooper() ?: error("ComponentView needs a Looper thread"))
    private val owner = ThreadOwner()
    /**
     * Its own recomposer on this thread's frames: Compose would otherwise make one for the whole window from its root
     * view's lifecycle owner, and a component is a view among others in a window it does not own (the shade's).
     */
    private val recomposeScope = CoroutineScope(AndroidUiDispatcher.CurrentThread)
    private val recomposer = Recomposer(recomposeScope.coroutineContext)
    private val binder = SourceBinder.onLooper(sources, handler.looper, granted) { source, capability ->
        android.util.Log.w("Launcher", "[kdl] ${def.name}: source '$source' refused: it needs $capability")
    }
    private val props = HashMap<String, Value>()
    private val centre = mutableFloatStateOf(0f)
    /** `place="cutout"` on its root (T-010): placed on the camera from the `device` source, not on [centreY]. */
    private val onCutout = ((def.view.attrs["place"]?.value as? dev.launcher.engine.model.AttrValue.Literal)?.value as? KdlValue.Str)?.value == "cutout"
    /** Read in layout when [onCutout]: a change of the device or of the view's place lays the root out again. */
    private val placeTick = androidx.compose.runtime.mutableIntStateOf(0)
    private lateinit var env: Env
    /** The resting origin the root was last placed from: a new one (the window laid out, moved) places it at once. */
    private var lastOrigin: FloatArray? = null
    private var disposed = false
    /** Whether motion is reduced, followed on this thread; the node motion reads it through [Env.reducedMotion]. */
    private val reduced = dev.launcher.engine.ui.motion.ReducedMotion.Watch(context.contentResolver, handler) { on ->
        if (on && !disposed) host.snapMotion()
    }
    /** Its nodes' motion clock: registered only while some node moves, nothing at rest (13a-0's [MotionTicker]). */
    private val ticker = dev.launcher.engine.ui.motion.MotionTicker(frames) { nanos ->
        !disposed && host.step(nanos)
    }
    private val textPainters = ArrayList<TextPainter>()
    /** Its icons' glyphs, prepared on [GLYPHS]' worker and published to this thread ([GlyphCache]). */
    private val glyphs = GlyphCache(symbols, { r -> GLYPHS.execute(r) }, { r -> handler.post(r) })
    private var prepared = false
    private var preparedLate = false
    /** True until the constructor returns: preparation that completes after it is "late" (one more frame to draw it). */
    private var constructing = true
    private val whenPrepared = ArrayList<(Boolean) -> Unit>()

    private val base = object : Env {
        private val states = def.state.associate { it.name to literal(it.initial) }
        private val defaults = def.props.associate { it.name to (it.default?.let(::literal) ?: Value.Null) }
        override fun state(name: String) = states[name] ?: Value.Null
        override fun prop(name: String) = props[name] ?: defaults[name] ?: Value.Null
        override fun token(key: String) = tokens.token(key)
        override fun source(path: String) = Value.Null
        override fun now() = System.currentTimeMillis()
        override val reducedMotion get() = reduced.on
    }

    private val host: ComponentHost
    private val tokensChanged: () -> Unit = { handler.post { if (!disposed) { host.settled({ it.tokens.isNotEmpty() }, ::settle); host.invalidateAll(); applied() } } }

    val view = ComposeView(context)

    init {
        // The binder first: the host's first values read what the sources hold now (SourcesTest's order).
        env = binder.env(base)
        var built: ComponentHost? = null
        binder.start(def.uses.sources.map { it.name }) { reads ->
            // Only the device changed: no binding need read it for the root to be placed again (Codex, T-010).
            if (onCutout && reads(DEVICE)) placeTick.intValue++
            built?.let { h -> h.settled(reads, ::settle); applied() }
        }
        host = ComponentHost(def, env, units, { v -> painters(v, tokens, glyphs).also { if (it is TextPainter) textPainters += it; if (it is IconPainter) it.pictures = allowedPictures; if (it is ImagePainter) it.pictures = allowedImages } }, { view.invalidate() }, { n -> textPainters.remove(n.painter); ShapeMask.release(n) },
            dev.launcher.engine.ui.motion.MotionContext(dev.launcher.engine.ui.motion.PresetMotion, { reduced.on }, clock), { ticker.wake() })
        built = host
        prepareGlyphs()
        constructing = false
        tokens.onChange(tokensChanged)
        owner.start()
        recomposeScope.launch(start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        view.setParentCompositionContext(recomposer)
        view.alpha = 0f
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        // Placed on the camera: any move of this view on screen (its own layout, an ancestor's, a scroll, the window's)
        // places the root again (Codex, T-010: its own bounds alone missed an ancestor moving).
        if (onCutout) {
            val moved = { if (!disposed && lastOrigin?.let { !it.contentEquals(restingOrigin()) } == true) placeTick.intValue++ }
            val layout = android.view.ViewTreeObserver.OnGlobalLayoutListener { moved() }
            val scroll = android.view.ViewTreeObserver.OnScrollChangedListener { moved() }
            view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: android.view.View) {
                    v.viewTreeObserver.addOnGlobalLayoutListener(layout); v.viewTreeObserver.addOnScrollChangedListener(scroll); moved()
                }
                override fun onViewDetachedFromWindow(v: android.view.View) {
                    v.viewTreeObserver.removeOnGlobalLayoutListener(layout); v.viewTreeObserver.removeOnScrollChangedListener(scroll)
                }
            })
        }
        view.setContent {
            Layout(content = { host.Content() }) { measurables, constraints ->
                val p = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
                // The centre line exactly on [centreY]: placed at whole pixels, the rest moved by the root's own node (a
                // baseline rounded differently from StatusBarView's moved a glyph by a pixel in a font whose ascent is
                // not whole, measured on the emulator).
                var left = 0f
                val top: Float
                if (onCutout) {
                    placeTick.intValue
                    val at = onCamera()
                    left = at.centreX - host.root.exactWidth / 2f
                    top = at.top(host.root.centre, placeGap())
                } else top = centre.floatValue - host.root.centre
                val whole = floor(top)
                val wholeX = floor(left)
                if (host.root.motion.translationY != top - whole) host.root.motion.translationY = top - whole
                if (host.root.motion.translationX != left - wholeX) host.root.motion.translationX = left - wholeX
                layout(constraints.maxWidth, constraints.maxHeight) {
                    // Its real place in the window, the baseline's fraction included (that fraction is applied apart, on
                    // the root's own translation, and its change too is carried by the place transition): a centre-line
                    // change moves it on its place transition if it declares one (Codex, 13a-1 review and sign-off).
                    host.root.placed(left, top, 0.5f, 0.5f)
                    p.place(wholeX.toInt(), whole.toInt())
                }
            }
        }
    }

    /** The root's `place-gap` in px (null without one): how near the screen's top it may come (T-012). */
    private fun placeGap(): Float? {
        val n = (def.view.attrs["place-gap"]?.value as? dev.launcher.engine.model.AttrValue.Literal)?.value as? KdlValue.Num ?: return null
        return n.raw.replace("_", "").toDoubleOrNull()?.let { units.px(it, n.type) }
    }

    /** Its root's horizontal centre and centre line on the camera, in this view's px ([CutoutPlacement]). */
    private fun onCamera(): CutoutPlacement.Place {
        fun px(name: String) = (env.source("device.$name") as? Value.Num)?.value?.toFloat()
        val cutout = if (env.source("device.cutout") == Value.Bool.TRUE) {
            val l = px("cutout-rect-left"); val t = px("cutout-rect-top"); val r = px("cutout-rect-right"); val b = px("cutout-rect-bottom")
            if (l != null && t != null && r != null && b != null) CutoutPlacement.Cutout(l, t, r, b) else null
        } else null
        val origin = restingOrigin()
        val (ox, oy, windowX, windowWidth) = origin
        // The view moved on screen: its root's last place is seen from there, so only a real change of anchor moves it.
        lastOrigin?.let { o -> if (o[0] != ox || o[1] != oy) host.root.nodeMotion?.rebase(o[0] - ox, o[1] - oy) }
        lastOrigin = origin
        return CutoutPlacement.of(cutout, px("status-height") ?: 0f, ox, oy, windowX + windowWidth / 2f)
    }

    /**
     * Where this view rests on screen, in whole px, and its window's left and width: the window's place on screen plus the
     * view's layout place in it, every view's translation and scale left out (Codex, T-010: `getLocationOnScreen` rounds a
     * place already moved, and a motion's fraction would be taken for a rest).
     */
    private fun restingOrigin(): FloatArray {
        val root = view.rootView
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        val wx = loc[0] - Math.round(root.translationX).toFloat()
        val wy = loc[1] - Math.round(root.translationY).toFloat()
        var x = wx; var y = wy
        var v: android.view.View = view
        while (v !== root) {
            val parent = v.parent as? android.view.View ?: break
            x += v.left - parent.scrollX
            y += v.top - parent.scrollY
            v = parent
        }
        return floatArrayOf(x, y, wx, root.width.toFloat())
    }

    /** Where the component's centre line sits, in px from the window's top (read in layout: a change places it again). */
    var centreY: Float
        get() = centre.floatValue
        set(v) { if (!disposed && v != centre.floatValue) { centre.floatValue = v; applied() } }

    /** A prop's value from the window (the clock's 12/24 h, the bar's tone), bindings reading it evaluated again; nothing once disposed (a delivery queued before can still come). */
    fun setProp(name: String, value: Value) {
        if (disposed || props[name] == value) return
        props[name] = value
        host.settled({ name in it.props }, ::settle)
        applied()
    }

    /**
     * Works out a prop from what the component shows now ([textWidth], [read]) and gives it with [setDerived]. It runs inside
     * every refresh's snapshot, so no frame shows new values with an old derived prop.
     */
    var derive: (() -> Unit)? = null
    private var settling = false
    private fun settle(): Boolean {
        val f = derive ?: return false
        settling = false
        f()
        return settling
    }

    /** From [derive] only. */
    fun setDerived(name: String, value: Value) {
        if (props[name] != value) { props[name] = value; settling = true }
    }

    /** Runs [derive] again for what no source tells (a layout, a font, the camera). */
    fun deriveAgain() {
        if (disposed) return
        host.settled({ false }, ::settle)
        applied()
    }

    /** A source's value now, for [derive]. */
    fun read(path: String): Value = binder.env(base).source(path)

    /** The exact width in px of the text node labelled [label] as it reads now, or null. */
    fun textWidth(label: String): Float? {
        val node = generateSequence(listOf(host.root)) { level -> level.flatMap { it.children }.takeIf { it.isNotEmpty() } }.flatten()
            .firstOrNull { it.view.primitive == "text" && (it.value("label") as? Value.Str)?.value == label } ?: return null
        return (node.painter as? TextPainter)?.measure(node, Int.MAX_VALUE, Int.MAX_VALUE)?.exactWidth
    }

    /** The root's width in px as its last layout measured it (proofs, T-012). */
    fun rootWidth(): Float = host.root.exactWidth

    /** The unit (px a point) now: every primitive measured and drawn again when it changed. */
    fun setUnit(unitPx: Float) {
        if (disposed || unitPx == units.unitPx) return
        units.unitPx = unitPx
        host.invalidateAll()
        applied()
    }

    /**
     * Every glyph its icons name now (literal or bound, as resolved at construction) is loaded off this thread before the
     * component is first shown: [f] runs on this thread once they are, with true when that came after construction
     * returned (a frame drawn before then showed placeholders: the caller waits one more frame). At once if already.
     */
    fun whenPrepared(f: (late: Boolean) -> Unit) { if (disposed) return; if (prepared) f(preparedLate) else whenPrepared += f }

    /** The glyphs its icons name now (their bindings' current values). */
    private fun iconKeys(): Set<GlyphCache.Key> =
        generateSequence(listOf(host.root)) { level -> level.flatMap { it.children }.takeIf { it.isNotEmpty() } }
            .flatten().filter { it.view.primitive == "icon" && it.value("picture") == dev.launcher.engine.expr.Value.Null }.map { GlyphCache.Key(IconPainter.set(it), IconPainter.symbol(it)) }.toSet()

    /** Every glyph its icons name now is loaded, found or absent for good (none still pending). This thread. */
    fun glyphsReady(): Boolean = iconKeys().none { glyphs.resolve(it) == GlyphCache.Pending }


    /**
     * Loads what its icons name; when that is done, looks again at what they name *then* (a prop or source may have
     * changed a name meanwhile) and loads that too, until nothing is pending: only then is it prepared.
     */
    private fun prepareGlyphs() {
        glyphs.prepare(iconKeys(), wanted = { !disposed }, by = this) {
            if (!glyphsReady()) return@prepare prepareGlyphs()
            // A prop or source may have named another glyph while the shape warmed up: looked at again first (Codex).
            warmShapes { if (!disposed) { if (glyphsReady()) finishPreparing() else prepareGlyphs() } }
        }
    }

    /** Whether any box in it may clip its children to its shape (T-011): its GPU work is done before it is shown. */
    // A function, not a property: the constructor's preparation reads it before the body's properties exist (a lazy
    // delegate too: measured, every component crashed at construction).
    private fun clips() = generateSequence(listOf(def.view)) { level -> level.flatMap { it.children }.takeIf { it.isNotEmpty() } }
        .flatten().any { it.primitive == "box" && "clip" in it.attrs }
    private var shapesWarmed = false

    /** [then] once a clipping shape has been drawn off screen ([ShapeWarmUp]), at once for a component with none. */
    private fun warmShapes(then: () -> Unit) {
        if (!clips() || shapesWarmed) return then()
        ShapeWarmUp.run(handler) { ok ->
            if (disposed) return@run
            shapesWarmed = true
            if (!ok) android.util.Log.w("Launcher", "[kdl] ${def.name}: the shape clip's warm-up failed")
            then()
        }
    }

    private fun finishPreparing() {
        run {
            prepared = true
            preparedLate = !constructing
            if (preparedLate) host.invalidateAll()
            whenPrepared.forEach { it(preparedLate) }
            whenPrepared.clear()
            applied()
        }
    }

    /** Seen or not (the view's alpha: composed and drawn either way; never the window's parameters). */
    fun show(on: Boolean) { if (!disposed) view.alpha = if (on) 1f else 0f }

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
        reduced.close()
        ticker.dispose()
        onDispose.forEach { it() }
        onDispose.clear()
        tokens.removeOnChange(tokensChanged)
        host.dispose()
        glyphs.dispose()
        whenPrepared.clear()
        binder.stop()
        owner.stop()
        recomposer.cancel()
        recomposeScope.cancel()
    }

    /** State written off the main thread reaches the composition at once, not at main's next frame (step 0). */
    private fun applied() = Snapshot.sendApplyNotifications()

    companion object {
        /** What reading any of the `device` source's members looks like to the binder's refresh. */
        private val DEVICE = dev.launcher.engine.expr.Deps(emptyMap(), emptyMap(), emptyMap(), mapOf("device.width" to 0), false)

        fun defaultPainter(v: ViewNode, tokens: Tokens, glyphs: GlyphCache<Symbol>): Painter = when (v.primitive) {
            "text" -> TextPainter(tokens)
            "icon" -> IconPainter(tokens, glyphs)
            "box" -> BoxPainter()
            "image" -> ImagePainter(fade = { dev.launcher.engine.ui.motion.PresetMotion.curve("motion.state") },
                reduced = { !android.animation.ValueAnimator.areAnimatorsEnabled() })
            else -> Nothing
        }

        /** Every component's glyph loads, one after another, on one thread of their own (never a drawing thread). */
        private val GLYPHS = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "kdl-glyphs").apply { isDaemon = true } }
        private val missed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        /** A symbol from the app's icon sets ([IconSets]: assets or the inbox's files), its miss logged once a process. */
        fun loadSymbol(context: Context, set: String, symbol: String): Symbol? {
            val g = dev.launcher.app.design.IconSets.get(context.applicationContext, set)?.glyph(symbol)
            if (g == null) {
                if (missed.size < 64 && missed.add("$set/$symbol")) dev.launcher.app.AppLog.log("[kdl] icon: no '$symbol' in set '$set'")
                return null
            }
            return Symbol(g.box.width(), g.box.height()) { c, cx, cy, size, color, paint -> g.draw(c, cx, cy, size, color, paint) }
        }

        /** Containers draw nothing of their own yet (`box`'s fill comes in E2 slice 10). */
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
