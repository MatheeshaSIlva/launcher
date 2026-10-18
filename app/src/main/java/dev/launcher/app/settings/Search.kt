package dev.launcher.app.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import dev.launcher.app.layout.Element
import dev.launcher.app.layout.Layouts
import dev.launcher.app.layout.Option
import dev.launcher.app.layout.Setup
import dev.launcher.app.motion.Moment
import dev.launcher.app.motion.MomentPart
import dev.launcher.app.motion.Moments
import dev.launcher.app.motion.MotionRoles
import dev.launcher.app.motion.MotionTokens
import kotlinx.coroutines.flow.first

// Search across every setting (docs/PLAN_SETTINGS_V2.md, V3): each is indexed with its words and a few more ("icons" finds
// the grid, "bounce" every Bounce); a result names its place ("Home › Grid") and opens its page with the row lit.

/**
 * One setting as the search finds it: its name, where it is in words, more words it answers to ([words]; [about]: what it
 * does, counting less), and where it lives (a part's page, a page pushed over it, the row's name there, a moment whose
 * Every detail it is in, a fold it waits in: [foldId]). [rank]: settings first (0), then moments (1), then single animations (2).
 */
internal class SearchEntry(
    val title: String, val place: String, val words: String, val part: Part,
    val pushed: Pushed? = null, val anchor: String = title, val fallback: String? = null, val detail: Moment? = null,
    val about: String = "", val rank: Int = 0, val fold: String? = null,
) {
    /** Unique among the entries (the results' keys). */
    val id: String = part.name + "/" + (pushed?.key ?: "") + "/" + anchor + "/" + title
}

/** The part a moment's page is pushed over: the part it belongs to, else Motion. */
internal fun hostOf(m: Moment): Part = when (m.part) {
    MomentPart.APPS -> Part.APPS
    MomentPart.HOME -> Part.HOME
    MomentPart.LIBRARY -> Part.LIBRARY
    MomentPart.SHADE -> Part.SHADE
    MomentPart.STATUS_BAR -> Part.STATUS_BAR
    MomentPart.EVERYWHERE -> Part.MOTION
}

/** The name an animation's row registers under (Every detail): its key, so a slider of the same name is another row. */
internal fun roleAnchor(key: String) = "role:$key"

/** Every setting there is to find ([developer]: the testing tools too, while they are turned on). Each once. */
internal fun settingsIndex(draft: Draft, developer: Boolean): List<SearchEntry> = settingsEntries(draft, developer).distinctBy { it.id }

