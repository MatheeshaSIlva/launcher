package dev.launcher.app.switcher

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import dev.launcher.app.AppLog

/**
 * The App Switcher's place among the apps: a translucent activity of its own task whose window shows the deck while the
 * switcher is open over a mini player (drawn by gesture nav's thread through [SwitcherHost]). Being an activity's window
 * it lies with the apps, under picture-in-picture: the mini player floats over the deck as over any app, with its shadow,
 * controls and dismiss target. Started with "no user action" and translucent: the app under it neither goes into its own
 * mini player nor stops playing (checked on the S24 with YouTube). Draws nothing of its own, in software (manifest): its
 * frames stay off the render thread the deck draws on.
 *
 * The deck's surface is in place before the window's first frame (embedded as its surface is made), so the window never
 * shows without it; it counts as on screen once its (instant) opening transition has finished ([onEnterAnimationComplete]).
 */
class SwitcherActivity : Activity() {
    private var surface: SurfaceView? = null
    /** The switcher session it was started for (see [SwitcherHost.session]). */
    var session = 0
        private set
    /** The deck's surface is in this window. */
    var embedded = false
        private set
    /** Its opening transition has finished: the window is on screen. */
    var entered = false
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = intent?.getIntExtra(EXTRA_SESSION, 0) ?: 0
        // Started for a session that has ended (it came late): gone at once, under whatever is in front by now.
        if (Build.VERSION.SDK_INT < 34 || !SwitcherHost.wanted(session)) { dismiss(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_JUMPCUT
            setFitInsetsTypes(0)
            windowAnimations = 0
        }
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        // Above its window (which draws nothing): what the host draws takes the touches, as it does in the card window.
        val sv = SurfaceView(this).apply {
            setZOrderOnTop(true)
            holder.addCallback(object : SurfaceHolder.Callback {
                // Inside the window's first traversal, before its first frame: in a software window the embedding is applied
                // at once, so the window is never shown without the deck's surface.
                override fun surfaceCreated(h: SurfaceHolder) = SwitcherHost.onSurfaceReady(this@SwitcherActivity)
                override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hh: Int) {}
                override fun surfaceDestroyed(h: SurfaceHolder) {}
            })
        }
        surface = sv
        setContentView(sv)
        SwitcherHost.onCreated(this)
    }

    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        if (entered) return
        entered = true
        SwitcherHost.onEntered(this)
    }

    override fun onStop() {
        super.onStop()
        // Covered (an app or home in front of it; an app expanding from its mini player grows over it first): nothing of it
        // is left behind.
        if (isChangingConfigurations) return
        SwitcherHost.onStopped(this)
        dismiss()
    }

    @Deprecated("Back: gesture nav decides, as for the bar's swipe")
    override fun onBackPressed() = SwitcherHost.onBack(this)

    override fun onDestroy() {
        SwitcherHost.onGone(this)
        super.onDestroy()
    }

    /** Shows what the host draws; false if it could not. */
    fun embed(pkg: android.view.SurfaceControlViewHost.SurfacePackage): Boolean {
        if (Build.VERSION.SDK_INT < 30 || isFinishing || embedded) return embedded
        return try {
            val sv = surface ?: return false
            sv.setChildSurfacePackage(pkg)
            embedded = true
            true
        } catch (t: Throwable) {
            AppLog.log("[switcher] its window could not take the deck: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /** Gone at once, without an animation (whatever is in front of it already shows what follows). */
    fun dismiss() {
        if (isFinishing) return
        finishAndRemoveTask()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_SESSION = "session"
    }
}
