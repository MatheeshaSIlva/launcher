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
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
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
import dev.launcher.app.motion.mover
import dev.launcher.app.theme.Appearance
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.layout.positionInParent
import androidx.compose.animation.core.VectorConverter
import dev.launcher.app.design.MaterialKey
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.composed
import androidx.compose.ui.zIndex
import androidx.compose.animation.animateContentSize

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
    /** 1: the overview is glass over the blurred wallpaper (iOS 27's Liquid Glass); 0: coloured tiles on a solid sheet. */
    val GLASS = NumberKey("comp.settings.glass")
    val TILE_MATERIAL = MaterialKey("comp.settings.tile.material")
    val SEGMENT_TRACK = ColorKey("comp.settings.segment.track")
    val SEGMENT_THUMB = ColorKey("comp.settings.segment.thumb")
    val SEGMENT_HEIGHT = NumberKey("comp.settings.segment.height")
    val SEGMENT_TEXT = TextKey("comp.settings.segment.text")
    val SEGMENT_SYMBOL = NumberKey("comp.settings.segment.symbol")
    val SEGMENT_CORNER = NumberKey("comp.settings.segment.corner")
    /** The least a control's touch area is, either way (Apple: 44 pt; Android: 48 dp). */
    val TARGET = NumberKey("comp.settings.target")
    val CHIP_HEIGHT = NumberKey("comp.settings.chip.height")
    val CHIP_MATERIAL = MaterialKey("comp.settings.chip.material")
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
    val ACCENT = ColorKey("comp.settings.accent")
    val SCRIM = ColorKey("comp.settings.scrim")
    val FIELD_FILL = ColorKey("comp.settings.field.fill")
    val FIELD_PLACEHOLDER = ColorKey("comp.settings.field.placeholder-color")
    val FIELD_HEIGHT = NumberKey("comp.settings.field.height")
    val ACCENT_LABEL = ColorKey("comp.settings.accent-label")
}

/** The parts of the settings, a tile each: its name, its colour and its symbol. */
private enum class Part(val title: String, val color: ColorKey, val symbol: Int) {
    LAYOUTS("Layouts", ColorKey("comp.settings.color.layouts"), R.drawable.sym_grid),
    LOOK("Look", ColorKey("comp.settings.color.look"), R.drawable.sym_palette),
    MOTION("Motion", ColorKey("comp.settings.color.motion"), R.drawable.sym_spring),
    PHONE("Phone", ColorKey("comp.settings.color.phone"), R.drawable.sym_shield),
}

/** What a part holds (with the changes waiting to be applied), in a line under its name on the tile. */
private fun Part.detail(d: Draft): String = when (this) {
    Part.LAYOUTS -> "${Element.entries.size} parts of the phone"
    Part.LOOK -> themeName(d.themeId()) + if (d.wallpaperColors()) " · wallpaper colours" else ""
    Part.MOTION -> presetName(d.motionId())
    Part.PHONE -> if (dev.launcher.app.ShizukuLink.service != null) "Shizuku connected" else "Shizuku not running"
}

private fun themeName(id: String) = Design.themes().firstOrNull { it.id == id }?.name ?: id
private fun presetName(id: String) = Design.motionPresets().firstOrNull { it.id == id }?.name ?: id

/**
 * The settings' changes, waiting for Apply (Matheesha: every change applied at once played the look's reveal again and
 * again). The rows show the draft over what is applied; Apply applies it all as one ([Design.batch]: one reveal), Discard
 * forgets it. Kept while the settings are open (going home and coming back keeps it).
 */
@Stable
private class Draft {
    var theme by mutableStateOf<String?>(null)
    var colors by mutableStateOf<String?>(null)
    var motion by mutableStateOf<String?>(null)
    var appearance by mutableStateOf<Appearance.Mode?>(null)
    /** An element's own theme (a theme id, or [FOLLOW]). */
    val elements = androidx.compose.runtime.mutableStateMapOf<Element, String>()
    /** A layout's choices: (element, "layout" | "placement" | "opt:" + an option's key) to the value. */
    val setup = androidx.compose.runtime.mutableStateMapOf<Pair<Element, String>, Any>()
    /** A font family chosen for a font token (`sys.font.text`, `sys.font.display`). */
    val fonts = androidx.compose.runtime.mutableStateMapOf<String, String>()
    /** Material You's style and intensity (see [dev.launcher.app.design.MaterialYou]). */
    var colorStyle by mutableStateOf<String?>(null)
    var colorIntensity by mutableStateOf<Float?>(null)
    /** What the palette is made from ("wallpaper" or "#rrggbb") and key colours set by hand per palette ("auto" or "#rrggbb"). */
    var colorSeed by mutableStateOf<String?>(null)
    val colorKeys = androidx.compose.runtime.mutableStateMapOf<Char, String>()
    /** Whether sizes follow the phone's Display size and Font size ("adaptive") or are the user's own ("custom"). */
    var scalePolicy by mutableStateOf<String?>(null)
    /** The user's own size of everything and of text (factors of an iPhone's; used when not following the phone). */
    var sizeFactor by mutableStateOf<Float?>(null)
    var textFactor by mutableStateOf<Float?>(null)
    /** When Back was pressed with changes waiting (a second Back soon after discards them and leaves); 0: not. */
    var backAt by androidx.compose.runtime.mutableLongStateOf(0L)

    val count: Int get() = listOfNotNull(theme, colors, motion, appearance, colorStyle, colorIntensity, colorSeed, scalePolicy, sizeFactor, textFactor).size +
        elements.size + setup.size +
        fonts.size + colorKeys.size

    fun themeId() = theme ?: Design.themeId
    fun chooseTheme(id: String) { theme = id.takeIf { it != Design.themeId } }
    fun wallpaperColors() = colors?.let { it == "wallpaper" } ?: Design.wallpaperColors
    fun chooseColors(on: Boolean) { colors = (if (on) "wallpaper" else "theme").takeIf { on != Design.wallpaperColors } }
    fun motionId() = motion ?: Design.motionPresetId
    fun chooseMotion(id: String) { motion = id.takeIf { it != Design.motionPresetId } }
    fun appearanceMode() = appearance ?: Appearance.mode
    fun chooseAppearance(m: Appearance.Mode) { appearance = m.takeIf { it != Appearance.mode } }
    /** The theme [e] follows of its own (null: the main one). */
    fun elementTheme(e: Element): String? = if (e in elements) elements[e].takeIf { it != FOLLOW } else Design.elementTheme(e)
    fun chooseElement(e: Element, id: String?) {
        if (id == Design.elementTheme(e)) elements.remove(e) else elements[e] = id ?: FOLLOW
    }
    fun layout(e: Element): String = setup[e to "layout"] as? String ?: Setup.layout(e).id
    fun chooseLayout(e: Element, id: String) = choose(e, "layout", id, Setup.layout(e).id)
    fun placement(e: Element): String = setup[e to "placement"] as? String ?: Setup.placement(e).id
    fun choosePlacement(e: Element, id: String) = choose(e, "placement", id, Setup.placement(e).id)
    fun bool(e: Element, key: String, fallback: Boolean): Boolean = setup[e to "opt:$key"] as? Boolean ?: Setup.bool(e, key, fallback)
    fun chooseBool(e: Element, key: String, v: Boolean, fallback: Boolean) = choose(e, "opt:$key", v, Setup.bool(e, key, fallback))
    fun int(e: Element, key: String, fallback: Int): Int = setup[e to "opt:$key"] as? Int ?: Setup.int(e, key, fallback)
    fun chooseInt(e: Element, key: String, v: Int, fallback: Int) = choose(e, "opt:$key", v, Setup.int(e, key, fallback))
    private fun choose(e: Element, what: String, v: Any, applied: Any) { if (v == applied) setup.remove(e to what) else setup[e to what] = v }
    fun colorStyle(): dev.launcher.app.design.MaterialYou.Style = dev.launcher.app.design.MaterialYou.Style.of(colorStyle ?: appliedStyle())
    fun chooseColorStyle(s: dev.launcher.app.design.MaterialYou.Style) { colorStyle = s.id.takeIf { it != appliedStyle() } }
    fun colorIntensity(): Float = colorIntensity ?: appliedIntensity()
    fun chooseColorIntensity(k: Float) { colorIntensity = k.takeIf { kotlin.math.abs(it - appliedIntensity()) > 0.005f } }
    fun scalePolicy(): String = scalePolicy ?: appliedChoice(dev.launcher.app.design.Scale.POLICY.name, "adaptive")
    fun followsPhoneSize(): Boolean = scalePolicy() == "adaptive"
    /** Follow the phone, or the user's own sizes (as they were set: 100 % is an iPhone's, Matheesha's "proper size"). */
    fun chooseFollowsPhoneSize(on: Boolean) {
        choosePolicy(if (on) "adaptive" else "custom")
        if (on) { sizeFactor = null; textFactor = null }   // the phone's sizes: the user's own wait as they were
    }
    private fun choosePolicy(v: String) {
        scalePolicy = v.takeIf { it != appliedChoice(dev.launcher.app.design.Scale.POLICY.name, "adaptive") }
    }
    /** The size of everything as it would be applied: the phone's Display size when following it, else the user's own. */
    fun size(ctx: android.content.Context): Float =
        if (followsPhoneSize()) dev.launcher.app.design.Scale.phoneZoom(ctx) else sizeFactor ?: dev.launcher.app.design.Scale.sizeFactor()
    fun text(ctx: android.content.Context): Float =
        if (followsPhoneSize()) dev.launcher.app.design.Scale.phoneText(ctx) else textFactor ?: dev.launcher.app.design.Scale.textFactor()
    /** A size of the user's own; moved while following the phone, the other one starts where the phone has it (nothing jumps). */
    fun chooseSize(ctx: android.content.Context, v: Float) {
        if (followsPhoneSize()) { chooseText0(snap(dev.launcher.app.design.Scale.phoneText(ctx))); choosePolicy("custom") }
        sizeFactor = v.takeIf { kotlin.math.abs(it - dev.launcher.app.design.Scale.sizeFactor()) > 0.001f }
    }
    fun chooseText(ctx: android.content.Context, v: Float) {
        if (followsPhoneSize()) { sizeFactor = snap(dev.launcher.app.design.Scale.phoneZoom(ctx)).takeIf { kotlin.math.abs(it - dev.launcher.app.design.Scale.sizeFactor()) > 0.001f }; choosePolicy("custom") }
        chooseText0(v)
    }
    /** One point in pixels on a screen [shortSidePx] across, as the draft's sizes would make it (the room a grid has). */
    fun unit(ctx: android.content.Context, shortSidePx: Int): Float = dev.launcher.app.design.Scale.unitFor(ctx, shortSidePx, scalePolicy(),
        sizeFactor ?: dev.launcher.app.design.Scale.sizeFactor())
    /** Home's own text size as the draft's sizes would make it ([dev.launcher.app.design.Scale.homeText]). */
    fun homeText(): Float = when (scalePolicy()) { "adaptive", "density" -> 1f; else -> textFactor ?: dev.launcher.app.design.Scale.textFactor() }
    private fun chooseText0(v: Float) { textFactor = v.takeIf { kotlin.math.abs(it - dev.launcher.app.design.Scale.textFactor()) > 0.001f } }
    /** The user's own colour the palette is made from (null: the wallpaper's), as it would be applied. */
    fun seedColor(): Int? = Design.hexColor(colorSeed ?: appliedChoice(Design.COLOR_SEED.name, "wallpaper"))
    fun chooseSeed(c: Int?) {
        val v = c?.let { Design.hexOf(it) } ?: "wallpaper"
        colorSeed = v.takeIf { it != appliedChoice(Design.COLOR_SEED.name, "wallpaper") }
    }
    /** Palette [p]'s key colour set by hand (null: the style's), as it would be applied. */
    fun keyColor(p: Char): Int? = Design.hexColor(colorKeys[p] ?: appliedChoice(Design.COLOR_KEYS.getValue(p).name, "auto"))
    fun chooseKey(p: Char, c: Int?) {
        val v = c?.let { Design.hexOf(it) } ?: "auto"
        if (v == appliedChoice(Design.COLOR_KEYS.getValue(p).name, "auto")) colorKeys.remove(p) else colorKeys[p] = v
    }
    /** Everything the palette would be made from once applied (the swatches show it before Apply). */
    fun youChoice() = dev.launcher.app.design.MaterialYou.Choice(colorStyle(), colorIntensity(), seedColor(),
        dev.launcher.app.design.MaterialYou.KEY_PALETTES.mapNotNull { (p, _) -> keyColor(p)?.let { p to it } }.toMap())
    private fun appliedChoice(key: String, default: String) =
        (try { Design.resolved(key) } catch (_: Throwable) { null } as? dev.launcher.app.design.Value.Choice)?.option ?: default
    private fun appliedStyle() = (try { Design.resolved(Design.COLOR_STYLE.name) } catch (_: Throwable) { null } as? dev.launcher.app.design.Value.Choice)?.option ?: "system"
    private fun appliedIntensity() = (try { Design.resolved(Design.COLOR_INTENSITY.name) } catch (_: Throwable) { null } as? dev.launcher.app.design.Value.Number)?.v ?: 1f
    /** The family font token [key] has (the draft's, else the one in use). */
    fun font(key: String): String = fonts[key] ?: appliedFont(key)
    fun chooseFont(key: String, family: String) { if (family == appliedFont(key)) fonts.remove(key) else fonts[key] = family }
    private fun appliedFont(key: String) = (try { Design.resolved(key) } catch (_: Throwable) { null } as? dev.launcher.app.design.Value.Choice)?.option ?: "inter"

    fun apply(ctx: android.content.Context) {
        val n = count
        if (n == 0) return
        Design.batch("$n change" + (if (n == 1) "" else "s") + " applied from the settings") {
            theme?.let { Design.setTheme(it) }
            colors?.let { Design.setColorSource(it) }
            for ((e, id) in elements) Design.setElementTheme(e, id.takeIf { it != FOLLOW })
            motion?.let { Design.setMotion(it) }
            for ((k, family) in fonts) Design.set(k, dev.launcher.app.design.Value.Choice(family))
            colorStyle?.let { Design.set(Design.COLOR_STYLE.name, dev.launcher.app.design.Value.Choice(it)) }
            colorIntensity?.let { Design.set(Design.COLOR_INTENSITY.name, dev.launcher.app.design.Value.Number(it, dev.launcher.app.design.NumUnit.FACTOR)) }
            colorSeed?.let { Design.set(Design.COLOR_SEED.name, dev.launcher.app.design.Value.Choice(it)) }
            scalePolicy?.let { Design.set(dev.launcher.app.design.Scale.POLICY.name, dev.launcher.app.design.Value.Choice(it)) }
            sizeFactor?.let { Design.set(dev.launcher.app.design.Scale.SIZE.name, dev.launcher.app.design.Value.Number(it, dev.launcher.app.design.NumUnit.FACTOR)) }
            textFactor?.let { Design.set(dev.launcher.app.design.Scale.TEXT.name, dev.launcher.app.design.Value.Number(it, dev.launcher.app.design.NumUnit.FACTOR)) }
            for ((p, v) in colorKeys) Design.set(Design.COLOR_KEYS.getValue(p).name, dev.launcher.app.design.Value.Choice(v))
        }
        appearance?.let { Appearance.setMode(ctx, it) }
        for (e in setup.keys.map { it.first }.distinct()) {
            val opts = setup.filterKeys { it.first == e && it.second.startsWith("opt:") }.mapKeys { it.key.second.removePrefix("opt:") }
            Setup.set(e, layout = setup[e to "layout"] as? String, placement = setup[e to "placement"] as? String, options = opts)
        }
        discard()
    }

    fun discard() {
        theme = null; colors = null; motion = null; appearance = null
        elements.clear(); setup.clear(); fonts.clear(); colorStyle = null; colorIntensity = null; colorSeed = null; colorKeys.clear(); scalePolicy = null; sizeFactor = null; textFactor = null; backAt = 0L
    }

    companion object {
        const val FOLLOW = "follow"
        const val BACK_AGAIN_MS = 3000L
        /** A size on the sliders' steps (5 %). */
        fun snap(v: Float) = kotlin.math.round(v * 20f) / 20f
    }
}

private val LocalDraft = staticCompositionLocalOf { Draft() }

/** Home as it showed in each theme (kept whenever the settings take a picture): a theme shows before it is applied. */
private object HomePictures {
    val byTheme = androidx.compose.runtime.mutableStateMapOf<String, android.graphics.Bitmap>()
    private var loaded = false

    private fun dir(ctx: android.content.Context) = java.io.File(ctx.filesDir, "design/home-pictures")

