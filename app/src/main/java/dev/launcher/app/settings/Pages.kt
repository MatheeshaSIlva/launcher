package dev.launcher.app.settings

import android.content.Intent
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import dev.launcher.app.HomeBridge
import dev.launcher.app.design.ColorKey
import dev.launcher.app.design.Design
import dev.launcher.app.design.MaterialKey
import dev.launcher.app.design.NumberKey
import dev.launcher.app.design.TextKey
import dev.launcher.app.layout.Element
import dev.launcher.app.layout.Layouts
import dev.launcher.app.layout.Option
import dev.launcher.app.layout.Setup
import dev.launcher.app.motion.MotionTokens
import dev.launcher.app.theme.Appearance

/** The settings app's tokens: the shared components (design/Components.kt) its pages are made of. */
object SettingsTokens {
    val CARD = MaterialKey("component.card.material")
    val CARD_CORNER = NumberKey("component.card.corner")
    val MARGIN = NumberKey("component.page.margin")
    val BENEATH_DIM = ColorKey("component.page.beneath-dim")
    val EDGE_SHADOW = ColorKey("component.page.edge-shadow")
    val EDGE_SHADOW_WIDTH = NumberKey("component.page.edge-shadow-width")
    val ROW_HEIGHT = NumberKey("component.list-row.height")
    val ROW_PADDING = NumberKey("component.list-row.padding")
    val ROW_SEPARATOR = ColorKey("component.list-row.separator-color")
    val ROW_TITLE = TextKey("component.list-row.title")
    val ROW_DETAIL = TextKey("component.list-row.detail")
    val ROW_LABEL = ColorKey("component.list-row.label-color")
    val ROW_DETAIL_COLOR = ColorKey("component.list-row.detail-color")
    val ROW_CHECK = ColorKey("component.list-row.check-color")
    val TOGGLE_W = NumberKey("component.toggle.width")
    val TOGGLE_H = NumberKey("component.toggle.height")
    val TOGGLE_ON = ColorKey("component.toggle.on-color")
    val TOGGLE_OFF = ColorKey("component.toggle.off-color")
    val TOGGLE_KNOB = ColorKey("component.toggle.knob-color")
    val SECTION_TITLE = TextKey("component.section.title")
    val SECTION_COLOR = ColorKey("component.section.title-color")
    val LARGE_TITLE = TextKey("sys.type.large-title")
    val LABEL = ColorKey("sys.color.label.primary")
    val ACCENT = ColorKey("sys.color.accent")
}

private enum class Page(val title: String) { START("Settings"), LAYOUTS("Layouts"), LOOK("Look"), MOTION("Motion"), PHONE("Phone") }

/** What a page keeps while it is in the stack: where it was scrolled, and whether its groups have already arrived. */
private class PageState {
    val scroll = IosScrollState()
    var arrived = false
    var groups = 0
}

private val LocalPage = androidx.compose.runtime.staticCompositionLocalOf { PageState() }
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
private val LocalShared = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.animation.SharedTransitionScope?> { null }
private val LocalAnim = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.animation.AnimatedVisibilityScope?> { null }
/** A page pulled back by a swipe: [start] (false: there is nothing to go back to), [move] by a fraction of the width, [end]. */
private interface SwipeBack {
    fun start(): Boolean
    fun move(fraction: Float)
    /** Let go at [velocity] (page widths a second, positive towards back). */
    fun end(velocity: Float)
}

private val LocalSwipeBack = androidx.compose.runtime.staticCompositionLocalOf<SwipeBack?> { null }

/** Where a page is in the window (it slides): what it draws of the backdrop stays fixed to the screen. */
private class PageOrigin {
    var x by androidx.compose.runtime.mutableFloatStateOf(0f)
    var y by androidx.compose.runtime.mutableFloatStateOf(0f)
}

private val LocalPageOrigin = androidx.compose.runtime.staticCompositionLocalOf { PageOrigin() }

