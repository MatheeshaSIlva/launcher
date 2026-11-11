package dev.launcher.engine.ui.motion

import dev.launcher.app.design.Curve
import dev.launcher.app.motion.Mover
import dev.launcher.engine.model.CurveSpec
import dev.launcher.engine.model.MotionChannel
import dev.launcher.engine.model.NodeMotionSpec
import dev.launcher.engine.model.Presence
import dev.launcher.engine.model.PresenceEffect
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
    /** The curve of the presence motion it is on (`enter`, `exit`, a revival): new targets follow it until it settles. */
    private var carry: Curve? = null
    val moving get() = mover != null

    /** Goes to [to] on [curve], or jumps there when [curve] is null. The first value it is given, it shows at once. */
    fun to(to: Float, curve: Curve?, now: Long) {
        // On a presence motion, a channel with no curve of its own retargets on that motion's curve (Codex: a revived node
        // given a new opacity snapped).
        val c = curve ?: carry.takeIf { moving }
        if (value.isNaN() || c == null) return snap(to)
        if (to != target) go(to, c, now, 0f)
    }

    /** Goes to [to] on [curve] as a presence motion: later targets follow [curve] until it settles. */
    fun present(to: Float, curve: Curve, now: Long) {
        if (value.isNaN()) return snap(to)
        carry = curve
        if (to != target || !moving) go(to, curve, now, 0f)
    }

    /** Shown [delta] further off than before, then on to its target: a layout move kept continuous. */
    fun shift(delta: Float, curve: Curve?, now: Long) {
        if (delta != 0f && curve != null && !value.isNaN()) go(target, curve, now, delta)
    }

    /** Shown at [from] now, then on to its target on [curve] (an arrival's start: `enter`). */
    fun launch(from: Float, curve: Curve, now: Long) {
        if (value.isNaN()) return
        mover = null; value = from; carry = curve
        go(target, curve, now, 0f)
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

    fun snap(to: Float = target) { value = to; target = to; low = to; high = to; mover = null; carry = null }

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
    private var layoutW = 0f
    private var layoutH = 0f
    /** What its bindings asked at its last placement: where a revival turns back to. */
    private val targets = FloatArray(6)
    /** On its way out (`exit`): no longer laid out, shown at its last place until it settles. */
    var leaving = false; private set
    /** The tracks its exit drives while it leaves. */
    private var exiting: Set<Track> = emptySet()

    /** Its text's roll (`roll`, 13a-3b), stepped with its tracks; drawn by the text painter. */
    val roll: RollMotion? = if (spec.roll != null) RollMotion() else null
    /** Half a pixel in the roll's units (rises), set by the text painter from its size. */
    var rollEpsilon = 0.01f

    val moving get() = tracks.any { it.moving } || roll?.moving == true
    /** Placed at least once (its first placement shows it where layout put it). */
    val placedOnce get() = !layoutX.isNaN()

    /** The curve [channel] moves on now; null: it jumps (not named, reduced motion, or a role the preset lacks). */
    fun curve(channel: MotionChannel): Curve? = if (context.reduced()) null else when (val s = spec.curveFor(channel)) {
        null -> null
        is CurveSpec.Role -> context.resolver.curve(s.role)
        is CurveSpec.Literal -> s.curve
    }

    /**
     * Where layout placed it in its parent and what its bindings ask now, at [now]; true while anything moves. A new layout
     * place starts the place tracks from where it was shown, measured at its anchor ([anchorX], [anchorY]: where a moving
     * size is held in its box, which already keeps the surface there): a centred box growing in a centred slot does not
     * move, its left edge going out by its size alone (Codex, 13a-1 review).
     */
    fun update(placeX: Float, placeY: Float, translateX: Float, translateY: Float, w: Float, h: Float, opacity: Float,
        scale: Float, now: Long = context.now(), anchorX: Float = 0f, anchorY: Float = 0f): Boolean {
        val place = curve(MotionChannel.PLACE)
        // The anchor holds the surface only while its size moves; a size that jumps leaves the old edge to hold (Codex).
        val held = curve(MotionChannel.SIZE) != null
        val ax = if (held) anchorX else 0f
        val ay = if (held) anchorY else 0f
        if (!layoutX.isNaN()) {
            x.shift(layoutX + layoutW * ax - (placeX + w * ax), place, now)
            y.shift(layoutY + layoutH * ay - (placeY + h * ay), place, now)
        }
        layoutX = placeX; layoutY = placeY; layoutW = w; layoutH = h
        floatArrayOf(translateX, translateY, w, h, opacity, scale).copyInto(targets)
        // While it leaves, the tracks its exit drives keep the exit's values; the others follow as before (Codex: the root,
        // placed while it leaves, had its fade cancelled).
        fun Track.follow(to: Float, c: Curve?) { if (this !in exiting) to(to, c, now) }
        x.follow(translateX, place); y.follow(translateY, place)
        val size = curve(MotionChannel.SIZE)
        width.follow(w, size); height.follow(h, size)
        alpha.follow(opacity, curve(MotionChannel.OPACITY))
        this.scale.follow(scale, curve(MotionChannel.SCALE))
        return moving
    }

    /** Every track at [now]; false once all have settled. Scale settles within half a pixel of the node's size. */
    fun step(now: Long): Boolean {
        var busy = false
        for (t in listOf(x, y, width, height)) busy = t.step(now, PIXEL) or busy
        busy = alpha.step(now, 1f / 255) or busy
        busy = scale.step(now, PIXEL / max(1f, max(width.target, height.target))) or busy
        roll?.let { busy = it.step(now, rollEpsilon) or busy }
        return busy
    }

    /** The roll's curve now; null: the text changes at once. */
    fun rollCurve(): Curve? = resolve(spec.rollCurve())

    /** How far apart changed characters start: the preset's `motion.text.stagger` (its duration); none without it. */
    fun stagger(): Long = if (context.reduced()) 0L else
        ((context.resolver.curve("motion.text.stagger") as? Curve.Ease)?.ms ?: 0f).let { (it * 1_000_000L).toLong() }

    /** The clock now (what a roll started from a measure counts from). */
    fun now() = context.now()

    /** Its presence curves (`enter`, `exit`), resolved now; null: it arrives or leaves at once. */
    fun enterCurve(): Curve? = resolve(spec.enterCurve())
    fun exitCurve(): Curve? = resolve(spec.exitCurve())
    private fun resolve(s: CurveSpec?): Curve? = if (context.reduced()) null else when (s) {
        null -> null
        is CurveSpec.Role -> context.resolver.curve(s.role)
        is CurveSpec.Literal -> s.curve
    }

    /**
     * Arrives (after its first placement): each effect starts from its `enter` value. [rise] is how far below its place a
     * rising node starts, [alongX] whether its parent lays it out along x (a row: `grow` is its width), else its height.
     */
    fun enter(rise: Float, alongX: Boolean, now: Long = context.now()): Boolean {
        val p = spec.enter ?: return false
        val c = enterCurve() ?: return false
        for (e in p.effects) presence(e, rise, alongX).let { (track, out) -> track.launch(out, c, now) }
        return moving
    }

    /** Leaves: each effect goes to its `exit` value from where it is, at the speed it has; true if it animates. */
    fun exit(rise: Float, alongX: Boolean, now: Long = context.now()): Boolean {
        val p = spec.exit ?: return false
        val c = exitCurve() ?: return false
        leaving = true
        exiting = p.effects.map { e -> presence(e, rise, alongX).also { (track, out) -> track.present(out, c, now) }.first }.toSet()
        return true
    }

    /** Comes back before its exit ended: every track turns back to what its bindings asked, from where it is. */
    fun revive(now: Long = context.now()) {
        if (!leaving) return
        leaving = false
        val c = enterCurve() ?: exitCurve()
        for ((i, t) in tracks.withIndex()) if (t in exiting) { if (c == null) t.snap(targets[i]) else t.present(targets[i], c, now) }
        exiting = emptySet()
    }

    /** Its exit ended (a root that stays, hidden): at rest on what its bindings ask, ready to arrive again. */
    fun ended() {
        leaving = false; exiting = emptySet()
        for ((i, t) in tracks.withIndex()) t.snap(targets[i])
    }

    private fun presence(e: PresenceEffect, rise: Float, alongX: Boolean): Pair<Track, Float> = when (e) {
        PresenceEffect.FADE -> alpha to 0f
        PresenceEffect.SCALE -> scale to SCALE_FROM
        PresenceEffect.RISE -> y to targets[1] + rise
        PresenceEffect.GROW -> if (alongX) width to 0f else height to 0f
    }

    /** Every track on its target at once (reduced motion turned on mid-motion). */
    fun snap() { tracks.forEach { it.snap() }; roll?.snap() }

    companion object {
        const val PIXEL = 0.5f
        /** Where `scale` arrives from and leaves to (judged: a small step, as the design language's sheets arrive). */
        const val SCALE_FROM = 0.85f
    }
}
