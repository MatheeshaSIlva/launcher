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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import dev.launcher.app.design.Curve
import dev.launcher.app.design.CurveKey
import dev.launcher.app.design.Design
import dev.launcher.app.design.Provenance
import dev.launcher.app.design.Value
import dev.launcher.app.motion.CurveFit
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.MotionAdjust
import dev.launcher.app.motion.MotionGroup
import dev.launcher.app.motion.MotionRole
import dev.launcher.app.motion.MotionTokens
import dev.launcher.app.motion.mover
import dev.launcher.app.theme.Appearance
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// The settings' Motion (docs/PLAN_MOTION.md): the presets and their fine-tuning (C4a), and every animation by group, each
// with its editor and its sample on the picture of home (C4b).

/**
 * Motion's page and the groups' pages over it: a group tapped slides its page in from the right as Motion gives way (a third
 * of the way left, fading; `motion.sheet.push`), the picture focusing on where the group plays; Back, the round button or
 * the system's, slides it back. A group's page is built when it is tapped, in the frame before its slide starts (every
 * page built ahead held the main thread for seconds on the emulator; built while a finger rested on its row, a scroll
 * starting there stuttered). Each page keeps its scroll.
 */
@Composable
internal fun MotionPager(st: Studio, g: Geometry, fadePx: Float, bandPaint: androidx.compose.ui.graphics.Paint) {
    val look = LocalLook.current
    val scope = rememberCoroutineScope()
    val spec = androidx.compose.runtime.rememberUpdatedState(look.motion<Float>(MotionTokens.NAV_PUSH))
    val m = look.dp(SettingsTokens.MARGIN)
    val main = st.scrolls.getValue(Part.MOTION)
    // The group whose page is built (the last one pushed; one at a time). Never another while the built one shows: a group
    // tapped as the last one's page slides away waits until it has gone. [pending]: tapped, its page being built (its row
    // stays lit until the page moves).
    var built by remember { mutableStateOf<MotionGroup?>(null) }
    var pending by remember { mutableStateOf<MotionGroup?>(null) }
    val gone = { st.motionPush.value <= PAGE_GONE }
    val pushing = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    val push: (MotionGroup) -> Unit = remember(st, scope) {
        { grp ->
            if (st.motionPush.targetValue < 0.5f) {
                st.motionRole = null
                pushing[0]?.cancel()
                pending = grp
                pushing[0] = scope.launch {
                    try {
                        if (built !== grp && !gone()) androidx.compose.runtime.snapshotFlow { gone() }.first { it }
                        val ready = built === grp
                        built = grp
                        st.motionGroup = grp
                        // A page not built yet is built in the next frame and slides from the one after: built in its slide's
                        // first frame, it cost that frame (and the slide's start a jump).
                        if (!ready) { androidx.compose.runtime.withFrameNanos { }; androidx.compose.runtime.withFrameNanos { } }
                        st.motionGroup = grp   // (the last page's slide may have ended meanwhile, letting go of its group)
                        pending = null
                        st.motionPush.animateTo(1f, spec.value)
                    } catch (_: kotlinx.coroutines.CancellationException) {
                    } finally { if (pending === grp) pending = null }
                }
            }
        }
    }
    val lit: () -> MotionGroup? = remember { { pending } }
    val pop: () -> Unit = remember(st, scope) {
        {
            st.motionRole = null
            pushing[0]?.cancel()
            scope.launch {
                try {
                    st.motionPush.animateTo(0f, spec.value)
                    if (pushing[0]?.isActive != true) st.motionGroup = null
                } catch (_: kotlinx.coroutines.CancellationException) { }   // pushed again on the way: it stays
            }
        }
    }
    SideEffect { st.popGroup = pop }
    GroupBack(st, pop)
    // Back at the tiles some other way with a group's page in (home brought the settings back): Motion itself next time.
    LaunchedEffect(st) {
        androidx.compose.runtime.snapshotFlow { st.open }.collect { open ->
            if (open != Part.MOTION) pushing[0]?.cancel()
            if (open != Part.MOTION && st.motionGroup != null) { st.motionPush.snapTo(0f); st.motionGroup = null; st.motionRole = null }
        }
    }
    MotionSampleLoop(st)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()
            .graphicsLayer {
                val k = st.motionPush.value.coerceIn(0f, 1f)
                translationX = -g.w / 3f * k
                alpha = 1f - k
            }
            .topFade(main, g.unit, fadePx, bandPaint)) {
            Column(Modifier.fillMaxSize().iosScroll(main).padding(horizontal = m)) {
                Spacer(Modifier.height(look.dp(26f * g.unit)))
                MotionPage(push, lit)
                // Room below the last row for the Apply bar.
                Spacer(Modifier.height(look.dp(24f * g.unit + g.navPx + g.inset + g.barRoom)))
            }
        }
        built?.let { grp -> androidx.compose.runtime.key(grp) { GroupPage(st, g, grp, fadePx, bandPaint) } }
    }
}

/** The system's Back on a group's page: back to Motion. Its own composable: only it composes again as a group comes or goes. */
@Composable
private fun GroupBack(st: Studio, pop: () -> Unit) {
    val on by remember(st) { androidx.compose.runtime.derivedStateOf { st.open == Part.MOTION && st.motionGroup != null } }
    BackHandler(enabled = on) { pop() }
}

