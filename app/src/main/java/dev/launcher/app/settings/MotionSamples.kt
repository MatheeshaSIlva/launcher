package dev.launcher.app.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import dev.launcher.app.motion.AppStyle
import dev.launcher.app.motion.Moment
import dev.launcher.app.motion.Moments
import kotlin.math.PI
import kotlin.math.sin

/**
 * What the stage's phone plays for Motion's group pages (docs/PLAN_MOTION.md, C4b; V5: the phone drawn, [PhoneScene]): which animation, how far its run is
 * ([k]: 0 to 1, past 1 as it overshoots; below 0 nothing), whether it is that animation's own run or the way back on its
 * partner ([forward]), seconds into its own run ([t], for the graph's dot) and a number it shows (a number's value as the
 * draft has it, NaN otherwise). Written by Motion's sample loop, read in drawing only.
 */
@Stable
internal class SampleState {
    var role by mutableStateOf<String?>(null)
    val k = mutableFloatStateOf(-1f)
    val forward = mutableStateOf(true)
    val t = mutableFloatStateOf(-1f)
    val number = mutableFloatStateOf(Float.NaN)
    /** Ticks every frame while a sample that moves at rest (a wiggle) pauses: the picture draws again with it. */
    val tick = androidx.compose.runtime.mutableIntStateOf(0)
    /** How present it is: one going fades where it is and the next fades in at its start (none is ever cut mid-run). */
    val alpha = androidx.compose.animation.core.Animatable(0f)
}

/** What a sample draws with, set by the picture before each draw: its size, home's picture, the dock's icon, the colours. */
internal class SampleKit {
    var w = 0f
    var h = 0f
    var corner = 0f
    var home: Bitmap? = null
    var icon: RectF? = null
    var fill = 0
    var page = 0
    var ink = 0
    var accent = 0
    lateinit var paint: Paint
    lateinit var image: Paint
    lateinit var path: Path
    val r = RectF()
    val src = android.graphics.Rect()
    val dst = RectF()
    /** How apps open and close (motion/AppStyles): the draft's, set before each draw. */
    var opening = AppStyle.ZOOM
    var closing = AppStyle.ZOOM
    /** The app's opening and closing drawn in a style on this picture ([AppStyleArt]). */
    internal val stage = SampleStage(this)
    /** The phone drawn as a motion graphic (a part's stage, V5): home, its icons and an app's page come from it. */
    var scene: PhoneScene? = null

    fun set(w: Float, h: Float, corner: Float, home: Bitmap?, icon: RectF?, fill: Int, page: Int, ink: Int, accent: Int, paint: Paint, image: Paint, path: Path) {
        this.w = w; this.h = h; this.corner = corner; this.home = home; this.icon = icon
        this.fill = fill; this.page = page; this.ink = ink; this.accent = accent
        this.paint = paint; this.image = image; this.path = path
    }
}

internal object MotionSamples {
    /** How a sample moves: the kind of motion, drawn where the element is. */
    enum class Kind {
        APP, APP_CANCEL, DEPTH, SWITCH, SWITCH_BACK, CARD_FADE, GROW, SLIDE_DOWN, SLIDE_UP, SLIDE_SIDE, FADE, PRESS, MOVE, FLY,
        LEVEL, ROLL, WIGGLE, ZOOM, REVEAL, RESIZE, LIFT, BANNER, CHARGE,
    }

    /**
     * One animation's sample: its kind, where on the phone (fractions of it), its run (from 0 to 1, or 1 to 0 for a way
     * back), the animation whose curve brings it back ([partner]; null: it starts over at once), and for a number the curve
     * that moves it ([drive]).
     */
    class Spec(val kind: Kind, val area: Rect, val from: Float = 0f, val to: Float = 1f, val partner: String? = null, val drive: String? = null)

    private val WHOLE = Rect(0f, 0f, 1f, 1f)
    private val CARD = Rect(0.18f, 0.32f, 0.82f, 0.6f)
    private val TOP_BAND = Rect(0.02f, 0.067f, 0.98f, 0.147f)
    private val STATUS = Rect(0.06f, 0.012f, 0.94f, 0.04f)
    private val PILL = Rect(0.33f, 0.975f, 0.67f, 0.99f)
    private val ICONS = Rect(0.06f, 0.2f, 0.94f, 0.46f)
    private val CLOCK = Rect(0.08f, 0.08f, 0.6f, 0.16f)
    private val CC = Rect(0.06f, 0.1f, 0.94f, 0.5f)
    private val CONTROL = Rect(0.08f, 0.14f, 0.3f, 0.24f)
    private val PLATTERS = Rect(0.04f, 0.4f, 0.96f, 0.68f)
    private val SHEET = Rect(0f, 0.38f, 1f, 1f)
    private val MENU = Rect(0.3f, 0.52f, 0.92f, 0.8f)
    private val LIST = Rect(0.06f, 0.16f, 0.94f, 0.86f)
    private val SMALL = Rect(0.42f, 0.44f, 0.58f, 0.5f)
    private val DECK = Rect(0.16f, 0.2f, 0.84f, 0.72f)

    private fun m(name: String) = "motion.$name"