/**
 * A page as a solid sheet: the blurred backdrop drawn where the page is on screen (so it does not move as the page slides),
 * a soft shadow cast from its left edge onto the page beneath, and, while it is [beneath] another, a dim by [dim] (0..1).
 */
@Composable
private fun Modifier.pageSurface(origin: PageOrigin, beneath: Boolean, dim: () -> Float): Modifier {
    val look = LocalLook.current
    val shadow = look.color(SettingsTokens.EDGE_SHADOW)
    val shadowW = look.pt(SettingsTokens.EDGE_SHADOW_WIDTH)
    val dimColor = look.color(SettingsTokens.BENEATH_DIM)
    return this
        .onGloballyPositioned { val p = it.positionInWindow(); origin.x = p.x; origin.y = p.y }
        .drawWithContent {
            if (!beneath && origin.x > 0.5f) {
                drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Transparent, shadow), -shadowW, 0f),
                    topLeft = Offset(-shadowW, 0f), size = Size(shadowW, size.height))
            }
            // Within the page only: the page beneath shows past its edge.
            clipRect { drawIntoCanvas { look.drawBackdrop(it.nativeCanvas, origin.x, origin.y) } }
            drawContent()
            val d = if (beneath) dim() else 0f
            if (d > 0.001f) drawRect(dimColor, alpha = d)
        }
}

/** True while a page is pulled back by a swipe: its transitions run linearly, with the finger. */
private val LocalSwiping = androidx.compose.runtime.compositionLocalOf { false }

/** A transition that moves exactly with its seeked fraction (a swipe drives it). */
private fun <T> linear(): androidx.compose.animation.core.FiniteAnimationSpec<T> =
    androidx.compose.animation.core.tween(1000, easing = androidx.compose.animation.core.LinearEasing)

/** Moves to another stack of pages (a page pushed, or back). */
private val LocalGo = androidx.compose.runtime.staticCompositionLocalOf<(List<Page>) -> Unit> { {} }

