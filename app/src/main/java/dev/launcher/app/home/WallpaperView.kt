package dev.launcher.app.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.view.View
import android.view.animation.PathInterpolator
import dev.launcher.app.AppLog
import dev.launcher.app.Wallpaper
import dev.launcher.app.WallpaperTransition

/** Draws our copy of the wallpaper with the same centre-crop the glass samples with. */
class WallpaperView(ctx: Context, private val cellPx: Float) : View(ctx) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    var wallpaper: Wallpaper? = null
        set(v) {
            val arrived = field == null && v != null && isAttachedToWindow && width > 0
            field = v
            // Read after home is on screen (the first start, a retry): fades in over the black ground instead of popping.
            if (arrived && transition == null) { alpha = 0f; animate().alpha(1f).setDuration(320).start() }
            invalidate()
        }

    private var transition: WallpaperTransition? = null
    private var progress = 0f
    private var time = 0f
    val transitioning get() = transition != null

    /** Reveal transition from [from] to [to] (≈2.8 s); falls back to a short crossfade if the shader fails. */
    fun transitionTo(from: Wallpaper, to: Wallpaper, onFrame: (Float, Float) -> Unit = { _, _ -> }, onEnd: () -> Unit) {
        val t = try {
            if (Build.VERSION.SDK_INT >= 33) WallpaperTransition(from, to, width, height, cellPx) else null
        } catch (e: Throwable) {
            AppLog.log("[wallpaper] transition shader failed (${e.javaClass.simpleName}: ${e.message}); crossfading")
            null
        }
        if (t == null) {
            alpha = 0f
            wallpaper = to
            animate().alpha(1f).setDuration(350).withEndAction(onEnd).start()
            return
        }
        transition = t
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2800
            interpolator = PathInterpolator(0.45f, 0f, 0.3f, 1f)   // starts gently, eases out
            addUpdateListener {
                progress = it.animatedValue as Float
                time = it.currentPlayTime / 1000f
                invalidate()
                onFrame(progress, time)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    transition = null
                    wallpaper = to
                    onEnd()
                }
            })
            start()
        }
        AppLog.log("[wallpaper] new wallpaper: reveal transition")
    }

    override fun onDraw(canvas: Canvas) {
        val t = transition
        if (t != null) t.draw(canvas, progress, time)
        else {
            val w = wallpaper ?: return
            canvas.drawBitmap(w.bitmap, w.matrix(width, height), paint)
        }
        // Dark mode dims the wallpaper a little (iOS); the glass on home sees the same dim.
        val dim = dev.launcher.app.theme.Appearance.wallpaperDim
        if (dim > 0f) canvas.drawColor((255 * dim).toInt() shl 24)
    }
}

/**
 * The material behind the App Library (and later folders and search): the wallpaper under a heavy blur, darkened a
 * little as iOS does. Its alpha follows how far the drawer is open. Without our wallpaper copy: a plain dark veil.
 */
class BackdropView(ctx: Context) : View(ctx) {
    // iOS's background material keeps the wallpaper's colours vivid (more saturated, barely darkened).
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(1.4f) })
    }
    var wallpaper: Wallpaper? = null
        set(v) { field = v; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val w = wallpaper
        if (w != null) {
            canvas.drawBitmap(w.heavy, w.heavyMatrix(width, height), paint)
            // The appearance's material: light (a white veil) or dark (a dark one); the glass on it sees the same veil.
            canvas.drawColor(dev.launcher.app.theme.Appearance.backdropVeil)
        } else {
            canvas.drawColor(0x99000000.toInt())
        }
    }
}