    /**
     * The last picture of home in every theme, from disk (off the main thread): after the app restarts there is no home
     * to picture until home shows (the settings showed the wallpaper alone), and theme cards keep their pictures.
     */
    suspend fun load(ctx: android.content.Context) {
        if (loaded) return
        loaded = true
        val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            (dir(ctx).listFiles() ?: emptyArray()).filter { it.name.endsWith(".webp") }.mapNotNull { f ->
                val b = try { android.graphics.BitmapFactory.decodeFile(f.path) } catch (_: Throwable) { null } ?: return@mapNotNull null
                // On the GPU before it is shown (a software bitmap is uploaded inside its first frame).
                f.name.removeSuffix(".webp") to (b.copy(android.graphics.Bitmap.Config.HARDWARE, false) ?: b)
            }
        }
        for ((id, b) in found) if (id !in byTheme) byTheme[id] = b
    }

    /** Home's picture [home] with the status bar's [bar] across its top, drawn together on the GPU (any thread but main). */
    fun withBar(home: android.graphics.Bitmap, bar: android.graphics.Bitmap): android.graphics.Bitmap = SettingsEntry.withBar(home, bar)

    /**
     * [b] is home in theme [id] now: kept, and saved (at half size) off the main thread. Only a picture of one of home's pages
     * as they rest ([page]): the theme's card is home as it looked in it (opened from the App Library, the card showed
     * the App Library).
     */
    fun keep(ctx: android.content.Context, id: String, b: android.graphics.Bitmap, page: Boolean) {
        if (!page) return
        byTheme[id] = b
        val app = ctx.applicationContext
        Thread {
            try {
                val sw = b.copy(android.graphics.Bitmap.Config.ARGB_8888, false) ?: return@Thread
                val half = android.graphics.Bitmap.createScaledBitmap(sw, sw.width / 2, sw.height / 2, true)
                val d = dir(app).apply { mkdirs() }
                val tmp = java.io.File(d, "$id.webp.tmp")
                tmp.outputStream().use { half.compress(android.graphics.Bitmap.CompressFormat.WEBP_LOSSY, 85, it) }
                tmp.renameTo(java.io.File(d, "$id.webp"))
                if (half !== sw) half.recycle()
                sw.recycle()
            } catch (t: Throwable) { dev.launcher.app.AppLog.log("[settings] keeping home's picture failed: ${t.message}") }
        }.start()
    }
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
private class Studio(
    /** Home sliding in (and back out): the picture, the sheet and the title move with it. */
    val entry: EntryState,
) {
    var open by mutableStateOf<Part?>(null)
    val p = Animatable(0f)
    /** Each tile's bounds in the sheet: where its page grows from and goes back to. */
    val tiles = HashMap<Part, Rect>()
    /** How far each tile is pressed in (the growing one starts from the size it sank to); read in drawing only. */
    val press = Part.entries.associateWith { Animatable(0f) }
    var sheet: LayoutCoordinates? = null
    /** How many pages are built (ahead of time, one a frame, so opening one never builds it). */
    var built by mutableIntStateOf(0)
    /** Each page's scroll (kept while the settings are open: a page comes back where it was). */
    val scrolls = Part.entries.associateWith { IosScrollState() }
    /** The element Layouts shows (chosen at its top: the picture focuses on it), each one's presence (0..1), and scroll. */
    var element by mutableStateOf(Element.STATUS_BAR)
    val elementShown = Element.entries.associateWith { Animatable(if (it == Element.STATUS_BAR) 1f else 0f) }
    /** The side each element slides to or from (-1 left, 1 right), set when it starts coming or going. */
    val elementSide = Element.entries.associateWith { androidx.compose.runtime.mutableFloatStateOf(1f) }
    val elementScrolls = Element.entries.associateWith { IosScrollState() }
}

private val LocalStudio = staticCompositionLocalOf<Studio?> { null }

/** A screen reader is on (the settings keep an accessibility tree only then). */
private val LocalScreenReader = androidx.compose.runtime.compositionLocalOf { false }

/** Where things are, in pixels: the sheet's two tops (at rest, a page open), the picture of home above it. */
private class Geometry(look: Look, val statusPx: Float, val navPx: Float) {
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

    /** The Apply bar, and the room the sheet makes for it at rest (it rises by that much while changes wait). */
    val barH = 56f * unit
    val barRoom = barH + 20f * unit
    val barTop = h - navPx - 12f * unit - barH

    /** The sheet's top: where the page puts it, lifted by [bar] (0..1, the Apply bar showing) while the tiles show. */
    fun top(p: Float, bar: Float = 0f) = restTop + (openTop - restTop) * p - bar * barRoom * (1f - p.coerceIn(0f, 1f))

    /** How far below its place the sheet waits while home slides in: off the screen, its shadow too. */
    val enterDrop = h - restTop + 48f * unit

    /**
     * Where home's picture, shrinking from the whole screen into its place at the tiles, leaves the status bar over the page
     * (0..1 of the slide): its top, at the slide's value times its top at rest, passes the bar's height.
     */
    val toneAt: Float = run {
        val availH = (restTop - previewTop - 22f * unit).coerceAtLeast(60f * unit)
        val availW = w - look.pt(SettingsTokens.MARGIN) * 2f
        val fh = minOf(availW * h / w, availH)
        val restY = previewTop + (availH - fh) / 2f
        (statusPx / restY.coerceAtLeast(1f)).coerceIn(0.05f, 0.9f)
    }
}

/** How far the page being composed is shown (0..1, read in drawing: the groups rise in with it). */
private val LocalShown = staticCompositionLocalOf<() -> Float> { { 1f } }

/** Counts a page's groups as they compose (each rises in a beat after the one above). */
private class GroupCounter { var n = 0 }

private val LocalGroups = staticCompositionLocalOf { GroupCounter() }

/**
 * The settings app, "studio" layout (docs/PLAN_SETTINGS.md, draft D): a picture of the phone above a sheet of tiles;
 * a tile grows into its page inside the sheet, which rises as the picture shrinks; back (the button, the system's Back)
 * shrinks the page into its tile. Changes wait for Apply ([Draft]). Every colour, surface, corner, font and motion is
 * the theme's.
 */
@Composable
fun SettingsApp(onClose: () -> Unit, beforeWallpaper: (first: Boolean) -> Unit = {}, entry: EntryState = remember { EntryState() },
                toHome: () -> Unit = onClose) {
    SettingsTheme(beforeWallpaper) {
        val look = LocalLook.current
        val st = remember(entry) { Studio(entry) }
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
        val draft = remember { Draft() }
        val bar = remember { Animatable(0f) }
        val ctx = LocalContext.current
        BackToTiles(st, close, draft, onClose, toHome)
        val fontSearch = remember { FontSearch() }
        val focus = androidx.compose.ui.platform.LocalFocusManager.current
        androidx.compose.runtime.SideEffect {
            entry.toneAt = g.toneAt
            // Coming from home again with a page (or the font search) left open: back at the tiles at once (unseen: home's
            // picture covers all).
            entry.reset = {
                st.open = null
                focus.clearFocus()
                scope.launch { st.p.snapTo(0f); fontSearch.closeNow() }
            }
        }
        // Home sliding in (and back out, Back on the tiles): one spring moves home's picture between the whole screen and its
        // place, the sheet up from below the screen and the title in (EntryState). The status bar keeps home's tone while the
        // picture is under it.
        LaunchedEffect(entry) {
            androidx.compose.runtime.snapshotFlow { entry.runs }.collectLatest { n ->
                if (n == 0) return@collectLatest
                val to = entry.target
                // A jump: the slide that played (if any) is cancelled by now; where it is was set with it.
                if (entry.instant) { entry.enter.floatValue = to; entry.velocity = 0f; return@collectLatest }
                val done = entry.done
                entry.done = null
                var home = entry.enter.floatValue < entry.toneAt
                entry.moving = true
                // For the log: its frames as the main thread drew them (what the screen showed: tools/framestats.py).
                val t0 = android.os.SystemClock.uptimeMillis()
                var frames = 0
                var last = 0L
                var longest = 0L
                var longestAt = 0f
                try {
                    // (From where it is and how fast it moves: Back while home slides in turns it round, no jump.)
                    androidx.compose.animation.core.animate(entry.enter.floatValue, to, entry.velocity, spec.value) { v, speed ->
                        entry.velocity = speed
                        val now = android.os.SystemClock.uptimeMillis()
                        if (last != 0L && now - last > longest) { longest = now - last; longestAt = v }
                        last = now
                        frames++
                        entry.enter.floatValue = v
                        val h = v < entry.toneAt
                        if (h != home) { home = h; entry.onTone?.invoke(h) }
                    }
                } finally { entry.moving = false }
                entry.velocity = 0f
                dev.launcher.app.AppLog.log("[settings] home slid ${if (to >= 1f) "in" else "out"}: $frames frames in ${last - t0} ms, " +
                    "longest $longest ms (at ${"%.2f".format(longestAt)})")
                if (to >= 1f) entry.picture = null   // taken in by the picture of home by now
                entry.onRest?.invoke()
                done?.invoke()
            }
        }
        // The pages are built ahead, one a frame, once the app has come up; then the font search's sheet, while nothing moves
        // (built in a page's opening it would cost that opening a frame).
        var sheetBuilt by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(350)
            // Not while home slides in: a page built in one of its frames would cost it that frame.
            androidx.compose.runtime.snapshotFlow { entry.moving || entry.enter.floatValue < 1f }.first { !it }
            while (st.built < Part.entries.size) {
                androidx.compose.runtime.withFrameNanos { }
                st.built++
            }
            androidx.compose.runtime.snapshotFlow { st.p.isRunning }.first { !it }
            androidx.compose.runtime.withFrameNanos { }
            sheetBuilt = true
        }
        // While a page moves (its spring, or a finger), the settings leave the accessibility tree: with an accessibility
        // service on (gesture nav is one), Compose re-reads the whole tree every 100 ms while layers move (7-25 ms frames
        // on the S24; none without the tree). At rest it is all there again, for whatever reads it.
        // Scrolling counts too: the rest of the tree alone still cost 4-5 ms a check while a page scrolled on the S24.
        val moving by remember {
            androidx.compose.runtime.derivedStateOf {
                st.p.isRunning || bar.isRunning || entry.moving || st.scrolls.values.any { it.moving } ||
                    st.elementShown.values.any { it.isRunning } || st.elementScrolls.values.any { it.moving }
            }
        }
        // And the tree is there at all only for a screen reader (TalkBack): taking it away at every scroll's start and
        // giving it back at its end cost 9-15 ms of the main thread each time (the first frames of a scroll on the S24),
        // for services that do not read it (gesture nav's; the S24's own).
        val reader = screenReaderOn()
        val noTree = remember { Modifier.clearAndSetSemantics { } }
        CompositionLocalProvider(LocalDraft provides draft, LocalStudio provides st, LocalScreenReader provides reader, LocalFontSearch provides fontSearch) {
            // The page behind everything: the theme's colour, or (glass) the wallpaper blurred under the appearance's veil.
            val glass = Design.num(SettingsTokens.GLASS) > 0.5f
            val backdrop = if (glass) Modifier.drawBehind { drawIntoCanvas { look.drawBackdrop(it.nativeCanvas) } } else Modifier.background(look.color(SettingsTokens.BACKGROUND))
            // (Without a screen reader the tree is off for good: "moving" is not read, so nothing here composes again when
            // something starts or stops moving.)
            Box(Modifier.fillMaxSize().then(if (!reader || moving) noTree else Modifier).then(backdrop)) {
                // Under the font search's sheet only what it leaves in sight is drawn (drawn whole, the settings beneath a
                // sheet that covered them took the GPU 7-10 ms a frame on the S24).
                Box(Modifier.fillMaxSize().drawWithContent {
                    val covered = fontSearch.coveredFrom()
                    if (covered < size.height) clipRect(bottom = covered.coerceAtLeast(0f)) { this@drawWithContent.drawContent() }
                    else drawContent()
                }) {
                    HomePicture(st, g, bar)
                    Header(st, g) { close(0f) }
                    Sheet(st, g, bar, open, close)
                    ApplyBar(draft, g, bar, st.entry) { draft.apply(ctx) }
                }
                // Built after the pages (the first opening builds nothing).
                if (sheetBuilt) FontSheet(fontSearch)
            }
        }
    }
}

/** A screen reader (touch exploration: TalkBack) is on, as it changes. */
@Composable
private fun screenReaderOn(): Boolean {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(android.view.accessibility.AccessibilityManager::class.java) }
    var on by remember { mutableStateOf(am?.isTouchExplorationEnabled == true) }
    androidx.compose.runtime.DisposableEffect(am) {
        val l = android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener { on = it }
        am?.addTouchExplorationStateChangeListener(l)
        onDispose { am?.removeTouchExplorationStateChangeListener(l) }
    }
    return on
}

/**
 * The system's Back closes the open page; on the tiles with changes waiting, it first says so (the bar), and a second Back
 * soon after discards them and leaves. Its own composable: only it changes when a page opens.
 */
@Composable
private fun BackToTiles(st: Studio, close: (Float) -> Unit, draft: Draft, leave: () -> Unit, toHome: () -> Unit) {
    BackHandler(enabled = st.open != null) { close(0f) }
    BackHandler(enabled = st.open == null && draft.count > 0) {
        val now = android.os.SystemClock.uptimeMillis()
        if (draft.backAt != 0L && now - draft.backAt < Draft.BACK_AGAIN_MS) { draft.discard(); if (st.entry.fromHome) toHome() else leave() }
        else draft.backAt = now
    }
    // Opened from home's edit menu: back into home the way it came (home's picture growing back over the whole screen).
    BackHandler(enabled = st.open == null && draft.count == 0 && st.entry.fromHome) { toHome() }
}

/**
 * What part of the phone the picture is about, as a band of the screen's height (top, bottom; always its full width): all
 * of it, or the part the element chosen on Layouts lives in. Matheesha: the elements were too small to see on the picture.
 */
private fun focusOf(e: Element?, placement: String, statusFrac: Float): Rect = when (e) {
    // The top of the screen: the bar at close to its real size.
    Element.STATUS_BAR -> Rect(0f, 0f, 1f, (statusFrac * 3f).coerceIn(0.08f, 0.16f))
    // Where it is pulled from.
    Element.SHADE -> Rect(0f, 0f, 1f, 0.22f)
    // The bottom edge the home swipe starts from.
    Element.RECENTS -> Rect(0f, 0.8f, 1f, 1f)
    Element.DRAWER -> when (placement) {
        "swipe-up" -> Rect(0f, 0.55f, 1f, 1f)
        else -> Rect(0f, 0f, 1f, 1f)
    }
    else -> Rect(0f, 0f, 1f, 1f)
}

/**
 * How element [e] is reached in [placement], as the finger's strokes on the phone (fractions of the screen: from, to), shown
 * one after another on the picture; [hold]: the finger rests at the end (the gesture is a hold). Null: nothing to show.
 */
private class Gesture(val strokes: List<Pair<Offset, Offset>>, val hold: Boolean = false)

private fun gestureOf(e: Element?, placement: String): Gesture? = when (e) {
    // Notifications from the top's left, controls from its right.
    Element.SHADE -> Gesture(listOf(Offset(0.25f, 0.005f) to Offset(0.25f, 0.17f), Offset(0.78f, 0.005f) to Offset(0.78f, 0.17f)))
    // Up from the bottom edge, then a rest.
    Element.RECENTS -> Gesture(listOf(Offset(0.5f, 0.995f) to Offset(0.5f, 0.84f)), hold = true)
    Element.DRAWER -> when (placement) {
        "page-after-last" -> Gesture(listOf(Offset(0.82f, 0.5f) to Offset(0.18f, 0.5f)))
        "page-before-first" -> Gesture(listOf(Offset(0.18f, 0.5f) to Offset(0.82f, 0.5f)))
        "swipe-up" -> Gesture(listOf(Offset(0.5f, 0.86f) to Offset(0.5f, 0.62f)))
        else -> null
    }
    else -> null
}

/** Home sliding in: its frame (rim, shadow) is there in full from this far along the slide (0..1). */
private const val FRAME_IN = 0.3f

/**
 * Home as it is now, above the sheet; a new picture (after any change) fades in over the last. Whole, it is the phone in
 * its frame. Focused on a part (the element chosen on Layouts), it zooms to the full width with that part in the room above
 * the sheet, and fades away from it (below, behind the sheet; above, for a part at the phone's bottom edge): no frame, no
 * box (Matheesha: "don't clip the phone preview into a small box"). One spring moves the zoom and the fade
 * (`motion.settings.focus`). Drawn in one pass (no layer moves: see [SettingsApp]); it takes no touches.
 */
