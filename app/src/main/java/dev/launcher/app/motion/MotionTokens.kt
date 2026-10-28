package dev.launcher.app.motion

import dev.launcher.app.design.NumberKey
import dev.launcher.app.design.CurveKey

/** Every animation's tokens by role (`motion.*`: the animation preset's, `assets/motion/`; [MotionProfile] reads them). */
object MotionTokens {
    val APP_OPEN = CurveKey("motion.app.open")
    val APP_CLOSE_POSITION = CurveKey("motion.app.close-position")
    val APP_CLOSE_SIZE = CurveKey("motion.app.close-size")
    val APP_CANCEL = CurveKey("motion.app.cancel")
    val HOME_DEPTH_OPEN = CurveKey("motion.home.depth-open")
    val HOME_DEPTH_CLOSE = CurveKey("motion.home.depth-close")
    val SWITCH_COMMIT = CurveKey("motion.switch.commit")
    val SWITCH_CANCEL = CurveKey("motion.switch.cancel")
    val PAGE_SNAP = CurveKey("motion.home.page-snap")
    val DRAWER = CurveKey("motion.drawer")
    val FOLDER_OPEN = CurveKey("motion.folder.open")
    val FOLDER_CLOSE = CurveKey("motion.folder.close")
    val MODE_CROSSFADE = CurveKey("motion.mode-crossfade")
    val INDEX_SCROLL = CurveKey("motion.index.scroll")
    val INDEX_BUBBLE_IN = CurveKey("motion.index.bubble-in")
    val INDEX_BUBBLE_OUT = CurveKey("motion.index.bubble-out")
    val INDEX_FOLLOW = CurveKey("motion.index.follow")
    val OVERSCROLL_RETURN = CurveKey("motion.scroll.overscroll-return")
    val RUBBER_BAND = NumberKey("motion.scroll.rubber-band")
    val DECELERATION_RATE = NumberKey("motion.scroll.deceleration")
    val PAGE_FLING_DP = NumberKey("motion.home.page-fling")
    val ICON_PRESS_DIM = NumberKey("motion.icon.press-dim")
    val ICON_PRESS_IN = CurveKey("motion.icon.press-in")
    val ICON_PRESS_OUT = CurveKey("motion.icon.press-out")
    val HOME_CONTENT_ZOOM = NumberKey("motion.home.content-zoom")
    val HOME_WALLPAPER_ZOOM = NumberKey("motion.home.wallpaper-zoom")
    val HOME_DEPTH_BLUR = NumberKey("motion.home.depth-blur")
    val MENU_OPEN = CurveKey("motion.menu.open")
    val MENU_CLOSE = CurveKey("motion.menu.close")
    val MENU_BLUR = NumberKey("motion.menu.blur")
    val ALERT_OPEN = CurveKey("motion.alert.open")
    val ALERT_CLOSE = CurveKey("motion.alert.close")
    val REFLOW = CurveKey("motion.edit.reflow")
    val DRAG_LIFT = CurveKey("motion.edit.drag-lift")
    val DRAG_SETTLE = CurveKey("motion.edit.drag-settle")
    val JIGGLE_DEGREES = NumberKey("motion.edit.jiggle")
    val JIGGLE_PERIOD = NumberKey("motion.edit.jiggle-period")
    val SHEET = CurveKey("motion.sheet")
    val NAV_PUSH = CurveKey("motion.sheet.push")
    val SETTINGS_OPEN = CurveKey("motion.settings.open")
    val SETTINGS_FOCUS = CurveKey("motion.settings.focus")
    val SETTINGS_HINT = CurveKey("motion.settings.hint")
    val WIDGET_RESIZE = CurveKey("motion.widget.resize")
    val APPEAR = CurveKey("motion.appear")
    val APPEAR_FADE = CurveKey("motion.appear.fade")
    val DISAPPEAR_FADE = CurveKey("motion.disappear.fade")
    val EDIT_BAR = CurveKey("motion.edit.bar")
    val ARRIVAL = CurveKey("motion.arrival")
    val ARRIVAL_WALLPAPER = CurveKey("motion.arrival.wallpaper")
    val ARRIVAL_ZOOM = NumberKey("motion.arrival.zoom")
    val ARRIVAL_WALLPAPER_ZOOM = NumberKey("motion.arrival.wallpaper-zoom")
    val CLOCK_TICK = CurveKey("motion.clock.tick")
    val SWITCHER_CARD_SCALE = NumberKey("motion.switcher.card-scale")
    val SWITCHER_CARD_CENTER_Y = NumberKey("motion.switcher.card-center-y")
    val SWITCHER_FOCUS_LEFT = NumberKey("motion.switcher.focus-left")
    val SWITCHER_HOLD_MS = NumberKey("motion.switcher.hold")
    val SWITCHER_HOLD_MIN_TRAVEL_DP = NumberKey("motion.switcher.hold-travel")
    val SWITCHER_ENTER = CurveKey("motion.switcher.enter")
    val SWITCHER_SCROLL = CurveKey("motion.switcher.scroll")
    val SWITCHER_FLING_PROJECTION = NumberKey("motion.switcher.fling-projection")
    val SWITCHER_OPEN = CurveKey("motion.switcher.open")
    val SWITCHER_HOME = CurveKey("motion.switcher.home")
    val SWITCHER_FLICK = CurveKey("motion.switcher.flick")
    val SWITCHER_FLICK_SPEED_DP = NumberKey("motion.switcher.flick-speed")
    val SWITCHER_REFLOW = CurveKey("motion.switcher.reflow")

