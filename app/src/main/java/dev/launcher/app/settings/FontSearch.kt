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
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import kotlinx.coroutines.flow.collectLatest
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
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.drawscope.Stroke
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

    /** The last request (its title and check stay while the sheet goes down). Read with [request] to follow it. */
    var last: Request? = null
        private set

    fun open(r: Request) { last = r; request = r; opened++ }
    internal fun gone() { request = null }
    /** Down at once, unseen (left open, the settings open from home again: it covered home's picture). */
    internal suspend fun closeNow() { p.snapTo(0f); request = null }

    // Where the sheet is (set as it is laid out), so what it covers is not drawn ([coveredFrom]).
    internal var top = 0f
    internal var height = 0f
    internal var cornerPx = 0f

    /**
     * From where down the sheet covers the screen now (its top edge plus its corners' depth), or past the screen's end
     * when it is down. What lies beneath is drawn only above it (the settings under a sheet that covered them cost the
     * GPU 7-8 ms a frame on the S24 as it scrolled).
     */
    fun coveredFrom(): Float = if (request == null) Float.POSITIVE_INFINITY else top + (1f - p.value.coerceIn(0f, 1f)) * height + cornerPx
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
    if (!id.startsWith("google:")) return remember(id, look.version) { familyOf(dev.launcher.app.theme.Fonts.of(id, 500, 15f)) }
    val name = id.removePrefix("google:")
    var face by remember(name) { mutableStateOf(FontPreviews.ready(name)?.let { familyOf(it) }) }
    LaunchedEffect(name) {
        if (face == null) withContext(FontPreviews.dispatcher) { FontPreviews.load(ctx, name) }?.let { face = familyOf(it) }
    }
    return face
}

/**
 * The sheet itself, over everything. Built with the settings (after their pages) and kept: out of sight and untouchable
 * while it is down, placed in front as it opens. Built in its opening's first frame it cost the emulator 220 ms (the sheet
 * appeared already up); drawn once out of sight when built, so its first opening records nothing either (as the pages).
 * Its opening composes only what shows the request (its title) and the rows whose check changes.
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
    search.top = top
    search.height = sheetH
    search.cornerPx = look.pt(SettingsTokens.SHEET_CORNER)
    val spec = androidx.compose.runtime.rememberUpdatedState(look.motion<Float>(MotionTokens.SHEET))
    val close: (Float) -> Unit = remember(search, scope, focus, keyboard) {
        { v ->
            focus.clearFocus()
            keyboard?.hide()
            scope.launch {
                search.p.animateTo(0f, spec.value, v)
                search.gone()
            }
        }
    }
    LaunchedEffect(search.opened) { if (search.opened > 0) search.p.animateTo(1f, spec.value) }
    SheetBack(search, close)
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

/** Back puts the sheet away while it is up (its own scope: only it composes again as it opens and closes). */
@Composable
private fun SheetBack(search: FontSearch, close: (Float) -> Unit) {
    BackHandler(enabled = search.request != null) { close(0f) }
}

