package dev.launcher.app.settings

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.launcher.app.design.Curve
import dev.launcher.app.design.CurveKey
import dev.launcher.app.design.Design
import dev.launcher.app.design.Provenance
import dev.launcher.app.design.Value
import dev.launcher.app.motion.CurveFit
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.MotionAdjust
import dev.launcher.app.motion.Moment
import dev.launcher.app.motion.Moments
import dev.launcher.app.motion.MotionRoles
import dev.launcher.app.motion.MotionRole
import dev.launcher.app.motion.MotionTokens
import dev.launcher.app.motion.mover
import dev.launcher.app.theme.Appearance
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// The settings' Motion (docs/PLAN_MOTION.md): the presets and their fine-tuning (C4a), and every animation by group, each
// with its editor and its sample on the phone above (C4b; V5: the phone drawn on the stage).

/**
 * A moment's page (docs/PLAN_SETTINGS_V2.md, V2), pushed over Motion or over the page of the part it belongs to; the picture
 * above plays the moment. Its own Speed and Bounce on top of the preset's ("As the preset" in the middle), its character
 * where it has one ("How far home steps back"), then Every detail: its animations in the order they play, each with its
 * editor, folded away until asked for. Everything waits for Apply.
 */
@Composable
internal fun MomentPageContent(st: Studio, m: Moment) {
    val draft = LocalDraft.current
    val look = LocalLook.current
    val id = draft.motionId()
    val v = look.version
    // Derived: every step of a slider changes the draft; only what it changed composes again.
    val speed by remember(draft, id, v, m) { androidx.compose.runtime.derivedStateOf { draft.motionNumber(id, m.speed) } }
    val bounce by remember(draft, id, v, m) { androidx.compose.runtime.derivedStateOf { draft.motionNumber(id, m.bounce) } }
    Group(null, m.description + " The phone above plays it.") {
        FeelSlider("Speed", momentSpeedWords(speed), speedToSlider(speed), "Relaxed", "Snappy", changed = abs(speed - 1f) > 0.001f) { t ->
            draft.chooseMotionNumber(id, m.speed, sliderToSpeed(t))
        }
        FeelSlider("Bounce", momentBounceWords(bounce), bounce / MotionAdjust.MOMENT_BOUNCE_MAX, "Still", "Lively", changed = abs(bounce - 1f) > 0.001f) { t ->
            draft.chooseMotionNumber(id, m.bounce, Draft.snap(t * MotionAdjust.MOMENT_BOUNCE_MAX))
        }
        m.character?.let { c -> CharacterSlider(draft, id, c) }
    }
    if (m.style != null) StyleGroup(m, draft)
    EveryDetail(st, m)
    val changes by remember(draft, id, v, m) { androidx.compose.runtime.derivedStateOf { draft.momentChanges(id, m) } }
    // (It keeps its last count while it folds away.)
    val last = remember { intArrayOf(1) }
    if (changes > 0) last[0] = changes
    Reveal(changes > 0) {
        Column {
            Group(null) {
                ActionRow("Back to ${presetName(id)}'s", "${last[0]} change" + (if (last[0] == 1) "" else "s") + " to ${m.title.lowercase()}") {
                    draft.resetMoment(id, m)
                }
            }
        }
    }
}

/** A moment's speed in words, the number beside them. */
private fun momentSpeedWords(s: Float): String = when {
    abs(s - 1f) < 0.03f -> "As the preset"
    s > 1f -> "Quicker · ${(s * 100).roundToInt()} %"
    else -> "Slower · ${(s * 100).roundToInt()} %"
}

/** A moment's bounce in words, the number beside them. */
private fun momentBounceWords(b: Float): String = when {
    b < 0.005f -> "None"
    abs(b - 1f) < 0.03f -> "As the preset"
    b > 1f -> "Livelier · ${(b * 100).roundToInt()} %"
    else -> "Calmer · ${(b * 100).roundToInt()} %"
}

/**
 * A value set on a slider whose ends say what they mean ([low], [high]): its name and its value in words above (rolling as it
 * changes), the slider, the ends' words under it.
 */
@Composable
private fun FeelSlider(title: String, value: String, position: Float, low: String, high: String, changed: Boolean = false, onChange: (Float) -> Unit) {
    val look = LocalLook.current
    val counter = LocalRowCounter.current
    val first = remember { counter == null || counter.n++ == 0 }
    val pad = look.dp(SettingsTokens.ROW_PADDING)
    val separator = look.color(SettingsTokens.ROW_SEPARATOR)
    val ends = look.text(SettingsTokens.ROW_DETAIL, look.color(SettingsTokens.ROW_DETAIL_COLOR))
    // A dot before its name while it differs from the preset's (V3).
    val dot by animateFloatAsState(if (changed) 1f else 0f, look.motion(if (changed) MotionTokens.APPEAR else MotionTokens.DISAPPEAR_FADE), label = "dot")
    val accent = look.color(SettingsTokens.ACCENT)
    Column(Modifier.fillMaxWidth()
        .drawBehind {
            if (!first) drawRect(separator, Offset(pad.toPx(), 0f), Size(size.width - pad.toPx(), 1f))
            if (dot > 0.003f) drawCircle(accent.copy(alpha = accent.alpha * dot.coerceIn(0f, 1f)), 3.5f * look.unit * dot, Offset(pad.toPx() / 2f, 21f * look.unit))
        }
        .anchor(title)
        .padding(horizontal = pad, vertical = look.dp(10f * look.unit))) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText(title, style = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_LABEL)), modifier = Modifier.weight(1f))
            Rolling(value, look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_DETAIL_COLOR)), Modifier)
        }
        Spacer(Modifier.height(look.dp(4f * look.unit)))
        SettingsSlider(position, onChange = onChange)
        Row(Modifier.fillMaxWidth()) {
            BasicText(low, style = ends, maxLines = 1)
            Spacer(Modifier.weight(1f))
            BasicText(high, style = ends, maxLines = 1)
        }
    }
}

/**
 * A moment's character: its numbers moved together (each away from where the moment shows none of it, by the slider's value:
 * 0 none, 1 the preset's own, 2 twice as much).
 */
@Composable
private fun CharacterSlider(draft: Draft, id: String, c: dev.launcher.app.motion.Character) {
    val v = LocalLook.current.version
    val k by remember(draft, id, v, c) { androidx.compose.runtime.derivedStateOf { characterOf(draft, id, c) } }
    FeelSlider(c.title, when {
        k < 0.005f -> "None"
        abs(k - 1f) < 0.03f -> "As the preset"
        k > 1f -> "More · ${(k * 100).roundToInt()} %"
        else -> "Less · ${(k * 100).roundToInt()} %"
    }, k / 2f, c.low, c.high, changed = abs(k - 1f) > 0.003f) { t -> setCharacter(draft, id, c, Draft.snap(t * 2f)) }
}

/** How far a character's numbers are from none, of how far the preset puts them (1: as it comes). */
private fun characterOf(d: Draft, id: String, c: dev.launcher.app.motion.Character): Float {
    for ((key, neutral) in c.keys) {
        val own = (d.motionOwn(id, key) as? Value.Number)?.v ?: continue
        if (abs(own - neutral) < 1e-4f) continue
        val now = (d.motionRaw(id, key) as? Value.Number)?.v ?: own
        return ((now - neutral) / (own - neutral)).coerceIn(0f, 2f)
    }
    return 1f
}

private fun setCharacter(d: Draft, id: String, c: dev.launcher.app.motion.Character, k: Float) {
    for ((key, neutral) in c.keys) {
        val own = d.motionOwn(id, key) as? Value.Number ?: continue
        val spec = NUMBERS[key]
        var x = neutral + (own.v - neutral) * k
        if (spec != null) x = snapTo(x, spec.step).coerceIn(spec.min, spec.max)
        d.chooseMotionValue(id, key, Value.Number(x, own.unit))
    }
}

/**
 * Every detail: a moment's animations in the order they play (curves, then the numbers that shape them), each with its editor
 * in place (the picture plays the one open), folded away under one row until asked for; built when first opened.
 */
@Composable
private fun EveryDetail(st: Studio, m: Moment) {
    val look = LocalLook.current
    var built by remember { mutableStateOf(false) }
    // Open while the settings say so (a tap here, or the search); built when first asked for and opened two frames later
    // (built in its opening's first frame, it cost that frame).
    var ready by remember { mutableStateOf(false) }
    val wanted by remember(st, m) { androidx.compose.runtime.derivedStateOf { st.detailFor == m.id } }
    val open = wanted && ready
    // (The search waits for it to be open on screen before it scrolls to one of its rows.)
    LaunchedEffect(open) { if (open) st.detailShown = m.id else if (st.detailShown == m.id) st.detailShown = null }
    LaunchedEffect(wanted) {
        if (wanted && !ready) {
            built = true
            androidx.compose.runtime.withFrameNanos { }
            androidx.compose.runtime.withFrameNanos { }
            ready = true
        }
    }
    val turn by animateFloatAsState(if (open) 1f else 0f, look.motion(MotionTokens.APPEAR), label = "detail")
    val roles = remember(m) { m.roles.mapNotNull { k -> MotionRoles.find(k)?.second } }
    val curves = remember(roles) { roles.filter { it.key in MotionTokens.CURVES } }
    val numbers = remember(roles) { roles.filter { it.key in MotionTokens.NUMBERS } }
    Group(null) {
        BaseRow("Every detail", "${roles.size} animation" + (if (roles.size == 1) "" else "s") + ", in the order they play", {
            if (wanted) { st.detailFor = null; st.motionRole = null }
            else st.detailFor = m.id
        }, held = wanted && !ready) {
            BasicText("›", style = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_DETAIL_COLOR)),
                modifier = Modifier.graphicsLayer { rotationZ = 90f * turn })
        }
        if (built) {
            val kit = rememberRowKit()
            Reveal(open) {
                Column {
                    for (r in curves) androidx.compose.runtime.key(r.key) { RoleRow(st, r, kit) }
                    for (r in numbers) androidx.compose.runtime.key(r.key) { RoleRow(st, r, kit) }
                }
            }
        }
    }
}

/**
 * A moment as a row (on Motion and on its part's page): its name, its feel in words as it would be applied ("Quick, a little
 * bounce": its sample's curve at the preset's and its own speed and bounce), the accent and a count once you changed it.
 * Tapped, it stays lit while its page is built.
 */
