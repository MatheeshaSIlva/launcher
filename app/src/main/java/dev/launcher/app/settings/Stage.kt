package dev.launcher.app.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import dev.launcher.app.design.CurveKey
import dev.launcher.app.design.Design
import dev.launcher.app.home.HomeConfig
import dev.launcher.app.home.HomeMetrics
import dev.launcher.app.layout.Element
import dev.launcher.app.motion.MotionTokens
import dev.launcher.app.motion.mover
import dev.launcher.app.theme.Appearance
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// A part's stage (V5, docs/PLAN_SETTINGS_V2.md; Matheesha, 2026-10-16: the live preview only where it is needed, the token
// editor; "Otherwise, it would be better to use a motion graphic"): a part's page opens with its card grown into a stage at
// the top of the page, the card's own scene giving way to the phone drawn as a motion graphic ([PhoneScene]) in the part's
// colours, at the part (its band of the phone). It shows what the draft would make (home's grid and dock, Control Center's
// columns, where the App Library is), plays how the part is reached (a finger, and what it opens following it), and Motion's
// moments on it (MotionSamples). It stays while the part's own pages are pushed below it, and shrinks back into the card.

/** Where on the phone a part's stage looks: a band of its height (its full width), the whole phone for most. */
internal fun stageFocus(part: Part?, placement: String, statusFrac: Float): Rect = when (part) {
    // The bar at about its real size.
    Part.STATUS_BAR -> Rect(0f, 0f, 1f, (statusFrac * 3f).coerceIn(0.08f, 0.16f))
    // Where the panels are pulled from, and their upper half.
    Part.SHADE -> Rect(0f, 0f, 1f, 0.5f)
    Part.LIBRARY -> if (placement == "swipe-up") Rect(0f, 0.34f, 1f, 1f) else Rect(0f, 0f, 1f, 0.66f)
    else -> WHOLE
}

private val WHOLE = Rect(0f, 0f, 1f, 1f)

/**
 * How element [e] is reached in [placement], as the finger's strokes on the phone (fractions of the screen: from, to), shown
 * one after another on the stage; [hold]: the finger rests at the end (the gesture is a hold). Null: nothing to show.
 */
internal class Gesture(val strokes: List<Pair<Offset, Offset>>, val hold: Boolean = false)

internal fun gestureOf(e: Element?, placement: String): Gesture? = when (e) {
    // Notifications from the top's left, controls from its right.
    Element.SHADE -> Gesture(listOf(Offset(0.25f, 0.005f) to Offset(0.25f, 0.2f), Offset(0.78f, 0.005f) to Offset(0.78f, 0.2f)))
    // Up from the bottom edge, then a rest.
    Element.RECENTS -> Gesture(listOf(Offset(0.5f, 0.995f) to Offset(0.5f, 0.8f)), hold = true)
    Element.DRAWER -> when (placement) {
        "page-after-last" -> Gesture(listOf(Offset(0.82f, 0.5f) to Offset(0.18f, 0.5f)))
        "page-before-first" -> Gesture(listOf(Offset(0.18f, 0.5f) to Offset(0.82f, 0.5f)))
        "swipe-up" -> Gesture(listOf(Offset(0.5f, 0.86f) to Offset(0.5f, 0.6f)))
        else -> null
    }
    else -> null
}

/** What a stroke opens on the stage, how far it follows the finger, and the draft's curves it opens and closes on. */
private enum class Opens(val follow: Float, val open: CurveKey, val close: CurveKey) {
    NC(0.32f, MotionTokens.NC_OPEN, MotionTokens.NC_CLOSE),
    CC(0.32f, MotionTokens.CC_OPEN, MotionTokens.CC_CLOSE),
    /** The App Switcher: the app shrinks into its card as the finger rises, the deck comes as it rests. */
    DECK(0.42f, MotionTokens.SWITCHER_ENTER, MotionTokens.SWITCHER_OPEN),
    /** The App Library's page from the right (the finger to the left), from the left, or its sheet from below. */
    LIBRARY_LEFT(0.42f, MotionTokens.PAGE_SNAP, MotionTokens.PAGE_SNAP),
    LIBRARY_RIGHT(0.42f, MotionTokens.PAGE_SNAP, MotionTokens.PAGE_SNAP),
    LIBRARY_UP(0.4f, MotionTokens.DRAWER, MotionTokens.DRAWER),
}

private fun opensOf(part: Part, placement: String, stroke: Int): Opens? = when (part) {
    Part.SHADE -> if (stroke == 0) Opens.NC else Opens.CC
    Part.APPS -> Opens.DECK
    Part.LIBRARY -> when (placement) {
        "page-before-first" -> Opens.LIBRARY_RIGHT
        "swipe-up" -> Opens.LIBRARY_UP
        else -> Opens.LIBRARY_LEFT
    }
    else -> null
}

/** The finger on the stage and what its stroke opens (written by the stage's loop, read in drawing). */
@Stable
private class StageAct {
    val at = mutableIntStateOf(0)
    val t = mutableFloatStateOf(0f)
    val finger = Animatable(0f)
    val panel = Animatable(0f)
    var opens by mutableStateOf<Opens?>(null)
    var gesture: Gesture? = null
}

/** Home as the draft lays it out: its columns, rows, dock and names, the App Library's columns. */
private fun stageHome(d: Draft) = HomeConfig(
    columns = d.int(Element.HOME, "columns", 4),
    rows = d.int(Element.HOME, "rows", 6),
    dockSlots = d.int(Element.HOME, "dock-icons", 4),
    libraryColumns = d.int(Element.DRAWER, "columns", 2),
    showLabels = d.bool(Element.HOME, "show-labels", true),
)

/** The stage's inner frame in a card [r] (px): where the phone is drawn. */
private fun innerOf(r: Rect, inset: Float) = Rect(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset)

/**
 * The open part's card, grown from its tile (or the row it was found in) into the stage at the top of its page, and back
 * ([Studio.p]), over the page whose groups rise in under it. Drawn in the sheet's coordinates, in one pass, in a layer of
 * its own; it takes no touches.
 */
