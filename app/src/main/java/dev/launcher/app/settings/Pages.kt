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
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.draw.clipToBounds
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
import dev.launcher.app.motion.MotionAdjust
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
import androidx.compose.animation.core.VectorConverter
import dev.launcher.app.design.MaterialKey
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.composed
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
    /** The room between the header and the sheet's top (V5: the sheet sits under the header, at rest and with a page open). */
    val SHEET_GAP = NumberKey("comp.settings.sheet.gap")
    /** A part's stage at the top of its page (V5): its height (of the screen's), and the phone's inset in it. */
    val STAGE_HEIGHT = NumberKey("comp.settings.stage.height")
    val STAGE_INSET = NumberKey("comp.settings.stage.inset")
    /** 1: the page behind the sheet is the wallpaper, blurred (iOS 27's Liquid Glass); 0: the theme's background colour. */
    val GLASS = NumberKey("comp.settings.glass")
    val SEGMENT_TRACK = ColorKey("comp.settings.segment.track")
    val SEGMENT_THUMB = ColorKey("comp.settings.segment.thumb")
    val SEGMENT_HEIGHT = NumberKey("comp.settings.segment.height")
    val SEGMENT_TEXT = TextKey("comp.settings.segment.text")
    val SEGMENT_SYMBOL = NumberKey("comp.settings.segment.symbol")
    val SEGMENT_CORNER = NumberKey("comp.settings.segment.corner")
    /** The least a control's touch area is, either way (Apple: 44 pt; Android: 48 dp). */
    val TARGET = NumberKey("comp.settings.target")
    val CHIP_HEIGHT = NumberKey("comp.settings.chip.height")
    val TILE_FILL = ColorKey("comp.settings.tile.fill")
    val TILE_TINT = NumberKey("comp.settings.tile.tint")
    val TILE_TITLE_TINT = NumberKey("comp.settings.tile.title-tint")
    val TILE_CORNER = NumberKey("comp.settings.tile.corner")
    val TILE_HEIGHT = NumberKey("comp.settings.tile.height")
    val TILE_GAP = NumberKey("comp.settings.tile.gap")
    /** A card's scene (TileArt): its share of the card's width, its frame's inset, the veil its colours start under, its paper. */
    val TILE_VISUAL = NumberKey("comp.settings.tile.visual")
    val TILE_VISUAL_INSET = NumberKey("comp.settings.tile.visual-inset")
    val TILE_VISUAL_VEIL = ColorKey("comp.settings.tile.visual-veil")
    val TILE_PAPER = ColorKey("comp.settings.tile.paper")
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

/**
 * The settings' tiles (docs/PLAN_SETTINGS_V2.md, structure B): Look and Motion for the whole phone, one per part of the phone
 * ([element]: its layout, where it lives, its options and its own theme, all on its page), and Phone. Each has its name, its
 * colour and its symbol.
 */
internal enum class Part(val title: String, val color: ColorKey, val element: Element? = null, val tile: Boolean = true) {
    LOOK("Look", ColorKey("comp.settings.color.look")),
    MOTION("Motion", ColorKey("comp.settings.color.motion")),
    HOME("Home", ColorKey("comp.settings.color.home"), Element.HOME),
    APPS("Apps", ColorKey("comp.settings.color.apps"), Element.RECENTS),
    LIBRARY("App Library", ColorKey("comp.settings.color.library"), Element.DRAWER),
    SHADE("Notifications & Control Center", ColorKey("comp.settings.color.shade"), Element.SHADE),
    STATUS_BAR("Status bar", ColorKey("comp.settings.color.statusbar"), Element.STATUS_BAR),
    PHONE("Phone", ColorKey("comp.settings.color.phone")),
    /** Everything you changed (V3), opened from the start page's "Your changes": a page, no tile. */
    CHANGES("Your changes", ColorKey("comp.settings.color.look"), tile = false),
}

/** What a part holds (with the changes waiting to be applied), in a line under its name on the tile. */
internal fun Part.detail(d: Draft, ctx: android.content.Context? = null): String {
    val e = element ?: return when (this) {
        // ("iOS 27" named the theme, the preset and the status bar's layout on three cards: each says which it is.)
        Part.LOOK -> themeName(d.themeId()) + " theme" + if (d.wallpaperColors()) " · wallpaper colours" else ""
        Part.MOTION -> presetName(d.motionId()) + " preset" + (d.motionChangeCount(d.motionId()).takeIf { it > 0 }?.let { " · $it change" + if (it == 1) "" else "s" } ?: "")
        else -> if (dev.launcher.app.ShizukuLink.service != null) "Shizuku connected" else "Shizuku not running"
    }
    val l = Layouts.find(e, d.layout(e)) ?: Setup.layout(e)
    fun count(key: String) = (l.option(key) as? Option.Count)?.let { d.int(e, key, it.default) }
    val what = when (e) {
        Element.HOME -> "${count("columns")} × ${count("rows")}, ${count("dock-icons")} in the dock"
        // (As many as Control Center shows: it shows only what fits.)
        Element.SHADE -> "${if (ctx != null) d.ccColumnsShown(ctx) else count(dev.launcher.app.shade.CcGrid.COLUMNS)} controls across"
        Element.DRAWER -> (l.placement(d.placement(e)) ?: l.defaultPlacement).title
        Element.STATUS_BAR -> l.origin + " style"
        else -> l.title
    }
    // A theme of its own, after it.
    return d.elementTheme(e)?.let { "$what · ${themeName(it)}" } ?: what
}

private fun themeName(id: String) = Design.themes().firstOrNull { it.id == id }?.name ?: id

/**
 * The themes the settings offer: themes made for testing (marked `"test"` in their file: Graphite) only with Developer's
 * switch on, or while one is in use (as test presets in Motion).
 */
internal fun offeredThemes(draft: Draft): List<Design.ThemeInfo> {
    val testsOn = SettingsPrefs.developer && SettingsPrefs.testPresets
    val inUse = buildSet { add(Design.themeId); add(draft.themeId()); addAll(Element.entries.mapNotNull { draft.elementTheme(it) }) }
    return Design.themes().filter { !it.test || testsOn || it.id in inUse }
}

/**
 * Whether part [e] offers a theme of its own: only while there is another theme to choose (with one theme, "Follows Look"
 * and that theme were two chips that made the same look), or while it has one.
 */
internal fun offersOwnTheme(draft: Draft, e: Element): Boolean = offeredThemes(draft).size > 1 || draft.elementTheme(e) != null
internal fun presetName(id: String) = Design.motionPresets().firstOrNull { it.id == id }?.name ?: id

/**
 * The settings' changes, waiting for Apply (Matheesha: every change applied at once played the look's reveal again and
 * again). The rows show the draft over what is applied; Apply applies it all as one ([Design.batch]: one reveal), Discard
 * forgets it. Kept while the settings are open (going home and coming back keeps it).
 */
@Stable
internal class Draft {
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
    /**
     * Animation presets' tokens changed here, per preset ((preset, token) to the value: the whole preset's speed and bounce,
     * and each animation with C4b), and presets whose changes of yours all go on Apply ([resetMotion]).
     */
    val motionEdits = androidx.compose.runtime.mutableStateMapOf<Pair<String, String>, dev.launcher.app.design.Value>()
    val motionCleared = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    /**
     * The same edits one state per (preset, token), for what shows one token: a change composes and works out again only
     * what reads that token. Read through the map, every animation's row and every group's count on Motion's pages was
     * worked out again at every step of a slider (~140 of them; 8 ms of the S24's main thread a step). Written with the map.
     */
    private val motionCells = HashMap<Pair<String, String>, androidx.compose.runtime.MutableState<dev.launcher.app.design.Value?>>()
    private fun cell(preset: String, key: String) = motionCells.getOrPut(preset to key) {
        androidx.compose.runtime.mutableStateOf(androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { motionEdits[preset to key] })
    }
    private fun putMotionEdit(pk: Pair<String, String>, v: dev.launcher.app.design.Value?) {
        if (v == null) motionEdits.remove(pk) else motionEdits[pk] = v
        motionCells[pk]?.value = v
    }
    /** When Back was pressed with changes waiting (a second Back soon after discards them and leaves); 0: not. */
    var backAt by androidx.compose.runtime.mutableLongStateOf(0L)

    val count: Int get() = listOfNotNull(theme, colors, motion, appearance, colorStyle, colorIntensity, colorSeed, scalePolicy, sizeFactor, textFactor).size +
        elements.size + setup.size +
        fonts.size + colorKeys.size + motionEdits.size + motionCleared.size

    fun themeId() = theme ?: Design.themeId
    fun chooseTheme(id: String) { theme = id.takeIf { it != Design.themeId } }
    fun wallpaperColors() = colors?.let { it == "wallpaper" } ?: Design.wallpaperColors
    fun chooseColors(on: Boolean) { colors = (if (on) "wallpaper" else "theme").takeIf { on != Design.wallpaperColors } }
    fun motionId() = motion ?: Design.motionPresetId
    fun chooseMotion(id: String) { motion = id.takeIf { it != Design.motionPresetId } }

    /** Number [k] of animation preset [preset] as it would be applied: this draft's, else yours, else the preset's own. */
    fun motionNumber(preset: String, k: NumberKey): Float =
        (cell(preset, k.name).value as? dev.launcher.app.design.Value.Number)?.v ?: appliedMotionNumber(preset, k)
    private fun appliedMotionNumber(preset: String, k: NumberKey): Float = Design.previewNumber(preset, k, edits = preset !in motionCleared) ?: 1f
    private fun ownMotionNumber(preset: String, k: NumberKey): Float = Design.previewNumber(preset, k, edits = false) ?: 1f
    fun chooseMotionNumber(preset: String, k: NumberKey, v: Float) {
        putMotionEdit(preset to k.name, if (kotlin.math.abs(v - appliedMotionNumber(preset, k)) < 0.001f) null
            else dev.launcher.app.design.Value.Number(v, dev.launcher.app.design.NumUnit.FACTOR))
    }
    fun motionSpeed(preset: String) = motionNumber(preset, MotionTokens.ADJUST_SPEED)
    fun motionBounce(preset: String) = motionNumber(preset, MotionTokens.ADJUST_BOUNCE)

    /** Role [k] of animation preset [preset] as it would run once applied (yours, at the draft's speed and bounce, the preset's and its moment's). */
    fun motionCurve(preset: String, k: dev.launcher.app.design.CurveKey): dev.launcher.app.design.Curve {
        val raw = (cell(preset, k.name).value as? dev.launcher.app.design.Value.CurveV)?.curve
            ?: Design.previewCurve(preset, k, edits = preset !in motionCleared, adjusted = false)
            ?: return dev.launcher.app.motion.Motion.role(k)
        val m = dev.launcher.app.motion.Moments.of(k.name)
        return MotionAdjust.apply(raw, MotionAdjust.speed(motionSpeed(preset), m?.let { motionNumber(preset, it.speed) } ?: 1f),
            MotionAdjust.bounce(motionBounce(preset), m?.let { motionNumber(preset, it.bounce) } ?: 1f))
    }

    /** Token [key] of animation preset [preset] as you have it (not at its speed and bounce): this draft's, else yours, else its own. */
    fun motionRaw(preset: String, key: String): dev.launcher.app.design.Value? =
        cell(preset, key).value ?: Design.presetValue(preset, key, edits = preset !in motionCleared)
    /** As the preset comes. */
    fun motionOwn(preset: String, key: String): dev.launcher.app.design.Value? = Design.presetValue(preset, key, edits = false)
    /** [key] of [preset] set to [v] (waits for Apply; the value as applied again forgets the change). */
    fun chooseMotionValue(preset: String, key: String, v: dev.launcher.app.design.Value) {
        putMotionEdit(preset to key, v.takeIf { it != Design.presetValue(preset, key, edits = preset !in motionCleared) })
    }
    /** How apps open or close ([k]: motion/AppStyles' OPENING or CLOSING) in [preset] as it would be applied. */
    fun motionStyle(preset: String, k: dev.launcher.app.design.ChoiceKey): dev.launcher.app.motion.AppStyle =
        dev.launcher.app.motion.AppStyle.of((motionRaw(preset, k.name) as? dev.launcher.app.design.Value.Choice)?.option)
    fun chooseMotionStyle(preset: String, k: dev.launcher.app.design.ChoiceKey, s: dev.launcher.app.motion.AppStyle) =
        chooseMotionValue(preset, k.name, dev.launcher.app.design.Value.Choice(s.id))

    /** Changed from how [preset] comes (by you, applied or not). */
    fun motionChanged(preset: String, key: String): Boolean = motionRaw(preset, key) != motionOwn(preset, key)
    /** Changed here and not applied yet. */
    fun motionPending(preset: String, key: String): Boolean = cell(preset, key).value != null || preset in motionCleared && key in Design.motionEdits(preset)

    /** How many of [preset]'s animations you changed, as it would be applied (its speed and bounce, and its moments', aside). */
    fun motionRoleChanges(preset: String): Int = changedKeys(preset).count { !isFeel(it) }

    /** How many moments of [preset] you gave a speed or a bounce of their own (each counts once per value). */
    private fun momentFeelChanges(preset: String): Int = changedKeys(preset).count { isFeel(it) && it !in OVERALL_MOTION }

    private fun changedKeys(preset: String): Set<String> {
        val keys = HashSet<String>()
        if (preset !in motionCleared) keys += Design.motionEdits(preset).keys
        for ((pk, _) in motionEdits) if (pk.first == preset) keys += pk.second
        return keys.filterTo(HashSet()) { it != MotionTokens.DEBUG_SLOW.name && motionChanged(preset, it) }
    }

