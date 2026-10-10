package dev.launcher.app.motion

/**
 * Every motion role by group, in plain words (docs/PLAN_LAYOUTS_THEMES.md, B3b): what the settings app shows. Each role is a
 * `motion.*` token of the animation preset: a curve (a spring or a bezier), or a number that shapes a motion (how far,
 * how long between, how fast). [behaviour] marks numbers that belong to a layout's behaviour more than to its motion
 * (the App Switcher's geometry and gestures): they move into the recents layout with D3.
 */
class MotionRole(val key: String, val title: String, val description: String, val behaviour: Boolean = false)

class MotionGroup(val title: String, val description: String, val roles: List<MotionRole>)

object MotionRoles {
    private fun r(k: String, title: String, description: String, behaviour: Boolean = false) = MotionRole("motion.$k", title, description, behaviour)

    val APPS = MotionGroup("Apps opening and closing", "An app growing out of its icon, going back into it, and home behind it.", listOf(
        r("app.open", "App opening", "The card growing from the icon to the full screen."),
        r("app.close-position", "App closing: path", "Where the closing card travels to its icon."),
        r("app.close-size", "App closing: size", "The closing card shrinking to its icon."),
        r("app.cancel", "Close let go", "A swipe home that does not go far enough: back to the app."),
        r("home.depth-open", "Home stepping back", "Home receding behind an app that opens."),
        r("home.depth-close", "Home coming forward", "Home coming back as an app closes."),
        r("home.content-zoom", "Depth: icons", "How far home's icons zoom while an app is open (1: none)."),
        r("home.wallpaper-zoom", "Depth: wallpaper", "How far the wallpaper zooms while an app is open (1: none)."),
        r("home.depth-blur", "Depth: blur", "How blurred home is behind an open app."),
        r("switch.commit", "Switching apps", "A sideways swipe on the bar going to the next app."),
        r("switch.cancel", "Switch let go", "A sideways swipe that does not go far enough: back."),
        r("card.color", "Launch colour", "A starting app's colour blending into its real launch screen."),
        r("card.snapshot", "Picture arriving", "An app's picture fading in over its card when it arrives late."),
        r("card.catch-up", "Card catching up", "A card that appears late gliding from the full screen to the finger."),
        r("card.fade-home", "Card into home", "The closing card fading into the icon at the end."),
        r("card.fade-app", "Card into the app", "The opening card fading into the app at the end."),
    ))

    val SWITCHER = MotionGroup("App Switcher", "The deck of recent apps.", listOf(
        r("switcher.enter", "Deck coming", "The deck appearing from a held swipe."),
        r("switcher.scroll", "Deck scrolling", "The deck coming to rest on a card."),
        r("switcher.open", "Card opening", "A card growing into its app."),
        r("switcher.home", "Deck going home", "The deck leaving for home."),
        r("switcher.flick", "Card flicked away", "A card thrown up and off."),
        r("switcher.reflow", "Gap closing", "The other cards closing the gap a flicked card left."),
        r("switcher.clear-press", "Clear All pressed", "The Clear All button as it is pressed."),
        r("switcher.clear-stagger", "Clear All: between cards", "How long after one card the next flies off (ms)."),
        r("switcher.clear-home", "Clear All: then home", "How long after the last card home comes (ms)."),
        r("switcher.card-scale", "Card size", "A card's size, of the screen's.", behaviour = true),
        r("switcher.card-center-y", "Card height on screen", "Where a card's centre sits, of the screen's height.", behaviour = true),
        r("switcher.focus-left", "Focused card's place", "Where the focused card's left edge sits, of its width.", behaviour = true),
        r("switcher.hold", "Hold to open", "How long the finger rests during a swipe home before the deck opens (ms).", behaviour = true),
        r("switcher.hold-travel", "Hold height", "How far up (dp) the swipe must be before a hold opens the deck.", behaviour = true),
        r("switcher.fling-projection", "Throw reach", "How far ahead (s) a throw is projected to choose a card.", behaviour = true),
        r("switcher.flick-speed", "Flick speed", "How fast (dp/s) a card must be thrown up to close its app.", behaviour = true),
    ))

