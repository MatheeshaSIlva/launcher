package dev.launcher.app.apps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * Icon masks, part of a theme's shape layer. [SYSTEM] (the default) leaves icons exactly as Android draws them; the
 * others reshape every icon and are chosen in settings.
 */
enum class IconShape {
    /** The system's own mask (One UI's on the S24): icons are drawn as they are. */
    SYSTEM,
    /** iOS app icon shape: a superellipse (continuous curvature, no visible start of the corner). */
    SQUIRCLE,
    CIRCLE,
    ROUNDED_SQUARE;

    fun path(size: Float): Path = Path().apply {
        when (this@IconShape) {
            SYSTEM -> addPath(systemMask(size))
            SQUIRCLE -> {
                val a = size / 2f
                val n = 5.0
                val steps = 160
                for (i in 0..steps) {
                    val t = 2.0 * Math.PI * i / steps
                    val c = cos(t)
                    val s = sin(t)
                    val x = a + a * sign(c) * abs(c).pow(2.0 / n)
                    val y = a + a * sign(s) * abs(s).pow(2.0 / n)
                    if (i == 0) moveTo(x.toFloat(), y.toFloat()) else lineTo(x.toFloat(), y.toFloat())
                }
                close()
            }
            CIRCLE -> addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW)
            ROUNDED_SQUARE -> addRoundRect(0f, 0f, size, size, size * 0.2f, size * 0.2f, Path.Direction.CW)
        }
    }

    /** Corner radius of a plain rounded rectangle that reads as this shape (for cards morphing into an icon). */
    fun cornerFraction(): Float = when (this) { SYSTEM -> systemCorner; SQUIRCLE -> 0.23f; CIRCLE -> 0.5f; ROUNDED_SQUARE -> 0.2f }

    /**
     * Corner of the card's clip once it is icon-sized: well inside the shape, so the clip never cuts the icon (the rendered
     * icon carries its exact mask). A clip at [cornerFraction] cut the system squircle's corners rounder than the real icon.
     */
    fun clipFraction(): Float = cornerFraction() * 0.6f

    private companion object {
        /** The system's adaptive-icon mask at [size] px. */
        fun systemMask(size: Float): Path {
            val d = AdaptiveIconDrawable(android.graphics.drawable.ColorDrawable(Color.WHITE), android.graphics.drawable.ColorDrawable(Color.WHITE))
            d.setBounds(0, 0, size.toInt(), size.toInt())
            return Path(d.iconMask)
        }

        /**
         * The system mask measured once: the corner radius of the rounded square with the same area (a square with corner
         * radius r covers 1 - (4 - pi) r^2 of its box). A circle gives 0.5, One UI's squircle about 0.25.
         */
        val systemCorner: Float by lazy {
            try {
                val n = 128
                val b = Bitmap.createBitmap(n, n, Bitmap.Config.ALPHA_8)
                Canvas(b).drawPath(systemMask(n.toFloat()), Paint(Paint.ANTI_ALIAS_FLAG))
                val px = ByteArray(n * n)
                b.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(px))
                b.recycle()
                val area = px.sumOf { (it.toInt() and 0xFF).toDouble() } / (255.0 * n * n)
                kotlin.math.sqrt(((1.0 - area) / (4.0 - Math.PI)).coerceIn(0.0, 0.25)).toFloat()
            } catch (_: Throwable) { 0.23f }
        }
    }
}

/**
 * App icons rendered once into the current [shape] at the size the layout asks for, cached (LRU, by app key and size),
 * mip-mapped so the small icons in App Library tiles stay smooth. Rendering runs on two workers; callbacks on main.
 */
