package dev.launcher.app.settings

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import dev.launcher.app.HomeBridge
import dev.launcher.app.R
import dev.launcher.app.design.ColorKey
import dev.launcher.app.design.Design
import dev.launcher.app.design.NumberKey
import dev.launcher.app.design.TextKey
import dev.launcher.app.layout.Element
import dev.launcher.app.layout.Layouts
import dev.launcher.app.layout.Option
import dev.launcher.app.layout.Setup
import dev.launcher.app.motion.MotionTokens
import dev.launcher.app.theme.Appearance
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The settings app's tokens: its own namespace (`comp.settings.*`, aliases into the shared ones), so the settings can
 * follow a theme of their own (docs/PLAN_LAYOUTS_THEMES.md, a theme per element) and read no other layout's.
 */
object SettingsTokens {
    val BACKGROUND = ColorKey("comp.settings.background")
    val TITLE = TextKey("comp.settings.title")
    val TITLE_COLOR = ColorKey("comp.settings.title-color")
    val HINT_COLOR = ColorKey("comp.settings.hint-color")
    val MARGIN = NumberKey("comp.settings.margin")
    val SHEET_FILL = ColorKey("comp.settings.sheet.fill")
    val SHEET_RIM = ColorKey("comp.settings.sheet.rim")
    val SHEET_SHADOW = ColorKey("comp.settings.sheet.shadow")
    val SHEET_CORNER = NumberKey("comp.settings.sheet.corner")
    val SHEET_INSET = NumberKey("comp.settings.sheet.inset")
    val SHEET_REST = NumberKey("comp.settings.sheet.rest")
    val SHEET_OPEN = NumberKey("comp.settings.sheet.open")
    val TILE_FILL = ColorKey("comp.settings.tile.fill")
    val TILE_TINT = NumberKey("comp.settings.tile.tint")
    val TILE_TITLE_TINT = NumberKey("comp.settings.tile.title-tint")
    val TILE_CORNER = NumberKey("comp.settings.tile.corner")
    val TILE_HEIGHT = NumberKey("comp.settings.tile.height")
    val TILE_GAP = NumberKey("comp.settings.tile.gap")
    val TILE_SYMBOL = NumberKey("comp.settings.tile.symbol")
    val TILE_SYMBOL_CORNER = NumberKey("comp.settings.tile.symbol-corner")
    val TILE_SYMBOL_COLOR = ColorKey("comp.settings.tile.symbol-color")
    val TILE_TITLE = TextKey("comp.settings.tile.title")
    val TILE_DETAIL = TextKey("comp.settings.tile.detail")
    val CHIP_FILL = ColorKey("comp.settings.chip.fill")
    val CHIP_CORNER = NumberKey("comp.settings.chip.corner")
    val CHIP_TEXT = TextKey("comp.settings.chip.text")
    val CHIP_TEXT_COLOR = ColorKey("comp.settings.chip.text-color")
    val BUTTON_FILL = ColorKey("comp.settings.button.fill")
    val BUTTON_RIM = ColorKey("comp.settings.button.rim")
    val BUTTON_SYMBOL = ColorKey("comp.settings.button.symbol-color")
    val BUTTON_SIZE = NumberKey("comp.settings.button.size")
    val GROUP_FILL = ColorKey("comp.settings.group.fill")
    val GROUP_CORNER = NumberKey("comp.settings.group.corner")
    val SECTION = TextKey("comp.settings.section")
    val SECTION_COLOR = ColorKey("comp.settings.section-color")
    val ROW_TITLE = TextKey("comp.settings.row.title")
    val ROW_DETAIL = TextKey("comp.settings.row.detail")
    val ROW_LABEL = ColorKey("comp.settings.row.label-color")
    val ROW_DETAIL_COLOR = ColorKey("comp.settings.row.detail-color")
    val ROW_SEPARATOR = ColorKey("comp.settings.row.separator-color")
    val ROW_CHECK = ColorKey("comp.settings.row.check-color")
    val ROW_HEIGHT = NumberKey("comp.settings.row.height")
    val ROW_PADDING = NumberKey("comp.settings.row.padding")
    val TOGGLE_W = NumberKey("comp.settings.toggle.width")
    val TOGGLE_H = NumberKey("comp.settings.toggle.height")
    val TOGGLE_ON = ColorKey("comp.settings.toggle.on-color")
    val TOGGLE_OFF = ColorKey("comp.settings.toggle.off-color")
    val TOGGLE_KNOB = ColorKey("comp.settings.toggle.knob-color")
    val PREVIEW_RIM = ColorKey("comp.settings.preview.rim")
    val PREVIEW_RIM_WIDTH = NumberKey("comp.settings.preview.rim-width")
    val PREVIEW_SHADOW = ColorKey("comp.settings.preview.shadow")
    val PREVIEW_CORNER = NumberKey("comp.settings.preview.corner")
}

/** The parts of the settings, a tile each: its name, its colour and its symbol. */
private enum class Part(val title: String, val color: ColorKey, val symbol: Int) {
    LAYOUTS("Layouts", ColorKey("comp.settings.color.layouts"), R.drawable.sym_grid),
    LOOK("Look", ColorKey("comp.settings.color.look"), R.drawable.sym_palette),
    MOTION("Motion", ColorKey("comp.settings.color.motion"), R.drawable.sym_spring),
    PHONE("Phone", ColorKey("comp.settings.color.phone"), R.drawable.sym_shield),
}