    private val SPECS: Map<String, Spec> = buildMap {
        fun add(name: String, s: Spec) { this[m(name)] = s }
        fun pair(open: String, close: String, kind: Kind, area: Rect) {
            add(open, Spec(kind, area, 0f, 1f, m(close)))
            add(close, Spec(kind, area, 1f, 0f, m(open)))
        }
        // Apps opening and closing: on home's picture, the app's page out of the dock's middle icon.
        add("app.open", Spec(Kind.APP, WHOLE, 0f, 1f, m("app.close-size")))
        add("app.close-size", Spec(Kind.APP, WHOLE, 1f, 0f, m("app.open")))
        add("app.close-position", Spec(Kind.APP, WHOLE, 1f, 0f, m("app.open")))
        add("app.cancel", Spec(Kind.APP_CANCEL, WHOLE))
        pair("home.depth-open", "home.depth-close", Kind.DEPTH, WHOLE)
        for (n in listOf("home.content-zoom", "home.wallpaper-zoom", "home.depth-blur")) add(n, Spec(Kind.DEPTH, WHOLE, 0f, 1f, m("home.depth-close"), m("home.depth-open")))
        add("switch.commit", Spec(Kind.SWITCH, WHOLE))
        add("switch.cancel", Spec(Kind.SWITCH_BACK, WHOLE))
        for (n in listOf("card.color", "card.snapshot", "card.fade-app")) add(n, Spec(Kind.CARD_FADE, WHOLE))
        add("card.fade-home", Spec(Kind.CARD_FADE, WHOLE, 1f, 0f))
        add("card.catch-up", Spec(Kind.MOVE, WHOLE))
        // App Switcher: a deck.
        add("switcher.enter", Spec(Kind.ZOOM, DECK, 1f, 0f, m("switcher.open")))
        add("switcher.open", Spec(Kind.ZOOM, DECK, 0f, 1f, m("switcher.enter")))
        add("switcher.home", Spec(Kind.FADE, DECK, 1f, 0f, m("switcher.enter")))
        add("switcher.scroll", Spec(Kind.MOVE, DECK))
        add("switcher.flick", Spec(Kind.FLY, DECK))
        add("switcher.reflow", Spec(Kind.MOVE, DECK))
        add("switcher.clear-press", Spec(Kind.PRESS, SMALL, 0f, 1f, null))
        for (n in listOf("switcher.clear-stagger", "switcher.clear-home")) add(n, Spec(Kind.FLY, DECK, drive = m("switcher.flick")))
        // Home.
        add("home.page-snap", Spec(Kind.MOVE, WHOLE))
        add("home.page-fling", Spec(Kind.MOVE, WHOLE, drive = m("home.page-snap")))
        add("drawer", Spec(Kind.SLIDE_UP, WHOLE, 0f, 1f, m("drawer")))
        pair("folder.open", "folder.close", Kind.GROW, CARD)
        add("arrival", Spec(Kind.ZOOM, WHOLE))
        add("arrival.wallpaper", Spec(Kind.ZOOM, WHOLE))
        add("arrival.hold", Spec(Kind.ZOOM, WHOLE))
        for (n in listOf("arrival.zoom", "arrival.wallpaper-zoom")) add(n, Spec(Kind.ZOOM, WHOLE, drive = m("arrival")))
        add("appear", Spec(Kind.GROW, SMALL, 0f, 1f, m("disappear.fade")))
        pair("appear.fade", "disappear.fade", Kind.FADE, SMALL)
        add("icon.press-dim", Spec(Kind.PRESS, SMALL, 0f, 1f, m("icon.press-out"), m("icon.press-in")))
        pair("icon.press-in", "icon.press-out", Kind.PRESS, SMALL)
        add("home.label-tone", Spec(Kind.FADE, ICONS, 0f, 1f, m("home.label-tone")))
        pair("home.pill-hide", "home.pill-show", Kind.FADE, Rect(0.4f, 0.83f, 0.6f, 0.86f))
        add("home.pill-edit", Spec(Kind.FADE, Rect(0.4f, 0.83f, 0.6f, 0.86f), 0f, 1f, m("home.pill-edit")))
        add("clock.tick", Spec(Kind.ROLL, CLOCK))
        add("clock.appear", Spec(Kind.FADE, CLOCK))
        add("wallpaper.appear", Spec(Kind.FADE, WHOLE))
        add("wallpaper.crossfade", Spec(Kind.FADE, WHOLE, 0f, 1f, m("wallpaper.crossfade")))
        add("wallpaper.reveal", Spec(Kind.REVEAL, WHOLE))
        add("home.rebuild", Spec(Kind.REVEAL, WHOLE))
        add("home.rebuild-settle", Spec(Kind.GROW, ICONS))
        // Editing home.
        add("edit.bar", Spec(Kind.SLIDE_DOWN, Rect(0.04f, 0.055f, 0.96f, 0.1f), 0f, 1f, m("edit.bar")))
        pair("edit.drag-lift", "edit.drag-settle", Kind.LIFT, SMALL)
        add("edit.reflow", Spec(Kind.MOVE, ICONS))
        for (n in listOf("edit.jiggle", "edit.jiggle-period")) add(n, Spec(Kind.WIGGLE, ICONS))
        add("edit.jiggle-stop", Spec(Kind.WIGGLE, ICONS, 1f, 0f))
        pair("edit.press-in", "edit.press-out", Kind.PRESS, SMALL)
        add("widget.resize", Spec(Kind.RESIZE, Rect(0.06f, 0.2f, 0.94f, 0.36f), 0f, 1f, m("widget.resize")))
        add("widget.layout-fade", Spec(Kind.FADE, Rect(0.06f, 0.2f, 0.94f, 0.36f)))
        // Menus, sheets and search.
        pair("menu.open", "menu.close", Kind.GROW, MENU)
        add("menu.blur", Spec(Kind.GROW, MENU, 0f, 1f, m("menu.close"), m("menu.open")))
        add("sheet", Spec(Kind.SLIDE_UP, SHEET, 0f, 1f, m("sheet")))
        add("sheet.push", Spec(Kind.SLIDE_SIDE, SHEET, 0f, 1f, m("sheet.push")))
        add("settings.open", Spec(Kind.GROW, SHEET, 0f, 1f, m("settings.open")))
        add("settings.focus", Spec(Kind.ZOOM, WHOLE, 0f, 1f, m("settings.focus")))
        add("settings.hint", Spec(Kind.MOVE, WHOLE))
        add("mode-crossfade", Spec(Kind.FADE, LIST, 0f, 1f, m("mode-crossfade")))
        add("index.scroll", Spec(Kind.MOVE, LIST))
        pair("index.bubble-in", "index.bubble-out", Kind.GROW, SMALL)
        add("index.follow", Spec(Kind.MOVE, SMALL))
        pair("field.clear-in", "field.clear-out", Kind.GROW, SMALL)
        add("widgets.row", Spec(Kind.SLIDE_UP, SHEET))
        add("widgets.row-stagger", Spec(Kind.SLIDE_UP, SHEET, drive = m("widgets.row")))
        add("widgets.preview", Spec(Kind.FADE, CARD))
        // Scrolling.
        add("scroll.overscroll-return", Spec(Kind.MOVE, LIST))
        for (n in listOf("scroll.deceleration", "scroll.rubber-band")) add(n, Spec(Kind.MOVE, LIST, drive = m("scroll.overscroll-return")))
        // Control Center.
        pair("cc.open", "cc.close", Kind.SLIDE_DOWN, CC)
        add("cc.close-style", Spec(Kind.FADE, CC, 1f, 0f, m("cc.open")))
        add("cc.settle", Spec(Kind.MOVE, CC))
        add("cc.stretch-back", Spec(Kind.MOVE, CC))
        pair("cc.press-in", "cc.press-out", Kind.PRESS, CONTROL)
        add("cc.toggle", Spec(Kind.FADE, CONTROL, 0f, 1f, m("cc.toggle")))
        add("cc.level", Spec(Kind.LEVEL, Rect(0.54f, 0.14f, 0.7f, 0.42f), 0f, 1f, m("cc.level")))
        pair("cc.expanded.open", "cc.expanded.close", Kind.GROW, CC)
        add("cc.expanded.level", Spec(Kind.LEVEL, Rect(0.4f, 0.14f, 0.6f, 0.46f), 0f, 1f, m("cc.expanded.level")))
        add("cc.edit", Spec(Kind.FADE, CC, 0f, 1f, m("cc.edit")))
        add("cc.reflow", Spec(Kind.MOVE, CC))
        add("cc.resize", Spec(Kind.RESIZE, CONTROL, 0f, 1f, m("cc.resize")))
        pair("cc.appear", "cc.leave", Kind.GROW, CONTROL)
        pair("cc.lift", "cc.drop", Kind.LIFT, CONTROL)
        pair("cc.gallery.open", "cc.gallery.close", Kind.SLIDE_UP, SHEET)
        add("cc.backdrop-hold", Spec(Kind.FADE, WHOLE))
        // Notifications.
        pair("nc.open", "nc.close", Kind.SLIDE_DOWN, WHOLE)
        pair("nc.appear", "nc.leave", Kind.GROW, PLATTERS)
        add("nc.reflow", Spec(Kind.MOVE, PLATTERS))
        add("nc.swipe-back", Spec(Kind.SWITCH_BACK, PLATTERS))
        add("nc.swipe-out", Spec(Kind.FLY, PLATTERS))
        pair("nc.menu-open", "nc.menu-close", Kind.GROW, PLATTERS)
        add("nc.confirm", Spec(Kind.RESIZE, SMALL, 0f, 1f, m("nc.confirm")))
        add("nc.hold", Spec(Kind.LIFT, SMALL, 0f, 1f, m("nc.hold")))
        add("nc.toggle", Spec(Kind.FADE, SMALL, 0f, 1f, m("nc.toggle")))
        add("nc.clock-tick", Spec(Kind.ROLL, CLOCK))
        add("nc.launch-fade", Spec(Kind.CARD_FADE, WHOLE))
        add("nc.time-fade", Spec(Kind.FADE, Rect(0.7f, 0.42f, 0.9f, 0.45f), 0f, 1f, m("nc.time-fade")))
        for (n in listOf("nc.pull-dim-home", "nc.pull-dim-app", "nc.reveal", "nc.lens-in")) add(n, Spec(Kind.FADE, WHOLE, 0f, 1f, m(n)))
        add("nc.pull-undim", Spec(Kind.FADE, WHOLE, 1f, 0f))
        add("nc.bar-fade", Spec(Kind.FADE, STATUS, 1f, 0f))
        pair("press-in", "press-out", Kind.PRESS, PLATTERS)
        // Banners.
        add("banner.in", Spec(Kind.BANNER, TOP_BAND, 0f, 1f, m("banner.out")))
        add("banner.out", Spec(Kind.BANNER, TOP_BAND, 1f, 0f, m("banner.in")))
        add("banner.fly", Spec(Kind.FLY, TOP_BAND))
        add("banner.fly-fade", Spec(Kind.FADE, TOP_BAND, 1f, 0f))
        add("banner.back", Spec(Kind.SWITCH_BACK, TOP_BAND))
        add("banner.resize", Spec(Kind.RESIZE, TOP_BAND, 0f, 1f, m("banner.resize")))
        add("banner.app-fade", Spec(Kind.FADE, TOP_BAND, 0f, 1f, m("banner.app-fade")))
        // Status bar and gesture bar.
        add("statusbar.move", Spec(Kind.MOVE, STATUS))
        pair("statusbar.appear", "statusbar.leave", Kind.GROW, Rect(0.62f, 0.014f, 0.7f, 0.038f))
        add("statusbar.level", Spec(Kind.LEVEL, Rect(0.84f, 0.016f, 0.92f, 0.036f), 0f, 1f, m("statusbar.level")))
        add("statusbar.roll", Spec(Kind.ROLL, Rect(0.06f, 0.012f, 0.22f, 0.04f)))
        pair("statusbar.show", "statusbar.hide", Kind.FADE, STATUS)
        add("statusbar.tone", Spec(Kind.FADE, STATUS, 0f, 1f, m("statusbar.tone")))
        add("statusbar.lock", Spec(Kind.FADE, STATUS, 0f, 1f, m("statusbar.lock")))
        // The charging capsule over the bar's battery (drawn by the phone's scene: PhoneScene.drawChargeMoment).
        pair("statusbar.charge-open", "statusbar.charge-close", Kind.CHARGE, Rect(0.68f, 0.012f, 0.95f, 0.04f))
        add("statusbar.charge-content", Spec(Kind.CHARGE, Rect(0.68f, 0.012f, 0.95f, 0.04f), 0f, 1f, m("statusbar.charge-close")))
        add("statusbar.charge-shimmer", Spec(Kind.CHARGE, Rect(0.68f, 0.012f, 0.95f, 0.04f)))
        pair("strip.show", "strip.hide", Kind.FADE, PILL)
        // Light and dark.
        add("appearance.change", Spec(Kind.FADE, WHOLE, 0f, 1f, m("appearance.change")))
    }