    /** A preset's or a moment's own speed or bounce, or a style (not one animation's). */
    private fun isFeel(key: String) = key.startsWith("motion.adjust.") || key.startsWith("motion.style.")

    /** Moment [m]'s own values: its speed, its bounce, its style if it has one, each of its animations. */
    private fun momentKeys(m: dev.launcher.app.motion.Moment) = listOfNotNull(m.speed.name, m.bounce.name, m.style?.name) + m.all

    /** How many of moment [m]'s values you changed in [preset]. */
    fun momentChanges(preset: String, m: dev.launcher.app.motion.Moment): Int = momentKeys(m).count { motionChanged(preset, it) }

    /** Moment [m] back as [preset] has it (on Apply): its speed, its bounce, its style and every animation of it. */
    fun resetMoment(preset: String, m: dev.launcher.app.motion.Moment) {
        for (k in momentKeys(m)) {
            if (!motionChanged(preset, k)) continue
            val own = motionOwn(preset, k) ?: continue
            chooseMotionValue(preset, k, own)
        }
    }
    private fun speedChanged(preset: String) = kotlin.math.abs(motionSpeed(preset) - ownMotionNumber(preset, MotionTokens.ADJUST_SPEED)) > 0.001f
    private fun bounceChanged(preset: String) = kotlin.math.abs(motionBounce(preset) - ownMotionNumber(preset, MotionTokens.ADJUST_BOUNCE)) > 0.001f
    /** Your changes to [preset] as it would be applied: its animations, its moments' feel, and its speed and bounce as one each. */
    fun motionChangeCount(preset: String): Int = motionRoleChanges(preset) + momentFeelChanges(preset) +
        (if (speedChanged(preset)) 1 else 0) + (if (bounceChanged(preset)) 1 else 0)
    /** What you changed in [preset], in a few words for its card (null: nothing). */
    fun motionSummary(preset: String): String? {
        val parts = ArrayList<String>()
        if (speedChanged(preset)) parts += "Speed ${(motionSpeed(preset) * 100).roundToInt()} %"
        if (bounceChanged(preset)) parts += motionBounce(preset).let { if (it < 0.001f) "No bounce" else "Bounce ${(it * 100).roundToInt()} %" }
        val n = motionRoleChanges(preset) + momentFeelChanges(preset)
        if (n > 0) parts += "$n change" + if (n == 1) "" else "s"
        return when (parts.size) { 0 -> null; 1 -> parts[0]; else -> motionChangeCount(preset).let { "$it changes" } }
    }
    /** Everything of yours in [preset] goes (on Apply): it is as it comes again. */
    fun resetMotion(preset: String) {
        for (k in motionEdits.keys.filter { it.first == preset }) putMotionEdit(k, null)
        if (Design.motionEdits(preset).keys.any { it != MotionTokens.DEBUG_SLOW.name }) motionCleared[preset] = true
    }
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
    /**
     * Control Center's columns as it would show them: the draft's count, but no more than fit across at the draft's size
     * (Control Center lays out only what fits; never fewer than 4).
     */
    fun ccColumnsShown(ctx: android.content.Context): Int {
        val n = int(Element.SHADE, dev.launcher.app.shade.CcGrid.COLUMNS, 4)
        val f = dev.launcher.app.home.HomeMetrics.frame ?: return n
        val side = minOf(f.w, f.h)
        return minOf(n, dev.launcher.app.shade.CcGrid.room(side, unit(ctx, side)).coerceAtLeast(4))
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
            // A preset's changes before the choice of preset (one being chosen loads them).
            for (id in motionCleared.keys) Design.clearMotionEdits(id)
            for ((pk, v) in motionEdits) Design.setMotionEdit(pk.first, pk.second, v)
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
        motionEdits.clear(); motionCleared.clear()
        for (c in motionCells.values) c.value = null
    }

    companion object {
        const val FOLLOW = "follow"
        const val BACK_AGAIN_MS = 3000L
        /** A preset's tokens that are not one animation's: its overall speed and bounce, and slow motion (Developer's). */
        private val OVERALL_MOTION = setOf(MotionTokens.ADJUST_SPEED.name, MotionTokens.ADJUST_BOUNCE.name, MotionTokens.DEBUG_SLOW.name)
        /** A size on the sliders' steps (5 %). */
        fun snap(v: Float) = kotlin.math.round(v * 20f) / 20f
    }
}

internal val LocalDraft = staticCompositionLocalOf { Draft() }

/**
 * Which part is open and how far: [p] 0 is the tiles, 1 its page. Opening one changes no composition and no layout (the
 * S24 traced 14-19 ms of recomposition and 8-10 ms of layout in the first frame when it did): [open] and [p] are read in
 * drawing and placement only, and everything a page needs is composed, laid out and placed ahead of time. One value moves everything (the tile growing into the
 * page's stage, its scene giving way to the phone, the title, the page's groups arriving), so every frame
 * of a change only moves and fades what is already built (docs/PLAN_SETTINGS.md S1: the S24 lost 60-80 ms building a
 * page in the first frame of its push).
 */
@Stable
internal class Studio(
    /** Home sliding in (and back out): home's picture, the sheet and the title move with it. */
    val entry: EntryState,
) {
    var open by mutableStateOf<Part?>(null)
    val p = Animatable(0f)
    /** The Apply bar showing (0..1): the sheet makes room above it while the tiles show. */
    val bar = Animatable(0f)
    /** Each tile's bounds in the sheet, and its name's, as its page starts growing: where the page grows from and goes back to. */
    val tiles = HashMap<Part, Rect>()
    val tileTitles = HashMap<Part, Rect>()
    val tileDetails = HashMap<Part, Rect>()
    /** Where each tile, its name and its line under it are laid out (their bounds are read when one is opened: the tiles scroll). */
    val tileAt = HashMap<Part, LayoutCoordinates>()
    val tileTitleAt = HashMap<Part, LayoutCoordinates>()
    val tileDetailAt = HashMap<Part, LayoutCoordinates>()
    /** How far each tile is pressed in (the growing one starts from the size it sank to); read in drawing only. */
    val press = Part.entries.associateWith { Animatable(0f) }
    /** The tiles' scenes' clock (s): it runs only while they are in sight (StartContent), so a scene never jumps. */
    val artClock = androidx.compose.runtime.mutableFloatStateOf(0f)
    var sheet: LayoutCoordinates? = null
    /** How many pages are built (ahead of time, one a frame, so opening one never builds it). */
    var built by mutableIntStateOf(0)
    /** Each page's scroll (kept while the settings are open: a page comes back where it was). */
    val scrolls = Part.entries.associateWith { IosScrollState() }
    /** The tiles' scroll. */
    val start = IosScrollState()
    /**
     * The sub-page pushed over the open part's page (Look's colours, fonts and size; Phone's developer tools; null: none), how
     * far it is in, each one's scroll, and the way back from each part's (set by its pager). [subTitled]: the last one pushed
     * (its title stays in the header, faded out, after its page has gone).
     */
    private val pushedSub = mutableStateOf<Pushed?>(null)
    var sub: Pushed?
        get() = pushedSub.value
        set(v) { pushedSub.value = v; if (v != null && v != subTitled) subTitled = v }
    var subTitled by mutableStateOf<Pushed?>(null)
        private set
    val subPush = Animatable(0f)
    /** Each pushed page's scroll (a moment's page made when first pushed: SubPager). */
    val subScrolls = androidx.compose.runtime.mutableStateMapOf<Pushed, IosScrollState>().apply { for (s in Sub.entries) put(s, IosScrollState()) }
    val popSubs = HashMap<Part, () -> Unit>()
    /** Each part's way of pushing a page over its own (set by its pager): the search goes to pages one level down. */
    val pushers = HashMap<Part, (Pushed) -> Unit>()
    /** The search (V3): whether it is up, its words, its results' scroll, and how far the start page has turned to it (0..1). */
    var searching by mutableStateOf(false)
    val query = androidx.compose.foundation.text.input.TextFieldState()
    val searchScroll = IosScrollState()
    val searchK = Animatable(0f)
    /** Rows the search can find, by page and name, each page's viewport, and the row lit now (its strength). */
    val anchors = HashMap<String, LayoutCoordinates>()
    val viewports = HashMap<String, LayoutCoordinates>()
    var lit by mutableStateOf<String?>(null)
    val litK = Animatable(0f)
    /** The open page grew from a row (a result, Your changes), not from its tile: it grows without the tile's contents. */
    var fromRow by mutableStateOf<Part?>(null)
    /** The moment whose Every detail is open (one at a time), and the one whose Every detail is open on screen now. */
    var detailFor by mutableStateOf<String?>(null)
    var detailShown by mutableStateOf<String?>(null)
    /**
     * A moment's page (MomentPage, pushed over Motion or a part's page): the animation whose editor is open on it (Every
     * detail), whether a slider or a handle is being dragged (what follows it changes at once, no rolling words), and what
     * the stage plays for it, at a quarter speed when asked.
     */
    var motionRole by mutableStateOf<String?>(null)
    var motionLive by mutableStateOf(false)
    val sample = SampleState()
    var sampleSlow by mutableStateOf(false)
}

internal val LocalStudio = staticCompositionLocalOf<Studio?> { null }

/** A screen reader is on (the settings keep an accessibility tree only then). */
private val LocalScreenReader = androidx.compose.runtime.compositionLocalOf { false }

/**
 * Where things are, in pixels: the header, the sheet under it (at rest and with a page open: V5, no picture of home above
 * it any more), a part's stage at the top of its page.
 */
internal class Geometry(look: Look, val statusPx: Float, val navPx: Float) {
    val unit = look.unit
    val w = look.screenW.toFloat()
    val h = look.screenH.toFloat()
    val inset = look.pt(SettingsTokens.SHEET_INSET)
    val sheetW = w - inset * 2f
    val buttonSize = look.pt(SettingsTokens.BUTTON_SIZE)
    val headerTop = statusPx + 6f * unit
    /** The sheet's top, under the header. */
    val sheetTop = headerTop + buttonSize + look.pt(SettingsTokens.SHEET_GAP)
    /** The sheet is laid out once and moved (home sliding in): nothing is laid out again while it moves. */
    val sheetH = h - sheetTop - inset

    /** The Apply bar, and the room the pages leave under their last rows for it. */
    val barH = 56f * unit
    val barRoom = barH + 20f * unit
    val barTop = h - navPx - 12f * unit - barH

    /** Where the sheet's top waits while home slides in: off the screen, its shadow too. */
    val belowTop = h + 48f * unit

    private val margin = look.pt(SettingsTokens.MARGIN)

    /** A part's stage at the top of its page (the sheet's coordinates), as wide as the page's groups. */
    val stage = Rect(margin, STAGE_TOP * unit, sheetW - margin, STAGE_TOP * unit + h * Design.num(SettingsTokens.STAGE_HEIGHT))

    /** Where a part's page begins, under its stage (the sheet's coordinates). */
    val pageTop = stage.bottom

    private companion object {
        /** The stage's top in the sheet (pt): clear of the grabber. */
        const val STAGE_TOP = 18f
    }
}

/** How far the page being composed is shown (0..1, read in drawing: the groups rise in with it). */
internal val LocalShown = staticCompositionLocalOf<() -> Float> { { 1f } }

/** Counts a page's groups as they compose (each rises in a beat after the one above). */
internal class GroupCounter { var n = 0 }

internal val LocalGroups = staticCompositionLocalOf { GroupCounter() }