/** What a part holds now, in a line (under its name on the tile). */
private fun Part.detail(): String = when (this) {
    Part.LAYOUTS -> "${Element.entries.size} parts of the phone"
    Part.LOOK -> Design.themeName + if (Design.wallpaperColors) " · wallpaper colours" else ""
    Part.MOTION -> Design.motionName
    Part.PHONE -> if (dev.launcher.app.ShizukuLink.service != null) "Shizuku connected" else "Shizuku not running"
}

/**
 * Which part is open and how far: [p] 0 is the tiles, 1 its page. Opening one changes no composition and no layout (the
 * S24 traced 14-19 ms of recomposition and 8-10 ms of layout in the first frame when it did): [open] and [p] are read in
 * drawing and placement only, and everything a page needs is composed, laid out and placed ahead of time. One value moves everything (the tile growing into the
 * sheet, the sheet rising, the picture of home shrinking above it, the title, the page's groups arriving), so every frame
 * of a change only moves and fades what is already built (docs/PLAN_SETTINGS.md S1: the S24 lost 60-80 ms building a
 * page in the first frame of its push).
 */
@Stable
private class Studio {
    var open by mutableStateOf<Part?>(null)
    val p = Animatable(0f)
    /** Each tile's bounds in the sheet: where its page grows from and goes back to. */
    val tiles = HashMap<Part, Rect>()
    /** How far each tile is pressed in (the growing one starts from the size it sank to); read in drawing only. */
    val press = Part.entries.associateWith { Animatable(0f) }
    var sheet: LayoutCoordinates? = null
    /** How many pages are built (ahead of time, one a frame, so opening one never builds it). */
    var built by mutableIntStateOf(0)
    /** A finger is taking the page back. */
    var dragging by mutableStateOf(false)
}

/** Where things are, in pixels: the sheet's two tops (at rest, a page open), the picture of home above it. */
private class Geometry(look: Look, statusPx: Float, val navPx: Float) {
    val unit = look.unit
    val w = look.screenW.toFloat()
    val h = look.screenH.toFloat()
    val inset = look.pt(SettingsTokens.SHEET_INSET)
    val sheetW = w - inset * 2f
    val restTop = h * Design.num(SettingsTokens.SHEET_REST)
    val openTop = h * Design.num(SettingsTokens.SHEET_OPEN)
    /** The sheet is laid out once at its open size and moved: nothing is laid out again while it moves. */
    val sheetH = h - openTop - inset
    val buttonSize = look.pt(SettingsTokens.BUTTON_SIZE)
    val headerTop = statusPx + 6f * unit
    val previewTop = headerTop + buttonSize + 14f * unit
    private val gap = 22f * unit
    val previewH = (restTop - previewTop - gap).coerceAtLeast(60f * unit)
    val previewW = previewH * w / h

    fun top(p: Float) = restTop + (openTop - restTop) * p

    /** The picture's scale wherever the sheet is: it keeps fitting between the header and the sheet. */
    fun previewScale(p: Float) = ((top(p) - previewTop - gap) / previewH).coerceIn(0.15f, 1.2f)
}

/** How far the page being composed is shown (0..1, read in drawing: the groups rise in with it). */
private val LocalShown = staticCompositionLocalOf<() -> Float> { { 1f } }

/** Counts a page's groups as they compose (each rises in a beat after the one above). */
private class GroupCounter { var n = 0 }

private val LocalGroups = staticCompositionLocalOf { GroupCounter() }

/**
 * The settings app, "studio" layout (docs/PLAN_SETTINGS.md, draft D): a picture of the phone above a sheet of tiles;
 * a tile grows into its page inside the sheet, which rises as the picture shrinks; back (the button, the system's Back,
 * a swipe right) shrinks the page into its tile. Every colour, surface, corner, font and motion is the theme's.
 */
@Composable
fun SettingsApp(onClose: () -> Unit, beforeWallpaper: (first: Boolean) -> Unit = {}) {
    SettingsTheme(beforeWallpaper) {
        val look = LocalLook.current
        val st = remember { Studio() }
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current
        val statusPx = WindowInsets.statusBars.getTop(density).toFloat()
        val navPx = WindowInsets.navigationBars.getBottom(density).toFloat()
        val g = remember(look, statusPx, navPx) { Geometry(look, statusPx, navPx) }
        val spec = androidx.compose.runtime.rememberUpdatedState(look.motion<Float>(MotionTokens.SETTINGS_OPEN))
        val open: (Part) -> Unit = remember(st, scope) {
            { part ->
                // From the tiles only (a tap on a fading tile while another page closes is not one).
                if (st.p.value < 0.02f || st.open == part) {
                    st.open = part
                    st.built = Part.entries.size
                    scope.launch { try { st.p.animateTo(1f, spec.value) } catch (_: kotlinx.coroutines.CancellationException) { } }
                }
            }
        }
        val close: (Float) -> Unit = remember(st, scope) {
            { velocity ->
                scope.launch {
                    try {
                        st.p.animateTo(0f, spec.value, velocity)
                        st.open = null
                    } catch (_: kotlinx.coroutines.CancellationException) { }   // opened again on the way: it stays open
                }
            }
        }
        BackToTiles(st, close)
        // The pages are built ahead, one a frame, once the app has come up.
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(350)
            while (st.built < Part.entries.size) {
                androidx.compose.runtime.withFrameNanos { }
                st.built++
            }
        }
        // While a page moves (its spring, or a finger), the settings leave the accessibility tree: with an accessibility
        // service on (gesture nav is one), Compose re-reads the whole tree every 100 ms while layers move (7-25 ms frames
        // on the S24; none without the tree). At rest it is all there again, for whatever reads it.
        val moving by remember { androidx.compose.runtime.derivedStateOf { st.p.isRunning || st.dragging } }
        Box(Modifier.fillMaxSize().then(if (moving) Modifier.clearAndSetSemantics { } else Modifier).background(look.color(SettingsTokens.BACKGROUND))) {
            HomePicture(st, g)
            Header(st, g) { close(0f) }
            Sheet(st, g, open, close)
        }
    }
}