    /** [key]'s sample (a plain card fading in where none is written). */
    fun spec(key: String): Spec = SPECS[key] ?: Spec(Kind.FADE, CARD)

    /**
     * The band of the phone (top, bottom; its full width) the picture focuses on for moment [m], or for the animation [role]
     * of it (its editor open): where it plays.
     */
    fun focus(m: Moment, role: String?, statusFrac: Float): Rect {
        val bars = (statusFrac * 3f).coerceIn(0.08f, 0.16f)
        val k = role ?: m.sample
        when {
            k.startsWith("motion.statusbar.") && k != "motion.statusbar.lock" -> return Rect(0f, 0f, 1f, bars)
            k.startsWith("motion.strip.") -> return Rect(0f, 0.84f, 1f, 1f)
            k.startsWith("motion.banner.") -> return Rect(0f, 0f, 1f, 0.24f)
        }
        return when (m) {
            Moments.BANNERS -> Rect(0f, 0f, 1f, 0.24f)
            Moments.STATUS_ICONS, Moments.CHARGING -> Rect(0f, 0f, 1f, bars)
            Moments.CONTROL_CENTER, Moments.CONTROLS, Moments.CC_EDITING -> Rect(0f, 0f, 1f, 0.58f)
            else -> WHOLE
        }
    }