/** One group's page: its animations, then the numbers that shape them; it slides in over Motion and back. */
@Composable
private fun GroupPage(st: Studio, g: Geometry, grp: MotionGroup, fadePx: Float, bandPaint: androidx.compose.ui.graphics.Paint) {
    val look = LocalLook.current
    val m = look.dp(SettingsTokens.MARGIN)
    val scroll = st.motionScrolls.getValue(grp)
    // Its groups rise in with its push, a beat apart (as a page's do with its opening).
    val shown: () -> Float = remember(st, grp) { { if (st.motionGroup === grp) st.motionPush.value.coerceIn(0f, 1f) else 0f } }
    val counter = remember { GroupCounter() }
    counter.n = 0
    CompositionLocalProvider(LocalShown provides shown, LocalGroups provides counter) {
        // (Over Motion's page by its place, last in their box: nothing here is read in composition, so a push or a pop
        // composes nothing.)
        Box(Modifier.fillMaxSize()
            .graphicsLayer {
                val on = st.motionGroup === grp
                val k = if (on) st.motionPush.value else 0f
                // Out of sight unless it is the one pushed (moved, never laid out again).
                translationX = if (!on) g.w * 3f else (1f - k.coerceIn(0f, 1f)) * g.w * 0.4f
                alpha = (k / 0.7f).coerceIn(0f, 1f)
            }
            .topFade(scroll, g.unit, fadePx, bandPaint)) {
            Column(Modifier.fillMaxSize().iosScroll(scroll).padding(horizontal = m)) {
                Spacer(Modifier.height(look.dp(26f * g.unit)))
                GroupRoles(st, grp)
                Spacer(Modifier.height(look.dp(24f * g.unit + g.navPx + g.inset + g.barRoom)))
            }
        }
    }
}

@Composable
private fun GroupRoles(st: Studio, grp: MotionGroup) {
    val curves = grp.roles.filter { it.key in MotionTokens.CURVES }
    val numbers = grp.roles.filter { it.key in MotionTokens.NUMBERS }
    Group(null, grp.description + " Tap one to change it: the picture above plays it.") {
        for (r in curves) androidx.compose.runtime.key(r.key) { RoleRow(st, r) }
    }
    if (numbers.isNotEmpty()) Group("Amounts", "Numbers that shape these animations: how far, how blurred, how long between.") {
        for (r in numbers) androidx.compose.runtime.key(r.key) { RoleRow(st, r) }
    }
}

/**
 * Motion's groups as rows (on Motion itself): a group's name, what it covers, how many of its animations you changed.
 * Tapped, it stays lit while its page is built ([lit]), and goes out as the page slides in.
 */
@Composable
private fun GroupRow(grp: MotionGroup, push: (MotionGroup) -> Unit, lit: () -> MotionGroup?) {
    val look = LocalLook.current
    val draft = LocalDraft.current
    // Counted through a derived state: every step of a slider changes the draft, and only a changed count should compose.
    val changed by remember(draft, grp, look.version) {
        androidx.compose.runtime.derivedStateOf { grp.roles.count { draft.motionChanged(draft.motionId(), it.key) } }
    }
    val held by remember(grp) { androidx.compose.runtime.derivedStateOf { lit() === grp } }
    val counter = LocalRowCounter.current
    val first = remember { counter == null || counter.n++ == 0 }
    var pressed by remember { mutableStateOf(false) }
    val down = pressed || held
    val press by animateFloatAsState(if (down) 1f else 0f, look.motion(if (down) MotionTokens.PRESS_IN else MotionTokens.PRESS_OUT), label = "press")
    val pad = look.dp(SettingsTokens.ROW_PADDING)
    val separator = look.color(SettingsTokens.ROW_SEPARATOR)
    val pressFill = Color(Appearance.pressFill)
    val onClick = remember(grp, push) { { push(grp) } }
    Row(Modifier.fillMaxWidth().heightIn(min = look.dp(SettingsTokens.ROW_HEIGHT))
        .drawBehind {
            if (press > 0.001f) drawRect(pressFill.copy(alpha = pressFill.alpha * press))
            if (!first) drawRect(separator, Offset(pad.toPx(), 0f), Size(size.width - pad.toPx(), 1f))
        }
        .pointerInput(grp) { rowTap({ pressed = it }, onClick) }
        .padding(horizontal = pad, vertical = look.dp(10f * look.unit)),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            BasicText(grp.title, style = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_LABEL)))
            BasicText(grp.description, style = look.text(SettingsTokens.ROW_DETAIL, look.color(SettingsTokens.ROW_DETAIL_COLOR)))
            // How many of its animations you changed: a line of its own that opens and closes, the count rolling (beside the
            // words, it made them wrap again). It keeps its last count while it closes.
            val last = remember { intArrayOf(1) }
            if (changed > 0) last[0] = changed
            Reveal(changed > 0) {
                Rolling("${last[0]} changed", look.text(SettingsTokens.ROW_DETAIL, look.color(SettingsTokens.ACCENT)), Modifier)
            }
        }
        Spacer(Modifier.width(look.dp(12f * look.unit)))
        BasicText("›", style = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_DETAIL_COLOR)))
    }
}