/** The system's Back closes the open page (its own composable: only it changes when a page opens). */
@Composable
private fun BackToTiles(st: Studio, close: (Float) -> Unit) {
    BackHandler(enabled = st.open != null) { close(0f) }
}

/** Home as it is now, in the phone's shape; a new picture (after any change) fades in over the last. */
@Composable
private fun HomePicture(st: Studio, g: Geometry) {
    val look = LocalLook.current
    var shown by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var before by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val fade = remember { Animatable(1f) }
    // Taken again after every change and whenever the settings come back to the front (home may have changed meanwhile).
    var resumed by remember { mutableIntStateOf(0) }
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumed++ }
        lifecycle.addObserver(o)
        onDispose { lifecycle.removeObserver(o) }
    }
    LaunchedEffect(look.version, resumed) {
        kotlinx.coroutines.delay(150)
        val next = HomeBridge.home?.pictureNow() ?: return@LaunchedEffect
        before = shown
        shown = next
        fade.snapTo(0f)
        fade.animateTo(1f, look.motion(MotionTokens.APPEAR_FADE))
        before = null
    }
    val rim = look.color(SettingsTokens.PREVIEW_RIM)
    val rimW = look.pt(SettingsTokens.PREVIEW_RIM_WIDTH)
    val shadow = look.color(SettingsTokens.PREVIEW_SHADOW)
    val page = look.color(SettingsTokens.BACKGROUND)
    val corner = g.previewW * Design.num(SettingsTokens.PREVIEW_CORNER)
    val shadowPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    Box(
        Modifier
            .offset { IntOffset(((g.w - g.previewW) / 2f).roundToInt(), g.previewTop.roundToInt()) }
            .size(look.dp(g.previewW), look.dp(g.previewH))
            .graphicsLayer {
                val s = g.previewScale(st.p.value)
                scaleX = s; scaleY = s
                transformOrigin = TransformOrigin(0.5f, 0f)
            }
            .drawBehind {
                // A soft shadow (cast by a shape in the page's own colour, so only the shadow shows), then the phone's edge.
                shadowPaint.color = page.toArgb()
                shadowPaint.setShadowLayer(18f * g.unit, 0f, 8f * g.unit, shadow.toArgb())
                drawIntoCanvas { it.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, corner, corner, shadowPaint) }
                drawRoundRect(rim, Offset(-rimW, -rimW), Size(size.width + rimW * 2f, size.height + rimW * 2f), CornerRadius(corner + rimW))
            }
            .clip(RoundedCornerShape(look.dp(corner))),
    ) {
        val b = before
        val s = shown
        if (b != null) Image(b.asImageBitmap(), null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        if (s != null) Image(s.asImageBitmap(), "Your home screen", contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = fade.value })
        else look.wallpaper?.let { wp ->
            // Home has not shown since the app started: its wallpaper at least.
            Canvas(Modifier.fillMaxSize()) {
                drawIntoCanvas { c ->
                    val m = wp.matrix(look.screenW, look.screenH)
                    m.postScale(size.width / look.screenW, size.height / look.screenH)
                    c.nativeCanvas.drawBitmap(wp.bitmap, m, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                }
            }
        }
    }
}

/** The title (the app's, then the open page's) and the round back button, both moving with the page. */
@Composable
private fun Header(st: Studio, g: Geometry, onBack: () -> Unit) {
    val look = LocalLook.current
    val style = look.text(SettingsTokens.TITLE, look.color(SettingsTokens.TITLE_COLOR))
    val rise = 6f * g.unit
    Box(Modifier.fillMaxWidth().offset { IntOffset(0, g.headerTop.roundToInt()) }.height(look.dp(g.buttonSize)), contentAlignment = Alignment.Center) {
        BasicText("Settings", style = style, modifier = Modifier.graphicsLayer {
            val p = st.p.value
            alpha = (1f - p * 2f).coerceIn(0f, 1f)
            translationY = -p * rise
        })
        for (part in Part.entries) BasicText(part.title, style = style, modifier = Modifier.graphicsLayer {
            val p = if (st.open == part) st.p.value else 0f
            alpha = ((p - 0.4f) / 0.6f).coerceIn(0f, 1f)
            translationY = (1f - p) * rise
        })
    }
    RoundButton(R.drawable.sym_back, "Back", { st.open != null }, onBack, Modifier
        .offset { IntOffset(look.pt(SettingsTokens.MARGIN).roundToInt(), g.headerTop.roundToInt()) }
        .graphicsLayer {
            val p = st.p.value.coerceIn(0f, 1f)
            alpha = p
            val s = 0.6f + 0.4f * p
            scaleX = s; scaleY = s
        })
}

