package dev.launcher.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * Stands in for home while a close from an app in landscape hands over to it. Home is upright only: started over a turned
 * display, Android shows it turned (fixed rotation) and, from the moment it is started, hides every window of ours that is
 * not an activity's (the card window among them) until the display has turned upright and they have drawn again
 * (AsyncRotationController): the app showed through for the half second home took to come up. An activity's window is not
 * hidden, so this one, started over the app (in the app's orientation: translucent, it asks for none) as the close
 * begins, draws the picture of home turned as Android will show the real one, under the card. Home is started once this
 * is on screen; it goes by itself once home is in front.
 */
class TurnCover : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Asked for and given up on before it came (home already started): it lies under home, gone at once.
        if (!wanted) { AppLog.log("[cover] came after it was given up: gone"); finishAndRemoveTask(); return }
        if (Build.VERSION.SDK_INT < 30) return finishAndRemoveTask()   // (gesture nav's closes need Android 11+ anyway)
        // Whatever goes wrong here, the close goes on without the cover (this runs in the launcher's own process).
        try { setUp() } catch (t: Throwable) {
            AppLog.log("[cover] failed: ${t.javaClass.simpleName}: ${t.message}")
            failedAt = android.os.SystemClock.uptimeMillis()
            wanted = false
            finishAndRemoveTask()
        }
    }

    private fun setUp() {
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_JUMPCUT
            setFitInsetsTypes(0)
        }
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        // The window's own background, not a view: the window draws it over its whole size whatever its content's insets
        // (a view laid out inside the bars, for a few frames as home started, left them to the window's black fallback).
        window.setBackgroundDrawable(CoverDrawable(this))
        // Its own first frame (an opaque activity in a new task may show a starting window first: the shell's word that the
        // cover's transition is on screen can be the starting window's).
        val decor = window.decorView
        decor.viewTreeObserver.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                decor.post { decor.viewTreeObserver.removeOnDrawListener(this) }
                if (drawnAt < createdAt) drawnAt = android.os.SystemClock.uptimeMillis()
            }
        })
        createdAt = android.os.SystemClock.uptimeMillis()
        // The system bars as the app had them (shown or hidden), so nothing about them changes under the card. Through the
        // decor view, which makes it (the window's own controller is null until then: asked first, it crashed the launcher
        // on every close from a full-screen video).
        if (intent.getBooleanExtra(EXTRA_HIDE_BARS, false)) try {
            window.decorView.windowInsetsController?.let {
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                it.hide(WindowInsets.Type.systemBars())
            }
        } catch (t: Throwable) { AppLog.log("[cover] bars not hidden: ${t.javaClass.simpleName}: ${t.message}") }
        current = this
    }

    override fun onStop() {
        super.onStop()
        // Home is in front: the cover's work is done (no task of it left behind, nothing in recents).
        if (!isChangingConfigurations) finishAndRemoveTask()
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    /**
     * Home as gesture nav's picture shows it at rest ([HomeBridge.preview]: every icon, as the real home shows once the card
     * has turned into its icon), turned about the screen's centre as Android shows the upright-only home on this display.
     */
    private class CoverDrawable(private val activity: Activity) : android.graphics.drawable.Drawable() {
        private val picture = HomeBridge.preview
        private val at = IntArray(2)

        override fun draw(c: Canvas) {
            c.drawColor(Color.BLACK)
            val p = picture ?: return
            val b = activity.windowManager.maximumWindowMetrics.bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            val turn = when (activity.display?.rotation) {
                Surface.ROTATION_90 -> -90f
                Surface.ROTATION_270 -> 90f
                else -> 0f
            }
            activity.window.decorView.getLocationOnScreen(at)
            c.save()
            c.translate(-at[0].toFloat(), -at[1].toFloat())
            if (turn != 0f) {
                // Home's width is the turned screen's height.
                c.translate(w / 2f, h / 2f)
                c.rotate(turn)
                c.translate(-h / 2f, -w / 2f)
            }
            p.wallpaper?.let { c.drawPicture(it) }
            c.drawPicture(p.content)
            c.restore()
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
    }

    companion object {
        private const val EXTRA_HIDE_BARS = "hideBars"

        private val main = Handler(Looper.getMainLooper())
        // Main thread only.
        private var current: TurnCover? = null
        private var wanted = false
        private var createdAt = 0L
        /** When (uptime ms) a cover last drew its first frame (any thread). */
        @Volatile var drawnAt = 0L
            private set
        /** When (uptime ms) the cover last failed to set itself up (any thread). */
        @Volatile var failedAt = 0L
            private set

        /** The cover's start, for the shell's own start (no system animation): [hideBars] as the app in front has them. */
        fun intent(ctx: Context, hideBars: Boolean): Intent = Intent(ctx, TurnCover::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(EXTRA_HIDE_BARS, hideBars)

        /** A cover is about to be started (any thread). */
        fun expect() = main.post { wanted = true }

        /** Home is starting over it (or it could not be started): it is no longer wanted on top. Any thread. */
        fun handedOver() = main.post { wanted = false }

        /** The close ended without home: the cover goes, if it came (any thread). */
        fun dismiss() = main.post {
            wanted = false
            current?.let {
                AppLog.log("[cover] not needed (no home behind it): gone")
                it.finishAndRemoveTask()
                @Suppress("DEPRECATION") it.overridePendingTransition(0, 0)
            }
        }
    }
}
