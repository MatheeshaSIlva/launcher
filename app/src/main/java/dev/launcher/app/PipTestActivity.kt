package dev.launcher.app

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View

/**
 * Test only: an app with a mini player, as YouTube's (picture-in-picture, entered by itself when it is left), showing a
 * fixed bright picture: the mini player's tests (the App Switcher, a close into it, a landscape close) measure its edges
 * against our holes frame by frame, which a video cannot be used for (ads, dark scenes, a countdown ticking). Over adb:
 *   am start -n dev.launcher.app/.PipTestActivity [--es orient landscape|portrait] [--ez pip true]
 * "pip": into the mini player at once (else when it is left).
 */
class PipTestActivity : Activity() {
    private lateinit var picture: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        picture = Picture(this)
        setContentView(picture)
        // The parameters need the picture's place: set once it is laid out, again whenever that changes.
        picture.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> params()?.let { setPictureInPictureParams(it) } }
        read(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        read(intent)
    }

    private fun read(i: Intent) {
        requestedOrientation = when (i.getStringExtra("orient")) {
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> requestedOrientation
        }
        if (i.getBooleanExtra("pip", false)) picture.post { params()?.let { enterPictureInPictureMode(it) } }
    }

    private fun params(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < 26) return null
        // The picture is 16:9, centred: its rectangle is where the mini player's content comes from.
        val w = picture.width
        val h = picture.height
        if (w == 0 || h == 0) return null
        val pw = minOf(w, h * 16 / 9)
        val ph = pw * 9 / 16
        val hint = Rect((w - pw) / 2, (h - ph) / 2, (w + pw) / 2, (h + ph) / 2)
        val loc = IntArray(2).also { picture.getLocationOnScreen(it) }
        hint.offset(loc[0], loc[1])
        return PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).setSourceRectHint(hint).apply {
            // Seamless resizing stays on (as a video's): without it Android shows the app's icon over a plain colour while
            // the mini player is resized, as for the display's turn.
            if (Build.VERSION.SDK_INT >= 31) setAutoEnterEnabled(true)
        }.build()
    }

    // As YouTube: into the mini player when the user leaves it, also where auto-enter does not run.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isInPictureInPictureMode) params()?.let { try { enterPictureInPictureMode(it) } catch (_: Throwable) { } }
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        picture.invalidate()
    }

    /** Hue across, light down, a white grid and a label: every part of it differs from every other and from the deck. */
    private class Picture(a: Activity) : View(a) {
        private val fill = Paint()
        private val line = Paint().apply { color = Color.WHITE; strokeWidth = 3f }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textAlign = Paint.Align.CENTER; isFakeBoldText = true }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            c.drawColor(Color.BLACK)
            // The 16:9 picture, centred (letterboxed in a tall window).
            val pw = minOf(w, h * 16f / 9f)
            val ph = pw * 9f / 16f
            val l = (w - pw) / 2f
            val t = (h - ph) / 2f
            val hues = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 0.75f, 1f)) }
            fill.shader = LinearGradient(l, 0f, l + pw, 0f, hues, null, Shader.TileMode.CLAMP)
            c.drawRect(l, t, l + pw, t + ph, fill)
            fill.shader = LinearGradient(0f, t, 0f, t + ph, Color.argb(0, 255, 255, 255), Color.argb(150, 255, 255, 255), Shader.TileMode.CLAMP)
            c.drawRect(l, t, l + pw, t + ph, fill)
            val step = pw / 12f
            var x = l + step
            while (x < l + pw - 1f) { c.drawLine(x, t, x, t + ph, line); x += step }
            var y = t + step
            while (y < t + ph - 1f) { c.drawLine(l, y, l + pw, y, line); y += step }
            text.textSize = ph / 6f
            c.drawText("PiP test", l + pw / 2f, t + ph / 2f + text.textSize / 3f, text)
        }
    }
}