object Icons {
    private lateinit var ctx: Context
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)
    private val cache = object : LruCache<String, Bitmap>(48 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val pending = HashMap<String, MutableList<(Bitmap) -> Unit>>()   // main thread only
    /** Latest icon per package at the home size: what a closing card turns into (read from the gesture thread). */
    private val byPkg = ConcurrentHashMap<String, Bitmap>()

    @Volatile var shape = IconShape.SYSTEM
    /** The home screen's icon size in px, set by the layout. */
    @Volatile var homeSize = 0

    fun init(context: Context) { ctx = context.applicationContext }

    fun cached(e: AppEntry, size: Int): Bitmap? = cache.get(cacheKey(e.key, size))

    /** Icon of [e] at [size] px: at once if cached, else rendered and delivered later. [cb] always runs on main. */
    fun load(e: AppEntry, size: Int, cb: (Bitmap) -> Unit) {
        if (size <= 0) return
        val k = cacheKey(e.key, size)
        cache.get(k)?.let { cb(it); return }
        val waiting = pending[k]
        if (waiting != null) { waiting += cb; return }
        pending[k] = mutableListOf(cb)
        val density = ctx.resources.displayMetrics.densityDpi
        io.execute {
            val b = try { render(e.loadIcon(ctx, density), size) } catch (_: Throwable) { null } ?: blank(size)
            cache.put(k, b)
            if (size == homeSize) byPkg[e.pkg] = b
            main.post { pending.remove(k)?.forEach { it(b) } }
        }
    }

    /** Renders [entries] at [size] in the background, so pages and tiles appear with their icons. */
    fun preload(entries: List<AppEntry>, size: Int) {
        for (e in entries) if (cached(e, size) == null) load(e, size) {}
    }

    /** The shaped icon of [pkg] as a drawable (any thread); null until it has been rendered once at the home size. */
    fun drawableFor(pkg: String): Drawable? = byPkg[pkg]?.let { BitmapDrawable(ctx.resources, it) }

    /** Forget every rendered icon (shape changed, icon pack switched). */
    fun clear() { cache.evictAll(); byPkg.clear() }

    private fun cacheKey(key: String, size: Int) = "$key|$size|${shape.name}"

    private fun blank(size: Int): Bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
        val c = Canvas(it)
        c.drawPath(shape.path(size.toFloat()), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8E8E93.toInt() })
    }

    private fun render(d: Drawable?, size: Int): Bitmap? {
        d ?: return null
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        if (shape == IconShape.SYSTEM) {
            // As Android draws it: adaptive icons in the system mask, legacy icons as they are.
            d.setBounds(0, 0, size, size)
            d.draw(c)
            b.setHasMipMap(true)
            return b
        }
        // The shape first (anti-aliased), then the art composited into it: a clipPath would leave jagged edges.
        c.drawPath(shape.path(size.toFloat()), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
        c.saveLayer(0f, 0f, size.toFloat(), size.toFloat(), Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) })
        if (d is AdaptiveIconDrawable) {
            // Layers are 108 dp of which the middle 72 dp is the icon: draw them 1.5x the icon, centred.
            val inset = size / 4
            val r = Rect(-inset, -inset, size + inset, size + inset)
            c.drawColor(Color.WHITE)
            d.background?.let { it.bounds = r; it.draw(c) }
            d.foreground?.let { it.bounds = r; it.draw(c) }
        } else if (fullBleed(d)) {
            d.setBounds(0, 0, size, size)
            d.draw(c)
        } else {
            // A legacy icon with its own shape: on white, inside the safe zone (as iOS shows non-square art).
            c.drawColor(Color.WHITE)
            val inset = (size * 0.16f).toInt()
            d.setBounds(inset, inset, size - inset, size - inset)
            d.draw(c)
        }
        c.restore()
        b.setHasMipMap(true)
        return b
    }

    /** True if the art fills its square (opaque corners): then it can simply be cut to the shape. */
    private fun fullBleed(d: Drawable): Boolean {
        val s = 24
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, s, s)
        d.draw(Canvas(b))
        val corners = listOf(b.getPixel(1, 1), b.getPixel(s - 2, 1), b.getPixel(1, s - 2), b.getPixel(s - 2, s - 2))
        b.recycle()
        return corners.all { (it ushr 24) > 200 }
    }
}
