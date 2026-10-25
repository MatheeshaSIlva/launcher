package dev.launcher.app.motion

import dev.launcher.app.design.ChoiceKey
import dev.launcher.app.design.NumberKey

/** The part of the phone a moment belongs to (the settings' tiles, docs/PLAN_SETTINGS_V2.md); [EVERYWHERE]: no one part. */
enum class MomentPart { APPS, HOME, LIBRARY, SHADE, STATUS_BAR, EVERYWHERE }

/**
 * A moment's character (docs/PLAN_SETTINGS_V2.md, V2): numbers of its that move together on one slider ("How far home steps
 * back"). Each key is scaled away from its [neutral][keys] (where the moment shows none of it) by the slider's value: 0 none,
 * 1 the preset's own, 2 twice as much. [low] and [high] name the slider's ends.
 */
class Character(val title: String, val low: String, val high: String, val keys: List<Pair<String, Float>>)

/**
 * A moment of the launcher's motion: something a person notices as one thing ("Opening apps"), made of several animations
 * (roles). Its own Speed and Bounce ([speed], [bounce]: `motion.adjust.<id>.*` in the preset, 1 = as the preset) apply on top
 * of the preset's to every curve it holds ([MotionAdjust], where a curve is read). [roles] are listed in the settings (Every
 * detail), in the order they play; [glue] follows the moment's feel unlisted: animations nobody sees on their own (a launch
 * colour, a late picture fading in), which only the token editor shows.
 */
class Moment(
    val id: String, val part: MomentPart, val title: String, val description: String,
    val roles: List<String>, val glue: List<String> = emptyList(), val character: Character? = null,
    /** The animation that shows the moment best (the settings' picture plays it): its first, unless another says more. */
    val sample: String = roles.first(),
    /** Its choreography, where it has a choice of them ([AppStyles]: opening apps, going home). */
    val style: ChoiceKey? = null,
) {
    val speed = NumberKey("motion.adjust.$id.speed")
    val bounce = NumberKey("motion.adjust.$id.bounce")
    /** Every animation that follows this moment's feel. */
    val all: List<String> get() = roles + glue
}

/**
 * The 24 moments (docs/PLAN_SETTINGS_V2.md): every animation of the launcher belongs to exactly one (a test checks it), so a
 * moment's feel is one value whatever it holds. Built from the plan's table: Apps 4, Home 6, App Library 2, Notifications &
 * Control Center 7 (Quick Settings, Android's shade, joined it on 2026-10-21), Status bar 3 (Charging joined it with the
 * charging moment, 2026-10-18), and two everywhere.
 */
object Moments {
    private fun k(s: String) = "motion.$s"
    private fun ks(vararg s: String) = s.map { k(it) }

    val OPENING = Moment("opening", MomentPart.APPS, "Opening apps", "An app growing out of its icon as home steps back behind it.",
        ks("app.open", "home.depth-open", "home.content-zoom", "home.wallpaper-zoom", "home.depth-blur"),
        glue = ks("card.color", "card.snapshot", "card.fade-app"),
        character = Character("How far home steps back", "Not at all", "Far",
            listOf(k("home.content-zoom") to 1f, k("home.wallpaper-zoom") to 1f, k("home.depth-blur") to 0f)),
        style = AppStyles.OPENING)
    val CLOSING = Moment("closing", MomentPart.APPS, "Going home", "An app shrinking back into its icon as home comes forward.",
        ks("app.close-size", "app.close-position", "home.depth-close", "app.cancel"),
        glue = ks("card.fade-home", "card.catch-up"), style = AppStyles.CLOSING)
    val SWITCHING = Moment("switching", MomentPart.APPS, "Switching apps", "A sideways swipe on the bar to the app before or after.",
        ks("switch.commit", "switch.cancel"))
    val SWITCHER = Moment("switcher", MomentPart.APPS, "App Switcher", "The deck of recent apps coming, scrolling, a card opening or flicked away.",
        ks("switcher.enter", "switcher.scroll", "switcher.open", "switcher.flick", "switcher.reflow", "switcher.home",
            "switcher.clear-press", "switcher.clear-stagger", "switcher.clear-home"),
        character = Character("Clear All's rhythm", "All at once", "One by one",
            listOf(k("switcher.clear-stagger") to 0f, k("switcher.clear-home") to 0f)))