/**
 * The settings app: a stack of pages over the blurred wallpaper, each change applied at once. Pages slide on the
 * preset's push role; a row's name becomes the next page's title and the title the way back (shared elements); a swipe
 * right moves the page under the finger and, let go, finishes or springs back.
 */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun SettingsApp(onClose: () -> Unit, beforeWallpaper: (first: Boolean) -> Unit = {}) {
    SettingsTheme(beforeWallpaper) {
        val look = LocalLook.current
        val nav = remember { androidx.compose.animation.core.SeekableTransitionState(listOf(Page.START)) }
        val transition = androidx.compose.animation.core.rememberTransition(nav, label = "pages")
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val pages = remember { HashMap<Page, PageState>() }
        pages.keys.retainAll((nav.currentState + nav.targetState).toSet())
        val go: (List<Page>) -> Unit = { to -> scope.launch { nav.animateTo(to) } }
        // Back (the system's gesture or key: not predictive back, which an Android 17 system dropped for this window when it
        // did not animate it) goes back with the page's own animation; on from the moment a page is pushed.
        androidx.activity.compose.BackHandler(enabled = nav.targetState.size > 1) { go(nav.targetState.dropLast(1)) }
        // A swipe to the right anywhere on a page pulls it back under the finger (iOS 26). While it lasts the pages move
        // linearly with the transition's fraction (a spring seeked by its time would run ahead of the finger: most of a
        // spring's motion is in its first moments); let go, the fraction springs on the push role from the finger's speed
        // to the end it was thrown towards, and the stack lands there.
        var swiping by remember { mutableStateOf(false) }
        val swipeBack = remember {
            object : SwipeBack {
                var from: List<Page>? = null
                var at = 0f
                override fun start(): Boolean {
                    if (from != null || nav.targetState.size < 2 || nav.currentState != nav.targetState) {
                        dev.launcher.app.AppLog.log("[settings] swipe refused (from ${from?.last()}, ${nav.currentState.last()} -> ${nav.targetState.last()})")
                        return false
                    }
                    from = nav.targetState
                    at = 0f
                    swiping = true
                    return true
                }
                override fun move(fraction: Float) {
                    val f = from ?: return
                    at = fraction.coerceIn(0f, 1f)
                    scope.launch { nav.seekTo(at, f.dropLast(1)) }
                }
                override fun end(velocity: Float) {
                    val f = from ?: return
                    // Thrown right, or let go past half way and not thrown back: it goes.
                    val commit = velocity > 0.75f || (velocity > -0.3f && at + velocity * 0.2f > 0.5f)
                    val to = f.dropLast(1)
                    scope.launch {
                        val a = androidx.compose.animation.core.Animatable(at)
                        a.animateTo(if (commit) 1f else 0f, look.motion(MotionTokens.NAV_PUSH), velocity) {
                            scope.launch { nav.seekTo(value.coerceIn(0f, 1f), to) }
                        }
                        dev.launcher.app.AppLog.log("[settings] swipe let go at ${"%.2f".format(at)}, ${"%.2f".format(velocity)}/s: ${if (commit) "back" else "stays"}")
                        nav.snapTo(if (commit) to else f)
                        dev.launcher.app.AppLog.log("[settings] swipe landed (${nav.currentState.last()} -> ${nav.targetState.last()})")
                        from = null
                        swiping = false
                    }
                }
            }
        }
        androidx.compose.animation.SharedTransitionLayout(Modifier.fillMaxSize().drawBehind { drawIntoCanvas { look.drawBackdrop(it.nativeCanvas) } }) {
            val shared = this
            transition.AnimatedContent(
                contentKey = { it.last() },
                transitionSpec = {
                    val forward = targetState.size > initialState.size
                    val slide: androidx.compose.animation.core.FiniteAnimationSpec<androidx.compose.ui.unit.IntOffset> =
                        if (swiping) linear() else look.motion(MotionTokens.NAV_PUSH)
                    // Solid pages (each draws the backdrop where it is on screen): the newer page slides over the older one, on
                    // top both ways, and the older one moves a third of the way in parallax under it (UIKit).
                    (slideInHorizontally(slide) { w -> if (forward) w else -w / 3 } togetherWith
                        slideOutHorizontally(slide) { w -> if (forward) -w / 3 else w }).apply { targetContentZIndex = if (forward) 1f else -1f }
                },
            ) { s ->
                val back: (() -> Unit)? = if (s.size > 1) ({ go(s.dropLast(1)) }) else null
                val push: (Page) -> Unit = { go(s + it) }
                // The older of two pages in a change lies beneath the other.
                val beneath = s.size < maxOf(transition.currentState.size, transition.targetState.size)
                val dim by this.transition.animateFloat(
                    transitionSpec = { if (swiping) linear() else look.motion(MotionTokens.NAV_PUSH) }, label = "dim",
                ) { if (it == androidx.compose.animation.EnterExitState.Visible) 0f else 1f }
                val origin = remember { PageOrigin() }
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalPageOrigin provides origin,
                    LocalPage provides pages.getOrPut(s.last()) { PageState() },
                    LocalShared provides shared,
                    LocalAnim provides this,
                    LocalGo provides go,
                    LocalSwipeBack provides swipeBack,
                    LocalSwiping provides swiping,
                ) {
                    Box(Modifier.fillMaxSize().pageSurface(origin, beneath, { dim })) {
                        when (s.last()) {
                            Page.START -> StartPage(push)
                            Page.LAYOUTS -> LayoutsPage(back)
                            Page.LOOK -> LookPage(back)
                            Page.MOTION -> MotionPage(back)
                            Page.PHONE -> PhonePage(back)
                        }
                    }
                }
            }
        }
    }
}