    /** [key]'s sample at [k] of its run ([forward]: its own, else the way back), showing [number] where it is one. */
    fun draw(kit: SampleKit, c: Canvas, key: String, k: Float, forward: Boolean, number: Float) {
        val s = spec(key)
        val w = kit.w
        val h = kit.h
        val a = kit.r.apply { set(s.area.left * w, s.area.top * h, s.area.right * w, s.area.bottom * h) }
        val kk = k.coerceIn(0f, 1f)
        when (s.kind) {
            Kind.APP -> {
                // Opening on its own run (or on the way back from a close), going home on the other.
                val opening = (key == m("app.open")) == forward
                val st = kit.stage.prepare()
                AppStyleArt.draw(st, c, k, if (opening) kit.opening else kit.closing, opening)
            }
            Kind.APP_CANCEL -> {
                // Pulled down a little by the finger, let go: back to the whole screen.
                val pull = 1f - k
                c.save()
                c.translate(0f, pull * h * 0.12f)
                c.scale(1f - 0.18f * pull, 1f - 0.18f * pull, w / 2f, h * 0.35f)
                page(kit, c, 0f, 0f, w, h, kit.corner * (0.4f + 0.6f * pull.coerceIn(0f, 1f)), 1f)
                c.restore()
            }
            Kind.DEPTH -> {
                // Home stepping back (zoomed, dimmed) behind an app's page drawn see-through, so home's motion shows.
                val zoom = if (number.isNaN()) 1.12f else if (key.endsWith("depth-blur")) 1.12f else number
                val z = 1f + (zoom - 1f) * kk
                home(kit, c, z, if (key.endsWith("depth-blur") && !number.isNaN()) 0.1f + number / 40f * 0.3f * kk else 0.2f * kk)
                app(kit, c, k, 0.35f)
            }
            Kind.SWITCH -> {
                // The app's page leaves sideways as another one comes.
                val x = -k * w
                page(kit, c, x, 0f, x + w, h, kit.corner, 1f)
                page(kit, c, x + w + w * 0.04f, 0f, x + w * 2.04f, h, kit.corner, 1f, tint = true)
            }
            Kind.SWITCH_BACK -> {
                // Moved part of the way sideways by the finger, let go: back.
                val off = (1f - k) * a.width() * 0.3f
                if (a.height() >= h * 0.9f) page(kit, c, off, 0f, w + off, h, kit.corner, 1f) else card(kit, c, a.left + off, a.top, a.right + off, a.bottom, 1f)
            }
            Kind.CARD_FADE -> {
                // The launch colour giving way to the app's page.
                kit.paint.color = kit.accent
                c.drawRoundRect(0f, 0f, w, h, kit.corner, kit.corner, kit.paint)
                page(kit, c, 0f, 0f, w, h, kit.corner, kk)
            }
            Kind.GROW -> {
                val sc = 0.3f + 0.7f * k
                c.save()
                c.scale(sc, sc, a.centerX(), a.centerY())
                card(kit, c, a.left, a.top, a.right, a.bottom, (kk / 0.5f).coerceIn(0f, 1f))
                c.restore()
            }
            Kind.SLIDE_DOWN -> {
                val off = -(1f - k) * (a.bottom + 0.02f * h)
                if (a.height() >= h * 0.9f) page(kit, c, 0f, off, w, h + off, kit.corner, 1f) else card(kit, c, a.left, a.top + off, a.right, a.bottom + off, 1f)
            }
            Kind.SLIDE_UP -> {
                val off = (1f - k) * (h - a.top + 0.02f * h)
                card(kit, c, a.left, a.top + off, a.right, a.bottom + off, 1f, sheet = true)
            }
            Kind.SLIDE_SIDE -> {
                val off = (1f - k) * w
                card(kit, c, a.left + off, a.top, a.right + off, a.bottom, 1f, sheet = true)
            }
            Kind.FADE -> card(kit, c, a.left, a.top, a.right, a.bottom, kk)
            Kind.PRESS -> {
                val sc = 1f - 0.12f * k
                c.save()
                c.scale(sc, sc, a.centerX(), a.centerY())
                card(kit, c, a.left, a.top, a.right, a.bottom, 1f)
                // Its dim (a press's darkening: the number, where the animation is one).
                val dim = (if (number.isNaN()) 0.32f else number) * kk
                kit.paint.color = (((255 * dim).toInt().coerceIn(0, 255)) shl 24)
                c.drawRoundRect(a.left, a.top, a.right, a.bottom, cornerOf(a), cornerOf(a), kit.paint)
                c.restore()
            }
            Kind.MOVE -> {
                val off = (1f - k) * a.width() * 0.35f
                card(kit, c, a.left + off, a.top, a.right + off, a.bottom, 1f)
            }
            Kind.FLY -> {
                val off = -k * (a.bottom + 0.05f * h)
                card(kit, c, a.left, a.top + off, a.right, a.bottom + off, (1f - kk * 0.6f))
            }
            Kind.LEVEL -> {
                card(kit, c, a.left, a.top, a.right, a.bottom, 1f)
                val level = 0.3f + 0.5f * k
                kit.paint.color = kit.accent
                val top = a.bottom - a.height() * level
                c.drawRoundRect(a.left, top.coerceIn(a.top, a.bottom), a.right, a.bottom, cornerOf(a), cornerOf(a), kit.paint)
            }
            Kind.ROLL -> {
                // The old digit going up, the new one coming from below.
                card(kit, c, a.left, a.top, a.right, a.bottom, 1f)
                c.save()
                c.clipRect(a)
                val y = -k * a.height()
                bar(kit, c, a.left + a.width() * 0.2f, a.top + a.height() * 0.35f + y, a.width() * 0.6f, a.height() * 0.3f, 0.8f)
                bar(kit, c, a.left + a.width() * 0.2f, a.top + a.height() * 1.35f + y, a.width() * 0.6f, a.height() * 0.3f, 0.8f)
                c.restore()
            }
            Kind.WIGGLE -> {
                // Icons wiggling (the number: how far, or how long one wiggle takes), at the clock's pace.
                val deg = if (key.endsWith("jiggle") && !number.isNaN()) number else 1.6f
                val period = if (key.endsWith("jiggle-period") && !number.isNaN()) number else 0.26f
                val amp = deg * (if (s.from > s.to) kk else 1f)
                val now = (android.os.SystemClock.uptimeMillis() % 100_000L) / 1000.0
                val cols = 4
                val size = a.width() / cols * 0.62f
                for (i in 0 until 8) {
                    val cx = a.left + a.width() * ((i % cols) + 0.5f) / cols
                    val cy = a.top + a.height() * ((i / cols) + 0.5f) / 2f
                    val rot = amp * sin(now * 2 * PI / period + i * 1.3).toFloat()
                    c.save()
                    c.rotate(rot, cx, cy)
                    card(kit, c, cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f, 1f, icon = true)
                    c.restore()
                }
            }
            Kind.ZOOM -> {
                if (a.height() >= h * 0.9f) {
                    val z0 = if (number.isNaN()) 1.18f else number
                    home(kit, c, 1f + (z0 - 1f) * (1f - k), 0f)
                } else {
                    // A card growing from the deck's size to the whole screen.
                    val l = a.left * (1f - k); val t = a.top * (1f - k)
                    page(kit, c, l, t, a.right + (w - a.right) * k, a.bottom + (h - a.bottom) * k, kit.corner, 1f)
                }
            }
            Kind.REVEAL -> {
                // The new look spreading from a point.
                kit.path.rewind()
                kit.path.addCircle(w / 2f, h * 0.55f, kk * h * 0.75f, Path.Direction.CW)
                c.save()
                c.clipPath(kit.path)
                kit.paint.color = (kit.accent and 0xFFFFFF) or (0x55 shl 24)
                c.drawRect(0f, 0f, w, h, kit.paint)
                c.restore()
            }
            Kind.RESIZE -> {
                val right = a.left + a.width() * (0.55f + 0.45f * k)
                card(kit, c, a.left, a.top, right, a.bottom, 1f)
            }
            Kind.LIFT -> {
                val sc = 1f + 0.12f * k
                c.save()
                c.scale(sc, sc, a.centerX(), a.centerY())
                kit.paint.color = (((60 * kk).toInt()) shl 24)
                c.drawRoundRect(a.left, a.top + a.height() * 0.08f * kk, a.right, a.bottom + a.height() * 0.08f * kk, cornerOf(a), cornerOf(a), kit.paint)
                card(kit, c, a.left, a.top, a.right, a.bottom, 1f)
                c.restore()
            }
            Kind.BANNER -> banner(kit, c, k)
            // Over the bar itself, after it ([drawOverBar]); a card where no phone is drawn.
            Kind.CHARGE -> if (kit.scene == null) card(kit, c, a.left, a.top, a.right, a.bottom, kk)
        }
    }