    // The shade's, Control Center's, the status bar's and edit mode's (they were in code).
    val CC_CLOSE_STYLE = CurveKey("motion.cc.close-style")
    val CC_PRESS_IN = CurveKey("motion.cc.press-in")
    val CC_PRESS_OUT = CurveKey("motion.cc.press-out")
    val CC_TOGGLE = CurveKey("motion.cc.toggle")
    val CC_LEVEL = CurveKey("motion.cc.level")
    val CC_EDIT = CurveKey("motion.cc.edit")
    val CC_REFLOW = CurveKey("motion.cc.reflow")
    val CC_RESIZE = CurveKey("motion.cc.resize")
    val CC_APPEAR = CurveKey("motion.cc.appear")
    val CC_LEAVE = CurveKey("motion.cc.leave")
    val CC_LIFT = CurveKey("motion.cc.lift")
    val CC_DROP = CurveKey("motion.cc.drop")
    val CC_STRETCH_BACK = CurveKey("motion.cc.stretch-back")
    val CC_EXPANDED_OPEN = CurveKey("motion.cc.expanded.open")
    val CC_EXPANDED_CLOSE = CurveKey("motion.cc.expanded.close")
    val CC_EXPANDED_LEVEL = CurveKey("motion.cc.expanded.level")
    val CC_GALLERY_OPEN = CurveKey("motion.cc.gallery.open")
    val CC_GALLERY_CLOSE = CurveKey("motion.cc.gallery.close")
    val NC_REFLOW = CurveKey("motion.nc.reflow")
    val NC_APPEAR = CurveKey("motion.nc.appear")
    val NC_LEAVE = CurveKey("motion.nc.leave")
    val NC_SWIPE_BACK = CurveKey("motion.nc.swipe-back")
    val NC_SWIPE_OUT = CurveKey("motion.nc.swipe-out")
    val PRESS_IN = CurveKey("motion.press-in")
    val PRESS_OUT = CurveKey("motion.press-out")
    val NC_HOLD = CurveKey("motion.nc.hold")
    val NC_TOGGLE = CurveKey("motion.nc.toggle")
    val NC_CONFIRM = CurveKey("motion.nc.confirm")
    val NC_MENU_OPEN = CurveKey("motion.nc.menu-open")
    val NC_MENU_CLOSE = CurveKey("motion.nc.menu-close")
    val NC_CLOCK_TICK = CurveKey("motion.nc.clock-tick")
    val BANNER_IN = CurveKey("motion.banner.in")
    val BANNER_OUT = CurveKey("motion.banner.out")
    val BANNER_FLY = CurveKey("motion.banner.fly")
    val BANNER_BACK = CurveKey("motion.banner.back")
    val BANNER_RESIZE = CurveKey("motion.banner.resize")
    val CC_CLOSE = CurveKey("motion.cc.close")
    val CC_SETTLE = CurveKey("motion.cc.settle")
    val NC_OPEN = CurveKey("motion.nc.open")
    val NC_CLOSE = CurveKey("motion.nc.close")
    val STATUSBAR_MOVE = CurveKey("motion.statusbar.move")
    val STATUSBAR_APPEAR = CurveKey("motion.statusbar.appear")
    val STATUSBAR_LEAVE = CurveKey("motion.statusbar.leave")
    val STATUSBAR_LEVEL = CurveKey("motion.statusbar.level")
    val STATUSBAR_ROLL = CurveKey("motion.statusbar.roll")
    val EDIT_PRESS_IN = CurveKey("motion.edit.press-in")
    val EDIT_PRESS_OUT = CurveKey("motion.edit.press-out")
    val SWITCHER_CLEAR_PRESS = CurveKey("motion.switcher.clear-press")
    val HOME_LABEL_TONE = CurveKey("motion.home.label-tone")
    val BANNER_FLY_FADE = CurveKey("motion.banner.fly-fade")
    /** Control Center finishing its way in after a pull (was `comp.cc.motion.open`). */
    val CC_OPEN = CurveKey("motion.cc.open")