/** This element as one end of the shared title [key]: it flies and scales into the other end as the page changes. */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
private fun Modifier.sharedTitle(key: String): Modifier {
    val shared = LocalShared.current ?: return this
    val anim = LocalAnim.current ?: return this
    val look = LocalLook.current
    val swiping = LocalSwiping.current
    val fade: androidx.compose.animation.core.FiniteAnimationSpec<Float> = if (swiping) linear() else look.motion(MotionTokens.NAV_PUSH)
    return with(shared) {
        this@sharedTitle.sharedBounds(
            // Pulled by a swipe the titles stay with their pages (iOS): the flight is for taps and Back only.
            rememberSharedContentState(if (swiping) "$key~${System.identityHashCode(anim)}" else key), anim,
            enter = fadeIn(fade), exit = fadeOut(fade),
            boundsTransform = { _, _ -> if (swiping) linear() else look.motion(MotionTokens.NAV_PUSH) },
            resizeMode = androidx.compose.animation.SharedTransitionScope.ResizeMode.ScaleToBounds(),
        )
    }
}

// ------------------------------------------------------------------ the pages

@Composable
private fun StartPage(push: (Page) -> Unit) {
    val ctx = LocalContext.current
    PageScaffold("Settings", "START", back = null, onBack = {}) {
        YourSetup()
        Group(null) {
            NavRow("Layouts", "What each part of the phone is and where it lives", "LAYOUTS") { push(Page.LAYOUTS) }
            NavRow("Look", "Theme: ${Design.themeName}" + if (Design.wallpaperColors) ", wallpaper colours" else "", "LOOK") { push(Page.LOOK) }
            NavRow("Motion", "Animations: ${Design.motionName}", "MOTION") { push(Page.MOTION) }
        }
        Group(null) {
            NavRow("Phone", "Shizuku, safety, updates", "PHONE") { push(Page.PHONE) }
            NavRow("Expert", "Every value of the theme, for theme authors") {
                ctx.startActivity(Intent(ctx, dev.launcher.app.design.DesignActivity::class.java))
            }
        }
    }
}

