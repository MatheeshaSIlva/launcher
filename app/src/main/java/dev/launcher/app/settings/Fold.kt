package dev.launcher.app.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import dev.launcher.app.motion.MotionTokens

// Folds (docs/SETTINGS_NAVIGATION.md): what most people never need waits folded under one row that says what it holds, on
// the page it belongs to (opened in place, never a page deeper). More (finer control: fonts, size, a part's moments) and
// Advanced (the design's and the motion's own vocabulary: the palette's style, each animation's curve).

/** A fold's name among the folds: its page's part and what it holds ("LOOK/more", "MOTION/HOME", "HOME/motion"). */
internal fun foldId(part: Part, what: String) = "${part.name}/$what"

/**
 * A fold: a row named [title] that says what it holds ([detail]), its rows ([content]) folded away under it until asked for.
 * Tapped, they unfold in place on the appear role, the chevron turning with them; it stays as it was left, in the next
 * session too ([SettingsPrefs.openFolds]). Built when first opened and opened two frames later, the row lit meanwhile (built
 * in its opening's first frame, a page's rows cost that frame); one open when its page is built is built with it. A dot
 * before its name while something in it differs from the theme or the preset ([changed]). [id]: [foldId] (the search opens
 * the fold a row it found is in).
 */
@Composable
internal fun Fold(id: String, title: String, detail: String?, changed: Boolean = false, content: @Composable () -> Unit) {
    val look = LocalLook.current
    val st = LocalStudio.current
    val wanted by remember(id) { derivedStateOf { id in SettingsPrefs.openFolds } }
    var built by remember(id) { mutableStateOf(wanted) }
    var ready by remember(id) { mutableStateOf(wanted) }
    val open = wanted && ready
    LaunchedEffect(wanted) {
        if (wanted && !ready) {
            built = true
            withFrameNanos { }
            withFrameNanos { }
            ready = true
        }
    }
    // (The search waits for it to be open on screen before it scrolls to one of its rows.)
    LaunchedEffect(open) { st?.let { s -> s.foldsShown = if (open) s.foldsShown + id else s.foldsShown - id } }
    val turn by animateFloatAsState(if (open) 1f else 0f, look.motion(MotionTokens.APPEAR), label = "fold")
    val onClick = remember(id) { { SettingsPrefs.setFold(id, id !in SettingsPrefs.openFolds) } }
    BaseRow(title, detail, onClick, held = wanted && !ready, changed = changed) {
        BasicText("›", style = look.text(SettingsTokens.ROW_TITLE, look.color(SettingsTokens.ROW_DETAIL_COLOR)),
            modifier = Modifier.graphicsLayer { rotationZ = 90f * turn })
    }
    if (built) {
        // Every row in it has a hairline above (counted on their own: the group's count may have started again since the
        // group was composed, and the first of them would have taken itself for the group's first).
        val rows = remember { RowCounter().also { it.n = 1 } }
        // On iOS's fill for expanded content: the fold's rows read as its own, the next fold's row as the next.
        val fill = look.color(SettingsTokens.FOLD_FILL)
        Reveal(open) { CompositionLocalProvider(LocalRowCounter provides rows) { Column(Modifier.background(fill)) { content() } } }
    }
}