/**
 * The settings app, "studio" layout (docs/PLAN_SETTINGS.md, draft D; V5: docs/PLAN_SETTINGS_V2.md): a sheet of cards
 * under the title; a card grows into the stage at the top of its page (the phone drawn as a motion graphic, at the part),
 * the page's groups rising under it; back (the button, the system's Back) shrinks it into its card. Changes wait for Apply ([Draft]). Every colour, surface, corner, font and motion is
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
                    // Where it grows from: the tile where it is now (the tiles scroll), its name's place in it.
                    if (st.open != part && st.fromRow != part) {
                        val s = st.sheet?.takeIf { it.isAttached }
                        val t = st.tileAt[part]?.takeIf { it.isAttached }
                        if (s != null && t != null) {
                            st.tiles[part] = s.localBoundingBoxOf(t, clipBounds = false)
                            st.tileTitleAt[part]?.takeIf { it.isAttached }?.let { st.tileTitles[part] = s.localBoundingBoxOf(it, clipBounds = false) }
                            st.tileDetailAt[part]?.takeIf { it.isAttached }?.let { st.tileDetails[part] = s.localBoundingBoxOf(it, clipBounds = false) }
                        }
                    }
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
        val bar = st.bar
        val ctx = LocalContext.current
        BackToTiles(st, close, draft, onClose, toHome)
        val fontSearch = remember { FontSearch() }
        val focus = androidx.compose.ui.platform.LocalFocusManager.current
        androidx.compose.runtime.SideEffect {
            // Coming from home again with a page (or the font search) left open: back at the tiles at once, at their top
            // (unseen: home's picture covers all).
            entry.reset = {
                st.open = null
                focus.clearFocus()
                st.start.scroller.jumpTo(0f)
                // A search left up ends too.
                st.searching = false
                st.query.edit { replace(0, length, "") }
                st.searchScroll.scroller.jumpTo(0f)
                scope.launch { st.p.snapTo(0f); fontSearch.closeNow(); st.searchK.snapTo(0f) }
            }
            // Where home's picture, stepping back into the page, leaves the status bar to the page's tone.
            entry.toneAt = ENTRY_TONE_AT
        }
        // Home sliding in (and back out, Back on the tiles): one spring steps home's picture back into the page (EntryHome),
        // the sheet up from below the screen and the title in (EntryState). The status bar keeps home's tone while the
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
                if (to >= 1f) entry.picture = null   // gone into the page by now
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
                st.p.isRunning || bar.isRunning || entry.moving || st.scrolls.values.any { it.moving } || st.start.moving ||
                    st.subPush.isRunning || st.subScrolls.values.any { it.moving } ||
                    st.searchK.isRunning || st.searchScroll.moving
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
                    EntryHome(st, g)
                    KeepHomePictures(st)
                    Header(st, g) {
                        // A pushed page goes back to its part's page first (once it is on its way back, the part's page closes).
                        val sub = st.sub
                        if (sub != null && sub.part == st.open && st.subPush.targetValue >= 0.5f) st.popSubs[sub.part]?.invoke() ?: close(0f)
                        else close(0f)
                    }
                    Sheet(st, g, open, close)
                    ApplyBar(draft, g, st) { draft.apply(ctx) }
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
    val waiting by remember(draft) { androidx.compose.runtime.derivedStateOf { draft.count > 0 } }
    BackHandler(enabled = st.open == null && waiting) {
        val now = android.os.SystemClock.uptimeMillis()
        if (draft.backAt != 0L && now - draft.backAt < Draft.BACK_AGAIN_MS) { draft.discard(); if (st.entry.fromHome) toHome() else leave() }
        else draft.backAt = now
    }
    // Opened from home's edit menu: back into home the way it came (home's picture growing back over the whole screen).
    BackHandler(enabled = st.open == null && !waiting && st.entry.fromHome) { toHome() }
}

/** How far (pt) a page's title slides as a page pushed over it takes the header. */
private const val TITLE_SLIDE = 60f

/** How far into a push the leaving title is gone, and from where the coming one shows (they barely overlap). */
private const val TITLE_OUT = 0.45f
private const val TITLE_IN = 0.35f

/** The title (the app's, then the open page's) and the round back button, both moving with the page. */
@Composable
private fun Header(st: Studio, g: Geometry, onBack: () -> Unit) {
    val look = LocalLook.current
    val style = look.text(SettingsTokens.TITLE, look.color(SettingsTokens.TITLE_COLOR))
    val rise = 6f * g.unit
    // A title keeps clear of the back button on either side (a long one ends in an ellipsis on a narrow phone).
    val clear = Modifier.padding(horizontal = look.dp(look.pt(SettingsTokens.MARGIN) + g.buttonSize + 8f * g.unit))
    // (Up and away with the sheet as the home gesture closes them; the slide from home moves only the sheet.)
    val lift = Modifier.graphicsLayer { translationY = if (st.entry.upward) st.entry.shift(0f) else 0f }
    // How far the sheet goes up to be gone: its bottom past the top of the screen, its shadow too.
    androidx.compose.runtime.SideEffect { st.entry.liftPx = g.sheetTop + g.sheetH + 40f * g.unit }
    Box(Modifier.fillMaxWidth().offset { IntOffset(0, g.headerTop.roundToInt()) }.then(lift).height(look.dp(g.buttonSize)), contentAlignment = Alignment.Center) {
        BasicText("Settings", style = style, modifier = Modifier.graphicsLayer {
            val p = st.p.value
            // In as home's picture has made room for it (the slide's second half).
            val e = ((st.entry.enter.floatValue - 0.45f) / 0.55f).coerceIn(0f, 1f)
            alpha = (1f - p * 2f).coerceIn(0f, 1f) * e
            translationY = -p * rise + (1f - e) * rise
        })
        for (part in Part.entries) BasicText(part.title, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = clear.graphicsLayer {
            val p = if (st.open == part) st.p.value else 0f
            // A part's own name gives way to a pushed page's (a sub-page's, a moment's) as that page comes over it: it
            // slides left and is gone before the other has come in from the right (crossing at the same strength, the two
            // read as one jumble halfway).
            val k = if (st.sub?.part == part) st.subPush.value.coerceIn(0f, 1f) else 0f
            alpha = ((p - 0.4f) / 0.6f).coerceIn(0f, 1f) * (1f - k / TITLE_OUT).coerceIn(0f, 1f)
            translationY = (1f - p) * rise
            translationX = -k * TITLE_SLIDE * g.unit
        })
        // The last page pushed keeps its title here (faded with the push): a page coming or going composes nothing, unless it
        // is another page (the header composed again at each push's start and pop's end: 2 missed refreshes, S24).
        val titledSub by remember { androidx.compose.runtime.derivedStateOf { st.subTitled } }
        titledSub?.let { s ->
            BasicText(s.title, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = clear.graphicsLayer {
                val p = if (st.open == s.part) st.p.value else 0f
                val k = if (st.sub == s) st.subPush.value.coerceIn(0f, 1f) else 0f
                alpha = ((p - 0.4f) / 0.6f).coerceIn(0f, 1f) * ((k - TITLE_IN) / (1f - TITLE_IN)).coerceIn(0f, 1f)
                translationX = (1f - k) * TITLE_SLIDE * g.unit
            })
        }
    }
    RoundButton(R.drawable.sym_back, "Back", { st.open != null }, onBack, Modifier
        .offset { IntOffset(look.pt(SettingsTokens.MARGIN).roundToInt(), g.headerTop.roundToInt()) }
        .then(lift)
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
    // The panel is there at rest too (glass is for the buttons on it: Matheesha).
    val solid: () -> Float = { 1f }
    Box(
        Modifier
            .offset { IntOffset(g.inset.roundToInt(), g.sheetTop.roundToInt()) }
            .size(look.dp(g.sheetW), look.dp(g.sheetH))
            .graphicsLayer {
                // While home slides in (or out): from below the screen (its shadow too) to its place under the title; up and
                // away with the finger as the home gesture closes them (EntryState.upward).
                translationY = st.entry.shift(g.belowTop - g.sheetTop)
            }
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
        MotionSampleLoop(st)
        val art = rememberArtKit()
        StartContent(st, g, open, 18f * g.unit, art)
        RowGrowth(st)
        for ((i, part) in Part.entries.withIndex()) {
            if (st.built > i) PageLayer(st, g, part, close)
        }
        // (Over the pages: a card crossing the page as it grows is over the groups rising in under it.)
        Stage(st, g, art)
        val grabber = look.color(SettingsTokens.TITLE_COLOR).copy(alpha = 0.18f)
        Box(
            Modifier.align(Alignment.TopCenter).padding(top = look.dp(7f * g.unit))
                .size(look.dp(36f * g.unit), look.dp(5f * g.unit))
                .drawBehind { drawRoundRect(grabber.copy(alpha = grabber.alpha * solid()), cornerRadius = CornerRadius(size.height / 2f)) },
        )
    }
}

/**
 * At rest: a search field (V3), then the tiles, two across (docs/PLAN_SETTINGS_V2.md: Look and Motion, the parts of the
 * phone, Phone), "Your changes" over them while anything differs from the theme or preset. More than the sheet shows at
 * rest: a scroll up lifts the sheet first, as a page would, then scrolls the tiles in it ([liftScroll]); the last ones clear
 * the Apply bar. The search lifts the sheet as a page would and turns the tiles into its results.
 */
@Composable
private fun StartContent(st: Studio, g: Geometry, open: (Part) -> Unit, fadePx: Float, art: androidx.compose.runtime.State<ArtKit>) {
    val look = LocalLook.current
    val m = look.dp(SettingsTokens.MARGIN)
    val bar = st.bar
    val bandPaint = remember { androidx.compose.ui.graphics.Paint() }
    // The scenes' clock runs while the tiles are in sight: not under a page at rest or the search's results, not with the
    // settings away or covered by home's picture. A long frame counts as a short one (a stall pauses the scenes).
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    val resumed = remember { mutableStateOf(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) }
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumed.value = true
            else if (e == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) resumed.value = false
        }
        lifecycle.addObserver(o)
        onDispose { lifecycle.removeObserver(o) }
    }
    val inSight = remember(st) {
        androidx.compose.runtime.derivedStateOf {
            resumed.value && st.entry.enter.floatValue > 0f && !(st.open != null && !st.p.isRunning && st.p.value >= 1f) &&
                !(st.searching && !st.searchK.isRunning)
        }
    }
    LaunchedEffect(st) {
        androidx.compose.runtime.snapshotFlow { inSight.value }.collectLatest { on ->
            if (!on) return@collectLatest
            var last = androidx.compose.runtime.withFrameNanos { it }
            while (true) {
                val now = androidx.compose.runtime.withFrameNanos { it }
                st.artClock.floatValue += ((now - last) / 1e9f).coerceAtMost(LOOP_STEP_MAX)
                last = now
            }
        }
    }
    val scope = rememberCoroutineScope()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val draft = LocalDraft.current
    val turn = androidx.compose.runtime.rememberUpdatedState(look.motion<Float>(MotionTokens.MODE_CROSSFADE))
    // The index is made as the search begins (it follows the layouts chosen and Developer's switch); searched off the main
    // thread as the words change, the list following a frame later.
    val index = remember { mutableStateOf<List<SearchEntry>>(emptyList()) }
    val results = remember { mutableStateOf<List<SearchEntry>>(emptyList()) }
    LaunchedEffect(st) {
        androidx.compose.runtime.snapshotFlow { st.searching }.collect { on -> if (on) index.value = settingsIndex(draft, SettingsPrefs.developer) }
    }
    LaunchedEffect(st) {
        androidx.compose.runtime.snapshotFlow { st.query.text.toString() to index.value }.collectLatest { (q, idx) ->
            results.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { searchSettings(idx, q) }
        }
    }
    val begin: () -> Unit = remember(st, scope, g) {
        {
            if (!st.searching) {
                st.searching = true
                scope.launch { st.searchK.animateTo(1f, turn.value) }
            }
        }
    }
    val cancel: () -> Unit = remember(st, scope, g, focus, keyboard) {
        {
            focus.clearFocus()
            keyboard?.hide()
            st.searching = false
            scope.launch {
                st.searchK.animateTo(0f, turn.value)
                // Gone: the next search starts afresh (no words, its top).
                if (!st.searching) { st.query.edit { replace(0, length, "") }; st.searchScroll.scroller.jumpTo(0f) }
            }
        }
    }
    SearchBack(st, cancel)
    val pick: (SearchEntry, Rect?) -> Unit = remember(st, scope, focus, keyboard, open) {
        { e, from ->
            focus.clearFocus()
            keyboard?.hide()
            scope.launch { jumpTo(st, e, from, look.unit, open) }
        }
    }
    val fade = Modifier.graphicsLayer { alpha = (1f - st.p.value * 1.8f).coerceIn(0f, 1f) }
    Column(Modifier.fillMaxSize()) {
        Row(fade.fillMaxWidth().padding(start = m, end = m, top = look.dp(22f * g.unit)), verticalAlignment = Alignment.CenterVertically) {
            SearchField(st.query, "Search", Modifier.weight(1f)) { focused -> if (focused) begin() }
            // Cancel: its room opens as the search begins (and the field gives way to it).
            Box(Modifier
                .layout { mm, c ->
                    val pl = mm.measure(c.copy(minWidth = 0))
                    val k = st.searchK.value.coerceIn(0f, 1f)
                    layout((pl.width * k).roundToInt(), pl.height) { pl.place(0, 0) }
                }
                .graphicsLayer { alpha = st.searchK.value.coerceIn(0f, 1f) }) {
                TextButton("Cancel", null) { cancel() }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            // (The tiles dissolve under the field once they scroll in the sheet, not while the sheet only rises.)
            Box(Modifier.fillMaxSize()
                .graphicsLayer { alpha = 1f - st.searchK.value.coerceIn(0f, 1f) }
                .topFade(st.start, g.unit, fadePx, bandPaint)) {
                Column(Modifier.fillMaxSize()
                    .liftScroll(st.start, { 0f }) { bar.value * g.barRoom }
                    .padding(start = m, end = m)) {
                    Spacer(Modifier.height(look.dp(16f * g.unit)))
                    ChangesEntry(st, open)
                    TileGrid(st, open, art)
                    Spacer(Modifier.height(look.dp(24f * g.unit + g.navPx + g.inset)))
                }
            }
            // The results, over the tiles while the search is up (taking every touch there), above the keyboard.
            val resultsShown by remember(st) { androidx.compose.runtime.derivedStateOf { st.searching || st.searchK.value > 0.001f } }
            if (resultsShown) {
                Box(Modifier.fillMaxSize()
                    .graphicsLayer { alpha = st.searchK.value.coerceIn(0f, 1f) * (1f - st.p.value * 1.8f).coerceIn(0f, 1f) }
                    .padding(start = m, end = m)
                    .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.ime.only(androidx.compose.foundation.layout.WindowInsetsSides.Bottom))
                    .topFade(st.searchScroll, g.unit, fadePx, bandPaint)) {
                    SearchResults(st, { results.value }, pick)
                }
            }
        }
    }
}

/** Back puts the search away while it is up at the tiles (its own scope: only it composes again as the search comes and goes). */
@Composable
private fun SearchBack(st: Studio, cancel: () -> Unit) {
    val on by remember(st) { androidx.compose.runtime.derivedStateOf { st.searching && st.open == null } }
    BackHandler(enabled = on) { cancel() }
}

