package dev.launcher.app.shade

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import dev.launcher.app.R
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.SpringSpec
import dev.launcher.app.motion.SpringValue
import dev.launcher.app.theme.Appearance
import dev.launcher.app.theme.Fonts
import dev.launcher.app.design.ColorKey
import dev.launcher.app.design.Design
import dev.launcher.app.design.MaterialKey
import dev.launcher.app.design.MaterialPainter
import dev.launcher.app.design.NumberKey
import dev.launcher.app.design.Scale
import dev.launcher.app.design.TextKey
import dev.launcher.app.design.applyTo
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Notification banners (Android's heads-up, ours: the stock ones do not show while the stock shade is blocked). As iOS
 * 27's (measured in the iOS 27 Simulator, docs/IOS27_MOTION.md): a banner grows out of the camera (iOS: the Dynamic
 * Island), widening as it comes down, drops a little past its place and settles (one spring: 0.64 s, damping 0.61; its
 * size peaks at 1.01), stays [SHOW_MS] (longer while touched) and goes back up into the camera (~0.15 s). A swipe up (or
 * sideways) sends it away with the finger's speed; a tap opens it; a pull down opens Notification Center. A newer banner
 * pushes the shown one up and away as it comes in. Platters look exactly like Notification Center's ([NotifPainter]) on
 * the kit's regular glass (`comp.banner.*` tokens, the one material renderer), over the colour most likely behind them
 * (an app's background in this appearance: what is really behind a banner is not known). iOS 27's banner over a white app
 * measured #fafafa inside with a darker rim: that glass over white. Where it rests: 8 pt from the sides, just under the
 * status bar (iOS: 386 x 66-97 pt at y 58.7 on a 402 pt wide iPhone).
 *
 * A ringing notification (an incoming call, an alarm: [Notifs.Item.urgent]) stays until it stops ringing, over an open
 * panel too, with its buttons: a call as iOS's compact incoming call (the caller, a red Decline and a green Answer), anything
 * else with its own actions in a row of capsules. A tap or a pull down opens its full screen. Swiped away, it rests in
 * Notification Center and does not come back while it rings (the shade remembers: [Host.dismissed]).
 *
 * Each banner is drawn once into its own GPU layer ([Card]); its motion is the layer's transform (scale about its top centre,
 * translation, alpha), so a swoop costs a texture per frame, not the glass and text.
 */
@SuppressLint("ViewConstructor")
class BannerView(ctx: Context, private val host: Host) : android.widget.FrameLayout(ctx) {
    interface Host {
        /** The status bar's height (px): banners sit just under the camera line. */
        val barHeight: Int
        /** Home is in front (behind a banner is the wallpaper, which its glass can show), not an app. */
        fun overHome(): Boolean
        fun open(item: Notifs.Item)
        fun openNotificationCenter()
        /** Sends a ringing banner's button or full screen; [closePanel]: an open panel goes (what it opens comes in front). */
        fun send(pi: PendingIntent, closePanel: Boolean): Boolean
        /** The user sent a ringing banner away: it does not come back while it rings. */
        fun dismissed(item: Notifs.Item)
        /** Where the banner takes touches now (null: none): the shade's window adds it to its touchable region. */
        fun touchArea(r: RectF?)
    }

