package dev.launcher.app.shade

import android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
import android.hardware.camera2.CameraCharacteristics.LENS_FACING_EXTERNAL
import android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * B-065: the torch is the first camera facing back with a flash, as Android's FlashlightControllerImpl.getCameraId
 * chooses it; a flash on any other side (front, external) gives no torch.
 */
class TorchCameraTest {

    private fun cam(id: String, facing: Int?, flash: Boolean?) = TorchCandidate(id, facing, flash)

    @Test fun frontFlashOnlyIsNoTorch() {
        assertNull(torchCameraOf(sequenceOf(cam("0", LENS_FACING_BACK, false), cam("1", LENS_FACING_FRONT, true))))
    }

    @Test fun backWithFlashIsTheTorch() {
        assertEquals("0", torchCameraOf(sequenceOf(cam("0", LENS_FACING_BACK, true), cam("1", LENS_FACING_FRONT, true))))
    }

    @Test fun theFirstBackWithFlashWins() {
        val list = sequenceOf(
            cam("1", LENS_FACING_FRONT, true), cam("2", LENS_FACING_BACK, false),
            cam("3", LENS_FACING_BACK, true), cam("4", LENS_FACING_BACK, true),
        )
        assertEquals("3", torchCameraOf(list))
    }

    @Test fun externalFlashIsNoTorch() {
        assertNull(torchCameraOf(sequenceOf(cam("5", LENS_FACING_EXTERNAL, true))))
    }

    @Test fun unknownFacingOrFlashIsNoTorch() {
        assertNull(torchCameraOf(sequenceOf(cam("0", null, true), cam("1", LENS_FACING_BACK, null))))
        assertNull(torchCameraOf(emptySequence()))
    }

    @Test fun stopsAtTheFirstMatch() {
        var read = 0
        val list = sequenceOf("0", "1", "2").map { read++; cam(it, LENS_FACING_BACK, true) }
        assertEquals("0", torchCameraOf(list))
        assertEquals(1, read)
    }
}