    /** Whether [key]'s sample is of the bar itself, drawn over it ([drawOverBar]) rather than under it. */
    fun overBar(key: String) = spec(key).kind == Kind.CHARGE

    /**
     * [key]'s sample over the phone's status bar at [k]: the charging moment over its battery, the capsule growing out of
     * it and back (its own run, or the way back on the close), what it holds coming in, or the light through the charge.
     */
    fun drawOverBar(kit: SampleKit, c: Canvas, key: String, k: Float, forward: Boolean) {
        val sc = kit.scene ?: return
        when {
            key.endsWith("charge-content") -> sc.drawChargeMoment(c, 1f, if (forward) 1f else k, k)
            key.endsWith("charge-shimmer") -> sc.drawChargeMoment(c, 1f, 1f, 1f, k.coerceIn(0f, 1f))
            else -> sc.drawChargeMoment(c, 1f, k, k)
        }
    }

    private fun cornerOf(a: RectF) = minOf(a.width(), a.height()) * 0.22f

    /** A stand-in for an element: a card in the theme's colours with a coloured mark and two lines of text. */
    private fun card(kit: SampleKit, c: Canvas, l: Float, t: Float, r: Float, b: Float, alpha: Float, sheet: Boolean = false, icon: Boolean = false) {
        if (alpha <= 0.003f) return
        val a = alpha.coerceIn(0f, 1f)
        val wd = r - l
        val ht = b - t
        val corner = if (sheet) minOf(wd, ht) * 0.08f else minOf(wd, ht) * 0.22f
        kit.paint.color = withAlpha(kit.fill, a * 0.97f)
        c.drawRoundRect(l, t, r, b, corner, corner, kit.paint)
        if (icon) {
            kit.paint.color = withAlpha(kit.accent, a * 0.85f)
            val ins = wd * 0.22f
            c.drawRoundRect(l + ins, t + ins, r - ins, b - ins, corner * 0.6f, corner * 0.6f, kit.paint)
            return
        }
        val unit = minOf(ht, wd * 0.5f)
        val mark = unit * 0.42f
        val mx = l + unit * 0.28f
        val my = t + (minOf(ht, unit) - mark) / 2f
        kit.paint.color = withAlpha(kit.accent, a)
        c.drawRoundRect(mx, my, mx + mark, my + mark, mark * 0.23f, mark * 0.23f, kit.paint)
        val tx = mx + mark + unit * 0.2f
        val room = (r - tx - unit * 0.2f).coerceAtLeast(0f)
        bar(kit, c, tx, my + mark * 0.12f, room * 0.6f, mark * 0.24f, 0.7f * a)
        bar(kit, c, tx, my + mark * 0.62f, room * 0.85f, mark * 0.2f, 0.3f * a)
    }

