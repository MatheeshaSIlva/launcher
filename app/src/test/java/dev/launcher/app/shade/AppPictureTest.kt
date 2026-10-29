package dev.launcher.app.shade

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-037 step 2, slice 2a2, round 4 (QA P2): a pull over an app that has just opened. The picture taken at the pull showed the
 * app's launch screen (its starting window: icon on a plain colour); the app's own window drew during the hold, and the
 * commit drew the launch screen inside the sheet. A picture counts as what the app shows only while its screen has not
 * changed since it was taken ([BackdropSource.App.outdated]), asked again at the commit.
 */
class AppPictureTest {
    @Test fun aPictureOfTheLaunchScreenIsOutdatedOnceTheAppDraws() {
        val takenAt = 1_000L          // the pull: the launch screen still shows
        assertFalse("nothing changed yet", BackdropSource.App.outdated(changedAt = 900L, takenAt = takenAt))
        // The app's window draws its list during the hold (its first content events).
        assertTrue(BackdropSource.App.outdated(changedAt = 1_400L, takenAt = takenAt))
    }

    @Test fun anAppThatNeverChangedKeepsItsPicture() {
        assertFalse(BackdropSource.App.outdated(changedAt = null, takenAt = 5_000L))
        assertFalse("changed at the very moment it was taken", BackdropSource.App.outdated(changedAt = 5_000L, takenAt = 5_000L))
    }

    @Test fun theHandedPictureIsAskedAgainNotFrozenAtTheHandover() {
        var changedAt: Long? = 900L
        val takenAt = 1_000L
        val shown = { !BackdropSource.App.outdated(changedAt, takenAt) }
        assertTrue(shown())
        changedAt = 1_200L            // after the handover to the shade
        assertFalse("the shade's check at the commit sees the change", shown())
    }
}