@Composable
internal fun MomentRow(m: Moment, host: Part, push: (Pushed) -> Unit, lit: () -> Pushed?) {
    val look = LocalLook.current
    val draft = LocalDraft.current
    val v = look.version
    val page = remember(m, host) { MomentPage(m, host) }
    val feel by remember(draft, m, v) { androidx.compose.runtime.derivedStateOf { feelOf(draft, m) } }
    val changes by remember(draft, m, v) { androidx.compose.runtime.derivedStateOf { draft.momentChanges(draft.motionId(), m) } }
    val held by remember(page) { androidx.compose.runtime.derivedStateOf { lit() == page } }
    val onClick = remember(page, push) { { push(page) } }
    // (Its count keeps its last number while its line closes.)
    val last = remember { intArrayOf(1) }
    if (changes > 0) last[0] = changes
    BaseRow(m.title, null, onClick, held = held, changed = changes > 0, below = {
        val detail = look.text(SettingsTokens.ROW_DETAIL, look.color(SettingsTokens.ROW_DETAIL_COLOR))
        Rolling(feel, detail, Modifier.fillMaxWidth())
        Reveal(changes > 0) {
            Rolling("${last[0]} changed", look.text(SettingsTokens.ROW_DETAIL, look.color(SettingsTokens.ACCENT)), Modifier)
        }
    }) {
        BasicText("›", style = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_DETAIL_COLOR)))
    }
}

/** How a moment feels as it would be applied, in words: its sample's pace and how far it goes past its place. */
internal fun feelOf(d: Draft, m: Moment): String {
    val key = m.sample.takeIf { it in MotionTokens.CURVES } ?: m.roles.firstOrNull { it in MotionTokens.CURVES } ?: return ""
    val c = d.motionCurve(d.motionId(), CurveKey(key))
    val t = CurveFit.settleTime(c)
    val past = when (c) {
        is Curve.Spring -> MotionAdjust.overshoot(c.damping).toFloat()
        is Curve.Ease -> ((0..40).maxOf { CurveFit.at(c, c.ms / 1000f * it / 40f) } - 1f).coerceAtLeast(0f)
    }
    val pace = when {
        t < 0.3f -> "Snappy"
        t < 0.45f -> "Quick"
        t < 0.65f -> "Smooth"
        t < 0.9f -> "Relaxed"
        else -> "Slow"
    }
    val spring = when {
        past < 0.005f -> "no bounce"
        past < 0.04f -> "a touch of bounce"
        past < 0.12f -> "a little bounce"
        past < 0.25f -> "bouncy"
        else -> "very bouncy"
    }
    return "$pace, $spring"
}

/** How far in a pushed page may be and still count as gone (its alpha under a 255th). */
internal const val PAGE_GONE = 0.002f

/**
 * The most time one frame moves Motion's loops (the cards' dots, the picture's demo and samples; s): a stalled frame (a
 * page or an editor being built) pauses them instead of making them jump ahead.
 */
internal const val LOOP_STEP_MAX = 1f / 30f

/**
 * What every animation's row on a group's page draws and moves with, made once per page (and design), not per row: sizes,
 * colours, the paints its words are drawn with, the motion of a press, of taking the accent and of new words, and the
 * page's coroutine scope the rows' animations run on.
 */
private class RowKit(
    val unit: Float, val pad: Float, val rowH: Float, val glyph: Float, val title: LinePaint, val value: LinePaint,
    val label: Color, val detail: Color, val accent: Color, val chipFill: Color, val separator: Color, val pressFill: Color,
    val pressIn: androidx.compose.animation.core.AnimationSpec<Float>, val pressOut: androidx.compose.animation.core.AnimationSpec<Float>,
    val inkSpec: androidx.compose.animation.core.AnimationSpec<Float>, val rollSpec: androidx.compose.animation.core.AnimationSpec<Float>,
    val scope: kotlinx.coroutines.CoroutineScope,
)

@Composable
private fun rememberRowKit(): RowKit {
    val look = LocalLook.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val resolver = androidx.compose.ui.platform.LocalFontFamilyResolver.current
    val scope = rememberCoroutineScope()
    return remember(look.version, look, density, resolver, scope) {
        val u = look.unit
        val label = look.color(SettingsTokens.ROW_LABEL)
        val detail = look.color(SettingsTokens.ROW_DETAIL_COLOR)
        val title = LinePaints.of(look.text(SettingsTokens.ROW_TITLE, label), density, resolver)
        val value = LinePaints.of(look.text(SettingsTokens.ROW_DETAIL, detail), density, resolver)
        val glyph = 28f * u
        val rowH = maxOf(look.pt(SettingsTokens.ROW_HEIGHT), glyph + 16f * u, title.line.first + 16f * u, value.line.first + 16f * u)
        RowKit(u, look.pt(SettingsTokens.ROW_PADDING), rowH, glyph, title, value, label, detail, look.color(SettingsTokens.ACCENT),
            look.color(SettingsTokens.CHIP_FILL), look.color(SettingsTokens.ROW_SEPARATOR), Color(Appearance.pressFill),
            look.motion(MotionTokens.PRESS_IN), look.motion(MotionTokens.PRESS_OUT), look.motion(MotionTokens.CC_TOGGLE),
            look.motion(MotionTokens.APPEAR), scope)
    }
}

/** A row's own moving parts, each made when it first moves (most rows are never pressed, changed or given new words). */
@Stable
private class RowMotion(changed: Boolean, words: String) {
    var press by mutableStateOf<Animatable<Float, androidx.compose.animation.core.AnimationVector1D>?>(null)
    var ink by mutableStateOf<Animatable<Float, androidx.compose.animation.core.AnimationVector1D>?>(null)
    var roll by mutableStateOf<Animatable<Float, androidx.compose.animation.core.AnimationVector1D>?>(null)
    var now by mutableStateOf(words)
    var was by mutableStateOf<String?>(null)
    // Plain: read only where the states above already make the row draw again.
    var inkTo = changed
    var lastOpen = false
    var building = false
    var unwarm: kotlinx.coroutines.Job? = null
    var titleFor = Float.NaN
    var titleShown = ""
}

/**
 * One animation: its curve drawn small, its name, its value in words (in the accent once changed); tapped, its editor opens
 * in place under it (one at a time) and the picture plays it. Drawn whole by one node in a layer of its own (a dozen small
 * composables a row took a group's page of 24 rows ~80 ms to build on the S24's debug build); its words rolling, its
 * accent and its press are animations made when first needed.
 */
@Composable
private fun RoleRow(st: Studio, role: MotionRole, kit: RowKit) {
    val draft = LocalDraft.current
    val key = role.key
    // Read through derived states: every step of a slider changes the draft, and only the row whose value it changed should
    // compose again (every row of the page did, each step).
    val v = LocalLook.current.version
    val raw = remember(draft, key, v) { androidx.compose.runtime.derivedStateOf { draft.motionRaw(draft.motionId(), key) } }
    val words by remember(raw) { androidx.compose.runtime.derivedStateOf { valueText(key, raw.value) } }
    val changed by remember(draft, key, v) { androidx.compose.runtime.derivedStateOf { draft.motionChanged(draft.motionId(), key) } }
    val glyph = remember(draft, key, v) {
        androidx.compose.runtime.derivedStateOf { if (key in MotionTokens.CURVES) draft.motionCurve(draft.motionId(), CurveKey(key)) else null }
    }
    val open by remember(st, key) { androidx.compose.runtime.derivedStateOf { st.motionRole == key } }
    val counter = LocalRowCounter.current
    val first = remember { counter == null || counter.n++ == 0 }
    val m = remember { RowMotion(changed, words) }
    // Its editor is built when tapped, in the frame before it opens (built in its opening's first frame, it cost that frame
    // and the opening a jump); the row stays lit meanwhile. Kept a little after it closes, for a quick change of mind.
    var warm by remember { mutableStateOf(false) }
    var everOpened by remember { mutableStateOf(false) }
    val pressTo: (Boolean) -> Unit = remember(m, kit) {
        { down ->
            val a = m.press ?: Animatable(0f).also { m.press = it }
            kit.scope.launch { a.animateTo(if (down) 1f else 0f, if (down) kit.pressIn else kit.pressOut) }
        }
    }
    val toggle: () -> Unit = remember(st, key, m, kit) {
        {
            if (st.motionRole == key) st.motionRole = null
            else if (warm) st.motionRole = key
            else if (!m.building) {
                everOpened = true
                warm = true
                m.building = true
                pressTo(true)
                kit.scope.launch {
                    try {
                        androidx.compose.runtime.withFrameNanos { }
                        androidx.compose.runtime.withFrameNanos { }
                        st.motionRole = key
                    } finally { m.building = false; pressTo(false) }
                }
            }
        }
    }
    // What changed since the last composition starts moving: the accent, new words (at once under a dragged finger), and
    // the editor let go of a while after it closed. (Read here, in the body: read only inside the effect, a change was not
    // seen and the row kept its old words.)
    val ch = changed
    val w = words
    val isOpen = open
    androidx.compose.runtime.SideEffect {
        if (ch != m.inkTo) {
            m.inkTo = ch
            val a = m.ink ?: Animatable(if (ch) 0f else 1f).also { m.ink = it }
            kit.scope.launch { a.animateTo(if (ch) 1f else 0f, kit.inkSpec) }
        }
        if (w != m.now) {
            if (st.motionLive) {
                m.was = null
                m.now = w
                m.roll?.let { r -> kit.scope.launch { r.snapTo(1f) } }
            } else {
                m.was = m.now
                m.now = w
                val r = m.roll ?: Animatable(1f).also { m.roll = it }
                kit.scope.launch {
                    r.snapTo(0f)
                    r.animateTo(1f, kit.rollSpec)
                    m.was = null
                }
            }
        }
        if (isOpen != m.lastOpen) {
            m.lastOpen = isOpen
            m.unwarm?.cancel()
            if (!isOpen) m.unwarm = kit.scope.launch { kotlinx.coroutines.delay(1500); if (st.motionRole != key && !m.building) warm = false }
        }
    }
    val rowH = with(androidx.compose.ui.platform.LocalDensity.current) { kit.rowH.toDp() }
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(rowH)
            .anchor(roleAnchor(role.key))
            .semantics(mergeDescendants = true) {
                contentDescription = role.title + ", " + m.now
                onClick { toggle(); true }
            }
            .pointerInput(key, kit) { rowTap({ d -> if (!m.building) pressTo(d) }, toggle) }
            .graphicsLayer()
            .drawWithCache {
                val u = kit.unit
                val side = kit.glyph
                val gx = kit.pad
                val gy = (size.height - side) / 2f
                val ins = side * 0.2f
                val gw = side - ins * 2f
                val gh = side - ins * 2f
                val c = glyph.value
                val path = androidx.compose.ui.graphics.Path()
                if (c != null) {
                    val d = CurveFit.settleTime(c, 0.01f).coerceAtLeast(0.05f)
                    for (i in 0..24) {
                        val y = CurveFit.at(c, d * i / 24f).coerceIn(-0.2f, 1.35f)
                        val x = gx + ins + gw * i / 24f
                        val yy = gy + ins + gh - y * gh * 0.74f
                        if (i == 0) path.moveTo(x, yy) else path.lineTo(x, yy)
                    }
                }
                val stroke = Stroke(1.6f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                val rise = 14f * u
                onDrawBehind {
                    val press = m.press?.value ?: 0f
                    if (press > 0.001f) drawRect(kit.pressFill.copy(alpha = kit.pressFill.alpha * press))
                    if (!first) drawRect(kit.separator, Offset(kit.pad, 0f), Size(size.width - kit.pad, 1f))
                    val ink = mix(kit.detail, kit.accent, (m.ink?.value ?: if (m.inkTo) 1f else 0f).coerceIn(0f, 1f))
                    // The curve drawn small on its tile (a number: three rising bars).
                    drawRoundRect(kit.chipFill, Offset(gx, gy), Size(side, side), CornerRadius(side * 0.26f))
                    if (c != null) drawPath(path, ink, style = stroke)
                    else for (i in 0..2) {
                        val bw = gw / 5f
                        val bh = gh * (0.35f + 0.3f * i)
                        drawRoundRect(ink, Offset(gx + ins + i * bw * 2f, gy + ins + gh - bh), Size(bw, bh), CornerRadius(bw / 2f))
                    }
                    drawIntoCanvas { cv ->
                        val nc = cv.nativeCanvas
                        // The value, against the right edge, rolling to new words.
                        val vp = kit.value.paint
                        val right = size.width - kit.pad
                        val vBase = (size.height - kit.value.line.first) / 2f + kit.value.line.second
                        val k = m.roll?.value?.coerceIn(0f, 1f) ?: 1f
                        val old = m.was
                        val now = m.now
                        val wNow = vp.measureText(now)
                        val wOld = if (old != null) vp.measureText(old) else 0f
                        fun words(t: String, w: Float, a: Float, dy: Float) {
                            if (a <= 0.003f) return
                            vp.color = ink.copy(alpha = ink.alpha * a).toArgb()
                            nc.drawText(t, right - w, vBase + dy, vp)
                        }
                        if (old != null) {
                            words(old, wOld, 1f - k, -rise * k)
                            words(now, wNow, k, rise * (1f - k))
                        } else words(now, wNow, 1f, 0f)
                        // The name, in what the value leaves (cut with an ellipsis if it must be).
                        val tp = kit.title.paint
                        val left = gx + side + 12f * u
                        val avail = right - maxOf(wNow, wOld) - 8f * u - left
                        if (avail != m.titleFor) {
                            m.titleFor = avail
                            m.titleShown = android.text.TextUtils.ellipsize(role.title, tp, avail.coerceAtLeast(0f), android.text.TextUtils.TruncateAt.END).toString()
                        }
                        tp.color = kit.label.toArgb()
                        nc.drawText(m.titleShown, left, (size.height - kit.title.line.first) / 2f + kit.title.line.second, tp)
                    }
                }
            })
        if (everOpened) OpenReveal(isOpen, warm) { RoleEditor(st, role) }
    }
}