    // Android's shade (a layout's `shade` panel: Pixel's), read from Android 17's SystemUIGoogle.
    /** The shade coming to rest open after a pull, and going (stopped at their places: no overshoot shows). */
    val QS_OPEN = CurveKey("motion.qs.open")
    val QS_CLOSE = CurveKey("motion.qs.close")
    /** Quick Settings coming to rest whole after the second pull, and back to the quick row over the notifications. */
    val QS_EXPAND = CurveKey("motion.qs.expand")
    val QS_COLLAPSE = CurveKey("motion.qs.collapse")
    /** A pressed tile growing into its neighbours, and back. */
    val QS_TILE_BOUNCE = CurveKey("motion.qs.tile-bounce")
    /** A tile's colours and corners following its state. */
    val QS_TILE_STATE = CurveKey("motion.qs.tile-state")
    /**
     * A tile's symbol playing its animation as the tile turns on or off (B-036). A curve with a length: the length over 500 ms
     * (Android's own pace) stretches every symbol's whole animation, the curve reshapes how far along it is (a straight line
     * keeps each symbol's own easing). See [SymbolTime].
     */
    val QS_TILE_SYMBOL = CurveKey("motion.qs.tile-symbol")
    /** A tapped tile's symbol (or words) popping out, and back. */
    val QS_TILE_POP = CurveKey("motion.qs.tile-pop")
    val QS_TILE_POP_BACK = CurveKey("motion.qs.tile-pop-back")
    /** Material's press ripple: growing from the finger over its target, coming in, and fading out after the release. */
    val QS_RIPPLE = CurveKey("motion.qs.ripple")
    val QS_RIPPLE_IN = CurveKey("motion.qs.ripple-in")
    val QS_RIPPLE_OUT = CurveKey("motion.qs.ripple-out")
    /** The brightness slider's symbol moving between its tracks. */
    val QS_SLIDER_ICON = CurveKey("motion.qs.slider-icon")
    /** Quick Settings giving way to their edit page (and back): out over the first half, the page in over the second. */
    val QS_EDIT = CurveKey("motion.qs.edit")
    /** Tiles moving to their places on the edit page (rearranged, resized, added, taken out). */
    val QS_EDIT_PLACE = CurveKey("motion.qs.edit-place")
    /** The edit page's undo, a tile added or taken out, Reset's question: fading in and out. */
    val QS_EDIT_APPEAR = CurveKey("motion.qs.edit-appear")
    /** The media player's play button changing shape and its wave calming or coming back, with play and pause. */
    val QS_MEDIA = CurveKey("motion.qs.media")
    /** Quick Settings' pages settling after a sideways swipe. */
    val QS_PAGE = CurveKey("motion.qs.page")
    /** The media player's card settling aside over its settings button, or back. */
    val QS_MEDIA_REVEAL = CurveKey("motion.qs.media-reveal")