    val PAGES = Moment("pages", MomentPart.HOME, "Pages", "Home's pages turning, and the Search pill becoming their dots.",
        ks("home.page-snap", "home.pill-hide", "home.pill-show", "home.page-fling"))
    val FOLDERS = Moment("folders", MomentPart.HOME, "Folders", "A folder opening out of its icon, and closing.",
        ks("folder.open", "folder.close"))
    val UNLOCKING = Moment("unlocking", MomentPart.HOME, "Unlocking", "Home arriving as the phone is unlocked or the launcher starts.",
        ks("arrival", "arrival.wallpaper", "arrival.zoom", "arrival.wallpaper-zoom"),
        glue = ks("arrival.hold"),
        character = Character("How far home zooms in", "Not at all", "Far",
            listOf(k("arrival.zoom") to 1f, k("arrival.wallpaper-zoom") to 1f)))
    val EDITING = Moment("editing", MomentPart.HOME, "Editing", "Icons wiggling, lifting, making room and dropping; widgets resizing.",
        ks("edit.bar", "edit.drag-lift", "edit.reflow", "edit.drag-settle", "edit.jiggle-stop", "edit.press-in", "edit.press-out",
            "widget.resize", "widget.layout-fade", "home.pill-edit", "edit.jiggle", "edit.jiggle-period"),
        character = Character("Wiggle", "Still", "Wild", listOf(k("edit.jiggle") to 0f)), sample = k("edit.drag-lift"))
    val LITTLE = Moment("little", MomentPart.HOME, "Little things", "Something new arriving, an icon pressed, the clock's minute, names changing tone.",
        ks("appear", "appear.fade", "disappear.fade", "icon.press-in", "icon.press-out", "icon.press-dim", "clock.tick", "clock.appear",
            "home.label-tone"))
    val NEW_LOOK = Moment("newlook", MomentPart.HOME, "A new look", "A new theme or wallpaper spreading over home, and light and dark.",
        ks("home.rebuild", "home.rebuild-settle", "wallpaper.reveal", "appearance.change"),
        glue = ks("wallpaper.appear", "wallpaper.crossfade"))

    val LIBRARY = Moment("library", MomentPart.LIBRARY, "Opening and search", "The App Library sliding in, its search and the A to Z index.",
        ks("drawer", "mode-crossfade", "field.clear-in", "field.clear-out", "index.scroll", "index.bubble-in", "index.follow", "index.bubble-out"))
    val MENUS = Moment("menus", MomentPart.LIBRARY, "Menus and sheets", "Long-press menus, alerts, and sheets such as the widget gallery.",
        ks("menu.open", "menu.close", "alert.open", "alert.close", "sheet", "widgets.row", "widgets.preview", "menu.blur", "widgets.row-stagger"))