@Composable
private fun HomePicture(st: Studio, g: Geometry, bar: Animatable<Float, *>) {
    val look = LocalLook.current
    val ctx = LocalContext.current
    val statusFrac = g.statusPx / g.h
    val draft = LocalDraft.current
    // What it focuses on: on Layouts, the element chosen at its top (once the page is coming); and how that element is
    // reached. Both are read by the effects and the drawing only: composing anything here when a page opens or another
    // element is chosen cost the first frame 11-27 ms on the S24.
    val focusedState = remember { androidx.compose.runtime.derivedStateOf { if (st.open == Part.LAYOUTS && st.p.targetValue >= 0.5f) st.element else null } }
    val targetState = remember(draft, statusFrac) {
        androidx.compose.runtime.derivedStateOf { focusedState.value.let { f -> focusOf(f, f?.let { draft.placement(it) } ?: "", statusFrac) } }
    }
    val gestureState = remember(draft) {
        androidx.compose.runtime.derivedStateOf { focusedState.value.let { f -> gestureOf(f, f?.let { draft.placement(it) } ?: "") } }
    }
    val crop = remember { Animatable(Rect(0f, 0f, 1f, 1f), Rect.VectorConverter) }
    val zoom = remember { Animatable(0f) }
    val cropFrom = remember { arrayOf(Rect(0f, 0f, 1f, 1f)) }
    val focusSpec = androidx.compose.runtime.rememberUpdatedState(look.motion<Rect>(MotionTokens.SETTINGS_FOCUS))
    val zoomSpec = androidx.compose.runtime.rememberUpdatedState(look.motion<Float>(MotionTokens.SETTINGS_FOCUS))
    LaunchedEffect(targetState) {
        androidx.compose.runtime.snapshotFlow { targetState.value }.collectLatest { target ->
            cropFrom[0] = crop.value
            launch { zoom.animateTo(if (target.height >= 0.999f) 0f else 1f, zoomSpec.value) }
            crop.animateTo(target, focusSpec.value)
        }
    }
    // On Layouts, a finger shows how the chosen element is reached (where it lives), again and again: [hintAt] the stroke,
    // [hintT] how far along it (0..1), [hintA] how present the finger is.
    val hintAt = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val hintT = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val hintA = remember { Animatable(0f) }
    val hintOnState = remember { androidx.compose.runtime.derivedStateOf { st.open == Part.LAYOUTS && !st.p.isRunning } }
    LaunchedEffect(gestureState) {
        androidx.compose.runtime.snapshotFlow { gestureState.value?.takeIf { hintOnState.value } }.collectLatest { gs ->
        if (gs == null) { hintA.animateTo(0f, look.motion(MotionTokens.DISAPPEAR_FADE)); return@collectLatest }
        val travel = dev.launcher.app.motion.Motion.role(MotionTokens.SETTINGS_HINT)
        hintA.snapTo(0f)
        kotlinx.coroutines.delay(500)   // the picture's focus first
        while (true) {
            for (i in gs.strokes.indices) {
                hintAt.intValue = i
                hintT.floatValue = 0f
                hintA.animateTo(1f, look.motion(MotionTokens.APPEAR_FADE))
                val m = travel.mover().apply { start(0f, 0f, 1f) }
                val t0 = androidx.compose.runtime.withFrameNanos { it }
                while (true) {
                    val t = (androidx.compose.runtime.withFrameNanos { it } - t0) / 1e9
                    hintT.floatValue = m.value(t).coerceIn(0f, 1f)
                    if (m.settled(t, 0.002f)) { hintT.floatValue = 1f; break }
                }
                if (gs.hold) kotlinx.coroutines.delay(600)
                hintA.animateTo(0f, look.motion(MotionTokens.DISAPPEAR_FADE))
                kotlinx.coroutines.delay(250)
            }
            kotlinx.coroutines.delay(700)
        }
        }
    }
    // While Motion is open, an app opens and closes on the picture, again and again, on the chosen preset's own curves
    // (0: closed, 1: open; below 0: no demo).
    val demo = remember { androidx.compose.runtime.mutableFloatStateOf(-1f) }
    // Where the demo's app opens from: the dock's middle icon as home shows it (else a place in the dock); read as it starts.
    val demoIconAt = remember { arrayOfNulls<android.graphics.RectF>(1) }
    val demoPreset = draft.motionId()
    val motionOpen by remember { androidx.compose.runtime.derivedStateOf { st.open == Part.MOTION && !st.p.isRunning } }
    LaunchedEffect(motionOpen, demoPreset) {
        demo.floatValue = -1f
        if (!motionOpen) return@LaunchedEffect
        val opening = Design.previewCurve(demoPreset, MotionTokens.APP_OPEN) ?: dev.launcher.app.motion.Motion.role(MotionTokens.APP_OPEN)
        val closing = Design.previewCurve(demoPreset, MotionTokens.APP_CLOSE_SIZE) ?: dev.launcher.app.motion.Motion.role(MotionTokens.APP_CLOSE_SIZE)
        suspend fun run(c: dev.launcher.app.design.Curve, from: Float, to: Float) {
            val m = c.mover().apply { start(from, 0f, to) }
            val t0 = androidx.compose.runtime.withFrameNanos { it }
            while (true) {
                val t = (androidx.compose.runtime.withFrameNanos { it } - t0) / 1e9
                demo.floatValue = m.value(t)
                if (m.settled(t, 0.002f)) { demo.floatValue = to; break }
            }
        }
        demoIconAt[0] = HomeBridge.dockIcon()
        demo.floatValue = 0f
        kotlinx.coroutines.delay(400)
        while (true) {
            run(opening, 0f, 1f)
            kotlinx.coroutines.delay(650)
            run(closing, 1f, 0f)
            kotlinx.coroutines.delay(900)
        }
    }
    val appFill = look.color(SettingsTokens.GROUP_FILL)
    val appPage = look.color(SettingsTokens.BACKGROUND)
    val appInk = look.color(SettingsTokens.ROW_LABEL)
    var latest by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
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
    // And when home's wallpaper arrives (after a start, home read it while the settings were in front).
    LaunchedEffect(look.version, look.wallpaper, resumed) {
        kotlinx.coroutines.delay(150)
        // Home handed over its picture as it slid in (it is current): no other one taken in its frames.
        if (st.entry.moving || st.entry.enter.floatValue < 1f) return@LaunchedEffect
        // Home may not be up yet (the app just started with the settings in front): asked again for a few seconds.
        var home: android.graphics.Bitmap? = null
        for (i in 0 until 10) {
            home = HomeBridge.home?.pictureNow()
            if (home != null) break
            kotlinx.coroutines.delay(500)
        }
        if (home == null) return@LaunchedEffect
        val page = HomeBridge.home?.showsPage() == true
        // With our status bar on top, as the phone shows home (its picture has none: the bar is a window of its own).
        // Asked again if it did not come (its thread busy with a frame on a slow phone: the picture had no bar).
        var bar: android.graphics.Bitmap? = null
        for (i in 0 until 3) {
            bar = HomeBridge.statusBarPicture()
            if (bar != null) break
            kotlinx.coroutines.delay(300)
        }
        val next = if (bar == null) home else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { HomePictures.withBar(home, bar) }
        HomePictures.keep(ctx, Design.themeId, next, page)
        latest = next
    }
    // The theme chosen and not yet applied shows as home looked in it (if the settings have seen it), else home as it is;
    // with no home to picture yet (the app just started), as it last looked in this theme.
    LaunchedEffect(Unit) { HomePictures.load(ctx) }
    // Home's picture handed over as it slides in is the newest: taken in at once (it is what shows); a fresh one before it
    // slides back out, with the usual fade.
    LaunchedEffect(st.entry) {
        androidx.compose.runtime.snapshotFlow { st.entry.picture to st.entry.fresh }.collect { (handed, fresh) ->
            if (handed != null && shown !== handed) {
                HomePictures.keep(ctx, Design.themeId, handed, st.entry.handedPage)
                latest = handed
                before = null
                shown = handed
                fade.snapTo(1f)
            }
            if (fresh != null) {
                st.entry.fresh = null
                HomePictures.keep(ctx, Design.themeId, fresh, st.entry.freshPage)
                latest = fresh
            }
        }
    }
    val wanted = draft.theme?.let { HomePictures.byTheme[it] } ?: latest ?: HomePictures.byTheme[Design.themeId]
    LaunchedEffect(wanted) {
        if (wanted == null || wanted === shown) return@LaunchedEffect
        before = shown
        shown = wanted
        fade.snapTo(0f)
        fade.animateTo(1f, look.motion(MotionTokens.APPEAR_FADE))
        before = null
    }
    val rim = look.color(SettingsTokens.PREVIEW_RIM).toArgb()
    val rimW = look.pt(SettingsTokens.PREVIEW_RIM_WIDTH)
    val shadow = look.color(SettingsTokens.PREVIEW_SHADOW).toArgb()
    val page = look.color(SettingsTokens.BACKGROUND).toArgb()
    val cornerFrac = Design.num(SettingsTokens.PREVIEW_CORNER)
    // The screen's own corners (px): home's picture filling the screen as it slides in is cut as the screen is, so its
    // first and last frames are home itself (cut to the picture's corners, with its rim, they showed a dark frame).
    val view = androidx.compose.ui.platform.LocalView.current
    val screenCorner = remember(view) {
        if (android.os.Build.VERSION.SDK_INT >= 31) view.display?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() ?: 0f else 0f
    }
    val margin = look.pt(SettingsTokens.MARGIN)
    val wallpaper = look.wallpaper
    val paints = remember {
        object {
            val shadow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            val rim = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            val image = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
            // The fade: fixed unit gradients (opaque to clear, clear to opaque, over 0..1), drawn through the canvas's transform
            // and cutting the layer away (DST_OUT) by the paint's alpha; a gradient made each frame was native memory each
            // frame (garbage collections in the middle of the motion on the S24).
            val cut = android.graphics.Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT) }
            val fadeOut = android.graphics.LinearGradient(0f, 0f, 0f, 1f, 0xFF000000.toInt(), 0x00000000, android.graphics.Shader.TileMode.CLAMP)
            val fadeIn = android.graphics.LinearGradient(0f, 0f, 0f, 1f, 0x00000000, 0xFF000000.toInt(), android.graphics.Shader.TileMode.CLAMP)
            val hint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND }
            val dot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            val path = android.graphics.Path()
            val frame = android.graphics.RectF()
            val src = android.graphics.Rect()
            val dst = android.graphics.RectF()
            val m = android.graphics.Matrix()
        }
    }
    Box(Modifier.fillMaxSize().semantics { contentDescription = "Your home screen" }.drawBehind {
        val p = st.p.value
        val c = crop.value
        // A long way from one part to another (the top to the bottom edge) pulls back a little on the way, as a camera
        // would, rather than sliding through the whole phone at full zoom.
        val from = cropFrom[0]
        val target = targetState.value
        val travel = kotlin.math.abs((target.top + target.bottom) - (from.top + from.bottom)) / 2f
        val along = if (travel < 0.02f) 1f else (kotlin.math.abs((c.top + c.bottom) / 2f - (from.top + from.bottom) / 2f) / travel).coerceIn(0f, 1f)
        val dip = kotlin.math.sin(Math.PI * along).toFloat() * (travel / 0.6f).coerceIn(0f, 1f)
        val z = zoom.value.coerceIn(0f, 1f) * (1f - 0.35f * dip)
        val ch = c.height.coerceAtLeast(0.01f)
        // The room between the header and the sheet (the sheet rises as a page opens: the room shrinks with it).
        val sheetTop = g.top(p, bar.value)
        val availH = (sheetTop - g.previewTop - 22f * g.unit).coerceAtLeast(60f * g.unit)
        val availW = g.w - margin * 2f
        // Whole: the phone fitted into the room, in its frame.
        var fw0 = availW
        var fh0 = fw0 * g.h / g.w
        if (fh0 > availH) { fh0 = availH; fw0 = fh0 * g.w / g.h }
        val s0 = fw0 / g.w
        val x0 = (g.w - fw0) / 2f
        val y0 = g.previewTop + (availH - fh0) / 2f
        // Focused: the full width, the part in the room (as high in it as the part is on the phone).
        val s1 = availW / g.w
        val anchor = if (ch >= 0.999f) 0f else (c.top / (1f - ch)).coerceIn(0f, 1f)
        val bandH = ch * g.h * s1
        val bandY = g.previewTop + (availH - bandH) * anchor
        val x1 = margin
        val y1 = bandY - c.top * g.h * s1
        val sRest = s0 + (s1 - s0) * z
        val oxRest = x0 + (x1 - x0) * z
        val oyRest = y0 + (y1 - y0) * z
        // Home sliding in from the whole screen (or back out to it): from there to here on the slide's one value (past its
        // end a little as the spring settles; never larger than the screen).
        val e = st.entry.enter.floatValue.coerceAtLeast(0f)
        val s = 1f + (sRest - 1f) * e
        val ox = oxRest * e
        val oy = oyRest * e
        // The phone's outline where it is now, its corners at its scale (the screen's own over the whole screen).
        val r = screenCorner + (g.w * cornerFrac * s - screenCorner) * e
        val f = paints.frame.apply { set(ox, oy, ox + g.w * s, oy + g.h * s) }
        drawIntoCanvas { cv ->
            val nc = cv.nativeCanvas
            // Whole: a soft shadow (cast by a shape in the page's own colour, so only the shadow shows) and the phone's
            // edge; both go as it zooms (there is no frame to cast them).
            // Both only outside the picture (a blur of its outline outward, a ring): a shadow cast by a shape in the page's colour
            // and a rim filled under the picture showed as a dark box wherever the picture faded (and the page is the
            // wallpaper now, no colour to hide a caster in).
            // Focused: it fades away from the part, towards the sheet (below) or the header (above), through a layer the size
            // of the room it shows in.
            val fadeTop = g.previewTop
            val fadeBottom = sheetTop + 40f * g.unit
            val layered = z > 0.003f
            if (layered) nc.saveLayer(0f, fadeTop - 1f, g.w, fadeBottom + 1f, null)
            // (Inside the layer: as the picture zooms they fade with it at the room's ends; drawn outside, they ran up the
            // zoomed phone's sides to the top of the screen.)
            // (And while home slides in: none over the whole screen, there by the time it is well inside it.)
            val frameA = (1f - zoom.value.coerceIn(0f, 1f)) * (e / FRAME_IN).coerceIn(0f, 1f)
            if (frameA > 0.003f) {
                paints.shadow.color = ((((shadow ushr 24) * frameA).toInt() shl 24) or (shadow and 0xFFFFFF))
                if (paints.shadow.maskFilter == null) paints.shadow.maskFilter = android.graphics.BlurMaskFilter(18f * g.unit, android.graphics.BlurMaskFilter.Blur.OUTER)
                nc.save()
                nc.translate(0f, 8f * g.unit)
                nc.drawRoundRect(f, r, r, paints.shadow)
                nc.restore()
                paints.rim.color = rim
                paints.rim.alpha = ((rim ushr 24) * frameA).toInt()
                paints.rim.style = android.graphics.Paint.Style.STROKE
                paints.rim.strokeWidth = rimW
                nc.drawRoundRect(f.left - rimW / 2f, f.top - rimW / 2f, f.right + rimW / 2f, f.bottom + rimW / 2f, r + rimW / 2f, r + rimW / 2f, paints.rim)
                paints.rim.style = android.graphics.Paint.Style.FILL
            }
            paints.path.rewind()
            paints.path.addRoundRect(f, r, r, android.graphics.Path.Direction.CW)
            nc.save()
            nc.clipPath(paints.path)
            nc.translate(ox, oy)
            nc.scale(s, s)
            // In phone pixels from here.
            fun picture(b: android.graphics.Bitmap, alpha: Float) {
                paints.image.alpha = (alpha * 255f).roundToInt().coerceIn(0, 255)
                paints.src.set(0, 0, b.width, b.height)
                paints.dst.set(0f, 0f, g.w, g.h)
                nc.drawBitmap(b, paints.src, paints.dst, paints.image)
            }
            // Home's own picture as it slid in, until it is taken in (its first frames: the effect taking it in comes after).
            val handed = st.entry.picture?.takeIf { it !== shown }
            val sb = if (handed != null) null else shown
            val bb = if (handed != null) null else before
            if (handed != null) picture(handed, 1f)
            if (bb != null) picture(bb, 1f)
            if (sb != null) picture(sb, fade.value)
            else if (handed == null && wallpaper != null) {
                // Home has not shown since the app started: its wallpaper at least.
                paints.image.alpha = 255
                paints.m.set(wallpaper.matrix(look.screenW, look.screenH))
                paints.m.postScale(g.w / look.screenW, g.h / look.screenH)
                nc.drawBitmap(wallpaper.bitmap, paints.m, paints.image)
            }
            // Motion's demo: an app opening over home out of the dock's middle icon, as it does on the phone: the icon grows
            // into the app's page (the icon itself, cut from the picture, fading into it), home stepping back behind.
            val d = demo.floatValue
            if (d >= 0f) {
                val k = d.coerceIn(-0.1f, 1.1f)
                val kk = k.coerceIn(0f, 1f)
                val ic = demoIconAt[0]
                val icon = ic?.width() ?: (g.w * 0.17f)
                val from = if (ic != null) Rect(ic.left, ic.top, ic.right, ic.bottom)
                    else Rect(g.w * 0.5f - icon / 2f, g.h * 0.9f - icon, g.w * 0.5f + icon / 2f, g.h * 0.9f)
                val a = Rect(from.left * (1f - k), from.top * (1f - k), from.right + (g.w - from.right) * k, from.bottom + (g.h - from.bottom) * k)
                nc.drawColor(Color.Black.copy(alpha = 0.25f * kk).toArgb())
                val phoneCorner = g.w * cornerFrac
                val rc = icon * 0.23f + (phoneCorner - icon * 0.23f) * kk
                nc.save()
                paints.path.rewind()
                paints.path.addRoundRect(a.left, a.top, a.right, a.bottom, rc, rc, android.graphics.Path.Direction.CW)
                nc.clipPath(paints.path)
                // The app's page: a title and two groups of rows, in the theme's own page colours.
                nc.drawColor(appPage.toArgb())
                val w = a.width; val h = a.height
                paints.rim.color = appInk.copy(alpha = appInk.alpha * 0.85f).toArgb()
                nc.drawRoundRect(a.left + w * 0.06f, a.top + h * 0.075f, a.left + w * 0.5f, a.top + h * 0.1f, h * 0.012f, h * 0.012f, paints.rim)
                for (gi in 0..1) {
                    val top = a.top + h * (0.14f + gi * 0.25f)
                    paints.rim.color = appFill.toArgb()
                    nc.drawRoundRect(a.left + w * 0.04f, top, a.right - w * 0.04f, top + h * 0.21f, w * 0.05f, w * 0.05f, paints.rim)
                    paints.rim.color = appInk.copy(alpha = appInk.alpha * 0.22f).toArgb()
                    for (ri in 0..2) {
                        val y = top + h * (0.045f + ri * 0.065f)
                        nc.drawRoundRect(a.left + w * 0.1f, y, a.left + w * (0.42f + 0.12f * ((ri + gi) % 2)), y + h * 0.014f, h * 0.007f, h * 0.007f, paints.rim)
                    }
                }
                // The icon it grows out of, fading into the page as it opens.
                val iconA = (1f - kk / 0.45f).coerceIn(0f, 1f)
                val src = shown
                if (ic != null && src != null && iconA > 0f) {
                    val sx = src.width / g.w; val sy = src.height / g.h
                    paints.src.set((ic.left * sx).toInt(), (ic.top * sy).toInt(), (ic.right * sx).toInt(), (ic.bottom * sy).toInt())
                    paints.dst.set(a.left, a.top, a.right, a.bottom)
                    paints.image.alpha = (255 * iconA).toInt()
                    nc.drawBitmap(src, paints.src, paints.dst, paints.image)
                    paints.image.alpha = 255
                }
                nc.restore()
            }
            // The finger showing how the element is reached: a soft white dot with a trail, on the picture (scaled with it).
            val ha = hintA.value
            val gs = gestureState.value
            if (ha > 0.003f && gs != null) {
                val (from, to) = gs.strokes[hintAt.intValue.coerceIn(0, gs.strokes.lastIndex)]
                val t = hintT.floatValue
                val x0 = from.x * g.w; val y0 = from.y * g.h
                val x = x0 + (to.x - from.x) * g.w * t
                val y = y0 + (to.y - from.y) * g.h * t
                val dot = g.w * 0.05f
                // The trail: short pieces from its start to the finger, each a little stronger (no gradient made each frame).
                paints.hint.strokeWidth = dot * 1.1f
                if (t > 0.01f) {
                    val n = 10
                    for (i in 0 until n) {
                        val a0 = i / n.toFloat(); val a1 = (i + 1) / n.toFloat()
                        paints.hint.color = (((0x99 * ha * a1 * a1).toInt()) shl 24) or 0xFFFFFF
                        nc.drawLine(x0 + (x - x0) * a0, y0 + (y - y0) * a0, x0 + (x - x0) * a1, y0 + (y - y0) * a1, paints.hint)
                    }
                }
                paints.dot.color = (((0xF2 * ha).toInt()) shl 24) or 0xFFFFFF
                paints.dot.setShadowLayer(dot * 0.5f, 0f, dot * 0.12f, (((0x55 * ha).toInt()) shl 24))
                nc.drawCircle(x, y, dot, paints.dot)
            }
            nc.restore()
            if (layered) {
                // Opaque over the part (where it is on screen now); towards the room's ends it fades out over at least
                // [fadeLen]. Everything here changes continuously with the springs, and the fade's length never shrinks to
                // nothing (a fade that ran out of room went sharp for a moment, then soft again).
                val span = fadeBottom - fadeTop
                val fadeLen = minOf(span * 0.45f, 160f * g.unit)
                // An end fades as far as the phone goes past the room there (its edge inside: nothing to fade; well past:
                // all the way to nothing), so the room's edge never cuts the picture visibly.
                val needTop = ((fadeTop - f.top) / (fadeLen * 0.5f)).coerceIn(0f, 1f)
                val needBottom = ((f.bottom - fadeBottom) / (fadeLen * 0.5f)).coerceIn(0f, 1f)
                val partTop = oy + c.top * g.h * s
                val partBottom = oy + c.bottom * g.h * s
                val solidTop = maxOf(partTop, fadeTop + fadeLen * needTop).coerceIn(fadeTop, fadeBottom)
                val solidBottom = minOf(partBottom, fadeBottom - fadeLen * needBottom).coerceIn(solidTop, fadeBottom)
                // Top: cut away by [needTop] at the room's top, nothing at [solidTop]; bottom: nothing at [solidBottom], by
                // [needBottom] at the room's bottom.
                if (needTop > 0.001f) {
                    nc.save()
                    nc.translate(0f, fadeTop)
                    nc.scale(1f, (solidTop - fadeTop).coerceAtLeast(1f))
                    paints.cut.shader = paints.fadeOut
                    paints.cut.alpha = (255 * needTop).toInt()
                    nc.drawRect(0f, -0.01f, g.w, 1f, paints.cut)
                    nc.restore()
                }
                if (needBottom > 0.001f) {
                    nc.save()
                    nc.translate(0f, solidBottom)
                    nc.scale(1f, (fadeBottom - solidBottom).coerceAtLeast(1f))
                    paints.cut.shader = paints.fadeIn
                    paints.cut.alpha = (255 * needBottom).toInt()
                    nc.drawRect(0f, 0f, g.w, 1.01f, paints.cut)
                    nc.restore()
                }
                nc.restore()
            }
        }
    })
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
            // In as home's picture has made room for it (the slide's second half).
            val e = ((st.entry.enter.floatValue - 0.45f) / 0.55f).coerceIn(0f, 1f)
            alpha = (1f - p * 2f).coerceIn(0f, 1f) * e
            translationY = -p * rise + (1f - e) * rise
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
private fun Sheet(st: Studio, g: Geometry, bar: Animatable<Float, *>, open: (Part) -> Unit, close: (Float) -> Unit) {
    val look = LocalLook.current
    val fill = look.color(SettingsTokens.SHEET_FILL)
    val rim = look.color(SettingsTokens.SHEET_RIM)
    val shadow = look.color(SettingsTokens.SHEET_SHADOW)
    val page = look.color(SettingsTokens.BACKGROUND)
    val corner = look.pt(SettingsTokens.SHEET_CORNER)
    val shadowPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    // A sheet that meets the screen's edges takes its bottom corners past them.
    val below = if (g.inset < 0.5f) corner else 0f
    // The panel is there at rest too (glass is for the buttons on it: Matheesha).
    val solid: () -> Float = { 1f }
    Box(
        Modifier
            .offset { IntOffset(g.inset.roundToInt(), g.openTop.roundToInt()) }
            .size(look.dp(g.sheetW), look.dp(g.sheetH))
            .graphicsLayer { translationY = g.top(st.p.value, bar.value) - g.openTop + (1f - st.entry.enter.floatValue.coerceAtLeast(0f)) * g.enterDrop }
            .drawBehind {
                val k = solid()
                if (k <= 0.003f) return@drawBehind
                shadowPaint.color = page.toArgb()
                shadowPaint.alpha = (255 * k).toInt()
                shadowPaint.setShadowLayer(26f * g.unit, 0f, -2f * g.unit, shadow.copy(alpha = shadow.alpha * k).toArgb())
                drawIntoCanvas { it.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height + below, corner, corner, shadowPaint) }
                drawRoundRect(fill.copy(alpha = fill.alpha * k), size = Size(size.width, size.height + below), cornerRadius = CornerRadius(corner))
                drawRoundRect(rim.copy(alpha = rim.alpha * k), size = Size(size.width, size.height + below), cornerRadius = CornerRadius(corner), style = Stroke(1f * g.unit))
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
        val grabber = look.color(SettingsTokens.TITLE_COLOR).copy(alpha = 0.18f)
        Box(
            Modifier.align(Alignment.TopCenter).padding(top = look.dp(7f * g.unit))
                .size(look.dp(36f * g.unit), look.dp(5f * g.unit))
                .drawBehind { drawRoundRect(grabber.copy(alpha = grabber.alpha * solid()), cornerRadius = CornerRadius(size.height / 2f)) },
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
        val draft = LocalDraft.current
        Row(back.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(look.dp(6f * g.unit))) {
            Chip(themeName(draft.themeId())) { open(Part.LOOK) }
            Chip(if (draft.wallpaperColors()) "Wallpaper colours" else "Theme colours") { open(Part.LOOK) }
            Chip(presetName(draft.motionId()) + " motion") { open(Part.MOTION) }
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
    val glass = Design.num(SettingsTokens.GLASS) > 0.5f
    val st = LocalStudio.current
    BasicText(text, style = look.text(SettingsTokens.CHIP_TEXT, look.color(SettingsTokens.CHIP_TEXT_COLOR)), maxLines = 1,
        modifier = Modifier
            .pressScale { 1f - 0.05f * press }
            .then(if (glass) Modifier.movingGlass(SettingsTokens.CHIP_MATERIAL, look.pt(SettingsTokens.CHIP_CORNER), { press }, panelUnder(look)) { st?.let { it.p.value + it.entry.enter.floatValue } }
                else Modifier.background(look.color(SettingsTokens.CHIP_FILL), RoundedCornerShape(look.dp(SettingsTokens.CHIP_CORNER))))
            .pointerInput(onClick) { rowTap({ pressed = it }, onClick) }
            .padding(horizontal = look.dp(12f * look.unit), vertical = look.dp(7f * look.unit)))
}

/**
 * Pressed in: drawn smaller by [scale] around the centre. In drawing, not as a layer's transform: a layer that changes
 * its transform makes Compose re-read the accessibility tree (see [SettingsApp]).
 */
internal fun Modifier.pressScale(scale: () -> Float): Modifier = drawWithContent {
    val s = scale()
    if (s == 1f) drawContent() else scale(s) { this@drawWithContent.drawContent() }
}

/**
 * This element as glass [key] (corner [cornerPx]) where it is on screen now: its place is read again whenever it draws, and
 * it draws again whenever [moving] changes (the sheet's spring): a glass that kept the place it rested at showed the wrong
 * part of the wallpaper while the sheet moved.
 */
private fun Modifier.movingGlass(key: MaterialKey, cornerPx: Float, press: () -> Float, under: List<dev.launcher.app.design.Fill>, moving: () -> Float?): Modifier = composed {
    val look = LocalLook.current
    val at = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val rect = remember { android.graphics.RectF() }
    this
        .onPlaced { at[0] = it }
        .drawBehind {
            moving()
            val co = at[0]?.takeIf { it.isAttached } ?: return@drawBehind
            val o = co.positionInWindow()
            rect.set(0f, 0f, size.width, size.height)
            drawIntoCanvas { look.drawGlass(it.nativeCanvas, key, rect, cornerPx, o.x, o.y, press(), under = under) }
        }
}

/** What glass on the panel shows through: the panel's own fill over the page (the blurred wallpaper). */
private fun panelUnder(look: Look): List<dev.launcher.app.design.Fill> {
    val c = look.color(SettingsTokens.SHEET_FILL).toArgb()
    val a = ((c ushr 24) and 0xFF) / 255f
    val opaque = c or (0xFF shl 24)
    return listOf(dev.launcher.app.design.Fill(dev.launcher.app.design.ColorValue.Literal(opaque, opaque), a, a, dev.launcher.app.design.Blend.NORMAL))
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
    val glass = Design.num(SettingsTokens.GLASS) > 0.5f
    val titleTint = if (glass) 0f else Design.num(SettingsTokens.TILE_TITLE_TINT).coerceIn(0f, 1f)
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
            .then(if (glass) Modifier.movingGlass(SettingsTokens.TILE_MATERIAL, look.pt(SettingsTokens.TILE_CORNER), { press.value }, panelUnder(look)) { st.p.value + st.entry.enter.floatValue }
                else Modifier.background(tileFill(look, part), RoundedCornerShape(look.dp(SettingsTokens.TILE_CORNER))))
            .pointerInput(part) { rowTap({ pressed = it }) { open(part) } }
            .padding(look.dp(14f * look.unit)),
    ) {
        // Glass: the symbol alone in the label's colour (no coloured square), the names in the labels' colours.
        Box(Modifier.size(look.dp(symbol)).then(if (glass) Modifier else Modifier.background(c, RoundedCornerShape(look.dp(SettingsTokens.TILE_SYMBOL_CORNER)))),
            contentAlignment = if (glass) Alignment.CenterStart else Alignment.Center) {
            Image(painterResource(part.symbol), null, colorFilter = ColorFilter.tint(if (glass) label else look.color(SettingsTokens.TILE_SYMBOL_COLOR)),
                modifier = Modifier.size(look.dp(symbol * if (glass) 0.8f else 0.6f)))
        }
        Spacer(Modifier.weight(1f))
        BasicText(part.title, style = look.text(SettingsTokens.TILE_TITLE, mix(label, c, titleTint)), maxLines = 1)
        BasicText(part.detail(LocalDraft.current), style = look.text(SettingsTokens.TILE_DETAIL, mix(detail, c, titleTint).copy(alpha = if (titleTint > 0f) 0.8f else detail.alpha)),
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
    val glass = Design.num(SettingsTokens.GLASS) > 0.5f
    val bg = tileFill(look, part)
    val c = look.color(part.color)
    val label = look.color(SettingsTokens.ROW_LABEL)
    val glassRect = remember { android.graphics.RectF() }
    val under = panelUnder(look)
    val fromCorner = look.pt(SettingsTokens.TILE_CORNER)
    val toCorner = look.pt(SettingsTokens.SHEET_CORNER)
    val pad = 14f * look.unit
    val symbol = look.pt(SettingsTokens.TILE_SYMBOL)
    val symbolCorner = look.pt(SettingsTokens.TILE_SYMBOL_CORNER)
    val glyph = painterResource(part.symbol)
    val glyphColor = ColorFilter.tint(if (glass) label else look.color(SettingsTokens.TILE_SYMBOL_COLOR))
    val titleTint = if (glass) 0f else Design.num(SettingsTokens.TILE_TITLE_TINT).coerceIn(0f, 1f)
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
        if (glass) {
            // The tile's glass grows with it, at its place over the wallpaper, and gives way to the sheet turning solid.
            val at = st.sheet?.takeIf { it.isAttached }?.positionInWindow() ?: Offset.Zero
            glassRect.set(r.left, r.top, r.right, r.bottom)
            if (colour > 0.003f) drawIntoCanvas { look.drawGlass(it.nativeCanvas, SettingsTokens.TILE_MATERIAL, glassRect, corner, at.x, at.y, alpha = colour, under = under) }
        } else drawRoundRect(bg.copy(alpha = bg.alpha * colour), r.topLeft, r.size, CornerRadius(corner))
        val content = (1f - p / 0.35f).coerceIn(0f, 1f)
        if (content > 0f) {
            translate(r.left + pad, r.top + pad) {
                if (glass) {
                    with(glyph) { draw(Size(symbol * 0.8f, symbol * 0.8f), alpha = content, colorFilter = glyphColor) }
                } else {
                    drawRoundRect(c.copy(alpha = c.alpha * content), Offset.Zero, Size(symbol, symbol), CornerRadius(symbolCorner))
                    translate(symbol * 0.2f, symbol * 0.2f) {
                        with(glyph) { draw(Size(symbol * 0.6f, symbol * 0.6f), alpha = content, colorFilter = glyphColor) }
                    }
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
    val scroll = st.scrolls.getValue(part)
    // Made once (a new one each time this composed changed what every group of the page reads: the whole page composed again
    // in the first frame of any opening).
    val shown: () -> Float = remember(st, part) { { if (st.open == part) st.p.value.coerceIn(0f, 1f) else 0f } }
    val counter = remember { GroupCounter() }
    counter.n = 0
    val m = look.dp(SettingsTokens.MARGIN)
    val fadePx = 18f * g.unit
    val bandPaint = remember { androidx.compose.ui.graphics.Paint() }
    // Only the open page is in the accessibility tree: with an accessibility service on (gesture nav is one), Compose
    // scans the whole tree every 100 ms while anything moves (7-9 ms of the main thread on the S24 with every page in it).
    val reader = LocalScreenReader.current
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
                // Only the open page is in the tree (the whole app leaves it while a page moves or scrolls: SettingsApp).
                .then(if (!reader || here) Modifier else Modifier.clearAndSetSemantics { })
                // Laid out and placed from the start; while closed it waits out of sight (moved, not laid out, when it opens).
                .graphicsLayer {
                    val open = st.open == part
                    translationX = if (open) 0f else g.w * 3f
                    alpha = if ((open && st.p.value > 0f) || warm) 1f else 0f
                }
                .then(if (part == Part.LAYOUTS) Modifier else Modifier.topFade(scroll, g.unit, fadePx, bandPaint)),
        ) {
            if (part == Part.LAYOUTS) LayoutsPager(st, g, fadePx, bandPaint)
            else Column(Modifier.fillMaxSize().iosScroll(scroll).padding(horizontal = m)) {
              run {
                Spacer(Modifier.height(look.dp(26f * g.unit)))
                when (part) {
                    Part.LAYOUTS -> Unit
                    Part.LOOK -> LookPage()
                    Part.MOTION -> MotionPage()
                    Part.PHONE -> PhonePage()
                }
                // Room below the last row for the Apply bar.
                Spacer(Modifier.height(look.dp(24f * g.unit + g.navPx + g.inset + g.barRoom)))
              }
            }
        }
    }
}

// ------------------------------------------------------------------ the pages

/**
 * Look (docs/PLAN_SETTINGS.md S2): the theme as cards (home as it looked in each, once the settings have seen it), a
 * theme for one part only, the wallpaper's colours, light and dark. All of it waits for Apply.
 */
@Composable
private fun LookPage() {
    val draft = LocalDraft.current
    val themes = remember(LocalLook.current.version) { Design.themes() }
    Group("Theme", "Everything follows it, unless a part has its own below.") {
        ThemeCards(themes, draft)
    }
    Group("Only for one part") {
        for (e in Element.entries) ElementTheme(e, themes, draft)
    }
    // Which colour the editor below the palette is changing: the one it is made from ([SEED]), a palette's key, or none.
    val editing = remember { mutableStateOf<Char?>(null) }
    Group("Colours", "Material You: any theme's surfaces, glass and accents take your wallpaper's colours, or your own. Tap a colour to set it yourself.") {
        ToggleRow("Material You", null, draft.wallpaperColors()) { draft.chooseColors(it) }
        ColourSourceRow(draft, editing)
        ColourStyleRow(draft)
        IntensityRow(draft)
        PaletteRow(draft, editing)
        ColourEditor(draft, editing)
    }
    Group("Fonts", "Google fonts are downloaded the first time they are used, then work offline.") {
        FontRow("Text", "sys.font.text", draft)
        FontRow("Titles", "sys.font.display", draft)
    }
    Group("Appearance") {
        Box(Modifier.padding(LocalLook.current.dp(SettingsTokens.ROW_PADDING))) {
            ChoiceChips(Appearance.Mode.entries.map { it.title }, Appearance.Mode.entries.indexOf(draft.appearanceMode())) {
                draft.chooseAppearance(Appearance.Mode.entries[it])
            }
        }
    }
    Group("Size", "Following the phone: everything takes its Display size, text its Font size, as Android's own apps do. Off, or a slider moved: your own sizes, whatever the phone's (100 % is an iPhone's).") {
        val ctx = LocalContext.current
        ToggleRow("Follow the phone's display and font size", null, draft.followsPhoneSize()) { draft.chooseFollowsPhoneSize(it) }
        val phone = draft.followsPhoneSize()
        SizeRow("Everything", draft.size(ctx), phone, dev.launcher.app.design.Scale.SIZE_MIN, dev.launcher.app.design.Scale.SIZE_MAX) { draft.chooseSize(ctx, it) }
        SizeRow("Text", draft.text(ctx), phone, dev.launcher.app.design.Scale.TEXT_MIN, dev.launcher.app.design.Scale.TEXT_MAX) { draft.chooseText(ctx, it) }
    }
}

/** The wallpaper's palette (Material You's roles in this appearance), lit while the wallpaper's colours are chosen. */
@Composable
private fun PaletteRow(draft: Draft, editing: androidx.compose.runtime.MutableState<Char?>) {
    val look = LocalLook.current
    val k by animateFloatAsState(if (draft.wallpaperColors()) 1f else 0f, look.motion(MotionTokens.CC_TOGGLE), label = "palette")
    val dark = Appearance.dark > 0.5f
    val choice = draft.youChoice()
    // As the choice would make them (before Apply), each swatch easing to its new colour; a tap sets that palette's colour.
    val roles = remember(look.version, choice) {
        listOf(Triple("primary", "Primary", 'P'), Triple("secondary", "Secondary", 'S'), Triple("tertiary", "Tertiary", 'T'),
            Triple("surface_container_highest", "Surface", 'N'), Triple("outline", "Neutral", 'V'))
            .map { (role, name, p) -> Triple(Color(dev.launcher.app.design.Theme.resolveSpec("@$role", dark) { Design.previewPalette(it, choice) }), name, p) }
    }
    val accent = look.color(SettingsTokens.ACCENT)
    val separator = look.color(SettingsTokens.ROW_SEPARATOR)
    val pad = look.dp(SettingsTokens.ROW_PADDING)
    val swatch = 34f * look.unit
    Row(Modifier.fillMaxWidth().drawBehind { drawRect(separator, Offset(pad.toPx(), 0f), Size(size.width - pad.toPx(), 1f)) }
        .padding(horizontal = pad, vertical = look.dp(12f * look.unit)), horizontalArrangement = Arrangement.SpaceBetween) {
        for ((target, name, p) in roles) Column(horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.pointerInput(p) { rowTap({}) { editing.value = if (editing.value == p) null else p } }) {
            val c by androidx.compose.animation.animateColorAsState(target, look.motion(MotionTokens.APPEAR_FADE), label = "swatch")
            // Ringed while it is the one being changed; a dot under one the user set themselves.
            val ring by animateFloatAsState(if (editing.value == p) 1f else 0f, look.motion(MotionTokens.CC_TOGGLE), label = "ring")
            val own by animateFloatAsState(if (draft.keyColor(p) != null) 1f else 0f, look.motion(MotionTokens.CC_TOGGLE), label = "own")
            Canvas(Modifier.size(look.dp(swatch))) {
                // Off: quieter and a little smaller (the colours are not in use).
                val s = 0.82f + 0.18f * k - 0.1f * ring
                drawCircle(c.copy(alpha = 0.3f + 0.7f * k), radius = this.size.minDimension / 2f * s)
                drawCircle(Color.Black.copy(alpha = 0.08f), radius = this.size.minDimension / 2f * s, style = Stroke(1f * look.unit))
                if (ring > 0.001f) drawCircle(accent.copy(alpha = accent.alpha * ring), radius = this.size.minDimension / 2f - 1.2f * look.unit, style = Stroke(2.4f * look.unit))
                if (own > 0.001f) drawCircle(accent.copy(alpha = accent.alpha * own), radius = 2.5f * look.unit,
                    center = Offset(this.size.width / 2f, this.size.height + 0f))
            }
            Spacer(Modifier.height(look.dp(5f * look.unit)))
            BasicText(name, style = look.text(SettingsTokens.ROW_DETAIL, look.color(SettingsTokens.ROW_DETAIL_COLOR)), maxLines = 1)
        }
    }
}

/** The editor's target for the colour the palette is made from (the others are the palettes' letters). */
private const val SEED = 'X'

/** What the palette is made from: the wallpaper's main colour, or a colour of the user's own (its swatch opens the editor). */
@Composable
private fun ColourSourceRow(draft: Draft, editing: androidx.compose.runtime.MutableState<Char?>) {
    val look = LocalLook.current
    val pad = look.dp(SettingsTokens.ROW_PADDING)
    val separator = look.color(SettingsTokens.ROW_SEPARATOR)
    val dim by animateFloatAsState(if (draft.wallpaperColors()) 1f else 0.45f, look.motion(MotionTokens.CC_TOGGLE), label = "dim")
    val seed = draft.seedColor()
    val shownSeed by androidx.compose.animation.animateColorAsState(seed?.let { Color(it) } ?: Color.Transparent, look.motion(MotionTokens.APPEAR_FADE), label = "seed")
    val showSeed by animateFloatAsState(if (seed != null) 1f else 0f, look.motion(MotionTokens.APPEAR), label = "showSeed")
    Column(Modifier.fillMaxWidth()
        .drawBehind { drawRect(separator, Offset(pad.toPx(), 0f), Size(size.width - pad.toPx(), 1f)) }
        .graphicsLayer { alpha = dim }
        .padding(horizontal = pad, vertical = look.dp(10f * look.unit))) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText("Made from", style = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_LABEL)), modifier = Modifier.weight(1f))
            // The user's colour, there while it is chosen: a tap opens the editor on it.
            Canvas(Modifier.size(look.dp(SettingsTokens.TARGET)).graphicsLayer { alpha = showSeed; scaleX = 0.6f + 0.4f * showSeed; scaleY = scaleX }
                .pointerInput(Unit) { rowTap({}) { if (draft.seedColor() != null) editing.value = if (editing.value == SEED) null else SEED } }) {
                drawCircle(shownSeed, radius = 12f * look.unit)
                drawCircle(Color.Black.copy(alpha = 0.1f), radius = 12f * look.unit, style = Stroke(1f * look.unit))
            }
        }
        Spacer(Modifier.height(look.dp(2f * look.unit)))
        ChoiceChips(listOf("Wallpaper", "Your colour"), if (seed == null) 0 else 1) { i ->
            if (i == 0) { draft.chooseSeed(null); if (editing.value == SEED) editing.value = null }
            else { draft.chooseSeed(Design.wallpaperSeed() ?: 0xFF3478F6.toInt()); editing.value = SEED }
        }
    }
}

/**
 * The colour editor, opening in place under the palette for the colour being changed (the one the palette is made from, or a
 * palette's key colour): a row of colours to start from, its hue, and how colourful it is (a palette takes a colour's hue and
 * colourfulness; its lightness is the palette's own). Each change shows at once in the swatches above and waits for Apply.
 */
@Composable
private fun ColourEditor(draft: Draft, editing: androidx.compose.runtime.MutableState<Char?>) {
    val look = LocalLook.current
    val t = editing.value
    Box(Modifier.fillMaxWidth().animateContentSize(look.motion(MotionTokens.APPEAR))) {
        if (t != null) androidx.compose.runtime.key(t) { ColourEditorBody(draft, t) { editing.value = null } }
    }
}

@Composable
private fun ColourEditorBody(draft: Draft, t: Char, done: () -> Unit) {
    val look = LocalLook.current
    val pad = look.dp(SettingsTokens.ROW_PADDING)
    val separator = look.color(SettingsTokens.ROW_SEPARATOR)
    val my = dev.launcher.app.design.MaterialYou
    // Where it starts: the colour set now, else what the palette is made of now.
    val start = remember(t) {
        val now = if (t == SEED) draft.seedColor() else draft.keyColor(t)
        val shade = when (t) { 'P' -> "accent1"; 'S' -> "accent2"; 'T' -> "accent3"; 'N' -> "neutral1"; 'V' -> "neutral2"; else -> "accent1" }
        now ?: Design.previewPalette("system_${shade}_500", draft.youChoice()) ?: 0xFF3478F6.toInt()
    }
    val lch = remember(t) { my.toLch(start) }
    var hue by remember(t) { androidx.compose.runtime.mutableFloatStateOf(lch.third.toFloat()) }
    var chroma by remember(t) { androidx.compose.runtime.mutableFloatStateOf(lch.second.toFloat().coerceIn(0f, MAX_CHROMA)) }
    fun set(h: Float, c: Float) {
        hue = h; chroma = c
        val col = my.lch(KEY_TONE, c.toDouble(), h.toDouble())
        if (t == SEED) draft.chooseSeed(col) else draft.chooseKey(t, col)
    }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, look.motion(MotionTokens.APPEAR_FADE)) }
    val name = if (t == SEED) "Your colour" else my.KEY_PALETTES.firstOrNull { it.first == t }?.second ?: ""
    val own = if (t == SEED) true else draft.keyColor(t) != null
    Column(Modifier.fillMaxWidth().graphicsLayer { alpha = appear.value }
        .drawBehind { drawRect(separator, Offset(pad.toPx(), 0f), Size(size.width - pad.toPx(), 1f)) }
        .padding(horizontal = pad, vertical = look.dp(10f * look.unit))) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText(name, style = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_LABEL)), modifier = Modifier.weight(1f))
            if (own) {
                TextButton("Reset", null) {
                    if (t == SEED) draft.chooseSeed(null) else draft.chooseKey(t, null)
                    done()
                }
                Spacer(Modifier.width(look.dp(12f * look.unit)))
            }
            TextButton("Done", null) { done() }
        }
        Spacer(Modifier.height(look.dp(6f * look.unit)))
        // Colours to start from: a circle of hues.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (h in PRESET_HUES) {
                val c = remember(h) { Color(my.lch(KEY_TONE, PRESET_CHROMA.toDouble(), h.toDouble())) }
                val near = kotlin.math.abs(((hue - h + 540f) % 360f) - 180f) < 8f && kotlin.math.abs(chroma - PRESET_CHROMA) < 4f
                val ring by animateFloatAsState(if (near) 1f else 0f, look.motion(MotionTokens.CC_TOGGLE), label = "preset")
                val accent = look.color(SettingsTokens.ACCENT)
                Canvas(Modifier.size(look.dp(30f * look.unit)).pointerInput(h) { rowTap({}) { set(h, PRESET_CHROMA) } }) {
                    drawCircle(c, radius = size.minDimension / 2f - 3f * look.unit * ring)
                    if (ring > 0.001f) drawCircle(accent.copy(alpha = accent.alpha * ring), radius = size.minDimension / 2f - 0.8f * look.unit, style = Stroke(1.6f * look.unit))
                }
            }
        }
        Spacer(Modifier.height(look.dp(8f * look.unit)))
        BasicText("Hue", style = look.text(SettingsTokens.ROW_DETAIL, look.color(SettingsTokens.ROW_DETAIL_COLOR)))
        val hues = remember { Brush.horizontalGradient((0..12).map { Color(my.lch(KEY_TONE, 50.0, it * 30.0)) }) }
        val now = Color(my.lch(KEY_TONE, chroma.toDouble(), hue.toDouble()))
        ColourSlider(hue / 360f, hues, now) { v -> set(v * 360f, chroma) }
        BasicText("Colourfulness", style = look.text(SettingsTokens.ROW_DETAIL, look.color(SettingsTokens.ROW_DETAIL_COLOR)))
        val chromas = remember(hue.roundToInt()) { Brush.horizontalGradient(listOf(Color(my.lch(KEY_TONE, 0.0, hue.toDouble())), Color(my.lch(KEY_TONE, MAX_CHROMA.toDouble(), hue.toDouble())))) }
        ColourSlider(chroma / MAX_CHROMA, chromas, now) { v -> set(hue, v * MAX_CHROMA) }
    }
}