/** How far in a group's page may be and still count as gone (its alpha under a 255th). */
private const val PAGE_GONE = 0.002f

/**
 * The most time one frame moves Motion's loops (the cards' dots, the picture's demo and samples; s): a stalled frame (a
 * page or an editor being built) pauses them instead of making them jump ahead.
 */
internal const val LOOP_STEP_MAX = 1f / 30f

/**
 * One animation: its curve drawn small, its name, its value in words (in the accent once changed); tapped, its editor opens
 * in place under it (one at a time) and the picture plays it.
 */
@Composable
private fun RoleRow(st: Studio, role: MotionRole) {
    val look = LocalLook.current
    val draft = LocalDraft.current
    val key = role.key
    // Read through derived states: every step of a slider changes the draft, and only the row whose value it changed should
    // compose again (every row of the page did, each step).
    val v = look.version
    val raw = remember(draft, key, v) { androidx.compose.runtime.derivedStateOf { draft.motionRaw(draft.motionId(), key) } }
    val changedState = remember(draft, key, v) { androidx.compose.runtime.derivedStateOf { draft.motionChanged(draft.motionId(), key) } }
    val changed by changedState
    val glyph = remember(draft, key, v) {
        androidx.compose.runtime.derivedStateOf { if (key in MotionTokens.CURVES) draft.motionCurve(draft.motionId(), CurveKey(key)) else null }
    }
    val open by remember(st, key) { androidx.compose.runtime.derivedStateOf { st.motionRole == key } }
    val counter = LocalRowCounter.current
    val first = remember { counter == null || counter.n++ == 0 }
    var pressed by remember { mutableStateOf(false) }
    // Its editor is built when tapped, in the frame before it opens (built in its opening's first frame, it cost that frame
    // and the opening a jump); the row stays lit meanwhile. Kept a little after it closes, for a quick change of mind.
    var warm by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }
    val down = pressed || opening
    val press by animateFloatAsState(if (down) 1f else 0f, look.motion(if (down) MotionTokens.PRESS_IN else MotionTokens.PRESS_OUT), label = "press")
    // Changed, its value and curve take the accent over a moment (and lose it the same way on Reset or Discard), in drawing.
    val ink = animateFloatAsState(if (changed) 1f else 0f, look.motion(MotionTokens.CC_TOGGLE), label = "changed")
    val accent = look.color(SettingsTokens.ACCENT)
    val detail = look.color(SettingsTokens.ROW_DETAIL_COLOR)
    val inkColor = remember(accent, detail) { androidx.compose.ui.graphics.ColorProducer { mix(detail, accent, ink.value.coerceIn(0f, 1f)) } }
    val pad = look.dp(SettingsTokens.ROW_PADDING)
    val separator = look.color(SettingsTokens.ROW_SEPARATOR)
    val pressFill = Color(Appearance.pressFill)
    val scope = rememberCoroutineScope()
    val toggle: () -> Unit = remember(st, key, scope) {
        {
            if (st.motionRole == key) st.motionRole = null
            else if (warm) st.motionRole = key
            else {
                warm = true
                opening = true
                scope.launch {
                    try {
                        androidx.compose.runtime.withFrameNanos { }
                        androidx.compose.runtime.withFrameNanos { }
                        st.motionRole = key
                    } finally { opening = false }
                }
            }
        }
    }
    LaunchedEffect(open, warm) { if (!open && warm && !opening) { kotlinx.coroutines.delay(1500); warm = false } }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = look.dp(SettingsTokens.ROW_HEIGHT))
            .drawBehind {
                if (press > 0.001f) drawRect(pressFill.copy(alpha = pressFill.alpha * press))
                if (!first) drawRect(separator, Offset(pad.toPx(), 0f), Size(size.width - pad.toPx(), 1f))
            }
            .pointerInput(key) { rowTap({ pressed = it }, toggle) }
            .padding(horizontal = pad, vertical = look.dp(8f * look.unit)),
            verticalAlignment = Alignment.CenterVertically) {
            RoleGlyph(key in MotionTokens.CURVES, { glyph.value }, { ink.value })
            Spacer(Modifier.width(look.dp(12f * look.unit)))
            BasicText(role.title, style = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_LABEL)), maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(look.dp(8f * look.unit)))
            RoleValue(st, key, raw, look.text(SettingsTokens.ROW_DETAIL, detail), inkColor)
        }
        OpenReveal(open, warm) { RoleEditor(st, role) }
    }
}

/**
 * An animation's value in its row, in a composable of its own: a slider's every step changes it, and only this composes
 * again (the whole row did).
 */
