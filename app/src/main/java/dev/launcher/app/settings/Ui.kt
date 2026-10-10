package dev.launcher.app.settings

import android.graphics.RectF
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.em
import dev.launcher.app.Wallpaper
import dev.launcher.app.design.ColorKey
import dev.launcher.app.design.Curve
import dev.launcher.app.design.CurveKey
import dev.launcher.app.design.Design
import dev.launcher.app.design.MaterialKey
import dev.launcher.app.design.NumberKey
import dev.launcher.app.design.Scale
import dev.launcher.app.design.TextKey
import dev.launcher.app.drawer.BackdropGlass
import dev.launcher.app.motion.Motion
import dev.launcher.app.theme.Appearance
import dev.launcher.app.theme.Fonts

/**
 * The settings app's bridge to the design system (docs/PLAN_SETTINGS.md, C1): Compose lays out and animates, the theme's
 * tokens give every colour, text style, size and surface, and the animation preset every motion. [Look] is read inside
 * composition and changes as the design or the appearance does, so the settings app redraws with the theme like everything
 * else. Surfaces are glass drawn by the launcher's own renderer over the blurred wallpaper, as the App Library's.
 */
class Look(
    /** Pixels per point ([Scale]): the theme's sizes are in points. */
    val unit: Float,
    private val density: Float,
    private val fontScale: Float,
    val screenW: Int,
    val screenH: Int,
    /** Changes with every design or appearance change: reading it subscribes a composable to them. */
    val version: Int,
    /** The wallpaper the page shows blurred (home's, or one this app read itself when home had not yet). */
    val wallpaper: Wallpaper?,
) {
    private val glass = BackdropGlass(unit, screenW, screenH).also { it.wallpaper = wallpaper }

    fun color(k: ColorKey): Color = Color(Design.color(k))
    fun pt(k: NumberKey): Float = Design.num(k) * unit
    fun dp(k: NumberKey): Dp = Dp(Design.num(k) * unit / density)
    fun dp(px: Float): Dp = Dp(px / density)

    /** Text style [k] in [color]: the theme's font (any family it names), size, line height and tracking. */
    fun text(k: TextKey, color: Color): TextStyle {
        val s = Design.text(k)
        return TextStyle(
            color = color,
            fontFamily = FontFamily(Fonts.of(s.family, s.weight, s.sizePt)),
            fontWeight = FontWeight(s.weight.coerceIn(1, 1000)),
            fontSynthesis = FontSynthesis.None,
            fontSize = androidx.compose.ui.unit.TextUnit(s.sizePt * unit / density / fontScale, androidx.compose.ui.unit.TextUnitType.Sp),
            lineHeight = androidx.compose.ui.unit.TextUnit(s.lineHeightPt * unit / density / fontScale, androidx.compose.ui.unit.TextUnitType.Sp),
            letterSpacing = if (s.sizePt > 0f) (s.trackingPt / s.sizePt).em else 0.em,
        )
    }

    /** The page behind everything: the wallpaper heavily blurred under the appearance's veil (the App Library's). */
    fun drawBackdrop(c: android.graphics.Canvas, ox: Float = 0f, oy: Float = 0f) {
        val w = wallpaper
        if (w == null) {
            c.drawColor(Appearance.mix(0xFFF2F2F7.toInt(), 0xFF000000.toInt()))
            return
        }
        // Fixed to the screen: the canvas's origin is at [ox], [oy] in the window.
        val m = android.graphics.Matrix(w.heavyMatrix(screenW, screenH))
        m.postTranslate(-ox, -oy)
        c.drawBitmap(w.heavy, m, backdropPaint)
        c.drawColor(Appearance.backdropVeil)
    }

    private val backdropPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)

    /** Glass [key] over the backdrop at [rect] (canvas coordinates, the canvas's origin at [ox], [oy] in the window). */
    fun drawGlass(c: android.graphics.Canvas, key: MaterialKey, rect: RectF, radius: Float, ox: Float, oy: Float, press: Float = 0f) {
        if (!glass.draw(c, key, rect, radius, ox, oy, Appearance.backdropVeil, press = press)) {
            // No wallpaper copy: a plain translucent fill in the appearance's colours.
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Appearance.mix(0xB3FFFFFF.toInt(), 0x80303033.toInt()) }
            c.drawRoundRect(rect, radius, radius, p)
        }
    }

    /** Role [k]'s curve as a Compose animation (a spring by response and damping, a bezier as a cubic easing). */
    fun <T> motion(k: CurveKey): FiniteAnimationSpec<T> = spec(Motion.role(k))

    companion object {
        fun <T> spec(c: Curve): FiniteAnimationSpec<T> {
            val slow = Motion.slow()
            return when (c) {
                is Curve.Spring -> {
                    val omega = 2.0 * Math.PI / (c.response * slow)
                    spring(dampingRatio = c.damping, stiffness = (omega * omega).toFloat())
                }
                is Curve.Ease -> tween((c.ms * slow).toInt(), easing = CubicBezierEasing(c.x1, c.y1, c.x2, c.y2))
            }
        }
    }
}

