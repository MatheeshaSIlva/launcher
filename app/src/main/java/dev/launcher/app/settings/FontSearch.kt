package dev.launcher.app.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import dev.launcher.app.R
import dev.launcher.app.motion.MotionTokens
import dev.launcher.app.theme.FontPreviews
import dev.launcher.app.theme.GoogleFontCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Google Fonts sheet (docs/PLAN_SETTINGS.md C3): a font row's "More Fonts" opens it over the settings to find any family
 * on fonts.google.com by name or category, every name written in its own face. What is chosen waits for Apply as the rest.
 */
@Stable
internal class FontSearch {
    /** What the sheet chooses for: its title, the family chosen now ("google:Name", "inter"...), and how to choose one. */
    class Request(val title: String, val chosen: () -> String, val pick: (String) -> Unit)

    var request by mutableStateOf<Request?>(null)
        private set
    /** Bumped by every opening (one while it closes takes it back up). */
    var opened by mutableIntStateOf(0)
        private set
    /** How far it is up (0 down, 1 open); a drag on its top moves it. */
    val p = Animatable(0f)

    fun open(r: Request) { request = r; opened++ }
    internal fun gone() { request = null }
}

internal val LocalFontSearch = staticCompositionLocalOf<FontSearch?> { null }

/**
 * Google families found with the search: offered as chips on both font rows from then on (newest last; kept on the phone,
 * the last [MAX]). Only added to (a chip never vanishes from under the finger).
 */
internal object FontPicks {
    private const val MAX = 6
    val list = mutableStateListOf<String>()
    private var loaded = false

    fun load(ctx: android.content.Context) {
        if (loaded) return
        loaded = true
        prefs(ctx).getString("picks", null)?.split('\n')?.filter { it.isNotBlank() }?.let { list.addAll(it) }
    }

    fun add(ctx: android.content.Context, name: String) {
        if (name in list) return
        list.add(name)
        prefs(ctx).edit().putString("picks", list.takeLast(MAX).joinToString("\n")).apply()
    }

    private fun prefs(ctx: android.content.Context) = ctx.getSharedPreferences("fonts", android.content.Context.MODE_PRIVATE)
}

/**
 * Font family [id]'s face for a preview: the bundled and the phone's at once; a Google family once it is here (null until
 * then: on the phone already, the next frame; else after its download). Its arrival changes nothing else.
 */
@Composable
internal fun rememberFace(id: String): FontFamily? {
    val ctx = LocalContext.current
    val look = LocalLook.current
    if (!id.startsWith("google:")) return remember(id, look.version) { FontFamily(dev.launcher.app.theme.Fonts.of(id, 500, 15f)) }
    val name = id.removePrefix("google:")
    var face by remember(name) { mutableStateOf(FontPreviews.ready(name)?.let { FontFamily(it) }) }
    LaunchedEffect(name) {
        if (face == null) withContext(FontPreviews.dispatcher) { FontPreviews.load(ctx, name) }?.let { face = FontFamily(it) }
    }
    return face
}

/**
 * The sheet itself, over everything. Built with the settings (after their pages) and kept: out of sight and untouchable
 * while it is down, placed in front as it opens. Built in its opening's first frame it cost the emulator 220 ms (the sheet
 * appeared already up); drawn once out of sight when built, so its first opening records nothing either (as the pages).
 */