/** A round button (the theme's: iOS 27's glass circle, Graphite's solid one) with a symbol; it sinks when pressed. */
@Composable
private fun RoundButton(symbol: Int, label: String, enabled: () -> Boolean, onClick: () -> Unit, modifier: Modifier) {
    val look = LocalLook.current
    val size = look.pt(SettingsTokens.BUTTON_SIZE)
    val fill = look.color(SettingsTokens.BUTTON_FILL)
    val rim = look.color(SettingsTokens.BUTTON_RIM)
    var pressed by remember { mutableStateOf(false) }
    val press by animateFloatAsState(if (pressed) 1f else 0f, look.motion(if (pressed) MotionTokens.PRESS_IN else MotionTokens.PRESS_OUT), label = "press")
    Box(
        modifier
            .size(look.dp(size))
            .pressScale { 1f - 0.08f * press }
            .drawBehind {
                drawCircle(fill)
                drawCircle(rim, style = Stroke(1f * look.unit))
            }
            .pointerInput(onClick) { rowTap({ pressed = it && enabled() }) { if (enabled()) onClick() } },
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(symbol), label, colorFilter = ColorFilter.tint(look.color(SettingsTokens.BUTTON_SYMBOL)),
            modifier = Modifier.size(look.dp(size * 0.5f)))
    }
}

/**
 * The sheet: laid out once at its open size and moved by the page's value. It holds the tiles, the tile growing into
 * a page, and the pages (built ahead, placed only while open).
 */
@Composable
private fun Sheet(st: Studio, g: Geometry, open: (Part) -> Unit, close: (Float) -> Unit) {
    val look = LocalLook.current
    val fill = look.color(SettingsTokens.SHEET_FILL)
    val rim = look.color(SettingsTokens.SHEET_RIM)
    val shadow = look.color(SettingsTokens.SHEET_SHADOW)
    val page = look.color(SettingsTokens.BACKGROUND)
    val corner = look.pt(SettingsTokens.SHEET_CORNER)
    val shadowPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    // A sheet that meets the screen's edges takes its bottom corners past them.
    val below = if (g.inset < 0.5f) corner else 0f
    Box(
        Modifier
            .offset { IntOffset(g.inset.roundToInt(), g.openTop.roundToInt()) }
            .size(look.dp(g.sheetW), look.dp(g.sheetH))
            .graphicsLayer { translationY = g.top(st.p.value) - g.openTop }
            .drawBehind {
                shadowPaint.color = page.toArgb()
                shadowPaint.setShadowLayer(26f * g.unit, 0f, -2f * g.unit, shadow.toArgb())
                drawIntoCanvas { it.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height + below, corner, corner, shadowPaint) }
                drawRoundRect(fill, size = Size(size.width, size.height + below), cornerRadius = CornerRadius(corner))
                drawRoundRect(rim, size = Size(size.width, size.height + below), cornerRadius = CornerRadius(corner), style = Stroke(1f * g.unit))
            }
            .clip(RoundedCornerShape(topStart = look.dp(corner), topEnd = look.dp(corner),
                bottomStart = look.dp(corner - below), bottomEnd = look.dp(corner - below)))
            .onPlaced { st.sheet = it },
    ) {
        StartContent(st, g, open)
        for (part in Part.entries) GrowingTile(st, part)
        for ((i, part) in Part.entries.withIndex()) {
            if (st.built > i) PageLayer(st, g, part, close)
        }
        Box(
            Modifier.align(Alignment.TopCenter).padding(top = look.dp(7f * g.unit))
                .size(look.dp(36f * g.unit), look.dp(5f * g.unit))
                .background(look.color(SettingsTokens.TITLE_COLOR).copy(alpha = 0.18f), RoundedCornerShape(50)),
        )
    }
}

/** At rest: what you have now (chips that open their page) and the four tiles. */
@Composable
private fun StartContent(st: Studio, g: Geometry, open: (Part) -> Unit) {
    val look = LocalLook.current
    val m = look.dp(SettingsTokens.MARGIN)
    val gap = look.dp(SettingsTokens.TILE_GAP)
    // Everything but the open tile steps back and fades as the page grows over it.
    val back = Modifier.graphicsLayer {
        val p = st.p.value
        alpha = (1f - p * 1.8f).coerceIn(0f, 1f)
        val s = 1f - 0.05f * p.coerceIn(0f, 1f)
        scaleX = s; scaleY = s
    }
    Column(Modifier.fillMaxWidth().padding(start = m, end = m, top = look.dp(26f * g.unit))) {
        BasicText("Your setup", style = look.text(SettingsTokens.SECTION, look.color(SettingsTokens.SECTION_COLOR)),
            modifier = back.padding(bottom = look.dp(8f * g.unit)))
        Row(back.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(look.dp(6f * g.unit))) {
            Chip(Design.themeName) { open(Part.LOOK) }
            Chip(if (Design.wallpaperColors) "Wallpaper colours" else "Theme colours") { open(Part.LOOK) }
            Chip(Design.motionName + " motion") { open(Part.MOTION) }
        }
        Spacer(Modifier.height(look.dp(16f * g.unit)))
        for (row in listOf(listOf(Part.LAYOUTS, Part.LOOK), listOf(Part.MOTION, Part.PHONE))) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (part in row) Tile(st, part, open, Modifier.weight(1f))
            }
            Spacer(Modifier.height(gap))
        }
    }
}