/**
 * "Your changes" over the tiles (V3): there while anything differs from the theme or the preset, how many, leading to them
 * all (the page grows out of the row). Counted while the tiles show (a slider dragged on a page does not count it again
 * at every step).
 */
@Composable
private fun ChangesEntry(st: Studio, open: (Part) -> Unit) {
    val draft = LocalDraft.current
    val look = LocalLook.current
    val ctx = LocalContext.current
    val held = remember { intArrayOf(-1) }
    val n by remember(st, draft, look.version) {
        androidx.compose.runtime.derivedStateOf {
            if (held[0] >= 0 && st.open != null && !st.p.isRunning) held[0] else changesOf(draft, ctx).size.also { held[0] = it }
        }
    }
    // (It keeps its last count while it folds away.)
    val last = remember { intArrayOf(1) }
    if (n > 0) last[0] = n
    val at = remember { arrayOfNulls<LayoutCoordinates>(1) }
    Reveal(n > 0) {
        // (It steps back and fades as a page grows over it, as the tiles do.)
        Column(Modifier.onPlaced { at[0] = it }.graphicsLayer {
            val p = st.p.value
            alpha = (1f - p * 1.8f).coerceIn(0f, 1f)
            val sc = 1f - 0.05f * p.coerceIn(0f, 1f)
            scaleX = sc; scaleY = sc
        }) {
            Group(null) {
                BaseRow("Your changes", "${last[0]} from the theme and the preset", {
                    val s = st.sheet?.takeIf { it.isAttached }
                    val me = at[0]?.takeIf { it.isAttached }
                    if (s != null && me != null) { st.tiles[Part.CHANGES] = s.localBoundingBoxOf(me, clipBounds = false); st.fromRow = Part.CHANGES }
                    open(Part.CHANGES)
                }) {
                    BasicText("›", style = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_DETAIL_COLOR)))
                }
            }
        }
    }
}

/**
 * The cards one under another, each as wide as the sheet (Matheesha, 2026-10-15: one column, "more polished and well
 * thought out"): every card the same, its name and what it holds on the left, its part's scene on the right; every card
 * as tall as the tallest needs and at least the theme's card height, so the column keeps one rhythm.
 */
@Composable
private fun TileGrid(st: Studio, open: (Part) -> Unit, art: androidx.compose.runtime.State<ArtKit>) {
    val look = LocalLook.current
    val gap = look.pt(SettingsTokens.TILE_GAP)
    val least = look.pt(SettingsTokens.TILE_HEIGHT)
    androidx.compose.ui.layout.Layout(content = { for (part in Part.entries) if (part.tile) Tile(st, part, open, art) }) { ms, c ->
        val w = c.maxWidth
        val h = maxOf(least, ms.maxOf { it.maxIntrinsicHeight(w).toFloat() }).roundToInt()
        val placeables = ms.map { it.measure(androidx.compose.ui.unit.Constraints.fixed(w, h)) }
        layout(w, (placeables.size * h + (placeables.size - 1).coerceAtLeast(0) * gap).roundToInt()) {
            placeables.forEachIndexed { i, pl -> pl.place(0, (i * (h + gap)).roundToInt()) }
        }
    }
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
 * Part [part]'s colour: the theme's; with the wallpaper's colours (Material You), the theme's own hue turned toward the
 * palette's accent by half the way, at most 15° (Material's harmonising), its lightness and chroma kept. Under the palette
 * every part's accent had become its one primary: every card and every stage the same hue, the page's colours no help.
 */
internal fun partColor(look: Look, part: Part): Color {
    if (!Design.wallpaperColors) return look.color(part.color)
    val key = look.version to part
    return partColors.getOrPut(key) {
        if (partColors.keys.any { it.first != look.version }) partColors.keys.removeAll { it.first != look.version }
        Color(dev.launcher.app.design.MaterialYou.harmonize(Design.ownColor(part.color), look.color(SettingsTokens.ACCENT).toArgb()))
    }
}

private val partColors = HashMap<Pair<Int, Part>, Color>()

/** A tile's fill: its colour by the theme's tint over the tile's own fill (the groups' fill in the themes: one look). */
internal fun tileFill(look: Look, part: Part): Color {
    val base = look.color(SettingsTokens.TILE_FILL)
    val c = partColor(look, part)
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

internal fun mix(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t, green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t, alpha = a.alpha + (b.alpha - a.alpha) * t,
)

/**
 * One part, on a solid card as the app's groups are (Matheesha, 2026-10-15: the cards as the rest of the app, not glass):
 * its name and what it holds on the left, read down the page; on the right a scene of the part in its colour ([TileArt]),
 * drawn on the card itself (Matheesha, 2026-10-16: no box, "blend with the card itself"). Pressed, it lights as a row
 * does and sinks; it grows into its page.
 */
@Composable
private fun Tile(st: Studio, part: Part, open: (Part) -> Unit, art: androidx.compose.runtime.State<ArtKit>) {
    val look = LocalLook.current
    val c = partColor(look, part)
    val titleTint = Design.num(SettingsTokens.TILE_TITLE_TINT).coerceIn(0f, 1f)
    val label = look.color(SettingsTokens.ROW_LABEL)
    val corner = look.pt(SettingsTokens.TILE_CORNER)
    val share = Design.num(SettingsTokens.TILE_VISUAL).coerceIn(0.2f, 0.7f)
    val fill = tileFill(look, part)
    val pressFill = Color(Appearance.pressFill)
    var pressed by remember { mutableStateOf(false) }
    val press = st.press.getValue(part)
    val pressIn = look.motion<Float>(MotionTokens.PRESS_IN)
    val pressOut = look.motion<Float>(MotionTokens.PRESS_OUT)
    LaunchedEffect(pressed) { press.animateTo(if (pressed) 1f else 0f, if (pressed) pressIn else pressOut) }
    Box(
        Modifier
            .fillMaxSize()
            .onPlaced { st.tileAt[part] = it }
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
            .drawBehind {
                val r = CornerRadius(corner)
                drawRoundRect(fill, cornerRadius = r)
                val k = press.value
                if (k > 0.001f) drawRoundRect(pressFill.copy(alpha = pressFill.alpha * k), cornerRadius = r)
            }
            .pointerInput(part) { rowTap({ pressed = it }) { st.fromRow = null; open(part) } },
    ) {
        // Its scene, on the art's clock, in a layer of its own (only the scene is drawn again in its frames).
        Canvas(Modifier.matchParentSize().graphicsLayer()) {
            TileArt.draw(this, part, size.width, size.height, st.artClock.floatValue, art.value, c)
        }
        // Its name and what it holds, beside the window, in the middle of the card's height.
        Column(Modifier
            .align(Alignment.CenterStart)
            .fillMaxWidth(1f - share)
            .padding(start = look.dp(TILE_TEXT_START_PT * look.unit), end = look.dp(TILE_TEXT_END_PT * look.unit),
                top = look.dp(TILE_TEXT_V * look.unit), bottom = look.dp(TILE_TEXT_V * look.unit))) {
            BasicText(part.title, style = look.text(SettingsTokens.TILE_TITLE, mix(label, c, titleTint)), maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.onPlaced { st.tileTitleAt[part] = it })
            Spacer(Modifier.height(look.dp(3f * look.unit)))
            // Read through a derived state where it follows the draft: every step of a slider in Motion changes the draft,
            // and the card (out of sight behind the page) composed again with each.
            val draft = LocalDraft.current
            val ctx = LocalContext.current
            val drafted = remember(part, draft, look.version) { androidx.compose.runtime.derivedStateOf { part.detail(draft, ctx) } }
            BasicText(if (part == Part.PHONE) part.detail(draft, ctx) else drafted.value, style = tileDetailStyle(look, part),
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.onPlaced { st.tileDetailAt[part] = it })
        }
    }
}

// A card's text: from its left edge, short of its scene, and above and below it (pt).
internal const val TILE_TEXT_START_PT = 18f
internal const val TILE_TEXT_END_PT = 12f
private const val TILE_TEXT_V = 14f

/** The line under a tile's name: the detail colour, or with names in their part's colour ([SettingsTokens.TILE_TITLE_TINT]) its own. */
internal fun tileDetailStyle(look: Look, part: Part): androidx.compose.ui.text.TextStyle {
    val titleTint = Design.num(SettingsTokens.TILE_TITLE_TINT).coerceIn(0f, 1f)
    val detail = look.color(SettingsTokens.ROW_DETAIL_COLOR)
    return look.text(SettingsTokens.TILE_DETAIL, mix(detail, partColor(look, part), titleTint).copy(alpha = if (titleTint > 0f) 0.8f else detail.alpha))
}

/** A page in the sheet: built ahead, placed (so drawn and touchable) only while it is the open one. */
@Composable
private fun PageLayer(st: Studio, g: Geometry, part: Part, close: (Float) -> Unit) {
    // Made once (a new one each time this composed changed what every group of the page reads: the whole page composed again
    // in the first frame of any opening).
    val shown: () -> Float = remember(st, part) { { if (st.open == part) st.p.value.coerceIn(0f, 1f) else 0f } }
    val counter = remember { GroupCounter() }
    counter.n = 0
    val fadePx = 18f * g.unit
    val bandPaint = remember { androidx.compose.ui.graphics.Paint() }
    val look = LocalLook.current
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
                // Under its stage (Your changes, no part of the phone, has none).
                .padding(top = if (part == Part.CHANGES) look.dp(0f) else look.dp(g.pageTop))
        ) {
            // Every page pushes pages of its own (SubPager, which also fades the tops of its pages): Look's and Phone's, and
            // the moments' (Motion's all, each part its own).
            when (part) {
                Part.LOOK -> SubPager(st, g, part, fadePx, bandPaint, { push, lit -> LookPage(push, lit) }) { s, _ -> LookSubPage(s) }
                Part.MOTION -> SubPager(st, g, part, fadePx, bandPaint, { push, lit -> MotionPage(push, lit) }) { s, _ -> MomentPageOf(st, s) }
                Part.PHONE -> PhonePager(st, g, fadePx, bandPaint)
                Part.CHANGES -> SubPager(st, g, part, fadePx, bandPaint, { _, _ -> ChangesList(null, byPart = true) }) { _, _ -> }
                else -> SubPager(st, g, part, fadePx, bandPaint, { push, lit -> PartPage(part, push, lit) }) { s, _ -> MomentPageOf(st, s) }
            }
        }
    }
}

// ------------------------------------------------------------------ the pages

/**
 * Look, for the whole phone (docs/PLAN_SETTINGS_V2.md, "Look, calmer"): the theme as cards (home as it looked in each, once
 * the settings have seen it), the colours as one choice with the palette under it, light and dark; fonts, sizes and the
 * palette's finer points a row away ([LookSubPage]); the settings' own theme last. A part's own theme is on its page. All of
 * it waits for Apply.
 */
@Composable
private fun LookPage(push: (Pushed) -> Unit, lit: () -> Pushed?) {
    val draft = LocalDraft.current
    val look = LocalLook.current
    val themes = remember(look.version, SettingsPrefs.developer, SettingsPrefs.testPresets) { offeredThemes(draft) }
    Group("Theme", "Every part follows it, unless one has a theme of its own (on that part's page).") {
        ThemeCards(themes, draft)
    }
    // Whether the colour of your own is being changed (its editor open under it).
    val editing = remember { mutableStateOf<Char?>(null) }
    Group("Colours", "Material You: any theme's surfaces, glass and accents take your wallpaper's colours, or a colour of your own.") {
        ColourChoiceRow(draft, editing)
        Reveal(draft.wallpaperColors() && draft.seedColor() != null) { SeedRow(draft, editing) }
        ColourEditor(draft, editing)
        Reveal(draft.wallpaperColors()) {
            Column {
                PaletteRow(draft, null)
                PushRow("Adjust colours", "Their style, how colourful, each palette's own colour", Sub.COLOURS, push, lit)
            }
        }
    }
    Group("Light & dark") {
        Box(Modifier.padding(look.dp(SettingsTokens.ROW_PADDING))) {
            ChoiceChips(Appearance.Mode.entries.map { it.title }, Appearance.Mode.entries.indexOf(draft.appearanceMode())) {
                draft.chooseAppearance(Appearance.Mode.entries[it])
            }
        }
    }
    Group(null) {
        val text = fontName(draft.font("sys.font.text"))
        val titles = fontName(draft.font("sys.font.display"))
        // ("Inter · Serif" did not say which was which.)
        PushRow("Fonts", if (text == titles) text else "$text, titles in $titles", Sub.FONTS, push, lit)
        val ctx = LocalContext.current
        PushRow("Size", if (draft.followsPhoneSize()) "Follows the phone"
            else "Your own · ${(draft.size(ctx) * 100).roundToInt()} %, text ${(draft.text(ctx) * 100).roundToInt()} %", Sub.SIZE, push, lit)
    }
    if (themes.size > 1 || draft.elementTheme(Element.SETTINGS) != null) {
        Group("Settings app", "The settings app's own theme, or the one Look has.") {
            OwnThemeRow(Element.SETTINGS)
        }
    }
    ChangesRow(Part.LOOK, push, lit)
}

/** A moment's page, for a page pushed as one. */
@Composable
private fun MomentPageOf(st: Studio, s: Pushed) {
    if (s is MomentPage) MomentPageContent(st, s.moment)
}

