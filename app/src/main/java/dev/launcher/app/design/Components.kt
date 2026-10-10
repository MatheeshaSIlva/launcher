package dev.launcher.app.design

/**
 * The component catalogue (docs/PLAN_LAYOUTS_THEMES.md, B2b): the shared styles every layout reads, whichever design it
 * comes from, so a theme restyles all of them at once. Each property is a `component.*` token of the theme; the iOS 27
 * layouts' own tokens (`comp.nc.*`, `comp.cc.*`, `comp.home.*`...) are aliases of them. Below the components sit the
 * theme's shared styles (Figma's styles): materials `sys.material.*`, text `sys.type.*` / `ref.type.*`, colours
 * `sys.color.*`.
 *
 * Names and descriptions are what the settings app shows, in plain words.
 */
class ComponentProp(val key: String, val title: String, val description: String)

class ComponentSpec(val id: String, val title: String, val description: String, val props: List<ComponentProp>)

object Components {
    private fun p(c: String, k: String, title: String, description: String) = ComponentProp("component.$c.$k", title, description)

    val NOTIFICATION = ComponentSpec("notification", "Notification card", "A notification: in the list, and when it pops up.", listOf(
        p("notification", "material", "Surface", "What the card is made of (glass, frosted, solid)."),
        p("notification", "floating-material", "Surface when it pops up", "The card over an app or home, where it must stay readable."),
        p("notification", "corner", "Corner radius", "How round the card's corners are."),
        p("notification", "padding", "Padding", "Space between the card's edge and its content."),
        p("notification", "icon", "App icon size", "The size of the app's icon on the card."),
        p("notification", "title", "Title text", "The notification's title."),
        p("notification", "body", "Message text", "The notification's message."),
        p("notification", "time", "Time text", "When it arrived."),
        p("notification", "label-color", "Text colour", "The colour of the title and message."),
        p("notification", "time-color", "Time colour", "The colour of the time."),
        p("notification", "time-blend", "Time blending", "How the time mixes with the surface under it."),
        p("notification", "gap", "Space between cards", "The gap between two notifications in the list."),
    ))

    val CONTROL = ComponentSpec("control", "Control", "A quick setting: a toggle, a button or a module with its name and state.", listOf(
        p("control", "material", "Surface", "What a control is made of when it is off."),
        p("control", "on-material", "Surface when on", "What a control that has no colour of its own is made of when on."),
        p("control", "corner", "Corner radius", "How round a control's corners are."),
        p("control", "symbol", "Symbol size", "The size of a control's symbol."),
        p("control", "symbol-color", "Symbol colour", "A symbol on the control's surface."),
        p("control", "on-symbol-color", "Symbol colour when on", "A symbol on the light surface of a control that is on."),
        p("control", "accent-symbol-color", "Symbol colour on an accent", "A symbol on a control's accent colour when it is on."),
        p("control", "label-color", "Name colour", "The colour of a control's name."),
        p("control", "detail-color", "State colour", "The colour of a control's state (On, Off, a value)."),
        p("control", "detail-blend", "State blending", "How the state mixes with the surface under it."),
        p("control", "title", "Name text", "A control's name."),
        p("control", "title-large", "Large name text", "A large control's name."),
        p("control", "detail", "State text", "A control's state."),
        p("control", "chevron-color", "Arrow colour", "The small arrow that opens a control's choices."),
        p("control", "well-material", "Symbol circle surface", "The circle behind a symbol when it is off."),
    ))

    val SLIDER = ComponentSpec("slider", "Slider", "Brightness, volume and other levels.", listOf(
        p("slider", "corner", "Corner radius", "How round a slider's corners are."),
        p("slider", "cover", "Fill over the symbol", "How far the level's fill reaches over the symbol before it changes colour."),
        p("slider", "expanded-corner", "Corner radius when opened", "A slider opened to full size."),
    ))

    val ROUND_BUTTON = ComponentSpec("round-button", "Round button", "A circular button with a symbol (the lock screen's torch and camera).", listOf(
        p("round-button", "material", "Surface", "What the button is made of."),
        p("round-button", "size", "Size", "The button's diameter."),
        p("round-button", "symbol", "Symbol size", "The size of its symbol."),
        p("round-button", "symbol-color", "Symbol colour", "The colour of its symbol."),
        p("round-button", "symbol-blend", "Symbol blending", "How the symbol mixes with the surface under it."),
        p("round-button", "on-color", "Colour when on", "The button's colour when it is on."),
    ))

    val BUTTON = ComponentSpec("button", "Button", "A capsule with a label (a notification's actions).", listOf(
        p("button", "fill", "Fill", "The button's colour."),
        p("button", "height", "Height", "The button's height."),
        p("button", "text", "Label text", "The button's label."),
    ))