@Composable
private fun Chip(text: String, onClick: () -> Unit) {
    val look = LocalLook.current
    var pressed by remember { mutableStateOf(false) }
    val press by animateFloatAsState(if (pressed) 1f else 0f, look.motion(if (pressed) MotionTokens.PRESS_IN else MotionTokens.PRESS_OUT), label = "press")
    BasicText(text, style = look.text(SettingsTokens.CHIP_TEXT, look.color(SettingsTokens.CHIP_TEXT_COLOR)), maxLines = 1,
        modifier = Modifier
            .pressScale { 1f - 0.05f * press }
            .background(look.color(SettingsTokens.CHIP_FILL), RoundedCornerShape(look.dp(SettingsTokens.CHIP_CORNER)))
            .pointerInput(onClick) { rowTap({ pressed = it }, onClick) }
            .padding(horizontal = look.dp(12f * look.unit), vertical = look.dp(7f * look.unit)))
}

/**
 * Pressed in: drawn smaller by [scale] around the centre. In drawing, not as a layer's transform: a layer that changes
 * its transform makes Compose re-read the accessibility tree (see [SettingsApp]).
 */
private fun Modifier.pressScale(scale: () -> Float): Modifier = drawWithContent {
    val s = scale()
    if (s == 1f) drawContent() else scale(s) { this@drawWithContent.drawContent() }
}

/** A tile's fill: its colour by the theme's tint over the tile's own fill. */
private fun tileFill(look: Look, part: Part): Color {
    val base = look.color(SettingsTokens.TILE_FILL)
    val c = look.color(part.color)
    val t = Design.num(SettingsTokens.TILE_TINT).coerceIn(0f, 1f) * c.alpha
    val a = t + base.alpha * (1f - t)
    if (a <= 0f) return Color.Transparent
    return Color(
        red = (c.red * t + base.red * base.alpha * (1f - t)) / a,
        green = (c.green * t + base.green * base.alpha * (1f - t)) / a,
        blue = (c.blue * t + base.blue * base.alpha * (1f - t)) / a,
        alpha = a,
    )
}

private fun mix(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t, green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t, alpha = a.alpha + (b.alpha - a.alpha) * t,
)