    private val painter = NotifPainter(ctx, 3) { for (s in all) s.card.invalidate() }
    /** The renderer of the banners' glass (they draw on the shade's thread, one at a time). */
    private var mp: MaterialPainter? = null
    private val glyphs = Glyphs(ctx)
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private var u = 1f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.text(600); textAlign = Paint.Align.CENTER }
    private val r = RectF()

    init { clipChildren = false }

    /** A ringing banner's button: a round symbol on its own colour (a call's) or a capsule with the action's title. */
    private class Button(val title: String?, val icon: Int, val color: ColorKey?, val pi: PendingIntent, val closesPanel: Boolean)

    /** A call with its own answer / decline intents: drawn as iOS's compact call. Other calls show their actions. */
    private fun isCall(item: Notifs.Item) = item.call && (item.answer != null || item.decline != null)

    private fun buttonsOf(item: Notifs.Item): List<Button> {
        if (!item.urgent) return emptyList()
        if (isCall(item)) return listOfNotNull(
            item.decline?.let { Button(null, R.drawable.sym_call_end, DECLINE, it, closesPanel = false) },
            item.answer?.let { Button(null, R.drawable.sym_call, ANSWER, it, closesPanel = true) },
        )
        // Replies need a keyboard (not yet ours): those stay in the app.
        return item.actions.filter { it.actionIntent != null && it.remoteInputs.isNullOrEmpty() && !it.title.isNullOrBlank() }
            .take(3).map { Button(it.title.toString(), 0, null, it.actionIntent, closesPanel = false) }
    }

    /** One banner on screen: [k] 0 (folded into the corner) .. 1 (in place); [dx], [dy] where a finger moved it. */
    private inner class Shown(item: Notifs.Item) {
        var item = item
            private set
        var buttons = buttonsOf(item)
            private set

        /** Takes up [v] (the same notification, read again); its layer is drawn again only if it looks different. */
        fun take(v: Notifs.Item) {
            val redraw = !looksSame(item, v)
            item = v
            buttons = buttonsOf(v)
            if (redraw) card.invalidate()
        }
        private val move: (Float) -> Unit = { place(this) }
        val k: SpringValue = SpringValue(0f, 100f, move) { if (k.value <= 0.001f && leaving) drop(this) }
        val dx: SpringValue = SpringValue(0f, 1f, move) { if (leaving && abs(dx.value) >= width * 0.9f) drop(this) }
        val dy: SpringValue = SpringValue(0f, 1f, move) { if (leaving && dy.value < 0f) drop(this) }
        val press = SpringValue(0f, 100f, move)
        /** Its height (px): springs when an update changes what it shows. */
        val height = SpringValue(0f, 1f, { place(this); card.invalidate() })
        val h get() = height.value
        /** Each button's press (0..1). */
        val pressed = List(3) { SpringValue(0f, 100f, { card.invalidate() }) }
        var leaving = false
        val card = Card(this)
    }

    /** A banner's platter in its own layer: drawn when its notification changes, moved by its transform. */
    @SuppressLint("ViewConstructor")
    private inner class Card(private val s: Shown) : View(context) {
        init { setLayerType(LAYER_TYPE_HARDWARE, null); alpha = 0f }
        override fun onDraw(c: Canvas) {
            val m = shadowMargin()
            val w = bannerW()
            c.save()
            c.translate(m, m)
            val p = mp
            if (p != null) {
                // Over home: the wallpaper, blurred to the glass's frost, where the banner rests. Over an app: its usual
                // background colour (what is behind is not known).
                val home = if (host.overHome()) wallpaperFrost() else null
                p.setBackdrop(home, Design.color(BEHIND) or (0xFF shl 24))
                val dim = Appearance.wallpaperDim
                val under = if (home != null && dim > 0.001f) listOf(dev.launcher.app.design.Fill(dev.launcher.app.design.ColorValue.Literal(0xFF000000.toInt(), 0xFF000000.toInt()), dim, dim, dev.launcher.app.design.Blend.NORMAL)) else emptyList()
                p.draw(c, Design.material(MATERIAL), w, s.h, radius(s), margin(), top(), 1f, under = under)
            } else {
                fill.color = Appearance.mix(0xF2F7F7F9.toInt(), 0xEB222224.toInt())
                r.set(0f, 0f, w, s.h)
                c.drawRoundRect(r, radius(s), radius(s), fill)
            }
            val label = Design.color(LABEL)
            val secondary = Design.color(SECONDARY)
            if (isCall(s.item)) {
                painter.drawCall(c, s.item, 0f, 0f, w, s.h, w - callButtonsLeft(s), label, secondary)
            } else {
                painter.draw(c, s.item, 0f, 0f, w, textHeight(s.item), 1f, label, secondary)
            }
            for (i in s.buttons.indices) drawButton(c, s, i)
            c.restore()
        }
    }

    private fun drawButton(c: Canvas, s: Shown, i: Int) {
        val b = s.buttons[i]
        buttonRect(s, i, r)
        val p = s.pressed[i].value
        if (b.title == null) {
            // A call's: a round symbol on its colour, darker and a little smaller while pressed.
            val sc = 1f - 0.06f * p
            val rad = r.width() / 2f * sc
            fill.color = b.color?.let { Design.color(it) } ?: 0xFF888888.toInt()
            c.drawCircle(r.centerX(), r.centerY(), rad, fill)
            if (p > 0f) { fill.color = (Math.round(0x40 * p) shl 24); c.drawCircle(r.centerX(), r.centerY(), rad, fill) }
            glyphs.draw(c, b.icon, r.centerX(), r.centerY(), 26f * u * sc, 0xFFFFFFFF.toInt())
        } else {
            // An action: a faint capsule (the kit's tertiary fill), stronger while pressed.
            fill.color = Design.color(ACTION_FILL)
            c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fill)
            if (p > 0f) {
                fill.color = (Design.color(LABEL) and 0xFFFFFF) or (Math.round(255 * 0.10f * p) shl 24)
                c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fill)
            }
            Design.text(ACTION_TYPE).applyTo(label, u)
            label.color = Design.color(LABEL)
            val text = TextUtils.ellipsize(b.title, label, r.width() - 16f * u, TextUtils.TruncateAt.END).toString()
            c.drawText(text, r.centerX(), r.centerY() + 5.3f * u, label)
        }
    }

    // ------------------------------------------------------------------ geometry (inside the platter)

    private fun textHeight(item: Notifs.Item) = painter.height(item, bannerW())

    /** The platter's height for [s]'s notification and buttons. */
    private fun heightOf(s: Shown): Float = when {
        isCall(s.item) -> pt(CALL_HEIGHT)
        s.buttons.isNotEmpty() -> textHeight(s.item) - 4f * u + pt(ACTION_HEIGHT) + 12f * u
        else -> textHeight(s.item)
    }

    private fun pt(k: NumberKey) = Design.pt(k, u)

    private fun radius(s: Shown) = pt(if (isCall(s.item)) CALL_CORNER else CORNER)

    private fun callButtonsLeft(s: Shown): Float {
        val n = s.buttons.size
        return bannerW() - 13f * u - n * pt(CALL_BUTTON) - (n - 1) * 12f * u
    }

    /** Button [i] of [s], in the platter's coordinates. */
    private fun buttonRect(s: Shown, i: Int, out: RectF): RectF {
        if (isCall(s.item)) {
            val d = pt(CALL_BUTTON)
            val x = callButtonsLeft(s) + i * (d + 12f * u)
            val y = (s.h - d) / 2f
            return out.apply { set(x, y, x + d, y + d) }
        }
        val n = s.buttons.size
        val gap = 8f * u
        val bw = (bannerW() - 24f * u - (n - 1) * gap) / n
        val x = 12f * u + i * (bw + gap)
        val y = s.h - 12f * u - pt(ACTION_HEIGHT)
        return out.apply { set(x, y, x + bw, y + pt(ACTION_HEIGHT)) }
    }

    /** The button of the shown banner under [x],[y] (this view's coordinates), or -1. */
    private fun buttonAt(s: Shown, x: Float, y: Float): Int {
        val px = x - margin() - s.dx.value
        val py = y - top() - s.dy.value
        for (i in s.buttons.indices) {
            buttonRect(s, i, r)
            r.inset(-6f * u, -6f * u)
            if (r.contains(px, py)) return i
        }
        return -1
    }

    /** Room around a banner in its layer for its glass's drop shadow (the kit's regular glass: 8 down, blur 48). */
    private fun shadowMargin() = mp?.reach(Design.material(MATERIAL)) ?: (14f * u)

    /** Where banners come from and go back to (px): the camera's centre (iOS: the Dynamic Island), else the top centre. */
    private var camX = 0f
    private var camY = 0f

    private fun findCamera() {
        val cut = try { display?.cutout?.boundingRectTop } catch (_: Throwable) { null }
        if (cut != null && !cut.isEmpty) { camX = cut.exactCenterX(); camY = cut.exactCenterY() }
        else { camX = width / 2f; camY = host.barHeight / 2f }
    }

    /** Lays [s]'s card out at its size and applies its springs as the card's transform. */
    private fun place(s: Shown) {
        val m = shadowMargin()
        val k = s.k.value.coerceIn(0f, 1.4f)
        val card = s.card
        val lp = card.layoutParams as? LayoutParams
        val w = (bannerW() + 2 * m).toInt()
        val h = kotlin.math.ceil(s.h + 2 * m).toInt()
        if (lp == null || lp.width != w || lp.height != h) {
            card.layoutParams = LayoutParams(w, h)
            card.invalidate()
        }
        val bw = bannerW()
        card.pivotX = m + bw / 2f
        card.pivotY = m
        // Out of the camera: grows about its top centre while it comes down, past its place on the spring's overshoot (its
        // size only just past 1, as iOS's), and back up into the camera when it goes.
        val kc = min(k, 1f)
        val scale = (0.3f + 0.7f * kc + 0.06f * max(0f, k - 1f)) * (1f - 0.025f * s.press.value)
        card.scaleX = scale
        card.scaleY = scale
        val startTop = camY - 8f * u
        val cx = camX + (width / 2f - camX) * kc
        card.translationX = cx - bw / 2f + s.dx.value - m
        card.translationY = startTop + (top() - startTop) * k + s.dy.value - m
        card.alpha = (k * 2.5f).coerceIn(0f, 1f) * (1f - (-s.dy.value / (top() + s.h)).coerceIn(0f, 1f) * 0.6f)
    }

    private fun drop(s: Shown) {
        if (!all.remove(s)) return
        removeView(s.card)
        areaChanged()
    }

    private val all = ArrayList<Shown>()
    private val current get() = all.lastOrNull { !it.leaving }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val nu = Scale.unitPx(context, min(w, h))
        if (mp == null || nu != u) mp = MaterialPainter.create(nu)
        u = nu
        painter.u = u
        post { wallpaperFrost() }   // made before the first banner over home needs it
        findCamera()
        pending?.let { p -> pending = null; post { show(p) } }
    }

    /** Shown before the first layout (the window just came up): shown once it has a size. */
    private var pending: Notifs.Item? = null

    private fun margin() = pt(MARGIN)

    private val frostMatrix = android.graphics.Matrix()

    /** The wallpaper blurred to the banner glass's frost ([dev.launcher.app.design.FrostCache]; null until it is made). */
    private fun wallpaperFrost(): dev.launcher.app.design.BackdropImage? {
        val wp = dev.launcher.app.Wallpaper.current ?: return null
        val h = handler ?: return null
        if (width == 0) return null
        frostMatrix.set(wp.matrix(width, height))
        val sigma = dev.launcher.app.design.Blur.sigmaPx(Design.material(MATERIAL).let { it.frostPt + (it.frostDarkPt - it.frostPt) * Appearance.dark }, u) /
            frostMatrix.mapRadius(1f).coerceAtLeast(0.001f)
        return dev.launcher.app.design.FrostCache.get(wp.bitmap, frostMatrix, sigma, h) { for (s in all) s.card.invalidate() }
    }

    private val wallpaperChanged: () -> Unit = { wallpaperFrost() }

    /** A light/dark change or a token edit: every banner's layer is drawn again (and laid out, if its size changed). */
    fun restyle() {
        for (s in all) {
            val h = heightOf(s)
            if (abs(s.height.target - h) > 0.5f) s.height.animateTo(h, RESIZE)
            place(s)
            s.card.invalidate()
        }
    }

    private val tokensChanged: () -> Unit = { handler?.post { restyle() } }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Design.addListener(tokensChanged)
        handler?.let { dev.launcher.app.Wallpaper.addListener(it, wallpaperChanged) }
    }
    override fun onDetachedFromWindow() {
        Design.removeListener(tokensChanged)
        dev.launcher.app.Wallpaper.removeListener(wallpaperChanged)
        super.onDetachedFromWindow()
    }
    private fun bannerW() = width - 2 * margin()
    private fun top() = host.barHeight * 0.5f + 16f * u

    /** Shows [item] (a newer banner sends the shown one up and away; a ringing one stays for anything that does not ring). */
    fun show(item: Notifs.Item) {
        if (width == 0) { pending = item; return }
        val cur = current
        if (cur != null && cur.item.key == item.key) {
            // The same notification again (an update that alerts): the banner stays, its text changes, the timer restarts.
            update(cur, item)
            restartTimer()
            return
        }
        // A ringing banner stays: what does not ring waits in Notification Center.
        if (cur != null && cur.item.urgent && !item.urgent) return
        cur?.let { leave(it, up = true, velocity = -1200f) }
        val s = Shown(item)
        s.height.snapTo(heightOf(s))
        all += s
        addView(s.card)
        place(s)
        s.k.animateTo(1f, SWOOP_IN)
        restartTimer()
        areaChanged()
    }

    /** What a banner shows is the same in [a] and [b] (every change anywhere reads all notifications again). */
    private fun looksSame(a: Notifs.Item, b: Notifs.Item) =
        a.postTime == b.postTime && a.urgent == b.urgent && a.call == b.call && a.title?.toString() == b.title?.toString() &&
            a.text?.toString() == b.text?.toString() && a.sub?.toString() == b.sub?.toString() &&
            a.actions.map { it.title?.toString() } == b.actions.map { it.title?.toString() } &&
            (a.answer == null) == (b.answer == null) && (a.decline == null) == (b.decline == null)

    private fun update(s: Shown, item: Notifs.Item) {
        if (s.item === item) return
        s.take(item)
        val h = heightOf(s)
        if (h != s.height.target) s.height.animateTo(h, RESIZE)
        areaChanged()
    }

    /**
     * The notifications changed (no alert): a banner whose notification is gone (or stopped ringing: a call answered
     * elsewhere) goes up and away; the others take up what changed in theirs.
     */
    fun sync(items: List<Notifs.Item>) {
        for (s in all.toList()) {
            if (s.leaving) continue
            val now = items.firstOrNull { it.key == s.item.key }
            when {
                now == null || (s.item.urgent && !now.urgent) -> {
                    if (mode != Mode.NONE && s === current) mode = Mode.NONE
                    leave(s, up = true, velocity = -900f)
                }
                else -> update(s, now)
            }
        }
    }

    /** Banners go at once (a panel opened over them, the screen went off); [keepRinging]: a ringing one stays. */
    fun clear(keepRinging: Boolean = false) {
        if (!keepRinging || pending?.urgent != true) pending = null
        for (s in all.toList()) {
            if (keepRinging && !s.leaving && s.item.urgent) continue
            removeView(s.card)
            all.remove(s)
        }
        if (current == null) { removeCallbacks(timeout); mode = Mode.NONE }
        areaChanged()
    }

    val showing get() = current != null

    /** The ringing banner goes up and away (its own screen came to the front); it may come back. */
    fun hideRinging() {
        val s = current?.takeIf { it.item.urgent } ?: return
        if (mode != Mode.NONE) { mode = Mode.NONE; button = -1 }
        leave(s, up = true, velocity = -900f)
    }

    /** The key of the ringing banner on screen, if one is. */
    val ringing: String? get() = (current?.item ?: pending)?.takeIf { it.urgent }?.key

    private val timeout = Runnable {
        val s = current ?: return@Runnable
        if (s.item.urgent) return@Runnable
        if (mode == Mode.NONE) leave(s, up = false) else restartTimer()
    }

    private fun restartTimer() {
        removeCallbacks(timeout)
        if (current?.item?.urgent != true) postDelayed(timeout, SHOW_MS)
    }

    /** [s] leaves: back into the corner (timed out), or up and away with [velocity] px/s (swiped, replaced). */
    private fun leave(s: Shown, up: Boolean, velocity: Float = 0f) {
        if (s.leaving) return
        s.leaving = true
        if (up) s.dy.animateTo(-(top() + s.h + 60f * u), FLY, min(velocity, -600f))
        else s.k.animateTo(0f, SWOOP_OUT)
        areaChanged()
    }

    private val area = RectF()

    private fun areaChanged() {
        val s = current
        if (s == null) { host.touchArea(null); return }
        area.set(margin(), top(), width - margin(), top() + s.height.target)
        host.touchArea(area)
    }

    /** True if a touch at [x],[y] belongs to the banner. */
    fun hits(x: Float, y: Float): Boolean {
        val s = current ?: return false
        return x >= margin() && x <= width - margin() && y >= top() - 10f * u && y <= top() + s.h + 10f * u + max(0f, s.dy.value)
    }

    // ------------------------------------------------------------------ touch

    private enum class Mode { NONE, PRESS, BUTTON, DRAG }
    private var mode = Mode.NONE
    private var button = -1
    private var downX = 0f
    private var downY = 0f
    private var vt: VelocityTracker? = null

    /** A ringing banner opens its full screen (a tap, a pull down): what it would have shown on a locked phone. */
    private fun openRinging(s: Shown) {
        val pi = s.item.fullScreen ?: s.item.contentIntent
        leave(s, up = true, velocity = -900f)
        if (pi != null) host.send(pi, closePanel = true) else host.open(s.item)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val s = current ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                vt?.recycle(); vt = VelocityTracker.obtain().also { it.addMovement(e) }
                downX = e.x; downY = e.y
                button = buttonAt(s, e.x, e.y)
                if (button >= 0) {
                    mode = Mode.BUTTON
                    s.pressed[button].animateTo(1f, PRESS_IN)
                } else {
                    mode = Mode.PRESS
                    s.press.animateTo(1f, PRESS_IN)
                }
                s.dx.stop(); s.dy.stop()
                removeCallbacks(timeout)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val dx = e.x - downX
                val dy = e.y - downY
                if ((mode == Mode.PRESS || mode == Mode.BUTTON) && (abs(dx) > slop || abs(dy) > slop)) {
                    // A button lets go as the finger moves on: the banner follows it instead.
                    if (mode == Mode.BUTTON) s.pressed[button].animateTo(0f, PRESS_OUT) else s.press.animateTo(0f, PRESS_OUT)
                    mode = Mode.DRAG
                }
                if (mode == Mode.DRAG) {
                    // Up and sideways it follows the finger; down it resists (a pull down opens Notification Center).
                    s.dy.snapTo(if (dy < 0f) dy else Motion.rubberBand(dy, height * 0.4f))
                    s.dx.snapTo(if (abs(dx) > abs(dy)) dx else dx * 0.3f)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.addMovement(e)
                vt?.computeCurrentVelocity(1000)
                val vx = vt?.xVelocity ?: 0f
                val vy = vt?.yVelocity ?: 0f
                vt?.recycle(); vt = null
                s.press.animateTo(0f, PRESS_OUT)
                val up = e.actionMasked == MotionEvent.ACTION_UP
                when {
                    mode == Mode.BUTTON -> {
                        s.pressed[button].animateTo(0f, PRESS_OUT)
                        val b = s.buttons.getOrNull(button)
                        if (up && b != null) {
                            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            leave(s, up = true, velocity = -900f)
                            host.send(b.pi, b.closesPanel)
                        } else restartTimer()
                    }
                    mode == Mode.PRESS && up -> if (s.item.urgent) openRinging(s) else { leave(s, up = true, velocity = -900f); host.open(s.item) }
                    mode == Mode.DRAG && (s.dy.value < -s.h * 0.3f || vy < -700f) -> { leave(s, up = true, velocity = vy); if (s.item.urgent) host.dismissed(s.item) }
                    mode == Mode.DRAG && (abs(s.dx.value) > width * 0.3f || abs(vx) > 1200f) -> {
                        s.leaving = true
                        s.dx.animateTo(if ((s.dx.value + vx * 0.1f) > 0f) width.toFloat() else -width.toFloat(), FLY, vx)
                        s.k.animateTo(0f, SpringSpec(0.5f, 1f))
                        areaChanged()
                        if (s.item.urgent) host.dismissed(s.item)
                    }
                    mode == Mode.DRAG && s.dy.value > 40f * u && up -> {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        if (s.item.urgent) openRinging(s)
                        else { clear(); host.openNotificationCenter() }
                    }
                    else -> { s.dx.animateTo(0f, BACK, vx); s.dy.animateTo(0f, BACK, vy); restartTimer() }
                }
                mode = Mode.NONE
                button = -1
            }
        }
        return true
    }

    companion object {
        /** iOS 27 keeps a banner ~7 s (measured: 7.4-7.7 s from its first frame to its last). */
        const val SHOW_MS = 7000L
        // The banner's tokens (`comp.banner.*`; values and sources in assets/themes/ios27.json).
        val MATERIAL = MaterialKey("comp.banner.material")
        val BEHIND = ColorKey("comp.banner.behind")
        val CORNER = NumberKey("comp.banner.corner")
        val MARGIN = NumberKey("comp.banner.margin")
        val LABEL = ColorKey("comp.banner.label")
        val SECONDARY = ColorKey("comp.banner.secondary")
        val CALL_CORNER = NumberKey("comp.banner.call.corner")
        val CALL_HEIGHT = NumberKey("comp.banner.call.height")
        val CALL_BUTTON = NumberKey("comp.banner.call.button")
        val DECLINE = ColorKey("comp.banner.call.decline")
        val ANSWER = ColorKey("comp.banner.call.answer")
        val ACTION_HEIGHT = NumberKey("comp.banner.action.height")
        val ACTION_FILL = ColorKey("comp.banner.action.fill")
        val ACTION_TYPE = TextKey("comp.banner.action.type")
        val ALL = listOf(MATERIAL, BEHIND, CORNER, MARGIN, LABEL, SECONDARY, CALL_CORNER, CALL_HEIGHT, CALL_BUTTON, DECLINE, ANSWER,
            ACTION_HEIGHT, ACTION_FILL, ACTION_TYPE).map { it.name }
        val SWOOP_IN = SpringSpec(0.64f, 0.61f)
        val SWOOP_OUT = SpringSpec(0.3f, 1f)
        val FLY = SpringSpec(0.35f, 1f)
        val BACK = SpringSpec(0.38f, 0.82f)
        val RESIZE = SpringSpec(0.4f, 1f)
        val PRESS_IN = SpringSpec(0.2f, 1f)
        val PRESS_OUT = SpringSpec(0.35f, 1f)
    }
}