/**
 * [content] opening in place (its height and alpha growing from nothing on the appear role) and closing the same way; only
 * composed while it shows or is [warm] (built ahead, at no height: an editor composed under every row was too much to build).
 */
@Composable
private fun OpenReveal(visible: Boolean, warm: Boolean, content: @Composable () -> Unit) {
    val look = LocalLook.current
    val k = animateFloatAsState(if (visible) 1f else 0f, look.motion(MotionTokens.APPEAR), label = "open")
    val present by remember { androidx.compose.runtime.derivedStateOf { k.value > 0.001f } }
    if (!visible && !present && !warm) return
    Box(Modifier.fillMaxWidth().clipToBounds()
        .layout { mm, c ->
            val p = mm.measure(c)
            layout(p.width, (p.height * k.value.coerceIn(0f, 1f)).roundToInt()) { p.place(0, 0) }
        }
        .graphicsLayer { alpha = k.value.coerceIn(0f, 1f) }) { content() }
}

/**
 * An animation's value in words first (docs/PLAN_SETTINGS_V2.md: "Quick, bouncy"), the number beside them (a spring's
 * duration, a curve's length); a number in its unit.
 */
internal fun valueWords(key: String, v: Value?): String = valueText(key, v)

private fun valueText(key: String, v: Value?): String = when (v) {
    is Value.CurveV -> {
        val c = v.curve
        val t = CurveFit.settleTime(c)
        val pace = when {
            t < 0.3f -> "Snappy"
            t < 0.45f -> "Quick"
            t < 0.65f -> "Smooth"
            t < 0.9f -> "Relaxed"
            else -> "Slow"
        }
        val past = when (c) {
            is Curve.Spring -> MotionAdjust.overshoot(c.damping).toFloat()
            is Curve.Ease -> ((0..40).maxOf { CurveFit.at(c, c.ms / 1000f * it / 40f) } - 1f).coerceAtLeast(0f)
        }
        val bounce = when { past < 0.04f -> null; past < 0.15f -> "bouncy"; else -> "very bouncy" }
        (if (bounce == null) pace else "$pace, $bounce") + " · " + when (c) { is Curve.Spring -> seconds(c.response); is Curve.Ease -> millis(c.ms) }
    }
    is Value.Number -> NUMBERS[key]?.text?.invoke(v.v) ?: fmt("%.2f", v.v)
    else -> ""
}

/**
 * [v] as [f] says ("%.2f s": two decimals, then " s"), without `String.format` (slow where code runs interpreted, as in the
 * debug build: every step of a slider formatted its values).
 */
private fun fmt(f: String, v: Float): String {
    if (f.length < 4 || f[0] != '%' || f[1] != '.' || f[3] != 'f' || f[2] !in '0'..'9') return String.format(java.util.Locale.ROOT, f, v)
    val digits = f[2] - '0'
    var p = 1L
    repeat(digits) { p *= 10 }
    val n = kotlin.math.round(abs(v.toDouble()) * p).toLong()
    val sign = if (v < 0f && n != 0L) "-" else ""
    val whole = n / p
    return (if (digits == 0) "$sign$whole" else "$sign$whole." + (n % p).toString().padStart(digits, '0')) + f.substring(4)
}
private fun seconds(s: Float) = if (s < 1f) fmt("%.2f s", s) else fmt("%.1f s", s)
private fun percent(b: Float) = "${(b * 100).roundToInt()} %"
private fun millis(ms: Float) = if (ms < 1000f) "${ms.roundToInt()} ms" else seconds(ms / 1000f)

/** A duration's place on its slider (0..1) between [min] and [max], evenly by ratio (as fine at 0.1 s as at 1 s), and back. */
private fun logPos(v: Float, min: Float, max: Float) = (kotlin.math.ln(v / min) / kotlin.math.ln(max / min)).coerceIn(0f, 1f)
private fun logVal(t: Float, min: Float, max: Float) = min * Math.pow((max / min).toDouble(), t.toDouble()).toFloat()

/** The durations a spring (its response, s) and a curve (ms) take on their sliders. */
private const val SPRING_MIN = 0.05f
private const val SPRING_MAX = 2f
private const val EASE_MIN = 30f
private const val EASE_MAX = 4000f

/** A number's range on its slider, its step, and how it reads. */
private class NumberSpec(val min: Float, val max: Float, val step: Float, val text: (Float) -> String)

private val NUMBERS: Map<String, NumberSpec> = mapOf(
    "motion.home.content-zoom" to NumberSpec(1f, 1.3f, 0.01f) { fmt("%.2f×", it) },
    "motion.home.wallpaper-zoom" to NumberSpec(1f, 1.2f, 0.01f) { fmt("%.2f×", it) },
    "motion.home.depth-blur" to NumberSpec(0f, 40f, 1f) { "${it.roundToInt()} pt" },
    "motion.switcher.clear-stagger" to NumberSpec(0f, 150f, 5f) { "${it.roundToInt()} ms" },
    "motion.switcher.clear-home" to NumberSpec(0f, 600f, 10f) { "${it.roundToInt()} ms" },
    "motion.home.page-fling" to NumberSpec(100f, 1500f, 10f) { "${it.roundToInt()} dp/s" },
    "motion.arrival.zoom" to NumberSpec(1f, 1.5f, 0.01f) { fmt("%.2f×", it) },
    "motion.arrival.wallpaper-zoom" to NumberSpec(1f, 1.3f, 0.01f) { fmt("%.2f×", it) },
    "motion.icon.press-dim" to NumberSpec(0f, 0.8f, 0.02f) { "${(it * 100).roundToInt()} %" },
    "motion.edit.jiggle" to NumberSpec(0f, 6f, 0.1f) { fmt("%.1f°", it) },
    "motion.edit.jiggle-period" to NumberSpec(0.1f, 0.8f, 0.01f) { fmt("%.2f s", it) },
    "motion.menu.blur" to NumberSpec(0f, 40f, 1f) { "${it.roundToInt()} pt" },
    "motion.widgets.row-stagger" to NumberSpec(0f, 120f, 2f) { "${it.roundToInt()} ms" },
    "motion.scroll.deceleration" to NumberSpec(0.99f, 0.999f, 0.0005f) { fmt("%.4f", it) },
    "motion.scroll.rubber-band" to NumberSpec(0.2f, 1f, 0.01f) { fmt("%.2f", it) },
)

private fun snapTo(v: Float, step: Float) = (kotlin.math.round(v / step) * step)

/** Where an animation's value comes from, in plain words (or that you changed it, waiting for Apply or not). */
private fun sourceLine(draft: Draft, preset: String, key: String): String = when {
    draft.motionPending(preset, key) -> if (draft.motionChanged(preset, key)) "Your change, waiting for Apply" else "Back to the preset's, waiting for Apply"
    draft.motionChanged(preset, key) -> "Your change"
    else -> when (Design.presetEntry(preset, key)?.src) {
        is Provenance.Measured -> "Measured on iOS 27"
        is Provenance.Kit -> "From Apple's design kit"
        else -> "As ${presetName(preset)} has it"
    }
}