@Composable
internal fun FontSheet(search: FontSearch) {
    val look = LocalLook.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val top = WindowInsets.statusBars.getTop(density) + 10f * look.unit
    val sheetH = look.screenH - top
    val close: (Float) -> Unit = { v ->
        focus.clearFocus()
        keyboard?.hide()
        scope.launch {
            search.p.animateTo(0f, look.motion(MotionTokens.SHEET), v)
            search.gone()
        }
    }
    LaunchedEffect(search.opened) { if (search.opened > 0) search.p.animateTo(1f, look.motion(MotionTokens.SHEET)) }
    BackHandler(enabled = search.request != null) { close(0f) }
    var warm by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        warm = false
    }
    val scrim = look.color(SettingsTokens.SCRIM)
    val fill = look.color(SettingsTokens.BACKGROUND)
    val corner = look.dp(SettingsTokens.SHEET_CORNER)
    Box(Modifier.fillMaxSize().graphicsLayer {
        val up = search.request != null
        translationX = if (up) 0f else look.screenW * 3f
        alpha = if (up || warm) 1f else 0f
    }) {
        // What is beneath dims as it rises; a tap there puts it away.
        Box(Modifier.fillMaxSize()
            .drawBehind { drawRect(scrim.copy(alpha = scrim.alpha * search.p.value.coerceIn(0f, 1f))) }
            .pointerInput(Unit) { rowTap({}) { close(0f) } })
        Column(Modifier.fillMaxSize().padding(top = look.dp(top))
            .graphicsLayer { translationY = (1f - search.p.value) * sheetH }
            .background(fill, RoundedCornerShape(topStart = corner, topEnd = corner))
            .clip(RoundedCornerShape(topStart = corner, topEnd = corner))
            // Its own touches (a tap between its rows is no tap on the dimmed page beneath).
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
            SheetContent(search, sheetH, close)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.SheetContent(search: FontSearch, sheetH: Float, close: (Float) -> Unit) {
    val look = LocalLook.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val pad = look.dp(SettingsTokens.ROW_PADDING)
    // The row it chooses for (kept while it goes down: its title and check stay as they were).
    val last = remember { arrayOfNulls<FontSearch.Request>(1) }
    search.request?.let { last[0] = it }
    val r = last[0]
    var query by remember { mutableStateOf("") }
    var category by remember { mutableIntStateOf(0) }
    val scroll = remember { IosScrollState() }
    // Down: the next opening starts afresh (no query, every category, the top of the list).
    val down = search.request == null
    LaunchedEffect(down) { if (down) { query = ""; category = 0; scroll.scroller.jumpTo(0f) } }
    // Google's list: the one kept on the phone, read with the settings; read from Google only when the search opens.
    var families by remember { mutableStateOf(GoogleFontCatalog.cachedNow()) }
    var unreachable by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (families == null) families = withContext(Dispatchers.IO) { GoogleFontCatalog.warm(ctx); GoogleFontCatalog.cachedNow() }
    }
    LaunchedEffect(search.opened) {
        if (search.opened == 0 || families != null) return@LaunchedEffect
        unreachable = false
        val f = withContext(Dispatchers.IO) { GoogleFontCatalog.families(ctx) }
        if (f == null) unreachable = true else families = f
    }
    val cat = if (category == 0) null else GoogleFontCatalog.CATEGORIES[category - 1].first
    val results = remember(families, query, cat) { families?.let { GoogleFontCatalog.search(it, query, cat, RESULTS) } ?: emptyList() }
    val heading = when {
        families == null && unreachable -> "Google Fonts can't be reached"
        families == null -> "Loading Google Fonts\u2026"
        query.isNotBlank() && results.isEmpty() -> "No matches"
        query.isNotBlank() -> "Matches"
        else -> "Popular"
    }

    // The top: its grabber and title; dragged down, the sheet follows the finger and goes (or comes back up). The finger is
    // read on the window (this moves with the sheet: read here, the sheet chased it at half its speed and stood still).
    val at = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    Box(Modifier.fillMaxWidth().height(look.dp(56f * look.unit)).onPlaced { at[0] = it }.pointerInput(sheetH) {
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false)
            fun onWindow(o: Offset) = at[0]?.takeIf { it.isAttached }?.localToWindow(o) ?: o
            val y0 = onWindow(first.position).y
            val tracker = VelocityTracker()
            tracker.addPosition(first.uptimeMillis, Offset(0f, y0))
            val slop = viewConfiguration.touchSlop
            val p0 = search.p.value
            var dragging = false
            while (true) {
                val c = awaitPointerEvent().changes.firstOrNull { it.id == first.id } ?: break
                val y = onWindow(c.position).y
                tracker.addPosition(c.uptimeMillis, Offset(0f, y))
                val dy = y - y0
                if (!dragging && abs(dy) > slop) { dragging = true; focus.clearFocus() }
                if (dragging) {
                    c.consume()
                    var to = p0 - (dy - kotlin.math.sign(dy) * slop) / sheetH
                    // Up past open: a little, ever stiffer (a rubber band).
                    if (to > 1f) to = 1f + (to - 1f) * 0.15f
                    scope.launch { search.p.snapTo(to) }
                }
                if (!c.pressed) break
            }
            if (dragging) {
                val v = -tracker.calculateVelocity().y / sheetH
                if (search.p.value < 0.8f || v < -1.5f) close(v)
                else scope.launch { search.p.animateTo(1f, look.motion(MotionTokens.SHEET), v) }
            }
        }
    }) {
        val grabber = look.color(SettingsTokens.TITLE_COLOR).copy(alpha = 0.18f)
        Box(Modifier.align(Alignment.TopCenter).padding(top = look.dp(6f * look.unit)).size(look.dp(36f * look.unit), look.dp(5f * look.unit))
            .drawBehind { drawRoundRect(grabber, cornerRadius = CornerRadius(size.height / 2f)) })
        BasicText(r?.title ?: "", style = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.TITLE_COLOR)).copy(fontWeight = FontWeight(600)),
            modifier = Modifier.align(Alignment.Center).padding(top = look.dp(6f * look.unit)))
        RoundButton(R.drawable.sym_close, Modifier.align(Alignment.CenterEnd).padding(end = pad, top = look.dp(6f * look.unit))) { close(0f) }
    }
    SearchField(query, { query = it }, "Search Google Fonts", Modifier.padding(horizontal = pad))
    Spacer(Modifier.height(look.dp(10f * look.unit)))
    ChoiceChips(listOf("All") + GoogleFontCatalog.CATEGORIES.map { it.second }, category, Modifier.padding(horizontal = pad)) { category = it }
    Spacer(Modifier.height(look.dp(18f * look.unit)))
    Rolling(heading, look.text(SettingsTokens.SECTION, look.color(SettingsTokens.SECTION_COLOR)),
        Modifier.padding(start = pad + pad, bottom = look.dp(6f * look.unit)))

    // The results: their own scroll (iOS's physics), above the keyboard while it is up; a scroll puts the keyboard away.
    LaunchedEffect(scroll) { snapshotFlow { scroll.moving }.collect { if (it) focus.clearFocus() } }
    var viewport by remember { mutableIntStateOf(0) }
    val rowH = look.pt(SettingsTokens.ROW_HEIGHT)
    val nav = WindowInsets.navigationBars.getBottom(LocalDensity.current).toFloat()
    // The rows in sight and a few around them: only those are built (a list of sixty built at once cost a frame).
    val window = remember(rowH) {
        androidx.compose.runtime.derivedStateOf {
            val first = kotlin.math.floor(scroll.offset / rowH).toInt() - 3
            first..(first + kotlin.math.ceil(viewport / rowH).toInt() + 6)
        }
    }
    val chosen = r?.chosen?.invoke()
    Box(Modifier.fillMaxWidth().weight(1f).windowInsetsPadding(WindowInsets.ime.only(WindowInsetsSides.Bottom))
        .onSizeChanged { viewport = it.height }.clip(androidx.compose.ui.graphics.RectangleShape).iosScroll(scroll)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = pad)) {
            GlidingRows(results, rowH, { window.value },
                inView = { y -> !down && (y - scroll.offset).let { it > -rowH * 2f && it < viewport + rowH } }) { f, index, leaving ->
                FontResultRow(f, index, chosen == "google:${f.name}", leaving) {
                    val req = search.request ?: return@FontResultRow
                    FontPicks.add(ctx, f.name)
                    req.pick("google:${f.name}")
                    close(0f)
                }
            }
            Spacer(Modifier.height(look.dp(24f * look.unit + nav)))
        }
    }
}