    val CONTROL_CENTER = Moment("cc", MomentPart.SHADE, "Control Center", "Control Center coming down and going.",
        ks("cc.open", "cc.settle", "cc.close", "cc.close-style", "cc.stretch-back"),
        glue = ks("cc.backdrop-hold"))
    val CONTROLS = Moment("controls", MomentPart.SHADE, "Controls", "A control pressed and turning on, a slider following, a module expanding.",
        ks("cc.press-in", "cc.press-out", "cc.toggle", "cc.level", "cc.expanded.open", "cc.expanded.close", "cc.expanded.level"),
        sample = k("cc.toggle"))
    val CC_EDITING = Moment("cc-editing", MomentPart.SHADE, "Editing controls", "Rearranging Control Center: controls lifted, resized, added and removed.",
        ks("cc.edit", "cc.lift", "cc.reflow", "cc.drop", "cc.resize", "cc.appear", "cc.leave", "cc.gallery.open", "cc.gallery.close"),
        sample = k("cc.lift"))
    val NOTIFICATION_CENTER = Moment("nc", MomentPart.SHADE, "Notification Center", "Notifications coming down, arriving, swiped away and held.",
        ks("nc.open", "nc.close", "nc.appear", "nc.leave", "nc.reflow", "nc.swipe-out", "nc.swipe-back", "nc.menu-open", "nc.menu-close",
            "nc.confirm", "press-in", "press-out", "nc.pull-dim-home", "nc.pull-dim-app", "nc.pull-undim", "nc.reveal", "nc.bar-fade"),
        glue = ks("nc.launch-fade", "nc.time-fade", "nc.lens-in"))
    val QUICK_SETTINGS = Moment("qs", MomentPart.SHADE, "Quick Settings", "Android's shade pulled down, Quick Settings expanding, a tile pressed and tapped.",
        ks("qs.open", "qs.close", "qs.expand", "qs.collapse", "qs.tile-bounce", "qs.tile-state", "qs.tile-symbol", "qs.tile-pop", "qs.edit", "qs.edit-place",
            "qs.page"),
        glue = ks("qs.tile-pop-back", "qs.slider-icon", "qs.edit-appear", "qs.media", "qs.media-reveal", "qs.ripple", "qs.ripple-in",
            "qs.ripple-out"))
    val LOCK_SCREEN = Moment("lock", MomentPart.SHADE, "Lock screen", "The lock screen's buttons and clock, and the time over it.",
        ks("nc.hold", "nc.toggle", "nc.clock-tick", "statusbar.lock"))
    val BANNERS = Moment("banners", MomentPart.SHADE, "Banners", "Notifications swooping in over apps and home, and flung away.",
        ks("banner.in", "banner.out", "banner.fly", "banner.back", "banner.resize"),
        glue = ks("banner.fly-fade", "banner.app-fade"))

    val STATUS_ICONS = Moment("status-icons", MomentPart.STATUS_BAR, "Status icons", "Icons arriving, leaving and moving aside; the battery and the time's digits.",
        ks("statusbar.appear", "statusbar.leave", "statusbar.move", "statusbar.level", "statusbar.roll"))
    val BARS = Moment("bars", MomentPart.STATUS_BAR, "Hiding and showing", "The bar and the gesture pill fading away and back, and changing tone.",
        ks("statusbar.hide", "statusbar.show", "statusbar.tone", "strip.hide", "strip.show"))
    val CHARGING = Moment("charging", MomentPart.STATUS_BAR, "Charging", "The battery growing into a capsule when a charger is connected, and folding back.",
        ks("statusbar.charge-open", "statusbar.charge-content", "statusbar.charge-shimmer", "statusbar.charge-close"))

    val SCROLLING = Moment("scrolling", MomentPart.EVERYWHERE, "Scrolling", "How lists move under the finger and after it.",
        ks("scroll.overscroll-return", "scroll.deceleration", "scroll.rubber-band"))
    val SETTINGS = Moment("settings", MomentPart.EVERYWHERE, "Settings app", "A tile opening into its page, the picture focusing, the finger's hint, a page pushed.",
        ks("settings.open", "settings.focus", "settings.hint", "sheet.push"))

    val ALL = listOf(OPENING, CLOSING, SWITCHING, SWITCHER, PAGES, FOLDERS, UNLOCKING, EDITING, LITTLE, NEW_LOOK, LIBRARY, MENUS,
        CONTROL_CENTER, CONTROLS, CC_EDITING, NOTIFICATION_CENTER, QUICK_SETTINGS, LOCK_SCREEN, BANNERS, STATUS_ICONS, BARS, CHARGING, SCROLLING, SETTINGS)

    private val byRole: Map<String, Moment> = HashMap<String, Moment>().apply { for (m in ALL) for (r in m.all) put(r, m) }

    /** The moment animation [key] belongs to (null: none, e.g. a preset's own speed). */
    fun of(key: String): Moment? = byRole[key]

    fun of(part: MomentPart): List<Moment> = ALL.filter { it.part == part }
}