/**
 * An animation's editor, under its row: a curve's (spring or bezier) or a number's. The value is handed down as state and
 * read where it shows (drawing, a few small composables): a slider's every step composed the whole editor again.
 */
@Composable
private fun RoleEditor(st: Studio, role: MotionRole) {
    val draft = LocalDraft.current
    val look = LocalLook.current
    val key = role.key
    val raw = remember(draft, key, look.version) { androidx.compose.runtime.derivedStateOf { draft.motionRaw(draft.motionId(), key) } }
    // Only its kind decides what is composed.
    val kind by remember(raw) { androidx.compose.runtime.derivedStateOf { when (raw.value) { is Value.CurveV -> 1; is Value.Number -> 2; else -> 0 } } }
    when (kind) {
        1 -> CurveEditor(st, draft, role, raw)
        2 -> NumberEditor(st, draft, role, raw)
        else -> Unit
    }
}

/** How far below none and above it a spring's bounce goes on its slider (SwiftUI's bounce). */
private const val BOUNCE_MIN = -0.4f
private const val BOUNCE_MAX = 0.8f

/** Shapes to start from: a spring's by its bounce, a curve's by its handles (each keeps the duration). */
private val SPRING_SHAPES = listOf("Smooth" to 0f, "Snappy" to 0.15f, "Bouncy" to 0.3f)
private val EASE_SHAPES = listOf("Ease out" to floatArrayOf(0f, 0f, 0.58f, 1f), "Ease in and out" to floatArrayOf(0.42f, 0f, 0.58f, 1f),
    "Emphasized" to floatArrayOf(0.05f, 0.7f, 0.1f, 1f), "Linear" to floatArrayOf(0f, 0f, 1f, 1f))

/** The chips' words, made once (a list made in composition is a new one each time: the chips composed again at every step). */
private val KINDS = listOf("Spring", "Curve")
private val SPRING_SHAPE_NAMES = SPRING_SHAPES.map { it.first }
private val EASE_SHAPE_NAMES = EASE_SHAPES.map { it.first }

/** What a curve makes, in words (and the preset's own pace, when Fine-tune changed it). */
private fun curveInfo(c: Curve, speed: Float): String {
    val past = when (c) {
        is Curve.Spring -> MotionAdjust.overshoot(c.damping).toFloat()
        is Curve.Ease -> ((0..40).maxOf { CurveFit.at(c, c.ms / 1000f * it / 40f) } - 1f).coerceAtLeast(0f)
    }
    return "Settles in " + seconds(CurveFit.settleTime(c)) + (if (past >= 0.005f) " · goes ${(past * 100).roundToInt()} % past" else "") +
        (if (abs(speed - 1f) > 0.001f) " · plays at ${(speed * 100).roundToInt()} % speed" else "")
}

/**
 * A curve's editor (docs/PLAN_MOTION.md): what it is and where its value comes from, Spring | Curve (switching fits the one
 * to the other), the graph (its handle or handles dragged), a spring's duration and bounce or a curve's length, what that
 * makes ("settles in, goes past"), shapes to start from, and Slow (the sample at a quarter speed), Reset, Done. Everything
 * waits for Apply and plays on the phone above at once. The curve ([raw]) is read where it shows; what is composed changes only
 * with its kind (a switch folds one kind's slider and shapes away as the other's open).
 */
@Composable
private fun CurveEditor(st: Studio, draft: Draft, role: MotionRole, raw: androidx.compose.runtime.State<Value?>) {
    val look = LocalLook.current
    val pad = look.dp(SettingsTokens.ROW_PADDING)
    val key = role.key
    val scope = rememberCoroutineScope()
    var fitting by remember { mutableStateOf(false) }
    // The last of each kind, shown by the kind that folds away after a switch.
    val lastSpring = remember { arrayOfNulls<Curve.Spring>(1) }
    val lastEase = remember { arrayOfNulls<Curve.Ease>(1) }
    val curve: () -> Curve = remember(raw) {
        {
            when (val c = (raw.value as? Value.CurveV)?.curve) {
                is Curve.Spring -> c.also { lastSpring[0] = it }
                is Curve.Ease -> c.also { lastEase[0] = it }
                null -> lastSpring[0] ?: lastEase[0] ?: Motion.role(MotionTokens.APPEAR)
            }
        }
    }
    val spring: () -> Curve.Spring? = remember(curve) { { (curve() as? Curve.Spring) ?: lastSpring[0] } }
    val ease: () -> Curve.Ease? = remember(curve) { { (curve() as? Curve.Ease) ?: lastEase[0] } }
    val isSpring by remember(curve) { androidx.compose.runtime.derivedStateOf { curve() is Curve.Spring } }
    // As the preset comes (for Reset and the graph's faint curve); the preset's speed (the info, the graph's dot).
    val own = remember(draft, key, look.version) { (draft.motionOwn(draft.motionId(), key) as? Value.CurveV)?.curve }
    val ownShown = remember(own, curve) { androidx.compose.runtime.derivedStateOf { own?.takeIf { it != curve() } } }
    val speed: () -> Float = remember(draft) { { draft.motionSpeed(draft.motionId()) } }
    // Read when used (a lambda kept by a control may be from an earlier composition).
    fun current(): Curve? = (draft.motionRaw(draft.motionId(), key) as? Value.CurveV)?.curve
    fun set(c: Curve) = draft.chooseMotionValue(draft.motionId(), key, Value.CurveV(c))
    val detail = look.color(SettingsTokens.ROW_DETAIL_COLOR)
    // A drag: the graph follows at once (it morphs only into a shape, Reset or the other kind) and values change at once.
    var sliding by remember { mutableStateOf(false) }
    val slide: (Boolean) -> Unit = remember(st) { { sliding = it; st.motionLive = it } }
    val live: () -> Boolean = remember { { sliding } }
    val instant: () -> Boolean = remember(st) { { st.motionLive } }
    Column(Modifier.fillMaxWidth().padding(start = pad, end = pad, bottom = look.dp(10f * look.unit))) {
        BasicText(role.description, style = look.text(SettingsTokens.ROW_DETAIL, look.color(SettingsTokens.ROW_LABEL)))
        SourceLine(draft, key)
        Spacer(Modifier.height(look.dp(10f * look.unit)))
        // Switched back without touching the other kind: exactly what it was (fitted back, it read the same and counted as a
        // change).
        val switched = remember { arrayOfNulls<Curve>(2) }
        ChoiceChips(KINDS, if (isSpring) 0 else 1) { i ->
            val now = current() ?: return@ChoiceChips
            if ((i == 0) == (now is Curve.Spring) || fitting) return@ChoiceChips
            val before = switched[0]
            if (before != null && switched[1] == now && (before is Curve.Spring) == (i == 0)) {
                switched[0] = null; switched[1] = null
                set(before)
                return@ChoiceChips
            }
            fitting = true
            scope.launch {
                val fit = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    if (now is Curve.Spring) CurveFit.easeLike(now) else CurveFit.springLike(now as Curve.Ease)
                }
                switched[0] = now; switched[1] = fit
                set(fit)
                fitting = false
            }
        }
        Spacer(Modifier.height(look.dp(8f * look.unit)))
        CurveGraph(st, key, curve, ownShown, speed, live) { set(it) }
        // The duration's slider stays through a switch (its knob glides to the other kind's place).
        EditorSlider("Duration",
            { when (val c = curve()) { is Curve.Spring -> seconds(c.response); is Curve.Ease -> millis(c.ms) } },
            { when (val c = curve()) { is Curve.Spring -> logPos(c.response, SPRING_MIN, SPRING_MAX); is Curve.Ease -> logPos(c.ms, EASE_MIN, EASE_MAX) } },
            slide) { t ->
            when (val now = current()) {
                is Curve.Spring -> set(Curve.Spring(snapTo(logVal(t, SPRING_MIN, SPRING_MAX), 0.01f).coerceIn(SPRING_MIN, SPRING_MAX), now.damping))
                is Curve.Ease -> set(now.copy(ms = snapTo(logVal(t, EASE_MIN, EASE_MAX), 10f).coerceIn(EASE_MIN, EASE_MAX)))
                null -> Unit
            }
        }
        OpenReveal(isSpring, false) {
            fun bounce() = spring()?.let { CurveFit.bounceOf(it.damping) } ?: 0f
            EditorSlider("Bounce", { bounce().let { b -> if (abs(b) < 0.005f) "None" else percent(b) } },
                { (bounce() - BOUNCE_MIN) / (BOUNCE_MAX - BOUNCE_MIN) }, slide) { t ->
                val now = current() as? Curve.Spring ?: return@EditorSlider
                set(Curve.Spring(now.response, CurveFit.dampingOf(snapTo(BOUNCE_MIN + t * (BOUNCE_MAX - BOUNCE_MIN), 0.01f).coerceIn(BOUNCE_MIN, BOUNCE_MAX))))
            }
        }
        Spacer(Modifier.height(look.dp(4f * look.unit)))
        DrawnLine({ curveInfo(curve(), speed()) }, look.text(SettingsTokens.ROW_DETAIL, detail), Modifier.fillMaxWidth(), instant = instant)
        Spacer(Modifier.height(look.dp(10f * look.unit)))
        // Shapes to start from (each keeps the duration); the other kind's fold away on a switch.
        OpenReveal(isSpring, false) {
            val chosen by remember(spring) {
                androidx.compose.runtime.derivedStateOf {
                    if (curve() !is Curve.Spring) -1
                    else spring()?.let { s -> SPRING_SHAPES.indexOfFirst { abs(CurveFit.bounceOf(s.damping) - it.second) < 0.005f } } ?: -1
                }
            }
            ChoiceChips(SPRING_SHAPE_NAMES, chosen) { i ->
                val now = current() as? Curve.Spring ?: return@ChoiceChips
                set(Curve.Spring(now.response, CurveFit.dampingOf(SPRING_SHAPES[i].second)))
            }
        }
        OpenReveal(!isSpring, false) {
            val chosen by remember(ease) {
                androidx.compose.runtime.derivedStateOf {
                    if (curve() !is Curve.Ease) -1
                    else ease()?.let { e -> EASE_SHAPES.indexOfFirst { (_, s) -> abs(e.x1 - s[0]) < 0.005f && abs(e.y1 - s[1]) < 0.005f && abs(e.x2 - s[2]) < 0.005f && abs(e.y2 - s[3]) < 0.005f } } ?: -1
                }
            }
            ChoiceChips(EASE_SHAPE_NAMES, chosen) { i ->
                val now = current() as? Curve.Ease ?: return@ChoiceChips
                val s = EASE_SHAPES[i].second
                set(Curve.Ease(s[0], s[1], s[2], s[3], now.ms))
            }
        }
        EditorButtons(st, draft, key)
    }
}