    // Animations that were timed in code until B3b (each with the curve it had).
    val CLOCK_APPEAR = CurveKey("motion.clock.appear")
    val CARD_COLOR = CurveKey("motion.card.color")
    val CARD_SNAPSHOT = CurveKey("motion.card.snapshot")
    val CARD_CATCH_UP = CurveKey("motion.card.catch-up")
    val CARD_FADE_HOME = CurveKey("motion.card.fade-home")
    val CARD_FADE_APP = CurveKey("motion.card.fade-app")
    val STRIP_HIDE = CurveKey("motion.strip.hide")
    val STRIP_SHOW = CurveKey("motion.strip.show")
    val JIGGLE_STOP = CurveKey("motion.edit.jiggle-stop")
    val PILL_HIDE = CurveKey("motion.home.pill-hide")
    val PILL_SHOW = CurveKey("motion.home.pill-show")
    val PILL_EDIT = CurveKey("motion.home.pill-edit")
    val FIELD_CLEAR_IN = CurveKey("motion.field.clear-in")
    val FIELD_CLEAR_OUT = CurveKey("motion.field.clear-out")
    val WALLPAPER_APPEAR = CurveKey("motion.wallpaper.appear")
    val WALLPAPER_CROSSFADE = CurveKey("motion.wallpaper.crossfade")
    val WALLPAPER_REVEAL = CurveKey("motion.wallpaper.reveal")
    val WIDGET_LAYOUT_FADE = CurveKey("motion.widget.layout-fade")
    val CC_BACKDROP_HOLD = CurveKey("motion.cc.backdrop-hold")
    val NC_LAUNCH_FADE = CurveKey("motion.nc.launch-fade")
    val STATUSBAR_HIDE = CurveKey("motion.statusbar.hide")
    val STATUSBAR_SHOW = CurveKey("motion.statusbar.show")
    val STATUSBAR_TONE = CurveKey("motion.statusbar.tone")
    val STATUSBAR_LOCK = CurveKey("motion.statusbar.lock")
    /** The charging moment (One UI's charging chip covered): the capsule growing out of the battery, its outline led. */
    val STATUSBAR_CHARGE_OPEN = CurveKey("motion.statusbar.charge-open")
    /** The battery, its bolt and its number moving into the capsule (the number sliding out of the battery). */
    val STATUSBAR_CHARGE_CONTENT = CurveKey("motion.statusbar.charge-content")
    /** The capsule folding back into the battery, what it holds first. */
    val STATUSBAR_CHARGE_CLOSE = CurveKey("motion.statusbar.charge-close")
    /** One cycle of the light sweeping through the battery's charge while the capsule shows. */
    val STATUSBAR_CHARGE_SHIMMER = CurveKey("motion.statusbar.charge-shimmer")
    val APPEARANCE_CHANGE = CurveKey("motion.appearance.change")
    val WIDGETS_ROW = CurveKey("motion.widgets.row")
    val WIDGETS_PREVIEW = CurveKey("motion.widgets.preview")
    val BANNER_APP_FADE = CurveKey("motion.banner.app-fade")
    val NC_TIME_FADE = CurveKey("motion.nc.time-fade")
    // Notification Center pulled as a clear slab (B-037, D-035).
    /** The dim below the sheet coming in as a pull starts over home (fast) and over an app (slow), and clearing when it falls back. */
    val NC_PULL_DIM_HOME = CurveKey("motion.nc.pull-dim-home")
    val NC_PULL_DIM_APP = CurveKey("motion.nc.pull-dim-app")
    val NC_PULL_UNDIM = CurveKey("motion.nc.pull-undim")
    /** The sheet's own content arriving once a release commits. */
    val NC_REVEAL = CurveKey("motion.nc.reveal")
    /** The lens bands taking hold when the picture they bend arrives after the pull began. */
    val NC_LENS_IN = CurveKey("motion.nc.lens-in")
    /** The status bar fading out as a pull over home starts, and back in on commit. */
    val NC_BAR_FADE = CurveKey("motion.nc.bar-fade")
    /** The glass numerals dropping into place on a commit, and the date coming in as they land. */
    val NC_CLOCK_DROP = CurveKey("motion.nc.clock-drop")
    val NC_DATE_IN = CurveKey("motion.nc.date-in")
    val ARRIVAL_HOLD = CurveKey("motion.arrival.hold")
    /** Home built again in front of the user (another theme, an edit, the setup): the old look fading into the new. */
    val HOME_REBUILD = CurveKey("motion.home.rebuild")
    /** And each item settling into place as the new look's front passes it. */
    val HOME_REBUILD_SETTLE = CurveKey("motion.home.rebuild-settle")
    val WIDGETS_ROW_STAGGER = NumberKey("motion.widgets.row-stagger")
    val SWITCHER_CLEAR_STAGGER = NumberKey("motion.switcher.clear-stagger")
    val SWITCHER_CLEAR_HOME = NumberKey("motion.switcher.clear-home")
    /** How long the status bar's charging moment shows when there is no One UI charging animation to cover (ms). */
    val STATUSBAR_CHARGE_HOLD = NumberKey("motion.statusbar.charge-hold")
    /** Slow motion for checking animations (1 = normal). */
    val DEBUG_SLOW = NumberKey("motion.debug.slow")
    /** The whole preset faster or slower, bouncier or calmer ([MotionAdjust]; 1 = the preset's own). */
    val ADJUST_SPEED = NumberKey("motion.adjust.speed")
    val ADJUST_BOUNCE = NumberKey("motion.adjust.bounce")