@Composable
internal fun Stage(st: Studio, g: Geometry, art: State<ArtKit>) {
    val look = LocalLook.current
    val draft = LocalDraft.current
    val ctx = LocalContext.current
    val scene = remember { PhoneScene() }
    val kit = remember { SampleKit() }
    val measurer = rememberTextMeasurer()
    val statusFrac = g.statusPx / g.h
    val view = androidx.compose.ui.platform.LocalView.current
    val screenCorner = remember(view) {
        if (android.os.Build.VERSION.SDK_INT >= 31) view.display?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() ?: 0f else 0f
    }
    // The theme's faces for the phone's words (a design change makes them again).
    val faces = remember(look.version) { dev.launcher.app.theme.Fonts.text(600) to dev.launcher.app.theme.Fonts.display(600) }
    val resumed = rememberResumed()

    // ---- home as the draft lays it out; a change glides there (as home reflows)
    val reflow = remember { Animatable(1f) }
    LaunchedEffect(scene, g) {
        snapshotFlow {
            val f = HomeMetrics.frame
            val fw = f?.w ?: g.w.toInt()
            val fh = f?.h ?: g.h.toInt()
            Triple(stageHome(draft), draft.unit(ctx, minOf(fw, fh)), f)
        }.collectLatest { (cfg, unit, f) ->
            scene.reflow = reflow.value
            val fw = (f?.w ?: g.w.toInt()).toFloat()
            val fh = (f?.h ?: g.h.toInt()).toFloat()
            val corner = if (screenCorner > 0f) screenCorner * fw / g.w else fw * Design.num(SettingsTokens.PREVIEW_CORNER)
            if (scene.layout(fw, fh, unit, f?.topInset ?: g.statusPx.toInt(), f?.bottomInset ?: g.navPx.toInt(), corner, cfg)) {
                reflow.snapTo(0f)
                reflow.animateTo(1f, Look.spec(draft.motionCurve(draft.motionId(), MotionTokens.REFLOW)))
            }
        }
    }

    // ---- where the stage looks: the part's band, or the moment's (a moment's page pushed below it)
    val momentIn = remember(st) {
        derivedStateOf { (st.sub as? MomentPage)?.takeIf { st.open == it.part && st.subPush.targetValue >= 0.5f } }
    }
    val focusTarget = remember(st, draft) {
        derivedStateOf {
            momentIn.value?.let { MotionSamples.focus(it.moment, st.motionRole, statusFrac) }
                ?: stageFocus(st.open, st.open?.element?.let { draft.placement(it) } ?: "", statusFrac)
        }
    }
    val crop = remember { Animatable(WHOLE, Rect.VectorConverter) }
    val cropFrom = remember { arrayOf(WHOLE) }
    val focusSpec = rememberUpdatedState(look.motion<Rect>(MotionTokens.SETTINGS_FOCUS))
    LaunchedEffect(st) {
        var shownFor: Part? = null
        snapshotFlow { st.open to focusTarget.value }.collectLatest { (open, target) ->
            // A page opening is at its part from its first frame (its growth is its motion); then it glides between bands.
            if (open != shownFor) { shownFor = open; cropFrom[0] = target; crop.snapTo(target); return@collectLatest }
            cropFrom[0] = crop.value
            crop.animateTo(target, focusSpec.value)
        }
    }

    // ---- the stage's clock: the parts' own acts (home's wiggle, the minute, Look's colours, Phone's pulse)
    val clock = remember { mutableFloatStateOf(0f) }
    val actsOn = remember(st) {
        derivedStateOf {
            val open = st.open
            open != null && open != Part.CHANGES && !st.p.isRunning && st.p.value > 0.99f && resumed.value &&
                !(st.sub?.part == open && st.subPush.targetValue >= 0.5f)
        }
    }
    // How present the acts are: they settle back to rest when a page is pushed or the page goes (never cut mid-move).
    val acts = remember { Animatable(0f) }
    LaunchedEffect(st) {
        snapshotFlow { actsOn.value }.collectLatest { on ->
            if (!on) { acts.animateTo(0f, look.motion(MotionTokens.DISAPPEAR_FADE)); return@collectLatest }
            // From a beat before the first act.
            clock.floatValue = -0.6f
            launch { acts.animateTo(1f, look.motion(MotionTokens.APPEAR_FADE)) }
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                // A long frame counts as a short one (a stall pauses the acts, never makes them jump).
                clock.floatValue += ((now - last) / 1e9f).coerceAtMost(LOOP_STEP_MAX)
                last = now
            }
        }
    }

    // ---- how the part is reached: the finger, and what it opens following it
    val act = remember { StageAct() }
    val reach = remember(st, draft) {
        derivedStateOf {
            val part = st.open
            val e = part?.element
            if (!actsOn.value || e == null) null else {
                val placement = draft.placement(e)
                gestureOf(e, placement)?.let { Triple(part, placement, it) }
            }
        }
    }
    LaunchedEffect(act) {
        snapshotFlow { reach.value }.collectLatest { on ->
            val fadeOut = look.motion<Float>(MotionTokens.DISAPPEAR_FADE)
            // Whatever shows goes first: the finger fades, what it opened closes on its own curve (a page pushed, the
            // page closing, another placement chosen mid-act: the next one starts from rest, nothing is cut).
            coroutineScope {
                launch { act.finger.animateTo(0f, fadeOut) }
                val o = act.opens
                if (o != null && act.panel.value > 0.001f) act.panel.animateTo(0f, Look.spec(draft.motionCurve(draft.motionId(), o.close)))
            }
            if (on == null) return@collectLatest
            val (part, placement, gs) = on
            act.gesture = gs
            act.opens = null
            delay(650)
            val travel = dev.launcher.app.motion.Motion.role(MotionTokens.SETTINGS_HINT)
            while (true) {
                for (i in gs.strokes.indices) {
                    val o = opensOf(part, placement, i)
                    act.opens = o
                    act.at.intValue = i
                    act.t.floatValue = 0f
                    act.finger.animateTo(1f, look.motion(MotionTokens.APPEAR_FADE))
                    val m = travel.mover().apply { start(0f, 0f, 1f) }
                    val t0 = withFrameNanos { it }
                    while (true) {
                        val t = (withFrameNanos { it } - t0) / 1e9
                        val v = m.value(t).coerceIn(0f, 1f)
                        act.t.floatValue = v
                        if (o != null) act.panel.snapTo(v * o.follow)
                        if (m.settled(t, 0.002f)) { act.t.floatValue = 1f; break }
                    }
                    // Let go (a hold first): what it opened settles open on its own curve as the finger lifts.
                    if (gs.hold) delay(420)
                    coroutineScope {
                        if (o != null) launch { act.panel.animateTo(1f, Look.spec(draft.motionCurve(draft.motionId(), o.open))) }
                        act.finger.animateTo(0f, fadeOut)
                    }
                    delay(1150)
                    if (o != null) act.panel.animateTo(0f, Look.spec(draft.motionCurve(draft.motionId(), o.close)))
                    delay(520)
                }
                delay(700)
            }
        }
    }

    // ---- Motion: an app opens and closes on the phone in the draft's ways, then a banner drops in and goes, again and again
    val demo = remember { mutableFloatStateOf(-1f) }
    val demoOpening = remember { mutableStateOf(true) }
    val demoBanner = remember { mutableFloatStateOf(-1f) }
    val demoAlpha = remember { Animatable(1f) }
    val motionOn = remember(st) { derivedStateOf { actsOn.value && st.open == Part.MOTION } }
    LaunchedEffect(st) {
        snapshotFlow { motionOn.value }.collectLatest { on ->
            // Whatever shows of it fades where it is (also when Motion comes back mid-fade: it starts again, closed).
            if (demo.floatValue > 0.001f || demoBanner.floatValue >= 0f) demoAlpha.animateTo(0f, look.motion(MotionTokens.DISAPPEAR_FADE))
            demo.floatValue = -1f
            demoBanner.floatValue = -1f
            if (!on) return@collectLatest
            suspend fun run(key: CurveKey, from: Float, to: Float, v: androidx.compose.runtime.MutableFloatState) {
                val m = draft.motionCurve(draft.motionId(), key).mover().apply { start(from, 0f, to) }
                var last = withFrameNanos { it }
                var t = 0.0
                while (true) {
                    val now = withFrameNanos { it }
                    t += ((now - last) / 1e9).coerceAtMost(LOOP_STEP_MAX.toDouble())
                    last = now
                    v.floatValue = m.value(t)
                    if (m.settled(t, 0.002f)) { v.floatValue = to; break }
                }
            }
            demo.floatValue = 0f
            demoAlpha.snapTo(1f)
            delay(500)
            while (true) {
                demoOpening.value = true
                run(MotionTokens.APP_OPEN, 0f, 1f, demo)
                delay(650)
                demoOpening.value = false
                run(MotionTokens.APP_CLOSE_SIZE, 1f, 0f, demo)
                delay(700)
                run(MotionTokens.BANNER_IN, 0f, 1f, demoBanner)
                delay(1100)
                run(MotionTokens.BANNER_OUT, 1f, 0f, demoBanner)
                demoBanner.floatValue = -1f
                delay(700)
            }
        }
    }
    // Control Center's columns as the draft has them: a change resizes its controls on the reflow's curve.
    val ccColumns = remember { Animatable(-1f) }
    LaunchedEffect(draft) {
        // (As many as Control Center shows: only what fits at the draft's size.)
        snapshotFlow { draft.ccColumnsShown(ctx).toFloat() }.collectLatest { n ->
            if (ccColumns.value < 0f) ccColumns.snapTo(n)
            else ccColumns.animateTo(n, Look.spec(draft.motionCurve(draft.motionId(), MotionTokens.REFLOW)))
        }
    }
    // Look's phone turns dark or light as the draft's appearance would show it.
    val lookDark = remember { Animatable(Appearance.dark) }
    LaunchedEffect(draft) {
        snapshotFlow {
            when (draft.appearanceMode()) {
                Appearance.Mode.LIGHT -> 0f
                Appearance.Mode.DARK -> 1f
                else -> Appearance.dark
            }
        }.collectLatest { lookDark.animateTo(it, Look.spec(draft.motionCurve(draft.motionId(), MotionTokens.APPEARANCE_CHANGE))) }
    }

    val tileCorner = look.pt(SettingsTokens.TILE_CORNER)
    val share = Design.num(SettingsTokens.TILE_VISUAL).coerceIn(0.2f, 0.7f)
    val titleTint = Design.num(SettingsTokens.TILE_TITLE_TINT).coerceIn(0f, 1f)
    val label = look.color(SettingsTokens.ROW_LABEL)
    val groupFill = look.color(SettingsTokens.GROUP_FILL)
    val pressFill = Color(Appearance.pressFill)
    val rim = look.color(SettingsTokens.PREVIEW_RIM).toArgb()
    val rimW = look.pt(SettingsTokens.PREVIEW_RIM_WIDTH)
    val shadow = look.color(SettingsTokens.PREVIEW_SHADOW).toArgb()
    val stageInset = look.pt(SettingsTokens.STAGE_INSET)
    val paints = remember { StagePaints() }
    val wave = remember { IntArray(PhoneScene.COLOR_SLOTS) }
    val iconRect = remember { android.graphics.RectF() }
    val cardPath = remember { Path() }
    val paletteInts = remember { PaletteInts() }
    val wallMatrix = remember { WallMatrix() }
    // Look's icons: the palette the draft would make, at its light tones (readable on any wallpaper: its mid tones were
    // dark slate on a slate phone under the wallpaper's colours), else the theme's own three.
    val lookIcons = remember(look, look.version, draft) {
        derivedStateOf {
            if (!draft.wallpaperColors()) art.value.palette else {
                val dark = Appearance.dark > 0.5f
                val choice = draft.youChoice()
                listOf("primary_fixed", "tertiary_fixed", "secondary_fixed").map { role ->
                    Color(dev.launcher.app.design.Theme.resolveSpec("@$role", dark) { Design.previewPalette(it, choice) })
                }
            }
        }
    }
    // Look's Fonts page: the stage turns to type (a specimen in the fonts the draft would apply) and back.
    val fontsIn = remember(st) { derivedStateOf { st.open == Part.LOOK && st.sub == Sub.FONTS && st.subPush.targetValue >= 0.5f } }
    val specimen = remember { Animatable(0f) }
    LaunchedEffect(st) {
        snapshotFlow { fontsIn.value }.collectLatest { specimen.animateTo(if (it) 1f else 0f, look.motion(MotionTokens.MODE_CROSSFADE)) }
    }
    // The fonts the draft would apply, as layers: a font chosen fades in over what shows while every layer before it fades
    // out from where it is (chosen again mid-fade, nothing jumps). Followed from the draft, not read in composition.
    val typeLayers = remember { ArrayList<TypeLayer>() }
    LaunchedEffect(draft, look.version) {
        snapshotFlow { draft.font("sys.font.text") to draft.font("sys.font.display") }.collect { (textId, displayId) ->
            val faces = TypeSet(faceFor(textId, 400), faceFor(displayId, 700), textId, displayId)
            val layer = TypeLayer(faces, Animatable(if (typeLayers.isEmpty()) 1f else 0f))
            val leaving = typeLayers.toList()
            typeLayers.add(layer)
            val spec = look.motion<Float>(MotionTokens.APPEAR_FADE)
            launch { layer.alpha.animateTo(1f, spec) }
            for (l in leaving) launch { l.alpha.animateTo(0f, spec); typeLayers.remove(l) }
        }
    }
    val typeInk = look.color(SettingsTokens.ROW_LABEL).toArgb()
    val typeSub = look.color(SettingsTokens.ROW_DETAIL_COLOR).toArgb()
    val typePaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    val typeMetrics = remember { android.graphics.Paint.FontMetrics() }
    val glyphBounds = remember { android.graphics.Rect() }

    /**
     * The fonts Look's Fonts page would apply, as type in the stage's frame [v] at [a]: "Aa" in the titles' face, and each
     * font named in its own face over what it is for. A long name is set smaller until it fits (never cut).
     */
    fun typeSpecimen(nc: android.graphics.Canvas, v: Rect, a: Float) {
        if (a <= 0.003f) return
        val u = look.unit
        val p = typePaint
        val fm = typeMetrics
        // One layout for every font (a font chosen changes the letters, never where they are): "Aa" centred in the frame's
        // left part, the names from a fixed line.
        val left = v.left + 18f * u
        val x = v.left + v.width * 0.46f
        val aaRoom = x - 22f * u - left
        fun pass(faces: TypeSet, k: Float) {
            if (k <= 0.003f) return
            // "Aa" in ink, as on Look's card, its letters (not its line) centred in their room; a wide face set smaller.
            p.typeface = faces.title
            p.textSize = v.height * 0.5f
            p.getTextBounds("Aa", 0, 2, glyphBounds)
            if (glyphBounds.width() > aaRoom && aaRoom > 0f) {
                p.textSize *= aaRoom / glyphBounds.width()
                p.getTextBounds("Aa", 0, 2, glyphBounds)
            }
            p.color = PhoneScene.withAlpha(typeInk, a * k)
            nc.drawText("Aa", left + (aaRoom - glyphBounds.width()) / 2f - glyphBounds.left, v.center.y - glyphBounds.exactCenterY(), p)
            val room = v.right - 18f * u - x
            if (room <= 0f) return
            val nameSize = 24f * u
            val labelSize = 13f * u
            // The two entries as one block, centred: a name's line, a label's line under it, a gap, the other.
            fun nameLine(name: String, face: android.graphics.Typeface): Float {
                p.typeface = face
                p.textSize = nameSize
                val w = p.measureText(name)
                if (w > room) p.textSize = nameSize * room / w
                p.getFontMetrics(fm)
                return fm.descent - fm.ascent
            }
            fun labelLine(): Float {
                p.typeface = faces.text
                p.textSize = labelSize
                p.getFontMetrics(fm)
                return fm.descent - fm.ascent
            }
            val display = fontName(faces.displayId)
            val text = fontName(faces.textId)
            val gap = 14f * u
            val block = nameLine(display, faces.title) + labelLine() + gap + nameLine(text, faces.text) + labelLine()
            var top = v.center.y - block / 2f
            fun entry(name: String, face: android.graphics.Typeface, label: String) {
                nameLine(name, face)
                p.color = PhoneScene.withAlpha(typeInk, a * k)
                nc.drawText(name, x, top - fm.ascent, p)
                top += fm.descent - fm.ascent
                labelLine()
                p.color = PhoneScene.withAlpha(typeSub, a * k)
                nc.drawText(label, x, top - fm.ascent, p)
                top += fm.descent - fm.ascent
            }
            entry(display, faces.title, "Titles")
            top += gap
            entry(text, faces.text, "Text")
        }
        for (l in typeLayers) pass(l.faces, l.alpha.value.coerceIn(0f, 1f))
    }

    /** The App Switcher: the app in front shrinks from the whole screen into its card ([k] 0..1), the older ones come in on its left. */
    fun deck(nc: android.graphics.Canvas, k: Float) {
        val w = scene.w
        val h = scene.h
        val kk = k.coerceIn(0f, 1f)
        scene.wash(nc, scene.veil, kk)
        val cw = w * 0.62f
        val chh = h * 0.62f
        val cx = w * 0.6f
        val cy = h * 0.46f
        val cardCorner = scene.corner * 0.62f
        if (kk > 0.003f) for (j in 2 downTo 1) {
            val x = cx - j * w * 0.36f - (1f - kk) * w * 0.8f
            val sc = 1f - 0.05f * j
            scene.drawAppPage(nc, x - cw * sc / 2f, cy - chh * sc / 2f, x + cw * sc / 2f, cy + chh * sc / 2f, cardCorner * sc, kk, seed = j)
        }
        val l = (cx - cw / 2f) * k
        val t = (cy - chh / 2f) * k
        val r = w + (cx + cw / 2f - w) * k
        val b = h + (cy + chh / 2f - h) * k
        scene.drawAppPage(nc, l, t, r, b, scene.corner + (cardCorner - scene.corner) * kk, 1f, seed = 0)
    }

    /** Phone: a shield over home, sending out a pulse at each act ([tau] s into it, at [a]). */
    fun shield(nc: android.graphics.Canvas, tau: Float, a: Float) {
        val w = scene.w
        val h = scene.h
        scene.wash(nc, PhoneScene.BLACK, 0.14f)
        val sz = w * 0.46f
        val cx = w / 2f
        val cy = h * 0.44f
        val path = paints.shield(sz)
        nc.save()
        nc.translate(cx - sz / 2f, cy - sz * 0.6f)
        scene.paint.color = scene.accent
        nc.drawPath(path, scene.paint)
        paints.check.color = PhoneScene.WHITE
        paints.check.strokeWidth = sz * 0.085f
        nc.drawPath(paints.checkPath, paints.check)
        nc.restore()
        val pulse = (tau / 1.2f).coerceIn(0f, 1f)
        if (pulse in 0.001f..0.999f && a > 0.003f) {
            paints.check.color = PhoneScene.withAlpha(scene.ink, 0.6f * (1f - pulse) * a)
            paints.check.strokeWidth = sz * 0.03f
            nc.drawCircle(cx, cy, sz * (0.62f + 0.4f * pulse), paints.check)
        }
    }

    /** The phone in phone pixels: its wallpaper, the part as the draft has it, what it opens, the samples, the bar, the finger. */
    fun drawPhone(nc: android.graphics.Canvas, part: Part, s: Float) {
        val w = scene.w
        val h = scene.h
        val ak = acts.value.coerceIn(0f, 1f)
        val t = clock.floatValue
        val moves = art.value.moves
        val cycle = moves.cycle
        val cyc = floor(t / cycle).toInt()
        val tau = t - cyc * cycle
        val o = act.opens
        val panel = if (o != null) act.panel.value else 0f
        val dark = Appearance.dark > 0.5f
        val wp = if (part == Part.LOOK) look.wallpaper else null
        if (wp != null) {
            // Look's phone is on your wallpaper (its colours are what Look is about), as home shows it: dimmed in dark, as
            // the draft's light or dark would have it.
            paints.kitImage.alpha = 255
            nc.drawBitmap(wp.bitmap, wallMatrix.of(wp, w.toInt(), h.toInt()), paints.kitImage)
            scene.wash(nc, PhoneScene.BLACK, lookDark.value.coerceIn(0f, 1f) * Design.num(dev.launcher.app.theme.PaletteTokens.WALLPAPER_DIM))
        } else scene.drawWallpaper(nc)
        var barInk = scene.ink
        if (part == Part.APPS) {
            // An app in front; the finger shrinks it into its card and the deck comes as it rests.
            val dk = if (o == Opens.DECK) panel else 0f
            deck(nc, dk)
            barInk = PhoneScene.mix(if (dark) PhoneScene.WHITE else PhoneScene.BLACK, scene.ink, dk.coerceIn(0f, 1f))
        } else {
            // Home (moved aside while the App Library's page comes in over the same wallpaper).
            val lib = if (o == Opens.LIBRARY_LEFT || o == Opens.LIBRARY_RIGHT) panel else 0f
            val dir = if (o == Opens.LIBRARY_RIGHT) 1f else -1f
            nc.save()
            nc.translate(dir * lib * w, 0f)
            when (part) {
                Part.HOME -> {
                    // Its act: the icons wiggle as when home is edited and two change places, and back.
                    val wiggle = if (tau < HOME_WIGGLE_S) minOf(1f, tau / 0.18f, (HOME_WIGGLE_S - tau) / 0.18f).coerceIn(0f, 1f) else 0f
                    val swap = (moves.folder.at(tau - 0.55f) - moves.folder.at(tau - 1.45f)).coerceIn(-0.1f, 1.1f)
                    val cols = scene.metrics?.cfg?.columns ?: 4
                    scene.drawHome(nc, 1f, wiggle * ak, t, 1, cols + cols - 2, swap * ak)
                }
                Part.LOOK -> {
                    // Its act: the icons turn to the next of Look's colours, one after another, and back.
                    val pal = lookIcons.value
                    for (i in wave.indices) {
                        val oi = ((moves.toggle.at(tau - 0.05f * (i % 24)) - moves.toggle.at(tau - 1.5f - 0.05f * (i % 24))) * ak).coerceIn(0f, 1f)
                        wave[i] = mix(pal[i % pal.size], pal[(i + 1) % pal.size], oi).toArgb()
                    }
                    scene.drawHome(nc, 1f, colors = wave)
                }
                else -> scene.drawHome(nc, 1f)
            }
            nc.restore()
            if (lib > 0.001f) {
                scene.wash(nc, PhoneScene.WHITE, 0.14f * lib.coerceIn(0f, 1f))
                scene.drawLibrary(nc, -dir * (1f - lib) * w, 0f, 1f, sheet = false)
            }
            if (o == Opens.LIBRARY_UP && panel > 0.001f) {
                scene.wash(nc, scene.veil, panel.coerceIn(0f, 1f))
                scene.drawLibrary(nc, 0f, (1f - panel) * h, 1f, sheet = true)
            }
            if (o == Opens.NC) scene.drawNotificationCenter(nc, panel)
            if (o == Opens.CC) scene.drawControlCenter(nc, panel, ccColumns.value)
        }
        if (part == Part.PHONE) shield(nc, tau, ak)
        // The samples and Motion's reel draw on the same phone.
        val sm = st.sample
        val role = sm.role
        val sk = sm.k.floatValue
        val sa = sm.alpha.value
        sm.tick.intValue
        val da = demoAlpha.value
        val demoShows = part == Part.MOTION && da > 0.003f && (demo.floatValue >= 0f || demoBanner.floatValue >= 0f)
        if ((role != null && sk > -0.5f && sa > 0.003f) || demoShows) {
            val preset = draft.motionId()
            kit.set(w, h, scene.corner, null, scene.dockIcon(scene.dockMiddle(), iconRect), PhoneScene.withAlpha(scene.paper, 0.97f), scene.paper,
                scene.paperInk, scene.accent, paints.kitPaint, paints.kitImage, paints.kitPath)
            kit.scene = scene
            kit.opening = draft.motionStyle(preset, dev.launcher.app.motion.AppStyles.OPENING)
            kit.closing = draft.motionStyle(preset, dev.launcher.app.motion.AppStyles.CLOSING)
            if (demoShows) {
                val layer = da < 0.997f
                if (layer) nc.saveLayerAlpha(0f, 0f, w, h, (255 * da).roundToInt())
                val d = demo.floatValue
                if (d >= 0f) MotionSamples.draw(kit, nc, "motion.app.open", d, demoOpening.value, Float.NaN)
                val bk = demoBanner.floatValue
                if (bk >= 0f) MotionSamples.draw(kit, nc, "motion.banner.in", bk, true, Float.NaN)
                if (layer) nc.restore()
            }
            if (role != null && sk > -0.5f && sa > 0.003f) {
                val layer = sa < 0.997f
                if (layer) nc.saveLayerAlpha(0f, 0f, w, h, (255 * sa).roundToInt())
                MotionSamples.draw(kit, nc, role, sk, sm.forward.value, sm.number.floatValue)
                if (layer) nc.restore()
            }
        }
        // The status bar: its act on its own stage (the minute rolls on, a notification's icon comes and goes).
        val bar = part == Part.STATUS_BAR
        val roll = if (bar) moves.roll.at(tau - 0.25f) * ak else 0f
        val notice = if (bar) (moves.appear.at(tau - 0.9f) - moves.appear.at(tau - 2.6f)) * ak else 0f
        scene.drawStatusBar(nc, 1f, if (bar) cyc + 1 else 1, roll, notice, barInk)
        scene.drawHomeBar(nc, 1f, barInk)
        // The finger showing how the part is reached: a soft white dot with a trail, at a size that reads on the stage.
        val fa = act.finger.value
        val gs = act.gesture
        if (fa > 0.003f && gs != null) {
            val (a0, a1) = gs.strokes[act.at.intValue.coerceIn(0, gs.strokes.lastIndex)]
            val ft = act.t.floatValue
            val x0 = a0.x * w; val y0 = a0.y * h
            val x = x0 + (a1.x - a0.x) * w * ft
            val y = y0 + (a1.y - a0.y) * h * ft
            val dot = maxOf(w * 0.045f, FINGER_PT * look.unit / s)
            paints.hint.strokeWidth = dot * 1.1f
            if (ft > 0.01f) {
                val n = 10
                for (i in 0 until n) {
                    val b0 = i / n.toFloat(); val b1 = (i + 1) / n.toFloat()
                    paints.hint.color = (((0x99 * fa * b1 * b1).toInt()) shl 24) or 0xFFFFFF
                    nc.drawLine(x0 + (x - x0) * b0, y0 + (y - y0) * b0, x0 + (x - x0) * b1, y0 + (y - y0) * b1, paints.hint)
                }
            }
            paints.dot.color = (((0xF2 * fa).toInt()) shl 24) or 0xFFFFFF
            paints.dot.setShadowLayer(dot * 0.5f, 0f, dot * 0.12f, (((0x55 * fa).toInt()) shl 24))
            nc.drawCircle(x, y, dot, paints.dot)
        }
    }

    /**
     * The phone in stage frame [v] (inside card [r]) at [alpha]: the band it looks at fitted into the frame (the whole phone
     * in its frame; a band of it larger, fading out at the card's edges where the phone goes past them).
     */
    fun phone(nc: android.graphics.Canvas, r: Rect, v: Rect, alpha: Float, part: Part) {
        val pw = scene.w
        val ph = scene.h
        if (pw <= 0f || v.width <= 1f || v.height <= 1f) return
        val c = crop.value
        // A long way from one band to another pulls back a little on the way, as a camera would.
        val from = cropFrom[0]
        val target = focusTarget.value
        val travel = abs((target.top + target.bottom) - (from.top + from.bottom)) / 2f
        val along = if (travel < 0.02f) 1f else (abs((c.top + c.bottom) / 2f - (from.top + from.bottom) / 2f) / travel).coerceIn(0f, 1f)
        val dip = sin(PI * along).toFloat() * (travel / 0.6f).coerceIn(0f, 1f)
        val ch = c.height.coerceAtLeast(0.01f)
        val sWhole = minOf(v.width / pw, v.height / ph)
        val sFit = minOf(v.width / pw, v.height / (ch * ph))
        val s = sWhole + (sFit - sWhole) * (1f - 0.35f * dip)
        val bandH = ch * ph * s
        val anchor = if (ch >= 0.999f) 0.5f else (c.top / (1f - ch)).coerceIn(0f, 1f)
        val bandY = v.top + (v.height - bandH) * anchor
        val ox = v.left + (v.width - pw * s) / 2f
        val oy = bandY - c.top * ph * s
        val f = paints.frame.apply { set(ox, oy, ox + pw * s, oy + ph * s) }
        val rad = scene.corner * s
        // Past the card's top or bottom it fades out there (a layer the size of the card: the cuts take it away).
        val fadeLen = minOf(r.height * 0.24f, 36f * look.unit)
        val needTop = ((r.top + stageInset - f.top) / (fadeLen * 0.5f)).coerceIn(0f, 1f)
        val needBottom = ((f.bottom - (r.bottom - stageInset)) / (fadeLen * 0.5f)).coerceIn(0f, 1f)
        val layered = alpha < 0.997f || needTop > 0.001f || needBottom > 0.001f
        if (layered) nc.saveLayerAlpha(r.left, r.top, r.right, r.bottom, (255 * alpha).roundToInt().coerceIn(0, 255))
        // Narrower than the stage: in its frame (a soft shadow and the phone's edge), which goes as it fills the width.
        val frameA = ((v.width - pw * s) / (0.08f * v.width)).coerceIn(0f, 1f)
        if (frameA > 0.003f) {
            paints.shadow.color = ((((shadow ushr 24) * frameA).toInt() shl 24) or (shadow and 0xFFFFFF))
            if (paints.shadow.maskFilter == null) paints.shadow.maskFilter = android.graphics.BlurMaskFilter(14f * look.unit, android.graphics.BlurMaskFilter.Blur.OUTER)
            nc.save()
            nc.translate(0f, 6f * look.unit)
            nc.drawRoundRect(f, rad, rad, paints.shadow)
            nc.restore()
            paints.rim.color = rim
            paints.rim.alpha = ((rim ushr 24) * frameA).toInt()
            paints.rim.strokeWidth = rimW
            nc.drawRoundRect(f.left - rimW / 2f, f.top - rimW / 2f, f.right + rimW / 2f, f.bottom + rimW / 2f, rad + rimW / 2f, rad + rimW / 2f, paints.rim)
        }
        paints.clip.rewind()
        paints.clip.addRoundRect(f, rad, rad, android.graphics.Path.Direction.CW)
        nc.save()
        nc.clipPath(paints.clip)
        nc.translate(ox, oy)
        nc.scale(s, s)
        drawPhone(nc, part, s)
        nc.restore()
        if (layered) {
            if (needTop > 0.001f) {
                nc.save()
                nc.translate(0f, r.top)
                nc.scale(1f, fadeLen)
                paints.cut.shader = paints.fadeOut
                paints.cut.alpha = (255 * needTop).toInt()
                nc.drawRect(r.left, -0.01f, r.right, 1f, paints.cut)
                nc.restore()
            }
            if (needBottom > 0.001f) {
                nc.save()
                nc.translate(0f, r.bottom - fadeLen)
                nc.scale(1f, fadeLen)
                paints.cut.shader = paints.fadeIn
                paints.cut.alpha = (255 * needBottom).toInt()
                nc.drawRect(r.left, 0f, r.right, 1.01f, paints.cut)
                nc.restore()
            }
            nc.restore()
        }
    }
    Canvas(Modifier.fillMaxSize().graphicsLayer().semantics { contentDescription = "The part of the phone this page is about" }) {
        val part = st.open ?: return@Canvas
        // (Your changes is no part of the phone: [RowGrowth], under its page.)
        if (part == Part.CHANGES) return@Canvas
        val p = st.p.value
        if (p <= 0f) return@Canvas
        val tile = st.tiles[part] ?: return@Canvas
        val row = st.fromRow == part
        val pressed = st.press.getValue(part).value
        val sunk = 0.04f * pressed
        val from = Rect(tile.left + tile.width * sunk / 2f, tile.top + tile.height * sunk / 2f, tile.right - tile.width * sunk / 2f, tile.bottom - tile.height * sunk / 2f)
        val k = p.coerceIn(0f, 1.05f)
        val kk = k.coerceIn(0f, 1f)
        val c = partColor(look, part)
        val fill = if (row) groupFill else tileFill(look, part)
        val r = lerp(from, g.stage, k)
        val corner = (if (row) 0f else tileCorner) + (tileCorner - (if (row) 0f else tileCorner)) * kk
        drawRoundRect(fill, r.topLeft, r.size, CornerRadius(corner))
        if (pressed > 0.001f && !row) drawRoundRect(pressFill.copy(alpha = pressFill.alpha * pressed * (1f - kk)), r.topLeft, r.size, CornerRadius(corner))
        cardPath.reset()
        cardPath.addRoundRect(RoundRect(r, CornerRadius(corner)))
        clipPath(cardPath) {
            // The card's scene, name and line ride along with its top left, giving way as the stage comes.
            val content = (1f - p / 0.38f).coerceIn(0f, 1f)
            if (!row && content > 0f) {
                translate(r.left, r.top) { TileArt.draw(this, part, tile.width, tile.height, st.artClock.floatValue, art.value, c, content) }
                val start = TILE_TEXT_START_PT * look.unit
                val inner = androidx.compose.ui.unit.Constraints(maxWidth = (tile.width * (1f - share) - start - TILE_TEXT_END_PT * look.unit).roundToInt().coerceAtLeast(1))
                val titleStyle = look.text(SettingsTokens.TILE_TITLE, mix(label, c, titleTint))
                val titleAt = st.tileTitles[part]
                if (titleAt != null) {
                    val t = measurer.measure(part.title, titleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis, constraints = inner)
                    drawText(t, topLeft = Offset(r.left + (titleAt.left - tile.left), r.top + (titleAt.top - tile.top)), alpha = content)
                }
                val detailAt = st.tileDetails[part]
                if (detailAt != null) {
                    val d = measurer.measure(part.detail(draft, ctx), tileDetailStyle(look, part), maxLines = 2, overflow = TextOverflow.Ellipsis, constraints = inner)
                    drawText(d, topLeft = Offset(r.left + (detailAt.left - tile.left), r.top + (detailAt.top - tile.top)), alpha = content)
                }
            }
            // The phone, growing from where the card's scene was into the stage.
            val phoneA = ((p - 0.12f) / 0.5f).coerceIn(0f, 1f)
            if (phoneA > 0.003f && scene.metrics != null) {
                val fr = art.value.shape.frame(tile.width, tile.height)
                val v0 = if (row) innerOf(from, 0f) else Rect(tile.left + fr.left, tile.top + fr.top, tile.left + fr.right, tile.top + fr.bottom)
                val v = lerp(v0, innerOf(g.stage, stageInset), k)
                scene.reflow = reflow.value
                val dark = if (part == Part.LOOK) lookDark.value else Appearance.dark
                scene.colors(c.toArgb(), dark, if (part == Part.LOOK) paletteInts.of(lookIcons.value) else null)
                scene.face = faces.first
                // Look's Fonts page shows type, not a phone: the two cross over as the page comes and goes.
                val sp = if (part == Part.LOOK) specimen.value.coerceIn(0f, 1f) else 0f
                if (sp < 0.997f) drawIntoCanvas { cv -> phone(cv.nativeCanvas, r, v, phoneA * (1f - sp), part) }
                if (sp > 0.003f) drawIntoCanvas { cv -> typeSpecimen(cv.nativeCanvas, innerOf(g.stage, stageInset), phoneA * sp) }
            }
        }
    }
}

