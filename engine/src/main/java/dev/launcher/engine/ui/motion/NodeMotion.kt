package dev.launcher.engine.ui.motion

import dev.launcher.app.design.Curve
import dev.launcher.app.motion.Mover
import dev.launcher.engine.model.CurveSpec
import dev.launcher.engine.model.MotionChannel
import dev.launcher.engine.model.NodeMotionSpec
import kotlin.math.max
import kotlin.math.min

/** What a component's node motion needs: roles, whether motion is reduced, and the time (ns, the frame clock's base). */
class MotionContext(
    val resolver: MotionResolver = MotionResolver { null },
    val reduced: () -> Boolean = { false },
    val now: () -> Long = System::nanoTime,
)

/**
 * One animated value of a node (T-009 13a-1): what it shows, where it goes, and the mover taking it there. A settled track
 * starts fresh (the authored curve from rest); a moving one is continued from the value and speed it has at that moment
 * ([Continuation]), so a retarget never jumps. [low]..[high] is the envelope of everything it can still show: set at a
 * start, only grown at an interruption, its target alone at rest.
 */
class Track {
    var value = Float.NaN; private set
    var target = 0f; private set
    var low = 0f; private set
    var high = 0f; private set
    private var mover: Mover? = null
    private var started = 0L
    val moving get() = mover != null

    /** Goes to [to] on [curve], or jumps there when [curve] is null. The first value it is given, it shows at once. */
    fun to(to: Float, curve: Curve?, now: Long) {
        if (value.isNaN() || curve == null) return snap(to)
        if (to != target) go(to, curve, now, 0f)
    }

    /** Shown [delta] further off than before, then on to its target: a layout move kept continuous. */
    fun shift(delta: Float, curve: Curve?, now: Long) {
        if (delta != 0f && curve != null && !value.isNaN()) go(target, curve, now, delta)
    }

    private fun go(to: Float, curve: Curve, now: Long, delta: Float) {
        val m = mover
        val t = elapsed(now)
        val v = m?.velocity(t) ?: 0f
        val from = (m?.value(t) ?: value) + delta
        val next = if (m == null) Continuation.fresh(curve, from, to) else Continuation.continued(curve, from, v, to)
        val r = Envelope.range(curve, next, from, v, to)
        low = if (m == null) r.start else min(low, r.start)
        high = if (m == null) r.endInclusive else max(high, r.endInclusive)
        mover = next; started = now; target = to; value = from
    }

    /** The value at [now]; false once it has settled on its target ([epsilon]: what a pixel can show, in its units). */
    fun step(now: Long, epsilon: Float): Boolean {
        val m = mover ?: return false
        val t = elapsed(now)
        if (m.settled(t, epsilon)) { snap(target); return false }
        value = m.value(t)
        return true
    }

    fun snap(to: Float = target) { value = to; target = to; low = to; high = to; mover = null }

    private fun elapsed(now: Long) = ((now - started) / 1e9).coerceAtLeast(0.0)
}

/**
 * The motion a node's `transition` declares: its place (declared translate plus, while it moves, where layout had it
 * before), its drawn size, opacity and scale, each a [Track]. Layout runs once at the target; only drawing follows these.
 */
class NodeMotion(private val spec: NodeMotionSpec, private val context: MotionContext) {
    val x = Track(); val y = Track()
    val width = Track(); val height = Track()
    val alpha = Track(); val scale = Track()
    private val tracks = listOf(x, y, width, height, alpha, scale)
    private var layoutX = Float.NaN
    private var layoutY = Float.NaN

    val moving get() = tracks.any { it.moving }

    /** The curve [channel] moves on now; null: it jumps (not named, reduced motion, or a role the preset lacks). */
    fun curve(channel: MotionChannel): Curve? = if (context.reduced()) null else when (val s = spec.curveFor(channel)) {
        null -> null
        is CurveSpec.Role -> context.resolver.curve(s.role)
        is CurveSpec.Literal -> s.curve
    }

    /**
     * Where layout placed it in its parent and what its bindings ask now, at [now]; true while anything moves. A new layout
     * place starts the place tracks from where it was shown: old place minus new, carried on to the declared translate.
     */
    fun update(placeX: Float, placeY: Float, translateX: Float, translateY: Float, w: Float, h: Float, opacity: Float,
        scale: Float, now: Long = context.now()): Boolean {
        val place = curve(MotionChannel.PLACE)
        if (!layoutX.isNaN()) { x.shift(layoutX - placeX, place, now); y.shift(layoutY - placeY, place, now) }
        layoutX = placeX; layoutY = placeY
        x.to(translateX, place, now); y.to(translateY, place, now)
        val size = curve(MotionChannel.SIZE)
        width.to(w, size, now); height.to(h, size, now)
        alpha.to(opacity, curve(MotionChannel.OPACITY), now)
        this.scale.to(scale, curve(MotionChannel.SCALE), now)
        return moving
    }

    /** Every track at [now]; false once all have settled. Scale settles within half a pixel of the node's size. */
    fun step(now: Long): Boolean {
        var busy = false
        for (t in listOf(x, y, width, height)) busy = t.step(now, PIXEL) or busy
        busy = alpha.step(now, 1f / 255) or busy
        busy = scale.step(now, PIXEL / max(1f, max(width.target, height.target))) or busy
        return busy
    }

    /** Every track on its target at once (reduced motion turned on mid-motion). */
    fun snap() = tracks.forEach { it.snap() }

    companion object { const val PIXEL = 0.5f }
}