/** A key colour's lightness as the editor shows it (a palette takes only its hue and colourfulness). */
private const val KEY_TONE = 60.0
/** The editor's most colourful (CIELAB chroma; a colour past what the screen shows at that hue is brought within it). */
private const val MAX_CHROMA = 90f
private const val PRESET_CHROMA = 50f
private val PRESET_HUES = listOf(25f, 55f, 85f, 135f, 185f, 215f, 255f, 285f, 315f, 345f)

/** A slider over a coloured track ([brush]) with a knob in the colour it makes ([knob]): the colour editor's. [value] 0..1. */
@Composable
private fun ColourSlider(value: Float, brush: Brush, knob: Color, onChange: (Float) -> Unit) {
    val look = LocalLook.current
    val change = androidx.compose.runtime.rememberUpdatedState(onChange)
    val ring = look.color(SettingsTokens.TOGGLE_KNOB)
    val knobR = 13f * look.unit
    Canvas(Modifier.fillMaxWidth().height(look.dp(SettingsTokens.TARGET)).pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val w = size.width - knobR * 2f
            fun at(x: Float) = ((x - knobR) / w).coerceIn(0f, 1f)
            val slop = viewConfiguration.touchSlop
            var dragging = false
            while (true) {
                val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                val d = c.position - down.position
                if (!dragging) {
                    if (kotlin.math.abs(d.y) > slop && kotlin.math.abs(d.y) > kotlin.math.abs(d.x)) break   // the page's scroll
                    if (kotlin.math.abs(d.x) > slop) dragging = true
                }
                if (dragging) { c.consume(); change.value(at(c.position.x)) }
                if (!c.pressed) { if (!dragging) change.value(at(c.position.x)); break }
            }
        }
    }) {
        val cy = size.height / 2f
        val th = 10f * look.unit
        val w = size.width - knobR * 2f
        val x = knobR + w * value.coerceIn(0f, 1f)
        drawRoundRect(brush, Offset(knobR, cy - th / 2f), Size(w, th), CornerRadius(th / 2f))
        drawCircle(Color.Black.copy(alpha = 0.12f), knobR, Offset(x, cy + 1.5f * look.unit))
        drawCircle(ring, knobR, Offset(x, cy))
        drawCircle(knob, knobR - 3f * look.unit, Offset(x, cy))
    }
}