/** At most this many results show (the best matches; scrolling a list of 1700 to find one is no search). */
private const val RESULTS = 60

/**
 * One family: its name in its own face (the text font until that arrives, then fading into it; one on the phone already is
 * there at once), its category, a check.
 */
@Composable
private fun FontResultRow(f: GoogleFontCatalog.Family, index: () -> Int, chosen: Boolean, leaving: () -> Boolean, onPick: () -> Unit) {
    val look = LocalLook.current
    val ctx = LocalContext.current
    val inView = LocalInView.current
    var pressed by remember { mutableStateOf(false) }
    val press by animateFloatAsState(if (pressed) 1f else 0f, look.motion(if (pressed) MotionTokens.PRESS_IN else MotionTokens.PRESS_OUT), label = "press")
    val check by animateFloatAsState(if (chosen) 1f else 0f, look.motion(MotionTokens.CC_TOGGLE), label = "check")
    var face by remember(f.name) { mutableStateOf(FontPreviews.ready(f.name)?.let { FontFamily(it) }) }
    val arrive = remember(f.name) { Animatable(if (face != null) 1f else 0f) }
    LaunchedEffect(f.name) {
        if (face != null) return@LaunchedEffect
        // On the phone: read now. Else downloaded only once it is on screen (a quick search does not download sixty fonts).
        val tf = withContext(FontPreviews.dispatcher) { FontPreviews.kept(ctx, f.name) } ?: run {
            snapshotFlow { inView() }.first { it }
            withContext(FontPreviews.dispatcher) { FontPreviews.load(ctx, f.name) }
        } ?: return@LaunchedEffect
        face = FontFamily(tf)
        // Fades into its face where it can be seen; out of sight it simply has it.
        if (inView()) arrive.animateTo(1f, look.motion(MotionTokens.APPEAR_FADE)) else arrive.snapTo(1f)
    }
    val pad = look.dp(SettingsTokens.ROW_PADDING)
    val separator = look.color(SettingsTokens.ROW_SEPARATOR)
    val label = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_LABEL))
    val pressFill = androidx.compose.ui.graphics.Color(dev.launcher.app.theme.Appearance.pressFill)
    Row(Modifier.fillMaxSize()
        .drawBehind {
            if (press > 0.001f) drawRect(pressFill.copy(alpha = pressFill.alpha * press))
            if (index() > 0) drawRect(separator, Offset(pad.toPx(), 0f), Size(size.width - pad.toPx(), 1f))
        }
        .pointerInput(f.name) { rowTap({ pressed = it }) { if (!leaving()) onPick() } }
        .padding(horizontal = pad),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            BasicText(f.name, style = label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer { alpha = 1f - arrive.value })
            face?.let { ff ->
                BasicText(f.name, style = label.copy(fontFamily = ff), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer { alpha = arrive.value })
            }
        }
        Spacer(Modifier.width(look.dp(10f * look.unit)))
        val catName = GoogleFontCatalog.CATEGORIES.firstOrNull { it.first == f.category }?.second ?: f.category
        BasicText(catName, style = look.text(SettingsTokens.ROW_DETAIL, look.color(SettingsTokens.ROW_DETAIL_COLOR)), maxLines = 1)
        Spacer(Modifier.width(look.dp(10f * look.unit)))
        BasicText("\u2713", style = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_CHECK).copy(alpha = check)))
    }
}