/** The sheet's title: the row it chooses for (its own scope: the one thing that composes again as it opens). */
@Composable
private fun SheetTitle(search: FontSearch, modifier: Modifier) {
    val look = LocalLook.current
    search.request   // read: composed again when another row opens it
    BasicText(search.last?.title ?: "", style = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.TITLE_COLOR)).copy(fontWeight = FontWeight(600)),
        modifier = modifier)
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.SheetContent(search: FontSearch, sheetH: Float, close: (Float) -> Unit) {
    val look = LocalLook.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val pad = look.dp(SettingsTokens.ROW_PADDING)
    // The field keeps its own words: typing composes nothing here (it recomposed the whole sheet a letter on the S24).
    val field = rememberTextFieldState()
    var category by remember { mutableIntStateOf(0) }
    val scroll = remember { IosScrollState() }
    // Down: the next opening starts afresh (no query, every category, the top of the list).
    LaunchedEffect(search) {
        snapshotFlow { search.request == null }.collect { down -> if (down) { field.clearText(); category = 0; scroll.scroller.jumpTo(0f) } }
    }
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
    // Searched off the main thread as the words change (1950 names: 25 ms a letter on the S24 in composition); the list
    // follows a frame later. A next letter cancels a search still running.
    val results = remember { mutableStateOf<List<GoogleFontCatalog.Family>>(emptyList()) }
    LaunchedEffect(Unit) {
        snapshotFlow { Triple(families, field.text.toString(), category) }.collectLatest { (all, q, c) ->
            if (all == null) return@collectLatest
            val cat = if (c == 0) null else GoogleFontCatalog.CATEGORIES[c - 1].first
            results.value = withContext(Dispatchers.Default) { GoogleFontCatalog.search(all, q, cat, RESULTS) }
        }
    }
    val heading by remember {
        androidx.compose.runtime.derivedStateOf {
            val asked = field.text.isNotBlank()
            when {
                families == null && unreachable -> "Google Fonts can't be reached"
                families == null -> "Loading Google Fonts\u2026"
                asked && results.value.isEmpty() -> "No matches"
                asked -> "Matches"
                else -> "Popular"
            }
        }
    }

    // The top: its grabber and title; dragged down, the sheet follows the finger and goes (or comes back up). The finger is
    // read on the window (this moves with the sheet: read here, the sheet chased it at half its speed and stood still).
    val at = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val spec = androidx.compose.runtime.rememberUpdatedState(look.motion<Float>(MotionTokens.SHEET))
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
                else scope.launch { search.p.animateTo(1f, spec.value, v) }
            }
        }
    }) {
        val grabber = look.color(SettingsTokens.TITLE_COLOR).copy(alpha = 0.18f)
        Box(Modifier.align(Alignment.TopCenter).padding(top = look.dp(6f * look.unit)).size(look.dp(36f * look.unit), look.dp(5f * look.unit))
            .drawBehind { drawRoundRect(grabber, cornerRadius = CornerRadius(size.height / 2f)) })
        SheetTitle(search, Modifier.align(Alignment.Center).padding(top = look.dp(6f * look.unit)))
        RoundButton(R.drawable.sym_close, Modifier.align(Alignment.CenterEnd).padding(end = pad, top = look.dp(6f * look.unit))) { close(0f) }
    }
    SearchField(field, "Search Google Fonts", Modifier.padding(horizontal = pad))
    Spacer(Modifier.height(look.dp(10f * look.unit)))
    val categories = remember { listOf("All") + GoogleFontCatalog.CATEGORIES.map { it.second } }
    ChoiceChips(categories, category, Modifier.padding(horizontal = pad)) { category = it }
    Spacer(Modifier.height(look.dp(18f * look.unit)))
    Rolling(heading, look.text(SettingsTokens.SECTION, look.color(SettingsTokens.SECTION_COLOR)),
        Modifier.padding(start = pad + pad, bottom = look.dp(6f * look.unit)))

    // The results: their own scroll (iOS's physics), above the keyboard while it is up; a scroll puts the keyboard away.
    LaunchedEffect(scroll) { snapshotFlow { scroll.moving }.collect { if (it) focus.clearFocus() } }
    val viewport = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val rowH = look.pt(SettingsTokens.ROW_HEIGHT)
    val nav = WindowInsets.navigationBars.getBottom(LocalDensity.current).toFloat()
    // The rows in sight and a few around them: only those are built (a list of sixty built at once cost a frame).
    val window = remember(rowH) {
        androidx.compose.runtime.derivedStateOf {
            val first = kotlin.math.floor(scroll.offset / rowH).toInt() - 3
            first..(first + kotlin.math.ceil(viewport.intValue / rowH).toInt() + 6)
        }
    }
    // Made once, so a row composes again only when what it shows changes.
    val inView: (Float) -> Boolean = remember(search, scroll, rowH) {
        { y -> search.request != null && (y - scroll.offset).let { it > -rowH * 2f && it < viewport.intValue + rowH } }
    }
    val chosenOf: () -> String? = remember(search) { { search.last?.chosen?.invoke() } }
    val onPick: (String) -> Unit = remember(search, ctx, close) {
        { name ->
            search.request?.let { req ->
                FontPicks.add(ctx, name)
                req.pick("google:$name")
                close(0f)
            }
        }
    }
    val windowOf: () -> IntRange = remember(window) { { window.value } }
    val measurer = rememberTextMeasurer()
    val paint = remember(look, measurer) { RowPaint(look, measurer, scope) }
    Box(Modifier.fillMaxWidth().weight(1f).windowInsetsPadding(WindowInsets.ime.only(WindowInsetsSides.Bottom))
        .onSizeChanged { viewport.intValue = it.height }.clip(androidx.compose.ui.graphics.RectangleShape).iosScroll(scroll)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = pad)) {
            GlidingRows({ results.value }, rowH, windowOf) { g -> FontResultRow(g, paint, chosenOf, inView, onPick) }
            Spacer(Modifier.height(look.dp(24f * look.unit + nav)))
        }
    }
}