    private fun bar(kit: SampleKit, c: Canvas, x: Float, y: Float, w: Float, h: Float, alpha: Float) {
        if (w <= 0f || h <= 0f) return
        kit.paint.color = withAlpha(kit.ink, alpha)
        c.drawRoundRect(x, y, x + w, y + h, h / 2f, h / 2f, kit.paint)
    }

    /** An app's page (a title and two groups of rows, in the theme's page colours) in l, t, r, b. */
    internal fun page(kit: SampleKit, c: Canvas, l: Float, t: Float, r: Float, b: Float, corner: Float, alpha: Float, tint: Boolean = false) {
        if (alpha <= 0.003f) return
        kit.scene?.let { it.drawAppPage(c, l, t, r, b, corner, alpha, seed = if (tint) 1 else 0); return }
        val w = r - l
        val h = b - t
        c.save()
        kit.path.rewind()
        kit.path.addRoundRect(l, t, r, b, corner, corner, Path.Direction.CW)
        c.clipPath(kit.path)
        kit.paint.color = withAlpha(if (tint) mixColor(kit.page, kit.accent, 0.12f) else kit.page, alpha)
        c.drawRect(l, t, r, b, kit.paint)
        kit.paint.color = withAlpha(kit.ink, 0.85f * alpha)
        c.drawRoundRect(l + w * 0.06f, t + h * 0.075f, l + w * 0.5f, t + h * 0.1f, h * 0.012f, h * 0.012f, kit.paint)
        for (gi in 0..1) {
            val top = t + h * (0.14f + gi * 0.25f)
            kit.paint.color = withAlpha(kit.fill, alpha)
            c.drawRoundRect(l + w * 0.04f, top, r - w * 0.04f, top + h * 0.21f, w * 0.05f, w * 0.05f, kit.paint)
            kit.paint.color = withAlpha(kit.ink, 0.22f * alpha)
            for (ri in 0..2) {
                val y = top + h * (0.045f + ri * 0.065f)
                c.drawRoundRect(l + w * 0.1f, y, l + w * (0.42f + 0.12f * ((ri + gi) % 2)), y + h * 0.014f, h * 0.007f, h * 0.007f, kit.paint)
            }
        }
        c.restore()
    }