    val MENU = ComponentSpec("menu", "Menu", "The menu a long press opens.", listOf(
        p("menu", "material", "Surface", "What the menu is made of."),
        p("menu", "corner", "Corner radius", "How round the menu's corners are."),
        p("menu", "width", "Width", "The menu's width (wider for a long label)."),
        p("menu", "row", "Row height", "The height of one item."),
        p("menu", "pad-top", "Space above", "Space above the first item."),
        p("menu", "pad-bottom", "Space below", "Space below the last item."),
        p("menu", "symbol-x", "Symbol position", "Where an item's symbol sits from the menu's edge."),
        p("menu", "label-x", "Label position", "Where an item's label starts from the menu's edge."),
        p("menu", "type", "Item text", "An item's label."),
        p("menu", "label", "Item colour", "The colour of an item's label and symbol."),
        p("menu", "destructive", "Destructive colour", "Items that remove or delete."),
        p("menu", "press", "Pressed colour", "An item while it is pressed."),
        p("menu", "fallback", "Plain surface", "The menu's surface where glass cannot be drawn."),
        p("menu", "symbol", "Symbol size", "The size of an item's symbol."),
        p("menu", "grow-from", "Opening size", "How large the menu starts as it opens (a fraction of its size)."),
    ))

    val BADGE = ComponentSpec("badge", "Badge", "The count on an app's icon.", listOf(
        p("badge", "fill", "Fill", "The badge's colour."),
        p("badge", "height", "Height", "The badge's height."),
        p("badge", "label", "Number colour", "The colour of the count."),
        p("badge", "max", "Largest number", "Counts above it show as this number and a plus."),
        p("badge", "offset-x", "Sideways offset", "How far the badge sits out from the icon's corner."),
        p("badge", "offset-y", "Upward offset", "How far the badge sits up from the icon's corner."),
        p("badge", "pad-x", "Padding", "Space on each side of the count."),
        p("badge", "type", "Number text", "The count's text."),
    ))

    val REMOVE_BADGE = ComponentSpec("remove-badge", "Remove badge", "The minus on items while home is being edited.", listOf(
        p("remove-badge", "disc", "Fill", "The badge's colour."),
        p("remove-badge", "minus", "Minus colour", "The colour of the minus."),
        p("remove-badge", "radius", "Size", "The badge's radius."),
        p("remove-badge", "stroke", "Minus thickness", "How thick the minus is."),
    ))

    val FIELD = ComponentSpec("field", "Search field", "A field to type a search in.", listOf(
        p("field", "material", "Surface", "What the field is made of."),
        p("field", "clear-color", "Clear button colour", "The button that empties the field."),
        p("field", "clear-symbol-color", "Clear symbol colour", "The cross on that button."),
    ))

    val WALLPAPER_LABEL = ComponentSpec("wallpaper-label", "Names on the wallpaper", "App and widget names over the wallpaper.", listOf(
        p("wallpaper-label", "light", "Light colour", "Names over a dark wallpaper."),
        p("wallpaper-label", "dark", "Dark colour", "Names over a light wallpaper."),
        p("wallpaper-label", "dark-from", "Darker from", "How light the wallpaper must be before names start to darken."),
        p("wallpaper-label", "dark-full", "Fully dark at", "How light the wallpaper is when names are fully dark."),
        p("wallpaper-label", "shadow", "Shadow", "The soft shadow under light names."),
    ))

    val CARD = ComponentSpec("card", "Card", "Widgets, App Library tiles and search results.", listOf(
        p("card", "material", "Surface", "What a card is made of."),
    ))

    val DOCK = ComponentSpec("dock", "Dock", "The row of apps at the bottom of home.", listOf(
        p("dock", "material", "Surface", "What the dock is made of."),
    ))

    val SHEET = ComponentSpec("sheet", "Sheet", "A panel that slides up from the bottom (the widget gallery).", listOf(
        p("sheet", "material", "Surface", "What the sheet is made of."),
        p("sheet", "grabber-color", "Handle colour", "The small handle at its top."),
        p("sheet", "fallback-color", "Plain surface", "The sheet's surface where glass cannot be drawn."),
    ))

    val PANEL = ComponentSpec("panel", "Panel background", "What covers the screen behind the controls.", listOf(
        p("panel", "material", "Surface", "How the screen behind is dimmed or blurred."),
        p("panel", "samsung-strength", "Samsung blur strength", "The strength of One UI's own blur behind the panel."),
    ))

    val ALL = listOf(NOTIFICATION, CONTROL, SLIDER, ROUND_BUTTON, BUTTON, MENU, BADGE, REMOVE_BADGE, FIELD, WALLPAPER_LABEL, CARD, DOCK, SHEET, PANEL)

    fun find(key: String): Pair<ComponentSpec, ComponentProp>? {
        for (c in ALL) for (p in c.props) if (p.key == key) return c to p
        return null
    }
}