private fun settingsEntries(draft: Draft, developer: Boolean): List<SearchEntry> = buildList {
    fun add(title: String, place: String, words: String, part: Part, pushed: Pushed? = null, anchor: String = title,
            fallback: String? = null, detail: Moment? = null, about: String = "", rank: Int = 0, fold: String? = null) =
        add(SearchEntry(title, place, words, part, pushed, anchor, fallback, detail, about, rank, fold))

    // Look: the theme, light and dark and the colours in sight, the rest in its More and its Advanced.
    val more = foldId(Part.LOOK, "more")
    val advanced = foldId(Part.LOOK, "advanced")
    add("Theme", "Look", "theme themes look style skin design ios graphite appearance", Part.LOOK)
    add("Colours", "Look", "colors colours color colour material you wallpaper palette accent tint dynamic theme's own", Part.LOOK)
    add("Your colour", "Look › Colours", "your own custom color colour seed accent pick", Part.LOOK, fallback = "Colours")
    add("Light & dark", "Look", "light dark mode night appearance automatic black white", Part.LOOK)
    add("More", "Look", "more fonts size settings app theme", Part.LOOK)
    add("Fonts", "Look › More", "fonts font typeface typography text titles inter google", Part.LOOK, fold = more)
    add("Size", "Look › More", "size zoom scale display bigger smaller large small text size", Part.LOOK, fold = more)
    if (offersOwnTheme(draft, dev.launcher.app.layout.Element.SETTINGS)) {
        add(SETTINGS_THEME, "Look › More", "settings app own theme this these", Part.LOOK, fold = more)
    }
    add("Advanced", "Look", "advanced adjust colors colours style intensity palette primary secondary tertiary key", Part.LOOK)
    add("Style", "Look › Advanced", "style tonal vibrant expressive neutral monochrome material you colors colours", Part.LOOK, fold = advanced)
    add("Intensity", "Look › Advanced", "intensity colourful colorful saturation chroma vivid grey gray", Part.LOOK, fold = advanced)
    add("The palette's colours", "Look › Advanced", "palette primary secondary tertiary surface neutral key colors colours own",
        Part.LOOK, anchor = "Palette", fold = advanced)
    add("Text", "Look › Fonts", "text font body typeface", Part.LOOK, Sub.FONTS)
    add("Titles", "Look › Fonts", "titles headings display font typeface", Part.LOOK, Sub.FONTS)
    add("Follow the phone's display and font size", "Look › Size", "follow phone display size font size adaptive", Part.LOOK, Sub.SIZE)
    add("Everything", "Look › Size", "size zoom scale everything bigger smaller", Part.LOOK, Sub.SIZE)
    add("Text size", "Look › Size", "text font size bigger smaller", Part.LOOK, Sub.SIZE, anchor = "Text")

    // Motion: each part's moments folded on its page (the moments everywhere on Motion's).
    add("Presets", "Motion", "presets preset animation animations motion ios material style feel", Part.MOTION)
    add("Speed", "Motion › Feel", "speed fast faster slow slower quick snappy relaxed animation animations duration feel", Part.MOTION)
    add("Bounce", "Motion › Feel", "bounce bouncy spring springy overshoot lively still feel", Part.MOTION)
    for (m in Moments.ALL) {
        val host = hostOf(m)
        val page = MomentPage(m, host)
        val at = host.title + " › " + m.title
        val everywhere = m.part == MomentPart.EVERYWHERE
        add(m.title, host.title + (if (everywhere) " › Everywhere" else " › Motion"), "motion animation animations moment", host,
            about = m.description, rank = 1, fold = if (everywhere) foldId(Part.MOTION, m.part.name) else foldId(host, "motion"))
        // (A moment's own settings rank with the moments; they answer to its name through their place.)
        add("Speed", at, "speed fast faster slow slower quick snappy relaxed", host, page, rank = 1)
        add("Bounce", at, "bounce bouncy spring overshoot lively still", host, page, rank = 1)
        // How apps open and close: answers to every style's name (a setting: before the moments).
        m.style?.let { k ->
            val closing = k == dev.launcher.app.motion.AppStyles.CLOSING
            val names = dev.launcher.app.motion.AppStyle.entries.joinToString(" ") { (if (closing) it.closing else it.opening) + " " + it.id }
            add("Style", at, "style styles transition transitions effect $names", host, page, rank = 0)
            // Each style by its name, found in that group.
            for (s in dev.launcher.app.motion.AppStyle.entries) {
                add(if (closing) s.closing else s.opening, "$at › Style", "style transition effect ${s.id}", host, page, anchor = "Style", about = s.about, rank = 0)
            }
        }
        m.character?.let { c -> add(c.title, at, "${c.low} ${c.high}", host, page, rank = 1) }
        add("Advanced", at, "advanced every detail each animation animations springs curves", host, page, rank = 1)
        for (k in m.roles) {
            val r = MotionRoles.find(k)?.second ?: continue
            // (An animation's row is found by its key: a moment's slider may share its name, Wiggle.)
            add(r.title, "$at › Advanced", "animation spring curve", host, page, anchor = roleAnchor(k), detail = m, about = r.description, rank = 2)
        }
    }

    // The parts of the phone.
    for (p in Part.entries) {
        val e = p.element ?: continue
        val l = Layouts.find(e, draft.layout(e)) ?: Setup.layout(e)
        add("Layout", p.title, "layout style design ${l.title} ${l.origin}", p, about = l.description)
        for (pl in l.placements) add(pl.title, p.title + " › Where it lives", "where lives place gesture swipe pull", p,
            fallback = "Where it lives", about = pl.description)
        for (o in l.options) {
            val section = if (o is Option.Count) "Grid" else "Options"
            add(o.title, p.title + " › " + section, synonyms(e, o.key), p, fallback = section, about = o.description)
        }
        if (offersOwnTheme(draft, e)) add("A theme of its own", p.title + " › Look", "theme own look follows", p, anchor = "Look")
    }

    // System (Phone until 2026-10-19: it still answers to the word).
    add("Shizuku", "System", "shizuku adb wireless debugging connect permission status system phone", Part.PHONE)
    add("Check for updates", "System › Updates", "updates update upgrade new version download install system phone", Part.PHONE)
    add("Restore system", "System › Safety", "restore safety status bar missing animations broken fix phone", Part.PHONE)
    add("Choose a different home app", "System › Safety", "default home launcher app change system phone", Part.PHONE)
    add("Build", "System › About", "about version build number developer system phone", Part.PHONE)
    if (developer) {
        add("Developer tools", "System › Developer", "developer tools tests log debug", Part.PHONE)
        add("Every value of the theme", "System › Developer", "token editor tokens expert values theme", Part.PHONE)
        val dev = Sub.DEVELOPER
        for ((t, w) in listOf(
            "Gesture navigation" to "gestures navigation strip cards", "Our status bar" to "status bar stock hide",
            "Test animation presets" to "test presets eased", "Slow animations" to "slow motion animations frame",
            "Frame report" to "frames jank report", "Status bar test" to "status bar test full screen landscape",
            "Mini player test" to "picture in picture pip mini player", "Watchdog status" to "watchdog heartbeat",
            "Shell identity" to "shell uid shizuku", "Diagnose Shizuku restarts" to "shizuku restarts log",
            "Probe: live cards" to "probe live cards", "Test: break the system" to "break test status bar animations",
            "Test: stop the shell service" to "stop shell service", "Reset home layout" to "reset home layout pages",
            "Animation scales" to "animation scales developer options", "Install the newest again" to "install update again",
            "Copy log" to "copy log clipboard",
        )) add(t, "System › Developer", w, Part.PHONE, dev)
    }
}

