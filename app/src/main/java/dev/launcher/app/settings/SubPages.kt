package dev.launcher.app.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import dev.launcher.app.motion.MotionTokens
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Pages one level below a part's own page (docs/PLAN_SETTINGS_V2.md, "Look, calmer" and "Phone, calmer"): what most people
 * never change waits there, a row away. [title] is the header's while the page is in.
 */
internal enum class Sub(val part: Part, val title: String) {
    COLOURS(Part.LOOK, "Adjust colours"),
    FONTS(Part.LOOK, "Fonts"),
    SIZE(Part.LOOK, "Size"),
    DEVELOPER(Part.PHONE, "Developer"),
}

/**
 * A part's page and its sub-pages, as Motion's groups over Motion: a row tapped ([PushRow]) slides its page in from the right
 * as the part's own page gives way (a third of the way left, fading; `motion.nav.push`); Back, the round button or the
 * system's, slides it back. A sub-page is built when its row is tapped and slides from two frames later, the row lit
 * meanwhile (built in its slide's first frame, it cost that frame); each page keeps its scroll. [main] gets the push and
 * what is being pushed (its row stays lit), [page] the page to build and how to go back from it.
 */
@Composable
internal fun SubPager(
    st: Studio, g: Geometry, part: Part, fadePx: Float, bandPaint: androidx.compose.ui.graphics.Paint,
    main: @Composable (push: (Sub) -> Unit, lit: () -> Sub?) -> Unit,
    page: @Composable (sub: Sub, pop: () -> Unit) -> Unit,
) {
    val look = LocalLook.current
    val scope = rememberCoroutineScope()
    val spec = androidx.compose.runtime.rememberUpdatedState(look.motion<Float>(MotionTokens.NAV_PUSH))
    val m = look.dp(SettingsTokens.MARGIN)
    val mainScroll = st.scrolls.getValue(part)
    // The sub-page built (the last one pushed; one at a time, never another while it shows: one tapped as the last one's page
    // slides away waits until it has gone), and the one tapped whose page is being built.
    var built by remember { mutableStateOf<Sub?>(null) }
    var pending by remember { mutableStateOf<Sub?>(null) }
    val gone = { st.subPush.value <= PAGE_GONE }
    val pushing = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    val push: (Sub) -> Unit = remember(st, scope) {
        { s ->
            if (st.open == part && st.subPush.targetValue < 0.5f) {
                pushing[0]?.cancel()
                pending = s
                pushing[0] = scope.launch {
                    try {
                        if (built !== s && !gone()) androidx.compose.runtime.snapshotFlow { gone() }.first { it }
                        val ready = built === s
                        built = s
                        st.sub = s
                        if (!ready) { androidx.compose.runtime.withFrameNanos { }; androidx.compose.runtime.withFrameNanos { } }
                        st.sub = s   // (the last page's slide may have ended meanwhile, letting go of its sub-page)
                        pending = null
                        st.subPush.animateTo(1f, spec.value)
                    } catch (_: kotlinx.coroutines.CancellationException) {
                    } finally { if (pending === s) pending = null }
                }
            }
        }
    }
    val lit: () -> Sub? = remember { { pending } }
    val pop: () -> Unit = remember(st, scope) {
        {
            pushing[0]?.cancel()
            scope.launch {
                try {
                    st.subPush.animateTo(0f, spec.value)
                    if (pushing[0]?.isActive != true && st.sub?.part == part) st.sub = null
                } catch (_: kotlinx.coroutines.CancellationException) { }   // pushed again on the way: it stays
            }
        }
    }
    SideEffect { st.popSubs[part] = pop }
    SubBack(st, part, pop)
    // Back at the tiles some other way with a sub-page in (home brought the settings back): the part's own page next time.
    LaunchedEffect(st) {
        androidx.compose.runtime.snapshotFlow { st.open }.collect { open ->
            if (open != part) {
                pushing[0]?.cancel()
                if (st.sub?.part == part) { st.subPush.snapTo(0f); st.sub = null }
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()
            .graphicsLayer {
                val k = if (st.sub?.part == part) st.subPush.value.coerceIn(0f, 1f) else 0f
                translationX = -g.w / 3f * k
                alpha = 1f - k
                // Faded draw by draw, not through a page-sized buffer (as Motion's page; MotionWarmUp compiles its pipelines).
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .topFade(mainScroll, g.unit, fadePx, bandPaint)) {
            Column(Modifier.fillMaxSize().iosScroll(mainScroll).padding(horizontal = m)) {
                Spacer(Modifier.height(look.dp(26f * g.unit)))
                main(push, lit)
                // Room below the last row for the Apply bar.
                Spacer(Modifier.height(look.dp(24f * g.unit + g.navPx + g.inset + g.barRoom)))
            }
        }
        built?.let { s -> androidx.compose.runtime.key(s) { SubPage(st, g, s, fadePx, bandPaint) { page(s, pop) } } }
    }
}

/** The system's Back on a sub-page: back to its part's page. Its own composable: only it composes again as one comes or goes. */
@Composable
private fun SubBack(st: Studio, part: Part, pop: () -> Unit) {
    val on by remember(st, part) {
        androidx.compose.runtime.derivedStateOf { st.open == part && st.sub?.part == part && st.subPush.targetValue >= 0.5f }
    }
    BackHandler(enabled = on) { pop() }
}

/** One sub-page: it slides in over its part's page and back; its groups rise in with the push, a beat apart. */
@Composable
private fun SubPage(st: Studio, g: Geometry, s: Sub, fadePx: Float, bandPaint: androidx.compose.ui.graphics.Paint, content: @Composable () -> Unit) {
    val look = LocalLook.current
    val m = look.dp(SettingsTokens.MARGIN)
    val scroll = st.subScrolls.getValue(s)
    val shown: () -> Float = remember(st, s) { { if (st.sub === s) st.subPush.value.coerceIn(0f, 1f) else 0f } }
    val counter = remember { GroupCounter() }
    counter.n = 0
    CompositionLocalProvider(LocalShown provides shown, LocalGroups provides counter) {
        // (Nothing here is read in composition: a push or a pop composes nothing.)
        Box(Modifier.fillMaxSize()
            .graphicsLayer {
                val on = st.sub === s
                val k = if (on) st.subPush.value else 0f
                // Out of sight unless it is the one pushed (moved, never laid out again).
                translationX = if (!on) g.w * 3f else (1f - k.coerceIn(0f, 1f)) * g.w * 0.4f
                alpha = (k / 0.7f).coerceIn(0f, 1f)
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .topFade(scroll, g.unit, fadePx, bandPaint)) {
            Column(Modifier.fillMaxSize().iosScroll(scroll).padding(horizontal = m)) {
                Spacer(Modifier.height(look.dp(26f * g.unit)))
                content()
                Spacer(Modifier.height(look.dp(24f * g.unit + g.navPx + g.inset + g.barRoom)))
            }
        }
    }
}

/**
 * A row that opens [sub]: its name, what it holds now, the chevron. Tapped, it stays lit while its page is built ([lit]) and
 * goes out as the page slides in.
 */
@Composable
internal fun PushRow(title: String, detail: String?, sub: Sub, push: (Sub) -> Unit, lit: () -> Sub?) {
    val look = LocalLook.current
    val held by remember(sub) { androidx.compose.runtime.derivedStateOf { lit() === sub } }
    val onClick = remember(sub, push) { { push(sub) } }
    BaseRow(title, detail, onClick, held = held) {
        BasicText("›", style = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_DETAIL_COLOR)))
    }
}