/** Look's pages one level down: the palette's finer points, the fonts, the sizes. */
@Composable
private fun LookSubPage(s: Pushed) {
    val draft = LocalDraft.current
    when (s) {
        Sub.COLOURS -> {
            // Which palette's colour the editor under the palette is changing (null: none).
            val editing = remember { mutableStateOf<Char?>(null) }
            Group(null, "Material You makes the palette from the wallpaper's main colour, or yours, in a style. Tap a colour to set it yourself.") {
                ColourStyleRow(draft)
                IntensityRow(draft)
                PaletteRow(draft, editing)
                ColourEditor(draft, editing)
            }
        }
        Sub.FONTS -> Group(null, "Google fonts are downloaded the first time they are used, then work offline.") {
            FontRow("Text", "sys.font.text", draft)
            FontRow("Titles", "sys.font.display", draft)
        }
        Sub.SIZE -> Group(null, "Following the phone: everything takes its Display size, text its Font size, as Android's own apps do. Off, or a slider moved: your own sizes, whatever the phone's (100 % is an iPhone's).") {
            val ctx = LocalContext.current
            ToggleRow("Follow the phone's display and font size", null, draft.followsPhoneSize()) { draft.chooseFollowsPhoneSize(it) }
            val phone = draft.followsPhoneSize()
            SizeRow("Everything", draft.size(ctx), phone, dev.launcher.app.design.Scale.SIZE_MIN, dev.launcher.app.design.Scale.SIZE_MAX) { draft.chooseSize(ctx, it) }
            SizeRow("Text", draft.text(ctx), phone, dev.launcher.app.design.Scale.TEXT_MIN, dev.launcher.app.design.Scale.TEXT_MAX) { draft.chooseText(ctx, it) }
        }
        else -> Unit
    }
}

/**
 * The palette's palettes (Material You's roles in this appearance), as the draft would make them. With [editing], a tap on
 * one opens the editor on its own colour (and rings it); without, they only show.
 */
@Composable
private fun PaletteRow(draft: Draft, editing: androidx.compose.runtime.MutableState<Char?>?) {
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
    val counter = LocalRowCounter.current
    val first = remember { counter == null || counter.n++ == 0 }
    val pad = look.dp(SettingsTokens.ROW_PADDING)
    val swatch = 34f * look.unit
    Row(Modifier.fillMaxWidth().drawBehind { if (!first) drawRect(separator, Offset(pad.toPx(), 0f), Size(size.width - pad.toPx(), 1f)) }
        .padding(horizontal = pad, vertical = look.dp(12f * look.unit)), horizontalArrangement = Arrangement.SpaceBetween) {
        for ((target, name, p) in roles) Column(horizontalAlignment = Alignment.CenterHorizontally,
            modifier = if (editing == null) Modifier else Modifier.pointerInput(p) { rowTap({}) { editing.value = if (editing.value == p) null else p } }) {
            val c by androidx.compose.animation.animateColorAsState(target, look.motion(MotionTokens.APPEAR_FADE), label = "swatch")
            // Ringed while it is the one being changed; a dot under one the user set themselves.
            val ring by animateFloatAsState(if (editing?.value == p) 1f else 0f, look.motion(MotionTokens.CC_TOGGLE), label = "ring")
            val own by animateFloatAsState(if (draft.keyColor(p) != null) 1f else 0f, look.motion(MotionTokens.CC_TOGGLE), label = "own")
            Canvas(Modifier.size(look.dp(swatch))) {
                // Off: quieter and a little smaller (the colours are not in use).
                val s = 0.82f + 0.18f * k - 0.1f * ring
                drawCircle(c.copy(alpha = 0.3f + 0.7f * k), radius = this.size.minDimension / 2f * s)
                // (Its rim in the separators' colour: a black one vanished in dark, and the surface's swatch with it.)
                drawCircle(separator, radius = this.size.minDimension / 2f * s, style = Stroke(1f * look.unit))
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

/**
 * Where the colours come from, one choice: the theme's own, the wallpaper's (Material You), or a colour of the user's own
 * (made into a palette the same way; its editor opens under it as it is chosen).
 */
@Composable
private fun ColourChoiceRow(draft: Draft, editing: androidx.compose.runtime.MutableState<Char?>) {
    val look = LocalLook.current
    val counter = LocalRowCounter.current
    val first = remember { counter == null || counter.n++ == 0 }
    val pad = look.dp(SettingsTokens.ROW_PADDING)
    val separator = look.color(SettingsTokens.ROW_SEPARATOR)
    val chosen = if (!draft.wallpaperColors()) 0 else if (draft.seedColor() == null) 1 else 2
    Box(Modifier.fillMaxWidth()
        .drawBehind { if (!first) drawRect(separator, Offset(pad.toPx(), 0f), Size(size.width - pad.toPx(), 1f)) }
        .padding(pad)) {
        ChoiceChips(listOf("The theme's", "The wallpaper's", "Your own"), chosen) { i ->
            when (i) {
                0 -> { draft.chooseColors(false); editing.value = null }
                1 -> { draft.chooseColors(true); draft.chooseSeed(null); editing.value = null }
                else -> {
                    draft.chooseColors(true)
                    if (draft.seedColor() == null) draft.chooseSeed(Design.wallpaperSeed() ?: 0xFF3478F6.toInt())
                    editing.value = SEED
                }
            }
        }
    }
}

/** The user's own colour the palette is made from: its swatch; a tap opens its editor (or closes it). */
@Composable
private fun SeedRow(draft: Draft, editing: androidx.compose.runtime.MutableState<Char?>) {
    val look = LocalLook.current
    val seed = draft.seedColor()
    // (It keeps its last colour while its row closes.)
    val last = remember { intArrayOf(0xFF3478F6.toInt()) }
    if (seed != null) last[0] = seed
    val shown by androidx.compose.animation.animateColorAsState(Color(last[0]), look.motion(MotionTokens.APPEAR_FADE), label = "seed")
    BaseRow("Your colour", null, { editing.value = if (editing.value == SEED) null else SEED }) {
        Canvas(Modifier.size(look.dp(24f * look.unit))) {
            drawCircle(shown, radius = size.minDimension / 2f)
            drawCircle(Color.Black.copy(alpha = 0.1f), radius = size.minDimension / 2f, style = Stroke(1f * look.unit))
        }
    }
}

/** A font family's name as the font rows show it. */
internal fun fontName(id: String) = FONT_FAMILIES.firstOrNull { it.first == id }?.second ?: id.removePrefix("google:")

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
    val counter = LocalRowCounter.current
    val first = remember { counter == null || counter.n++ == 0 }
    val dim by animateFloatAsState(if (draft.wallpaperColors()) 1f else 0.45f, look.motion(MotionTokens.CC_TOGGLE), label = "dim")
    val styles = dev.launcher.app.design.MaterialYou.Style.entries
    Column(Modifier.fillMaxWidth()
        .drawBehind { if (!first) drawRect(separator, Offset(pad.toPx(), 0f), Size(size.width - pad.toPx(), 1f)) }
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
    val counter = LocalRowCounter.current
    val first = remember { counter == null || counter.n++ == 0 }
    Column(Modifier.fillMaxWidth()
        .drawBehind { if (!first) drawRect(separator, Offset(pad.toPx(), 0f), Size(size.width - pad.toPx(), 1f)) }
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
    val counter = LocalRowCounter.current
    val first = remember { counter == null || counter.n++ == 0 }
    val dim by animateFloatAsState(if (draft.wallpaperColors()) 1f else 0.45f, look.motion(MotionTokens.CC_TOGGLE), label = "dim")
    val k = draft.colorIntensity()
    Column(Modifier.fillMaxWidth()
        .drawBehind { if (!first) drawRect(separator, Offset(pad.toPx(), 0f), Size(size.width - pad.toPx(), 1f)) }
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
internal fun SettingsSlider(value: Float, onDrag: ((Boolean) -> Unit)? = null, onChange: (Float) -> Unit) {
    val now = androidx.compose.runtime.rememberUpdatedState(value)
    SettingsSlider({ now.value }, onDrag, onChange)
}

/**
 * The same, its place read from [position] in drawing: a value that changes at every step of a drag (an editor's) draws the
 * slider again and composes nothing.
 */
@Composable
internal fun SettingsSlider(position: () -> Float, onDrag: ((Boolean) -> Unit)? = null, onChange: (Float) -> Unit) {
    val look = LocalLook.current
    val track = look.color(SettingsTokens.CHIP_FILL)
    val accent = look.color(SettingsTokens.ACCENT)
    val knob = look.color(SettingsTokens.TOGGLE_KNOB)
    val change = androidx.compose.runtime.rememberUpdatedState(onChange)
    val drag = androidx.compose.runtime.rememberUpdatedState(onDrag)
    // Under the finger while dragged (it trailed behind on a spring); a value set any other way glides there.
    var held by remember { mutableStateOf(false) }
    val read = androidx.compose.runtime.rememberUpdatedState(position)
    val now = remember { androidx.compose.runtime.derivedStateOf { read.value() } }
    val glide = remember { Animatable(now.value) }
    val spec = androidx.compose.runtime.rememberUpdatedState(look.motion<Float>(MotionTokens.CC_LEVEL))
    // One follower for every change (keyed on the value, a drag launched a coroutine at every step).
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { now.value to held }.collectLatest { (v, h) -> if (h) glide.snapTo(v) else glide.animateTo(v, spec.value) }
    }
    val knobR = 13f * look.unit
    // In a layer of its own: redrawn at every step of a drag, it re-recorded everything around it in its page's list.
    Canvas(Modifier.fillMaxWidth().height(look.dp(SettingsTokens.TARGET)).graphicsLayer().pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val w = size.width - knobR * 2f
            fun at(x: Float) = ((x - knobR) / w).coerceIn(0f, 1f)
            val slop = viewConfiguration.touchSlop
            var dragging = false
            try {
                while (true) {
                    val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    val d = c.position - down.position
                    if (!dragging) {
                        if (kotlin.math.abs(d.y) > slop && kotlin.math.abs(d.y) > kotlin.math.abs(d.x)) break   // the page's scroll
                        if (kotlin.math.abs(d.x) > slop) { dragging = true; held = true; drag.value?.invoke(true) }
                    }
                    if (dragging) { c.consume(); change.value(at(c.position.x)) }
                    if (!c.pressed) { if (!dragging) change.value(at(c.position.x)); break }
                }
            } finally {
                if (dragging) { held = false; drag.value?.invoke(false) }
            }
        }
    }) {
        val cy = size.height / 2f
        val th = 4f * look.unit
        val w = size.width - knobR * 2f
        val shown = if (held) now.value else glide.value
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
        ChoiceChips(shown.map { fontName(ids[it]) }, shown.indexOfFirst { ids[it] == chosen }, fonts = shown.map { faces[it] }) { i ->
            draft.chooseFont(key, ids[shown[i]])
        }
    }
}

/** A button in words (and a symbol) in the accent, as iOS's plain buttons: pressed, it dims. */
@Composable
internal fun TextButton(text: String, symbol: Int?, onClick: () -> Unit) {
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
    // One theme on offer: its card with its name and what it is beside it (a lone card read as a choice with nothing to
    // choose from, the rest of the group empty).
    val alone = themes.size == 1
    Row(Modifier.fillMaxWidth().padding(pad), horizontalArrangement = Arrangement.spacedBy(look.dp((if (alone) 18f else 14f) * look.unit)),
        verticalAlignment = if (alone) Alignment.CenterVertically else Alignment.Top) {
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
                if (!alone) {
                    Spacer(Modifier.height(look.dp(8f * look.unit)))
                    BasicText(t.name, style = look.text(SettingsTokens.ROW_DETAIL, mix(look.color(SettingsTokens.ROW_LABEL), accent, k)), maxLines = 1)
                }
            }
        }
        if (alone) {
            val t = themes[0]
            Column(Modifier.weight(1f)) {
                BasicText(t.name, style = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_LABEL)))
                if (t.description.isNotEmpty()) {
                    Spacer(Modifier.height(look.dp(2f * look.unit)))
                    BasicText(t.description, style = look.text(SettingsTokens.ROW_DETAIL, look.color(SettingsTokens.ROW_DETAIL_COLOR)))
                }
            }
        }
    }
}

/**
 * Home as it looked in theme [id] with the wallpaper there is now; one not seen yet (or seen with another wallpaper): the
 * wallpaper under that theme's sheet colour and accent. A new picture (taken a moment after the settings come up or the
 * wallpaper changes) fades in over what showed.
 */