/** Words a count or a switch also answers to. */
private fun synonyms(e: Element, key: String): String = when {
    e == Element.HOME && key == "columns" -> "icons grid across columns"
    e == Element.HOME && key == "rows" -> "icons grid down rows"
    e == Element.HOME && key == "dock-icons" -> "dock icons bottom"
    e == Element.HOME -> "home screen icons labels names widgets"
    e == Element.SHADE -> "control center controls columns grid quick settings"
    e == Element.DRAWER -> "app library folders tiles columns drawer"
    else -> ""
}

/** Words as the search compares them: lower case, "&" as "and", no punctuation. */
internal fun searchWords(s: String): List<String> {
    val b = StringBuilder(s.length + 8)
    for (ch in s.lowercase()) when {
        ch == '&' -> b.append(" and ")
        ch.isLetterOrDigit() -> b.append(ch)
        else -> b.append(' ')
    }
    return b.split(' ').filter { it.isNotEmpty() }
}

/**
 * The entries [q] finds: every word of it begins a word of the entry (its name counts most, then the words it answers to,
 * then what it does and its place); settings before moments before single animations, and within those the best first
 * (a name that begins with the query first of all). The best [max].
 */
internal fun searchSettings(index: List<SearchEntry>, q: String, max: Int = 40): List<SearchEntry> {
    val asked = searchWords(q)
    if (asked.isEmpty()) return emptyList()
    val whole = asked.joinToString(" ")
    val scored = ArrayList<Pair<SearchEntry, Int>>()
    for (e in index) {
        val title = searchWords(e.title)
        val words = searchWords(e.words)
        val about = searchWords(e.about)
        val place = searchWords(e.place)
        var score = 0
        var all = true
        for (w in asked) {
            score += when {
                title.any { it.startsWith(w) } -> 3
                words.any { it.startsWith(w) } -> 2
                about.any { it.startsWith(w) } || place.any { it.startsWith(w) } -> 1
                else -> { all = false; 0 }
            }
            if (!all) break
        }
        if (!all) continue
        if (title.joinToString(" ").startsWith(whole)) score += 4
        scored += e to score
    }
    return scored.sortedWith(compareBy<Pair<SearchEntry, Int>> { it.first.rank }.thenByDescending { it.second }).take(max).map { it.first }
}

// ------------------------------------------------------------------ rows the search can light

/** The page a row is composed on ([Pushed.key], or a part's name): rows register under it ([anchor]). */
internal val LocalAnchorPage = staticCompositionLocalOf<String?> { null }

/**
 * A row the search can find: where it is (for scrolling to it) under its page and [title], and its light while the search
 * shows it (a soft accent wash in and out). The light is read in drawing; only the lit row reads its strength.
 */