/** Material You's style: the phone's own palette, or one made here from the wallpaper in Android's styles. */
@Composable
private fun ColourStyleRow(draft: Draft) {
    val look = LocalLook.current
    val pad = look.dp(SettingsTokens.ROW_PADDING)
    val separator = look.color(SettingsTokens.ROW_SEPARATOR)
    val dim by animateFloatAsState(if (draft.wallpaperColors()) 1f else 0.45f, look.motion(MotionTokens.CC_TOGGLE), label = "dim")
    val styles = dev.launcher.app.design.MaterialYou.Style.entries
    Column(Modifier.fillMaxWidth()
        .drawBehind { drawRect(separator, Offset(pad.toPx(), 0f), Size(size.width - pad.toPx(), 1f)) }
        .graphicsLayer { alpha = dim }
        .padding(horizontal = pad, vertical = look.dp(10f * look.unit))) {
        BasicText("Style", style = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_LABEL)))
        Spacer(Modifier.height(look.dp(8f * look.unit)))
        ChoiceChips(styles.map { it.title }, styles.indexOf(draft.colorStyle())) { i -> draft.chooseColorStyle(styles[i]) }
    }
}

/**
 * A size (the settings' Size): its name, how big it is now ("the phone's" while it follows the phone), and a slider over
 * [min]..[max] in 5 % steps.
 */
@Composable
private fun SizeRow(title: String, value: Float, phone: Boolean, min: Float, max: Float, onChange: (Float) -> Unit) {
    val look = LocalLook.current
    val pad = look.dp(SettingsTokens.ROW_PADDING)
    val separator = look.color(SettingsTokens.ROW_SEPARATOR)
    Column(Modifier.fillMaxWidth()
        .drawBehind { drawRect(separator, Offset(pad.toPx(), 0f), Size(size.width - pad.toPx(), 1f)) }
        .padding(horizontal = pad, vertical = look.dp(10f * look.unit))) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText(title, style = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_LABEL)), modifier = Modifier.weight(1f))
            Rolling((if (phone) "The phone's · " else "") + "${(value * 100).roundToInt()} %", look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_DETAIL_COLOR)), Modifier)
        }
        Spacer(Modifier.height(look.dp(6f * look.unit)))
        SettingsSlider((value - min) / (max - min)) { t -> onChange(Draft.snap(min + t * (max - min)).coerceIn(min, max)) }
    }
}

/** How colourful the wallpaper's palette is: a slider from grey through the style's own to twice that. */
@Composable
private fun IntensityRow(draft: Draft) {
    val look = LocalLook.current
    val pad = look.dp(SettingsTokens.ROW_PADDING)
    val separator = look.color(SettingsTokens.ROW_SEPARATOR)
    val dim by animateFloatAsState(if (draft.wallpaperColors()) 1f else 0.45f, look.motion(MotionTokens.CC_TOGGLE), label = "dim")
    val k = draft.colorIntensity()
    Column(Modifier.fillMaxWidth()
        .drawBehind { drawRect(separator, Offset(pad.toPx(), 0f), Size(size.width - pad.toPx(), 1f)) }
        .graphicsLayer { alpha = dim }
        .padding(horizontal = pad, vertical = look.dp(10f * look.unit))) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText("Intensity", style = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_LABEL)), modifier = Modifier.weight(1f))
            Rolling("${(k * 100).roundToInt()} %", look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_DETAIL_COLOR)), Modifier)
        }
        Spacer(Modifier.height(look.dp(6f * look.unit)))
        SettingsSlider(k / 2f) { v -> draft.chooseColorIntensity((v * 2f * 20f).roundToInt() / 20f) }
    }
}

/**
 * A slider (iOS's): a thin track filled in the accent up to a round knob. A drag along it moves the knob under the finger
 * (a drag that starts mostly up or down is the page's scroll); a tap moves it there. [value] 0..1.
 */
@Composable
private fun SettingsSlider(value: Float, onChange: (Float) -> Unit) {
    val look = LocalLook.current
    val track = look.color(SettingsTokens.CHIP_FILL)
    val accent = look.color(SettingsTokens.ACCENT)
    val knob = look.color(SettingsTokens.TOGGLE_KNOB)
    val change = androidx.compose.runtime.rememberUpdatedState(onChange)
    val shown by animateFloatAsState(value, look.motion(MotionTokens.CC_LEVEL), label = "slider")
    val knobR = 13f * look.unit
    Canvas(Modifier.fillMaxWidth().height(look.dp(SettingsTokens.TARGET)).pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val w = size.width - knobR * 2f
            fun at(x: Float) = ((x - knobR) / w).coerceIn(0f, 1f)
            val slop = viewConfiguration.touchSlop
            var dragging = false
            while (true) {
                val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                val d = c.position - down.position
                if (!dragging) {
                    if (kotlin.math.abs(d.y) > slop && kotlin.math.abs(d.y) > kotlin.math.abs(d.x)) break   // the page's scroll
                    if (kotlin.math.abs(d.x) > slop) dragging = true
                }
                if (dragging) { c.consume(); change.value(at(c.position.x)) }
                if (!c.pressed) { if (!dragging) change.value(at(c.position.x)); break }
            }
        }
    }) {
        val cy = size.height / 2f
        val th = 4f * look.unit
        val w = size.width - knobR * 2f
        val x = knobR + w * shown.coerceIn(0f, 1f)
        drawRoundRect(track, Offset(knobR, cy - th / 2f), Size(w, th), CornerRadius(th / 2f))
        drawRoundRect(accent, Offset(knobR, cy - th / 2f), Size(x - knobR, th), CornerRadius(th / 2f))
        drawCircle(Color.Black.copy(alpha = 0.12f), knobR, Offset(x, cy + 1.5f * look.unit))
        drawCircle(knob, knobR, Offset(x, cy))
    }
}