/** One part: its symbol on its colour, its name and what it holds; it sinks when pressed and grows into its page. */
@Composable
private fun Tile(st: Studio, part: Part, open: (Part) -> Unit, modifier: Modifier) {
    val look = LocalLook.current
    val c = look.color(part.color)
    val titleTint = Design.num(SettingsTokens.TILE_TITLE_TINT).coerceIn(0f, 1f)
    val label = look.color(SettingsTokens.ROW_LABEL)
    val detail = look.color(SettingsTokens.ROW_DETAIL_COLOR)
    val symbol = look.pt(SettingsTokens.TILE_SYMBOL)
    var pressed by remember { mutableStateOf(false) }
    val press = st.press.getValue(part)
    val pressIn = look.motion<Float>(MotionTokens.PRESS_IN)
    val pressOut = look.motion<Float>(MotionTokens.PRESS_OUT)
    LaunchedEffect(pressed) { press.animateTo(if (pressed) 1f else 0f, if (pressed) pressIn else pressOut) }
    Column(
        modifier
            .height(look.dp(SettingsTokens.TILE_HEIGHT))
            .onGloballyPositioned { co -> st.sheet?.let { s -> if (s.isAttached && co.isAttached) st.tiles[part] = s.localBoundingBoxOf(co) } }
            .graphicsLayer {
                val p = st.p.value
                if (st.open == part) {
                    // The open tile is drawn by the growing one (it takes its place from the first frame).
                    alpha = if (p > 0f) 0f else 1f
                    scaleX = 1f; scaleY = 1f
                } else {
                    // The others step back and fade as the page grows over them.
                    alpha = (1f - p * 1.8f).coerceIn(0f, 1f)
                    val s = 1f - 0.05f * p.coerceIn(0f, 1f)
                    scaleX = s; scaleY = s
                }
            }
            .pressScale { 1f - 0.04f * press.value }
            .background(tileFill(look, part), RoundedCornerShape(look.dp(SettingsTokens.TILE_CORNER)))
            .pointerInput(part) { rowTap({ pressed = it }) { open(part) } }
            .padding(look.dp(14f * look.unit)),
    ) {
        Box(Modifier.size(look.dp(symbol)).background(c, RoundedCornerShape(look.dp(SettingsTokens.TILE_SYMBOL_CORNER))), contentAlignment = Alignment.Center) {
            Image(painterResource(part.symbol), null, colorFilter = ColorFilter.tint(look.color(SettingsTokens.TILE_SYMBOL_COLOR)),
                modifier = Modifier.size(look.dp(symbol * 0.6f)))
        }
        Spacer(Modifier.weight(1f))
        BasicText(part.title, style = look.text(SettingsTokens.TILE_TITLE, mix(label, c, titleTint)), maxLines = 1)
        BasicText(part.detail(), style = look.text(SettingsTokens.TILE_DETAIL, mix(detail, c, titleTint).copy(alpha = if (titleTint > 0f) 0.8f else detail.alpha)),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The open tile growing into the sheet (and back): its shape from the tile's bounds to the sheet's, its colour fading
 * into the sheet's as it grows; its symbol and name ride along and fade as the page arrives.
 */
@Composable
private fun GrowingTile(st: Studio, part: Part) {
    val look = LocalLook.current
    val bg = tileFill(look, part)
    val c = look.color(part.color)
    val fromCorner = look.pt(SettingsTokens.TILE_CORNER)
    val toCorner = look.pt(SettingsTokens.SHEET_CORNER)
    val pad = 14f * look.unit
    val symbol = look.pt(SettingsTokens.TILE_SYMBOL)
    val symbolCorner = look.pt(SettingsTokens.TILE_SYMBOL_CORNER)
    val glyph = painterResource(part.symbol)
    val glyphColor = ColorFilter.tint(look.color(SettingsTokens.TILE_SYMBOL_COLOR))
    val titleTint = Design.num(SettingsTokens.TILE_TITLE_TINT).coerceIn(0f, 1f)
    val measurer = rememberTextMeasurer()
    val title = remember(part, look) { measurer.measure(part.title, look.text(SettingsTokens.TILE_TITLE, mix(look.color(SettingsTokens.ROW_LABEL), c, titleTint))) }
    Canvas(Modifier.fillMaxSize()) {
        if (st.open != part) return@Canvas
        val p = st.p.value
        if (p <= 0f) return@Canvas
        val tile = st.tiles[part] ?: return@Canvas
        // From the size the tile sank to when pressed (it springs back out as it grows).
        val sunk = 0.04f * st.press.getValue(part).value
        val from = Rect(tile.left + tile.width * sunk / 2f, tile.top + tile.height * sunk / 2f, tile.right - tile.width * sunk / 2f, tile.bottom - tile.height * sunk / 2f)
        val k = p.coerceIn(0f, 1.05f)
        val r = Rect(
            from.left + (0f - from.left) * k, from.top + (0f - from.top) * k,
            from.right + (size.width - from.right) * k, from.bottom + (size.height - from.bottom) * k,
        )
        val corner = fromCorner + (toCorner - fromCorner) * k.coerceIn(0f, 1f)
        val colour = (1f - (p - 0.25f) / 0.6f).coerceIn(0f, 1f)
        drawRoundRect(bg.copy(alpha = bg.alpha * colour), r.topLeft, r.size, CornerRadius(corner))
        val content = (1f - p / 0.35f).coerceIn(0f, 1f)
        if (content > 0f) {
            translate(r.left + pad, r.top + pad) {
                drawRoundRect(c.copy(alpha = c.alpha * content), Offset.Zero, Size(symbol, symbol), CornerRadius(symbolCorner))
                translate(symbol * 0.2f, symbol * 0.2f) {
                    with(glyph) { draw(Size(symbol * 0.6f, symbol * 0.6f), alpha = content, colorFilter = glyphColor) }
                }
            }
            drawText(title, topLeft = Offset(r.left + pad, from.bottom - from.top + r.top - pad - title.size.height - 16f * look.unit), alpha = content)
        }
    }
}

/** A page in the sheet: built ahead, placed (so drawn and touchable) only while it is the open one. */
@Composable
private fun PageLayer(st: Studio, g: Geometry, part: Part, close: (Float) -> Unit) {
    val look = LocalLook.current
    val scroll = remember { IosScrollState() }
    val scope = rememberCoroutineScope()
    val shown: () -> Float = { if (st.open == part) st.p.value.coerceIn(0f, 1f) else 0f }
    val counter = remember { GroupCounter() }
    counter.n = 0
    val m = look.dp(SettingsTokens.MARGIN)
    val fadePx = 18f * g.unit
    // Only the open page is in the accessibility tree: with an accessibility service on (gesture nav is one), Compose
    // scans the whole tree every 100 ms while anything moves (7-9 ms of the main thread on the S24 with every page in it).
    val here by remember { androidx.compose.runtime.derivedStateOf { st.open == part } }
    // Drawn once out of sight when built, so its first opening does not record it (25-45 ms on the S24).
    var warm by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        warm = false
    }
    CompositionLocalProvider(LocalShown provides shown, LocalGroups provides counter) {
        Box(
            Modifier
                .fillMaxSize()
                .then(if (here) Modifier else Modifier.clearAndSetSemantics { })
                // Laid out and placed from the start; while closed it waits out of sight (moved, not laid out, when it opens).
                .graphicsLayer {
                    val open = st.open == part
                    translationX = if (open) 0f else g.w * 3f
                    alpha = if ((open && st.p.value > 0f) || warm) 1f else 0f
                    // Content scrolled up dissolves under the sheet's top edge.
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .swipeBack(g, onMove = { f -> if (st.open == part) { st.dragging = true; scope.launch { st.p.snapTo((1f - f).coerceIn(0f, 1f)) } } },
                    onEnd = { f, v ->
                        st.dragging = false
                        if (st.open != part) Unit
                        else if (f > 0.3f || v > 0.5f) close(-v)
                        else scope.launch { st.p.animateTo(1f, look.motion(MotionTokens.SETTINGS_OPEN), -v) }
                    })
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = 6f * g.unit, endY = 6f * g.unit + fadePx),
                        blendMode = BlendMode.DstIn)
                },
        ) {
            Column(Modifier.fillMaxSize().iosScroll(scroll).padding(horizontal = m)) {
                Spacer(Modifier.height(look.dp(26f * g.unit)))
                when (part) {
                    Part.LAYOUTS -> LayoutsPage()
                    Part.LOOK -> LookPage()
                    Part.MOTION -> MotionPage()
                    Part.PHONE -> PhonePage()
                }
                Spacer(Modifier.height(look.dp(24f * g.unit + g.navPx + g.inset)))
            }
        }
    }
}