@Composable
private fun ThemeThumb(id: String) {
    val look = LocalLook.current
    val pic = HomePictures.of(id, look.wallpaper?.id)
    val sheet = Design.previewColor(id, SettingsTokens.SHEET_FILL)?.let { Color(it) } ?: look.color(SettingsTokens.SHEET_FILL)
    val accent = Design.previewColor(id, SettingsTokens.ACCENT)?.let { Color(it) } ?: look.color(SettingsTokens.ACCENT)
    val shown = remember { arrayOf(pic) }
    val before = remember { arrayOfNulls<android.graphics.Bitmap>(1) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(pic) {
        if (pic === shown[0]) return@LaunchedEffect
        before[0] = shown[0]
        shown[0] = pic
        fade.snapTo(0f)
        fade.animateTo(1f, look.motion(MotionTokens.APPEAR_FADE))
        before[0] = null
    }
    val paint = remember { android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG) }
    val src = remember { android.graphics.Rect() }
    val dst = remember { android.graphics.RectF() }
    Canvas(Modifier.fillMaxSize()) {
        val f = fade.value.coerceIn(0f, 1f)
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            fun picture(b: android.graphics.Bitmap, a: Float) {
                if (a <= 0.003f) return
                paint.alpha = (255 * a).roundToInt().coerceIn(0, 255)
                src.set(0, 0, b.width, b.height)
                dst.set(0f, 0f, size.width, size.height)
                nc.drawBitmap(b, src, dst, paint)
            }
            val now = shown[0]
            val old = before[0]
            // The drawn stand-in under whatever picture there is (hidden by an opaque picture of home).
            if (now == null || (old == null && f < 0.999f)) {
                val wp = look.wallpaper
                if (wp != null) {
                    val m = wp.matrix(look.screenW, look.screenH)
                    m.postScale(size.width / look.screenW, size.height / look.screenH)
                    paint.alpha = 255
                    nc.drawBitmap(wp.bitmap, m, paint)
                } else nc.drawColor(look.color(SettingsTokens.BACKGROUND).toArgb())
            }
            if (now == null || (old == null && f < 0.999f)) {
                drawRoundRect(sheet, Offset(size.width * 0.08f, size.height * 0.58f), Size(size.width * 0.84f, size.height * 0.36f), CornerRadius(size.width * 0.1f))
                drawCircle(accent, size.width * 0.08f, Offset(size.width * 0.5f, size.height * 0.76f))
            }
            if (now != null) { if (old != null) picture(old, 1f); picture(now, f) }
            else if (old != null) picture(old, 1f - f)
        }
    }
}

/** A part's own theme (docs/PLAN_LAYOUTS_THEMES.md, a theme per element): it follows Look's, or has any theme of its own. */
@Composable
private fun OwnThemeRow(e: Element) {
    val look = LocalLook.current
    val draft = LocalDraft.current
    val themes = remember(look.version, SettingsPrefs.developer, SettingsPrefs.testPresets) { offeredThemes(draft) }
    val counter = LocalRowCounter.current
    val first = remember { counter == null || counter.n++ == 0 }
    val pad = look.dp(SettingsTokens.ROW_PADDING)
    val separator = look.color(SettingsTokens.ROW_SEPARATOR)
    val own = draft.elementTheme(e)
    Box(Modifier.fillMaxWidth()
        .drawBehind { if (!first) drawRect(separator, Offset(pad.toPx(), 0f), Size(size.width - pad.toPx(), 1f)) }
        .padding(pad)) {
        val names = listOf("Follows Look") + themes.map { it.name }
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
                         fonts: List<androidx.compose.ui.text.font.FontFamily?>? = null,
                         edge: androidx.compose.ui.unit.Dp? = null, onChoose: (Int) -> Unit) {
    val look = LocalLook.current
    val scroll = rememberScrollState()
    // The row scrolls out to its card's edges ([edge]: the room between them and the row, a row's padding unless said),
    // its chips inset as before: cut at the padding, the last chip ended in a hard line inside the card.
    val edgeDp = edge ?: look.dp(SettingsTokens.ROW_PADDING)
    val edgePx = with(androidx.compose.ui.platform.LocalDensity.current) { edgeDp.roundToPx() }
    // The chosen chip kept in sight (glides there when it is chosen near an edge, or arrives there). Where each chip is comes
    // from what they measure (the gap before each and its width, once in): reading their places from an onPlaced made every
    // chip of every row place itself again in every frame something above it moved (all 53 in a page's slide: 4-13 ms of
    // the S24's main thread a frame).
    val sizes = remember { HashMap<String, IntArray>() }
    fun rangeOf(name: String?): IntRange? {
        var x = 0
        for (nm in names) {
            val sz = sizes[nm] ?: return null
            if (nm == name) return (x + sz[0])..(x + sz[0] + sz[1])
            x += sz[0] + sz[1]
        }
        return null
    }
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
                val r = rangeOf(chosenName) ?: continue
                // (In sight: inside the insets, where the row comes to rest at either end.)
                val view = scroll.viewportSize - 2 * edgePx
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
    Row(Modifier.onPlaced { if (!placed) placed = true }.bleed(edgePx).horizontalScroll(scroll).padding(horizontal = edgeDp).then(modifier),
        verticalAlignment = Alignment.CenterVertically) {
        names.forEachIndexed { i, n ->
            androidx.compose.runtime.key(n) {
                // The chosen one's fill and label read in drawing (mixed here, every frame of a chip's fade composed the
                // whole row again).
                val k = animateFloatAsState(if (i == chosen) 1f else 0f, look.motion(MotionTokens.CC_TOGGLE), label = "chip")
                var pressed by remember { mutableStateOf(false) }
                val press by animateFloatAsState(if (pressed) 1f else 0f, look.motion(if (pressed) MotionTokens.PRESS_IN else MotionTokens.PRESS_OUT), label = "press")
                val fillOff = look.color(SettingsTokens.CHIP_FILL)
                val fillOn = look.color(SettingsTokens.ACCENT)
                val textOff = look.color(SettingsTokens.CHIP_TEXT_COLOR)
                val textOn = look.color(SettingsTokens.ACCENT_LABEL)
                val label = remember(textOff, textOn) { androidx.compose.ui.graphics.ColorProducer { mix(textOff, textOn, k.value) } }
                // One height for every chip, the name centred in it (a face of its own may be taller: a script).
                Box(Modifier
                    // Its room once in, measured (a chip growing in is read where it ends: its gap still opening, it was
                    // scrolled to short of its end).
                    .chipArrival(born[0], if (i > 0) gap else 0f) { lead, w -> sizes[n] = intArrayOf(lead, w) }
                    .pressScale { 1f - 0.05f * press }
                    .drawBehind {
                        val r = minOf(corner, chipH / 2f)
                        drawRoundRect(mix(fillOff, fillOn, k.value), Offset(0f, (size.height - chipH) / 2f), Size(size.width, chipH), CornerRadius(r))
                    }
                    .pointerInput(i, chosen) { rowTap({ pressed = it }) { if (i != chosen) onChoose(i) } }
                    .height(look.dp(targetH))
                    .padding(horizontal = look.dp(12f * look.unit)),
                    contentAlignment = Alignment.Center) {
                    BasicText(n, style = look.text(SettingsTokens.CHIP_TEXT, textOff).let { s -> fonts?.getOrNull(i)?.let { s.copy(fontFamily = it) } ?: s },
                        maxLines = 1, color = label)
                }
            }
        }
    }
}

/** Laid out [px] wider on each side than its room (a row that scrolls out to its card's edges). */
private fun Modifier.bleed(px: Int): Modifier = if (px == 0) this else layout { m, c ->
    if (!c.hasBoundedWidth) {
        val p = m.measure(c)
        layout(p.width, p.height) { p.place(0, 0) }
    } else {
        val p = m.measure(c.copy(minWidth = c.minWidth + 2 * px, maxWidth = c.maxWidth + 2 * px))
        layout((p.width - 2 * px).coerceAtLeast(0), p.height) { p.place(-px, 0) }
    }
}

/** How long a chip row keeps its chosen chip in sight after a change (chips growing in move it meanwhile). */
private const val FOLLOW_MS = 900L

/**
 * A chip's room in its row ([lead]: the gap before it): there from the start in a row composed with it; one added later
 * grows in (its room opening as it scales and fades up), on the appear role.
 */
private fun Modifier.chipArrival(rowShown: Boolean, lead: Float, measured: (lead: Int, width: Int) -> Unit): Modifier = composed {
    val look = LocalLook.current
    val k = remember { Animatable(if (rowShown) 0f else 1f) }
    LaunchedEffect(Unit) { if (k.value < 1f) k.animateTo(1f, look.motion(MotionTokens.APPEAR)) }
    layout { m, c ->
        val p = m.measure(c)
        measured(lead.roundToInt(), p.width)
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

/** A value set with a slider: its name and the value now (rolling as it changes) above, the slider ([position] 0..1) below. */
@Composable
internal fun SliderRow(title: String, value: String, position: Float, onChange: (Float) -> Unit) {
    val look = LocalLook.current
    val counter = LocalRowCounter.current
    val first = remember { counter == null || counter.n++ == 0 }
    val pad = look.dp(SettingsTokens.ROW_PADDING)
    val separator = look.color(SettingsTokens.ROW_SEPARATOR)
    Column(Modifier.fillMaxWidth()
        .drawBehind { if (!first) drawRect(separator, Offset(pad.toPx(), 0f), Size(size.width - pad.toPx(), 1f)) }
        .padding(horizontal = pad, vertical = look.dp(10f * look.unit))) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText(title, style = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_LABEL)), modifier = Modifier.weight(1f))
            Rolling(value, look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_DETAIL_COLOR)), Modifier)
        }
        Spacer(Modifier.height(look.dp(6f * look.unit)))
        SettingsSlider(position, onChange = onChange)
    }
}

/**
 * [content] showing or not: its height and its alpha grow from nothing and back on the appear role (it stays composed, so
 * it never pops in or out, and a group's rows keep their order for the separators).
 */
@Composable
internal fun Reveal(visible: Boolean, content: @Composable () -> Unit) {
    val look = LocalLook.current
    val k = animateFloatAsState(if (visible) 1f else 0f, look.motion(MotionTokens.APPEAR), label = "reveal")
    Box(Modifier.fillMaxWidth().clipToBounds()
        .layout { m, c ->
            val placeable = m.measure(c)
            layout(placeable.width, (placeable.height * k.value.coerceIn(0f, 1f)).roundToInt()) { placeable.place(0, 0) }
        }
        .graphicsLayer { alpha = k.value.coerceIn(0f, 1f) }) { content() }
}

/**
 * Content scrolled up dissolves under the top edge: drawn clipped below an [fadePx] band, and only that band through a layer
 * of its own (the content is a layer: drawing it twice draws one list twice); the whole page in one cost a sheet-sized
 * buffer every frame of a scroll.
 */
internal fun Modifier.topFade(scroll: IosScrollState, unit: Float, fadePx: Float, paint: androidx.compose.ui.graphics.Paint,
                              offset: () -> Float = { scroll.offset }): Modifier = composed {
    // Its gradient made once (one made every frame of a scroll was native memory every frame).
    val top = 6f * unit
    val end = top + fadePx
    val brush = remember(top, end) { Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = top, endY = end) }
    drawWithContent {
        clipRect(top = end) { this@drawWithContent.drawContent() }
        if (offset() > 0f) drawIntoCanvas { cv ->
            cv.saveLayer(Rect(0f, top, size.width, end), paint)
            clipRect(top = top, bottom = end) { this@drawWithContent.drawContent() }
            drawRect(brush, topLeft = Offset(0f, top), size = Size(size.width, fadePx), blendMode = BlendMode.DstIn)
            cv.restore()
        } else clipRect(top = top, bottom = end) { this@drawWithContent.drawContent() }
    }
}

/**
 * A part of the phone's page (docs/PLAN_SETTINGS_V2.md, "A part's page"): the same sections every time, each short. Its
 * layout (a single line while it has only one), where it lives (the finger on the stage shows how it is reached), its
 * counts and switches, and its own theme. Waits for Apply like the rest.
 */
@Composable
private fun PartPage(part: Part, push: (Pushed) -> Unit, lit: () -> Pushed?) {
    val e = part.element ?: return
    val draft = LocalDraft.current
    val current = Layouts.find(e, draft.layout(e)) ?: Setup.layout(e)
    val layouts = Layouts.of(e)
    val at = draft.placement(e)
    // What it is and where it lives, in words under the stage; rows that choose only where there is a choice (a lone
    // layout and a lone place were rows that looked tappable and did nothing: "Where it lives: Home").
    val single = current.placements.size == 1
    PageLead(buildString {
        append(current.description)
        // (Where it lives says how it is reached: "Swipe up from the bottom edge and rest your finger"; a line about the
        // finger on the stage after it said it twice.)
        if (single && current.defaultPlacement.id != "home") append(" ").append(current.defaultPlacement.description)
        else if (single && gestureOf(e, at) != null) append(" The finger above shows how it is reached.")
    }, listOfNotNull("Layout".takeIf { layouts.size == 1 }, "Where it lives".takeIf { single }))
    if (layouts.size > 1) Group("Layout", current.description) {
        for (l in layouts) CheckRow(l.title, l.origin, l.id == current.id) { draft.chooseLayout(e, l.id) }
    }
    if (!single) Group("Where it lives", if (gestureOf(e, at) != null) "The finger above shows how it is reached." else null) {
        for (p in current.placements) {
            CheckRow(p.title, p.description, p.id == at, changed = p.id == at && p.id != current.defaultPlacement.id) { draft.choosePlacement(e, p.id) }
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
                // Past what fits (the size grew since): it says what that does (Control Center shows only what fits; home and
                // the App Library make their icons smaller), as the row's line said nothing and the note under it disagreed.
                val past = v > top
                // (What fits, and why, is the group's note under it: the row says only what it does.)
                val detail = if (!past) o.description else if (e == Element.SHADE) "Control Center shows only $top." else "Made smaller to fit."
                StepperRow(o.title, detail, v, minus = v > o.min, plus = v < top, changed = v != o.default) { draft.chooseInt(e, o.key, it, o.default) }
            }
        }
    }
    val toggles = current.options.filterIsInstance<Option.Toggle>()
    if (toggles.isNotEmpty()) Group("Options") {
        for (o in toggles) {
            val v = draft.bool(e, o.key, o.default)
            ToggleRow(o.title, o.description, v, changed = v != o.default) { draft.chooseBool(e, o.key, it, o.default) }
        }
    }
    val ownTheme = remember(LocalLook.current.version, SettingsPrefs.developer, SettingsPrefs.testPresets, draft.elementTheme(e)) { offersOwnTheme(draft, e) }
    if (ownTheme) Group("Look", "It follows the theme chosen in Look, or has one of its own.") {
        OwnThemeRow(e)
    }
    // Its moments (docs/PLAN_SETTINGS_V2.md, V2): each a row with its feel, pushing its page.
    val moments = remember(part) { momentsOf(part) }
    if (moments.isNotEmpty()) Group("Motion", "Each moment's speed and bounce on top of the preset's (Motion), and every animation in it.") {
        for (m in moments) androidx.compose.runtime.key(m.id) { MomentRow(m, part, push, lit) }
    }
    ChangesRow(part, push, lit)
}