/** Home as it is now, small, with what it is made of beside it. */
@Composable
private fun YourSetup() {
    val look = LocalLook.current
    // Taken again after each change (home is built again behind this app at once).
    var picture by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(look.version) {
        kotlinx.coroutines.delay(150)
        picture = HomeBridge.home?.pictureNow()
    }
    Group("Your setup") {
        Row(Modifier.padding(look.dp(SettingsTokens.ROW_PADDING)), verticalAlignment = Alignment.CenterVertically) {
            val p = picture
            val corner = look.dp(look.pt(SettingsTokens.CARD_CORNER) * 0.6f)
            Box(Modifier.width(look.dp(look.screenW * 0.28f)).aspectRatio(look.screenW / look.screenH.toFloat()).clip(RoundedCornerShape(corner))) {
                if (p != null) Image(p.asImageBitmap(), contentDescription = "Your home screen", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
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
            Spacer(Modifier.width(look.dp(SettingsTokens.ROW_PADDING)))
            Column(verticalArrangement = Arrangement.spacedBy(look.dp(8f * look.unit))) {
                Fact("Theme", Design.themeName)
                Fact("Colours", if (Design.wallpaperColors) "From the wallpaper" else "The theme's")
                Fact("Animations", Design.motionName)
                Fact("App drawer", Setup.placement(Element.DRAWER).title)
            }
        }
    }
}

@Composable
private fun Fact(name: String, value: String) {
    val look = LocalLook.current
    Column {
        BasicText(name, style = look.text(SettingsTokens.ROW_DETAIL, look.color(SettingsTokens.ROW_DETAIL_COLOR)))
        BasicText(value, style = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_LABEL)), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LookPage(back: (() -> Unit)?) {
    PageScaffold("Look", "LOOK", "Settings", back ?: {}) {
        Group("Theme") {
            for (t in Design.themes()) CheckRow(t.name, if (t.builtIn) null else "From a file", t.id == Design.themeId) { Design.setTheme(t.id) }
        }
        Group("Colours", "Any theme can take its accents from your wallpaper (Material You).") {
            ToggleRow("Wallpaper colours", null, Design.wallpaperColors) { Design.setColorSource(if (it) "wallpaper" else "theme") }
        }
    }
}

@Composable
private fun MotionPage(back: (() -> Unit)?) {
    PageScaffold("Motion", "MOTION", "Settings", back ?: {}) {
        Group("Animations", "How everything moves. Each animation can be retimed in Expert for now.") {
            for (p in Design.motionPresets()) CheckRow(p.name, null, p.id == Design.motionPresetId) { Design.setMotion(p.id) }
        }
    }
}

@Composable
private fun LayoutsPage(back: (() -> Unit)?) {
    PageScaffold("Layouts", "LAYOUTS", "Settings", back ?: {}) {
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
}

@Composable
private fun PhonePage(back: (() -> Unit)?) {
    val ctx = LocalContext.current
    PageScaffold("Phone", "PHONE", "Settings", back ?: {}) {
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
    }
}

// ------------------------------------------------------------------ the parts

/** How many rows a group has composed (each row learns whether it is the first). */
private class RowCounter { var n = 0 }

private val LocalRowCounter = androidx.compose.runtime.compositionLocalOf<RowCounter?> { null }

/** A page: its title (and the way back), its groups, scrolling under the status bar. */
@Composable
private fun PageScaffold(title: String, key: String, back: String?, onBack: () -> Unit, content: @Composable () -> Unit) {
    val look = LocalLook.current
    val page = LocalPage.current
    val scroll = page.scroll
    // Its groups rise in the first time the page opens, not again when it is come back to.
    page.groups = 0
    androidx.compose.runtime.LaunchedEffect(Unit) { page.arrived = true }
    val margin = look.dp(SettingsTokens.MARGIN)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val statusPx = WindowInsets.statusBars.getTop(density).toFloat()
    val navPx = WindowInsets.navigationBars.getBottom(density).toFloat()
    val barPx = statusPx + BAR_PT * look.unit
    var titleH by remember { mutableStateOf(1f) }
    val swipeBack = LocalSwipeBack.current
    Box(Modifier.fillMaxSize().then(if (back != null && swipeBack != null) Modifier.swipeBack(swipeBack) else Modifier)) {
        Column(Modifier.fillMaxSize().iosScroll(scroll).padding(horizontal = margin)) {
            Spacer(Modifier.height(look.dp(barPx)))
            // The large title; pulled down past the top it grows a little (iOS), from its left edge.
            BasicText(title, style = look.text(SettingsTokens.LARGE_TITLE, look.color(SettingsTokens.LABEL)),
                modifier = Modifier
                    .sharedTitle("title-$key")
                    .onSizeChanged { titleH = it.height.toFloat() }
                    .graphicsLayer {
                        val pull = (-scroll.offset).coerceAtLeast(0f)
                        val s = 1f + 0.12f * (pull / (pull + 600f))
                        scaleX = s; scaleY = s
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 1f)
                        // It slides under the bar as the page scrolls up, fading as the compact title takes over.
                        alpha = 1f - ((scroll.offset - titleH * 0.35f) / (titleH * 0.5f)).coerceIn(0f, 1f)
                    }
                    .padding(top = look.dp(4f * look.unit), bottom = look.dp(12f * look.unit)))
            content()
            Spacer(Modifier.height(look.dp(32f * look.unit + navPx)))
        }
        TopBar(title, back, onBack, scroll, barPx, statusPx, titleH)
    }
}

/**
 * A rightward drag that starts more sideways than up or down pulls the page back ([SwipeBack]); its fraction is the
 * finger's travel over the page's width.
 */
private fun Modifier.swipeBack(sb: SwipeBack): Modifier = pointerInput(sb) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var x = 0f
        val first = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
            if (over > 0f) { change.consume(); x = over }   // rightwards only: a leftward drag is not ours
        } ?: return@awaitEachGesture
        if (!sb.start()) return@awaitEachGesture
        val w = size.width.toFloat().coerceAtLeast(1f)
        horizontalDrag(first.id) { change ->
            tracker.addPosition(change.uptimeMillis, change.position)
            x += change.positionChange().x
            sb.move(x / w)
            change.consume()
        }
        sb.end(tracker.calculateVelocity().x / w)
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

/** A page's bar's height below the status bar (pt: iOS's navigation bar). */
private const val BAR_PT = 44f

/**
 * The top of a page: content scrolling up dissolves into the page's own backdrop (iOS 26's soft scroll edge, no hard bar),
 * the large title's compact twin rises into its centre, the way back sits at its left.
 */
@Composable
private fun TopBar(title: String, back: String?, onBack: () -> Unit, scroll: IosScrollState, barPx: Float, statusPx: Float, titleH: Float) {
    val look = LocalLook.current
    val origin = LocalPageOrigin.current
    val fadePx = 28f * look.unit
    val edge = (scroll.offset / (12f * look.unit)).coerceIn(0f, 1f)
    val compact = ((scroll.offset - titleH * 0.55f) / (titleH * 0.35f)).coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(look.dp(barPx + fadePx))) {
        Canvas(Modifier.fillMaxSize()) {
            if (edge <= 0.001f) return@Canvas
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                val save = nc.saveLayerAlpha(0f, 0f, size.width, size.height, (255 * edge).toInt())
                look.drawBackdrop(nc, origin.x, origin.y)
                // Solid under the bar, fading to nothing below it: rows dissolve as they go under.
                val mask = android.graphics.Paint().apply {
                    shader = android.graphics.LinearGradient(0f, barPx - 6f * look.unit, 0f, size.height,
                        android.graphics.Color.BLACK, android.graphics.Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP)
                    xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
                }
                nc.drawRect(0f, barPx - 6f * look.unit, size.width, size.height, mask)
                nc.restoreToCount(save)
            }
        }
        val barH = look.dp(barPx - statusPx)
        Box(Modifier.fillMaxWidth().padding(top = look.dp(statusPx)).height(barH), contentAlignment = Alignment.Center) {
            BasicText(title, style = look.text(TextKey("sys.type.headline"), look.color(SettingsTokens.LABEL)),
                modifier = Modifier.graphicsLayer { alpha = compact; translationY = (1f - compact) * 10f * look.unit })
        }
        if (back != null) {
            // The way back: the page before's title, shrunk into the bar beside a chevron (it flies back up on the way back).
            Row(Modifier.padding(top = look.dp(statusPx), start = look.dp(SettingsTokens.MARGIN)).height(barH)
                    .pointerInput(Unit) { detectTapGestures(onTap = { onBack() }) },
                verticalAlignment = Alignment.CenterVertically) {
                val style = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ACCENT))
                BasicText("‹ ", style = style)
                BasicText(back, style = style, modifier = Modifier.sharedTitle("title-START"))
            }
        }
    }
}