/** The font families offered for a font token: the bundled, the phone's, and a few from Google Fonts. */
private val FONT_FAMILIES = listOf(
    "inter" to "Inter", "system" to "The phone's", "system-serif" to "Serif", "system-mono" to "Mono",
    "google:DM Sans" to "DM Sans", "google:Manrope" to "Manrope", "google:Outfit" to "Outfit", "google:Lora" to "Lora",
)

/** One font token's family, as chips each written in its own font. */
@Composable
private fun FontRow(title: String, key: String, draft: Draft) {
    val look = LocalLook.current
    val counter = LocalRowCounter.current
    val first = remember { counter == null || counter.n++ == 0 }
    val pad = look.dp(SettingsTokens.ROW_PADDING)
    val separator = look.color(SettingsTokens.ROW_SEPARATOR)
    val ctx = LocalContext.current
    val search = LocalFontSearch.current
    val chosen = draft.font(key)
    LaunchedEffect(Unit) { FontPicks.load(ctx) }
    // The offered ones, then Google families found with the search (on either row), and the one chosen if it is neither.
    val ids = (FONT_FAMILIES.map { it.first } + FontPicks.list.map { "google:$it" } + chosen).distinct()
    val faces = ids.map { id -> androidx.compose.runtime.key(id) { rememberFace(id) } }
    // A Google family shows once its face is here (it arrives in its own font, never in another first); the chosen at once.
    val shown = ids.indices.filter { i -> !ids[i].startsWith("google:") || faces[i] != null || ids[i] == chosen }
    fun nameOf(id: String) = FONT_FAMILIES.firstOrNull { it.first == id }?.second ?: id.removePrefix("google:")
    Column(Modifier.fillMaxWidth()
        .drawBehind { if (!first) drawRect(separator, Offset(pad.toPx(), 0f), Size(size.width - pad.toPx(), 1f)) }
        .padding(horizontal = pad, vertical = look.dp(10f * look.unit))) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText(title, style = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_LABEL)), modifier = Modifier.weight(1f))
            // Any family on Google Fonts (a sheet over the settings); in sight on the row's own line.
            TextButton("More Fonts", R.drawable.sym_search) {
                search?.open(FontSearch.Request("$title font", { draft.font(key) }, { id -> draft.chooseFont(key, id) }))
            }
        }
        Spacer(Modifier.height(look.dp(8f * look.unit)))
        ChoiceChips(shown.map { nameOf(ids[it]) }, shown.indexOfFirst { ids[it] == chosen }, fonts = shown.map { faces[it] }) { i ->
            draft.chooseFont(key, ids[shown[i]])
        }
    }
}

/** A button in words (and a symbol) in the accent, as iOS's plain buttons: pressed, it dims. */
@Composable
private fun TextButton(text: String, symbol: Int?, onClick: () -> Unit) {
    val look = LocalLook.current
    var pressed by remember { mutableStateOf(false) }
    val press by animateFloatAsState(if (pressed) 1f else 0f, look.motion(if (pressed) MotionTokens.PRESS_IN else MotionTokens.PRESS_OUT), label = "press")
    val accent = look.color(SettingsTokens.ACCENT)
    // Its touch area the touch target's height and a little wider than its words.
    Row(Modifier.graphicsLayer { alpha = 1f - 0.65f * press }
        .heightIn(min = look.dp(SettingsTokens.TARGET))
        .pointerInput(Unit) { rowTap({ pressed = it }, onClick) }
        .padding(start = look.dp(8f * look.unit)),
        verticalAlignment = Alignment.CenterVertically) {
        if (symbol != null) {
            Image(painterResource(symbol), null, colorFilter = ColorFilter.tint(accent), modifier = Modifier.size(look.dp(16f * look.unit)))
            Spacer(Modifier.width(look.dp(4f * look.unit)))
        }
        BasicText(text, style = look.text(SettingsTokens.ROW_DETAIL, accent), maxLines = 1)
    }
}

/** The themes side by side, each as home looks in it; the chosen one ringed in the accent. */
@Composable
private fun ThemeCards(themes: List<Design.ThemeInfo>, draft: Draft) {
    val look = LocalLook.current
    val pad = look.dp(SettingsTokens.ROW_PADDING)
    Row(Modifier.fillMaxWidth().padding(pad), horizontalArrangement = Arrangement.spacedBy(look.dp(14f * look.unit))) {
        for (t in themes) {
            val chosen = draft.themeId() == t.id
            val k by animateFloatAsState(if (chosen) 1f else 0f, look.motion(MotionTokens.CC_TOGGLE), label = "chosen")
            var pressed by remember { mutableStateOf(false) }
            val press by animateFloatAsState(if (pressed) 1f else 0f, look.motion(if (pressed) MotionTokens.PRESS_IN else MotionTokens.PRESS_OUT), label = "press")
            val accent = look.color(SettingsTokens.ACCENT)
            val w = 84f * look.unit
            val h = w * look.screenH / look.screenW
            val corner = w * Design.num(SettingsTokens.PREVIEW_CORNER)
            Column(Modifier.pressScale { 1f - 0.05f * press }.pointerInput(t.id) { rowTap({ pressed = it }) { draft.chooseTheme(t.id) } },
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(look.dp(w), look.dp(h))
                    .drawBehind {
                        // The ring around the chosen one grows in.
                        if (k > 0.001f) {
                            val r = 4f * look.unit * k
                            drawRoundRect(accent.copy(alpha = accent.alpha * k), Offset(-r, -r), Size(size.width + r * 2f, size.height + r * 2f),
                                CornerRadius(corner + r), style = Stroke(2.5f * look.unit))
                        }
                    }
                    .clip(RoundedCornerShape(look.dp(corner)))) {
                    ThemeThumb(t.id)
                }
                Spacer(Modifier.height(look.dp(8f * look.unit)))
                BasicText(t.name, style = look.text(SettingsTokens.ROW_DETAIL, mix(look.color(SettingsTokens.ROW_LABEL), accent, k)), maxLines = 1)
            }
        }
    }
}

/** Home as it looked in theme [id]; one not seen yet: the wallpaper under that theme's sheet colour and accent. */
@Composable
private fun ThemeThumb(id: String) {
    val look = LocalLook.current
    val pic = HomePictures.byTheme[id]
    if (pic != null) { Image(pic.asImageBitmap(), null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()); return }
    val sheet = Design.previewColor(id, SettingsTokens.SHEET_FILL)?.let { Color(it) } ?: look.color(SettingsTokens.SHEET_FILL)
    val accent = Design.previewColor(id, SettingsTokens.ACCENT)?.let { Color(it) } ?: look.color(SettingsTokens.ACCENT)
    Canvas(Modifier.fillMaxSize()) {
        val wp = look.wallpaper
        if (wp != null) drawIntoCanvas { c ->
            val m = wp.matrix(look.screenW, look.screenH)
            m.postScale(size.width / look.screenW, size.height / look.screenH)
            c.nativeCanvas.drawBitmap(wp.bitmap, m, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        } else drawRect(look.color(SettingsTokens.BACKGROUND))
        drawRoundRect(sheet, Offset(size.width * 0.08f, size.height * 0.58f), Size(size.width * 0.84f, size.height * 0.36f), CornerRadius(size.width * 0.1f))
        drawCircle(accent, size.width * 0.08f, Offset(size.width * 0.5f, size.height * 0.76f))
    }
}

/** One part's own theme: Follow (the main one) or any theme. */
@Composable
private fun ElementTheme(e: Element, themes: List<Design.ThemeInfo>, draft: Draft) {
    val look = LocalLook.current
    val counter = LocalRowCounter.current
    val first = remember { counter == null || counter.n++ == 0 }
    val pad = look.dp(SettingsTokens.ROW_PADDING)
    val separator = look.color(SettingsTokens.ROW_SEPARATOR)
    val own = draft.elementTheme(e)
    Column(Modifier.fillMaxWidth()
        .drawBehind { if (!first) drawRect(separator, Offset(pad.toPx(), 0f), Size(size.width - pad.toPx(), 1f)) }
        .padding(horizontal = pad, vertical = look.dp(10f * look.unit))) {
        BasicText(e.title, style = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_LABEL)))
        Spacer(Modifier.height(look.dp(8f * look.unit)))
        val names = listOf("Follow") + themes.map { it.name }
        val at = if (own == null) 0 else 1 + themes.indexOfFirst { it.id == own }.coerceAtLeast(0)
        ChoiceChips(names, at) { i -> draft.chooseElement(e, if (i == 0) null else themes[i - 1].id) }
    }
}

/**
 * A row of chips, one chosen (its fill blends to the accent); [fonts]: each chip's name in a face of its own. A chip added
 * after the row first showed grows in at its place (the others make room on the same motion).
 */
@Composable
internal fun ChoiceChips(names: List<String>, chosen: Int, modifier: Modifier = Modifier,
                         fonts: List<androidx.compose.ui.text.font.FontFamily?>? = null, onChoose: (Int) -> Unit) {
    val look = LocalLook.current
    val scroll = rememberScrollState()
    // The chosen chip kept in sight (glides there when it is chosen near an edge, or arrives there).
    val xs = remember { HashMap<String, IntRange>() }
    val born = remember { booleanArrayOf(false) }
    androidx.compose.runtime.SideEffect { born[0] = true }
    val chosenName = names.getOrNull(chosen)
    // Kept in sight as the row changes: another chosen, a chip added or a face arrived (chips grow in, and one in its own
    // font is wider than in the fallback, so the chosen one moves for a moment), and when the row is first placed (a page
    // is built ahead and placed only as it opens). For that moment the scroll follows the chosen chip where it is on each
    // frame, on one spring retargeted as it moves (a single scroll measured before a chip grew in stopped short of it).
    // Not seen yet, it is there at once (the page opens with it in sight). A finger on the row takes over.
    val seen = remember { booleanArrayOf(false) }
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(chosenName, names.size, fonts?.count { it != null }, placed) {
        if (!placed) return@LaunchedEffect
        val glide = Animatable(scroll.value.toFloat())
        var target = Float.NaN
        val until = android.os.SystemClock.uptimeMillis() + FOLLOW_MS
        kotlinx.coroutines.coroutineScope {
            var job: kotlinx.coroutines.Job? = null
            while (android.os.SystemClock.uptimeMillis() < until && !scroll.isScrollInProgress) {
                androidx.compose.runtime.withFrameNanos { }
                val r = xs[chosenName] ?: continue
                val view = scroll.viewportSize
                if (view <= 0) continue
                val want = when {
                    r.first < scroll.value -> r.first
                    r.last > scroll.value + view -> r.last - view
                    else -> continue
                }.toFloat()
                if (!seen[0]) { scroll.dispatchRawDelta(want - scroll.value); continue }
                if (want == target) continue
                target = want
                job?.cancel()
                job = launch {
                    val me = coroutineContext[kotlinx.coroutines.Job]
                    glide.animateTo(want, look.motion(MotionTokens.NAV_PUSH)) {
                        if (scroll.isScrollInProgress) me?.cancel() else scroll.dispatchRawDelta(value - scroll.value)
                    }
                }
            }
            seen[0] = true
            if (scroll.isScrollInProgress) job?.cancel() else job?.join()
        }
    }
    val gap = 6f * look.unit
    val corner = look.pt(SettingsTokens.CHIP_CORNER)
    // Drawn at the chip's height, touched across the touch target's (a 32 pt chip was a small target).
    val chipH = look.pt(SettingsTokens.CHIP_HEIGHT)
    val targetH = maxOf(look.pt(SettingsTokens.TARGET), chipH)
    Row(Modifier.onPlaced { if (!placed) placed = true }.horizontalScroll(scroll).then(modifier), verticalAlignment = Alignment.CenterVertically) {
        names.forEachIndexed { i, n ->
            androidx.compose.runtime.key(n) {
                val k by animateFloatAsState(if (i == chosen) 1f else 0f, look.motion(MotionTokens.CC_TOGGLE), label = "chip")
                var pressed by remember { mutableStateOf(false) }
                val press by animateFloatAsState(if (pressed) 1f else 0f, look.motion(if (pressed) MotionTokens.PRESS_IN else MotionTokens.PRESS_OUT), label = "press")
                val fill = mix(look.color(SettingsTokens.CHIP_FILL), look.color(SettingsTokens.ACCENT), k)
                val text = mix(look.color(SettingsTokens.CHIP_TEXT_COLOR), look.color(SettingsTokens.ACCENT_LABEL), k)
                val slot = remember { IntArray(1) }
                // One height for every chip, the name centred in it (a face of its own may be taller: a script).
                Box(Modifier
                    // Where it will be once in (a chip growing in is read where it ends: its gap still opening, it
                    // was scrolled to short of its end).
                    .onPlaced { co -> slot[0] = co.positionInParent().x.toInt() }
                    .chipArrival(born[0], if (i > 0) gap else 0f)
                    .pressScale { 1f - 0.05f * press }
                    .drawBehind {
                        val r = minOf(corner, chipH / 2f)
                        drawRoundRect(fill, Offset(0f, (size.height - chipH) / 2f), Size(size.width, chipH), CornerRadius(r))
                    }
                    .pointerInput(i, chosen) { rowTap({ pressed = it }) { if (i != chosen) onChoose(i) } }
                    .onPlaced { co -> val x = slot[0] + (if (i > 0) gap.roundToInt() else 0); xs[n] = x..(x + co.size.width) }
                    .height(look.dp(targetH))
                    .padding(horizontal = look.dp(12f * look.unit)),
                    contentAlignment = Alignment.Center) {
                    BasicText(n, style = look.text(SettingsTokens.CHIP_TEXT, text).let { s -> fonts?.getOrNull(i)?.let { s.copy(fontFamily = it) } ?: s }, maxLines = 1)
                }
            }
        }
    }
}

/** How long a chip row keeps its chosen chip in sight after a change (chips growing in move it meanwhile). */
private const val FOLLOW_MS = 900L

/**
 * A chip's room in its row ([lead]: the gap before it): there from the start in a row composed with it; one added later
 * grows in (its room opening as it scales and fades up), on the appear role.
 */