/**
 * A rightward drag that starts more sideways than up or down takes the page back towards its tile, following the finger
 * ([onMove] with the fraction of the width); let go, [onEnd] gets the fraction and the speed (widths a second).
 */
private fun Modifier.swipeBack(g: Geometry, onMove: (Float) -> Unit, onEnd: (Float, Float) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var x = 0f
        val first = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
            if (over > 0f) { change.consume(); x = over }   // rightwards only
        } ?: return@awaitEachGesture
        val w = g.sheetW.coerceAtLeast(1f)
        onMove(x / w)
        horizontalDrag(first.id) { change ->
            tracker.addPosition(change.uptimeMillis, change.position)
            x += change.positionChange().x
            onMove((x / w).coerceAtLeast(0f))
            change.consume()
        }
        dev.launcher.app.AppLog.log("[settings] swipe back let go at ${"%.2f".format(x / w)}")
        onEnd((x / w).coerceAtLeast(0f), tracker.calculateVelocity().x / w)
    }
}

// ------------------------------------------------------------------ the pages

@Composable
private fun LookPage() {
    Group("Theme") {
        for (t in Design.themes()) CheckRow(t.name, if (t.builtIn) null else "From a file", t.id == Design.themeId) { Design.setTheme(t.id) }
    }
    Group("Colours", "Any theme can take its accents from your wallpaper (Material You).") {
        ToggleRow("Wallpaper colours", null, Design.wallpaperColors) { Design.setColorSource(if (it) "wallpaper" else "theme") }
    }
}

@Composable
private fun MotionPage() {
    Group("Animations", "How everything moves. Each animation can be retimed in Expert for now.") {
        for (p in Design.motionPresets()) CheckRow(p.name, null, p.id == Design.motionPresetId) { Design.setMotion(p.id) }
    }
}

@Composable
private fun LayoutsPage() {
    for (e in Element.entries) {
        val current = Setup.layout(e)
        val layouts = Layouts.of(e)
        Group(e.title, current.description) {
            for (l in layouts) CheckRow(l.title, l.origin, l.id == current.id) { Setup.set(e, layout = l.id) }
        }
        if (current.placements.size > 1) {
            val at = Setup.placement(e)
            Group("Where it lives") {
                for (p in current.placements) CheckRow(p.title, p.description, p.id == at.id) { Setup.set(e, placement = p.id) }
            }
        }
        val toggles = current.options.filterIsInstance<Option.Toggle>()
        if (toggles.isNotEmpty()) Group("Options") {
            for (o in toggles) ToggleRow(o.title, o.description, Setup.bool(e, o.key, o.default)) { Setup.set(e, options = mapOf(o.key to it)) }
        }
    }
}