internal fun Modifier.anchor(title: String, corner: Float = 0f, outset: Float = 0f): Modifier = composed {
    val st = LocalStudio.current
    val page = LocalAnchorPage.current
    if (st == null || page == null) return@composed this
    val key = remember(page, title) { "$page|$title" }
    val accent = LocalLook.current.color(SettingsTokens.ACCENT)
    this
        .onPlaced { st.anchors[key] = it }
        .drawBehind {
            if (st.lit == key) {
                val k = st.litK.value.coerceIn(0f, 1f)
                // (A row is lit edge to edge, its group's corners cut it; words on the page itself as a card would be:
                // [corner], reaching [outset] past them above and below.)
                if (k > 0.003f) {
                    val c = accent.copy(alpha = accent.alpha * LIT_ALPHA * k)
                    if (corner <= 0f && outset <= 0f) drawRect(c)
                    else drawRoundRect(c, androidx.compose.ui.geometry.Offset(0f, -outset),
                        androidx.compose.ui.geometry.Size(size.width, size.height + 2f * outset), androidx.compose.ui.geometry.CornerRadius(corner))
                }
            }
        }
}

/** How strong a lit row's wash is, of the accent. */
private const val LIT_ALPHA = 0.18f

/** Where a page's rows scroll ([LocalAnchorPage]'s key): its viewport, registered by the page. */
internal fun Modifier.viewport(st: Studio, page: String): Modifier = onPlaced { st.viewports[page] = it }

/**
 * Takes the settings to [e]: its part's page opens (growing from [from], the result's row, unless it is open), the page it
 * is on is pushed, the fold it waits in or its moment's Every detail opens, the page scrolls the row into sight and the row
 * lights up and fades.
 */
internal suspend fun jumpTo(st: Studio, e: SearchEntry, from: Rect?, unit: Float, open: (Part) -> Unit) {
    val part = e.part
    if (st.open != part) {
        if (from != null) { st.tiles[part] = from; st.fromRow = part }
        open(part)
    }
    androidx.compose.runtime.snapshotFlow { st.open == part && st.p.value > 0.85f }.first { it }
    val pushed = e.pushed
    if (pushed != null && st.sub != pushed) {
        // (Another page of this part in: it goes first.)
        if (st.sub != null && st.sub?.part == part) {
            st.popSubs[part]?.invoke()
            androidx.compose.runtime.snapshotFlow { st.subPush.value < PAGE_GONE }.first { it }
        }
        st.pushers[part]?.invoke(pushed)
        androidx.compose.runtime.snapshotFlow { st.sub == pushed && st.subPush.value > 0.85f }.first { it }
    } else if (pushed == null && st.sub?.part == part && st.subPush.targetValue >= 0.5f) {
        st.popSubs[part]?.invoke()
        androidx.compose.runtime.snapshotFlow { st.subPush.value < PAGE_GONE }.first { it }
    }
    val page = pushed?.key ?: part.name
    val scroll = if (pushed == null) st.scrolls[part] else st.subScrolls[pushed]
    e.detail?.let { m ->
        st.detailFor = m.id
        // Built at its opening and open two frames later: its rows unfold from then on.
        kotlinx.coroutines.withTimeoutOrNull(1500) { androidx.compose.runtime.snapshotFlow { st.detailShown == m.id }.first { it } }
    }
    e.fold?.let { f ->
        // (Left open from now on, as a fold opened by hand.)
        SettingsPrefs.setFold(f, true)
        kotlinx.coroutines.withTimeoutOrNull(1500) { androidx.compose.runtime.snapshotFlow { f in st.foldsShown }.first { it } }
    }
    // What opened has finished unfolding (Every detail grows in): until then the page's scroll ends short of its rows, and
    // a scroll there stopped short.
    var stable = 0
    var lastMax = Float.NaN
    for (i in 0 until SETTLE_FRAMES_MAX) {
        androidx.compose.runtime.withFrameNanos { }
        val max = scroll?.scroller?.maxPos ?: 0f
        if (i >= 2 && max == lastMax) stable++ else stable = 0
        lastMax = max
        if (stable >= 3) break
    }
    val key = listOfNotNull(e.anchor, e.fallback).map { "$page|$it" }.firstOrNull { st.anchors[it]?.isAttached == true }
    dev.launcher.app.AppLog.log("[settings] search: ${e.title} (${e.place})" + if (key == null) ", its row not found" else "")
    if (key == null) return
    val row = st.anchors[key] ?: return
    val vp = st.viewports[page]?.takeIf { it.isAttached }
    if (vp != null && scroll != null) {
        val margin = LIT_MARGIN_PT * unit
        val dy = row.positionInWindow().y - vp.positionInWindow().y
        // (As far as the page goes: a row near its end, the page already there, waited out a scroll to nowhere before it lit.)
        val target = (scroll.offset + dy - margin).coerceIn(scroll.scroller.minPos, scroll.scroller.maxPos)
        if (kotlin.math.abs(target - scroll.offset) > 4f && (dy < margin || dy + row.size.height > vp.size.height - margin)) {
            scroll.scroller.animateTo(target, dev.launcher.app.motion.Motion.role(MotionTokens.INDEX_SCROLL), 0f)
            // (The scroller is no snapshot state: watched frame by frame, a flow of it never saw it come to rest.)
            while (scroll.scroller.isSettling) androidx.compose.runtime.withFrameNanos { }
        }
    }
    st.lit = key
    st.litK.snapTo(0f)
    st.litK.animateTo(1f, Look.spec(dev.launcher.app.motion.Motion.role(MotionTokens.APPEAR_FADE)))
    kotlinx.coroutines.delay(LIT_HOLD_MS)
    st.litK.animateTo(0f, Look.spec(dev.launcher.app.motion.Motion.role(MotionTokens.DISAPPEAR_FADE)))
    if (st.lit == key) st.lit = null
}