    /** The app's page out of the dock's middle icon at [k] of its opening (home dimming behind it), at [alpha]. */
    private fun app(kit: SampleKit, c: Canvas, k: Float, alpha: Float) {
        val w = kit.w
        val h = kit.h
        val kk = k.coerceIn(0f, 1f)
        val ic = kit.icon
        val icon = ic?.width() ?: (w * 0.17f)
        val fl = ic?.left ?: (w * 0.5f - icon / 2f)
        val ft = ic?.top ?: (h * 0.9f - icon)
        val fr = ic?.right ?: (w * 0.5f + icon / 2f)
        val fb = ic?.bottom ?: (h * 0.9f)
        val kc = k.coerceIn(-0.1f, 1.1f)
        val l = fl * (1f - kc); val t = ft * (1f - kc)
        val r = fr + (w - fr) * kc; val b = fb + (h - fb) * kc
        if (alpha >= 1f) c.drawColor((((255 * 0.25f * kk).toInt()) shl 24))
        page(kit, c, l, t, r, b, icon * 0.23f + (kit.corner - icon * 0.23f) * kk, alpha)
        // The icon it grows out of, fading into the page as it opens.
        val iconA = (1f - kk / 0.45f).coerceIn(0f, 1f) * alpha
        val home = kit.home
        val scene = kit.scene
        if (ic != null && home == null && scene != null && iconA > 0f) {
            kit.paint.color = withAlpha(scene.dockIconColor(scene.dockMiddle()), iconA)
            val cr = (r - l).coerceAtMost(b - t) * 0.23f
            c.drawRoundRect(l, t, r, b, cr, cr, kit.paint)
        }
        if (ic != null && home != null && iconA > 0f) {
            val sx = home.width / w; val sy = home.height / h
            kit.src.set((ic.left * sx).toInt(), (ic.top * sy).toInt(), (ic.right * sx).toInt(), (ic.bottom * sy).toInt())
            kit.dst.set(l, t, r, b)
            kit.image.alpha = (255 * iconA).toInt()
            c.drawBitmap(home, kit.src, kit.dst, kit.image)
            kit.image.alpha = 255
        }
    }

