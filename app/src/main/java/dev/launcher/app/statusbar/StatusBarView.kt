package dev.launcher.app.statusbar

import kotlin.math.max
import kotlin.math.min
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.text.format.DateFormat
import android.view.View
import android.view.WindowInsets
import dev.launcher.app.AppLog
import dev.launcher.app.design.Design
import dev.launcher.app.motion.MotionValue
import dev.launcher.app.motion.RoleTiming
import dev.launcher.app.motion.timed
import dev.launcher.app.shade.Notifs
import dev.launcher.app.theme.Fonts
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * The iOS status bar, in white or black like iOS (set from outside through [setDark]).
 * Left: the time, the Focus moon while Do Not Disturb is on, and the icons of the apps with notifications (Android's; iOS
 * shows none), a dot when more than fit. Right: cellular bars (or "No SIM", "No Service", an airplane in airplane mode),
 * the network type when not on Wi-Fi, Wi-Fi, a VPN badge, and the battery with its percentage inside (iOS 16+), green
 * and a bolt while charging, yellow in Low Power mode, red when low.
 *
 * Sizes from the iOS 26 press image (iPhone 16 Pro, pt; one point = width / 402): signal 19.2 x 11.7, Wi-Fi 17 x 11.2,
 * battery 27 x 12.6. Changed with Matheesha (2026-10-06): the time is smaller than iOS's 17 pt (15 pt, its digits about
 * as tall as the signal bars: at 17 pt it looked big next to the icons), and both groups keep the same distance from the
 * screen's edges (iOS centres each group in its "ear" beside the Dynamic Island, which left the time further in than the
 * battery). Everything sits on the camera's centre line when the display has a top cut-out.
 *
 * Every change moves: each element is a slot on springs (its place and its presence), so an icon that arrives grows in
 * and the others make room, one that leaves shrinks away and the others close up; the time's digits roll at the minute
 * change; levels (battery, signal, Wi-Fi) glide; colours cross-fade. Interruptible, like every spring here.
 *
 * Panels: Control Center ([setPanel] cc) carries the right group down to its own status row (the network icons gliding
 * over to the left, the percentage appearing beside the battery), Notification Center (nc) fades the time, which its big
 * clock shows. Content turns white over Control Center and follows the wallpaper over Notification Center. A Control
 * Center without a status row (its layout file's) leaves the bar as it is over it, but for its colour.
 *
 * Over the parts of One UI's own bar that no disable flag hides (StockBarSettings has the rest): while the screen is shared
 * or cast the time sits in a red capsule ([setSharing]; One UI's call and cast chips show then), and on plug-in the battery
 * grows into a charging capsule for as long as One UI's charging animation plays ([chargeMomentWanted]).
 *
 * Lives on gesture nav's UI thread (its own looper): all state changes arrive through [post]. Hides itself when the app
 * in front hides the status bar (immersive). [preview] fixes a state for the dev preview screen.
 */
@SuppressLint("ViewConstructor")
class StatusBarView(ctx: Context) : View(ctx) {
    // The theme's fonts, kept when it changes (B4b: Fonts.Followers).
    private val fonts = Fonts.Followers(this) { relayout(); invalidate() }
    private val time = Paint(Paint.ANTI_ALIAS_FLAG).apply { fonts.text(this, 600); fontFeatureSettings = "'tnum'" }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { fonts.text(this, 600) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT); fonts.text(this, 700); textAlign = Paint.Align.CENTER
        fontFeatureSettings = "'tnum'"
    }
    /** The battery's number written out (the charging capsule's percentage): as [cut] draws it into the battery. */
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { fonts.text(this, 700); fontFeatureSettings = "'tnum'" }
    private val r = RectF()
    private val path = Path()

    private var batteryLevel = 100
    private var charging = false
    private var powerSave = false
    private var airplane = false
    private var dnd = false
    private var vpn = false
    private var simAbsent = false
    private var inService = true
    private var wifiLevel = -1          // -1 = no Wi-Fi, else 1..3 arcs
    private var cellLevel = 0           // 0..4 bars
    private var networkType = ""        // "5G", "LTE", ... (shown when not on Wi-Fi)
    private var dark = 0f               // 0 = white content, 1 = black (animated)
    private var darkTarget = 0f
    private var darkAnim: android.animation.ValueAnimator? = null
    private var previewing = false

    private val cm get() = context.getSystemService(ConnectivityManager::class.java)
    private val tm get() = context.getSystemService(TelephonyManager::class.java)

    /** One point of the iOS layout in px (the design system's: it follows the phone's Display size). */
    private val u get() = dev.launcher.app.design.Scale.unitPx(context)

    init {
        // Not from our own window's insets: an overlay above the status bar is never told the bar is visible (that hid this
        // bar for good). Whether an app hides the status bar comes from the window manager instead ([setHiddenByApp]).
        setOnApplyWindowInsetsListener { _, insets ->
            if (!loggedInsets && Build.VERSION.SDK_INT >= 30) {
                loggedInsets = true
                AppLog.log("[statusbar] own window insets: status bar visible=${insets.isVisible(WindowInsets.Type.statusBars())} (ignored)")
            }
            insets
        }
    }

    private var loggedInsets = false

    /**
     * The app in front hides the status bar (immersive, a full-screen video, a game): so do we, unless a swipe from the top
     * shows it for a moment ([showTransient]) or a panel is open (docs/STATUS_BAR.md).
     */
    fun setHiddenByApp(hidden: Boolean) {
        if (hidden == hiddenByApp) return
        hiddenByApp = hidden
        AppLog.log("[statusbar] ${if (hidden) "hidden (the app in front is full screen)" else "shown (the app in front shows it)"}")
        if (!hidden) { transientUntil = 0L; removeCallbacks(transientEnd) }
        sync()
    }

    var hiddenByApp = false
        private set

    /**
     * A swipe from the top over an app that hides the bar: it shows for [ms] (as Android shows its bars for a moment; the
     * shade can be pulled from it then), again from now on every swipe.
     */
    fun showTransient(ms: Long) {
        if (!hiddenByApp) return
        transientUntil = android.os.SystemClock.uptimeMillis() + ms
        removeCallbacks(transientEnd)
        postDelayed(transientEnd, ms)
        AppLog.log("[statusbar] shown for a moment (a swipe from the top)")
        sync()
    }

    private var transientUntil = 0L
    private val transientEnd = Runnable { transientUntil = 0L; sync() }

    /** Shown for a moment over an app that hides the bar. */
    val transient get() = hiddenByApp && transientUntil > android.os.SystemClock.uptimeMillis()

    /** A panel is open (or opening): the bar shows with it whatever the app in front asks for. */
    private val panelOpen get() = ccOpen > 0.001f || nc > 0.001f

    /**
     * Held back whatever the app asks: home shown turned under an app's landscape (a close; upright-only home is shown at the
     * phone's natural way up until the display turns): the bar along the landscape top would run down home's side.
     */
    fun setSuppressed(on: Boolean) {
        if (on == suppressed) return
        suppressed = on
        AppLog.log("[statusbar] ${if (on) "held back (home shown turned)" else "back"}")
        sync()
    }

    private var suppressed = false

    /** Whether the bar shows now (and the shade takes touches on it). */
    val showing get() = !suppressed && (!hiddenByApp || transient || panelOpen)

    private var shownNow = true

    /** Fades (and slides a touch upward) like the stock bar does, instead of vanishing or appearing in one frame. */
    private fun sync() {
        val want = showing
        if (want == shownNow) return
        shownNow = want
        animate().cancel()
        if (!want) {
            animate().alpha(0f).translationY(-barHeight * 0.25f).timed(dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.STATUSBAR_HIDE)).withEndAction { visibility = INVISIBLE }.start()
        } else {
            visibility = VISIBLE
            animate().alpha(1f).translationY(0f).timed(dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.STATUSBAR_SHOW)).start()
        }
        onHiddenChanged?.invoke(!want)
    }

    /** Told when the bar comes or goes (the shade takes touches on the bar only while it shows). */
    var onHiddenChanged: ((Boolean) -> Unit)? = null

    /** Black content (the app asks for a light status bar, or home's wallpaper is light under it). */
    fun setDark(d: Boolean) {
        val t = if (d) 1f else 0f
        if (t == darkTarget) return
        darkTarget = t
        darkAnim?.cancel()
        darkAnim = android.animation.ValueAnimator.ofFloat(dark, t).apply {
            timed(dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.STATUSBAR_TONE))
            addUpdateListener { dark = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /**
     * Over the lock screen: the time (and the notification icons beside it) fade out, as on iOS's lock screen: the lock
     * screen's own clock shows the time.
     */
    fun setLocked(l: Boolean) {
        val t = if (l) 1f else 0f
        if (t == lockTarget) return
        lockTarget = t
        lockAnim?.cancel()
        lockAnim = android.animation.ValueAnimator.ofFloat(lockK, t).apply {
            timed(dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.STATUSBAR_LOCK))
            addUpdateListener { lockK = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private var lockK = 0f
    private var lockTarget = 0f
    private var lockAnim: android.animation.ValueAnimator? = null

    // ------------------------------------------------------------------ capsules over One UI's own bar

    /**
     * The screen is shared or cast (a capture is running, or a presentation display is on): the time sits in a red capsule
     * with a sharing glyph after it, iOS's screen-recording capsule. It also covers One UI's call and cast chips, which show
     * then whatever our disable flags say.
     */
    fun setSharing(on: Boolean) {
        if (on == sharing) return
        sharing = on
        AppLog.log("[statusbar] ${if (on) "the screen is shared or cast: the time in its capsule" else "no longer shared"}")
        if (!settled) shareK.snapTo(if (on) 1f else 0f) else shareK.animateTo(if (on) 1f else 0f, if (on) APPEAR else LEAVE)
        relayout()
    }

    private var sharing = false
    private val shareK = MotionValue(0f, 100f, { invalidate() })

    /**
     * The sharing capsule covers One UI's cast chip (Samsung): it is sized over it (as tall as the charging capsule, past the
     * chip on the left); elsewhere it is iOS's, close round the time.
     */
    var shareCoversChip = false

    /**
     * One UI plays a charging animation in its own bar when a charger is connected, and no disable flag or setting stops it
     * (it drew under ours, clashing with it: Matheesha). Set where it does: on plug-in the battery grows into an opaque
     * capsule over it ([onChargeMoment] then waits for its end, [endChargeMoment]).
     */
    var chargeMomentWanted = false

    /** The charging moment began: gesture nav waits for One UI's charging animation to end and then ends it. */
    var onChargeMoment: (() -> Unit)? = null

    private var chargeMoment = false
    /** The capsule's outline: 0 the battery's body, 1 the capsule (led on the way, [chargeGeometry]). */
    private val chargeOutline = MotionValue(0f, 100f, { invalidate() })
    /** What it holds: 0 the bar as it is, 1 the percentage, the battery and the bolt in the capsule, the icons beside gone. */
    private val chargeContent = MotionValue(0f, 100f, { invalidate() })
    private val chargeTimeout = Runnable { endChargeMoment() }
    /** When the moment last began (uptime ms): its outline is over One UI's chip in time whatever the springs ([chargeGeometry]). */
    private var chargeStart = 0L
    /** The charging light's clock (ms; counted from frames, at most a 30th of a second each: a stall pauses it). */
    private var shineMs = 0f
    private var shineFrameNs = 0L
    private val shine = RoleTiming(dev.launcher.app.motion.MotionTokens.STATUSBAR_CHARGE_SHIMMER)

    private fun startChargeMoment() {
        if (chargeMoment) {
            // Plugged in again while the capsule stayed for One UI's animation (unplugged meanwhile): One UI may play its
            // animation again, so the capsule now waits for that one.
            AppLog.log("[statusbar] charging moment: plugged in again, the capsule stays")
            removeCallbacks(chargeTimeout)
            postDelayed(chargeTimeout, CHARGE_MAX_MS)
            onChargeMoment?.invoke()
            return
        }
        chargeMoment = true
        AppLog.log("[statusbar] charging moment")
        val now = SystemClock.uptimeMillis()
        chargeStart = now
        // From rest the light starts its first cycle off the battery (one already showing carries on).
        if (chargeOutline.value <= 0.001f && chargeContent.value <= 0.001f) { shineMs = 0f; shineFrameNs = 0L }
        if (outlineFolding) {
            // It was folding back: the capsule carries on from where it is, now on the opening's lead, its right end too
            // (its lead goes on from where that end is).
            val x = chargeOutline.value.coerceIn(0f, 1f)
            val right = chargeRight(foldOf(x))
            val o = leadInverse(foldOf(x))
            val v = chargeOutline.velocity * foldSlope(x) / max(leadSlope(o), MIN_SLOPE)
            outlineFolding = false
            rightStart = now - (foldInverse(right) * CHARGE_RIGHT_MS * dev.launcher.app.motion.Motion.slow()).toLong()
            chargeOutline.snapTo(o)
            chargeOutline.animateTo(1f, CHARGE_OPEN, v)
        } else {
            rightStart = now
            chargeOutline.animateTo(1f, CHARGE_OPEN)
        }
        chargeContent.animateTo(1f, CHARGE_CONTENT)
        removeCallbacks(chargeTimeout)
        postDelayed(chargeTimeout, CHARGE_MAX_MS)
        onChargeMoment?.invoke()
    }

    /**
     * Unplugged during the moment: the capsule stays while One UI's charging animation may still play (it is told to end by
     * One UI's log, [endChargeMoment]); without one it folds back at once.
     */
    var keepCoverOnUnplug: (() -> Boolean)? = null

    /**
     * The charging moment ends: the capsule folds back into the battery, what it holds first (also on unplugging, and after
     * at most 8 s).
     */
    fun endChargeMoment() {
        if (!chargeMoment) return
        // The outline from here on its folding curve ([chargeGeometry]), from the capsule as it is (no jump, its speed kept);
        // its right end, ahead of the rest while it opened, comes back to it as it folds ([chargeRight]).
        val o = chargeOutline.value.coerceIn(0f, 1f)
        val g = chargeGeometry(o, 1f)
        val x = foldInverse(g)
        val v = chargeOutline.velocity * leadSlope(o) / max(foldSlope(x), MIN_SLOPE)
        rightCarry = (chargeRight(g) - g).coerceAtLeast(0f)
        foldFrom = g
        chargeMoment = false
        outlineFolding = true
        removeCallbacks(chargeTimeout)
        chargeOutline.snapTo(x)
        chargeOutline.animateTo(0f, CHARGE_CLOSE, v)
        chargeContent.animateTo(0f, CHARGE_CLOSE)
    }

    /** The outline is folding back (on [foldOf]), not opening (on [leadOf]). */
    private var outlineFolding = false

    /**
     * The capsule's outline (0..1) from its spring's [o]. Opening, led: 1 - (1 - o)^4, so it is over One UI's chip within
     * ~55 ms (the chip shows 65-100 ms after the plug-in on the S24) while what it holds takes its time, and never less
     * than [CHARGE_COVER_AT] after [CHARGE_COVER_MS] (a moment slowed down in the settings would show One UI's chip under
     * its first frames). Folding back, a smoothstep: it holds a little while its contents gather into the battery, then
     * contracts into the battery's body with a short tail (on the lead its last 5 % lingered as a dark ring for ~150 ms).
     * [fold]: what of it Control Center leaves (it folds back as that opens).
     */
    private fun chargeGeometry(o: Float, fold: Float): Float {
        val x = o.coerceIn(0f, 1f)
        val s = sim
        if (if (s != null) s[3] == 0f else outlineFolding) return foldOf(x)
        val led = leadOf(x)
        if (if (s != null) s[3] == 0f else !chargeMoment) return led
        val elapsed = s?.get(2) ?: (SystemClock.uptimeMillis() - chargeStart).toFloat()
        val t = elapsed / (CHARGE_COVER_MS * dev.launcher.app.motion.Motion.slow())
        return max(led, CHARGE_COVER_AT * smooth(t) * fold)
    }

    /**
     * How far the capsule's right end is on its way (0..1). It leads the
     * rest: One UI's chip comes as a small pill at the hidden stock battery's place (x 945-1028 on the S24), its first frame
     * as early as ~30 ms after our first one (One UI's own log 29 ms after ours, 2026-10-19), and only later stretches left
     * over its whole width; so while the moment opens that end is out by [CHARGE_RIGHT_MS], whatever the springs. As the
     * capsule folds, what it was ahead comes back to the rest in step with it. [g]: the outline ([chargeGeometry]); [fold]:
     * what of it Control Center leaves.
     */
    private fun chargeRight(g: Float, fold: Float = 1f): Float {
        val lead = CHARGE_RIGHT_MS * dev.launcher.app.motion.Motion.slow()
        val s = sim
        // (The frames fold from a capsule fully open: nothing is ahead then.)
        if (s != null) return if (s[3] == 0f) g else max(g, smooth(s[2] / lead) * fold)
        if (outlineFolding) return if (foldFrom > 0.001f) (g + rightCarry * (g / foldFrom)).coerceAtMost(1f) else g
        if (!chargeMoment) return g
        return max(g, smooth((SystemClock.uptimeMillis() - rightStart) / lead) * fold)
    }

    /** When the right end's lead began (uptime ms), and what of it is carried into a fold (with the outline it began from). */
    private var rightStart = 0L
    private var rightCarry = 0f
    private var foldFrom = 0f

    private fun leadOf(o: Float) = 1f - (1f - o).let { it * it * it * it }
    private fun leadSlope(o: Float) = 4f * (1f - o).let { it * it * it }
    private fun leadInverse(g: Float) = 1f - Math.pow((1f - g.coerceIn(0f, 1f)).toDouble(), 0.25).toFloat()
    private fun foldOf(x: Float) = x * x * (3f - 2f * x)
    private fun foldSlope(x: Float) = 6f * x * (1f - x)
    private fun foldInverse(g: Float) = (0.5 - Math.sin(Math.asin(1.0 - 2.0 * g.coerceIn(0f, 1f)) / 3.0)).toFloat()

    /** While [renderChargeFrames] draws: the outline, the contents, ms since the start, opening (1) or not, the light's clock. */
    private var sim: FloatArray? = null

    /**
     * For checks: the charging moment drawn at [fps] into PNGs in [dir] (the bar's right half, over [bg]): it opens, holds
     * [holdMs] and closes, each frame at its exact time on the moment's curves (the real drawing; everything else on the bar
     * as it is now). A recording on the emulator shows a few frames a second, and our springs pause through slow frames.
     * One frame a message on the bar's thread (touches go on between them), one picture at a time; [done] gets the count.
     */
    fun renderChargeFrames(dir: java.io.File, fps: Int, holdMs: Long, bg: Int = 0xFF34405A.toInt(), done: (Int) -> Unit = {}) {
        val h = barHeight
        if (width == 0 || h == 0) { done(0); return }
        val open = dev.launcher.app.motion.CurveTiming(CHARGE_OPEN)
        val content = dev.launcher.app.motion.CurveTiming(CHARGE_CONTENT)
        val close = dev.launcher.app.motion.CurveTiming(CHARGE_CLOSE)
        val opened = max(open.durationMs, content.durationMs) + holdMs
        val step = 1000f / fps.coerceIn(10, 240)
        val frames = ((opened + close.durationMs + 50) / step).toInt() + 1
        val b = android.graphics.Bitmap.createBitmap(width, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = Canvas(b)
        val half = width / 2
        // Black content over a light background, as over a light wallpaper.
        val darkness = if (Color.luminance(bg) > 0.5f) 1f else 0f
        fun frame(n: Int) {
            if (n >= frames) { b.recycle(); invalidate(); done(n); return }
            val t = n * step
            val st = if (t < opened) floatArrayOf(open.atMs(t), content.atMs(t), t, 1f, t)
            else (t - opened).let { c -> floatArrayOf(1f - close.atMs(c), 1f - close.atMs(c), t, 0f, t) }
            val wasDark = dark
            sim = st
            dark = darkness
            try { b.eraseColor(bg); draw(canvas) } finally { sim = null; dark = wasDark }
            val crop = android.graphics.Bitmap.createBitmap(b, half, 0, width - half, h)
            try {
                java.io.File(dir, "f%03d_%04dms_o%.3f_k%.3f.png".format(n, t.toInt(), st[0], st[1])).outputStream().use {
                    crop.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            } catch (_: Throwable) { }
            crop.recycle()
            post { frame(n + 1) }
        }
        post { frame(0) }
    }

    /** For checks (adb): the charging moment as on plug-in, ended after [ms] (in slow motion as the springs are). */
    fun testChargeMoment(ms: Long) {
        val hook = onChargeMoment
        onChargeMoment = null
        try { startChargeMoment() } finally { onChargeMoment = hook }
        removeCallbacks(chargeTimeout)
        postDelayed(chargeTimeout, (ms * dev.launcher.app.motion.Motion.slow()).toLong())
    }

    private fun interactive() = try { context.getSystemService(PowerManager::class.java).isInteractive } catch (_: Throwable) { true }

    // ------------------------------------------------------------------ panels (set by the shade every frame)

    /** How far the bar has gone into Control Center's status row (as far as the panel is open; 0 if it has no row). */
    private var cc = 0f
    /** How far Control Center is open (the bar's colour over it, and the bar showing whatever the app asks). */
    private var ccOpen = 0f
    private var nc = 0f
    private var ncDark = 0f
    private var ccDark = 0f

    /**
     * The bar as it shows over home, at rest ([darkContent]: black content), as a picture the bar's height: the settings'
     * picture of home has it on top (home's own picture has no bar, it is our window). On the bar's thread.
     */
    fun pictureOnHome(darkContent: Boolean): android.graphics.Bitmap? {
        val h = barHeight
        if (width == 0 || h == 0) return null
        val b = android.graphics.Bitmap.createBitmap(width, h, android.graphics.Bitmap.Config.ARGB_8888)
        val was = floatArrayOf(dark, cc, nc, lockK, ccOpen)
        dark = if (darkContent) 1f else 0f; cc = 0f; nc = 0f; lockK = 0f; ccOpen = 0f
        // (At rest: a charging moment playing now is not in the picture.)
        picturing = true
        try { draw(Canvas(b)) } finally { dark = was[0]; cc = was[1]; nc = was[2]; lockK = was[3]; ccOpen = was[4]; picturing = false }
        return b
    }

    /**
     * How far Control Center ([ccK]) and Notification Center ([ncK]) are open (0..1, they follow the finger), whether
     * Notification Center's wallpaper wants black content ([ncDarkContent]), and Control Center's veil ([ccDarkContent]: a
     * light one, as Pixel's in light mode; iOS's dark veil keeps it white). [ccRowY]: the centre of Control Center's status
     * row (px, in this view); [ccRowInset]: how far in from each side the row runs (0: the bar's own sides; in landscape the
     * grid's, as iOS 27 lines its one status line up with the controls). [ccHasRow]: false for a Control Center without a
     * status row (its layout file's): the bar stays as it is over it, only its colour following the veil.
     */
    fun setPanel(ccK: Float, ncK: Float, ncDarkContent: Float, ccRowY: Float, ccRowInset: Float = 0f, ccDarkContent: Float = 0f,
                 ccHasRow: Boolean = true) {
        val toRow = if (ccHasRow) ccK else 0f
        if (ccK == ccOpen && toRow == cc && ncK == nc && ncDarkContent == ncDark && ccRowY == ccRow && ccRowInset == ccInset && ccDarkContent == ccDark) return
        ccOpen = ccK; cc = toRow; nc = ncK; ncDark = ncDarkContent; ccRow = ccRowY; ccInset = ccRowInset; ccDark = ccDarkContent
        // (Laid out again within this frame: the panels call this from their animation, before it is drawn.)
        val t = ccK > 0f || ncK > 0f
        if (t != tall) { tall = t; requestLayout() }
        sync()
        invalidate()
    }

    private var ccRow = 0f
    private var ccInset = 0f
    private var rowAlpha = 1f
    private var picturing = false

    /**
     * Android's shade over the bar ([on]): its first stage's header is the bar's row with the date beside the time (drawn by
     * the shade), so the moon and the notification icons make way, shrinking away on their springs; [timeAlpha]: the time
     * gives way to the shade's large clock as Quick Settings expand.
     */
    fun setShadeHeader(on: Boolean, timeAlpha: Float, rowAlpha: Float = 1f, rowShift: Float = 0f, expandedAlpha: Float = 0f,
                       expandedY: Float = 0f, expandedDx: Float = 0f) {
        val a = timeAlpha.coerceIn(0f, 1f)
        val ro = rowAlpha.coerceIn(0f, 1f)
        val ea = expandedAlpha.coerceIn(0f, 1f)
        if (on == header && a == timeFade && ro == headerRowAlpha && rowShift == headerRowShift && ea == headerExpandedAlpha &&
            expandedY == headerExpandedY && expandedDx == headerExpandedDx) return
        val relay = on != header
        header = on
        timeFade = a
        headerRowAlpha = ro; headerRowShift = rowShift; headerExpandedAlpha = ea; headerExpandedY = expandedY
        headerExpandedDx = expandedDx
        if (relay) relayout()
        invalidate()
    }

    private var header = false
    private var timeFade = 1f
    /**
     * As Android's shade expands into Quick Settings, the status icons leave the bar's row (going down [headerRowShift] px
     * and out: [headerRowAlpha]) for the expanded header's date row (centred at [headerExpandedY], coming in:
     * [headerExpandedAlpha]), as its collapsed and expanded headers cross.
     */
    private var headerRowAlpha = 1f
    private var headerRowShift = 0f
    private var headerExpandedAlpha = 0f
    private var headerExpandedY = 0f
    /** How far the expanded copy sits right of the bar's own place (the panel's side, not the bar's). */
    private var headerExpandedDx = 0f

    /** Where the bar's right group ends (px): a panel lines its own rows up with it, or moves it to theirs. */
    val iconsRight: Float get() = width - SIDE_PT * u

    /** Where the time ends (px): Android's shade writes the date after it. */
    val timeRight: Float get() = timeSlot.x.value + clockText.width(time)
    /** Where the time starts, its baseline and its size (px): Android's shade grows its large clock out of it. */
    val timeLeft: Float get() = timeSlot.x.value
    val timeBaseline: Float get() = centreLine() + 0.727f * TIME_PT * u / 2f
    val timeSize: Float get() = TIME_PT * u
    /** How much of the time shows (over the lock screen its clock stands for it: the bar's gives way). */
    val timeShown: Float get() = 1f - lockK

    /** Control Center's status row fades while its edit mode is on (iOS hides it there). */
    fun setRowAlpha(a: Float) {
        if (a == rowAlpha) return
        rowAlpha = a
        invalidate()
    }

    /** The left edge of the time (px), for panels that line their content up with the bar. */
    val sideMargin get() = SIDE_PT * u

    /** Dev preview: a fixed state (every indicator at once when [busy]). */
    fun preview(busy: Boolean, level: Int, isCharging: Boolean, icons: List<Drawable> = emptyList()) {
        previewing = true
        clockText.set("9:41", animate = false)
        batteryLevel = level; charging = isCharging; powerSave = busy && !isCharging
        airplane = false; dnd = busy; vpn = busy; simAbsent = false; inService = true
        wifiLevel = 3; cellLevel = 3; networkType = "5G"
        previewIcons = icons
        settled = false
        relayout()
    }

    private var previewIcons: List<Drawable> = emptyList()

    // ------------------------------------------------------------------ sources

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_BATTERY_CHANGED -> readBattery(i)
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> readPowerSave()
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> readAirplane()
                NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> readDnd()
                else -> updateClock()
            }
            relayout()
        }
    }

    private val wifi = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val rssi = caps.signalStrength
            @Suppress("DEPRECATION")
            val level = if (rssi == Int.MIN_VALUE || rssi == NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) 3 else WifiManager.calculateSignalLevel(rssi, 4)
            post { wifiLevel = level.coerceIn(1, 3); relayout() }
        }
        override fun onLost(network: Network) { post { wifiLevel = -1; relayout() } }
    }

    private val vpnCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { post { vpn = true; relayout() } }
        override fun onLost(network: Network) { post { vpn = false; relayout() } }
    }

    private val telephony: TelephonyCallback? = if (Build.VERSION.SDK_INT >= 31) object : TelephonyCallback(),
        TelephonyCallback.SignalStrengthsListener, TelephonyCallback.ServiceStateListener {
        override fun onSignalStrengthsChanged(ss: SignalStrength) { post { cellLevel = ss.level.coerceIn(0, 4); relayout() } }
        override fun onServiceStateChanged(ss: ServiceState) { post { inService = ss.state == ServiceState.STATE_IN_SERVICE; readSim(); relayout() } }
    } else null

    private val displayInfo: TelephonyCallback? = if (Build.VERSION.SDK_INT >= 31) object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
        override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) { post { networkType = typeLabel(info); relayout() } }
    } else null

    private val notifsChanged: () -> Unit = { readNotifIcons() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        settled = false
        if (previewing) { relayout(); return }
        updateClock()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED); addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED); addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        }
        context.registerReceiver(receiver, f, null, handler)?.let { readBattery(it) }
        readPowerSave(); readAirplane(); readDnd(); readSim()
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifi, handler)
        } catch (t: Throwable) { AppLog.log("[statusbar] Wi-Fi state unavailable: ${t.message}") }
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), vpnCallback, handler)
        } catch (t: Throwable) { AppLog.log("[statusbar] VPN state unavailable: ${t.message}") }
        if (Build.VERSION.SDK_INT >= 31) {
            val exec = context.mainExecutor
            try { telephony?.let { tm.registerTelephonyCallback(exec, it) } } catch (t: Throwable) { AppLog.log("[statusbar] signal strength unavailable: ${t.message}") }
            try { displayInfo?.let { tm.registerTelephonyCallback(exec, it) } } catch (_: Throwable) { /* needs READ_PHONE_STATE: no network type */ }
        }
        Notifs.addListener(handler, notifsChanged)
        readNotifIcons()
        relayout()
    }

    override fun onDetachedFromWindow() {
        if (!previewing) {
            try { context.unregisterReceiver(receiver) } catch (_: Throwable) { }
            try { cm.unregisterNetworkCallback(wifi) } catch (_: Throwable) { }
            try { cm.unregisterNetworkCallback(vpnCallback) } catch (_: Throwable) { }
            if (Build.VERSION.SDK_INT >= 31) {
                try { telephony?.let { tm.unregisterTelephonyCallback(it) } } catch (_: Throwable) { }
                try { displayInfo?.let { tm.unregisterTelephonyCallback(it) } } catch (_: Throwable) { }
            }
            Notifs.removeListener(notifsChanged)
        }
        darkAnim?.cancel()
        super.onDetachedFromWindow()
    }

    private fun updateClock() {
        val c = Calendar.getInstance()
        clockText.set(DateFormat.format(if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm", c).toString(), animate = settled)
    }

    private fun readBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level >= 0 && scale > 0) batteryLevel = level * 100 / scale
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val was = charging
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        // Plugged in where One UI plays its charging animation (not over the lock screen, nor with a panel open: it does not
        // play there; nor while our bar is away).
        if (!was && charging && settled && chargeMomentWanted && showing && lockTarget == 0f && ccOpen == 0f && nc == 0f && !clearSlab && interactive()) startChargeMoment()
        if (was && !charging && chargeMoment) {
            if (keepCoverOnUnplug?.invoke() == true) AppLog.log("[statusbar] unplugged: the capsule stays while One UI's charging animation plays")
            else endChargeMoment()
        }
    }

    private fun readPowerSave() {
        powerSave = try { context.getSystemService(PowerManager::class.java).isPowerSaveMode } catch (_: Throwable) { false }
    }

    private fun readAirplane() {
        airplane = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
    }

    private fun readDnd() {
        dnd = try {
            context.getSystemService(NotificationManager::class.java).currentInterruptionFilter.let {
                it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
            }
        } catch (_: Throwable) { false }
    }

    private fun readSim() {
        simAbsent = try { tm.simState == TelephonyManager.SIM_STATE_ABSENT } catch (_: Throwable) { false }
    }

    private fun typeLabel(info: TelephonyDisplayInfo): String {
        if (Build.VERSION.SDK_INT < 31) return ""
        return when (info.overrideNetworkType) {
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA, TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "5G"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO, TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "LTE+"
            else -> when (info.networkType) {
                TelephonyManager.NETWORK_TYPE_NR -> "5G"
                TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
                TelephonyManager.NETWORK_TYPE_UNKNOWN -> ""
                else -> "E"
            }
        }
    }

    // ------------------------------------------------------------------ notification icons

    /** The apps whose icons show, most important first (their icons load on a worker; see [iconFor]). */
    private var notifApps: List<Pair<String, Notifs.Item>> = emptyList()

    private fun readNotifIcons() {
        if (previewing) return
        notifApps = Notifs.barIcons(context.packageName).map { it.pkg to it }
        for ((_, item) in notifApps) iconFor(item)
        relayout()
    }

    private class LoadedIcon(val key: String, val drawable: Drawable?)
    private val icons = HashMap<String, LoadedIcon>()   // by package (this thread only)

    /** The small icon of [item] as a drawable, loaded on a worker the first time (null until then). */
    private fun iconFor(item: Notifs.Item): Drawable? {
        val ic = item.smallIcon ?: return null
        val key = iconKey(ic)
        val have = icons[item.pkg]
        if (have != null && have.key == key) return have.drawable
        if (loading.add(item.pkg + "|" + key)) {
            val ctx = context
            iconIo.execute {
                val d = try { ic.loadDrawable(ctx)?.mutate() } catch (_: Throwable) { null }
                post {
                    loading.remove(item.pkg + "|" + key)
                    icons[item.pkg] = LoadedIcon(key, d)
                    relayout()
                }
            }
        }
        return have?.drawable
    }

    private val loading = HashSet<String>()

    private fun iconKey(ic: android.graphics.drawable.Icon): String = try {
        if (ic.type == android.graphics.drawable.Icon.TYPE_RESOURCE) "${ic.resPackage}:${ic.resId}" else "obj:${System.identityHashCode(ic)}"
    } catch (_: Throwable) { "obj:${System.identityHashCode(ic)}" }

    // ------------------------------------------------------------------ layout: slots on springs

    /**
     * Something the bar shows: its width, where its left edge is ([x], animated), and how present it is ([shown], 0..1,
     * animated). Slots that are no longer wanted shrink away where they are while the others close up.
     */
    private inner class Slot(val id: String, val draw: (Canvas, Float, Float, Float, Int) -> Unit) {
        var width = 0f
        var wanted = false
        var placed = false
        val x = MotionValue(0f, 1f, { invalidate() })
        val shown = MotionValue(0f, 100f, { invalidate() })

        fun place(target: Float) {
            if (!placed || !settled) { x.snapTo(target); placed = true; return }
            if (kotlin.math.abs(x.target - target) > 0.5f) x.animateTo(target, MOVE)
        }

        fun present(want: Boolean) {
            wanted = want
            val t = if (want) 1f else 0f
            if (!settled) { shown.snapTo(t); return }
            if (shown.target != t) shown.animateTo(t, if (want) APPEAR else LEAVE)
        }
    }

    private val timeSlot = Slot("time") { c, x, cy, k, col -> drawTime(c, x, cy, k, col) }
    private val moonSlot = Slot("moon") { c, x, cy, k, col -> drawMoon(c, x + 6.2f * u, cy, 6.2f * u, col) }
    private val moreSlot = Slot("more") { c, x, cy, _, col -> fill.color = col; c.drawCircle(x + 1.7f * u, cy, 1.7f * u, fill) }
    private val notifSlots = LinkedHashMap<String, NotifSlot>()

    private val boltSlot = Slot("bolt") { c, x, cy, _, col -> drawBolt(c, x + 3.6f * u, cy, 9f * u, col) }
    private val batterySlot = Slot("battery") { c, x, cy, _, col -> drawBattery(c, x, cy, col) }
    private val vpnSlot = Slot("vpn") { c, x, cy, _, col -> drawBadge(c, "VPN", x, cy, col) }
    private val wifiSlot = Slot("wifi") { c, x, cy, _, col -> drawWifi(c, x + 8.5f * u, cy + 5.6f * u, col) }
    private val typeSlot = Slot("type") { c, x, cy, k, col -> typeText.draw(c, x, cy + 0.727f * 12.5f * u / 2f, label.apply { color = col; textSize = 12.5f * u }, k) }
    private val cellSlot = Slot("cell") { c, x, cy, _, col -> drawCell(c, x, cy, col) }

    /** True once the bar has laid itself out after attaching: before that everything snaps (nothing animates in). */
    private var settled = false

    private val leftSlots: List<Slot> get() = buildList { add(timeSlot); add(moonSlot); for (n in notifSlots.values) add(n.slot); add(moreSlot) }
    private val rightSlots: List<Slot> get() = listOf(boltSlot, batterySlot, vpnSlot, wifiSlot, typeSlot, cellSlot)

    // Levels glide instead of jumping.
    private val batteryAnim = MotionValue(100f, 1f, { invalidate() })
    private val cellAnim = MotionValue(0f, 100f, { invalidate() })
    private val wifiAnim = MotionValue(0f, 100f, { invalidate() })
    private val chargeAnim = MotionValue(0f, 100f, { invalidate() })
    private val saveAnim = MotionValue(0f, 100f, { invalidate() })

    private val clockText = RollingText { invalidate() }
    private val typeText = RollingText { invalidate() }
    private val pctLabel = RollingText { invalidate() }
    private var pctText = ""

    /** Works out what shows where and starts the springs that take everything there. Call after any state change. */
    private fun relayout() {
        if (width == 0) return
        val u = u
        // Levels and colours.
        level(batteryAnim, batteryLevel.toFloat())
        level(cellAnim, cellLevel.toFloat())
        level(wifiAnim, wifiLevel.coerceAtLeast(0).toFloat())
        level(chargeAnim, if (charging) 1f else 0f)
        level(saveAnim, if (powerSave && !charging) 1f else 0f)
        typeText.set(networkType, settled)
        pctText = "$batteryLevel%"

        // Left: the time, the moon, notification icons while they fit before the camera, a dot for the rest.
        time.textSize = TIME_PT * u
        // (Sharing: the glyph after the time and the capsule's end are part of its slot, so the icons after it make room,
        // starting a little past the capsule.)
        val sharePad = if (shareCoversChip) SHARE_CHIP_PAD_PT else SHARE_PAD_PT
        timeSlot.width = clockText.width(time) +
            if (sharing) (SHARE_GAP_PT + SHARE_GLYPH_PT + max(0f, sharePad + SHARE_AFTER_PT - TIME_GAP_PT)) * u else 0f
        timeSlot.present(clockText.text.isNotEmpty())
        moonSlot.width = 12.4f * u
        moonSlot.present(dnd && !header)
        val limit = cameraLeft() - 8f * u
        var cursor = SIDE_PT * u + timeSlot.width + TIME_GAP_PT * u + (if (dnd) moonSlot.width + ICON_GAP_PT * u else 0f)
        val want = LinkedHashMap<String, Drawable?>()
        if (previewing) previewIcons.forEachIndexed { i, d -> want["p$i"] = d }
        else for ((pkg, item) in notifApps) want[pkg] = iconFor(item)
        val iconW = ICON_PT * u
        val fitting = LinkedHashMap<String, Drawable>()
        var overflow = false
        for ((id, d) in want) {
            if (d == null) continue   // still loading: it arrives (grows in) once it has loaded
            if (cursor + iconW > limit - (3.4f + ICON_GAP_PT) * u || fitting.size >= MAX_ICONS) { overflow = true; continue }
            fitting[id] = d
            cursor += iconW + ICON_GAP_PT * u
        }
        // In ranking order: the icons that show first, then those shrinking away (they no longer take room).
        val ordered = LinkedHashMap<String, NotifSlot>()
        for ((id, d) in fitting) ordered[id] = (notifSlots[id] ?: NotifSlot(id)).also { it.drawable = d }
        for ((id, s) in notifSlots) if (id !in ordered) ordered[id] = s
        notifSlots.clear()
        notifSlots.putAll(ordered)
        for ((id, s) in notifSlots) { s.slot.width = iconW; s.slot.present(id in fitting && !header) }
        moreSlot.width = 3.4f * u
        moreSlot.present(overflow && !header)
        var x = SIDE_PT * u
        for (s in leftSlots) {
            s.place(x)
            if (s.wanted) x += s.width + (if (s === timeSlot) TIME_GAP_PT else ICON_GAP_PT) * u
        }
        // Slots of apps whose notifications are gone are dropped once they have shrunk away.
        notifSlots.entries.removeAll { (_, s) -> !s.slot.wanted && !s.slot.shown.isAnimating && s.slot.shown.value == 0f }

        // Right, from the edge inwards: bolt, battery, VPN, Wi-Fi or the network type, cellular.
        boltSlot.width = 7.6f * u
        boltSlot.present(charging)
        batterySlot.width = 27f * u
        batterySlot.present(true)
        label.textSize = 9.5f * u
        vpnSlot.width = label.measureText("VPN") + 5f * u
        vpnSlot.present(vpn)
        wifiSlot.width = 17f * u
        wifiSlot.present(wifiLevel > 0)
        label.textSize = 12.5f * u
        typeSlot.width = typeText.width(label)
        typeSlot.present(wifiLevel <= 0 && networkType.isNotEmpty() && !airplane && !simAbsent && inService)
        label.textSize = 13.5f * u
        cellSlot.width = when {
            airplane -> 16.8f * u
            simAbsent -> label.measureText("No SIM")
            !inService -> label.measureText("No Service")
            else -> 4 * 3f * u + 3 * 2.2f * u
        }
        cellSlot.present(true)
        var right = width - SIDE_PT * u
        for (s in rightSlots) {
            s.place(right - s.width)
            if (s.wanted) right -= s.width + (if (s === boltSlot) 2.6f else 6.8f) * u
        }
        settled = true
        invalidate()
    }

    /** A notification icon: its drawable and the slot that moves it. */
    private inner class NotifSlot(id: String) {
        var drawable: Drawable? = null
        val slot = Slot(id) { c, x, cy, _, col -> drawIcon(c, drawable, x, cy, col) }
    }

    private fun level(s: MotionValue, to: Float) {
        if (!settled) { s.snapTo(to); return }
        if (s.target != to) s.animateTo(to, LEVEL)
    }

    /** The left edge of the camera's cut-out on the bar's line (px), or the middle of the screen without one. */
    private fun cameraLeft(): Float {
        val cutout = if (Build.VERSION.SDK_INT >= 29) try { display?.cutout?.boundingRectTop } catch (_: Throwable) { null } else null
        if (cutout != null && !cutout.isEmpty) {
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            return (cutout.left - loc[0]).toFloat()
        }
        return width / 2f - 20f * u
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Only taller or shorter (a panel came or went, [tall]): nothing on the bar moves, nor may it snap.
        if (w == oldw) return
        settled = false
        relayout()
    }

    // ------------------------------------------------------------------ drawing

    /** The line everything is centred on: the camera cut-out's centre if there is one at the top, else iOS's proportion. */
    private fun centreLine(): Float {
        val cutout = if (Build.VERSION.SDK_INT >= 29) try { display?.cutout?.boundingRectTop } catch (_: Throwable) { null } else null
        if (cutout != null && !cutout.isEmpty) {
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            val cy = cutout.exactCenterY() - loc[1]
            if (cy > barHeight * 0.2f && cy < barHeight * 0.8f) return cy
        }
        return barHeight * 0.52f
    }

    /** The bar's own height: the view may be taller (it reaches down to Control Center's status row). */
    var barHeight: Int
        get() = if (barH > 0) barH else height
        set(v) { barH = v }
    private var barH = 0

    // As tall as the bar itself while no panel shows, the whole window while one does (Control Center's status row is
    // further down). A view that changes is drawn again whole: full screen, every step of every animation of the bar (a
    // minute ticking, the tone turning as the App Library comes in, a level moving) redrew the shade's whole window (a
    // trace on the S24: 256 full-window frames in a few App Library swipes).
    private var tall = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val full = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), if (tall || barH <= 0) full else min(full, barH))
    }

    /** Notification Center's clear slab is pulled (the panel counts as open for the charging moment though [nc] is 0 then). */
    @Volatile private var clearSlab = false

    fun setClearSlab(on: Boolean) { clearSlab = on }

    /** The bar as faded by Notification Center's pull over home (1 shown; set by the shade on `motion.nc.bar-fade`). */
    private var fadeK = 1f

    fun setFade(k: Float) {
        if (k == fadeK) return
        fadeK = k
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        if (width == 0) return
        val f = fadeK
        if (picturing || f >= 0.999f) { drawBar(c); return }
        if (f <= 0.003f) return
        // The bar's band only (a layer the size of the screen would be made for the fade); Control Center's row lies lower.
        val n = c.saveLayerAlpha(0f, 0f, width.toFloat(), if (ccOpen > 0.001f) height.toFloat() else min(height, barHeight).toFloat(), (255 * f).toInt())
        drawBar(c)
        c.restoreToCount(n)
    }

    private fun drawBar(c: Canvas) {
        val u = u
        val cy = centreLine()
        // Control Center's veil's choice over it (white over iOS's), the wallpaper's over Notification Center, else the app's
        // or home's.
        val base = dark * (1f - ccOpen) * (1f - nc) + ncDark * nc * (1f - ccOpen) + ccDark * ccOpen
        val k = (255 * (1f - base)).toInt()
        val content = Color.rgb(k, k, k)

        // Left group: fades as either panel opens (the time: Notification Center's clock shows it; Control Center has none).
        val leftK = (1f - cc) * (1f - nc) * (1f - lockK)
        val sk = shareK.value
        if (sk > 0.001f && leftK > 0f) drawShareCapsule(c, cy - cc * 6f * u, sk, leftK)
        for (s in leftSlots) {
            // The time in the capsule is written in the capsule's label colour.
            val col = if (s === timeSlot && sk > 0f) blend(content or (0xFF shl 24), Design.color(StatusBarTokens.CAPSULE_LABEL), sk.coerceIn(0f, 1f)) else content
            drawSlot(c, s, s.x.value, cy - cc * 6f * u, col, leftK)
        }

        // Right group: down to Control Center's status row with cc. The network icons glide over to the row's left side
        // (iOS shows carrier, signal and Wi-Fi there), the percentage appears beside the battery.
        val rowY = ccRow.takeIf { it > 0f } ?: cy
        val y = cy + (rowY - cy) * cc
        // In Control Center's edit mode the row goes (iOS).
        val rightK = 1f - cc * (1f - rowAlpha)
        // Further in than the bar's own sides (landscape: the grid's), as the panel opens.
        val inset = max(0f, ccInset - SIDE_PT * u) * smooth(cc)
        // The charging moment: its capsule's outline and what it holds (both fold back as Control Center opens: the row
        // moves down there). The icons beside the battery dissolve as it comes (shrinking, blurring and fading, as iOS's
        // status items do when the Dynamic Island grows) and come back as it goes.
        val sm = sim
        val co = if (picturing) 0f else (sm?.get(0) ?: chargeOutline.value) * (1f - cc)
        val ck = if (picturing) 0f else (sm?.get(1) ?: chargeContent.value) * (1f - cc)
        val inMoment = co > 0.0005f || ck > 0.0005f
        val dk = if (inMoment) smooth(ck / CHARGE_DISSOLVED_AT) else 0f
        if (dk > 0f) dissolveBlur(DISSOLVE_BLUR_PT * u * dk)
        // Over Android's shade expanding, the group in the bar's row goes down and out, and comes in on the expanded
        // header's date row (the same icons: its collapsed and expanded headers cross).
        val rows = if (header && headerExpandedAlpha > 0.003f) 2 else 1
        for (row in 0 until rows) {
            val ry = if (row == 0) y + (if (header) headerRowShift else 0f) else headerExpandedY
            val rk = rightK * if (!header) 1f else if (row == 0) headerRowAlpha else headerExpandedAlpha
            val dx = if (row == 1) headerExpandedDx else 0f
            if (rk <= 0.003f) continue
            var leftX = SIDE_PT * u + max(0f, ccInset - SIDE_PT * u)
            for (s in listOf(cellSlot, typeSlot, wifiSlot, vpnSlot)) {
                val ccX = leftX
                if (s.wanted) leftX += s.width + 5.6f * u
                drawSlot(c, s, s.x.value + (ccX - s.x.value) * smooth(cc) + dx, ry, content, rk * (1f - dk), 1f - DISSOLVE_SHRINK * dk)
            }
            if (dk > 0f && row == rows - 1) dissolveBlur(0f)
            if (inMoment) {
                drawChargeCapsule(c, ry, co, ck, content, rk, inset - dx)
                // The light through the battery's charge runs while the capsule shows (the springs draw the rest).
                postInvalidateOnAnimation()
            } else {
                // The percentage appears beside the battery (the battery's own number fades out of it).
                drawSlot(c, boltSlot, boltSlot.x.value - inset + dx, ry, content, rk)
                drawSlot(c, batterySlot, batterySlot.x.value - inset + dx, ry, content, rk)
            }
        }
        if (cc > 0f && rightK > 0f) {
            label.color = content
            label.alpha = (255 * cc.coerceIn(0f, 1f) * rightK).toInt()
            label.textSize = 15f * u
            pctLabel.set(pctText, animate = true)
            val right = batterySlot.x.value - inset - 5f * u   // the bolt is on the battery's other side
            val w = pctSlotWidth()
            pctLabel.draw(c, right - w + (1f - cc) * w * 0.3f, y + 0.727f * label.textSize / 2f, label, 1f)
            label.alpha = 255
        }
    }

    private fun pctSlotWidth(): Float { label.textSize = 15f * u; return label.measureText(pctText) }

    private fun smooth(t: Float): Float { val x = t.coerceIn(0f, 1f); return x * x * (3f - 2f * x) }

    /**
     * Draws [s] at [x] on line [cy]: scaled about its centre and faded by how present it is (times [alpha], and scaled by
     * [scaleBy] as well).
     */
    private fun drawSlot(c: Canvas, s: Slot, x: Float, cy: Float, color: Int, alpha: Float, scaleBy: Float = 1f) {
        val k = s.shown.value.coerceIn(0f, 1.2f)
        val a = (k.coerceIn(0f, 1f) * alpha).coerceIn(0f, 1f)
        if (a <= 0.004f) return
        val scale = (0.55f + 0.45f * k) * scaleBy
        val col = (Math.round(a * 255) shl 24) or (color and 0xFFFFFF)
        val save = c.save()
        if (scale != 1f) c.scale(scale, scale, x + s.width / 2f, cy)
        s.draw(c, x, cy, a, col)
        c.restoreToCount(save)
    }

    private fun drawTime(c: Canvas, x: Float, cy: Float, k: Float, col: Int) {
        time.color = if (timeFade >= 1f) col else (Math.round((col ushr 24) * timeFade) shl 24) or (col and 0xFFFFFF)
        time.textSize = TIME_PT * u
        clockText.draw(c, x, cy + 0.727f * time.textSize / 2f, time, 1f)
    }

    private fun drawIcon(c: Canvas, d: Drawable?, x: Float, cy: Float, col: Int) {
        d ?: return
        val s = ICON_PT * u
        d.setBounds(x.toInt(), (cy - s / 2f).toInt(), (x + s).toInt(), (cy + s / 2f).toInt())
        d.setTint(col or (0xFF shl 24))
        d.alpha = (col ushr 24) and 0xFF
        d.draw(c)
    }

    /**
     * The battery starting at [left]; [numberK]: how much of its number is knocked out of it (the percentage beside it).
     * The number is in its middle unless [numCx] says where (the charging capsule takes it out through its side; past the
     * body nothing is knocked out), at [numSize]. [shineAt]: where the charging light is across the body (its middle, as a
     * fraction of the body's width), [shineK] how strong (0: none); it shows on the charge only.
     */
    private fun drawBattery(c: Canvas, left: Float, cy: Float, content: Int, numberK: Float = 1f - cc,
                            numCx: Float = Float.NaN, numSize: Float = NUMBER_PT * u, shineAt: Float = 0f, shineK: Float = 0f) {
        val u = u
        val a = (content ushr 24) and 0xFF
        val nubW = 1.6f * u
        val bodyW = 27f * u - nubW - 0.8f * u
        val bodyH = 12.6f * u
        r.set(left, cy - bodyH / 2f, left + bodyW, cy + bodyH / 2f)
        val rad = 4.2f * u
        val k = Color.red(content)
        val low = ((25f - batteryAnim.value) / 8f).coerceIn(0f, 1f)   // turns red below ~20 %
        var levelColor = blend(content or (0xFF shl 24), dev.launcher.app.design.Design.color(StatusBarTokens.BATTERY_LOW), low)
        levelColor = blend(levelColor, dev.launcher.app.design.Design.color(StatusBarTokens.BATTERY_SAVER), saveAnim.value.coerceIn(0f, 1f))
        levelColor = blend(levelColor, dev.launcher.app.design.Design.color(StatusBarTokens.BATTERY_CHARGING), chargeAnim.value.coerceIn(0f, 1f))
        levelColor = (a shl 24) or (levelColor and 0xFFFFFF)
        // Nub.
        fill.color = Color.argb(102 * a / 255, k, k, k)
        c.drawRoundRect(r.right + 0.8f * u, cy - 2.2f * u, r.right + 0.8f * u + nubW, cy + 2.2f * u, nubW, nubW, fill)
        val lvl = (batteryAnim.value / 100f).coerceIn(0f, 1f)
        // iOS 16+: the body is the gauge (filled to the level, the rest faint), the number knocked out of it. In Control
        // Center's row the number is beside the battery instead: it fades out of the body.
        val layer = c.saveLayer(r.left - u, r.top - u, r.right + u, r.bottom + u, null)
        fill.color = Color.argb(89 * a / 255, k, k, k)
        c.drawRoundRect(r, rad, rad, fill)
        c.save()
        c.clipRect(r.left, r.top, r.left + r.width() * lvl, r.bottom)
        fill.color = levelColor
        c.drawRoundRect(r, rad, rad, fill)
        if (shineK > 0.004f) {
            // A soft band of light across the charge (off the body at both ends of its run).
            val w = bodyW * SHINE_WIDTH
            shineMatrix.setScale(w, 1f)
            shineMatrix.postTranslate(left + bodyW * shineAt - w / 2f, 0f)
            shineShader.setLocalMatrix(shineMatrix)
            fill.shader = shineShader
            fill.color = Color.WHITE
            fill.alpha = (a * shineK.coerceAtMost(1f)).toInt()
            c.drawRoundRect(r, rad, rad, fill)
            fill.shader = null
        }
        c.restore()
        cut.textSize = numSize
        cut.alpha = (255 * numberK).toInt().coerceIn(0, 255)
        if (cut.alpha > 0) pctInside.drawCentered(c, if (numCx.isNaN()) r.centerX() else numCx, cy + 0.727f * cut.textSize / 2f, cut, batteryLevel.toString())
        c.restoreToCount(layer)
    }

    private val shineShader = LinearGradient(0f, 0f, 1f, 0f, intArrayOf(0x00FFFFFF, 0x80FFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    private val shineMatrix = Matrix()

    /** Blurs what the shared paints draw by [px] (0: sharp again): the icons dissolving under the charging capsule. */
    private fun dissolveBlur(px: Float) {
        val step = (px * 2f).toInt()
        val f = if (step <= 0) null else blurs.getOrPut(step) { BlurMaskFilter(step / 2f, BlurMaskFilter.Blur.NORMAL) }
        fill.maskFilter = f; stroke.maskFilter = f; label.maskFilter = f
    }

    private val blurs = HashMap<Int, BlurMaskFilter>()

    private val pctInside = RollingText { invalidate() }

    /**
     * The sharing capsule behind the time ([k]: its presence, 0..1 and past while its spring overshoots): from just before
     * the time to past the glyph after it, growing from its middle; the glyph (two screens, one in front of the other)
     * comes in with it.
     */
    private fun drawShareCapsule(c: Canvas, cy: Float, k: Float, alpha: Float) {
        val u = u
        val dp = resources.displayMetrics.density
        val kk = k.coerceIn(0f, 1f)
        time.textSize = TIME_PT * u
        val clockW = clockText.width(time)
        // Over One UI's cast chip ([shareCoversChip]): as tall as the charging capsule and reaching past the chip on the left,
        // the time in its middle, coming in from closer to its size and solid sooner (the chip never shows through it).
        // Elsewhere iOS's, close round the time: taller than AOSP's chips (20 dp), within a short bar (the emulator's 24 dp).
        val chip = shareCoversChip
        val pad = (if (chip) SHARE_CHIP_PAD_PT else SHARE_PAD_PT) * u
        val left = if (chip) min(timeSlot.x.value - pad, SHARE_CHIP_LEFT_DP * dp) else timeSlot.x.value - pad
        val right = timeSlot.x.value + clockW + (SHARE_GAP_PT + SHARE_GLYPH_PT) * u * kk + pad
        val h = if (chip) min(CHARGE_H_DP * dp, barHeight * 0.9f) else min(max(SHARE_H_PT * u, 21f * dp), barHeight * 0.84f)
        val from = if (chip) SHARE_CHIP_FROM else SHARE_FROM
        val s = from + (1f - from) * k.coerceAtMost(1.1f)
        val save = c.save()
        c.scale(s, s, (left + right) / 2f, cy)
        fill.color = Design.color(StatusBarTokens.SHARING)
        fill.alpha = (Color.alpha(fill.color) * (if (chip) (kk / SHARE_CHIP_SOLID_AT).coerceAtMost(1f) else kk) * alpha).toInt()
        r.set(left, cy - h / 2f, right, cy + h / 2f)
        c.drawRoundRect(r, h / 2f, h / 2f, fill)
        // The glyph: a screen outlined in front of another, filled, behind it to the upper right.
        val labelColor = Design.color(StatusBarTokens.CAPSULE_LABEL)
        val gx = timeSlot.x.value + clockW + SHARE_GAP_PT * u
        val gw = SHARE_GLYPH_PT * u
        val gh = gw * 0.72f
        val a = (255 * kk * kk * alpha).toInt().coerceIn(0, 255)
        fill.color = labelColor
        fill.alpha = (a * 0.55f).toInt()
        c.drawRoundRect(gx + gw * 0.28f, cy - gh * 0.62f, gx + gw, cy + gh * 0.24f, 1.6f * u, 1.6f * u, fill)
        fill.color = Design.color(StatusBarTokens.SHARING)
        fill.alpha = (Color.alpha(fill.color) * kk * alpha).toInt()
        c.drawRoundRect(gx - 0.6f * u, cy - gh * 0.32f - 0.6f * u, gx + gw * 0.72f + 0.6f * u, cy + gh * 0.56f + 0.6f * u, 2f * u, 2f * u, fill)
        stroke.color = labelColor
        stroke.alpha = a
        stroke.strokeWidth = 1.3f * u
        c.drawRoundRect(gx, cy - gh * 0.32f, gx + gw * 0.72f, cy + gh * 0.56f, 1.6f * u, 1.6f * u, stroke)
        c.restoreToCount(save)
    }

    /**
     * The charging moment, drawn over One UI's charging chip (78 x 22 dp at 23 dp from the screen's right edge on the S24,
     * by its own log; One UI lays it out in dp, so this does). [o]: the capsule's outline spring, [k]: what it holds (both
     * already folded by Control Center), [inset]: Control Center's row inset.
     *
     * An opaque capsule (the Dynamic Island's black) grows out from behind the battery's body, on its led outline
     * ([chargeGeometry]). The battery and its bolt move to its right end and take its label colour; the battery's number
     * slides out of it through its left side, growing, and is written on the capsule there (inside the body it is still a
     * hole in it, as the bar draws it), the "%" following it out: the percentage. A light sweeps through the charge while it
     * shows. Back, in reverse: the number slides into the battery, the battery and the bolt go back to their places, and the
     * capsule folds into the body, fading over its last steps.
     */
    private fun drawChargeCapsule(c: Canvas, cy: Float, o: Float, k: Float, content: Int, alpha: Float, inset: Float) {
        val u = u
        val dp = resources.displayMetrics.density
        val kk = k.coerceIn(0f, 1f)
        // At rest: the percentage, the battery and the bolt in a row, in the middle of the capsule, which is never less than
        // over One UI's chip.
        val battW = 27f * u
        val bodyW = battW - 2.4f * u
        val bodyH = 12.6f * u
        val boltGap = 2.6f * u
        val gap = CHARGE_GAP_PT * u
        val level = batteryLevel.toString()
        num.textSize = CHARGE_TEXT_PT * u
        val digitsW = num.measureText(level)
        val signW = num.measureText("%")
        // (The bolt as present as it is: unplugged while the capsule stays, the row closes up as it goes, and opens as it
        // comes back.)
        val rowW = digitsW + signW + gap + battW + (boltGap + boltSlot.width) * boltSlot.shown.value.coerceIn(0f, 1f)
        val pad = CHARGE_PAD_DP * dp
        val capR = width - CHARGE_RIGHT_DP * dp
        val capW = max(CHARGE_W_DP * dp, rowW + 2f * pad)
        val capL = capR - capW
        val battTarget = capL + (capW - rowW) / 2f + digitsW + signW + gap
        val capH = min(CHARGE_H_DP * dp, barHeight * 0.9f)

        // The outline, from the battery's body where the bar has it.
        val slotX = batterySlot.x.value - inset
        val g = chargeGeometry(o, 1f - cc)
        val l = slotX + (capL - slotX) * g
        val rr = slotX + bodyW + (capR - slotX - bodyW) * chargeRight(g, 1f - cc)
        val h = bodyH + (capH - bodyH) * g
        capRect.set(l, cy - h / 2f, rr, cy + h / 2f)
        val rad = 4.2f * u + (h / 2f - 4.2f * u) * g.coerceIn(0f, 1f)
        // Opaque at once, but over its first steps out of the body (as it comes, and its last as it folds back) it fades in:
        // no dark ring pops up around the battery, nor stays as a halo.
        fill.color = Design.color(StatusBarTokens.CAPSULE)
        fill.alpha = (Color.alpha(fill.color) * (g / CHARGE_SOLID_AT).coerceIn(0f, 1f) * alpha).toInt()
        c.drawRoundRect(capRect, rad, rad, fill)

        // The battery and the bolt, keeping their places to each other, in the capsule's label colour as soon as it is under
        // them (on its outline, not on their own way: black content over a light wallpaper vanished on it for 100 ms).
        val labelColor = Design.color(StatusBarTokens.CAPSULE_LABEL)
        val onCapsule = blend(content or (0xFF shl 24), labelColor, smooth(g / CHARGE_TONE_AT))
        val a = (255 * alpha).toInt().coerceIn(0, 255)
        val battX = slotX + (battTarget - slotX) * k
        // The number: from the battery's middle to the percentage's place, from the bar's size to the capsule's.
        val size = NUMBER_PT * u + (CHARGE_TEXT_PT - NUMBER_PT) * u * k
        val wNow = digitsW * size / (CHARGE_TEXT_PT * u)
        val from = battX + bodyW / 2f
        val to = battX - gap - signW - digitsW / 2f
        val numCx = from + (to - from) * k
        // The light: once the battery is in the capsule, a sweep each cycle (its run ends off the body on both sides).
        val sm = sim
        if (sm != null) shineMs = sm[4] else {
            val nowNs = System.nanoTime()
            if (shineFrameNs != 0L) shineMs += ((nowNs - shineFrameNs) / 1e6f).coerceIn(0f, MAX_LOOP_STEP_MS)
            shineFrameNs = nowNs
        }
        val t = shine.get()
        val cycle = t.durationMs.toFloat().coerceAtLeast(1f)
        val shineAt = SHINE_FROM + (SHINE_TO - SHINE_FROM) * t.at((shineMs % cycle) / cycle)
        val shineK = smooth((kk - 0.6f) / 0.4f) * SHINE_STRENGTH
        drawBattery(c, battX, cy, (a shl 24) or (onCapsule and 0xFFFFFF), numberK = 1f - cc, numCx = numCx, numSize = size,
            shineAt = shineAt, shineK = shineK)
        // The bolt beside it: on the capsule as far as the capsule's end has passed it (its end crosses it as it grows and
        // folds), and while the capsule is solid.
        val boltX = boltSlot.x.value - inset + (battX - slotX)
        val boltOn = smooth((rr - boltX) / boltSlot.width) * (g / CHARGE_SOLID_AT).coerceIn(0f, 1f)
        drawSlot(c, boltSlot, boltX, cy, blend(content or (0xFF shl 24), labelColor, boltOn), alpha)

        // Past the battery's side the number is written on the capsule (and never past the capsule's edge as it grows).
        if (k > 0.001f) {
            val save = c.save()
            capClip.reset()
            capClip.addRoundRect(capRect, rad, rad, Path.Direction.CW)
            c.clipPath(capClip)
            bodyClip.reset()
            bodyClip.addRoundRect(battX, cy - bodyH / 2f, battX + bodyW, cy + bodyH / 2f, 4.2f * u, 4.2f * u, Path.Direction.CW)
            c.clipOutPath(bodyClip)
            num.textSize = size
            num.color = labelColor
            num.alpha = (a * (1f - cc)).toInt()
            val baseline = cy + 0.727f * size / 2f
            pctInside.drawCentered(c, numCx, baseline, num, level)
            // The "%" follows the number out.
            val signA = smooth((k - 0.35f) / 0.5f)
            if (signA > 0f) {
                num.alpha = (a * (1f - cc) * signA).toInt()
                c.drawText("%", numCx + wNow / 2f, baseline, num)
            }
            c.restoreToCount(save)
        }
    }

    private val capClip = Path()
    private val bodyClip = Path()
    private val capRect = RectF()

    private fun drawCell(c: Canvas, left: Float, cy: Float, content: Int) {
        val u = u
        val a = (content ushr 24) and 0xFF
        val faint = Color.argb(77 * a / 255, Color.red(content), Color.green(content), Color.blue(content))
        when {
            airplane -> drawPlane(c, left + 8.4f * u, cy, 8.4f * u, content)
            simAbsent -> { label.color = content; label.textSize = 13.5f * u; c.drawText("No SIM", left, cy + 0.727f * label.textSize / 2f, label) }
            !inService -> { label.color = content; label.textSize = 13.5f * u; c.drawText("No Service", left, cy + 0.727f * label.textSize / 2f, label) }
            else -> {
                val barW = 3f * u
                val gap = 2.2f * u
                val heights = floatArrayOf(4.4f, 6.8f, 9.2f, 11.7f)
                val bottom = cy + 5.85f * u
                var x = left
                for (i in 0 until 4) {
                    // A bar fills as the level glides past it.
                    fill.color = blend(faint, content, (cellAnim.value - i).coerceIn(0f, 1f))
                    val h = heights[i] * u
                    c.drawRoundRect(x, bottom - h, x + barW, bottom, 1f * u, 1f * u, fill)
                    x += barW + gap
                }
            }
        }
    }

    /** iOS's VPN badge: the letters in a rounded box, from [left]. */
    private fun drawBadge(c: Canvas, text: String, left: Float, cy: Float, color: Int) {
        val u = u
        label.textSize = 9.5f * u
        val w = label.measureText(text) + 5f * u
        r.set(left, cy - 6f * u, left + w, cy + 6f * u)
        stroke.color = color
        stroke.strokeWidth = 1.2f * u
        c.drawRoundRect(r, 3f * u, 3f * u, stroke)
        label.color = color
        c.drawText(text, r.left + 2.5f * u, cy + 0.727f * label.textSize / 2f, label)
    }

    private fun drawMoon(c: Canvas, cx: Float, cy: Float, rad: Float, color: Int) {
        val layer = c.saveLayer(cx - rad * 2, cy - rad * 2, cx + rad * 2, cy + rad * 2, null)
        fill.color = color
        c.drawCircle(cx, cy, rad, fill)
        cut.alpha = 255
        c.drawCircle(cx + rad * 0.55f, cy - rad * 0.45f, rad * 0.85f, cut)
        c.restoreToCount(layer)
    }

    private fun drawBolt(c: Canvas, cx: Float, cy: Float, h: Float, color: Int) {
        path.reset()
        path.moveTo(cx + h * 0.12f, cy - h * 0.5f)
        path.lineTo(cx - h * 0.3f, cy + h * 0.08f)
        path.lineTo(cx - h * 0.02f, cy + h * 0.08f)
        path.lineTo(cx - h * 0.12f, cy + h * 0.5f)
        path.lineTo(cx + h * 0.3f, cy - h * 0.08f)
        path.lineTo(cx + h * 0.02f, cy - h * 0.08f)
        path.close()
        fill.color = color
        c.drawPath(path, fill)
    }

    private fun drawPlane(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        // Body, wings and tail of a plane pointing right.
        path.reset()
        path.moveTo(cx + s, cy)
        path.lineTo(cx + s * 0.75f, cy - s * 0.12f)
        path.lineTo(cx + s * 0.15f, cy - s * 0.12f)
        path.lineTo(cx - s * 0.25f, cy - s * 0.8f)
        path.lineTo(cx - s * 0.45f, cy - s * 0.8f)
        path.lineTo(cx - s * 0.2f, cy - s * 0.12f)
        path.lineTo(cx - s * 0.65f, cy - s * 0.12f)
        path.lineTo(cx - s * 0.85f, cy - s * 0.38f)
        path.lineTo(cx - s, cy - s * 0.38f)
        path.lineTo(cx - s * 0.85f, cy)
        path.lineTo(cx - s, cy + s * 0.38f)
        path.lineTo(cx - s * 0.85f, cy + s * 0.38f)
        path.lineTo(cx - s * 0.65f, cy + s * 0.12f)
        path.lineTo(cx - s * 0.2f, cy + s * 0.12f)
        path.lineTo(cx - s * 0.45f, cy + s * 0.8f)
        path.lineTo(cx - s * 0.25f, cy + s * 0.8f)
        path.lineTo(cx + s * 0.15f, cy + s * 0.12f)
        path.lineTo(cx + s * 0.75f, cy + s * 0.12f)
        path.close()
        fill.color = color
        c.drawPath(path, fill)
    }

    /** iOS Wi-Fi glyph: three bands of a fan opening upwards from (cx, bottom); bands fill as the level glides past them. */
    private fun drawWifi(c: Canvas, cx: Float, bottom: Float, on: Int) {
        val u = u
        val a = (on ushr 24) and 0xFF
        val off = Color.argb(77 * a / 255, Color.red(on), Color.green(on), Color.blue(on))
        val outer = floatArrayOf(3.4f, 7.4f, 11.2f)
        val band = floatArrayOf(3.4f, 2.6f, 2.6f)
        for (i in 0 until 3) {
            val ro = outer[i] * u
            val ri = (outer[i] - band[i]) * u
            path.reset()
            path.arcTo(cx - ro, bottom - ro, cx + ro, bottom + ro, 225f, 90f, true)
            if (ri > 0.5f) path.arcTo(cx - ri, bottom - ri, cx + ri, bottom + ri, 315f, -90f, false) else path.lineTo(cx, bottom)
            path.close()
            fill.color = blend(off, on, (wifiAnim.value - i).coerceIn(0f, 1f))
            c.drawPath(path, fill)
        }
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        if (t <= 0f) return a
        if (t >= 1f) return b
        fun ch(s: Int) = ((((a shr s) and 0xFF) + ((((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * t)) + 0.5f).toInt() shl s
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    private companion object {
        /** Distance of both groups from the screen's edges (pt). */
        const val SIDE_PT = 34f
        const val TIME_PT = 15f
        const val TIME_GAP_PT = 7f
        const val ICON_PT = 14.5f
        const val ICON_GAP_PT = 4.5f
        /** Notification icons shown at most (Matheesha: more looked cluttered); a dot stands for the rest. */
        const val MAX_ICONS = 3
        /** The sharing capsule: its padding around the time and the glyph, the glyph's gap and size, its height (pt). */
        const val SHARE_PAD_PT = 8f
        const val SHARE_GAP_PT = 5f
        const val SHARE_GLYPH_PT = 12f
        const val SHARE_H_PT = 22f
        /** And grown in from this much of its size; the gap after it before the next icon (pt). */
        const val SHARE_FROM = 0.75f
        const val SHARE_AFTER_PT = 5f
        /**
         * Over One UI's cast chip (S24, from a Link to Windows share: x 70-158, y 34-79 px, 33 x 17 dp at 27 dp from the left
         * edge, its middle 5 px below the camera's line): the padding round the time and the glyph (pt), the farthest left it
         * starts (dp: its rounded end then holds the chip's), from what size it grows in, opaque from how far in.
         */
        const val SHARE_CHIP_PAD_PT = 13f
        const val SHARE_CHIP_LEFT_DP = 21f
        const val SHARE_CHIP_FROM = 0.94f
        const val SHARE_CHIP_SOLID_AT = 0.12f
        /**
         * The charging capsule over One UI's charging animation (dp, as One UI lays that out): its right inset, width, height,
         * padding. One UI's chip on the S24 (its own log): 205 x 58 px at x 815, y 26 (78 x 22 dp, 23 dp from the right edge,
         * its middle 4 px below the camera's line, where our capsule is centred): ours reaches 10 dp past it to the left,
         * 9 dp to the right, 4 px below.
         */
        const val CHARGE_RIGHT_DP = 14f
        const val CHARGE_W_DP = 96f
        const val CHARGE_H_DP = 28f
        const val CHARGE_PAD_DP = 10f
        /** The longest a charging moment lasts if One UI's animation never says it ended (it takes ~4 s). */
        const val CHARGE_MAX_MS = 8000L
        /**
         * Not a timing but a deadline: One UI's chip shows 65-100 ms after the plug-in (S24), so the outline is at least
         * [CHARGE_COVER_AT] of the way (the chip's whole box covered) this long after the moment began, whatever the springs.
         */
        const val CHARGE_COVER_MS = 70f
        const val CHARGE_COVER_AT = 0.85f
        /**
         * Also a deadline: the capsule's right end is out this long after the moment began (One UI's chip comes first as a
         * small pill at the stock battery's place, x 945-1028 on the S24, as early as ~30 ms after our first frame).
         */
        const val CHARGE_RIGHT_MS = 25f
        /** The capsule is opaque from this much of its outline on (fading in over its first steps out of the battery). */
        const val CHARGE_SOLID_AT = 0.15f
        /** What is on the capsule is in its label colour from this much of its outline on. */
        const val CHARGE_TONE_AT = 0.5f
        /** The battery's number (pt) in the bar, and as the capsule's percentage; the gap between it and the battery. */
        const val NUMBER_PT = 9.6f
        const val CHARGE_TEXT_PT = 14f
        const val CHARGE_GAP_PT = 6f
        /** The icons beside the battery are gone at this much of the capsule's contents, shrunk by this and blurred this much (pt). */
        const val CHARGE_DISSOLVED_AT = 0.6f
        const val DISSOLVE_SHRINK = 0.3f
        const val DISSOLVE_BLUR_PT = 2.5f
        /**
         * The charging light: its width and its run across the battery's body (fractions of the body: it starts and ends off
         * it, so a cycle rests at both ends), its strength.
         */
        const val SHINE_WIDTH = 0.6f
        const val SHINE_FROM = -0.6f
        const val SHINE_TO = 1.6f
        const val SHINE_STRENGTH = 0.9f
        /** A loop nobody drives counts a long frame as this much at most (ms): a stall pauses it instead of skipping. */
        const val MAX_LOOP_STEP_MS = 1000f / 30f
        /** The least slope a speed is carried across with, from one curve of the outline to the other (where one is flat). */
        const val MIN_SLOPE = 0.05f
        val CHARGE_OPEN get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.STATUSBAR_CHARGE_OPEN)
        val CHARGE_CONTENT get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.STATUSBAR_CHARGE_CONTENT)
        val CHARGE_CLOSE get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.STATUSBAR_CHARGE_CLOSE)
        val MOVE get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.STATUSBAR_MOVE)
        val APPEAR get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.STATUSBAR_APPEAR)
        val LEAVE get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.STATUSBAR_LEAVE)
        val LEVEL get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.STATUSBAR_LEVEL)
        val iconIo = Executors.newSingleThreadExecutor()
    }
}

