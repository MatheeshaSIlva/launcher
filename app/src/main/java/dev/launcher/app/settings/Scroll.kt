package dev.launcher.app.settings

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
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
    /** A finger drags it or it is still settling (what moves with it can leave the accessibility tree meanwhile). */
    var moving by mutableStateOf(false)
        internal set
    val scroller = IosScroller({ offset = it }, { moving = false })
}

/**
 * Scrolls this element's single child by [state]: the child is measured at its full height and placed [IosScrollState.offset]
 * up. A drag past the touch slop scrolls (children's taps are cancelled then); a touch on a visibly moving list stops it and
 * taps nothing (iOS), and the finger takes over from there at once: a drag the other way, or a flick so quick that its
 * whole travel arrives with the lift (one batch of events), turns the list around (Matheesha: reversing a fling had to wait
 * for it to end; a flick that lifted before a separate move past the slop was taken for no drag).
 */
fun Modifier.iosScroll(state: IosScrollState): Modifier = this
    .pointerInput(state) {
        awaitEachGesture {
            val s = state.scroller
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val wasMoving = s.isMovingVisibly()
            if (wasMoving) { s.stop(); down.consume() } else if (s.isSettling) s.stop()
            val tracker = VelocityTracker()
            tracker.addPointerInputChange(down)
            val slop = viewConfiguration.touchSlop
            var dragging = false
            var travel = 0f
            while (true) {
                val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                // The touch-down itself again (its main pass, after this one consumed it to stop a moving list): it is no
                // sign of anyone else (taking it for one left a finger that stopped a fling unable to drag until it lifted).
                if (c.uptimeMillis == down.uptimeMillis && c.pressed) continue
                // Taken by something else first (a row of chips scrolling sideways): not ours.
                if (!dragging && c.isConsumed) break
                tracker.addPointerInputChange(c)
                val dy = c.position.y - c.previousPosition.y
                if (dragging) {
                    s.dragBy(-dy)
                    c.consume()
                } else {
                    travel += dy
                    if (kotlin.math.abs(travel) > slop) {
                        // From the slop on the content follows the finger 1:1 (it does not jump by the slop it took to decide).
                        dragging = true
                        state.moving = true
                        s.beginDrag()
                        s.dragBy(-(travel - kotlin.math.sign(travel) * slop))
                        c.consume()
                    }
                }
                if (!c.pressed) break
            }
            if (dragging) {
                s.endDrag(-tracker.calculateVelocity().y)
            } else {
                // A tap (or a touch that only stopped it): an overscroll left behind goes back; nothing moves now.
                if (!s.isSettling && (s.position < s.minPos || s.position > s.maxPos)) s.endDrag(0f)
                if (!s.isSettling) state.moving = false
            }
        }
    }
    .layout { measurable, constraints ->
        val p = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val viewport = constraints.maxHeight
        state.scroller.setBounds(0f, (p.height - viewport).toFloat(), viewport.toFloat())
        // In a layer of its own, moved by the layer: a scroll changes one property each frame; nothing is placed or
        // recorded again (placed by position, every row of a long page was drawn again into the page's list every frame).
        layout(constraints.maxWidth, viewport) { p.placeRelativeWithLayer(0, 0) { translationY = -state.offset.roundToInt().toFloat() } }
    }