    val CURVES = listOf(CC_CLOSE_STYLE, CC_PRESS_IN, CC_PRESS_OUT, CC_TOGGLE, CC_LEVEL, CC_EDIT, CC_REFLOW, CC_RESIZE, CC_APPEAR, CC_LEAVE, CC_LIFT, CC_DROP, CC_STRETCH_BACK, CC_EXPANDED_OPEN, CC_EXPANDED_CLOSE, CC_EXPANDED_LEVEL, CC_GALLERY_OPEN, CC_GALLERY_CLOSE, NC_REFLOW, NC_APPEAR, NC_LEAVE, NC_SWIPE_BACK, NC_SWIPE_OUT, PRESS_IN, PRESS_OUT, NC_HOLD, NC_TOGGLE, NC_CONFIRM, NC_MENU_OPEN, NC_MENU_CLOSE, NC_CLOCK_TICK, BANNER_IN, BANNER_OUT, BANNER_FLY, BANNER_BACK, BANNER_RESIZE, CC_CLOSE, CC_SETTLE, NC_OPEN, NC_CLOSE, STATUSBAR_MOVE, STATUSBAR_APPEAR, STATUSBAR_LEAVE, STATUSBAR_LEVEL, STATUSBAR_ROLL, EDIT_PRESS_IN, EDIT_PRESS_OUT, SWITCHER_CLEAR_PRESS, HOME_LABEL_TONE, BANNER_FLY_FADE, APP_OPEN, APP_CLOSE_POSITION, APP_CLOSE_SIZE, APP_CANCEL, HOME_DEPTH_OPEN, HOME_DEPTH_CLOSE, SWITCH_COMMIT, SWITCH_CANCEL, PAGE_SNAP, DRAWER, FOLDER_OPEN, FOLDER_CLOSE, INDEX_SCROLL, INDEX_BUBBLE_IN, INDEX_BUBBLE_OUT, INDEX_FOLLOW, OVERSCROLL_RETURN, MENU_OPEN, MENU_CLOSE, ALERT_OPEN, ALERT_CLOSE, REFLOW, DRAG_LIFT, DRAG_SETTLE, SHEET, NAV_PUSH, SETTINGS_OPEN, SETTINGS_FOCUS, SETTINGS_HINT, WIDGET_RESIZE, APPEAR, EDIT_BAR, ARRIVAL, ARRIVAL_WALLPAPER, SWITCHER_ENTER, SWITCHER_SCROLL, SWITCHER_OPEN, SWITCHER_HOME, SWITCHER_FLICK, SWITCHER_REFLOW, CC_OPEN, MODE_CROSSFADE, ICON_PRESS_IN, ICON_PRESS_OUT, APPEAR_FADE, DISAPPEAR_FADE, CLOCK_TICK, CLOCK_APPEAR, CARD_COLOR, CARD_SNAPSHOT, CARD_CATCH_UP, CARD_FADE_HOME, CARD_FADE_APP, STRIP_HIDE, STRIP_SHOW, JIGGLE_STOP, PILL_HIDE, PILL_SHOW, PILL_EDIT, FIELD_CLEAR_IN, FIELD_CLEAR_OUT, WALLPAPER_APPEAR, WALLPAPER_CROSSFADE, WALLPAPER_REVEAL, WIDGET_LAYOUT_FADE, CC_BACKDROP_HOLD, NC_LAUNCH_FADE, STATUSBAR_HIDE, STATUSBAR_SHOW, STATUSBAR_TONE, STATUSBAR_LOCK, STATUSBAR_CHARGE_OPEN, STATUSBAR_CHARGE_CONTENT, STATUSBAR_CHARGE_CLOSE, STATUSBAR_CHARGE_SHIMMER, APPEARANCE_CHANGE, WIDGETS_ROW, WIDGETS_PREVIEW, BANNER_APP_FADE, NC_TIME_FADE, NC_PULL_DIM_HOME, NC_PULL_DIM_APP, NC_PULL_UNDIM, NC_REVEAL, NC_LENS_IN, NC_BAR_FADE, NC_CLOCK_DROP, NC_DATE_IN, ARRIVAL_HOLD, HOME_REBUILD, HOME_REBUILD_SETTLE, QS_OPEN, QS_CLOSE, QS_EXPAND, QS_COLLAPSE, QS_TILE_BOUNCE, QS_TILE_STATE, QS_TILE_SYMBOL, QS_TILE_POP, QS_TILE_POP_BACK, QS_RIPPLE, QS_RIPPLE_IN, QS_RIPPLE_OUT, QS_SLIDER_ICON, QS_EDIT, QS_EDIT_PLACE, QS_EDIT_APPEAR, QS_MEDIA, QS_PAGE, QS_MEDIA_REVEAL).map { it.name }
    val NUMBERS = listOf(DEBUG_SLOW, ADJUST_SPEED, ADJUST_BOUNCE, RUBBER_BAND, DECELERATION_RATE, PAGE_FLING_DP, ICON_PRESS_DIM, HOME_CONTENT_ZOOM, HOME_WALLPAPER_ZOOM, HOME_DEPTH_BLUR, MENU_BLUR, JIGGLE_DEGREES, JIGGLE_PERIOD, ARRIVAL_ZOOM, ARRIVAL_WALLPAPER_ZOOM, WIDGETS_ROW_STAGGER, SWITCHER_CLEAR_STAGGER, SWITCHER_CLEAR_HOME, SWITCHER_CARD_SCALE, SWITCHER_CARD_CENTER_Y, SWITCHER_FOCUS_LEFT, SWITCHER_HOLD_MS, SWITCHER_HOLD_MIN_TRAVEL_DP, SWITCHER_FLING_PROJECTION, SWITCHER_FLICK_SPEED_DP, STATUSBAR_CHARGE_HOLD).map { it.name }
}