/** Where an animation's value comes from, in plain words; composed again only when that changes (not at a slider's steps). */
@Composable
private fun SourceLine(draft: Draft, key: String) {
    val look = LocalLook.current
    val line by remember(draft, key, look.version) { androidx.compose.runtime.derivedStateOf { sourceLine(draft, draft.motionId(), key) } }
    Rolling(line, look.text(SettingsTokens.ROW_DETAIL, look.color(SettingsTokens.ROW_DETAIL_COLOR)), Modifier)
}

/** A number's editor: what it is, where its value comes from, its slider, Reset and Done; the value read where it shows. */
@Composable
private fun NumberEditor(st: Studio, draft: Draft, role: MotionRole, raw: androidx.compose.runtime.State<Value?>) {
    val look = LocalLook.current
    val pad = look.dp(SettingsTokens.ROW_PADDING)
    val first = remember { (raw.value as? Value.Number)?.v ?: 1f }
    val spec = NUMBERS[role.key] ?: remember { NumberSpec(first * 0.5f, first * 1.5f + 1f, 0.01f) { fmt("%.2f", it) } }
    val value: () -> Float = remember(raw) { { (raw.value as? Value.Number)?.v ?: first } }
    Column(Modifier.fillMaxWidth().padding(start = pad, end = pad, bottom = look.dp(10f * look.unit))) {
        BasicText(role.description, style = look.text(SettingsTokens.ROW_DETAIL, look.color(SettingsTokens.ROW_LABEL)))
        SourceLine(draft, role.key)
        EditorSlider(role.title, { spec.text(value()) }, { (value() - spec.min) / (spec.max - spec.min) }, { st.motionLive = it }) { t ->
            val now = draft.motionRaw(draft.motionId(), role.key) as? Value.Number ?: return@EditorSlider
            draft.chooseMotionValue(draft.motionId(), role.key, Value.Number(snapTo(spec.min + t * (spec.max - spec.min), spec.step).coerceIn(spec.min, spec.max), now.unit))
        }
        EditorButtons(st, draft, role.key)
    }
}

/** Slow (the sample at a quarter speed), Reset (back to the preset's, when you changed it) and Done. */
@Composable
private fun EditorButtons(st: Studio, draft: Draft, key: String) {
    val look = LocalLook.current
    // (Derived: composed again when Reset comes or goes, not at every step of a slider.)
    val changed by remember(draft, key, look.version) { androidx.compose.runtime.derivedStateOf { draft.motionChanged(draft.motionId(), key) } }
    Spacer(Modifier.height(look.dp(4f * look.unit)))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        EditorButton(if (st.sampleSlow) "Full speed" else "Slow", true) { st.sampleSlow = !st.sampleSlow }
        Spacer(Modifier.weight(1f))
        // Reset keeps its place and fades in and out with the change (nothing beside it moves).
        EditorButton("Reset", changed) {
            val p = draft.motionId()
            draft.motionOwn(p, key)?.let { draft.chooseMotionValue(p, key, it) }
        }
        EditorButton("Done", true) { st.motionRole = null }
    }
}

/** A button of words in an editor: its words roll when they change, and it fades out of sight (and touch) unless [shown]. */
@Composable
private fun EditorButton(text: String, shown: Boolean, onClick: () -> Unit) {
    val look = LocalLook.current
    var pressed by remember { mutableStateOf(false) }
    val press by animateFloatAsState(if (pressed) 1f else 0f, look.motion(if (pressed) MotionTokens.PRESS_IN else MotionTokens.PRESS_OUT), label = "press")
    val seen by animateFloatAsState(if (shown) 1f else 0f, look.motion(if (shown) MotionTokens.APPEAR_FADE else MotionTokens.DISAPPEAR_FADE), label = "seen")
    val click = androidx.compose.runtime.rememberUpdatedState(onClick)
    val on = androidx.compose.runtime.rememberUpdatedState(shown)
    Box(Modifier.graphicsLayer { alpha = seen * (1f - 0.65f * press) }
        .then(if (shown) Modifier else Modifier.clearAndSetSemantics { })
        .heightIn(min = look.dp(SettingsTokens.TARGET))
        .pointerInput(Unit) { rowTap({ pressed = it && on.value }) { if (on.value) click.value() } }
        .padding(start = look.dp(8f * look.unit)), contentAlignment = Alignment.CenterStart) {
        Rolling(text, look.text(SettingsTokens.ROW_DETAIL, look.color(SettingsTokens.ACCENT)), Modifier)
    }
}

/**
 * A value set with a slider inside an editor: its name and the value now above (drawn, not laid out), the slider below;
 * [value] and [position] (0..1) are read in drawing.
 */
@Composable
private fun EditorSlider(title: String, value: () -> String, position: () -> Float, onDrag: ((Boolean) -> Unit)? = null, onChange: (Float) -> Unit) {
    val look = LocalLook.current
    val st = LocalStudio.current
    val instant: () -> Boolean = remember(st) { { st?.motionLive == true } }
    Column(Modifier.fillMaxWidth().padding(top = look.dp(6f * look.unit))) {
        // The value drawn over the row's right end: its width changing never lays the name out again.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            BasicText(title, style = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_LABEL)))
            DrawnLine(value, look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_DETAIL_COLOR)), Modifier.fillMaxWidth(),
                end = true, instant = instant)
        }
        SettingsSlider({ position().coerceIn(0f, 1f) }, onDrag, onChange)
    }
}

/**
 * The curve's graph: how far the motion has gone (up) over time (across), the place it rests dashed; the preset's own faint
 * behind yours once changed (fading in and out with the change); a dot running along it in step with the sample (its own
 * run, at the preset's speed). A spring has a handle on its first peak (or where it reaches 98 %): up and down for its
 * bounce, sideways for its duration; a bezier its two, joined to its ends. The time axis glides to fit the curve and holds
 * still while a handle moves. A new curve (a shape, Reset, the other kind) morphs from what shows, its handles gliding (or,
 * the other kind's, fading in as the last's fade out); while a handle or a slider is dragged it follows at once ([live]).
 */