/** Whether a row's place in the list is on screen (or about to be), read where it is asked. */
private val LocalInView = staticCompositionLocalOf<() -> Boolean> { { true } }

private class Glide(val key: String, var item: GoogleFontCatalog.Family, y: Float, a: Float) {
    val y = Animatable(y)
    val a = Animatable(a)
    var index by mutableIntStateOf(0)
    var leaving by mutableStateOf(false)
}

/**
 * A list on a solid group whose rows glide to their new places when it changes (a letter typed): a row that stays moves
 * there on the reflow spring, a new one fades and rises in at its place, one that goes fades where it was; the group's
 * height follows. Every motion interruptible (the next letter retargets them). Only the rows in [window] (indices: those
 * in sight and a few around) are built, and only they move: the others simply take their places. Rows of height [rowH].
 */
@Composable
private fun GlidingRows(items: List<GoogleFontCatalog.Family>, rowH: Float, window: () -> IntRange, inView: (Float) -> Boolean,
                        row: @Composable (GoogleFontCatalog.Family, () -> Int, () -> Boolean) -> Unit) {
    val look = LocalLook.current
    val scope = rememberCoroutineScope()
    val glides = remember { mutableStateListOf<Glide>() }
    val height = remember { Animatable(0f) }
    val rise = 10f * look.unit
    LaunchedEffect(items) {
        val seen = window()
        val at = HashMap<String, Int>(items.size * 2)
        items.forEachIndexed { i, f -> at[f.name] = i }
        for (g in glides.toList()) if (g.key !in at && !g.leaving) {
            if (g.index !in seen) { glides.remove(g); continue }
            g.leaving = true
            scope.launch {
                g.a.animateTo(0f, look.motion(MotionTokens.DISAPPEAR_FADE))
                glides.remove(g)
            }
        }
        items.forEachIndexed { i, f ->
            val to = i * rowH
            val g = glides.firstOrNull { it.key == f.name }
            if (g == null) {
                if (i !in seen) { glides.add(Glide(f.name, f, to, 1f).also { it.index = i }); return@forEachIndexed }
                val n = Glide(f.name, f, to + rise, 0f).also { it.index = i }
                glides.add(n)
                scope.launch { n.a.animateTo(1f, look.motion(MotionTokens.APPEAR_FADE)) }
                scope.launch { n.y.animateTo(to, look.motion(MotionTokens.REFLOW)) }
            } else {
                val moving = i in seen || g.index in seen
                g.item = f
                g.index = i
                if (g.leaving) { g.leaving = false; scope.launch { g.a.animateTo(1f, look.motion(MotionTokens.APPEAR_FADE)) } }
                if (g.y.targetValue != to || g.y.value != to) scope.launch { if (moving) g.y.animateTo(to, look.motion(MotionTokens.REFLOW)) else g.y.snapTo(to) }
            }
        }
        height.animateTo(items.size * rowH, look.motion(MotionTokens.REFLOW))
    }
    val groupFill = look.color(SettingsTokens.GROUP_FILL)
    val corner = look.pt(SettingsTokens.GROUP_CORNER)
    val shown = window()
    Box(Modifier.fillMaxWidth()
        .layout { m, c ->
            val h = height.value.roundToInt().coerceAtLeast(0)
            val p = m.measure(c.copy(minHeight = h, maxHeight = h))
            layout(p.width, h) { p.place(0, 0) }
        }
        .drawBehind { if (size.height > 0.5f) drawRoundRect(groupFill, cornerRadius = CornerRadius(corner)) }
        .clip(RoundedCornerShape(look.dp(corner)))) {
        for (g in glides) if (g.index in shown) key(g.key) {
            androidx.compose.runtime.CompositionLocalProvider(LocalInView provides { inView(g.y.value) }) {
                Box(Modifier.fillMaxWidth().height(look.dp(rowH)).graphicsLayer { translationY = g.y.value; alpha = g.a.value.coerceIn(0f, 1f) }) {
                    row(g.item, { g.index }, { g.leaving })
                }
            }
        }
    }
}