@Composable
private fun PhonePage() {
    val ctx = LocalContext.current
    Group("Shizuku", "The advanced features (the shade, gestures, app switching) work through Shizuku.") {
        val on = dev.launcher.app.ShizukuLink.service != null
        InfoRow("Status", if (on) "Connected" else "Not running")
    }
    Group("Safety") {
        NavRow("Safe settings", "Restore the system's status bar, gestures and animations") {
            ctx.startActivity(Intent(ctx, dev.launcher.app.SafeSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    Group("Updates") {
        NavRow("Developer panel", "Updates, status and the log") {
            ctx.startActivity(Intent(ctx, dev.launcher.app.DevActivity::class.java))
        }
    }
    Group("Expert") {
        NavRow("Every value of the theme", "The token editor, for theme authors") {
            ctx.startActivity(Intent(ctx, dev.launcher.app.design.DesignActivity::class.java))
        }
    }
}

// ------------------------------------------------------------------ the parts

/** How many rows a group has composed (each row learns whether it is the first). */
private class RowCounter { var n = 0 }

private val LocalRowCounter = androidx.compose.runtime.compositionLocalOf<RowCounter?> { null }

/**
 * A group of rows on a solid card (as Apple's apps: no glass on content), with a heading and a note; as its page opens
 * it rises in a beat after the group above.
 */
@Composable
private fun Group(title: String?, note: String? = null, rows: @Composable () -> Unit) {
    val look = LocalLook.current
    val pad = look.dp(SettingsTokens.ROW_PADDING)
    val shown = LocalShown.current
    val index = LocalGroups.current.n++
    val rise = Modifier.graphicsLayer {
        val k = ((shown() - 0.3f - 0.06f * index.coerceAtMost(6)) / 0.45f).coerceIn(0f, 1f)
        alpha = k
        translationY = (1f - k) * 22f * look.unit
    }
    if (title != null) {
        BasicText(title, style = look.text(SettingsTokens.SECTION, look.color(SettingsTokens.SECTION_COLOR)),
            modifier = rise.padding(start = pad, bottom = look.dp(6f * look.unit)))
    }
    // Rows count themselves as they are first composed: the separator goes above every row but the first.
    val counter = remember { RowCounter() }
    counter.n = 0
    Column(rise.fillMaxWidth().background(look.color(SettingsTokens.GROUP_FILL), RoundedCornerShape(look.dp(SettingsTokens.GROUP_CORNER)))
        .clip(RoundedCornerShape(look.dp(SettingsTokens.GROUP_CORNER)))) {
        CompositionLocalProvider(LocalRowCounter provides counter) { rows() }
    }
    if (note != null) {
        BasicText(note, style = look.text(SettingsTokens.ROW_DETAIL, look.color(SettingsTokens.ROW_DETAIL_COLOR)),
            modifier = rise.padding(start = pad, end = pad, top = look.dp(6f * look.unit)))
    }
    Spacer(Modifier.height(look.dp(24f * look.unit)))
}

/** One row: a name, an optional detail below it, something at its end; pressed, it lights up. */
@Composable
private fun BaseRow(title: String, detail: String?, onClick: (() -> Unit)?, end: @Composable () -> Unit) {
    val look = LocalLook.current
    val counter = LocalRowCounter.current
    val first = remember { counter == null || counter.n++ == 0 }
    var pressed by remember { mutableStateOf(false) }
    val press by animateFloatAsState(if (pressed) 1f else 0f, look.motion(if (pressed) MotionTokens.PRESS_IN else MotionTokens.PRESS_OUT), label = "press")
    val pad = look.dp(SettingsTokens.ROW_PADDING)
    val separator = look.color(SettingsTokens.ROW_SEPARATOR)
    Row(
        Modifier.fillMaxWidth().heightIn(min = look.dp(SettingsTokens.ROW_HEIGHT))
            .drawBehind {
                if (press > 0.001f) drawRect(Color(Appearance.pressFill).copy(alpha = Color(Appearance.pressFill).alpha * press))
                // Between rows: a hairline above each but the first (inset by the padding, as iOS's).
                if (!first) drawRect(separator, Offset(pad.toPx(), 0f), Size(size.width - pad.toPx(), 1f))
            }
            .then(if (onClick == null) Modifier else Modifier.pointerInput(onClick) { rowTap({ pressed = it }, onClick) })
            .padding(horizontal = pad, vertical = look.dp(10f * look.unit)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(title, style = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_LABEL)))
            if (detail != null) BasicText(detail, style = look.text(SettingsTokens.ROW_DETAIL, look.color(SettingsTokens.ROW_DETAIL_COLOR)))
        }
        Spacer(Modifier.width(look.dp(12f * look.unit)))
        end()
    }
}

@Composable
private fun NavRow(title: String, detail: String?, onClick: () -> Unit) = BaseRow(title, detail, onClick) {
    val look = LocalLook.current
    BasicText("›", style = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_DETAIL_COLOR)))
}

@Composable
private fun InfoRow(title: String, value: String) = BaseRow(title, null, null) {
    val look = LocalLook.current
    BasicText(value, style = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_DETAIL_COLOR)))
}

/** A choice: the chosen row carries a check (it moves to the row tapped). */
@Composable
private fun CheckRow(title: String, detail: String?, chosen: Boolean, onChoose: () -> Unit) = BaseRow(title, detail, { if (!chosen) onChoose() }) {
    val look = LocalLook.current
    val k by animateFloatAsState(if (chosen) 1f else 0f, look.motion(MotionTokens.CC_TOGGLE), label = "check")
    BasicText("✓", style = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_CHECK).copy(alpha = k)))
}

@Composable
private fun ToggleRow(title: String, detail: String?, on: Boolean, onChange: (Boolean) -> Unit) = BaseRow(title, detail, { onChange(!on) }) {
    Toggle(on)
}

/** The theme's switch: its track blends from off to on, its knob slides, both on the toggle role. */
@Composable
private fun Toggle(on: Boolean) {
    val look = LocalLook.current
    val k by animateFloatAsState(if (on) 1f else 0f, look.motion(MotionTokens.CC_TOGGLE), label = "toggle")
    val w = look.pt(SettingsTokens.TOGGLE_W)
    val h = look.pt(SettingsTokens.TOGGLE_H)
    val onC = look.color(SettingsTokens.TOGGLE_ON)
    val offC = look.color(SettingsTokens.TOGGLE_OFF)
    val knob = look.color(SettingsTokens.TOGGLE_KNOB)
    Canvas(Modifier.size(look.dp(w), look.dp(h))) {
        val t = k.coerceIn(0f, 1f)
        drawRoundRect(mix(offC, onC, t), cornerRadius = CornerRadius(size.height / 2f))
        val inset = size.height * 0.08f
        val kw = size.width * 0.6f - inset * 2f
        val kh = size.height - inset * 2f
        val x = inset + (size.width - kw - inset * 2f) * k
        drawRoundRect(Color.Black.copy(alpha = 0.12f), Offset(x, inset + kh * 0.06f), Size(kw, kh), CornerRadius(kh / 2f))
        drawRoundRect(knob, Offset(x, inset), Size(kw, kh), CornerRadius(kh / 2f))
    }
}

/**
 * A tap as iOS counts one: let go near where it went down. A touch that moves past the touch slop (a scroll, a swipe back,
 * a drag across the row) or that something else takes is no tap; [pressed] follows the finger while it could still be one.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.rowTap(pressed: (Boolean) -> Unit, onTap: () -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        pressed(true)
        var tapped = false
        while (true) {
            val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            if (c.isConsumed || (c.position - down.position).getDistance() > viewConfiguration.touchSlop) break
            if (!c.pressed) { tapped = true; c.consume(); break }
        }
        pressed(false)
        if (tapped) onTap()
    }
}