    val HOME = MotionGroup("Home", "Pages, folders, the clock, the wallpaper, and home arriving.", listOf(
        r("home.page-snap", "Page snapping", "A page settling after a swipe."),
        r("home.page-fling", "Page fling", "A fling faster than this (dp/s) turns the page even after a short move."),
        r("drawer", "App Library", "The App Library sliding in and out."),
        r("folder.open", "Folder opening", "A folder's icons growing out."),
        r("folder.close", "Folder closing", "A folder's icons going back."),
        r("arrival", "Home arriving", "Home zooming out to rest after unlocking or a fresh start."),
        r("arrival.wallpaper", "Wallpaper arriving", "The wallpaper settling on a fresh start."),
        r("arrival.zoom", "Arrival zoom", "How far zoomed home starts when it arrives (1: none)."),
        r("arrival.wallpaper-zoom", "Arrival zoom: wallpaper", "How far zoomed the wallpaper starts on a fresh start (1: none)."),
        r("arrival.hold", "Arrival held", "Home easing into its arrival while the screen goes off."),
        r("appear", "Something new", "A new icon or widget growing into its place."),
        r("appear.fade", "Fading in", "Something arriving fading in."),
        r("disappear.fade", "Fading out", "Something leaving fading out."),
        r("icon.press-dim", "Icon pressed: dim", "How much an icon darkens while touched."),
        r("icon.press-in", "Icon pressed", "The dim coming when an icon is touched."),
        r("icon.press-out", "Icon let go", "The dim going when the finger lifts."),
        r("home.label-tone", "Names changing tone", "Names and the clock turning light or dark with a new wallpaper."),
        r("home.pill-hide", "Search into dots", "The Search pill turning into the page dots while pages move."),
        r("home.pill-show", "Dots into Search", "The page dots turning back into the Search pill."),
        r("home.pill-edit", "Search in edit mode", "The Search pill becoming the page dots in edit mode, and back."),
        r("clock.tick", "Clock: the minute", "The clock's numerals changing at the minute."),
        r("clock.appear", "Clock appearing", "The clock's numerals fading in."),
        r("wallpaper.appear", "Wallpaper appearing", "The wallpaper fading in when it is read late."),
        r("wallpaper.crossfade", "Wallpaper crossfade", "A new wallpaper fading in where its reveal cannot be drawn."),
        r("wallpaper.reveal", "Wallpaper reveal", "A new wallpaper's reveal."),
        r("home.rebuild", "New look", "The new look spreading over home from where the change came from (another theme, a changed layout)."),
        r("home.rebuild-settle", "New look: items", "Each icon and widget settling into place as the new look reaches it."),
    ))

    val EDIT = MotionGroup("Editing home", "Moving icons and widgets, and the edit buttons.", listOf(
        r("edit.bar", "Edit bar", "The edit bar sliding in from the top and out."),
        r("edit.drag-lift", "Lifting", "An icon or widget lifting to be dragged."),
        r("edit.drag-settle", "Dropping", "A dragged item dropping into its place."),
        r("edit.reflow", "Making room", "Icons moving aside for a dragged one."),
        r("edit.jiggle", "Wiggle", "How far icons wiggle (degrees; widgets less)."),
        r("edit.jiggle-period", "Wiggle speed", "How long one wiggle takes (s)."),
        r("edit.jiggle-stop", "Wiggle stopping", "Icons coming to rest when editing ends."),
        r("edit.press-in", "Edit button pressed", "Edit mode's buttons as they are pressed."),
        r("edit.press-out", "Edit button let go", "And let go."),
        r("widget.resize", "Widget resizing", "A widget changing size."),
        r("widget.layout-fade", "Widget's new layout", "A widget's new layout fading in after a resize."),
    ))

    val SHEETS = MotionGroup("Menus, sheets and search", "Long-press menus, sheets, lists and search fields.", listOf(
        r("menu.open", "Menu opening", "The pressed item lifting, home blurring and the menu growing."),
        r("menu.close", "Menu closing", "The menu going and home coming back."),
        r("menu.blur", "Menu: blur behind", "How blurred home is behind a menu."),
        r("sheet", "Sheet", "A sheet coming up and going (the widget gallery)."),
        r("sheet.push", "Page in a sheet", "A page pushed inside a sheet."),
        r("settings.open", "Settings: a tile opening", "A tile of the settings growing into its page, and the page back into it."),
        r("mode-crossfade", "Results crossfade", "Tiles and the list, suggestions and results, crossfading."),
        r("index.scroll", "Index: scrolling", "The list gliding to a letter picked on the A-Z index."),
        r("index.bubble-in", "Index: letter in", "The letter bubble popping in."),
        r("index.bubble-out", "Index: letter out", "The letter bubble going."),
        r("index.follow", "Index: following", "The letter bubble following the finger."),
        r("field.clear-in", "Clear button in", "A search field's clear button popping in."),
        r("field.clear-out", "Clear button out", "A search field's clear button shrinking away."),
        r("widgets.row", "Gallery rows", "The widget gallery's rows arriving or moving."),
        r("widgets.row-stagger", "Gallery rows: between", "How long after one row the next arrives (ms)."),
        r("widgets.preview", "Gallery previews", "A widget preview fading in."),
    ))

    val SCROLL = MotionGroup("Scrolling", "How lists move under the finger and after it.", listOf(
        r("scroll.deceleration", "Slowing down", "How quickly a flung list slows (per ms; closer to 1 glides further)."),
        r("scroll.rubber-band", "Rubber band", "How stiff the edge feels when a list is pulled past its end."),
        r("scroll.overscroll-return", "Back from the edge", "A list pulled past its end going back."),
    ))

