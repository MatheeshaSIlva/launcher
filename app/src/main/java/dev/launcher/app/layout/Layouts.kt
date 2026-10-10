package dev.launcher.app.layout

/**
 * The main elements the user arranges (docs/PLAN_LAYOUTS_THEMES.md). Each has layouts to choose from, places it can live
 * and options; which ones are chosen is the user's [Setup]. Layouts are independent of the theme (how things look) and of
 * the animations (how they move): a theme never chooses a layout.
 */
enum class Element(val id: String, val title: String) {
    STATUS_BAR("status-bar", "Status bar"),
    SHADE("shade", "Notifications and controls"),
    RECENTS("recents", "Recent apps"),
    DRAWER("drawer", "App drawer"),
    HOME("home", "Home screen"),
    /** The launcher's own settings app: themed and laid out like the rest. */
    SETTINGS("settings", "Settings");

    companion object {
        fun of(id: String?): Element? = entries.firstOrNull { it.id == id }
    }
}

/** Where an element lives: how it is reached (a page beside home, a sheet from the bottom, a pull from the top...). */
class Placement(val id: String, val title: String, val description: String)

/**
 * One option of a layout, as the settings app will show it: a control with a name, a plain-words description, a default
 * and the values it accepts. [accept] turns a stored value into a valid one (null if it is not valid).
 */
sealed class Option(val key: String, val title: String, val description: String) {
    abstract val default: Any
    abstract fun accept(v: Any?): Any?

    class Toggle(key: String, title: String, description: String, override val default: Boolean) : Option(key, title, description) {
        override fun accept(v: Any?): Boolean? = when (v) {
            is Boolean -> v
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    class Count(key: String, title: String, description: String, override val default: Int, val min: Int, val max: Int) :
        Option(key, title, description) {
        override fun accept(v: Any?): Int? {
            val n = when (v) {
                is Int -> v
                is Long -> v.toInt()
                is Double -> if (v % 1.0 == 0.0) v.toInt() else return null
                is String -> v.toIntOrNull() ?: return null
                else -> return null
            }
            return n.takeIf { it in min..max }
        }
    }

    class Choice(key: String, title: String, description: String, override val default: String, val choices: List<Pair<String, String>>) :
        Option(key, title, description) {
        override fun accept(v: Any?): String? = (v as? String)?.takeIf { s -> choices.any { it.first == s } }
    }
}

/**
 * A layout of one [element]: its [id] (what the setup stores), its name, the design it comes from, what it is in plain
 * words, the places it can live (the first is its default) and its options.
 */
class LayoutSpec(
    val element: Element,
    val id: String,
    val title: String,
    val origin: String,
    val description: String,
    val placements: List<Placement>,
    val options: List<Option> = emptyList(),
) {
    init { require(placements.isNotEmpty()) { "layout $id has no placement" } }

    val defaultPlacement get() = placements.first()
    fun placement(id: String?): Placement? = placements.firstOrNull { it.id == id }
    fun option(key: String): Option? = options.firstOrNull { it.key == key }
}

/**
 * Every layout there is, per element (the first of an element is its default). For now today's iOS 27 elements, one
 * each; the element rounds of the plan (D1-D6) add the others.
 */
object Layouts {
    val IOS_STATUS_BAR = LayoutSpec(
        Element.STATUS_BAR, "ios", "iOS", "iOS 27",
        "The time on the left of the camera; signal, Wi-Fi and the battery on the right.",
        listOf(Placement("top", "Along the top", "Across the top of the screen, everywhere.")),
    )

    val IOS_SHADE = LayoutSpec(
        Element.SHADE, "ios", "iOS", "iOS 27",
        "Notification Center and Control Center as two panels.",
        listOf(Placement("split-top", "Pull from the top: notifications on the left, controls on the right",
            "Pull down from the top edge left of the camera for notifications, right of it for the controls.")),
    )

    val IOS_DECK = LayoutSpec(
        Element.RECENTS, "deck", "Card deck", "iOS 27",
        "Recent apps as a deck of cards, the newest on the right, older ones stacked to the left.",
        listOf(Placement("hold", "Hold during the home swipe", "Swipe up from the bottom edge and rest your finger.")),
    )

    /** The drawer's places (also [dev.launcher.app.home.DrawerPlacement]'s ids). */
    val DRAWER_PLACEMENTS = listOf(
        Placement("page-after-last", "A page after the last", "Swipe left past your last home page (iOS)."),
        Placement("page-before-first", "A page before the first", "Swipe right from your first home page."),
        Placement("swipe-up", "Swipe up", "Swipe up anywhere on home; it comes up as a sheet."),
    )

    val APP_LIBRARY = LayoutSpec(
        Element.DRAWER, "app-library", "App Library", "iOS 27",
        "Your apps sorted into categories, with a search field on top and an A-Z list.",
        DRAWER_PLACEMENTS,
    )

    val IOS_HOME = LayoutSpec(
        Element.HOME, "grid", "Icon grid", "iOS 27",
        "Pages of icons and widgets, a dock at the bottom.",
        listOf(Placement("home", "Home", "What the Home gesture and the Home key show.")),
        options = listOf(
            Option.Toggle("show-labels", "App names", "Names under the icons on your home pages.", true),
            Option.Toggle("show-widget-labels", "Widget names", "Names under the widgets on your home pages.", true),
            Option.Toggle("new-apps-on-home", "Add new apps to home", "Newly installed apps get an icon on your last page.", true),
        ),
    )

    val STUDIO = LayoutSpec(
        Element.SETTINGS, "studio", "Studio", "Launcher",
        "A live picture of your phone above a sheet of tiles; tap a part of the phone to change just that part.",
        listOf(Placement("app", "An app", "Opened from the drawer, home's edit menu and a Control Center control.")),
    )

    val ALL: List<LayoutSpec> = listOf(IOS_STATUS_BAR, IOS_SHADE, IOS_DECK, APP_LIBRARY, IOS_HOME, STUDIO)

    fun of(e: Element): List<LayoutSpec> = ALL.filter { it.element == e }
    fun find(e: Element, id: String?): LayoutSpec? = ALL.firstOrNull { it.element == e && it.id == id }
    fun default(e: Element): LayoutSpec = of(e).first()
}