/** A page's first words, under its stage: what the part is (rising in with the page as a group's note does). */
@Composable
private fun PageLead(text: String, anchors: List<String> = emptyList()) {
    val look = LocalLook.current
    val pad = look.dp(SettingsTokens.ROW_PADDING)
    val shown = LocalShown.current
    // ([anchors]: the rows it stands for, which a search result lands on.)
    val corner = look.pt(SettingsTokens.GROUP_CORNER)
    BasicText(text, style = look.text(SettingsTokens.ROW_DETAIL, look.color(SettingsTokens.ROW_DETAIL_COLOR)),
        modifier = Modifier.graphicsLayer {
            val k = ((shown() - 0.3f) / 0.45f).coerceIn(0f, 1f)
            alpha = k
            translationY = (1f - k) * 22f * look.unit
        }.padding(bottom = look.dp(20f * look.unit))
            .then(anchors.fold(Modifier as Modifier) { m, a -> m.anchor(a, corner, 10f * look.unit) })
            .padding(horizontal = pad))
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
private fun StepperRow(title: String, detail: String?, value: Int, minus: Boolean, plus: Boolean, changed: Boolean = false, onChange: (Int) -> Unit) =
    BaseRow(title, detail, null, changed = changed) {
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
private fun ApplyBar(draft: Draft, g: Geometry, st: Studio, onApply: () -> Unit) {
    val bar = st.bar
    val entry = st.entry
    val look = LocalLook.current
    // (A derived state: every step of a slider changes the draft, most of them not its count.)
    val n by remember(draft) { androidx.compose.runtime.derivedStateOf { draft.count } }
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
        n == 1 -> "1 change to apply"
        else -> "$n changes to apply"
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
                // (With the sheet while home slides in or out: as far as the sheet goes.)
                translationY = (1f - k) * (g.barH + 40f * g.unit) + entry.shift(g.belowTop - g.sheetTop)
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

/**
 * A line that rolls to its new words: the old ones rise and fade, the new come up from below. [color], when given, is read in
 * drawing (a colour that moves draws again and composes nothing). [instant]: the words change at once (a value under a
 * dragged finger: rolled at every step, it composed and laid out its old words again each frame and never settled).
 */
@Composable
internal fun Rolling(text: String, style: androidx.compose.ui.text.TextStyle, modifier: Modifier, lines: Int = 1,
                     color: androidx.compose.ui.graphics.ColorProducer? = null, instant: Boolean = false) {
    val look = LocalLook.current
    var now by remember { mutableStateOf(text) }
    var was by remember { mutableStateOf<String?>(null) }
    val k = remember { Animatable(1f) }
    val quick = androidx.compose.runtime.rememberUpdatedState(instant)
    LaunchedEffect(text) {
        if (text == now) return@LaunchedEffect
        if (quick.value) { was = null; now = text; k.snapTo(1f); return@LaunchedEffect }
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
            BasicText(w, style = style, maxLines = lines, overflow = TextOverflow.Ellipsis, color = color, modifier = Modifier
                .graphicsLayer { alpha = (1f - k.value).coerceIn(0f, 1f) }
                .drawWithContent { translate(0f, -rise * k.value) { this@drawWithContent.drawContent() } })
        }
        BasicText(if (instant) text else now, style = style, maxLines = lines, overflow = TextOverflow.Ellipsis, color = color, modifier = Modifier
            .graphicsLayer { alpha = k.value.coerceIn(0f, 1f) }
            .drawWithContent { translate(0f, rise * (1f - k.value)) { this@drawWithContent.drawContent() } })
    }
}

/** A text style as Android's own paint draws it, and its line's height and baseline as Compose lays it out. */
internal class LinePaint(val paint: android.text.TextPaint, val line: Pair<Int, Float>)

/** [LinePaint]s by style, made once each (bounded: a design change brings new styles). */
internal object LinePaints {
    private val made = HashMap<Any, LinePaint>()

    fun of(style: androidx.compose.ui.text.TextStyle, density: androidx.compose.ui.unit.Density,
           resolver: androidx.compose.ui.text.font.FontFamily.Resolver): LinePaint {
        val key = listOf(style, density.density, density.fontScale, resolver)
        made[key]?.let { return it }
        if (made.size > 24) made.clear()
        val measurer = androidx.compose.ui.text.TextMeasurer(resolver, density, androidx.compose.ui.unit.LayoutDirection.Ltr, 0)
        val line = measurer.measure("0", style, maxLines = 1, softWrap = false).let { it.size.height to it.firstBaseline }
        val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            (resolver.resolve(style.fontFamily, style.fontWeight ?: androidx.compose.ui.text.font.FontWeight.Normal,
                style.fontStyle ?: androidx.compose.ui.text.font.FontStyle.Normal,
                style.fontSynthesis ?: androidx.compose.ui.text.font.FontSynthesis.All).value as? android.graphics.Typeface)?.let { typeface = it }
            textSize = with(density) { if (style.fontSize != androidx.compose.ui.unit.TextUnit.Unspecified) style.fontSize.toPx() else 14f * density.density * density.fontScale }
            val ls = style.letterSpacing
            if (ls.isSp) letterSpacing = with(density) { ls.toPx() } / textSize else if (ls.isEm) letterSpacing = ls.value
            style.fontFeatureSettings?.let { fontFeatureSettings = it }
        }
        return LinePaint(paint, line).also { made[key] = it }
    }
}

/**
 * One line of words drawn from [text] (read in drawing, and laid out by itself): a value under a dragged finger changes at
 * every step, and as laid-out text it composed and measured its row again with each (an editor's sliders: 13-16 ms a step
 * on the S24's debug build). It rolls to new words as [Rolling] does, at once while [instant]. A line high; as wide as
 * its words unless the modifier sets the width, and while [instant] it only grows (what is beside it lays out again
 * seldom). [end]: drawn against its right edge. [color], when given, is read in drawing.
 */
@Composable
internal fun DrawnLine(text: () -> String, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier, end: Boolean = false,
                       instant: () -> Boolean = { false }, color: androidx.compose.ui.graphics.ColorProducer? = null) {
    val look = LocalLook.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val resolver = androidx.compose.ui.platform.LocalFontFamilyResolver.current
    // Drawn with Android's own text paint (shaped natively): Compose's text, laid out at every step of a drag, was most of a
    // step's layout where code runs interpreted (the debug build). One paint and line per style for every line (made per
    // line, a group's page of rows took 45 ms more to build); Compose measures the line's height and baseline once, so it
    // sits as a laid-out line does.
    val pl = remember(style, density, resolver) { LinePaints.of(style, density, resolver) }
    val paint = pl.paint
    val line = pl.line
    val read = androidx.compose.runtime.rememberUpdatedState(text)
    val quick = androidx.compose.runtime.rememberUpdatedState(instant)
    var now by remember { mutableStateOf(text()) }
    var was by remember { mutableStateOf<String?>(null) }
    val k = remember { Animatable(1f) }
    val spec = androidx.compose.runtime.rememberUpdatedState(look.motion<Float>(MotionTokens.APPEAR))
    LaunchedEffect(Unit) {
        // While [instant] the words are read where they are drawn, not here (worked out here as well, every step of a drag
        // made them twice more); they are taken back at once when it ends.
        var live = false
        androidx.compose.runtime.snapshotFlow { if (quick.value()) null else read.value() }.collectLatest { t ->
            if (t == null) { live = true; return@collectLatest }
            if (live) { live = false; was = null; now = t; k.snapTo(1f); return@collectLatest }
            if (t == now) return@collectLatest
            was = now
            now = t
            k.snapTo(0f)
            k.animateTo(1f, spec.value)
            was = null
        }
    }
    val rise = 14f * look.unit
    val held = remember { intArrayOf(0) }
    Box(modifier
        .layout { m, c ->
            // Its width only matters where the modifier leaves it free (a fixed width never reads the words).
            val width = if (c.hasFixedWidth) c.maxWidth else {
                val live = quick.value()
                val w = kotlin.math.ceil(paint.measureText(if (live) read.value() else now)).toInt()
                (if (live) maxOf(held[0], w) else w).also { held[0] = it }.coerceIn(c.minWidth, c.maxWidth)
            }
            val p = m.measure(androidx.compose.ui.unit.Constraints.fixed(width, line.first))
            layout(width, line.first) { p.place(0, 0) }
        }
        // (A layer of its own: its words change at every step of a drag, and drawn in the page's list they re-recorded it.)
        .graphicsLayer()
        .drawBehind {
            val live = quick.value()
            val kk = if (live) 1f else k.value.coerceIn(0f, 1f)
            val ink = color?.invoke() ?: style.color
            drawIntoCanvas { cv ->
                fun words(t: String, a: Float, dy: Float) {
                    if (a <= 0.003f) return
                    paint.color = ink.copy(alpha = ink.alpha * a).toArgb()
                    val x = if (end) size.width - paint.measureText(t) else 0f
                    cv.nativeCanvas.drawText(t, x, line.second + dy, paint)
                }
                val old = was
                if (!live && old != null) words(old, 1f - kk, -rise * kk)
                words(if (live) read.value() else now, kk, rise * (1f - kk))
            }
        })
}

/**
 * Phone: what the launcher needs from the phone, its updates, and the way out of trouble, in one place (Matheesha: the
 * developer panel and the safe settings were apps of their own). Calmer (docs/PLAN_SETTINGS_V2.md): Shizuku, Updates,
 * Safety, About; the testing tools and the token editor show once the build has been tapped seven times (Android's way),
 * until turned off on their page. All of it takes effect at once (nothing here waits for Apply). The plain safe settings
 * screen stays for the Restore notification (it opens even if the themes cannot load).
 */
@Composable
private fun PhonePager(st: Studio, g: Geometry, fadePx: Float, bandPaint: androidx.compose.ui.graphics.Paint) {
    // What it shows is read again whenever Shizuku comes or goes, the settings come back, or an action here is done.
    val tick = remember { mutableIntStateOf(0) }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val l: () -> Unit = { main.post { tick.intValue++ } }
        dev.launcher.app.ShizukuLink.addListener(l)
        onDispose { dev.launcher.app.ShizukuLink.removeListener(l) }
    }
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick.intValue++ }
        lifecycle.addObserver(o)
        onDispose { lifecycle.removeObserver(o) }
    }
    // The log's newest lines and the updater's last word, while Phone (or its Developer page) is open and at rest (a few times
    // a second at most: each is laid out again). Watched outside the pages' composition: reading which page is open there
    // recomposed a whole page whenever any page opened (14 ms in the first frame of opening Look on the S24).
    val feed = remember { LogFeed() }
    LaunchedEffect(st) {
        androidx.compose.runtime.snapshotFlow { st.open == Part.PHONE && !st.p.isRunning && !st.subPush.isRunning }.collectLatest { open ->
            if (!open) return@collectLatest
            feed.read()
            var pending = false
            val l: (String) -> Unit = { if (!pending) { pending = true; main.postDelayed({ pending = false; feed.read() }, 300) } }
            dev.launcher.app.AppLog.addListener(l)
            try { kotlinx.coroutines.awaitCancellation() } finally { dev.launcher.app.AppLog.removeListener(l) }
        }
    }
    SubPager(st, g, Part.PHONE, fadePx, bandPaint, { push, lit -> PhonePage(tick, feed, push, lit) }) { _, pop -> DeveloperPage(tick, feed, pop) }
}

