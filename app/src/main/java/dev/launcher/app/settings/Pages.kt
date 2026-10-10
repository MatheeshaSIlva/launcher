package dev.launcher.app.settings

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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

/** The settings app: a stack of pages over the blurred wallpaper, each change applied at once. */
@Composable
fun SettingsApp(onClose: () -> Unit) {
    SettingsTheme {
        val look = LocalLook.current
        var stack by remember { mutableStateOf(listOf(Page.START)) }
        BackHandler(enabled = stack.size > 1) { stack = stack.dropLast(1) }
        Box(Modifier.fillMaxSize().drawBehind { drawIntoCanvas { look.drawBackdrop(it.nativeCanvas) } }) {
            AnimatedContent(
                targetState = stack,
                contentKey = { it.last() },
                transitionSpec = {
                    val forward = targetState.size > initialState.size
                    (slideInHorizontally(look.motion(MotionTokens.NAV_PUSH)) { w -> if (forward) w else -w / 3 } + fadeIn(look.motion(MotionTokens.NAV_PUSH)))
                        .togetherWith(slideOutHorizontally(look.motion(MotionTokens.NAV_PUSH)) { w -> if (forward) -w / 3 else w } + fadeOut(look.motion(MotionTokens.NAV_PUSH)))
                },
                label = "pages",
            ) { s ->
                val back: (() -> Unit)? = if (s.size > 1) ({ stack = s.dropLast(1) }) else null
                val push: (Page) -> Unit = { stack = s + it }
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

// ------------------------------------------------------------------ the pages

@Composable
private fun StartPage(push: (Page) -> Unit) {
    val ctx = LocalContext.current
    PageScaffold("Settings", back = null, onBack = {}) {
        YourSetup()
        Group(null) {
            NavRow("Layouts", "What each part of the phone is and where it lives") { push(Page.LAYOUTS) }
            NavRow("Look", "Theme: ${Design.themeName}" + if (Design.wallpaperColors) ", wallpaper colours" else "") { push(Page.LOOK) }
            NavRow("Motion", "Animations: ${Design.motionName}") { push(Page.MOTION) }
        }
        Group(null) {
            NavRow("Phone", "Shizuku, safety, updates") { push(Page.PHONE) }
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
    PageScaffold("Look", "Settings", back ?: {}) {
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
    PageScaffold("Motion", "Settings", back ?: {}) {
        Group("Animations", "How everything moves. Each animation can be retimed in Expert for now.") {
            for (p in Design.motionPresets()) CheckRow(p.name, null, p.id == Design.motionPresetId) { Design.setMotion(p.id) }
        }
    }
}

@Composable
private fun LayoutsPage(back: (() -> Unit)?) {
    PageScaffold("Layouts", "Settings", back ?: {}) {
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
    PageScaffold("Phone", "Settings", back ?: {}) {
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
private fun PageScaffold(title: String, back: String?, onBack: () -> Unit, content: @Composable () -> Unit) {
    val look = LocalLook.current
    val margin = look.dp(SettingsTokens.MARGIN)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = margin)
    ) {
        Spacer(Modifier.height(look.dp(8f * look.unit)))
        if (back != null) {
            BasicText("‹ $back", style = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ACCENT)),
                modifier = Modifier.pointerInput(Unit) { detectTapGestures(onTap = { onBack() }) }.padding(vertical = look.dp(6f * look.unit)))
        }
        BasicText(title, style = look.text(SettingsTokens.LARGE_TITLE, look.color(SettingsTokens.LABEL)),
            modifier = Modifier.padding(top = look.dp(4f * look.unit), bottom = look.dp(12f * look.unit)))
        content()
        Spacer(Modifier.height(look.dp(32f * look.unit)))
    }
}

/** A group of rows on one glass card, with an optional heading and a note below it. */
@Composable
private fun Group(title: String?, note: String? = null, rows: @Composable () -> Unit) {
    val look = LocalLook.current
    val pad = look.dp(SettingsTokens.ROW_PADDING)
    if (title != null) {
        BasicText(title.uppercase(), style = look.text(SettingsTokens.SECTION_TITLE, look.color(SettingsTokens.SECTION_COLOR)),
            modifier = Modifier.padding(start = pad, bottom = look.dp(6f * look.unit)))
    }
    // Rows count themselves as they are first composed: the separator goes above every row but the first.
    val counter = remember { RowCounter() }
    counter.n = 0
    Column(Modifier.fillMaxWidth().glass(SettingsTokens.CARD, Design.num(SettingsTokens.CARD_CORNER))) {
        androidx.compose.runtime.CompositionLocalProvider(LocalRowCounter provides counter) { rows() }
    }
    if (note != null) {
        BasicText(note, style = look.text(SettingsTokens.ROW_DETAIL, look.color(SettingsTokens.ROW_DETAIL_COLOR)),
            modifier = Modifier.padding(start = pad, end = pad, top = look.dp(6f * look.unit)))
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
    Row(
        Modifier.fillMaxWidth().heightIn(min = look.dp(SettingsTokens.ROW_HEIGHT))
            .drawBehind {
                if (press > 0.001f) drawRect(Color(Appearance.pressFill).copy(alpha = Color(Appearance.pressFill).alpha * press))
                // Between rows: a separator above each but the first (inset by the padding, as iOS's).
                if (!first) drawRect(look.color(SettingsTokens.ROW_SEPARATOR), Offset(pad.toPx(), 0f), Size(size.width - pad.toPx(), 1f))
            }
            .then(if (onClick == null) Modifier else Modifier.pointerInput(onClick) {
                detectTapGestures(onPress = { pressed = true; tryAwaitRelease(); pressed = false }, onTap = { onClick() })
            })
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