/**
 * A short text that changes by rolling: characters that change slide up and out while the new ones rise in (digits are
 * tabular, so the others keep their place); a change of length rolls the whole text. [onFrame] redraws.
 */
class RollingText(private val onFrame: () -> Unit) {
    var text = ""
        private set
    private var old = ""
    private val k = MotionValue(1f, 100f, { onFrame() })

    fun set(t: String, animate: Boolean) {
        if (t == text) return
        if (!animate || text.isEmpty()) { text = t; old = t; k.snapTo(1f); onFrame(); return }
        old = if (k.value < 0.5f) old else text   // interrupted: carry on from what shows most
        text = t
        k.snapTo(0f)
        k.animateTo(1f, ROLL)
    }

    fun width(p: Paint): Float = p.measureText(text)

    /** Draws with the text's left edge at [x] on [baseline]. [alpha] multiplies the paint's alpha. */
    fun draw(c: Canvas, x: Float, baseline: Float, p: Paint, alpha: Float) = drawAt(c, x, baseline, p, alpha, text, false)

    /** As [draw], centred on [cx], showing [t] (set first). */
    fun drawCentered(c: Canvas, cx: Float, baseline: Float, p: Paint, t: String) {
        set(t, animate = true)
        drawAt(c, cx, baseline, p, 1f, text, true)
    }