@Composable
private fun CurveGraph(st: Studio, key: String, curve: () -> Curve, own: androidx.compose.runtime.State<Curve?>, speed: () -> Float,
                       live: () -> Boolean, onChange: (Curve) -> Unit) {
    val look = LocalLook.current
    val fill = look.color(SettingsTokens.CHIP_FILL)
    val accent = look.color(SettingsTokens.ACCENT)
    val line = look.color(SettingsTokens.ROW_DETAIL_COLOR)
    val knob = look.color(SettingsTokens.TOGGLE_KNOB)
    val unit = look.unit
    val change = androidx.compose.runtime.rememberUpdatedState(onChange)
    // The curve as it is (read in drawing, by the gestures and by the follower below: nothing here composes at a step).
    val now = remember(curve) { androidx.compose.runtime.derivedStateOf { curve() } }
    var held by remember { mutableStateOf<Float?>(null) }
    // Long enough for the curve to settle, the preset's own behind it, and the handle (a spring's peak can come later).
    fun span(c: Curve) = maxOf(CurveFit.settleTime(c, 0.005f), if (c is Curve.Spring) CurveFit.handleOf(c).first * 1.06f else 0f)
    fun want() = maxOf(span(now.value), own.value?.let { span(it) } ?: 0f, 0.25f) * 1.12f
    val axisSpec = androidx.compose.runtime.rememberUpdatedState(look.motion<Float>(MotionTokens.CC_LEVEL))
    val axis = remember { Animatable(want()) }
    LaunchedEffect(Unit) { androidx.compose.runtime.snapshotFlow { held ?: want() }.collectLatest { axis.animateTo(it, axisSpec.value) } }
    // What shows: [from] (a function of time: the last curve, or the blend that showed when this one came) into [target];
    // the handles from where they showed. Set when a new curve comes (morph back to 0), read in drawing.
    val morph = remember { Animatable(1f) }
    val levelSpec = androidx.compose.runtime.rememberUpdatedState(look.motion<Float>(MotionTokens.CC_LEVEL))
    val target = remember { arrayOf(now.value) }
    val from = remember { arrayOf<(Float) -> Float>({ t -> CurveFit.at(target[0], t) }) }
    val fromHandles = remember { arrayOf(Handles.of(now.value)) }
    LaunchedEffect(Unit) { androidx.compose.runtime.snapshotFlow { now.value }.collectLatest { curve ->
        val last = target[0]
        if (last == curve) return@collectLatest
        val mk = morph.value
        val shownFrom = from[0]
        val lastHandles = Handles.of(last)
        if (mk >= 0.999f) {
            from[0] = { t -> CurveFit.at(last, t) }
            fromHandles[0] = lastHandles
        } else {
            // Mid-morph: the blend on screen, kept as it showed (sampled), so the next starts there and nothing jumps.
            val reach = maxOf(axis.value, span(curve), span(last)) * 1.3f
            val vals = FloatArray(FROZEN_SAMPLES + 1) { i -> val t = reach * i / FROZEN_SAMPLES; shownFrom(t) + (CurveFit.at(last, t) - shownFrom(t)) * mk }
            from[0] = { t -> frozenAt(vals, reach, t) }
            fromHandles[0] = fromHandles[0].toward(lastHandles, mk)
        }
        target[0] = curve
        if (held != null || live()) { morph.snapTo(1f); return@collectLatest }
        morph.snapTo(0f)
        morph.animateTo(1f, levelSpec.value)
    } }
    // The preset's own curve behind yours: faded in as you change it, out as you go back (drawn as it last was meanwhile).
    val lastOwn = remember { arrayOfNulls<Curve>(1) }
    val ownNow = own.value
    if (ownNow != null) lastOwn[0] = ownNow
    val ownK = animateFloatAsState(if (ownNow != null) 1f else 0f, look.motion(if (ownNow != null) MotionTokens.APPEAR_FADE else MotionTokens.DISAPPEAR_FADE), label = "own")
    val path = remember { androidx.compose.ui.graphics.Path() }
    val ownPath = remember { androidx.compose.ui.graphics.Path() }
    val dash = remember(unit) { androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(5f * unit, 5f * unit)) }
    val padX = 16f * unit
    val padY = 14f * unit
    val yMin = -0.12f
    val yMax = 1.45f
    Canvas(Modifier.fillMaxWidth().height(look.dp(160f * unit)).graphicsLayer().pointerInput(key) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val c0 = now.value
            val ax = axis.value
            val w = size.width - padX * 2f
            val h = size.height - padY * 2f
            fun at(t: Float, v: Float) = Offset(padX + t / ax * w, padY + h * (1f - (v - yMin) / (yMax - yMin)))
            val handles = Handles.of(c0).pts.map { at(it.x, it.y) }
            val hit = handles.indices.minByOrNull { (handles[it] - down.position).getDistance() }
                ?.takeIf { (handles[it] - down.position).getDistance() < 30f * unit } ?: return@awaitEachGesture
            down.consume()
            held = ax
            st.motionLive = true
            // The handle moves by what the finger moves (grabbed off its middle, it does not jump under the finger).
            val grab = handles[hit] - down.position
            try {
                while (true) {
                    val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    ch.consume()
                    if (ch.position == ch.previousPosition) continue
                    val p = ch.position + grab
                    val t = ((p.x - padX) / w * ax).coerceIn(0.02f, ax)
                    val v = yMin + (1f - (p.y - padY) / h) * (yMax - yMin)
                    val next = when (val cur = now.value) {
                        is Curve.Spring -> CurveFit.springFromHandle(t, v.coerceAtMost(1.6f), cur)
                        is Curve.Ease -> {
                            val x = (t / (cur.ms / 1000f)).coerceIn(0f, 1f)
                            val y = v.coerceIn(-0.5f, 1.5f)
                            if (hit == 0) cur.copy(x1 = x, y1 = y) else cur.copy(x2 = x, y2 = y)
                        }
                    }
                    change.value(next)
                }
            } finally { held = null; st.motionLive = false }
        }
    }) {
        val ax = axis.value
        val w = size.width - padX * 2f
        val h = size.height - padY * 2f
        fun xOf(t: Float) = padX + t / ax * w
        fun yOf(v: Float) = padY + h * (1f - (v.coerceIn(yMin, yMax) - yMin) / (yMax - yMin))
        drawRoundRect(fill, cornerRadius = CornerRadius(14f * unit))
        drawLine(line.copy(alpha = line.alpha * 0.45f), Offset(padX, yOf(1f)), Offset(padX + w, yOf(1f)), strokeWidth = 1f * unit, pathEffect = dash)
        drawLine(line.copy(alpha = line.alpha * 0.25f), Offset(padX, yOf(0f)), Offset(padX + w, yOf(0f)), strokeWidth = 1f * unit)
        val n = 72
        val ok = ownK.value
        val ownC = lastOwn[0]
        if (ownC != null && ok > 0.003f) {
            ownPath.reset()
            for (i in 0..n) { val t = ax * i / n; val x = xOf(t); val y = yOf(CurveFit.at(ownC, t)); if (i == 0) ownPath.moveTo(x, y) else ownPath.lineTo(x, y) }
            drawPath(ownPath, line.copy(alpha = line.alpha * 0.5f * ok), style = Stroke(1.5f * unit, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        // Dragged (a handle, a slider): the curve as it is now, at once.
        val dragging = held != null || live()
        val b = if (dragging) now.value else target[0]
        val mk = if (dragging) 1f else morph.value
        val a = from[0]
        path.reset()
        for (i in 0..n) {
            val t = ax * i / n
            val v = if (mk >= 1f) CurveFit.at(b, t) else a(t) + (CurveFit.at(b, t) - a(t)) * mk
            val x = xOf(t); val y = yOf(v)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, accent, style = Stroke(2.6f * unit, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        // The handles: gliding from where they showed to the new curve's (the other kind's fade in as the last's fade out).
        fun handle(p: Offset, k: Float) {
            drawCircle(Color.Black.copy(alpha = 0.14f * k), 9.5f * unit, p + Offset(0f, 1.2f * unit))
            drawCircle(knob.copy(alpha = knob.alpha * k), 9f * unit, p)
            drawCircle(accent.copy(alpha = accent.alpha * k), 9f * unit, p, style = Stroke(2f * unit))
        }
        fun draw(hs: Handles, k: Float) {
            if (k <= 0.003f) return
            val ps = hs.pts.map { Offset(xOf(it.x), yOf(it.y)) }
            if (hs.bezier) {
                drawLine(accent.copy(alpha = 0.5f * k), Offset(xOf(0f), yOf(0f)), ps[0], strokeWidth = 1.4f * unit)
                drawLine(accent.copy(alpha = 0.5f * k), Offset(xOf(hs.length), yOf(1f)), ps[1], strokeWidth = 1.4f * unit)
            }
            for (p in ps) handle(p, k)
        }
        val hb = Handles.of(b)
        val ha = fromHandles[0]
        when {
            mk >= 1f -> draw(hb, 1f)
            ha.bezier == hb.bezier -> draw(ha.toward(hb, mk), 1f)
            else -> { draw(ha, 1f - mk); draw(hb, mk) }
        }
        // The dot, in step with the sample (its own run): at the role's own time (the preset's speed undone).
        val sm = st.sample
        val t = sm.t.floatValue
        if (sm.role == key && t >= 0f) {
            val tr = (t * speed()).coerceAtMost(ax)
            val v = if (mk >= 1f) CurveFit.at(b, tr) else a(tr) + (CurveFit.at(b, tr) - a(tr)) * mk
            drawCircle(accent.copy(alpha = accent.alpha * sm.alpha.value), 5f * unit, Offset(xOf(tr), yOf(v)))
        }
    }
}

/** A curve's handles on the graph, in time (s) and value: a spring's one, a bezier's two (joined to its ends, [length] s). */
private class Handles(val bezier: Boolean, val pts: List<Offset>, val length: Float) {
    /** Partway ([k]) from these to [o] of the same kind. */
    fun toward(o: Handles, k: Float): Handles =
        if (o.bezier != bezier || o.pts.size != pts.size) o
        else Handles(bezier, pts.indices.map { pts[it] + (o.pts[it] - pts[it]) * k }, length + (o.length - length) * k)

    companion object {
        fun of(c: Curve): Handles = when (c) {
            is Curve.Spring -> CurveFit.handleOf(c).let { (t, v) -> Handles(false, listOf(Offset(t, v)), 0f) }
            is Curve.Ease -> (c.ms / 1000f).let { d -> Handles(true, listOf(Offset(c.x1 * d, c.y1), Offset(c.x2 * d, c.y2)), d) }
        }
    }
}

/** How many pieces a blend that showed is kept in (over its time), for the next curve to morph from. */
private const val FROZEN_SAMPLES = 96

/** [v] sampled evenly over [reach] s, at [t] (past its end, where it ended). */
private fun frozenAt(v: FloatArray, reach: Float, t: Float): Float {
    if (t >= reach) return v.last()
    val x = (t / reach * (v.size - 1)).coerceAtLeast(0f)
    val i = x.toInt().coerceAtMost(v.size - 2)
    return v[i] + (v[i + 1] - v[i]) * (x - i)
}

/**
 * What the picture plays while a moment's page is in (over Motion or over a part's page): the animation whose editor is open,
 * else the moment's own sample; its run on the draft's curve (as it would be applied: the preset's and the moment's speed
 * and bounce), a pause, the way back on its partner (or at once), a pause, again. Slow plays it at a quarter speed. The
 * picture draws it (MotionSamples), the editor's graph runs a dot with it. One for the whole settings.
 */
@Composable
internal fun MotionSampleLoop(st: Studio) {
    val draft = LocalDraft.current
    val playing = remember(st) {
        androidx.compose.runtime.derivedStateOf {
            val sub = st.sub as? MomentPage
            if (sub != null && st.open == sub.part && !st.p.isRunning && st.subPush.targetValue >= 0.5f) st.motionRole ?: sub.moment.sample else null
        }
    }
    // An editor left open on a page that has gone: none open on the next one.
    LaunchedEffect(st) { androidx.compose.runtime.snapshotFlow { st.sub }.collect { st.motionRole = null } }
    val look = LocalLook.current
    val fadeOut = androidx.compose.runtime.rememberUpdatedState(look.motion<Float>(MotionTokens.DISAPPEAR_FADE))
    val fadeIn = androidx.compose.runtime.rememberUpdatedState(look.motion<Float>(MotionTokens.APPEAR_FADE))
    LaunchedEffect(st) {
        androidx.compose.runtime.snapshotFlow { playing.value }.collectLatest { key ->
            val sm = st.sample
            // The last one fades out where it is (never cut mid-run; the same one too, unless it waits at its start), then
            // the next fades in at its start.
            val spec = key?.let { MotionSamples.spec(it) }
            if (sm.role != null && (sm.role != key || spec == null || sm.k.floatValue != spec.from)) sm.alpha.animateTo(0f, fadeOut.value)
            if (key == null || spec == null) { sm.role = null; sm.k.floatValue = -1f; sm.t.floatValue = -1f; return@collectLatest }
            sm.role = key
            sm.forward.value = true
            sm.k.floatValue = spec.from
            sm.t.floatValue = -1f
            // (The preset is read at each run: it cannot change while a moment's page is in, but its speeds and bounces can.)
            fun curveOf(k: String): Curve = if (k in MotionTokens.CURVES) draft.motionCurve(draft.motionId(), CurveKey(k)) else Motion.role(MotionTokens.APPEAR)
            suspend fun run(c: Curve, from: Float, to: Float, own: Boolean) {
                val m = c.mover().apply { start(from, 0f, to) }
                var last = androidx.compose.runtime.withFrameNanos { it }
                var t = 0.0
                while (true) {
                    val nowNs = androidx.compose.runtime.withFrameNanos { it }
                    // A long frame counts as a short one (a stall pauses the sample, never makes it jump).
                    t += ((nowNs - last) / 1e9).coerceAtMost(LOOP_STEP_MAX.toDouble()) * (if (st.sampleSlow) 0.25 else 1.0)
                    last = nowNs
                    sm.k.floatValue = m.value(t)
                    if (own) sm.t.floatValue = (t / Motion.slow()).toFloat()
                    if (m.settled(t, 0.002f)) { sm.k.floatValue = to; break }
                }
            }
            suspend fun pause(ms: Long) {
                // A wiggle goes on while it rests (drawn from the clock).
                if (spec.kind == MotionSamples.Kind.WIGGLE) {
                    val until = android.os.SystemClock.uptimeMillis() + ms
                    while (android.os.SystemClock.uptimeMillis() < until) { androidx.compose.runtime.withFrameNanos { }; sm.tick.intValue++ }
                } else kotlinx.coroutines.delay((ms * (if (st.sampleSlow) 2 else 1)))
            }
            kotlinx.coroutines.coroutineScope {
                launch { sm.alpha.animateTo(1f, fadeIn.value) }
                pause(350)
            }
            while (true) {
                sm.number.floatValue = (draft.motionRaw(draft.motionId(), key) as? Value.Number)?.v ?: Float.NaN
                sm.forward.value = true
                run(curveOf(spec.drive ?: key), spec.from, spec.to, true)
                sm.t.floatValue = -1f
                pause(700)
                val back = spec.partner
                if (back != null) {
                    sm.forward.value = false
                    run(curveOf(back), spec.to, spec.from, false)
                } else sm.k.floatValue = spec.from
                pause(600)
            }
        }
    }
}

/**
 * Motion (docs/PLAN_SETTINGS_V2.md, V2): the presets as cards, each playing a small app opening in its own way (the picture
 * of home above plays the chosen one), the whole preset's feel (Speed, Bounce), and the moments by part, each a row with its
 * feel in words that pushes its page. Waits for Apply like the rest.
 */
@Composable
internal fun MotionPage(push: (Pushed) -> Unit, lit: () -> Pushed?) {
    val draft = LocalDraft.current
    val look = LocalLook.current
    val st = LocalStudio.current
    val all = remember(look.version) { Design.motionPresets() }
    val chosen = draft.motionId()
    // Presets made for testing show when Developer asks for them, or while one is in use; the default first, tests last.
    val presets = all.filter { !it.test || (SettingsPrefs.developer && SettingsPrefs.testPresets) || it.id == chosen || it.id == Design.motionPresetId }
        .sortedWith(compareBy({ it.test }, { it.id != Design.DEFAULT_MOTION }, { it.name }))
    // The cards' apps run on one clock while the page is open and at rest (read in drawing only).
    val clock = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    LaunchedEffect(st) {
        androidx.compose.runtime.snapshotFlow {
            st == null || (st.open == Part.MOTION && !st.p.isRunning && !(st.sub?.part == Part.MOTION && st.subPush.targetValue >= 0.5f))
        }.collectLatest { open ->
            if (!open) return@collectLatest
            // Frame by frame, a long frame counting as a short one: the apps pause through a stall, never jump.
            var last = androidx.compose.runtime.withFrameNanos { it }
            while (true) {
                val now = androidx.compose.runtime.withFrameNanos { it }
                clock.floatValue += ((now - last) / 1e9f).coerceAtMost(LOOP_STEP_MAX)
                last = now
            }
        }
    }
    ChangesRow(Part.MOTION, push, lit)
    Group("Presets", "How everything moves, each its own way. The phone above plays the one you choose.") {
        PresetCards(presets, draft, clock)
    }
    Feel(draft)
    for (part in MOMENT_PARTS) {
        Group(part.second, if (part.first == dev.launcher.app.motion.MomentPart.EVERYWHERE) "Each moment's speed and bounce on top of the preset's, and every animation in it." else null) {
            for (m in Moments.of(part.first)) androidx.compose.runtime.key(m.id) { MomentRow(m, Part.MOTION, push, lit) }
        }
    }
}

/** The moments' parts as Motion lists them, with their headings. */
private val MOMENT_PARTS = listOf(
    dev.launcher.app.motion.MomentPart.APPS to "Apps", dev.launcher.app.motion.MomentPart.HOME to "Home",
    dev.launcher.app.motion.MomentPart.LIBRARY to "App Library", dev.launcher.app.motion.MomentPart.SHADE to "Notifications & Control Center",
    dev.launcher.app.motion.MomentPart.STATUS_BAR to "Status bar", dev.launcher.app.motion.MomentPart.EVERYWHERE to "Everywhere",
)

/** Part [p]'s moments (none for Look, Motion and Phone). */
internal fun momentsOf(p: Part): List<Moment> = when (p) {
    Part.HOME -> Moments.of(dev.launcher.app.motion.MomentPart.HOME)
    Part.APPS -> Moments.of(dev.launcher.app.motion.MomentPart.APPS)
    Part.LIBRARY -> Moments.of(dev.launcher.app.motion.MomentPart.LIBRARY)
    Part.SHADE -> Moments.of(dev.launcher.app.motion.MomentPart.SHADE)
    Part.STATUS_BAR -> Moments.of(dev.launcher.app.motion.MomentPart.STATUS_BAR)
    else -> emptyList()
}

/**
 * The presets side by side, each playing a small app opening out of its icon and going back into it, on that preset's own
 * curves at its speed and bounce as it would be applied (its pace and bounce show), the chosen one ringed in the accent. One
 * beat for all of them: every app starts opening together and closing together, so a quicker preset settles sooner. Two
 * share the row; more keep that width and scroll sideways, the next one peeking in.
 */
@Composable
private fun PresetCards(presets: List<Design.ThemeInfo>, draft: Draft, clock: androidx.compose.runtime.FloatState) {
    val look = LocalLook.current
    val pad = look.dp(SettingsTokens.ROW_PADDING)
    val gap = look.dp(12f * look.unit)
    // Derived: every step of a slider changes the draft; the cards compose again only when one of their curves changed, and
    // not at all while a moment's page covers them.
    val st = LocalStudio.current
    val held = remember { arrayOfNulls<List<Pair<CurveRun, CurveRun>>>(1) }
    val runs by remember(presets, draft, look.version) {
        androidx.compose.runtime.derivedStateOf {
            val covered = st != null && st.sub?.part == Part.MOTION && st.subPush.targetValue >= 0.5f
            held[0]?.takeIf { covered } ?: presets.map {
                CurveRun.of(draft.motionCurve(it.id, MotionTokens.APP_OPEN)) to CurveRun.of(draft.motionCurve(it.id, MotionTokens.APP_CLOSE_SIZE))
            }.also { held[0] = it }
        }
    }
    // The beat: when every app has opened, and when every one has closed (it glides to fit the slowest).
    val openFor = animateFloatAsState(maxOf(0.3f, runs.maxOfOrNull { it.first.duration } ?: 0.3f), look.motion(MotionTokens.SETTINGS_FOCUS), label = "open")
    val closeFor = animateFloatAsState(maxOf(0.3f, runs.maxOfOrNull { it.second.duration } ?: 0.3f), look.motion(MotionTokens.SETTINGS_FOCUS), label = "close")
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val room = maxWidth - pad * 2
        val cardW = if (presets.size <= 2) (room - gap) / 2 else room * 0.44f
        val scroll = rememberScrollState()
        Row(Modifier.fillMaxWidth().then(if (presets.size > 2) Modifier.horizontalScroll(scroll) else Modifier).padding(pad),
            horizontalArrangement = Arrangement.spacedBy(gap)) {
            presets.forEachIndexed { i, p ->
                androidx.compose.runtime.key(p.id) { PresetCard(p, runs[i], draft, clock, openFor, closeFor, Modifier.width(cardW)) }
            }
        }
    }
}

/** How long every app rests open, and closed, between its moves (s). */
private const val PRESET_REST = 0.7f

@Composable
private fun PresetCard(p: Design.ThemeInfo, run: Pair<CurveRun, CurveRun>, draft: Draft, clock: androidx.compose.runtime.FloatState,
                       openFor: androidx.compose.runtime.State<Float>, closeFor: androidx.compose.runtime.State<Float>, modifier: Modifier) {
    val look = LocalLook.current
    val accent = look.color(SettingsTokens.ACCENT)
    val line = look.color(SettingsTokens.ROW_DETAIL_COLOR)
    val fill = look.color(SettingsTokens.CHIP_FILL)
    val page = look.color(SettingsTokens.GROUP_FILL).copy(alpha = 1f)
    val chosen = draft.motionId() == p.id
    val k = animateFloatAsState(if (chosen) 1f else 0f, look.motion(MotionTokens.CC_TOGGLE), label = "chosen")
    var pressed by remember { mutableStateOf(false) }
    val press = animateFloatAsState(if (pressed) 1f else 0f, look.motion(if (pressed) MotionTokens.PRESS_IN else MotionTokens.PRESS_OUT), label = "press")
    val corner = look.pt(SettingsTokens.TILE_CORNER) * 0.7f
    val unit = look.unit
    val stage = remember { CardStage() }
    Column(modifier.pressScale { 1f - 0.04f * press.value }
        .pointerInput(p.id) { rowTap({ pressed = it }) { draft.chooseMotion(p.id) } }) {
        Box(Modifier.fillMaxWidth().height(look.dp(84f * unit)).drawBehind {
            val kk = k.value.coerceIn(0f, 1f)
            // The beat: open, rest, close, rest, again.
            val o = openFor.value
            val c = closeFor.value
            val loop = o + PRESET_REST + c + PRESET_REST
            val t = clock.floatValue % loop
            val opening = t < o + PRESET_REST
            val v = if (opening) run.first.at(t) else 1f - run.second.at(t - o - PRESET_REST)
            // Its app, opening and going home in the preset's own ways (motion/AppStyles).
            val style = draft.motionStyle(p.id, if (opening) dev.launcher.app.motion.AppStyles.OPENING else dev.launcher.app.motion.AppStyles.CLOSING)
            drawStyleArt(stage, corner, unit, fill, line, accent, kk, page, style, opening, v)
        })
        Spacer(Modifier.height(look.dp(8f * unit)))
        BasicText(p.name, style = look.text(SettingsTokens.ROW_DETAIL, mix(look.color(SettingsTokens.ROW_LABEL), accent, k.value.coerceIn(0f, 1f))), maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        val summary by remember(p, draft, look.version) { androidx.compose.runtime.derivedStateOf { draft.motionSummary(p.id) ?: p.description } }
        Rolling(summary, look.text(SettingsTokens.ROW_DETAIL, look.color(SettingsTokens.ROW_DETAIL_COLOR)), Modifier, lines = 2)
    }
}

/**
 * A curve run once from rest (0 to 1), sampled for drawing: how far along it is at a time ([at]), and how long it runs
 * until it rests (a bezier: its length).
 */
private class CurveRun(val step: Float, val values: FloatArray) {
    val duration: Float get() = step * (values.size - 1)

    fun at(t: Float): Float {
        val i = (t / step).coerceAtLeast(0f)
        val i0 = i.toInt().coerceAtMost(values.size - 1)
        val i1 = (i0 + 1).coerceAtMost(values.size - 1)
        return values[i0] + (values[i1] - values[i0]) * (i - i0)
    }

    companion object {
        private const val STEP = 1f / 240f
        private const val LONGEST = 4.0

        fun of(c: dev.launcher.app.design.Curve): CurveRun {
            val m = c.mover().apply { start(0f, 0f, 1f) }
            var end = 0.0
            while (end < LONGEST && !m.settled(end, 0.002f)) end += STEP
            val n = (end / STEP).roundToInt().coerceAtLeast(1)
            return CurveRun(STEP, FloatArray(n + 1) { i -> if (i == n) 1f else m.value(i * STEP.toDouble()) })
        }
    }
}

/**
 * The whole preset's feel (docs/PLAN_SETTINGS_V2.md): its speed (every curve's time) and its bounce (how far whatever bounces
 * goes past its place), in words, the slider's ends saying which way is which; and back to the preset as it comes. Shown at
 * once on the cards and the phone above; waits for Apply. The speed's slider doubles and halves evenly either side of the
 * preset's own.
 */
@Composable
private fun Feel(draft: Draft) {
    val id = draft.motionId()
    val name = presetName(id)
    val v = LocalLook.current.version
    // Derived: every step of a slider on a moment's page changes the draft; these compose again only when they change.
    val speed by remember(draft, id, v) { androidx.compose.runtime.derivedStateOf { draft.motionSpeed(id) } }
    val bounce by remember(draft, id, v) { androidx.compose.runtime.derivedStateOf { draft.motionBounce(id) } }
    val n by remember(draft, id, v) { androidx.compose.runtime.derivedStateOf { draft.motionChangeCount(id) } }
    // (It keeps its last count while it folds away.)
    val last = remember { intArrayOf(1) }
    if (n > 0) last[0] = n
    Group("Feel", "For $name as a whole: every moment quicker or calmer, every bounce livelier or stiller.") {
        val speedOwn = remember(draft, id, v) { (draft.motionOwn(id, MotionTokens.ADJUST_SPEED.name) as? Value.Number)?.v ?: 1f }
        val bounceOwn = remember(draft, id, v) { (draft.motionOwn(id, MotionTokens.ADJUST_BOUNCE.name) as? Value.Number)?.v ?: 1f }
        FeelSlider("Speed", when {
            abs(speed - 1f) < 0.03f -> "As $name has it"
            speed > 1f -> "Snappier · ${(speed * 100).roundToInt()} %"
            else -> "More relaxed · ${(speed * 100).roundToInt()} %"
        }, speedToSlider(speed), "Relaxed", "Snappy", changed = abs(speed - speedOwn) > 0.001f) { t ->
            draft.chooseMotionNumber(id, MotionTokens.ADJUST_SPEED, sliderToSpeed(t))
        }
        FeelSlider("Bounce", when {
            bounce < 0.005f -> "None"
            abs(bounce - 1f) < 0.03f -> "As $name has it"
            bounce > 1f -> "Livelier · ${(bounce * 100).roundToInt()} %"
            else -> "Stiller · ${(bounce * 100).roundToInt()} %"
        }, bounce / MotionAdjust.BOUNCE_MAX, "Still", "Lively", changed = abs(bounce - bounceOwn) > 0.001f) { t ->
            draft.chooseMotionNumber(id, MotionTokens.ADJUST_BOUNCE, Draft.snap(t * MotionAdjust.BOUNCE_MAX))
        }
        Reveal(n > 0) {
            ActionRow("Reset all", "Back to $name as it comes (${last[0]} change" + (if (last[0] == 1) "" else "s") + ")") {
                draft.resetMotion(id)
            }
        }
    }
}

/** The speed slider's place (0..1) for a speed: 50 % at the start, the preset's own in the middle, 200 % at the end. */
private fun speedToSlider(speed: Float) = ((kotlin.math.ln(speed) / kotlin.math.ln(2f)) + 1f) / 2f

private fun sliderToSpeed(t: Float) = Draft.snap(Math.pow(2.0, (t * 2f - 1f).toDouble()).toFloat()).coerceIn(MotionAdjust.SPEED_MIN, MotionAdjust.SPEED_MAX)

/**
 * A card's small app ([AppStyleArt]): its fill and the accent ring (at [ring]), a home of faint icons, and the app opening
 * ([opening]) or going home in [style] at [v] of its run, its icon a third of the way across the card's bottom row.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStyleArt(
    stage: CardStage, corner: Float, unit: Float, fill: Color, line: Color, accent: Color, ring: Float, page: Color,
    style: dev.launcher.app.motion.AppStyle, opening: Boolean, v: Float,
) {
    drawRoundRect(fill, cornerRadius = CornerRadius(corner))
    val inset = 9f * unit
    val icon = 18f * unit
    stage.w = size.width - inset * 2f
    stage.h = size.height - inset * 2f
    stage.corner = (corner - inset).coerceAtLeast(2f * unit)
    val ix = (stage.w * 0.36f - icon / 2f)
    val iy = stage.h - icon - 4f * unit
    stage.icon.set(ix, iy, ix + icon, iy + icon)
    stage.fill = fill.toArgb()
    stage.faint = line.copy(alpha = line.alpha * 0.22f).toArgb()
    stage.accent = mix(line, accent, ring).toArgb()
    stage.pageColor = page.toArgb()
    stage.ink = mix(line, accent, ring).toArgb()
    drawIntoCanvas { cv ->
        val nc = cv.nativeCanvas
        nc.save()
        stage.path.rewind()
        stage.path.addRoundRect(0f, 0f, size.width, size.height, corner, corner, android.graphics.Path.Direction.CW)
        nc.clipPath(stage.path)
        nc.translate(inset, inset)
        stage.drawIcons(nc)
        // (Sliding and fading leave the icon on home.)
        if (!style.fromIcon) stage.icon(nc, stage.icon.left, stage.icon.top, stage.icon.right, stage.icon.bottom, 1f)
        AppStyleArt.draw(stage, nc, v, style, opening)
        nc.restore()
    }
    if (ring > 0.001f) drawRoundRect(accent.copy(alpha = accent.alpha * ring), cornerRadius = CornerRadius(corner), style = Stroke(2.5f * unit))
}

/**
 * How apps open, or go home (docs/MOTION_STYLES.md): a card per style, each playing it on this moment's curves with the
 * other half as you have it (an opening style's app goes home your way), the chosen one ringed in the accent; the picture
 * above plays the chosen one. Chosen by watching, never by name alone. Waits for Apply like the rest.
 */
@Composable
private fun StyleGroup(m: Moment, draft: Draft) {
    val look = LocalLook.current
    val key = m.style ?: return
    val closing = key == dev.launcher.app.motion.AppStyles.CLOSING
    val id = draft.motionId()
    val v = look.version
    val runs by remember(draft, id, v) {
        androidx.compose.runtime.derivedStateOf {
            CurveRun.of(draft.motionCurve(id, MotionTokens.APP_OPEN)) to CurveRun.of(draft.motionCurve(id, MotionTokens.APP_CLOSE_SIZE))
        }
    }
    // One clock for every card while the page is in (read in drawing only); a long frame counts as a short one.
    val clock = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = androidx.compose.runtime.withFrameNanos { it }
        while (true) {
            val now = androidx.compose.runtime.withFrameNanos { it }
            clock.floatValue += ((now - last) / 1e9f).coerceAtMost(LOOP_STEP_MAX)
            last = now
        }
    }
    val pad = look.dp(SettingsTokens.ROW_PADDING)
    val gap = look.dp(12f * look.unit)
    Group("Style", if (closing) "How an app goes home, each its own way. The phone above plays the one you choose."
        else "How an app opens, each its own way. The phone above plays the one you choose.") {
        Column(Modifier.fillMaxWidth().padding(pad), verticalArrangement = Arrangement.spacedBy(gap)) {
            for (row in dev.launcher.app.motion.AppStyle.entries.chunked(2)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (s in row) androidx.compose.runtime.key(s.id) {
                        StyleCard(s, key, closing, draft, runs, clock, Modifier.weight(1f))
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StyleCard(s: dev.launcher.app.motion.AppStyle, key: dev.launcher.app.design.ChoiceKey, closing: Boolean, draft: Draft,
                      runs: Pair<CurveRun, CurveRun>, clock: androidx.compose.runtime.FloatState, modifier: Modifier) {
    val look = LocalLook.current
    val accent = look.color(SettingsTokens.ACCENT)
    val line = look.color(SettingsTokens.ROW_DETAIL_COLOR)
    val fill = look.color(SettingsTokens.CHIP_FILL)
    val page = look.color(SettingsTokens.GROUP_FILL).copy(alpha = 1f)
    val id = draft.motionId()
    val chosen by remember(draft, id, s, key) { androidx.compose.runtime.derivedStateOf { draft.motionStyle(id, key) == s } }
    val k = animateFloatAsState(if (chosen) 1f else 0f, look.motion(MotionTokens.CC_TOGGLE), label = "chosen")
    var pressed by remember { mutableStateOf(false) }
    val press = animateFloatAsState(if (pressed) 1f else 0f, look.motion(if (pressed) MotionTokens.PRESS_IN else MotionTokens.PRESS_OUT), label = "press")
    val corner = look.pt(SettingsTokens.TILE_CORNER) * 0.7f
    val unit = look.unit
    val stage = remember { CardStage() }
    Column(modifier.pressScale { 1f - 0.04f * press.value }
        .pointerInput(s.id, id) { rowTap({ pressed = it }) { draft.chooseMotionStyle(id, key, s) } }) {
        Box(Modifier.fillMaxWidth().height(look.dp(96f * unit)).drawBehind {
            // The beat: open, rest, home, rest, again; this card's style on its half, the other as you have it.
            val o = runs.first.duration
            val c = runs.second.duration
            val loop = o + PRESET_REST + c + PRESET_REST
            val t = clock.floatValue % loop
            val opening = t < o + PRESET_REST
            val v = if (opening) runs.first.at(t) else 1f - runs.second.at(t - o - PRESET_REST)
            val style = if (opening != closing) s
                else draft.motionStyle(id, if (opening) dev.launcher.app.motion.AppStyles.OPENING else dev.launcher.app.motion.AppStyles.CLOSING)
            drawStyleArt(stage, corner, unit, fill, line, accent, k.value.coerceIn(0f, 1f), page, style, opening, v)
        })
        Spacer(Modifier.height(look.dp(8f * unit)))
        BasicText(if (closing) s.closing else s.opening,
            style = look.text(SettingsTokens.ROW_DETAIL, mix(look.color(SettingsTokens.ROW_LABEL), accent, k.value.coerceIn(0f, 1f))),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
