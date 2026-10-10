package dev.launcher.app.settings

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import dev.launcher.app.motion.IosScroller
import kotlin.math.roundToInt

/**
 * A page's vertical scroll with the launcher's own iOS physics ([IosScroller]: rubber band past either end, UIScrollView's
 * deceleration, the overscroll spring), on the animation preset's values, instead of Android's stretch. [offset] is how far
 * the content is scrolled (negative while pulled down past its top).
 */
@Stable
class IosScrollState {
    var offset by mutableFloatStateOf(0f)
        private set
    val scroller = IosScroller({ offset = it })
}

/**
 * Scrolls this element's single child by [state]: the child is measured at its full height and placed [IosScrollState.offset]
 * up. A drag past the touch slop scrolls (children's taps are cancelled then); a touch on a visibly moving list stops it and
 * taps nothing (iOS).
 */
fun Modifier.iosScroll(state: IosScrollState): Modifier = this
    .pointerInput(state) {
        awaitEachGesture {
            val s = state.scroller
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (s.isMovingVisibly()) { s.stop(); down.consume() } else if (s.isSettling) s.stop()
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            // From the slop on the content follows the finger 1:1 (it does not jump by the slop it took to decide).
            val first = awaitVerticalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } ?: run {
                if (!s.isSettling && (s.position < s.minPos || s.position > s.maxPos)) s.endDrag(0f)
                return@awaitEachGesture
            }
            tracker.addPosition(first.uptimeMillis, first.position)
            s.beginDrag()
            verticalDrag(first.id) { change ->
                tracker.addPosition(change.uptimeMillis, change.position)
                s.dragBy(-change.positionChange().y)
                change.consume()
            }
            s.endDrag(-tracker.calculateVelocity().y)
        }
    }
    .layout { measurable, constraints ->
        val p = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val viewport = constraints.maxHeight
        state.scroller.setBounds(0f, (p.height - viewport).toFloat(), viewport.toFloat())
        layout(constraints.maxWidth, viewport) { p.placeRelative(0, -state.offset.roundToInt()) }
    }