    val CONTROL_CENTER = MotionGroup("Control Center", "The controls panel.", listOf(
        r("cc.open", "Opening", "Control Center finishing its way in after a pull."),
        r("cc.close", "Closing", "Control Center going."),
        r("cc.close-style", "Closing choreography", "The controls' closing blending in and out."),
        r("cc.settle", "Settling", "The controls settling as it opens."),
        r("cc.stretch-back", "Stretch back", "The panel stretched by an overpull going back."),
        r("cc.press-in", "Control pressed", "A control as it is pressed."),
        r("cc.press-out", "Control let go", "A control springing back."),
        r("cc.toggle", "Turning on or off", "A control changing state."),
        r("cc.level", "Slider following", "A slider's level following the system's."),
        r("cc.expanded.open", "Module expanding", "A control growing into its expanded form."),
        r("cc.expanded.close", "Module closing", "An expanded control going back into its place."),
        r("cc.expanded.level", "Expanded slider", "The expanded slider's level."),
        r("cc.edit", "Editing", "Edit mode coming and going."),
        r("cc.reflow", "Making room", "Controls moving aside while editing."),
        r("cc.resize", "Resizing", "A control changing size."),
        r("cc.appear", "Control added", "A control arriving."),
        r("cc.leave", "Control removed", "A control leaving."),
        r("cc.lift", "Lifting", "A control lifted to be dragged."),
        r("cc.drop", "Dropping", "A dragged control dropping into its place."),
        r("cc.gallery.open", "Gallery opening", "The controls gallery coming up."),
        r("cc.gallery.close", "Gallery closing", "The controls gallery going."),
        r("cc.backdrop-hold", "Background holding", "The live background turning into a still picture before an app starts."),
    ))

    val NOTIFICATIONS = MotionGroup("Notifications", "Notification Center and its notifications.", listOf(
        r("nc.open", "Opening", "Notification Center coming down."),
        r("nc.close", "Closing", "Notification Center going."),
        r("nc.appear", "Notification arriving", "A notification arriving in the list."),
        r("nc.leave", "Notification leaving", "A notification leaving the list."),
        r("nc.reflow", "List moving", "Notifications moving to new places."),
        r("nc.swipe-back", "Swipe let go", "A swiped notification going back."),
        r("nc.swipe-out", "Swiped away", "A swiped notification going away."),
        r("nc.menu-open", "Long look opening", "A notification held: its long look opening."),
        r("nc.menu-close", "Long look closing", "The long look closing."),
        r("nc.confirm", "Clear turning", "A group's clear button turning into Clear."),
        r("nc.hold", "Lock screen buttons held", "The flashlight and camera buttons growing while held."),
        r("nc.toggle", "Flashlight lighting", "The flashlight button turning on or off."),
        r("nc.clock-tick", "Clock: the minute", "The lock screen clock's numerals changing at the minute."),
        r("nc.launch-fade", "Opening an app", "The card of an app opened from a notification fading into the app."),
        r("nc.time-fade", "Time changing", "A notification's time label changing to its new text."),
        r("press-in", "Notification pressed", "A notification or a button as it is pressed."),
        r("press-out", "Notification let go", "And let go."),
    ))

    val BANNERS = MotionGroup("Banners", "Notifications popping up over apps and home.", listOf(
        r("banner.in", "Banner in", "A banner swooping in from the top."),
        r("banner.out", "Banner out", "A banner going."),
        r("banner.fly", "Banner flung", "A banner thrown away sideways."),
        r("banner.fly-fade", "Banner fading as flung", "A thrown banner fading as it goes."),
        r("banner.back", "Banner let go", "A dragged banner going back."),
        r("banner.resize", "Banner resizing", "A banner changing height."),
        r("banner.app-fade", "Banner's glass", "A banner's glass taking the picture of the app behind it."),
    ))

    val BARS = MotionGroup("Status bar and gesture bar", "The bar at the top and the pill at the bottom.", listOf(
        r("statusbar.move", "Icon moving", "A status icon moving aside."),
        r("statusbar.appear", "Icon coming", "A status icon arriving."),
        r("statusbar.leave", "Icon going", "A status icon leaving."),
        r("statusbar.level", "Battery level", "The battery's level changing."),
        r("statusbar.roll", "Digit rolling", "A digit of the time rolling to the next."),
        r("statusbar.hide", "Bar hiding", "The bar fading when the app in front is full screen."),
        r("statusbar.show", "Bar showing", "The bar coming back."),
        r("statusbar.tone", "Bar's tone", "The bar's content turning dark or light."),
        r("statusbar.lock", "Time over the lock screen", "The time fading over the lock screen, and back."),
        r("strip.hide", "Gesture pill hiding", "The pill fading while a panel is open."),
        r("strip.show", "Gesture pill showing", "The pill coming back."),
    ))

    val APPEARANCE = MotionGroup("Light and dark", "Switching between light and dark.", listOf(
        r("appearance.change", "Light and dark", "Everything crossfading between light and dark."),
    ))

    val TESTING = MotionGroup("Testing", "For checking animations.", listOf(
        r("debug.slow", "Slow motion", "Every animation this many times slower (1: normal)."),
    ))

    val ALL = listOf(APPS, SWITCHER, HOME, EDIT, SHEETS, SCROLL, CONTROL_CENTER, NOTIFICATIONS, BANNERS, BARS, APPEARANCE, TESTING)

    fun find(key: String): Pair<MotionGroup, MotionRole>? {
        for (g in ALL) for (r in g.roles) if (r.key == key) return g to r
        return null
    }
}