private fun Modifier.chipArrival(rowShown: Boolean, lead: Float): Modifier = composed {
    val look = LocalLook.current
    val k = remember { Animatable(if (rowShown) 0f else 1f) }
    LaunchedEffect(Unit) { if (k.value < 1f) k.animateTo(1f, look.motion(MotionTokens.APPEAR)) }
    layout { m, c ->
        val p = m.measure(c)
        val a = k.value.coerceIn(0f, 1f)
        layout(((lead + p.width) * a).roundToInt(), p.height) {
            p.placeWithLayer((lead * a).roundToInt(), 0) {
                alpha = a
                scaleX = 0.7f + 0.3f * a
                scaleY = scaleX
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
        }
    }
}

/**
 * Motion (docs/PLAN_SETTINGS.md S4): the animation presets as cards, each drawing its own curve; the picture of home above
 * plays an app opening and closing on the chosen one. Waits for Apply like the rest.
 */
@Composable
private fun MotionPage() {
    val draft = LocalDraft.current
    val ctx = LocalContext.current
    val presets = remember(LocalLook.current.version) { Design.motionPresets() }
    Group("Animations", "How everything moves. The picture above plays the one you choose.") {
        PresetCards(presets, draft)
    }
    Group("Every animation", "Each one can be retimed on its own: a spring or a curve.") {
        NavRow("Each animation", "In Expert, for now") {
            ctx.startActivity(Intent(ctx, dev.launcher.app.design.DesignActivity::class.java))
        }
    }
}

/** The presets side by side, each with the curve its apps open on; the chosen one ringed in the accent. */
@Composable
private fun PresetCards(presets: List<Design.ThemeInfo>, draft: Draft) {
    val look = LocalLook.current
    val pad = look.dp(SettingsTokens.ROW_PADDING)
    val accent = look.color(SettingsTokens.ACCENT)
    val line = look.color(SettingsTokens.ROW_DETAIL_COLOR)
    Row(Modifier.fillMaxWidth().padding(pad), horizontalArrangement = Arrangement.spacedBy(look.dp(12f * look.unit))) {
        for (p in presets) {
            val chosen = draft.motionId() == p.id
            val k by animateFloatAsState(if (chosen) 1f else 0f, look.motion(MotionTokens.CC_TOGGLE), label = "chosen")
            var pressed by remember { mutableStateOf(false) }
            val press by animateFloatAsState(if (pressed) 1f else 0f, look.motion(if (pressed) MotionTokens.PRESS_IN else MotionTokens.PRESS_OUT), label = "press")
            // The curve its apps open on, sampled until it comes to rest (a spring overshoots above the line).
            val points = remember<List<Pair<Float, Float>>>(p.id, look.version) {
                val c = Design.previewCurve(p.id, MotionTokens.APP_OPEN) ?: return@remember emptyList()
                val m = c.mover().apply { start(0f, 0f, 1f) }
                var end = 0.2
                while (end < 2.0 && !m.settled(end, 0.002f)) end += 0.05
                (0..48).map { i -> val t = end * i / 48.0; (i / 48f) to m.value(t) }
            }
            val corner = look.pt(SettingsTokens.TILE_CORNER) * 0.7f
            Column(Modifier.weight(1f).pressScale { 1f - 0.04f * press }
                .pointerInput(p.id) { rowTap({ pressed = it }) { draft.chooseMotion(p.id) } }) {
                Canvas(Modifier.fillMaxWidth().height(look.dp(84f * look.unit))) {
                    drawRoundRect(look.color(SettingsTokens.CHIP_FILL), cornerRadius = CornerRadius(corner))
                    if (k > 0.001f) drawRoundRect(accent.copy(alpha = accent.alpha * k), cornerRadius = CornerRadius(corner), style = Stroke(2.5f * look.unit))
                    if (points.size > 1) {
                        val inset = 14f * look.unit
                        val w = size.width - inset * 2f
                        val h = size.height - inset * 2f
                        val path = androidx.compose.ui.graphics.Path()
                        points.forEachIndexed { i, (x, y) ->
                            val px = inset + x * w
                            val py = inset + h - y.coerceIn(-0.2f, 1.25f) * h * 0.8f
                            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                        }
                        drawPath(path, mix(line, accent, k), style = Stroke(2.5f * look.unit, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    }
                }
                Spacer(Modifier.height(look.dp(8f * look.unit)))
                BasicText(p.name, style = look.text(SettingsTokens.ROW_DETAIL, mix(look.color(SettingsTokens.ROW_LABEL), accent, k)), maxLines = 1)
            }
        }
    }
}

/**
 * Content scrolled up dissolves under the top edge: drawn clipped below an [fadePx] band, and only that band through a layer
 * of its own (the content is a layer: drawing it twice draws one list twice); the whole page in one cost a sheet-sized
 * buffer every frame of a scroll.
 */
private fun Modifier.topFade(scroll: IosScrollState, unit: Float, fadePx: Float, paint: androidx.compose.ui.graphics.Paint): Modifier = composed {
    // Its gradient made once (one made every frame of a scroll was native memory every frame).
    val top = 6f * unit
    val end = top + fadePx
    val brush = remember(top, end) { Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = top, endY = end) }
    drawWithContent {
        clipRect(top = end) { this@drawWithContent.drawContent() }
        if (scroll.offset > 0f) drawIntoCanvas { cv ->
            cv.saveLayer(Rect(0f, top, size.width, end), paint)
            clipRect(top = top, bottom = end) { this@drawWithContent.drawContent() }
            drawRect(brush, topLeft = Offset(0f, top), size = Size(size.width, fadePx), blendMode = BlendMode.DstIn)
            cv.restore()
        } else clipRect(top = top, bottom = end) { this@drawWithContent.drawContent() }
    }
}

/** The elements' short names, for the picker at the top of Layouts. */
private val Element.short get() = when (this) {
    Element.STATUS_BAR -> "Status bar"
    Element.SHADE -> "Shade"
    Element.RECENTS -> "Recents"
    Element.DRAWER -> "Drawer"
    Element.HOME -> "Home"
    Element.SETTINGS -> "Settings"
}

/**
 * Layouts: the elements in a picker at the top (it stays while the rest scrolls), the chosen one's layouts, place and options
 * below; the picture of home above focuses on the chosen one. Another element: its page slides in from its side as the last
 * one slides away (each its own spring, interruptible), and the picture moves to it. (Matheesha: following the scroll
 * through one long page to decide the focus was odd.)
 */
@Composable
private fun LayoutsPager(st: Studio, g: Geometry, fadePx: Float, bandPaint: androidx.compose.ui.graphics.Paint) {
    val look = LocalLook.current
    val scope = rememberCoroutineScope()
    val spec = androidx.compose.runtime.rememberUpdatedState(look.motion<Float>(MotionTokens.NAV_PUSH))
    val m = look.dp(SettingsTokens.MARGIN)
    val slide = 48f * g.unit
    val choose: (Element) -> Unit = remember(st, scope) {
        { e ->
            val was = st.element
            if (e != was) {
                st.element = e
                for (o in Element.entries) {
                    val shown = st.elementShown.getValue(o)
                    // The one coming in comes from the side it is on (from the last one); the ones going leave towards theirs.
                    if (o == e) st.elementSide.getValue(o).floatValue = if (o.ordinal > was.ordinal) 1f else -1f
                    else if (shown.value > 0.001f || shown.isRunning) st.elementSide.getValue(o).floatValue = if (o.ordinal > e.ordinal) 1f else -1f
                    scope.launch { try { shown.animateTo(if (o == e) 1f else 0f, spec.value) } catch (_: kotlinx.coroutines.CancellationException) { } }
                }
            }
        }
    }
    val pickerTop = 24f * g.unit
    val pickerH = look.pt(SettingsTokens.SEGMENT_HEIGHT)
    val contentTop = pickerTop + pickerH + 10f * g.unit
    Box(Modifier.fillMaxSize()) {
        for (e in Element.entries) ElementColumn(st, e, g, contentTop, slide, fadePx, m)
        // The picker, over the pages' tops: a segmented control (draft C), the page's main choice. It comes and goes with the
        // page, as its first group (it showed whole from the first frame of the opening, over the tiles).
        val shownNow = LocalShown.current
        Box(Modifier.zIndex(2f).padding(top = look.dp(pickerTop)).fillMaxWidth().height(look.dp(pickerH))
            .graphicsLayer {
                val k = ((shownNow() - 0.3f) / 0.45f).coerceIn(0f, 1f)
                alpha = k
                translationY = (1f - k) * 22f * look.unit
            }
            .padding(horizontal = m),
            contentAlignment = Alignment.Center) {
            ElementPicker(st, choose)
        }
    }
}

/** The elements' symbols, for the picker at the top of Layouts. */
private val Element.symbol get() = when (this) {
    Element.STATUS_BAR -> R.drawable.sym_statusbar
    Element.SHADE -> R.drawable.sym_bell
    Element.RECENTS -> R.drawable.sym_recents
    Element.DRAWER -> R.drawable.sym_apps
    Element.HOME -> R.drawable.sym_home
    Element.SETTINGS -> R.drawable.sym_settings
}

/** The picker of Layouts (Matheesha chose B: symbols, the chosen one with its name), reading the choice in its own scope. */
@Composable
private fun ElementPicker(st: Studio, choose: (Element) -> Unit) {
    SymbolSegmented(Element.entries.map { it.short }, Element.entries.map { it.symbol }, st.element.ordinal) { i -> choose(Element.entries[i]) }
}

/**
 * The picker of Layouts: option B (Matheesha's choice), at the touch rules' sizes. Every choice is its symbol; the chosen
 * one is wider, on the thumb, with its name beside its symbol. The others are each at least a 44 pt target (on the S24
 * 47 x 48 pt: the room left once the longest name fits), symbols 22 pt, the name 13 pt semibold. One spring moves it all:
 * the thumb slides and takes the new one's width, the old name fades as its segment narrows, the new one's comes as it
 * widens. A tap chooses; a drag along it carries the thumb under the finger and chooses where it is let go.
 */
@Composable
private fun SymbolSegmented(names: List<String>, symbols: List<Int>, chosen: Int, onChoose: (Int) -> Unit) {
    val look = LocalLook.current
    val n = names.size
    val at = remember { Animatable(chosen.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    val spec = androidx.compose.runtime.rememberUpdatedState(look.motion<Float>(MotionTokens.NAV_PUSH))
    val scope = rememberCoroutineScope()
    LaunchedEffect(chosen) { if (!dragging) at.animateTo(chosen.toFloat(), spec.value) }
    val measurer = rememberTextMeasurer()
    val label = look.color(SettingsTokens.ROW_LABEL)
    val muted = look.color(SettingsTokens.ROW_DETAIL_COLOR)
    val style = look.text(SettingsTokens.SEGMENT_TEXT, label).copy(fontWeight = androidx.compose.ui.text.font.FontWeight(600))
    val painters = symbols.map { painterResource(it) }
    val track = look.color(SettingsTokens.SEGMENT_TRACK)
    val thumbFill = look.color(SettingsTokens.SEGMENT_THUMB)
    val inset = 3f * look.unit
    val iconSize = look.pt(SettingsTokens.SEGMENT_SYMBOL)
    val gap = 6f * look.unit
    val pad = 12f * look.unit
    val target = look.pt(SettingsTokens.TARGET)
    var width by remember { mutableIntStateOf(0) }
    // The widths: the others share what the chosen one leaves once the longest name fits it, each at least a target; on a
    // phone too narrow for that, the names are set smaller until they fit.
    val layout = remember(names, look, width) {
        var texts = names.map { measurer.measure(it, style, maxLines = 1) }
        val w = width.toFloat()
        var base = if (w <= 0f) target else (w - (iconSize + gap + texts.maxOf { it.size.width } + pad * 2f)) / (n - 1)
        if (w > 0f && base < target) {
            base = target
            val room = w - (n - 1) * base - iconSize - gap - pad * 2f
            val widest = texts.maxOf { it.size.width }
            if (room > 0f && widest > room) texts = names.map { measurer.measure(it, style.copy(fontSize = style.fontSize * (room / widest)), maxLines = 1) }
        }
        base to texts
    }
    val (base, texts) = layout
    val choose = androidx.compose.runtime.rememberUpdatedState(onChoose)
    // How much of the chosen one's extra room each choice has at [p] (the thumb's place: an index, fractional on the way).
    fun share(i: Int, p: Float) = (1f - kotlin.math.abs(p - i)).coerceIn(0f, 1f)
    Canvas(
        Modifier.fillMaxWidth().height(look.dp(SettingsTokens.SEGMENT_HEIGHT))
            .onSizeChanged { width = it.width }
            .pointerInput(n, base) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val w = size.width.toFloat()
                val slop = viewConfiguration.touchSlop
                var moved = false
                // Where a finger is as a place along the choices (spread evenly over the control).
                fun place(x: Float) = ((x - base / 2f) / (w - base) * (n - 1)).coerceIn(0f, (n - 1).toFloat())
                while (true) {
                    val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    val dx = c.position.x - down.position.x
                    if (!moved && kotlin.math.abs(dx) > slop) { moved = true; dragging = true }
                    if (moved) { c.consume(); scope.launch { at.snapTo(place(c.position.x)) } }
                    if (!c.pressed) break
                }
                // A tap: the choice whose segment it is now (the chosen one wider).
                val to = if (moved) kotlin.math.round(at.value).toInt() else {
                    val extra = w - n * base
                    var x = 0f
                    var hit = n - 1
                    for (i in 0 until n) {
                        val sw = base + extra * share(i, at.value)
                        if (down.position.x < x + sw) { hit = i; break }
                        x += sw
                    }
                    hit
                }
                dragging = false
                scope.launch { at.animateTo(to.toFloat(), spec.value) }
                choose.value(to)
            }
        },
    ) {
        val h = size.height
        val w = size.width
        val p = at.value
        val extra = (w - n * base).coerceAtLeast(0f)
        val r = minOf(look.pt(SettingsTokens.SEGMENT_CORNER), h / 2f)
        drawRoundRect(track, cornerRadius = CornerRadius(r))
        val lefts = FloatArray(n)
        val widths = FloatArray(n)
        var x = 0f
        for (i in 0 until n) { lefts[i] = x; widths[i] = base + extra * share(i, p); x += widths[i] }
        // The thumb: over the segment the place is in, moving into the next with it.
        val i0 = p.toInt().coerceIn(0, n - 1)
        val i1 = (i0 + 1).coerceAtMost(n - 1)
        val f = (p - i0).coerceIn(0f, 1f)
        val tl = lefts[i0] + (lefts[i1] - lefts[i0]) * f
        val tw = widths[i0] + (widths[i1] - widths[i0]) * f
        val tr = (r - inset).coerceAtLeast(0f)
        drawRoundRect(Color.Black.copy(alpha = 0.06f), Offset(tl + inset, inset + 1.5f * look.unit), Size(tw - inset * 2f, h - inset * 2f), CornerRadius(tr))
        drawRoundRect(thumbFill, Offset(tl + inset, inset), Size(tw - inset * 2f, h - inset * 2f), CornerRadius(tr))
        for (i in 0 until n) {
            val k = share(i, p)
            val t = texts[i]
            // The symbol and the name side by side, centred in the segment (the name's room growing with it).
            val content = iconSize + (gap + t.size.width) * k
            val cx = lefts[i] + widths[i] / 2f
            val sx = cx - content / 2f
            translate(sx, (h - iconSize) / 2f) { with(painters[i]) { draw(Size(iconSize, iconSize), colorFilter = ColorFilter.tint(mix(muted, label, k))) } }
            if (k > 0.01f) clipRect(left = lefts[i], right = lefts[i] + widths[i]) {
                drawText(t, topLeft = Offset(sx + iconSize + gap, (h - t.size.height) / 2f), alpha = k * k)
            }
        }
    }
}

/** One element's page on Layouts: it slides in from its side and away, and takes the touches while it is the chosen one. */
@Composable
private fun ElementColumn(st: Studio, e: Element, g: Geometry, contentTop: Float, slide: Float, fadePx: Float, m: androidx.compose.ui.unit.Dp) {
    val look = LocalLook.current
    val shown = st.elementShown.getValue(e)
    val side = st.elementSide.getValue(e)
    val scroll = st.elementScrolls.getValue(e)
    val bandPaint = remember { androidx.compose.ui.graphics.Paint() }
    val chosen by remember(st, e) { androidx.compose.runtime.derivedStateOf { st.element == e } }
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = look.dp(contentTop))
                    // The chosen one takes the touches (the one going is under it while both show).
                    .zIndex(if (chosen) 1f else 0f)
                    .graphicsLayer {
                        val k = shown.value.coerceIn(0f, 1f)
                        // Out of sight once gone (not laid out again when it comes).
                        translationX = if (k <= 0.001f) g.w * 3f else side.floatValue * (1f - k) * slide
                        // Seen only in the upper part of its way: the one going is nearly gone before the one coming shows
                        // (both at half strength, their names over each other, read as a mess).
                        val a = ((k - 0.35f) / 0.65f).coerceIn(0f, 1f)
                        alpha = a * a * (3f - 2f * a)
                    }
                    .topFade(scroll, g.unit, fadePx, bandPaint),
            ) {
                Column(Modifier.fillMaxSize().iosScroll(scroll).padding(horizontal = m)) {
                    Spacer(Modifier.height(look.dp(10f * g.unit)))
                    ElementLayouts(e)
                    // Room below the last row for the Apply bar.
                    Spacer(Modifier.height(look.dp(24f * g.unit + g.navPx + g.inset + g.barRoom)))
                }
            }
}

/** One element on Layouts: its layouts, where it lives, its options. Waits for Apply like the rest. */
@Composable
private fun ElementLayouts(e: Element) {
    val draft = LocalDraft.current
    val current = Layouts.find(e, draft.layout(e)) ?: Setup.layout(e)
    val layouts = Layouts.of(e)
    Group(e.title, current.description) {
        for (l in layouts) CheckRow(l.title, l.origin, l.id == current.id) { draft.chooseLayout(e, l.id) }
    }
    if (current.placements.size > 1) {
        val at = draft.placement(e)
        Group("Where it lives") {
            for (p in current.placements) CheckRow(p.title, p.description, p.id == at) { draft.choosePlacement(e, p.id) }
        }
    }
    val counts = current.options.filterIsInstance<Option.Count>()
    if (counts.isNotEmpty()) {
        val room = roomOf(e, draft, LocalContext.current)
        Group("Grid", roomNote(e, room)) {
            for (o in counts) {
                val v = draft.int(e, o.key, o.default)
                // Up to what fits at the size chosen (Look, Size); a count past it (the size grew since) can only come down.
                val top = minOf(o.max, room[o.key] ?: o.max)
                StepperRow(o.title, o.description, v, minus = v > o.min, plus = v < top) { draft.chooseInt(e, o.key, it, o.default) }
            }
        }
    }
    val toggles = current.options.filterIsInstance<Option.Toggle>()
    if (toggles.isNotEmpty()) Group("Options") {
        for (o in toggles) ToggleRow(o.title, o.description, draft.bool(e, o.key, o.default)) { draft.chooseBool(e, o.key, it, o.default) }
    }
}

/**
 * How many of each of [e]'s counts fit at the size the draft would apply (key: an option's; absent: no limit from the
 * room). Home: columns, rows and dock icons at iOS's icon size on the screen home was last laid out on.
 */
private fun roomOf(e: Element, draft: Draft, ctx: android.content.Context): Map<String, Int> = when (e) {
    Element.HOME -> {
        val f = dev.launcher.app.home.HomeMetrics.frame
        if (f == null) emptyMap() else {
            val r = dev.launcher.app.home.HomeMetrics.room(f, draft.unit(ctx, minOf(f.w, f.h)), draft.bool(e, "show-labels", true), draft.homeText())
            mapOf("columns" to r.columns, "rows" to r.rows, "dock-icons" to r.dock)
        }
    }
    Element.SHADE -> dev.launcher.app.home.HomeMetrics.frame?.let { f ->
        mapOf(dev.launcher.app.shade.CcGrid.COLUMNS to dev.launcher.app.shade.CcGrid.room(minOf(f.w, f.h), draft.unit(ctx, minOf(f.w, f.h))))
    } ?: emptyMap()
    Element.DRAWER -> dev.launcher.app.home.HomeMetrics.frame?.let { f ->
        mapOf("columns" to dev.launcher.app.home.HomeMetrics.roomTiles(minOf(f.w, f.h), draft.unit(ctx, minOf(f.w, f.h))))
    } ?: emptyMap()
    else -> emptyMap()
}

/** What the room allows, in words, under [e]'s grid. */
private fun roomNote(e: Element, room: Map<String, Int>): String? = when {
    e == Element.HOME && room.isNotEmpty() ->
        "At this size there is room for ${room["columns"]} across, ${room["rows"]} down and ${room["dock-icons"]} in the dock. A smaller size (Look, Size) makes room for more."
    e == Element.SHADE && room.isNotEmpty() ->
        "At this size there is room for ${room.values.first()} controls across. A smaller size (Look, Size) makes room for more."
    e == Element.DRAWER && room.isNotEmpty() ->
        "At this size there is room for ${room.values.first()} tiles across. A smaller size (Look, Size) makes room for more."
    else -> null
}

/** A count: its name and what it is, the number (rolling to a new one) and iOS's stepper. */
@Composable
private fun StepperRow(title: String, detail: String?, value: Int, minus: Boolean, plus: Boolean, onChange: (Int) -> Unit) = BaseRow(title, detail, null) {
    val look = LocalLook.current
    Rolling("$value", look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_DETAIL_COLOR)), Modifier)
    Spacer(Modifier.width(look.dp(12f * look.unit)))
    Stepper(minus, plus, { onChange(value - 1) }, { onChange(value + 1) })
}

/**
 * iOS's stepper: a capsule (94 x 32 pt) with a minus and a plus either side of a hairline, each half a full touch target
 * (the target's height). A half lights while pressed; one that cannot go further fades.
 */
