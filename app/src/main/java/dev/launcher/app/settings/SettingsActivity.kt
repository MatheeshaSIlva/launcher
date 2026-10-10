package dev.launcher.app.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import dev.launcher.app.AppLog
import dev.launcher.app.design.Design
import dev.launcher.app.design.ViewPicture
import dev.launcher.app.home.ThemeReveal
import dev.launcher.app.motion.Motion
import dev.launcher.app.motion.MotionTokens
import dev.launcher.app.motion.timed

/**
 * The settings app (docs/PLAN_SETTINGS.md): layouts, the look, motion and the phone's side, drawn in the active theme
 * ([SettingsTheme]). A change applies at once: when it changes the look, the app holds its old look the moment the design
 * changes and reveals the new one from where it was touched (as home does: [ThemeReveal]).
 */
class SettingsActivity : ComponentActivity() {
    private lateinit var root: FrameLayout
    private var cover: View? = null
    private var coverAnim: android.animation.ValueAnimator? = null
    private var held: Bitmap? = null
    private var builtStructure = Design.structure
    private var touchX = -1f
    private var touchY = -1f
    private var touchAt = 0L

    /** Registered before the content's own listener, so the old look is held before it draws in the new one. */
    private val onDesign: () -> Unit = {
        if (Design.structure != builtStructure && ::root.isInitialized && root.isAttachedToWindow) {
            builtStructure = Design.structure
            hold()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Design.addListener(onDesign)
        // Edge to edge: the page draws behind the bars (our own status bar is over it).
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        @Suppress("DEPRECATION") window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION") window.navigationBarColor = android.graphics.Color.TRANSPARENT
        root = FrameLayout(this)
        root.addView(ComposeView(this).apply { setContent { SettingsApp(onClose = { finish() }) } },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        AppLog.log("[settings] opened")
    }

    override fun onDestroy() {
        Design.removeListener(onDesign)
        coverAnim?.cancel()
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_UP) { touchX = ev.x; touchY = ev.y; touchAt = android.os.SystemClock.uptimeMillis() }
        return super.dispatchTouchEvent(ev)
    }

    /** The settings as they show now, held still over the page until the new look is drawn under it, then revealed. */
    private fun hold() {
        // As it shows (a reveal under way included), then whatever was running gives way to it.
        val old = ViewPicture.render(root) ?: return
        coverAnim?.cancel()
        held = old
        val still = object : View(this) { override fun onDraw(c: Canvas) { if (!old.isRecycled) c.drawBitmap(old, 0f, 0f, null) } }
        cover?.let { root.removeView(it) }
        cover = still
        root.addView(still, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // The content recomposes in the new look on the next frame; the reveal starts on the one after.
        root.postOnAnimation { root.postOnAnimation { reveal(old) } }
    }

    private fun reveal(old: Bitmap) {
        if (held !== old) return
        if (Build.VERSION.SDK_INT < 33) { fade(old); return }
        val w = root.width.toFloat()
        val h = root.height.toFloat()
        // From where the change was tapped (a theme's row), else from the bottom centre.
        val recent = android.os.SystemClock.uptimeMillis() - touchAt < 3000
        val r = try {
            ThemeReveal(old, w, h, if (recent) touchX else w / 2f, if (recent) touchY else h, resources.displayMetrics.density * dev.launcher.app.home.HomeScreen.REVEAL_CELL_DP)
        } catch (t: Throwable) { fade(old); return }
        var progress = 0f
        var time = 0f
        val v = object : View(this) { override fun onDraw(c: Canvas) { if (!old.isRecycled) r.draw(c, progress, time) } }
        cover?.let { root.removeView(it) }
        cover = v
        root.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        coverAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).timed(Motion.role(MotionTokens.HOME_REBUILD)).apply {
            addUpdateListener { a -> progress = a.animatedValue as Float; time = a.currentPlayTime / 1000f; v.invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { done(v, old) }
            })
            start()
        }
    }

    private fun fade(old: Bitmap) {
        val v = cover ?: return
        v.animate().alpha(0f).timed(Motion.role(MotionTokens.HOME_REBUILD)).withEndAction { done(v, old) }.start()
    }

    private fun done(v: View, old: Bitmap) {
        root.removeView(v)
        if (cover === v) cover = null
        if (held === old) held = null
        old.recycle()
    }
}