/**
 * Your changes (no part of the phone) growing out of its row into the whole sheet, its fill giving way to the sheet's: under
 * its page, whose rows rise in over it.
 */
@Composable
internal fun RowGrowth(st: Studio) {
    val look = LocalLook.current
    val fill = look.color(SettingsTokens.GROUP_FILL)
    val sheetCorner = look.pt(SettingsTokens.SHEET_CORNER)
    Canvas(Modifier.fillMaxSize()) {
        if (st.open != Part.CHANGES) return@Canvas
        val p = st.p.value
        if (p <= 0f) return@Canvas
        val from = st.tiles[Part.CHANGES] ?: return@Canvas
        val k = p.coerceIn(0f, 1.05f)
        val r = lerp(from, Rect(0f, 0f, size.width, size.height), k)
        val colour = (1f - (p - 0.25f) / 0.6f).coerceIn(0f, 1f)
        drawRoundRect(fill.copy(alpha = fill.alpha * colour), r.topLeft, r.size, CornerRadius(sheetCorner * k.coerceIn(0f, 1f)))
    }
}

/** Font [id]'s face at [weight] (a Google family's regular face once it is here, else the theme's text face). */
internal fun faceFor(id: String, weight: Int): android.graphics.Typeface =
    if (!id.startsWith("google:")) dev.launcher.app.theme.Fonts.of(id, weight, 15f)
    else dev.launcher.app.theme.FontPreviews.ready(id.removePrefix("google:")) ?: dev.launcher.app.theme.Fonts.text(weight)