/** New rows built in one frame (each costs about 3 ms of the S24's main thread in a debug build). */
private const val ROWS_A_FRAME = 2

/** At most this many results show (the best matches; scrolling a list of 1700 to find one is no search). */
private const val RESULTS = 60

/** What every row draws the same way: its categories' words (measured once each) and the check. */
@Stable
private class RowPaint(val look: Look, val measurer: androidx.compose.ui.text.TextMeasurer, val scope: kotlinx.coroutines.CoroutineScope) {
    private val categories = HashMap<String, androidx.compose.ui.text.TextLayoutResult>()
    val detail = look.text(SettingsTokens.ROW_DETAIL, look.color(SettingsTokens.ROW_DETAIL_COLOR))
    val check = look.color(SettingsTokens.ROW_CHECK)
    val separator = look.color(SettingsTokens.ROW_SEPARATOR)
    val pressFill = androidx.compose.ui.graphics.Color(dev.launcher.app.theme.Appearance.pressFill)
    val checkPath = androidx.compose.ui.graphics.Path()
    fun category(name: String) = categories.getOrPut(name) { measurer.measure(name, detail, maxLines = 1) }
}

/**
 * One family: its name in its own face (the text font until that arrives, then fading into it; one on the phone already is
 * there at once), its category and the check drawn (a row is three nodes: rows arriving as letters are typed were laid
 * out four texts each). Every argument is made once: it composes again only when its own check or face changes.
 */