    private fun drawAt(c: Canvas, x: Float, baseline: Float, p: Paint, alpha: Float, t: String, centred: Boolean) {
        val baseAlpha = p.alpha
        val align = p.textAlign
        p.textAlign = Paint.Align.LEFT
        val kv = k.value.coerceIn(0f, 1f)
        val rise = p.textSize * 0.55f
        val newW = p.measureText(t)
        val oldW = p.measureText(old)
        val nx = if (centred) x - newW / 2f else x
        val ox = if (centred) x - oldW / 2f else x
        if (kv >= 1f || old == t) {
            p.alpha = (baseAlpha * alpha).toInt()
            c.drawText(t, nx, baseline, p)
        } else if (old.length == t.length) {
            // Per character: unchanged ones stay, changed ones roll.
            var cx = nx
            for (i in t.indices) {
                val w = p.measureText(t, i, i + 1)
                if (t[i] == old[i]) {
                    p.alpha = (baseAlpha * alpha).toInt()
                    c.drawText(t, i, i + 1, cx, baseline, p)
                } else {
                    p.alpha = (baseAlpha * alpha * (1f - kv)).toInt()
                    c.drawText(old, i, i + 1, cx, baseline - rise * kv, p)
                    p.alpha = (baseAlpha * alpha * kv).toInt()
                    c.drawText(t, i, i + 1, cx, baseline + rise * (1f - kv), p)
                }
                cx += w
            }
        } else {
            p.alpha = (baseAlpha * alpha * (1f - kv)).toInt()
            c.drawText(old, ox, baseline - rise * kv, p)
            p.alpha = (baseAlpha * alpha * kv).toInt()
            c.drawText(t, nx, baseline + rise * (1f - kv), p)
        }
        p.alpha = baseAlpha
        p.textAlign = align
    }

    private companion object {
        val ROLL get() = dev.launcher.app.motion.Motion.role(dev.launcher.app.motion.MotionTokens.STATUSBAR_ROLL)
    }
}