/** How long a found row stays lit before it fades. */
private const val LIT_HOLD_MS = 1100L
/** The most frames waited for a page to settle before scrolling to a row (about a second at 60 Hz). */
private const val SETTLE_FRAMES_MAX = 60
/** The room (pt) left above a row scrolled to. */
private const val LIT_MARGIN_PT = 90f

// ------------------------------------------------------------------ the start page's search

/**
 * The results while searching: the words asked, the best matches as rows that glide (a letter typed moves them), each naming
 * its place; tapped, the settings go there ([jumpTo]). With nothing asked yet, a few examples.
 */
@Composable
internal fun SearchResults(st: Studio, results: () -> List<SearchEntry>, onPick: (SearchEntry, Rect?) -> Unit) {
    val look = LocalLook.current
    val pad = look.dp(SettingsTokens.ROW_PADDING)
    val rowH = 62f * look.unit
    val scroll = st.searchScroll
    val viewport = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val window = remember(rowH) {
        androidx.compose.runtime.derivedStateOf {
            val first = kotlin.math.floor(scroll.offset / rowH).toInt() - 3
            first..(first + kotlin.math.ceil(viewport.intValue / rowH).toInt() + 6)
        }
    }
    val windowOf: () -> IntRange = remember(window) { { window.value } }
    val empty by remember { androidx.compose.runtime.derivedStateOf { results().isEmpty() } }
    val asked by remember { androidx.compose.runtime.derivedStateOf { st.query.text.isNotBlank() } }
    Box(Modifier.fillMaxSize().onSizeChanged { viewport.intValue = it.height }.iosScroll(scroll)) {
        Column(Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(look.dp(14f * look.unit)))
            val note = when {
                !asked -> "Search every setting: try “bounce”, “icons” or “dark”."
                empty -> "Nothing found. Try other words."
                else -> null
            }
            Reveal(note != null) {
                // (It keeps its last words while it folds away.)
                val last = remember { arrayOf("") }
                if (note != null) last[0] = note
                BasicText(last[0], style = look.text(SettingsTokens.ROW_DETAIL, look.color(SettingsTokens.ROW_DETAIL_COLOR)),
                    modifier = Modifier.padding(start = pad, end = pad, bottom = look.dp(10f * look.unit)))
            }
            GlidingRows(results, { it.id }, rowH, windowOf) { g -> ResultRow(st, g, onPick) }
            Spacer(Modifier.height(look.dp(320f * look.unit)))
        }
    }
}

/** One result: the setting's name and its place under it; it lights when pressed and grows into its page when tapped. */
@Composable
private fun ResultRow(st: Studio, g: Glide<SearchEntry>, onPick: (SearchEntry, Rect?) -> Unit) {
    val at = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val e = g.item
    Box(Modifier.fillMaxSize().onPlaced { at[0] = it }) {
        // (Its own counter: the first row of a list that changes is not always the first composed.)
        androidx.compose.runtime.CompositionLocalProvider(LocalRowCounter provides null) {
            BaseRow(e.title, e.place, {
                if (!g.leaving) {
                    val sheet = st.sheet?.takeIf { it.isAttached }
                    val me = at[0]?.takeIf { it.isAttached }
                    onPick(e, if (sheet != null && me != null) sheet.localBoundingBoxOf(me, clipBounds = false) else null)
                }
            }, separator = g.index > 0) {
                BasicText("›", style = LocalLook.current.text(SettingsTokens.ROW_TITLE, LocalLook.current.color(SettingsTokens.ROW_DETAIL_COLOR)))
            }
        }
    }
}