    /** Home's picture again, zoomed by [zoom] around the centre and dimmed by [dim] (a depth, an arrival). */
    private fun home(kit: SampleKit, c: Canvas, zoom: Float, dim: Float) {
        val b = kit.home
        val scene = kit.scene
        if (b == null && scene == null) return
        c.save()
        c.scale(zoom, zoom, kit.w / 2f, kit.h / 2f)
        if (b != null) {
            kit.src.set(0, 0, b.width, b.height)
            kit.dst.set(0f, 0f, kit.w, kit.h)
            kit.image.alpha = 255
            c.drawBitmap(b, kit.src, kit.dst, kit.image)
        } else if (scene != null) {
            // (Past the phone's edges as it zooms out: the wallpaper reaches them.)
            c.save()
            c.scale(1.2f, 1.2f, kit.w / 2f, kit.h / 2f)
            scene.drawWallpaper(c)
            c.restore()
            scene.drawHome(c, 1f)
        }
        c.restore()
        if (dim > 0.003f) c.drawColor((((255 * dim).toInt().coerceIn(0, 255)) shl 24))
    }

    /** A banner out of the camera, past its place and back as the curve bounces (Motion's reel draws the same). */
    private fun banner(kit: SampleKit, c: Canvas, k: Float) {
        if (k < 0f) return
        val w = kit.w
        val h = kit.h
        val grow = k.coerceIn(0f, 1f) + (k - k.coerceIn(0f, 1f)) * 0.25f
        val bw = w * 0.3f + (w * 0.66f) * grow
        val bh = h * 0.04f + h * 0.04f * grow
        val bt = h * 0.013f + (h * 0.054f) * k
        val bl = (w - bw) / 2f
        card(kit, c, bl, bt, bl + bw, bt + bh, (k / 0.2f).coerceIn(0f, 1f))
    }

    internal fun withAlpha(color: Int, a: Float): Int = (((color ushr 24) * a.coerceIn(0f, 1f)).toInt() shl 24) or (color and 0xFFFFFF)

    private fun mixColor(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) + (((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * t).toInt().coerceIn(0, 255)
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/** The stage's phone as a stage for the apps' styles ([AppStyleArt]): home (drawn, [PhoneScene]), the dock's icon, the sample's page. */
internal class SampleStage(private val kit: SampleKit) : StyleStage() {
    /** Its size, corners and icon from the kit as it was set for this draw. */
    fun prepare(): SampleStage {
        w = kit.w
        h = kit.h
        corner = kit.corner
        val ic = kit.icon
        val size = ic?.width() ?: (w * 0.17f)
        if (ic != null) icon.set(ic) else icon.set(w * 0.5f - size / 2f, h * 0.9f - size, w * 0.5f + size / 2f, h * 0.9f)
        return this
    }

    override fun home(c: Canvas, zoom: Float, px: Float, py: Float) {
        val b = kit.home
        val scene = kit.scene
        if (b == null && scene == null) return
        c.save()
        c.scale(zoom, zoom, px, py)
        if (b != null) {
            kit.src.set(0, 0, b.width, b.height)
            kit.dst.set(0f, 0f, w, h)
            kit.image.alpha = 255
            c.drawBitmap(b, kit.src, kit.dst, kit.image)
        } else if (scene != null) {
            scene.drawWallpaper(c)
            scene.drawHome(c, 1f)
        }
        c.restore()
    }

    override fun page(c: Canvas, l: Float, t: Float, r: Float, b: Float, corner: Float, alpha: Float) =
        MotionSamples.page(kit, c, l, t, r, b, corner, alpha)

    override fun icon(c: Canvas, l: Float, t: Float, r: Float, b: Float, alpha: Float) {
        if (alpha <= 0.003f) return
        val home = kit.home
        val ic = kit.icon
        val scene = kit.scene
        if (home == null && scene != null) {
            // The dock's icon as the phone draws it.
            kit.paint.color = MotionSamples.withAlpha(scene.dockIconColor(scene.dockMiddle()), alpha)
            val cr = kotlin.math.min(r - l, b - t) * 0.23f
            c.drawRoundRect(l, t, r, b, cr, cr, kit.paint)
        } else if (home != null && ic != null) {
            // The dock's icon as home shows it.
            val sx = home.width / w
            val sy = home.height / h
            kit.src.set((ic.left * sx).toInt(), (ic.top * sy).toInt(), (ic.right * sx).toInt(), (ic.bottom * sy).toInt())
            kit.dst.set(l, t, r, b)
            kit.image.alpha = (255 * alpha.coerceIn(0f, 1f)).toInt()
            c.drawBitmap(home, kit.src, kit.dst, kit.image)
            kit.image.alpha = 255
        } else {
            kit.paint.color = MotionSamples.withAlpha(kit.accent, alpha)
            val cr = kotlin.math.min(r - l, b - t) * 0.23f
            c.drawRoundRect(l, t, r, b, cr, cr, kit.paint)
        }
    }

    override fun box(c: Canvas, l: Float, t: Float, r: Float, b: Float, corner: Float, alpha: Float) {
        if (alpha <= 0.003f) return
        kit.paint.color = MotionSamples.withAlpha(kit.page, alpha)
        c.drawRoundRect(l, t, r, b, corner, corner, kit.paint)
    }

    override fun pageKey(): Long = ((kit.page.toLong() * 1_000_003L + kit.fill) * 1_000_003L + kit.ink) * 31 + kit.corner.toBits()
}