@Composable
private fun Stepper(minus: Boolean, plus: Boolean, onMinus: () -> Unit, onPlus: () -> Unit) {
    val look = LocalLook.current
    val fill = look.color(SettingsTokens.CHIP_FILL)
    val label = look.color(SettingsTokens.ROW_LABEL)
    val separator = look.color(SettingsTokens.ROW_SEPARATOR)
    val pressFill = Color(Appearance.pressFill)
    val minusOn by animateFloatAsState(if (minus) 1f else 0.3f, look.motion(MotionTokens.CC_TOGGLE), label = "minus")
    val plusOn by animateFloatAsState(if (plus) 1f else 0.3f, look.motion(MotionTokens.CC_TOGGLE), label = "plus")
    var pressedMinus by remember { mutableStateOf(false) }
    var pressedPlus by remember { mutableStateOf(false) }
    val pm by animateFloatAsState(if (pressedMinus) 1f else 0f, look.motion(if (pressedMinus) MotionTokens.PRESS_IN else MotionTokens.PRESS_OUT), label = "pm")
    val pp by animateFloatAsState(if (pressedPlus) 1f else 0f, look.motion(if (pressedPlus) MotionTokens.PRESS_IN else MotionTokens.PRESS_OUT), label = "pp")
    val minusNow = androidx.compose.runtime.rememberUpdatedState(if (minus) onMinus else null)
    val plusNow = androidx.compose.runtime.rememberUpdatedState(if (plus) onPlus else null)
    val w = 94f * look.unit
    val h = 32f * look.unit
    Row(Modifier.height(look.dp(SettingsTokens.TARGET)).width(look.dp(w))
        .drawBehind {
            val top = (size.height - h) / 2f
            val r = CornerRadius(h / 2f)
            drawRoundRect(fill, Offset(0f, top), Size(size.width, h), r)
            // The pressed half, inside the capsule's shape.
            fun lit(k: Float, left: Boolean) {
                if (k < 0.001f) return
                clipRect(left = if (left) 0f else size.width / 2f, right = if (left) size.width / 2f else size.width) {
                    drawRoundRect(pressFill.copy(alpha = pressFill.alpha * k), Offset(0f, top), Size(size.width, h), r)
                }
            }
            lit(pm, true); lit(pp, false)
            val lh = h * 0.56f
            drawRect(separator, Offset(size.width / 2f - 0.5f * look.unit, (size.height - lh) / 2f), Size(1f * look.unit, lh))
            val arm = 6.5f * look.unit
            val stroke = 2f * look.unit
            val cy = size.height / 2f
            val mx = size.width / 4f
            val px = size.width * 3f / 4f
            val round = androidx.compose.ui.graphics.StrokeCap.Round
            drawLine(label.copy(alpha = label.alpha * minusOn), Offset(mx - arm, cy), Offset(mx + arm, cy), stroke, round)
            drawLine(label.copy(alpha = label.alpha * plusOn), Offset(px - arm, cy), Offset(px + arm, cy), stroke, round)
            drawLine(label.copy(alpha = label.alpha * plusOn), Offset(px, cy - arm), Offset(px, cy + arm), stroke, round)
        }) {
        Box(Modifier.weight(1f).fillMaxSize().pointerInput(Unit) { rowTap({ pressedMinus = it && minusNow.value != null }) { minusNow.value?.invoke() } })
        Box(Modifier.weight(1f).fillMaxSize().pointerInput(Unit) { rowTap({ pressedPlus = it && plusNow.value != null }) { plusNow.value?.invoke() } })
    }
}

/**
 * Apply and Discard, floating at the bottom while changes wait: it rises in with the first change (the sheet makes room
 * above it), counts them, and after Back with changes waiting says what a second Back does.
 */
@Composable
private fun ApplyBar(draft: Draft, g: Geometry, bar: Animatable<Float, *>, entry: EntryState, onApply: () -> Unit) {
    val look = LocalLook.current
    val n = draft.count
    val spec = look.motion<Float>(MotionTokens.SHEET)
    LaunchedEffect(n > 0) { bar.animateTo(if (n > 0) 1f else 0f, spec) }
    val nudge = remember { Animatable(0f) }
    LaunchedEffect(draft.backAt) {
        if (draft.backAt == 0L) return@LaunchedEffect
        nudge.snapTo(1f)
        launch { nudge.animateTo(0f, look.motion(MotionTokens.APPEAR)) }
        kotlinx.coroutines.delay(Draft.BACK_AGAIN_MS)
        draft.backAt = 0L
    }
    var said by remember { mutableStateOf("") }
    if (n > 0) said = when {
        draft.backAt != 0L -> "Back again to discard"
        n == 1 -> "1 change"
        else -> "$n changes"
    }
    val m = look.pt(SettingsTokens.MARGIN)
    val fill = look.color(SettingsTokens.BUTTON_FILL)
    val rim = look.color(SettingsTokens.BUTTON_RIM)
    val shadow = look.color(SettingsTokens.SHEET_SHADOW)
    val page = look.color(SettingsTokens.BACKGROUND)
    val shadowPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    Box(
        Modifier
            .offset { IntOffset((g.inset + m).roundToInt(), g.barTop.roundToInt()) }
            .size(look.dp(g.sheetW - m * 2f), look.dp(g.barH))
            .graphicsLayer {
                val k = bar.value
                alpha = k.coerceIn(0f, 1f)
                translationY = (1f - k) * (g.barH + 40f * g.unit) + (1f - entry.enter.floatValue.coerceAtLeast(0f)) * g.enterDrop
            }
            .pressScale { 1f + 0.05f * nudge.value }
            .drawBehind {
                val r = size.height / 2f
                shadowPaint.color = page.toArgb()
                shadowPaint.setShadowLayer(20f * g.unit, 0f, 6f * g.unit, shadow.toArgb())
                drawIntoCanvas { it.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, shadowPaint) }
                drawRoundRect(fill, cornerRadius = CornerRadius(r))
                drawRoundRect(rim, cornerRadius = CornerRadius(r), style = Stroke(1f * g.unit))
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(Modifier.fillMaxSize().padding(start = look.dp(20f * look.unit), end = look.dp(7f * look.unit)), verticalAlignment = Alignment.CenterVertically) {
            Rolling(said, look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_LABEL)), Modifier.weight(1f))
            BarButton("Discard", accent = false) { draft.discard() }
            Spacer(Modifier.width(look.dp(6f * look.unit)))
            BarButton("Apply", accent = true, onClick = onApply)
        }
    }
}

@Composable
private fun BarButton(text: String, accent: Boolean, onClick: () -> Unit) {
    val look = LocalLook.current
    var pressed by remember { mutableStateOf(false) }
    val press by animateFloatAsState(if (pressed) 1f else 0f, look.motion(if (pressed) MotionTokens.PRESS_IN else MotionTokens.PRESS_OUT), label = "press")
    val a = look.color(SettingsTokens.ACCENT)
    val style = look.text(SettingsTokens.TITLE, if (accent) look.color(SettingsTokens.ACCENT_LABEL) else a)
    BasicText(text, style = style, maxLines = 1, modifier = Modifier
        .pressScale { 1f - 0.06f * press }
        .drawBehind { if (accent) drawRoundRect(a, cornerRadius = CornerRadius(size.height / 2f)) }
        .pointerInput(onClick) { rowTap({ pressed = it }, onClick) }
        .heightIn(min = look.dp(SettingsTokens.TARGET))
        .wrapContentHeight()
        .padding(horizontal = look.dp(16f * look.unit)))
}

/** A line that rolls to its new words: the old ones rise and fade, the new come up from below. */
@Composable
internal fun Rolling(text: String, style: androidx.compose.ui.text.TextStyle, modifier: Modifier, lines: Int = 1) {
    val look = LocalLook.current
    var now by remember { mutableStateOf(text) }
    var was by remember { mutableStateOf<String?>(null) }
    val k = remember { Animatable(1f) }
    LaunchedEffect(text) {
        if (text == now) return@LaunchedEffect
        was = now
        now = text
        k.snapTo(0f)
        k.animateTo(1f, look.motion(MotionTokens.APPEAR))
        was = null
    }
    val rise = 14f * look.unit
    // Several lines: the height follows the new words (from the old's).
    Box(if (lines > 1) modifier.animateContentSize(look.motion(MotionTokens.APPEAR)) else modifier) {
        was?.let { w ->
            BasicText(w, style = style, maxLines = lines, overflow = TextOverflow.Ellipsis, modifier = Modifier
                .graphicsLayer { alpha = (1f - k.value).coerceIn(0f, 1f) }
                .drawWithContent { translate(0f, -rise * k.value) { this@drawWithContent.drawContent() } })
        }
        BasicText(now, style = style, maxLines = lines, overflow = TextOverflow.Ellipsis, modifier = Modifier
            .graphicsLayer { alpha = k.value.coerceIn(0f, 1f) }
            .drawWithContent { translate(0f, rise * (1f - k.value)) { this@drawWithContent.drawContent() } })
    }
}

/**
 * Phone: what the launcher needs from the phone, its updates, and the way out of trouble, in one place (Matheesha: the
 * developer panel and the safe settings were apps of their own). All of it takes effect at once (nothing here waits for
 * Apply). The plain safe settings screen stays for the Restore notification (it opens even if the themes cannot load).
 */
@Composable
private fun PhonePage() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as? dev.launcher.app.LauncherApp
    val st = LocalStudio.current
    // What it shows is read again whenever Shizuku comes or goes, the settings come back, or an action here is done.
    var tick by remember { mutableIntStateOf(0) }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val l: () -> Unit = { main.post { tick++ } }
        dev.launcher.app.ShizukuLink.addListener(l)
        onDispose { dev.launcher.app.ShizukuLink.removeListener(l) }
    }
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(o)
        onDispose { lifecycle.removeObserver(o) }
    }
    // The log's newest lines, while this page is open and at rest (a few times a second at most: each is laid out again).
    // Watched outside the page's composition: reading which page is open here recomposed the whole page whenever any page
    // opened (14 ms in the first frame of opening Look on the S24).
    val feed = remember { LogFeed() }
    LaunchedEffect(st) {
        androidx.compose.runtime.snapshotFlow { st == null || (st.open == Part.PHONE && !st.p.isRunning) }.collectLatest { open ->
            if (!open) return@collectLatest
            feed.read()
            var pending = false
            val l: (String) -> Unit = { if (!pending) { pending = true; main.postDelayed({ pending = false; feed.read() }, 300) } }
            dev.launcher.app.AppLog.addListener(l)
            try { kotlinx.coroutines.awaitCancellation() } finally { dev.launcher.app.AppLog.removeListener(l) }
        }
    }
    val shizuku = remember(tick) { dev.launcher.app.ShizukuLink.state() }
    val connected = shizuku == dev.launcher.app.ShizukuLink.State.CONNECTED
    fun settingsPage(action: String) {
        try { ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: android.content.ActivityNotFoundException) { ctx.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    Group("Shizuku", "The advanced features (the shade, gestures, app switching, updates) work through Shizuku.") {
        InfoRow("Status", when (shizuku) {
            dev.launcher.app.ShizukuLink.State.CONNECTED -> "Connected"
            dev.launcher.app.ShizukuLink.State.CONNECTING -> "Connecting"
            dev.launcher.app.ShizukuLink.State.NO_PERMISSION -> "Not allowed yet"
            dev.launcher.app.ShizukuLink.State.NOT_RUNNING -> "Not running"
        })
        if (!connected) ActionRow("Connect", "Start Shizuku first (wireless debugging)") { dev.launcher.app.ShizukuLink.connect() }
    }
    Group("Updates") {
        InfoRow("This build", app?.buildStamp() ?: "?")
        ActionRow("Check for updates", feed.update) { app?.let { dev.launcher.app.Updater.update(it, force = false) } }
        ActionRow("Install the newest again", "Even if it is the build you have") { app?.let { dev.launcher.app.Updater.update(it, force = true) } }
    }
    var restored by remember { mutableStateOf<String?>(null) }
    Group("Safety", "If the status bar is missing, animations are off or the launcher misbehaves. Restarting the phone also brings the status bar back.") {
        val hidden = remember(tick) { dev.launcher.app.SystemRestore.statusBarHidden(ctx) }
        InfoRow("Status bar", if (hidden) "Ours (stock one hidden)" else "The phone's own")
        InfoRow("Animations", remember(tick) { dev.launcher.app.SystemRestore.currentScales(ctx) })
        InfoRow("Restore without Shizuku", if (remember(tick) { dev.launcher.app.SystemRestore.canWriteSecureSettings(ctx) }) "Ready" else "Connect Shizuku once")
        InfoRow("Safety notification", if (remember(tick) { dev.launcher.app.SafetyNotification.canPost(ctx) }) "Allowed" else "Blocked")
        ActionRow("Restore system", restored ?: "The status bar, gestures and animations as they were") {
            restored = "Restoring..."
            dev.launcher.app.DevTools.restore(ctx) { restored = it; tick++ }
        }
        NavRow("Choose a different home app", null) { settingsPage(android.provider.Settings.ACTION_HOME_SETTINGS) }
        NavRow("Animation scales", "Android's developer options") { settingsPage(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) }
    }
    Group("Developer", "For testing the launcher. Each takes effect at once; what they find goes to the log below.") {
        var gestures by remember(tick) { mutableStateOf(dev.launcher.app.SystemRestore.gesturesWanted(ctx)) }
        ToggleRow("Gesture navigation", "The launcher's own gestures and app cards", gestures) { on ->
            if (connected) { gestures = on; dev.launcher.app.DevTools.setGestures(ctx, on) { tick++ } } else dev.launcher.app.ShizukuLink.connect()
        }
        var bar by remember(tick) { mutableStateOf(dev.launcher.app.SystemRestore.statusBarWanted(ctx)) }
        ToggleRow("Our status bar", "The phone's own hidden", bar) { on ->
            if (connected) { bar = on; dev.launcher.app.DevTools.setOwnStatusBar(ctx, on) { tick++ } } else dev.launcher.app.ShizukuLink.connect()
        }
        ActionRow("Frame report", "Frames since the last report") { dev.launcher.app.DevTools.frameReport(ctx) }
        ActionRow("Status bar test", "An app that puts the bars in every state (full screen, landscape...)") {
            ctx.startActivity(Intent(ctx, dev.launcher.app.BarsTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        ActionRow("Mini player test", "An app that goes into a mini player when left, as a video does (a fixed picture)") {
            ctx.startActivity(Intent(ctx, dev.launcher.app.PipTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        ActionRow("Watchdog status", null) { dev.launcher.app.DevTools.watchdogStatus() }
        ActionRow("Shell identity", null) { dev.launcher.app.DevTools.identity() }
        ActionRow("Diagnose Shizuku restarts", "The system's log around them") { dev.launcher.app.DevTools.diagnoseRestarts() }
        ActionRow("Probe: live cards", "Opens Settings and goes home through our transition") { dev.launcher.app.DevTools.probeLiveCards(ctx) }
        ActionRow("Test: break the system", "Hides the phone's status bar and turns animations off, until Restore system") { dev.launcher.app.DevTools.breakSystem(ctx) }
        ActionRow("Test: stop the shell service", "The phone's status bar should come back at once") { dev.launcher.app.DevTools.killService() }
        ConfirmRow("Reset home layout", "Forgets your home pages; the launcher restarts", "Tap again to reset home") { dev.launcher.app.DevTools.resetHomeLayout(ctx) }
    }
    Group("Log") {
        BasicText(feed.log.ifEmpty { "Nothing yet." }, style = LocalLook.current.text(SettingsTokens.ROW_DETAIL, LocalLook.current.color(SettingsTokens.ROW_DETAIL_COLOR))
            .copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp)),
            modifier = Modifier.fillMaxWidth().padding(LocalLook.current.dp(SettingsTokens.ROW_PADDING)))
        ActionRow("Copy log", null) { dev.launcher.app.DevTools.copyLog(ctx) }
    }
    Group("Expert") {
        NavRow("Every value of the theme", "The token editor, for theme authors") {
            ctx.startActivity(Intent(ctx, dev.launcher.app.design.DesignActivity::class.java))
        }
    }
}

/** How many of the log's newest lines the Phone page shows. */
private const val LOG_LINES = 40

/** The log's newest lines and the updater's last word, for the Phone page. */
private class LogFeed {
    var log by mutableStateOf("")
    var update by mutableStateOf<String?>(null)
    fun read() {
        val all = dev.launcher.app.AppLog.text().lines()
        log = all.takeLast(LOG_LINES).joinToString("\n")
        update = all.lastOrNull { "[update]" in it }?.substringAfter("[update] ")
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
private fun BaseRow(title: String, detail: String?, onClick: (() -> Unit)?, accent: Boolean = false, changing: Boolean = false, end: @Composable () -> Unit) {
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
            BasicText(title, style = look.text(SettingsTokens.ROW_TITLE, look.color(if (accent) SettingsTokens.ACCENT else SettingsTokens.ROW_LABEL)))
            val detailStyle = look.text(SettingsTokens.ROW_DETAIL, look.color(SettingsTokens.ROW_DETAIL_COLOR))
            if (changing) { if (detail != null) Rolling(detail, detailStyle, Modifier.fillMaxWidth(), lines = 6) }
            else if (detail != null) BasicText(detail, style = detailStyle)
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
    Rolling(value, look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_DETAIL_COLOR)), Modifier)
}

/** Something to do, named in the accent (as iOS's button rows); what it reports rolls in under its name. */
@Composable
private fun ActionRow(title: String, detail: String?, onClick: () -> Unit) = BaseRow(title, detail, onClick, accent = true, changing = true) { }

/** Something that cannot be undone: the first tap asks ([confirm] under its name), a second one within 3 s does it. */
@Composable
private fun ConfirmRow(title: String, detail: String, confirm: String, onConfirm: () -> Unit) {
    var armedAt by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    LaunchedEffect(armedAt) { if (armedAt != 0L) { kotlinx.coroutines.delay(Draft.BACK_AGAIN_MS); armedAt = 0L } }
    BaseRow(title, if (armedAt != 0L) confirm else detail, {
        if (armedAt != 0L) onConfirm() else armedAt = android.os.SystemClock.uptimeMillis()
    }, accent = true, changing = true) { }
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
internal suspend fun androidx.compose.ui.input.pointer.PointerInputScope.rowTap(pressed: (Boolean) -> Unit, onTap: () -> Unit) {
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