@Composable
private fun RoleValue(st: Studio, key: String, raw: androidx.compose.runtime.State<Value?>, style: androidx.compose.ui.text.TextStyle,
                      ink: androidx.compose.ui.graphics.ColorProducer) {
    Rolling(valueText(key, raw.value), style, Modifier, color = ink, instant = st.motionLive)
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
 * An animation's curve drawn small on a tile (a number: three rising bars); in the accent once you changed it ([ink], 0..1).
 * Both are read in drawing: a change draws the tile again and composes nothing.
 */
@Composable
private fun RoleGlyph(isCurve: Boolean, curve: () -> Curve?, ink: () -> Float) {
    val look = LocalLook.current
    val side = 28f * look.unit
    val fill = look.color(SettingsTokens.CHIP_FILL)
    val accent = look.color(SettingsTokens.ACCENT)
    val detail = look.color(SettingsTokens.ROW_DETAIL_COLOR)
    val unit = look.unit
    Box(Modifier.size(look.dp(side)).drawWithCache {
        val path = androidx.compose.ui.graphics.Path()
        val ins = size.width * 0.2f
        val w = size.width - ins * 2f
        val h = size.height - ins * 2f
        val curve = if (isCurve) curve() else null
        if (curve != null) {
            val d = CurveFit.settleTime(curve, 0.01f).coerceAtLeast(0.05f)
            for (i in 0..24) {
                val v = CurveFit.at(curve, d * i / 24f).coerceIn(-0.2f, 1.35f)
                val x = ins + w * i / 24f
                val y = ins + h - v * h * 0.74f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
        }
        onDrawBehind {
            val c = mix(detail, accent, ink().coerceIn(0f, 1f))
            drawRoundRect(fill, cornerRadius = CornerRadius(size.width * 0.26f))
            if (curve != null) drawPath(path, c, style = Stroke(1.6f * unit, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            else for (i in 0..2) {
                val bw = w / 5f
                val bh = h * (0.35f + 0.3f * i)
                drawRoundRect(c, Offset(ins + i * bw * 2f, ins + h - bh), Size(bw, bh), CornerRadius(bw / 2f))
            }
        }
    })
}

/** An animation's value in words: a spring's duration and bounce, a curve's length, a number in its unit. */
private fun valueText(key: String, v: Value?): String = when (v) {
    is Value.CurveV -> when (val c = v.curve) {
        is Curve.Spring -> {
            val b = CurveFit.bounceOf(c.damping)
            "Spring · " + seconds(c.response) + if (abs(b) >= 0.005f) " · " + percent(b) else ""
        }
        is Curve.Ease -> "Curve · " + millis(c.ms)
    }
    is Value.Number -> NUMBERS[key]?.text?.invoke(v.v) ?: fmt("%.2f", v.v)
    else -> ""
}

private fun fmt(f: String, v: Float) = String.format(java.util.Locale.ROOT, f, v)
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

/** An animation's editor, under its row: a curve's (spring or bezier) or a number's. */
@Composable
private fun RoleEditor(st: Studio, role: MotionRole) {
    val draft = LocalDraft.current
    val preset = draft.motionId()
    when (val v = draft.motionRaw(preset, role.key)) {
        is Value.CurveV -> CurveEditor(st, draft, preset, role, v.curve)
        is Value.Number -> NumberEditor(st, draft, preset, role, v)
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

/**
 * A curve's editor (docs/PLAN_MOTION.md): what it is and where its value comes from, Spring | Curve (switching fits the one
 * to the other), the graph (its handle or handles dragged), a spring's duration and bounce or a curve's length, what that
 * makes ("settles in, goes past"), shapes to start from, and Slow (the sample at a quarter speed), Reset, Done. Everything
 * waits for Apply and plays on the picture at once.
 */
@Composable
private fun CurveEditor(st: Studio, draft: Draft, preset: String, role: MotionRole, curve: Curve) {
    val look = LocalLook.current
    val pad = look.dp(SettingsTokens.ROW_PADDING)
    val key = role.key
    val own = (draft.motionOwn(preset, key) as? Value.CurveV)?.curve
    val scope = rememberCoroutineScope()
    var fitting by remember { mutableStateOf(false) }
    // Read when used (a lambda kept by a control may be from an earlier composition).
    fun current(): Curve? = (draft.motionRaw(draft.motionId(), key) as? Value.CurveV)?.curve
    fun set(c: Curve) = draft.chooseMotionValue(draft.motionId(), key, Value.CurveV(c))
    val detail = look.color(SettingsTokens.ROW_DETAIL_COLOR)
    Column(Modifier.fillMaxWidth().padding(start = pad, end = pad, bottom = look.dp(10f * look.unit))) {
        BasicText(role.description, style = look.text(SettingsTokens.ROW_DETAIL, look.color(SettingsTokens.ROW_LABEL)))
        Rolling(sourceLine(draft, preset, key), look.text(SettingsTokens.ROW_DETAIL, detail), Modifier)
        Spacer(Modifier.height(look.dp(10f * look.unit)))
        // Switched back without touching the other kind: exactly what it was (fitted back, it read the same and counted as a
        // change).
        val switched = remember { arrayOfNulls<Curve>(2) }
        ChoiceChips(KINDS, if (curve is Curve.Spring) 0 else 1) { i ->
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
        // While a slider is dragged the graph follows it at once (it morphs only into a shape, Reset or the other kind).
        var sliding by remember { mutableStateOf(false) }
        val slide: (Boolean) -> Unit = remember(st) { { sliding = it; st.motionLive = it } }
        val live: () -> Boolean = remember { { sliding } }
        CurveGraph(st, key, curve, own?.takeIf { it != curve }, draft.motionSpeed(preset), live) { set(it) }
        // Spring | Curve switched: the duration's slider stays (its knob glides to the other kind's place), the bounce's and
        // the shapes of the kind that goes fold away as the other's open (the last spring shown while it folds).
        val lastSpring = remember { arrayOfNulls<Curve.Spring>(1) }
        val lastEase = remember { arrayOfNulls<Curve.Ease>(1) }
        if (curve is Curve.Spring) lastSpring[0] = curve
        if (curve is Curve.Ease) lastEase[0] = curve
        val durText = when (curve) { is Curve.Spring -> seconds(curve.response); is Curve.Ease -> millis(curve.ms) }
        val durPos = when (curve) {
            is Curve.Spring -> logPos(curve.response, SPRING_MIN, SPRING_MAX)
            is Curve.Ease -> logPos(curve.ms, EASE_MIN, EASE_MAX)
        }
        EditorSlider("Duration", durText, durPos, slide) { t ->
            when (val now = current()) {
                is Curve.Spring -> set(Curve.Spring(snapTo(logVal(t, SPRING_MIN, SPRING_MAX), 0.01f).coerceIn(SPRING_MIN, SPRING_MAX), now.damping))
                is Curve.Ease -> set(now.copy(ms = snapTo(logVal(t, EASE_MIN, EASE_MAX), 10f).coerceIn(EASE_MIN, EASE_MAX)))
                null -> Unit
            }
        }
        OpenReveal(curve is Curve.Spring, false) {
            val s = (curve as? Curve.Spring) ?: lastSpring[0] ?: return@OpenReveal
            val b = CurveFit.bounceOf(s.damping)
            EditorSlider("Bounce", if (abs(b) < 0.005f) "None" else percent(b), (b - BOUNCE_MIN) / (BOUNCE_MAX - BOUNCE_MIN), slide) { t ->
                val now = current() as? Curve.Spring ?: return@EditorSlider
                set(Curve.Spring(now.response, CurveFit.dampingOf(snapTo(BOUNCE_MIN + t * (BOUNCE_MAX - BOUNCE_MIN), 0.01f).coerceIn(BOUNCE_MIN, BOUNCE_MAX))))
            }
        }
        // What it makes, in words (and the preset's own pace, when Fine-tune changed it).
        val past = when (curve) {
            is Curve.Spring -> MotionAdjust.overshoot(curve.damping).toFloat()
            is Curve.Ease -> ((0..40).maxOf { CurveFit.at(curve, curve.ms / 1000f * it / 40f) } - 1f).coerceAtLeast(0f)
        }
        val speed = draft.motionSpeed(preset)
        val info = "Settles in " + seconds(CurveFit.settleTime(curve)) + (if (past >= 0.005f) " · goes ${(past * 100).roundToInt()} % past" else "") +
            (if (abs(speed - 1f) > 0.001f) " · plays at ${(speed * 100).roundToInt()} % speed" else "")
        Spacer(Modifier.height(look.dp(4f * look.unit)))
        Rolling(info, look.text(SettingsTokens.ROW_DETAIL, detail), Modifier, instant = st.motionLive)
        Spacer(Modifier.height(look.dp(10f * look.unit)))
        // Shapes to start from (each keeps the duration); the other kind's fold away on a switch.
        OpenReveal(curve is Curve.Spring, false) {
            val s = (curve as? Curve.Spring) ?: lastSpring[0] ?: return@OpenReveal
            val chosen = SPRING_SHAPES.indexOfFirst { abs(CurveFit.bounceOf(s.damping) - it.second) < 0.005f }
            ChoiceChips(SPRING_SHAPE_NAMES, if (curve is Curve.Spring) chosen else -1) { i ->
                val now = current() as? Curve.Spring ?: return@ChoiceChips
                set(Curve.Spring(now.response, CurveFit.dampingOf(SPRING_SHAPES[i].second)))
            }
        }
        OpenReveal(curve is Curve.Ease, false) {
            val e = (curve as? Curve.Ease) ?: lastEase[0] ?: return@OpenReveal
            val chosen = EASE_SHAPES.indexOfFirst { (_, s) -> abs(e.x1 - s[0]) < 0.005f && abs(e.y1 - s[1]) < 0.005f && abs(e.x2 - s[2]) < 0.005f && abs(e.y2 - s[3]) < 0.005f }
            ChoiceChips(EASE_SHAPE_NAMES, if (curve is Curve.Ease) chosen else -1) { i ->
                val now = current() as? Curve.Ease ?: return@ChoiceChips
                val s = EASE_SHAPES[i].second
                set(Curve.Ease(s[0], s[1], s[2], s[3], now.ms))
            }
        }
        EditorButtons(st, draft, key)
    }
}

/** A number's editor: what it is, where its value comes from, its slider, Reset and Done. */
@Composable
private fun NumberEditor(st: Studio, draft: Draft, preset: String, role: MotionRole, v: Value.Number) {
    val look = LocalLook.current
    val pad = look.dp(SettingsTokens.ROW_PADDING)
    val spec = NUMBERS[role.key] ?: NumberSpec(v.v * 0.5f, v.v * 1.5f + 1f, 0.01f) { fmt("%.2f", it) }
    val detail = look.color(SettingsTokens.ROW_DETAIL_COLOR)
    Column(Modifier.fillMaxWidth().padding(start = pad, end = pad, bottom = look.dp(10f * look.unit))) {
        BasicText(role.description, style = look.text(SettingsTokens.ROW_DETAIL, look.color(SettingsTokens.ROW_LABEL)))
        Rolling(sourceLine(draft, preset, role.key), look.text(SettingsTokens.ROW_DETAIL, detail), Modifier)
        EditorSlider(role.title, spec.text(v.v), (v.v - spec.min) / (spec.max - spec.min), { st.motionLive = it }) { t ->
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
    Spacer(Modifier.height(look.dp(4f * look.unit)))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        EditorButton(if (st.sampleSlow) "Full speed" else "Slow", true) { st.sampleSlow = !st.sampleSlow }
        Spacer(Modifier.weight(1f))
        // Reset keeps its place and fades in and out with the change (nothing beside it moves).
        EditorButton("Reset", draft.motionChanged(draft.motionId(), key)) {
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

/** A value set with a slider inside an editor: its name and the value now above, the slider ([position] 0..1) below. */
@Composable
private fun EditorSlider(title: String, value: String, position: Float, onDrag: ((Boolean) -> Unit)? = null, onChange: (Float) -> Unit) {
    val look = LocalLook.current
    Column(Modifier.fillMaxWidth().padding(top = look.dp(6f * look.unit))) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText(title, style = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_LABEL)), modifier = Modifier.weight(1f))
            Rolling(value, look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_DETAIL_COLOR)), Modifier,
                instant = LocalStudio.current?.motionLive == true)
        }
        SettingsSlider(position.coerceIn(0f, 1f), onDrag, onChange)
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
private fun CurveGraph(st: Studio, key: String, curve: Curve, own: Curve?, speed: Float, live: () -> Boolean, onChange: (Curve) -> Unit) {
    val look = LocalLook.current
    val fill = look.color(SettingsTokens.CHIP_FILL)
    val accent = look.color(SettingsTokens.ACCENT)
    val line = look.color(SettingsTokens.ROW_DETAIL_COLOR)
    val knob = look.color(SettingsTokens.TOGGLE_KNOB)
    val unit = look.unit
    val change = androidx.compose.runtime.rememberUpdatedState(onChange)
    val now = androidx.compose.runtime.rememberUpdatedState(curve)
    var held by remember { mutableStateOf<Float?>(null) }
    // Long enough for the curve to settle, the preset's own behind it, and the handle (a spring's peak can come later).
    fun span(c: Curve) = maxOf(CurveFit.settleTime(c, 0.005f), if (c is Curve.Spring) CurveFit.handleOf(c).first * 1.06f else 0f)
    val want = maxOf(span(curve), own?.let { span(it) } ?: 0f, 0.25f) * 1.12f
    val axis = animateFloatAsState(held ?: want, look.motion(MotionTokens.CC_LEVEL), label = "axis")
    // What shows: [from] (a function of time: the last curve, or the blend that showed when this one came) into [target];
    // the handles from where they showed. Set when a new curve comes (morph back to 0), read in drawing.
    val morph = remember { Animatable(1f) }
    val levelSpec = androidx.compose.runtime.rememberUpdatedState(look.motion<Float>(MotionTokens.CC_LEVEL))
    val target = remember { arrayOf(curve) }
    val from = remember { arrayOf<(Float) -> Float>({ t -> CurveFit.at(curve, t) }) }
    val fromHandles = remember { arrayOf(Handles.of(curve)) }
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
    if (own != null) lastOwn[0] = own
    val ownK = animateFloatAsState(if (own != null) 1f else 0f, look.motion(if (own != null) MotionTokens.APPEAR_FADE else MotionTokens.DISAPPEAR_FADE), label = "own")
    val path = remember { androidx.compose.ui.graphics.Path() }
    val ownPath = remember { androidx.compose.ui.graphics.Path() }
    val dash = remember(unit) { androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(5f * unit, 5f * unit)) }
    val padX = 16f * unit
    val padY = 14f * unit
    val yMin = -0.12f
    val yMax = 1.45f
    Canvas(Modifier.fillMaxWidth().height(look.dp(160f * unit)).pointerInput(key) {
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
            val tr = (t * speed).coerceAtMost(ax)
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
 * What the picture plays while a group's page is in (docs/PLAN_MOTION.md, C4b): the open animation, else the group's first;
 * its own run on the draft's curve (as it would be applied: the preset's speed and bounce too), a pause, the way back on
 * its partner (or at once), a pause, again. Slow plays it at a quarter speed. The picture draws it (MotionSamples), the
 * editor's graph runs a dot with it.
 */
@Composable
private fun MotionSampleLoop(st: Studio) {
    val draft = LocalDraft.current
    val playing = remember(st) {
        androidx.compose.runtime.derivedStateOf {
            if (st.open == Part.MOTION && !st.p.isRunning && st.motionPush.targetValue >= 0.5f) st.motionRole ?: st.motionGroup?.roles?.firstOrNull()?.key else null
        }
    }
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
            // (The preset is read at each run: it cannot change while a group's page is in, but its speed and bounce can.)
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
 * Motion (docs/PLAN_MOTION.md): the animation presets as cards, each playing its curve (the picture of home above plays the
 * chosen one: an app opening and closing, a banner dropping in), the whole preset's speed and bounce, and every animation by
 * group (each group's page pushed over this one). Waits for Apply like the rest.
 */
@Composable
private fun MotionPage(push: (MotionGroup) -> Unit, lit: () -> MotionGroup?) {
    val draft = LocalDraft.current
    val look = LocalLook.current
    val st = LocalStudio.current
    val all = remember(look.version) { Design.motionPresets() }
    val chosen = draft.motionId()
    // Presets made for testing show when Developer asks for them, or while one is in use; the default first, tests last.
    val presets = all.filter { !it.test || SettingsPrefs.testPresets || it.id == chosen || it.id == Design.motionPresetId }
        .sortedWith(compareBy({ it.test }, { it.id != Design.DEFAULT_MOTION }, { it.name }))
    // The cards' dots run on one clock while the page is open and at rest (read in drawing only).
    val clock = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    LaunchedEffect(st) {
        androidx.compose.runtime.snapshotFlow { st == null || (st.open == Part.MOTION && !st.p.isRunning && st.motionPush.targetValue < 0.5f) }.collectLatest { open ->
            if (!open) return@collectLatest
            // Frame by frame, a long frame counting as a short one: the dots pause through a stall, never jump.
            var last = androidx.compose.runtime.withFrameNanos { it }
            while (true) {
                val now = androidx.compose.runtime.withFrameNanos { it }
                clock.floatValue += ((now - last) / 1e9f).coerceAtMost(LOOP_STEP_MAX)
                last = now
            }
        }
    }
    Group("Presets", "How everything moves. The picture above plays the one you choose.") {
        PresetCards(presets, draft, clock)
    }
    FineTune(draft)
    Group("Every animation", "Each one can be changed on its own: a spring or a curve, played on the picture as you change it.") {
        for (grp in MOTION_GROUPS) androidx.compose.runtime.key(grp.title) { GroupRow(grp, push, lit) }
    }
}

/**
 * The presets side by side, each drawing the curve a banner drops in on (a motion that bounces, so a preset's bounce shows as
 * well as its pace; iOS 27's app opening hardly overshoots), at its speed and bounce as it would be applied, with a dot
 * running along it on its own timing, again and again; the chosen one ringed in the accent. One time axis for all of
 * them, so a quicker preset rises sooner and a slower one later (it glides to fit the slowest). Two share the row; more
 * keep that width and scroll sideways, the next one peeking in.
 */
@Composable
private fun PresetCards(presets: List<Design.ThemeInfo>, draft: Draft, clock: androidx.compose.runtime.FloatState) {
    val look = LocalLook.current
    val pad = look.dp(SettingsTokens.ROW_PADDING)
    val gap = look.dp(12f * look.unit)
    // Derived: every step of a slider changes the draft; the cards compose again only when one of their curves changed, and
    // not at all while a group's page covers them (editing a banner's curve made them sample theirs again at every step).
    val st = LocalStudio.current
    val held = remember { arrayOfNulls<List<Curve>>(1) }
    val curves by remember(presets, draft, look.version) {
        androidx.compose.runtime.derivedStateOf {
            val covered = st != null && st.motionPush.targetValue >= 0.5f
            held[0]?.takeIf { covered } ?: presets.map { draft.motionCurve(it.id, MotionTokens.BANNER_IN) }.also { held[0] = it }
        }
    }
    val runs = remember(curves) { curves.map { CurveRun.of(it) } }
    val axis = animateFloatAsState(maxOf(0.6f, runs.maxOfOrNull { it.duration } ?: 0.6f) * 1.08f, look.motion(MotionTokens.SETTINGS_FOCUS), label = "axis")
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val room = maxWidth - pad * 2
        val cardW = if (presets.size <= 2) (room - gap) / 2 else room * 0.44f
        val scroll = rememberScrollState()
        Row(Modifier.fillMaxWidth().then(if (presets.size > 2) Modifier.horizontalScroll(scroll) else Modifier).padding(pad),
            horizontalArrangement = Arrangement.spacedBy(gap)) {
            presets.forEachIndexed { i, p ->
                androidx.compose.runtime.key(p.id) { PresetCard(p, runs[i], draft, clock, axis, Modifier.width(cardW)) }
            }
        }
    }
}

/** How long a preset's dot rests at the end of its curve before it runs again (s). */
private const val PRESET_DOT_REST = 0.7f

@Composable
private fun PresetCard(p: Design.ThemeInfo, run: CurveRun, draft: Draft, clock: androidx.compose.runtime.FloatState,
                       axis: androidx.compose.runtime.State<Float>, modifier: Modifier) {
    val look = LocalLook.current
    val accent = look.color(SettingsTokens.ACCENT)
    val line = look.color(SettingsTokens.ROW_DETAIL_COLOR)
    val fill = look.color(SettingsTokens.CHIP_FILL)
    val chosen = draft.motionId() == p.id
    val k = animateFloatAsState(if (chosen) 1f else 0f, look.motion(MotionTokens.CC_TOGGLE), label = "chosen")
    var pressed by remember { mutableStateOf(false) }
    val press = animateFloatAsState(if (pressed) 1f else 0f, look.motion(if (pressed) MotionTokens.PRESS_IN else MotionTokens.PRESS_OUT), label = "press")
    val corner = look.pt(SettingsTokens.TILE_CORNER) * 0.7f
    val unit = look.unit
    Column(modifier.pressScale { 1f - 0.04f * press.value }
        .pointerInput(p.id) { rowTap({ pressed = it }) { draft.chooseMotion(p.id) } }) {
        Box(Modifier.fillMaxWidth().height(look.dp(84f * unit)).drawWithCache {
            val inset = 14f * unit
            val w = size.width - inset * 2f
            val h = size.height - inset * 2f
            fun yOf(v: Float) = inset + h - v.coerceIn(-0.2f, 1.25f) * h * 0.8f
            val ax = axis.value
            fun xOf(t: Float) = inset + (t / ax).coerceAtMost(1f) * w
            // The curve, made again only when it or the axis changes (never in a frame of the dot's).
            val path = androidx.compose.ui.graphics.Path()
            for (i in run.values.indices) {
                val x = xOf(i * run.step)
                val y = yOf(run.values[i])
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f * unit, 4f * unit))
            onDrawBehind {
                val kk = k.value
                drawRoundRect(fill, cornerRadius = CornerRadius(corner))
                if (kk > 0.001f) drawRoundRect(accent.copy(alpha = accent.alpha * kk.coerceIn(0f, 1f)), cornerRadius = CornerRadius(corner), style = Stroke(2.5f * unit))
                // Where it comes to rest.
                drawLine(line.copy(alpha = line.alpha * 0.4f), Offset(inset, yOf(1f)), Offset(inset + w, yOf(1f)), strokeWidth = 1f * unit, pathEffect = dash)
                val ink = mix(line, accent, kk.coerceIn(0f, 1f))
                drawPath(path, ink, style = Stroke(2.5f * unit, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                // The dot: along the curve on its own timing, a rest at its end, again (it fades out at the end of the rest
                // and in at the start, so it never jumps back).
                val loop = run.duration + PRESET_DOT_REST
                val t = clock.floatValue % loop
                val tt = t.coerceAtMost(run.duration)
                val a = minOf((t / 0.12f).coerceIn(0f, 1f), ((loop - t) / 0.25f).coerceIn(0f, 1f))
                if (a > 0.003f) drawCircle(ink.copy(alpha = ink.alpha * a), 4.5f * unit, Offset(xOf(tt), yOf(run.at(tt))))
            }
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
 * The whole preset at once (docs/PLAN_MOTION.md): its speed (every curve's time) and its bounce (how far whatever bounces
 * goes past its place), and back to the preset as it comes. Shown at once on the cards and the picture above; waits for
 * Apply. The speed's slider doubles and halves evenly either side of the preset's own.
 */
@Composable
private fun FineTune(draft: Draft) {
    val id = draft.motionId()
    val name = presetName(id)
    val v = LocalLook.current.version
    // Derived: every step of a slider in a group's editor changes the draft; these compose again only when they change.
    val speed by remember(draft, id, v) { androidx.compose.runtime.derivedStateOf { draft.motionSpeed(id) } }
    val bounce by remember(draft, id, v) { androidx.compose.runtime.derivedStateOf { draft.motionBounce(id) } }
    val n by remember(draft, id, v) { androidx.compose.runtime.derivedStateOf { draft.motionRoleChanges(id) } }
    val any by remember(draft, id, v) { androidx.compose.runtime.derivedStateOf { draft.motionChangeCount(id) > 0 } }
    Group("Fine-tune", "For $name as a whole: every animation faster or slower, every bounce further or calmer.") {
        SliderRow("Speed", "${(speed * 100).roundToInt()} %", speedToSlider(speed)) { t ->
            draft.chooseMotionNumber(id, MotionTokens.ADJUST_SPEED, sliderToSpeed(t))
        }
        SliderRow("Bounce", if (bounce < 0.001f) "None" else "${(bounce * 100).roundToInt()} %", bounce / MotionAdjust.BOUNCE_MAX) { t ->
            draft.chooseMotionNumber(id, MotionTokens.ADJUST_BOUNCE, Draft.snap(t * MotionAdjust.BOUNCE_MAX))
        }
        Reveal(any) {
            ActionRow("Reset all", "Back to $name as it comes" + if (n > 0) " ($n change" + (if (n == 1) "" else "s") + " to its animations)" else "") {
                draft.resetMotion(id)
            }
        }
    }
}

/** The speed slider's place (0..1) for a speed: 50 % at the start, the preset's own in the middle, 200 % at the end. */
private fun speedToSlider(speed: Float) = ((kotlin.math.ln(speed) / kotlin.math.ln(2f)) + 1f) / 2f

private fun sliderToSpeed(t: Float) = Draft.snap(Math.pow(2.0, (t * 2f - 1f).toDouble()).toFloat()).coerceIn(MotionAdjust.SPEED_MIN, MotionAdjust.SPEED_MAX)