@Composable
private fun FontResultRow(g: Glide, paint: RowPaint, chosenOf: () -> String?, inView: (Float) -> Boolean, onPick: (String) -> Unit) {
    val look = paint.look
    val ctx = LocalContext.current
    val isChosen by remember(g) { androidx.compose.runtime.derivedStateOf { chosenOf() == "google:${g.name}" } }
    // One animatable each (a row composed as it scrolls in: animateFloatAsState made two more effects and states a row).
    val press = remember { Animatable(0f) }
    val check = remember(g) { Animatable(if (isChosen) 1f else 0f) }
    LaunchedEffect(isChosen) { check.animateTo(if (isChosen) 1f else 0f, look.motion(MotionTokens.CC_TOGGLE)) }
    var face by remember(g.name) { mutableStateOf(FontPreviews.ready(g.name)?.let { familyOf(it) }) }
    val arrive = remember(g.name) { Animatable(if (face != null) 1f else 0f) }
    // The text font's name only until its own face has faded in.
    val fallback by remember(g.name) { androidx.compose.runtime.derivedStateOf { face == null || arrive.value < 1f } }
    LaunchedEffect(g.name) {
        if (face != null) return@LaunchedEffect
        // On the phone: read now. Else downloaded only once it is on screen (a quick search does not download sixty fonts).
        val tf = withContext(FontPreviews.dispatcher) { FontPreviews.kept(ctx, g.name) } ?: run {
            snapshotFlow { inView(g.y.value) }.first { it }
            withContext(FontPreviews.dispatcher) { FontPreviews.load(ctx, g.name) }
        } ?: return@LaunchedEffect
        face = familyOf(tf)
        // Fades into its face where it can be seen; out of sight it simply has it.
        if (inView(g.y.value)) arrive.animateTo(1f, look.motion(MotionTokens.APPEAR_FADE)) else arrive.snapTo(1f)
    }
    val pad = look.pt(SettingsTokens.ROW_PADDING)
    val label = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_LABEL))
    val faceStyle = remember(label, face) { face?.let { label.copy(fontFamily = it) } }
    // Room on the right for the category and the check.
    val endRoom = 120f * look.unit
    Box(Modifier.fillMaxSize()
        .drawBehind {
            if (press.value > 0.001f) drawRect(paint.pressFill.copy(alpha = paint.pressFill.alpha * press.value))
            if (g.index > 0) drawRect(paint.separator, Offset(pad, 0f), Size(size.width - pad, 1f))
            val checkW = 14f * look.unit
            val right = size.width - pad
            paint.category(g.categoryName).let { t ->
                drawText(t, topLeft = Offset(right - checkW - 10f * look.unit - t.size.width, (size.height - t.size.height) / 2f))
            }
            if (check.value > 0.001f) {
                // A check mark (iOS's): a short stroke down, a long one up, in the check colour.
                val p = paint.checkPath.apply {
                    reset()
                    val cy = size.height / 2f
                    moveTo(right - checkW, cy)
                    lineTo(right - checkW * 0.62f, cy + checkW * 0.38f)
                    lineTo(right, cy - checkW * 0.45f)
                }
                drawPath(p, paint.check.copy(alpha = paint.check.alpha * check.value),
                    style = Stroke(2.2f * look.unit, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
        }
        .pointerInput(g) {
            rowTap({ down ->
                paint.scope.launch { press.animateTo(if (down) 1f else 0f, look.motion(if (down) MotionTokens.PRESS_IN else MotionTokens.PRESS_OUT)) }
            }) { if (!g.leaving) onPick(g.name) }
        }
        .padding(start = look.dp(pad), end = look.dp(endRoom)),
        contentAlignment = Alignment.CenterStart) {
        if (fallback) BasicText(g.name, style = label, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.graphicsLayer { alpha = 1f - arrive.value; compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha })
        if (faceStyle != null) BasicText(g.name, style = faceStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.graphicsLayer { alpha = arrive.value; compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha })
    }
}

/** A row of the list: its family, where it is and is going, and whether it is on its way out. */
@Stable
private class Glide(val name: String, category: String, y: Float, a: Float) {
    val categoryName = GoogleFontCatalog.CATEGORIES.firstOrNull { it.first == category }?.second ?: category
    val y = Animatable(y)
    val a = Animatable(a)
    var index by mutableIntStateOf(0)
    var leaving by mutableStateOf(false)
    /** Composed now: only rows near the visible part are, and at most [ROWS_A_FRAME] are built in a frame. */
    var built by mutableStateOf(false)
    /** Rises and fades in as it is built (new, or brought into sight as the list changed). */
    var appear = false
}

/**
 * A list on a solid group whose rows glide to their new places when it changes (a letter typed): a row that stays moves
 * there on the reflow spring, a new one fades and rises in at its place, one that goes fades where it was; the group's
 * height follows. Every motion interruptible (the next letter retargets them). Only the rows in [window] (indices: those
 * in sight and a few around) are built, and only they move: the others simply take their places. Rows of height [rowH].
 */
@Composable
private fun GlidingRows(itemsOf: () -> List<GoogleFontCatalog.Family>, rowH: Float, window: () -> IntRange, row: @Composable (Glide) -> Unit) {
    val look = LocalLook.current
    val scope = rememberCoroutineScope()
    val glides = remember { mutableStateListOf<Glide>() }
    val height = remember { Animatable(0f) }
    val rise = 10f * look.unit
    LaunchedEffect(Unit) { snapshotFlow { itemsOf() }.collectLatest { items ->
        val seen = window()
        val at = HashMap<String, Int>(items.size * 2)
        items.forEachIndexed { i, f -> at[f.name] = i }
        val byName = HashMap<String, Glide>(glides.size * 2)
        for (g in glides) byName[g.name] = g
        for (g in glides.toList()) if (g.name !in at && !g.leaving) {
            if (!g.built) { glides.remove(g); continue }
            g.leaving = true
            scope.launch {
                g.a.animateTo(0f, look.motion(MotionTokens.DISAPPEAR_FADE))
                glides.remove(g)
            }
        }
        items.forEachIndexed { i, f ->
            val to = i * rowH
            val g = byName[f.name]
            if (g == null) {
                // In sight it rises and fades in as it is built; out of sight it is simply there when scrolled to.
                val inSight = i in seen
                glides.add(Glide(f.name, f.category, if (inSight) to + rise else to, if (inSight) 0f else 1f).also { it.index = i; it.appear = inSight })
            } else {
                g.index = i
                if (g.leaving) { g.leaving = false; scope.launch { g.a.animateTo(1f, look.motion(MotionTokens.APPEAR_FADE)) } }
                when {
                    // On screen: it glides to its new place.
                    g.built -> scope.launch { g.y.animateTo(to, look.motion(MotionTokens.REFLOW)) }
                    // Brought into sight from out of it: it arrives as a new one does (it would pop in at its place).
                    i in seen -> { g.appear = true; scope.launch { g.a.snapTo(0f); g.y.snapTo(to + rise) } }
                    else -> scope.launch { g.y.snapTo(to) }
                }
            }
        }
        scope.launch { height.animateTo(items.size * rowH, look.motion(MotionTokens.REFLOW)) }
    } }
    // The builder: rows near the visible part are built, at most two a frame and the top first (ten built in one frame took
    // it 75 ms on the S24); rows that arrive rise and fade in as they are built, so a new list arrives as a quick cascade.
    // Rows far out of sight are let go, two a frame, once nothing waits to be built. A finger or a letter never waits.
    LaunchedEffect(Unit) {
        while (true) {
            snapshotFlow { val w = window(); glides.any { !it.leaving && (it.index in w) != it.built } }.first { it }
            val w = window()
            val build = glides.filter { !it.built && !it.leaving && it.index in w }.sortedBy { it.index }.take(ROWS_A_FRAME)
            for (g in build) {
                g.built = true
                if (g.appear) {
                    g.appear = false
                    scope.launch { g.a.animateTo(1f, look.motion(MotionTokens.APPEAR_FADE)) }
                    scope.launch { g.y.animateTo(g.index * rowH, look.motion(MotionTokens.REFLOW)) }
                }
            }
            if (build.isEmpty()) for (g in glides.filter { it.built && !it.leaving && it.index !in w }.take(ROWS_A_FRAME)) g.built = false
            androidx.compose.runtime.withFrameNanos { }
        }
    }
    val groupFill = look.color(SettingsTokens.GROUP_FILL)
    val corner = look.pt(SettingsTokens.GROUP_CORNER)
    // The group's height follows on its spring; the rows inside are measured once (measured with the height, every row was
    // measured again on every frame of it: 19 ms of layout as results arrived).
    Box(Modifier.fillMaxWidth()
        .drawBehind { if (size.height > 0.5f) drawRoundRect(groupFill, cornerRadius = CornerRadius(corner)) }
        .clip(RoundedCornerShape(look.dp(corner)))
        .layout { m, c ->
            val h = height.value.roundToInt().coerceAtLeast(0)
            val p = m.measure(c.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
            layout(p.width, h) { p.place(0, 0) }
        }) {
        // Keyed at the loop's top: a key inside the `if` sat in a positional group, so a row whose place in the list changed
        // was not recognised and was built again (eleven rows rebuilt in one frame as a letter was typed).
        for (g in glides.filter { it.built }) key(g.name) { GlideSlot(g, rowH, row) }
    }
}

/** A row's place: where its glide puts it, faded by it (its own scope: the list composing again leaves it alone). */
@Composable
private fun GlideSlot(g: Glide, rowH: Float, row: @Composable (Glide) -> Unit) {
    val look = LocalLook.current
    Box(Modifier.fillMaxWidth().height(look.dp(rowH)).graphicsLayer {
        translationY = g.y.value
        alpha = g.a.value.coerceIn(0f, 1f)
        // Faded without a buffer of its own (rows fading together were an offscreen layer each).
        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
    }) {
        row(g)
    }
}

/**
 * A search field (iOS's): a capsule with a magnifier, the words, and a clear button that fades in while there are any. It
 * keeps its own words ([state]): typing composes only it.
 */
@Composable
private fun SearchField(state: TextFieldState, placeholder: String, modifier: Modifier) {
    val look = LocalLook.current
    val fill = look.color(SettingsTokens.FIELD_FILL)
    val hint = look.color(SettingsTokens.FIELD_PLACEHOLDER)
    val text = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_LABEL))
    val focus = LocalFocusManager.current
    val isEmpty by remember(state) { androidx.compose.runtime.derivedStateOf { state.text.isEmpty() } }
    val empty by animateFloatAsState(if (isEmpty) 1f else 0f,
        look.motion(if (isEmpty) MotionTokens.FIELD_CLEAR_OUT else MotionTokens.FIELD_CLEAR_IN), label = "empty")
    Row(modifier.fillMaxWidth().height(look.dp(SettingsTokens.FIELD_HEIGHT))
        .background(fill, RoundedCornerShape(look.dp(look.pt(SettingsTokens.FIELD_HEIGHT) / 2f))),
        verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(look.dp(10f * look.unit)))
        Image(painterResource(R.drawable.sym_search), null, colorFilter = ColorFilter.tint(hint),
            modifier = Modifier.size(look.dp(17f * look.unit)))
        Spacer(Modifier.width(look.dp(6f * look.unit)))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            BasicText(placeholder, style = text.copy(color = hint), maxLines = 1, modifier = Modifier.graphicsLayer { alpha = empty })
            BasicTextField(state = state, textStyle = text, lineLimits = TextFieldLineLimits.SingleLine,
                cursorBrush = SolidColor(look.color(SettingsTokens.ACCENT)),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                onKeyboardAction = { focus.clearFocus() },
                modifier = Modifier.fillMaxWidth())
        }
        // Clear: a filled circle with a cross, there while there are words.
        Canvas(Modifier.size(look.dp(36f * look.unit)).graphicsLayer { alpha = 1f - empty; scaleX = 0.6f + 0.4f * (1f - empty); scaleY = scaleX }
            .pointerInput(state) { rowTap({}) { state.clearText() } }) {
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
    val drawn = 30f * look.unit
    // Drawn 30 pt across (iOS's sheet close), touched across the touch target.
    Box(modifier.size(look.dp(SettingsTokens.TARGET)).pressScale { 1f - 0.08f * press }
        .drawBehind { drawCircle(fill, radius = drawn / 2f) }
        .pointerInput(Unit) { rowTap({ pressed = it }, onClick) }, contentAlignment = Alignment.Center) {
        Image(painterResource(symbol), null, colorFilter = ColorFilter.tint(look.color(SettingsTokens.ROW_DETAIL_COLOR)),
            modifier = Modifier.size(look.dp(12f * look.unit)))
    }
}