/** A search field (iOS's): a capsule with a magnifier, the words, and a clear button that fades in while there are any. */
@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier) {
    val look = LocalLook.current
    val fill = look.color(SettingsTokens.FIELD_FILL)
    val hint = look.color(SettingsTokens.FIELD_PLACEHOLDER)
    val text = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_LABEL))
    val focus = LocalFocusManager.current
    val empty by animateFloatAsState(if (value.isEmpty()) 1f else 0f,
        look.motion(if (value.isEmpty()) MotionTokens.FIELD_CLEAR_OUT else MotionTokens.FIELD_CLEAR_IN), label = "empty")
    Row(modifier.fillMaxWidth().height(look.dp(SettingsTokens.FIELD_HEIGHT))
        .background(fill, RoundedCornerShape(look.dp(look.pt(SettingsTokens.FIELD_HEIGHT) / 2f))),
        verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(look.dp(10f * look.unit)))
        Image(painterResource(R.drawable.sym_search), null, colorFilter = ColorFilter.tint(hint),
            modifier = Modifier.size(look.dp(17f * look.unit)))
        Spacer(Modifier.width(look.dp(6f * look.unit)))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            BasicText(placeholder, style = text.copy(color = hint), maxLines = 1, modifier = Modifier.graphicsLayer { alpha = empty })
            BasicTextField(value, onChange, singleLine = true, textStyle = text,
                cursorBrush = SolidColor(look.color(SettingsTokens.ACCENT)),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth())
        }
        // Clear: a filled circle with a cross, there while there are words.
        Canvas(Modifier.size(look.dp(36f * look.unit)).graphicsLayer { alpha = 1f - empty; scaleX = 0.6f + 0.4f * (1f - empty); scaleY = scaleX }
            .pointerInput(Unit) { rowTap({}) { if (value.isNotEmpty()) onChange("") } }) {
            val r = 8.5f * look.unit
            val c = center
            drawCircle(hint, r, c)
            val a = 3.2f * look.unit
            val w = 1.6f * look.unit
            drawLine(fill.copy(alpha = 1f), Offset(c.x - a, c.y - a), Offset(c.x + a, c.y + a), w)
            drawLine(fill.copy(alpha = 1f), Offset(c.x - a, c.y + a), Offset(c.x + a, c.y - a), w)
        }
        Spacer(Modifier.width(look.dp(2f * look.unit)))
    }
}

/** A small round button with a symbol (a sheet's close). */
@Composable
private fun RoundButton(symbol: Int, modifier: Modifier, onClick: () -> Unit) {
    val look = LocalLook.current
    var pressed by remember { mutableStateOf(false) }
    val press by animateFloatAsState(if (pressed) 1f else 0f, look.motion(if (pressed) MotionTokens.PRESS_IN else MotionTokens.PRESS_OUT), label = "press")
    val fill = look.color(SettingsTokens.CHIP_FILL)
    val size = 30f * look.unit
    Box(modifier.size(look.dp(size)).pressScale { 1f - 0.08f * press }
        .drawBehind { drawCircle(fill) }
        .pointerInput(Unit) { rowTap({ pressed = it }, onClick) }, contentAlignment = Alignment.Center) {
        Image(painterResource(symbol), null, colorFilter = ColorFilter.tint(look.color(SettingsTokens.ROW_DETAIL_COLOR)),
            modifier = Modifier.size(look.dp(12f * look.unit)))
    }
}
