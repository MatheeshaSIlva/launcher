package dev.launcher.app.shade

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import dev.launcher.app.AppLog

/**
 * Test only: Samsung's view-level blur ([android.view.SemBlurInfo], set with View.semSetBlurInfo) on a banner-sized card
 * inside a full-screen overlay window, as the shade holds its banners. Driven over adb (TEST_SHADE do=semblur):
 *   --es kind describe                       logs SemBlurInfo's constants and Builder methods
 *   --es mode BLUR_MODE_WINDOW --ei radius R [--es curve NAME] [--es bg AARRGGBB] [--ez move true] [--ei secs S]
 */
internal class SemBlurProbe(private val ctx: Context, private val wm: WindowManager, private val handler: Handler) {
    private var root: View? = null
    private val infoCls by lazy { try { Class.forName("android.view.SemBlurInfo") } catch (_: Throwable) { null } }
    private val builderCls by lazy { try { Class.forName("android.view.SemBlurInfo\$Builder") } catch (_: Throwable) { null } }

    fun run(i: Intent) {
        if (i.getStringExtra("kind") == "describe") { describe(); return }
        if (i.getStringExtra("kind") == "glass") { glass(i); return }
        remove()
        val d = ctx.resources.displayMetrics
        val margin = (8 * d.density).toInt()
        val cardW = d.widthPixels - 2 * margin
        val cardH = (78 * d.density).toInt()
        val top = i.getIntExtra("top", (52 * d.density).toInt())
        val radius = 24 * d.density
        val frame = FrameLayout(ctx)
        val card = TextView(ctx).apply {
            text = i.getStringExtra("label") ?: "probe"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, radius) }
            }
        }
        frame.addView(card, FrameLayout.LayoutParams(cardW, cardH).apply { leftMargin = margin; topMargin = top })
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "LauncherBlurProbe"
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }
        try { wm.addView(frame, lp) } catch (t: Throwable) { AppLog.log("[semblur] addView failed: ${t.message}"); return }
        root = frame
        val result = apply(card, i)
        AppLog.log("[semblur] ${i.getStringExtra("label")}: $result")
        if (i.getBooleanExtra("move", false)) {
            card.animate().translationY(600f).setDuration(1500).withEndAction { card.animate().translationY(0f).setDuration(1500).start() }.start()
        }
        if (i.getBooleanExtra("ramp", false)) {
            // The radius re-applied at every frame: 0 -> R -> 0 with the card's alpha.
            val r = i.getIntExtra("radius", 150)
            val t0 = android.os.SystemClock.uptimeMillis()
            var frames = 0
            val cb = object : android.view.Choreographer.FrameCallback {
                override fun doFrame(now: Long) {
                    if (root !== frame) return
                    val t = (android.os.SystemClock.uptimeMillis() - t0) / 1000f
                    val k = if (t < 1f) t else if (t < 2f) 1f else (3f - t).coerceAtLeast(0f)
                    card.alpha = k
                    val ri = Intent(i).putExtra("radius", (r * k).toInt())
                    apply(card, ri)
                    frames++
                    if (t < 3f) android.view.Choreographer.getInstance().postFrameCallback(this)
                    else AppLog.log("[semblur] ramp: $frames frames re-applied")
                }
            }
            android.view.Choreographer.getInstance().postFrameCallback(cb)
        }
        if (i.getBooleanExtra("fade", false)) {
            card.alpha = 0f
            card.animate().alpha(1f).setDuration(1500).withEndAction { card.animate().alpha(0f).setDuration(1500).start() }.start()
        }
        if (i.getBooleanExtra("scale", false)) {
            card.animate().scaleX(0.5f).scaleY(0.5f).setDuration(1500).withEndAction { card.animate().scaleX(1f).scaleY(1f).setDuration(1500).start() }.start()
        }
        handler.postDelayed({ if (root === frame) remove() }, i.getIntExtra("secs", 4) * 1000L)
    }

    private fun apply(v: View, i: Intent): String {
        val ic = infoCls ?: return "no SemBlurInfo"
        val bc = builderCls ?: return "no Builder"
        return try {
            View::class.java.declaredMethods.firstOrNull { it.name == "semSetBlurEnabled" }?.apply { isAccessible = true }?.invoke(v, true)
            val mode = ic.getField(i.getStringExtra("mode") ?: "BLUR_MODE_WINDOW").getInt(null)
            var b: Any = bc.getConstructor(Int::class.javaPrimitiveType).newInstance(mode)
            val r = i.getIntExtra("radius", -1)
            if (r >= 0) b = bc.getMethod("setRadius", Int::class.javaPrimitiveType).invoke(b, r)!!
            i.getStringExtra("curve")?.let { n -> b = bc.getMethod("setColorCurvePreset", Int::class.javaPrimitiveType).invoke(b, ic.getField(n).getInt(null))!! }
            i.getStringExtra("bg")?.let { c -> b = bc.getMethod("setBackgroundColor", Int::class.javaPrimitiveType).invoke(b, c.toLong(16).toInt())!! }
            i.getStringExtra("cc")?.let { cc ->
                val v = cc.split(',').map { it.trim().toFloat() }
                val f = Float::class.javaPrimitiveType
                b = bc.getMethod("setColorCurve", f, f, f, f, f, f).invoke(b, v[0], v[1], v[2], v[3], v[4], v[5])!!
            }
            val corner = i.getFloatExtra("corner", -1f)
            if (corner >= 0f) b = bc.getMethod("setBackgroundCornerRadius", Float::class.javaPrimitiveType).invoke(b, corner)!!
            if (i.getBooleanExtra("layer", false)) v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            val info = bc.getMethod("build").invoke(b)
            val set = View::class.java.declaredMethods.first { it.name == "semSetBlurInfo" }.apply { isAccessible = true }
            set.invoke(v, info)
            "applied"
        } catch (t: Throwable) {
            "FAILED ${t.javaClass.simpleName}: ${t.cause?.message ?: t.message}"
        }
    }

    private fun describe() {
        val sb = StringBuilder("[semblur] ")
        val ic = infoCls
        if (ic == null) { AppLog.log("[semblur] no SemBlurInfo"); return }
        sb.append("constants: ")
        for (f in ic.fields) if (f.type == Int::class.javaPrimitiveType) sb.append("${f.name}=${try { f.getInt(null) } catch (_: Throwable) { "?" }} ")
        sb.append("| builder: ")
        builderCls?.methods?.filter { it.declaringClass == builderCls }?.forEach { sb.append("${it.name}(${it.parameterTypes.joinToString(",") { p -> p.simpleName }}) ") }
        sb.append("| view: ")
        View::class.java.declaredMethods.filter { it.name.startsWith("semSetBlur") || it.name.contains("BlurInfo") }.forEach { sb.append("${it.name}(${it.parameterTypes.joinToString(",") { p -> p.simpleName }}) ") }
        sb.toString().chunked(900).forEach { AppLog.log(it) }
        builderCls?.methods?.filter { it.declaringClass == builderCls }?.forEach {
            AppLog.log("[semblur] builder ${it.name}(${it.parameterTypes.joinToString(",") { p -> p.simpleName }})")
        }
    }

    /**
     * The banner's glass on two cards (not banners: test labels only): on top over the system's live blur (a plate and the
     * glass's own layers, as banners draw it), below as before (over the wallpaper blurred ahead of time). Over home.
     */
    private fun glass(i: Intent) {
        remove()
        val d = ctx.resources.displayMetrics
        val u = dev.launcher.app.design.Scale.unitPx(ctx, minOf(d.widthPixels, d.heightPixels))
        val mp = dev.launcher.app.design.MaterialPainter.create(u) ?: run { AppLog.log("[semblur] no glass renderer"); return }
        val mat = dev.launcher.app.design.Design.material(BannerView.MATERIAL)
        val margin = 8f * u
        val w = d.widthPixels - 2 * margin
        val h = 72f * u
        val radius = 24f * u
        val top1 = i.getIntExtra("top", 860).toFloat()
        val top2 = top1 + h + 30f * u
        val frame = FrameLayout(ctx)
        val sigma = dev.launcher.app.design.Blur.sigmaPx(mat.frostPt + (mat.frostDarkPt - mat.frostPt) * dev.launcher.app.theme.Appearance.dark, u)
        val plate = View(ctx)
        frame.addView(plate, FrameLayout.LayoutParams(w.toInt(), h.toInt()).apply { leftMargin = margin.toInt(); topMargin = top1.toInt() })
        val look = mp.systemLook(mat)
        val tint = if (i.getBooleanExtra("tint", false)) mp.systemTint(mat) else 0
        val ok = dev.launcher.app.design.SamsungBlur.set(plate, dev.launcher.app.design.SamsungBlur.radiusFor(sigma), radius, tint, if (tint == 0) look else null)
        val label = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f * u; color = dev.launcher.app.design.Design.color(BannerView.LABEL) }
        val wp = dev.launcher.app.Wallpaper.current
        val toScreen = android.graphics.Matrix()
        val overlay = object : View(ctx) {
            override fun onDraw(c: android.graphics.Canvas) {
                c.save(); c.translate(margin, top1)
                mp.drawOverSystemBlur(c, mat, w, h, radius)
                c.drawText("live: system blur + glass layers", 20f * u, h / 2f + 5f * u, label)
                c.restore()
                c.save(); c.translate(margin, top2)
                val frost = wp?.let { x ->
                    toScreen.set(x.matrix(width, height))
                    dev.launcher.app.design.FrostCache.get(x.bitmap, toScreen, sigma / toScreen.mapRadius(1f).coerceAtLeast(0.001f), handler) { invalidate() }
                }
                mp.setBackdrop(frost, 0xFF000000.toInt())
                val dim = dev.launcher.app.theme.Appearance.wallpaperDim
                val under = if (frost != null && dim > 0.001f) listOf(dev.launcher.app.design.Fill(dev.launcher.app.design.ColorValue.Literal(0xFF000000.toInt(), 0xFF000000.toInt()), dim, dim, dev.launcher.app.design.Blend.NORMAL)) else emptyList()
                mp.draw(c, mat, w, h, radius, margin, top2, 1f, under = under)
                c.drawText("before: blurred wallpaper picture", 20f * u, h / 2f + 5f * u, label)
                c.restore()
            }
        }
        overlay.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        frame.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "LauncherBlurProbe"
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }
        try { wm.addView(frame, lp) } catch (t: Throwable) { AppLog.log("[semblur] addView failed: ${t.message}"); return }
        root = frame
        AppLog.log("[semblur] glass: live ${if (ok) "on" else "FAILED"}, radius ${dev.launcher.app.design.SamsungBlur.radiusFor(sigma)}, tint #${Integer.toHexString(tint)}, $look")
        handler.postDelayed({ if (root === frame) remove() }, i.getIntExtra("secs", 5) * 1000L)
    }

    fun remove() {
        root?.let { try { wm.removeView(it) } catch (_: Throwable) { } }
        root = null
    }
}