@Composable
private fun PhonePage(tick: androidx.compose.runtime.MutableIntState, feed: LogFeed, push: (Pushed) -> Unit, lit: () -> Pushed?) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as? dev.launcher.app.LauncherApp
    val shizuku = remember(tick.intValue) { dev.launcher.app.ShizukuLink.state() }
    Group("Shizuku", "The advanced features (the shade, gestures, app switching, updates) work through Shizuku.") {
        InfoRow("Status", when (shizuku) {
            dev.launcher.app.ShizukuLink.State.CONNECTED -> "Connected"
            dev.launcher.app.ShizukuLink.State.CONNECTING -> "Connecting"
            dev.launcher.app.ShizukuLink.State.NO_PERMISSION -> "Not allowed yet"
            dev.launcher.app.ShizukuLink.State.NOT_RUNNING -> "Not running"
        })
        Reveal(shizuku != dev.launcher.app.ShizukuLink.State.CONNECTED) {
            ActionRow("Connect", "Start Shizuku first (wireless debugging)") { dev.launcher.app.ShizukuLink.connect() }
        }
    }
    Group("Updates") {
        ActionRow("Check for updates", feed.update) { app?.let { dev.launcher.app.Updater.update(it, force = false) } }
    }
    var restored by remember { mutableStateOf<String?>(null) }
    Group("Safety", "If the status bar is missing, animations are off or the launcher misbehaves. Restarting the phone also brings the status bar back.") {
        ActionRow("Restore system", restored ?: "The status bar, gestures and animations as they were") {
            restored = "Restoring..."
            dev.launcher.app.DevTools.restore(ctx) { restored = it; tick.intValue++ }
        }
        InfoRow("Restore without Shizuku", if (remember(tick.intValue) { dev.launcher.app.SystemRestore.canWriteSecureSettings(ctx) }) "Ready" else "Connect Shizuku once")
        InfoRow("Safety notification", if (remember(tick.intValue) { dev.launcher.app.SafetyNotification.canPost(ctx) }) "Allowed" else "Blocked")
        NavRow("Choose a different home app", null) { openSystemSettings(ctx, android.provider.Settings.ACTION_HOME_SETTINGS) }
    }
    Group("About") {
        BuildRow(app?.buildStamp() ?: "?")
    }
    // Once turned on (seven taps on the build): the testing tools and the token editor.
    Reveal(SettingsPrefs.developer) {
        Column {
            Group("Developer") {
                PushRow("Developer tools", "Tests, slow animations, the log", Sub.DEVELOPER, push, lit)
                NavRow("Every value of the theme", "The token editor, for theme authors") {
                    ctx.startActivity(Intent(ctx, dev.launcher.app.design.DesignActivity::class.java))
                }
            }
        }
    }
}

/** How many taps on the build turn the developer tools on (Android's own number). */
private const val DEVELOPER_TAPS = 7

/**
 * The build this is; seven taps on it turn the developer tools on (Android's way). From the third, a line under it counts
 * down; once on, a tap says where they are.
 */
@Composable
private fun BuildRow(build: String) {
    val look = LocalLook.current
    val ctx = LocalContext.current
    var taps by remember { mutableIntStateOf(0) }
    // A pause in the tapping starts the count again.
    LaunchedEffect(taps) { if (taps > 0) { kotlinx.coroutines.delay(3000); taps = 0 } }
    val said = when {
        SettingsPrefs.developer && taps > 0 -> "The developer tools are on, below"
        taps >= 3 -> (DEVELOPER_TAPS - taps).let { "$it more tap" + (if (it == 1) "" else "s") + " for the developer tools" }
        else -> null
    }
    // (It keeps its last words while its line closes.)
    val last = remember { arrayOf("") }
    if (said != null) last[0] = said
    BaseRow("Build", null, {
        if (SettingsPrefs.developer) taps = 1
        else if (taps + 1 >= DEVELOPER_TAPS) { SettingsPrefs.setDeveloper(ctx, true); taps = 1 }
        else taps++
    }, below = {
        Reveal(said != null) { Rolling(last[0], look.text(SettingsTokens.ROW_DETAIL, look.color(SettingsTokens.ACCENT)), Modifier.fillMaxWidth()) }
    }) {
        BasicText(build, style = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_DETAIL_COLOR)))
    }
}

/**
 * Phone's Developer page: the tools for testing the launcher, what it has done to the phone, and the log. A switch at its
 * top hides it (and the token editor) again.
 */
@Composable
private fun DeveloperPage(tick: androidx.compose.runtime.MutableIntState, feed: LogFeed, pop: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as? dev.launcher.app.LauncherApp
    val connected = remember(tick.intValue) { dev.launcher.app.ShizukuLink.state() } == dev.launcher.app.ShizukuLink.State.CONNECTED
    Group(null, "On Phone until turned off here.") {
        ToggleRow("Developer tools", null, SettingsPrefs.developer) { on ->
            SettingsPrefs.setDeveloper(ctx, on)
            if (!on) pop()
        }
    }
    Group("Tests", "For testing the launcher. Each takes effect at once; what they find goes to the log below.") {
        var gestures by remember(tick.intValue) { mutableStateOf(dev.launcher.app.SystemRestore.gesturesWanted(ctx)) }
        ToggleRow("Gesture navigation", "The launcher's own gestures and app cards", gestures) { on ->
            if (connected) { gestures = on; dev.launcher.app.DevTools.setGestures(ctx, on) { tick.intValue++ } } else dev.launcher.app.ShizukuLink.connect()
        }
        var bar by remember(tick.intValue) { mutableStateOf(dev.launcher.app.SystemRestore.statusBarWanted(ctx)) }
        ToggleRow("Our status bar", "The phone's own hidden", bar) { on ->
            if (connected) { bar = on; dev.launcher.app.DevTools.setOwnStatusBar(ctx, on) { tick.intValue++ } } else dev.launcher.app.ShizukuLink.connect()
        }
        ToggleRow("Test themes and presets", "Shown in Look and Motion with the others", SettingsPrefs.testPresets) { on -> SettingsPrefs.setTestPresets(ctx, on) }
        SlowRow()
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
    Group("The phone now") {
        InfoRow("Status bar", if (remember(tick.intValue) { dev.launcher.app.SystemRestore.statusBarHidden(ctx) }) "Ours (stock one hidden)" else "The phone's own")
        InfoRow("Animations", remember(tick.intValue) { dev.launcher.app.SystemRestore.currentScales(ctx) })
        NavRow("Animation scales", "Android's developer options") { openSystemSettings(ctx, android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) }
        ActionRow("Install the newest again", "Even if it is the build you have") { app?.let { dev.launcher.app.Updater.update(it, force = true) } }
    }
    Group("Log") {
        BasicText(feed.log.ifEmpty { "Nothing yet." }, style = LocalLook.current.text(SettingsTokens.ROW_DETAIL, LocalLook.current.color(SettingsTokens.ROW_DETAIL_COLOR))
            .copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp)),
            modifier = Modifier.fillMaxWidth().padding(LocalLook.current.dp(SettingsTokens.ROW_PADDING)))
        ActionRow("Copy log", null) { dev.launcher.app.DevTools.copyLog(ctx) }
    }
}

/** One of the phone's own settings pages (its Settings app if this phone has no such page). */
private fun openSystemSettings(ctx: android.content.Context, action: String) {
    try { ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (_: android.content.ActivityNotFoundException) { ctx.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * Slow animations, for checking them frame by frame (as iOS's): every animation of the preset in use this many times
 * slower (`motion.debug.slow`, an edit of the preset), at once as everything on Phone.
 */
@Composable
private fun SlowRow() {
    val look = LocalLook.current
    val pad = look.dp(SettingsTokens.ROW_PADDING)
    val separator = look.color(SettingsTokens.ROW_SEPARATOR)
    val counter = LocalRowCounter.current
    val first = remember { counter == null || counter.n++ == 0 }
    val factors = listOf(1f, 2f, 4f, 10f)
    val now = (try { Design.resolved(MotionTokens.DEBUG_SLOW.name) } catch (_: Throwable) { null } as? dev.launcher.app.design.Value.Number)?.v ?: 1f
    val chosen = factors.indexOfFirst { kotlin.math.abs(it - now) < 0.01f }.coerceAtLeast(0)
    Column(Modifier.fillMaxWidth()
        .drawBehind { if (!first) drawRect(separator, Offset(pad.toPx(), 0f), Size(size.width - pad.toPx(), 1f)) }
        .padding(horizontal = pad, vertical = look.dp(10f * look.unit))) {
        BasicText("Slow animations", style = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_LABEL)))
        BasicText("Every animation this many times slower, to check it frame by frame",
            style = look.text(SettingsTokens.ROW_DETAIL, look.color(SettingsTokens.ROW_DETAIL_COLOR)))
        Spacer(Modifier.height(look.dp(8f * look.unit)))
        ChoiceChips(listOf("Off", "2×", "4×", "10×"), chosen) { i ->
            if (i == 0) Design.reset(MotionTokens.DEBUG_SLOW.name)
            else Design.set(MotionTokens.DEBUG_SLOW.name, dev.launcher.app.design.Value.Number(factors[i], dev.launcher.app.design.NumUnit.FACTOR))
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
internal class RowCounter { var n = 0 }

internal val LocalRowCounter = androidx.compose.runtime.compositionLocalOf<RowCounter?> { null }

/**
 * A group of rows on a solid card (as Apple's apps: no glass on content), with a heading and a note; as its page opens
 * it rises in a beat after the group above.
 */
@Composable
internal fun Group(title: String?, note: String? = null, rows: @Composable () -> Unit) {
    val look = LocalLook.current
    val pad = look.dp(SettingsTokens.ROW_PADDING)
    val shown = LocalShown.current
    // Its place in the page's order, taken once (composed again alone, a group took the next number on and kept it).
    val groups = LocalGroups.current
    val index = remember { groups.n++ }
    val rise = Modifier.graphicsLayer {
        // From the fifth on they rise together, so every one is whole by the end of the page's motion (capped at the
        // sixth, the sixth stayed at 89 % and those after it at 75 % for good).
        val k = ((shown() - 0.3f - 0.06f * index.coerceAtMost(4)) / 0.45f).coerceIn(0f, 1f)
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
        .clip(RoundedCornerShape(look.dp(SettingsTokens.GROUP_CORNER)))
        .then(if (title != null) Modifier.anchor(title) else Modifier)) {
        CompositionLocalProvider(LocalRowCounter provides counter) { rows() }
    }
    if (note != null) {
        BasicText(note, style = look.text(SettingsTokens.ROW_DETAIL, look.color(SettingsTokens.ROW_DETAIL_COLOR)),
            modifier = rise.padding(start = pad, end = pad, top = look.dp(6f * look.unit)))
    }
    Spacer(Modifier.height(look.dp(24f * look.unit)))
}

/**
 * One row: a name, an optional detail below it ([below]: more under that), something at its end; pressed (or [held]: what it
 * opens being built), it lights up.
 */
@Composable
internal fun BaseRow(title: String, detail: String?, onClick: (() -> Unit)?, accent: Boolean = false, changing: Boolean = false,
                     held: Boolean = false, below: (@Composable () -> Unit)? = null, changed: Boolean = false, separator: Boolean? = null,
                     end: @Composable () -> Unit) {
    val look = LocalLook.current
    val counter = LocalRowCounter.current
    val counted = remember { counter == null || counter.n++ == 0 }
    val first = separator?.let { !it } ?: counted
    // A dot before its name while it differs from the theme's or the preset's (V3).
    val dot by animateFloatAsState(if (changed) 1f else 0f, look.motion(if (changed) MotionTokens.APPEAR else MotionTokens.DISAPPEAR_FADE), label = "dot")
    val dotColor = look.color(SettingsTokens.ACCENT)
    var pressed by remember { mutableStateOf(false) }
    val down = pressed || held
    val press by animateFloatAsState(if (down) 1f else 0f, look.motion(if (down) MotionTokens.PRESS_IN else MotionTokens.PRESS_OUT), label = "press")
    val pad = look.dp(SettingsTokens.ROW_PADDING)
    val separatorColor = look.color(SettingsTokens.ROW_SEPARATOR)
    Row(
        Modifier.fillMaxWidth().heightIn(min = look.dp(SettingsTokens.ROW_HEIGHT))
            .drawBehind {
                if (press > 0.001f) drawRect(Color(Appearance.pressFill).copy(alpha = Color(Appearance.pressFill).alpha * press))
                // Between rows: a hairline above each but the first (inset by the padding, as iOS's).
                if (!first) drawRect(separatorColor, Offset(pad.toPx(), 0f), Size(size.width - pad.toPx(), 1f))
                if (dot > 0.003f) drawCircle(dotColor.copy(alpha = dotColor.alpha * dot.coerceIn(0f, 1f)), 3.5f * look.unit * dot,
                    Offset(pad.toPx() / 2f, 21f * look.unit))
            }
            .anchor(title)
            .then(if (onClick == null) Modifier else Modifier.pointerInput(onClick) { rowTap({ pressed = it }, onClick) })
            .padding(horizontal = pad, vertical = look.dp(10f * look.unit)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(title, style = look.text(SettingsTokens.ROW_TITLE, look.color(if (accent) SettingsTokens.ACCENT else SettingsTokens.ROW_LABEL)))
            val detailStyle = look.text(SettingsTokens.ROW_DETAIL, look.color(SettingsTokens.ROW_DETAIL_COLOR))
            if (changing) { if (detail != null) Rolling(detail, detailStyle, Modifier.fillMaxWidth(), lines = 6) }
            else if (detail != null) BasicText(detail, style = detailStyle)
            below?.invoke()
        }
        Spacer(Modifier.width(look.dp(12f * look.unit)))
        end()
    }
}

@Composable
internal fun NavRow(title: String, detail: String?, onClick: () -> Unit) = BaseRow(title, detail, onClick) {
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
internal fun ActionRow(title: String, detail: String?, onClick: () -> Unit) = BaseRow(title, detail, onClick, accent = true, changing = true) { }

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
private fun CheckRow(title: String, detail: String?, chosen: Boolean, changed: Boolean = false, onChoose: () -> Unit) =
    BaseRow(title, detail, { if (!chosen) onChoose() }, changed = changed) {
    val look = LocalLook.current
    val k by animateFloatAsState(if (chosen) 1f else 0f, look.motion(MotionTokens.CC_TOGGLE), label = "check")
    BasicText("✓", style = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_CHECK).copy(alpha = k)))
}

@Composable
private fun ToggleRow(title: String, detail: String?, on: Boolean, changed: Boolean = false, onChange: (Boolean) -> Unit) =
    BaseRow(title, detail, { onChange(!on) }, changed = changed) {
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