/** A group of rows on one glass card, with an optional heading and a note below it. */
@Composable
private fun Group(title: String?, note: String? = null, rows: @Composable () -> Unit) {
    val look = LocalLook.current
    val pad = look.dp(SettingsTokens.ROW_PADDING)
    // The first time the page opens, each group rises into place a beat after the one above it.
    val page = LocalPage.current
    val index = remember { page.groups++ }
    val k = remember { androidx.compose.animation.core.Animatable(if (page.arrived) 1f else 0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (k.value < 1f) {
            kotlinx.coroutines.delay(60L + index * 45L)
            k.animateTo(1f, look.motion(MotionTokens.APPEAR))
        }
    }
    val rise = Modifier.graphicsLayer {
        val v = k.value
        alpha = v.coerceIn(0f, 1f)
        translationY = (1f - v) * 28f * look.unit
        val sc = 0.96f + 0.04f * v
        scaleX = sc; scaleY = sc
    }
    if (title != null) {
        BasicText(title.uppercase(), style = look.text(SettingsTokens.SECTION_TITLE, look.color(SettingsTokens.SECTION_COLOR)),
            modifier = rise.padding(start = pad, bottom = look.dp(6f * look.unit)))
    }
    // Rows count themselves as they are first composed: the separator goes above every row but the first.
    val counter = remember { RowCounter() }
    counter.n = 0
    Column(rise.fillMaxWidth().glass(SettingsTokens.CARD, Design.num(SettingsTokens.CARD_CORNER))) {
        androidx.compose.runtime.CompositionLocalProvider(LocalRowCounter provides counter) { rows() }
    }
    if (note != null) {
        BasicText(note, style = look.text(SettingsTokens.ROW_DETAIL, look.color(SettingsTokens.ROW_DETAIL_COLOR)),
            modifier = rise.padding(start = pad, end = pad, top = look.dp(6f * look.unit)))
    }
    Spacer(Modifier.height(look.dp(24f * look.unit)))
}

/** One row: a name, an optional detail below it, something at its end; pressed, it lights up. */
@Composable
private fun BaseRow(title: String, detail: String?, onClick: (() -> Unit)?, titleKey: String? = null, end: @Composable () -> Unit) {
    val look = LocalLook.current
    val counter = LocalRowCounter.current
    val first = remember { counter == null || counter.n++ == 0 }
    var pressed by remember { mutableStateOf(false) }
    val press by animateFloatAsState(if (pressed) 1f else 0f, look.motion(if (pressed) MotionTokens.PRESS_IN else MotionTokens.PRESS_OUT), label = "press")
    val pad = look.dp(SettingsTokens.ROW_PADDING)
    Row(
        Modifier.fillMaxWidth().heightIn(min = look.dp(SettingsTokens.ROW_HEIGHT))
            .drawBehind {
                if (press > 0.001f) drawRect(Color(Appearance.pressFill).copy(alpha = Color(Appearance.pressFill).alpha * press))
                // Between rows: a separator above each but the first (inset by the padding, as iOS's).
                if (!first) drawRect(look.color(SettingsTokens.ROW_SEPARATOR), Offset(pad.toPx(), 0f), Size(size.width - pad.toPx(), 1f))
            }
            .then(if (onClick == null) Modifier else Modifier.pointerInput(onClick) {
                rowTap({ pressed = it }, onClick)
            })
            .padding(horizontal = pad, vertical = look.dp(10f * look.unit)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(title, style = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_LABEL)),
                modifier = if (titleKey != null) Modifier.sharedTitle("title-$titleKey") else Modifier)
            if (detail != null) BasicText(detail, style = look.text(SettingsTokens.ROW_DETAIL, look.color(SettingsTokens.ROW_DETAIL_COLOR)))
        }
        Spacer(Modifier.width(look.dp(12f * look.unit)))
        end()
    }
}

@Composable
private fun NavRow(title: String, detail: String?, page: String? = null, onClick: () -> Unit) = BaseRow(title, detail, onClick, page) {
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
        val track = Color(
            red = offC.red + (onC.red - offC.red) * t, green = offC.green + (onC.green - offC.green) * t,
            blue = offC.blue + (onC.blue - offC.blue) * t, alpha = offC.alpha + (onC.alpha - offC.alpha) * t,
        )
        drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2f))
        val inset = size.height * 0.08f
        val kw = size.width * 0.6f - inset * 2f
        val kh = size.height - inset * 2f
        val x = inset + (size.width - kw - inset * 2f) * k
        drawRoundRect(Color.Black.copy(alpha = 0.12f), Offset(x, inset + kh * 0.06f), Size(kw, kh), CornerRadius(kh / 2f))
        drawRoundRect(knob, Offset(x, inset), Size(kw, kh), CornerRadius(kh / 2f))
    }
}
