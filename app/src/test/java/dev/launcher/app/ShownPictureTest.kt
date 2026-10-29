package dev.launcher.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-037 step 2, slice 2a2, round 3: Notification Center's commit draws home's picture only while it is exactly what home
 * shows ([ShownPicture]). QA's case: home recorded mid-way through a new look's reveal had no clock where the screen showed one.
 */
class ShownPictureTest {
    private class Rec

    @Test fun aRecordingAtRestIsWhatShowsUntilHomeDrawsAgain() {
        val s = ShownPicture<Rec>()
        val p = Rec()
        s.recorded(p, atRest = true)
        assertTrue(s.isExactly(p))
        s.drew()
        assertFalse("home drew after it: stale", s.isExactly(p))
    }

    @Test fun aRecordingMidMotionNeverIs() {
        val s = ShownPicture<Rec>()
        val p = Rec()
        s.recorded(p, atRest = false)
        assertFalse(s.isExactly(p))
    }

    @Test fun aNewerRecordingReplacesTheOlder() {
        val s = ShownPicture<Rec>()
        val old = Rec()
        val new = Rec()
        s.recorded(old, atRest = true)
        s.recorded(new, atRest = true)
        assertFalse("the older one is not what shows", s.isExactly(old))
        assertTrue(s.isExactly(new))
        // Recorded mid-motion afterwards: neither is.
        s.recorded(Rec(), atRest = false)
        assertFalse(s.isExactly(new))
    }

    @Test fun noneIsNeverWhatShows() {
        val s = ShownPicture<Rec>()
        assertFalse(s.isExactly(null))
        s.recorded(null, atRest = true)
        assertFalse(s.isExactly(null))
    }
}