/** The wallpaper's place on the phone, made again only when the wallpaper or the phone's size is another. */
private class WallMatrix {
    private var id = -1
    private var w = 0
    private var h = 0
    private var m = android.graphics.Matrix()
    fun of(wp: dev.launcher.app.Wallpaper, w: Int, h: Int): android.graphics.Matrix {
        if (wp.id != id || w != this.w || h != this.h) { id = wp.id; this.w = w; this.h = h; m = wp.matrix(w, h) }
        return m
    }
}

/** The faces of the fonts the draft would apply: the text's, the titles' ([textId], [displayId] their families). */
private class TypeSet(val text: android.graphics.Typeface, val title: android.graphics.Typeface, val textId: String, val displayId: String)

/** One layer of the type specimen: its faces at its [alpha]. */
private class TypeLayer(val faces: TypeSet, val alpha: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>)

/** Look's palette as colour ints, made again only when the palette is another (never in each frame). */
private class PaletteInts {
    private var from: List<Color>? = null
    private var ints = IntArray(0)

    fun of(p: List<Color>): IntArray {
        if (p !== from) { from = p; ints = IntArray(p.size) { p[it].toArgb() } }
        return ints
    }
}

/** The stage's paints and shapes (made once). */
private class StagePaints {
    val shadow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val rim = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE }
    val cut = android.graphics.Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT) }
    val fadeOut = android.graphics.LinearGradient(0f, 0f, 0f, 1f, 0xFF000000.toInt(), 0x00000000, android.graphics.Shader.TileMode.CLAMP)
    val fadeIn = android.graphics.LinearGradient(0f, 0f, 0f, 1f, 0x00000000, 0xFF000000.toInt(), android.graphics.Shader.TileMode.CLAMP)
    val hint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND }
    val dot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val kitPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val kitImage = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    val kitPath = android.graphics.Path()
    val clip = android.graphics.Path()
    val frame = android.graphics.RectF()
    val check = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
    }
    val checkPath = android.graphics.Path()
    private val shieldPath = android.graphics.Path()
    private var shieldFor = -1f

    /** A shield [sz] wide (its check in [checkPath]), from its top left; made again only when the size changes. */
    fun shield(sz: Float): android.graphics.Path {
        if (sz == shieldFor) return shieldPath
        shieldFor = sz
        val sh = sz * 1.2f
        val cx = sz / 2f
        val p = shieldPath
        p.reset()
        p.moveTo(cx, 0f)
        p.cubicTo(cx + sz * 0.18f, sh * 0.08f, cx + sz * 0.38f, sh * 0.1f, sz, sh * 0.1f)
        p.lineTo(sz, sh * 0.46f)
        p.cubicTo(sz, sh * 0.78f, cx + sz * 0.2f, sh * 0.93f, cx, sh)
        p.cubicTo(cx - sz * 0.2f, sh * 0.93f, 0f, sh * 0.78f, 0f, sh * 0.46f)
        p.lineTo(0f, sh * 0.1f)
        p.cubicTo(cx - sz * 0.38f, sh * 0.1f, cx - sz * 0.18f, sh * 0.08f, cx, 0f)
        p.close()
        val cy = sh * 0.5f
        checkPath.reset()
        checkPath.moveTo(cx - sz * 0.2f, cy)
        checkPath.lineTo(cx - sz * 0.05f, cy + sz * 0.15f)
        checkPath.lineTo(cx + sz * 0.22f, cy - sz * 0.15f)
        return p
    }
}

/** The finger's least size on the stage (pt), however small the phone is drawn. */
private const val FINGER_PT = 6f
/** Home's act: how long its icons wiggle (s). */
private const val HOME_WIGGLE_S = 2.2f

/** Whether the settings are in front (resumed), as it changes. */
@Composable
internal fun rememberResumed(): State<Boolean> {
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
    return resumed
}

private fun lerp(a: Rect, b: Rect, t: Float) = Rect(
    a.left + (b.left - a.left) * t, a.top + (b.top - a.top) * t, a.right + (b.right - a.right) * t, a.bottom + (b.bottom - a.bottom) * t,
)