val LocalLook = staticCompositionLocalOf<Look> { error("no Look: wrap the content in SettingsTheme") }

/** Gives [content] the theme as a [Look], made again whenever the design or the appearance changes. */
@Composable
fun SettingsTheme(beforeWallpaper: (first: Boolean) -> Unit = {}, content: @Composable () -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    val ctx = LocalContext.current
    // Home's wallpaper (followed as it changes); read here if home has not (this app opened before home ever showed).
    // [beforeWallpaper] is told just before a new one is drawn ([first]: the first, over the plain colour), so the
    // settings can hold how they look and fade into it.
    var wallpaper by remember { mutableStateOf(Wallpaper.current) }
    DisposableEffect(Unit) {
        val l: () -> Unit = {
            Wallpaper.current?.let { if (it !== wallpaper) { beforeWallpaper(wallpaper == null); wallpaper = it } }
        }
        Wallpaper.addListener(android.os.Handler(android.os.Looper.getMainLooper()), l)
        onDispose { Wallpaper.removeListener(l) }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (wallpaper == null) {
            val w = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Wallpaper.load(ctx.applicationContext) }
            if (wallpaper == null && w != null) { beforeWallpaper(true); wallpaper = w }
        }
    }
    DisposableEffect(Unit) {
        val l: () -> Unit = { version++ }
        Design.addListener(l)
        Appearance.addListener(l)
        // The user's setup too (a layout, a placement, an option chosen here).
        dev.launcher.app.layout.Setup.addListener(l)
        onDispose { Design.removeListener(l); Appearance.removeListener(l); dev.launcher.app.layout.Setup.removeListener(l) }
    }
    val d = LocalDensity.current
    val dm = ctx.resources.displayMetrics
    // The whole window, the bars' strips included (the display metrics leave the navigation bar out: the backdrop
    // stopped above it and the window's own colour showed there).
    val (w, h) = remember(dm.widthPixels, dm.heightPixels) {
        val wm = ctx.getSystemService(android.view.WindowManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.let { it.width() to it.height() }
        else android.graphics.Point().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(it) }.let { it.x to it.y }
    }
    val look = remember(version, d.density, d.fontScale, wallpaper) { Look(Scale.unitPx(ctx, minOf(w, h)), d.density, d.fontScale, w, h, version, wallpaper) }
    CompositionLocalProvider(LocalLook provides look) { content() }
}

/** This element as glass [key] with corner [radiusPt] (points), drawn where it is on screen; [press] lightens it. */
fun Modifier.glass(key: MaterialKey, radiusPt: Float, press: () -> Float = { 0f }): Modifier = composed {
    val look = LocalLook.current
    var at by remember { mutableStateOf(Offset.Zero) }
    this
        .onGloballyPositioned { at = it.positionInWindow() }
        .drawBehind {
            drawIntoCanvas { c ->
                look.drawGlass(c.nativeCanvas, key, RectF(0f, 0f, size.width, size.height), radiusPt * look.unit, at.x, at.y, press())
            }
        }
}
