package dev.launcher.app.statusbar

import kotlin.math.max
import kotlin.math.min
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
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
 * clock shows. Content turns white over Control Center and follows the wallpaper over Notification Center.
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
    private val panelOpen get() = cc > 0.001f || nc > 0.001f

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
     * One UI plays a charging animation in its own bar when a charger is connected, and no disable flag stops it (it drew under
     * ours, clashing with it: Matheesha). Set where it does: on plug-in the battery grows into an opaque capsule over it
     * ([onChargeMoment] then waits for its end, [endChargeMoment]).
     */
    var chargeMomentWanted = false

    /** The charging moment began: gesture nav waits for One UI's charging animation to end and then ends it. */
    var onChargeMoment: (() -> Unit)? = null

    private var chargeMoment = false
    private val chargeK = MotionValue(0f, 100f, { invalidate() })
    private val chargeTimeout = Runnable { endChargeMoment() }

    private fun startChargeMoment() {
        if (chargeMoment) return
        chargeMoment = true
        AppLog.log("[statusbar] charging moment")
        chargeK.animateTo(1f, APPEAR)
        removeCallbacks(chargeTimeout)
        postDelayed(chargeTimeout, CHARGE_MAX_MS)
        onChargeMoment?.invoke()
    }

    /** The charging moment ends: the capsule shrinks back into the battery (also on unplugging, and after at most 8 s). */
    fun endChargeMoment() {
        if (!chargeMoment) return
        chargeMoment = false
        removeCallbacks(chargeTimeout)
        chargeK.animateTo(0f, MOVE)
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

    private var cc = 0f
    private var nc = 0f
    private var ncDark = 0f

    /**
     * The bar as it shows over home, at rest ([darkContent]: black content), as a picture the bar's height: the settings'
     * picture of home has it on top (home's own picture has no bar, it is our window). On the bar's thread.
     */
    fun pictureOnHome(darkContent: Boolean): android.graphics.Bitmap? {
        val h = barHeight
        if (width == 0 || h == 0) return null
        val b = android.graphics.Bitmap.createBitmap(width, h, android.graphics.Bitmap.Config.ARGB_8888)
        val was = floatArrayOf(dark, cc, nc, lockK)
        dark = if (darkContent) 1f else 0f; cc = 0f; nc = 0f; lockK = 0f
        try { draw(Canvas(b)) } finally { dark = was[0]; cc = was[1]; nc = was[2]; lockK = was[3] }
        return b
    }

    /**
     * How far Control Center ([ccK]) and Notification Center ([ncK]) are open (0..1, they follow the finger), and whether
     * Notification Center's wallpaper wants black content ([ncDarkContent]). [ccRowY]: the centre of Control Center's
     * status row (px, in this view); [ccRowInset]: how far in from each side the row runs (0: the bar's own sides; in
     * landscape the grid's, as iOS 27 lines its one status line up with the controls).
     */
    fun setPanel(ccK: Float, ncK: Float, ncDarkContent: Float, ccRowY: Float, ccRowInset: Float = 0f) {
        if (ccK == cc && ncK == nc && ncDarkContent == ncDark && ccRowY == ccRow && ccRowInset == ccInset) return
        cc = ccK; nc = ncK; ncDark = ncDarkContent; ccRow = ccRowY; ccInset = ccRowInset
        // (Laid out again within this frame: the panels call this from their animation, before it is drawn.)
        val t = ccK > 0f || ncK > 0f
        if (t != tall) { tall = t; requestLayout() }
        sync()
        invalidate()
    }

    private var ccRow = 0f
    private var ccInset = 0f
    private var rowAlpha = 1f

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
        if (!was && charging && settled && chargeMomentWanted && showing && lockTarget == 0f && cc == 0f && nc == 0f && interactive()) startChargeMoment()
        if (was && !charging) endChargeMoment()
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
        // (Sharing: the glyph after the time is part of its slot, so the icons after it make room.)
        timeSlot.width = clockText.width(time) + if (sharing) (SHARE_GAP_PT + SHARE_GLYPH_PT) * u else 0f
        timeSlot.present(clockText.text.isNotEmpty())
        moonSlot.width = 12.4f * u
        moonSlot.present(dnd)
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
        for ((id, s) in notifSlots) { s.slot.width = iconW; s.slot.present(id in fitting) }
        moreSlot.width = 3.4f * u
        moreSlot.present(overflow)
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

    override fun onDraw(c: Canvas) {
        if (width == 0) return
        val u = u
        val cy = centreLine()
        // White over Control Center, the wallpaper's choice over Notification Center, else the app's or home's.
        val base = dark * (1f - cc) * (1f - nc) + ncDark * nc * (1f - cc)
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
        // The charging moment: the icons under its capsule make way, the battery and the bolt move into it (it fades as
        // Control Center opens: the row moves down there).
        val mk = chargeK.value.coerceIn(0f, 1f) * (1f - cc)
        var leftX = SIDE_PT * u + max(0f, ccInset - SIDE_PT * u)
        for (s in listOf(cellSlot, typeSlot, wifiSlot, vpnSlot)) {
            val ccX = leftX
            if (s.wanted) leftX += s.width + 5.6f * u
            drawSlot(c, s, s.x.value + (ccX - s.x.value) * smooth(cc), y, content, rightK * (1f - mk))
        }
        if (mk > 0.001f) {
            drawChargeCapsule(c, y, chargeK.value * (1f - cc), content, rightK)
        } else {
            // The percentage appears beside the battery (the battery's own number fades out of it).
            drawSlot(c, boltSlot, boltSlot.x.value - inset, y, content, rightK)
            drawSlot(c, batterySlot, batterySlot.x.value - inset, y, content, rightK)
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

    /** Draws [s] at [x] on line [cy]: scaled about its centre and faded by how present it is (times [alpha]). */
    private fun drawSlot(c: Canvas, s: Slot, x: Float, cy: Float, color: Int, alpha: Float) {
        val k = s.shown.value.coerceIn(0f, 1.2f)
        val a = (k.coerceIn(0f, 1f) * alpha).coerceIn(0f, 1f)
        if (a <= 0.004f) return
        val scale = 0.55f + 0.45f * k
        val col = (Math.round(a * 255) shl 24) or (color and 0xFFFFFF)
        val save = c.save()
        if (scale != 1f) c.scale(scale, scale, x + s.width / 2f, cy)
        s.draw(c, x, cy, a, col)
        c.restoreToCount(save)
    }

    private fun drawTime(c: Canvas, x: Float, cy: Float, k: Float, col: Int) {
        time.color = col
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

    /** The battery starting at [left]; [numberK]: how much of its number is knocked out of it (the percentage beside it). */
    private fun drawBattery(c: Canvas, left: Float, cy: Float, content: Int, numberK: Float = 1f - cc) {
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
        c.restore()
        cut.textSize = 9.6f * u
        cut.alpha = (255 * numberK).toInt().coerceIn(0, 255)
        if (cut.alpha > 0) pctInside.drawCentered(c, r.centerX(), cy + 0.727f * cut.textSize / 2f, cut, batteryLevel.toString())
        c.restoreToCount(layer)
    }

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
        val pad = SHARE_PAD_PT * u
        val left = timeSlot.x.value - pad
        val right = timeSlot.x.value + clockW + (SHARE_GAP_PT + SHARE_GLYPH_PT) * u * kk + pad
        // Taller than One UI's chips (20 dp), within a short bar (the emulator's is 24 dp).
        val h = min(max(SHARE_H_PT * u, 21f * dp), barHeight * 0.84f)
        val s = 0.75f + 0.25f * k.coerceAtMost(1.1f)
        val save = c.save()
        c.scale(s, s, (left + right) / 2f, cy)
        fill.color = Design.color(StatusBarTokens.SHARING)
        fill.alpha = (Color.alpha(fill.color) * kk * alpha).toInt()
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
     * The charging capsule ([k]: its presence, past 1 while its spring overshoots): it grows out of the battery's body over
     * One UI's charging animation (22 x ~76 dp at 19 dp from the screen's right edge on the camera's line, measured on the
     * S24; One UI lays it out in dp, so this does), the battery and its bolt moving into it as it grows and the percentage
     * appearing beside them, on the capsule in its label colour (the battery keeps its charging green). The battery is drawn
     * throughout (as the moment starts it is still in its place, the capsule darkening around it).
     */
    private val capClip = Path()
    private val capRect = RectF()

    private fun drawChargeCapsule(c: Canvas, cy: Float, k: Float, content: Int, alpha: Float) {
        val u = u
        val dp = resources.displayMetrics.density
        val kk = k.coerceIn(0f, 1f)
        val battW = 27f * u
        // Where the battery and the bolt go: the capsule's right end, inset.
        val capR = width - CHARGE_RIGHT_DP * dp
        val battTarget = capR - CHARGE_PAD_DP * dp - boltSlot.width - 2.6f * u - battW
        // The percentage beside them sets how far left the capsule reaches, never less than over One UI's animation.
        label.textSize = 14f * u
        val pctW = label.measureText(pctText)
        val capL = min(capR - CHARGE_W_DP * dp, battTarget - 6f * u - pctW - CHARGE_PAD_DP * dp)
        val capH = min(CHARGE_H_DP * dp, barHeight * 0.9f)
        // From the battery's body.
        val bodyL = batterySlot.x.value
        val bodyR = bodyL + battW - 2.4f * u
        val bodyH = 12.6f * u
        // The capsule's outline leads its spring: One UI's pill shows ~100 ms after the plug-in at its full size, and with the
        // outline on the spring itself its right end showed past ours for five frames (S24 recording; our bar also held a
        // frame for 40 ms then). Led, the outline is over the pill's area within ~75 ms; it also stays a moment longer as
        // it shrinks, then folds into the battery. Past 1 (the spring's overshoot) it follows the spring.
        val g = if (k >= 1f) k else 1f - (1f - k).let { it * it * it * it }
        val l = bodyL + (capL - bodyL) * g
        val rr = bodyR + (capR - bodyR) * g
        val h = bodyH + (capH - bodyH) * g
        fill.color = Design.color(StatusBarTokens.CAPSULE)
        // (Opaque from 60 % of its outline on: when it folds back into the battery it fades over its last part, no halo.)
        fill.alpha = (Color.alpha(fill.color) * (g / 0.6f).coerceAtMost(1f) * alpha).toInt()
        // (A rect of its own: drawBattery below sets [r] to the battery's body.)
        capRect.set(l, cy - h / 2f, rr, cy + h / 2f)
        val rad = 4.2f * u + (h / 2f - 4.2f * u) * g.coerceAtMost(1f)
        c.drawRoundRect(capRect, rad, rad, fill)
        // The battery and the bolt move in, keeping their places to each other, and take the capsule's label colour; the
        // battery's number goes (the percentage is beside it). The percentage is inside the capsule as it is now (it never
        // shows past the capsule's edge while that grows or shrinks).
        val labelColor = Design.color(StatusBarTokens.CAPSULE_LABEL)
        val onCapsule = blend(content or (0xFF shl 24), labelColor, kk)
        val a = (255 * alpha).toInt().coerceIn(0, 255)
        val dx = (battTarget - batterySlot.x.value) * kk
        drawBattery(c, batterySlot.x.value + dx, cy, (a shl 24) or (onCapsule and 0xFFFFFF), numberK = (1f - cc) * (1f - kk))
        drawSlot(c, boltSlot, boltSlot.x.value + dx, cy, onCapsule, alpha)
        val t = ((kk - 0.65f) / 0.35f).coerceIn(0f, 1f)
        if (t > 0f) {
            val save = c.save()
            capClip.reset()
            capClip.addRoundRect(capRect, rad, rad, Path.Direction.CW)
            c.clipPath(capClip)
            label.color = labelColor
            label.alpha = (255 * t * alpha).toInt()
            // (Beside the battery where it is now: it is still moving in.)
            c.drawText(pctText, batterySlot.x.value + dx - 6f * u - pctW + (1f - t) * 3f * u, cy + 0.727f * label.textSize / 2f, label)
            label.alpha = 255
            c.restoreToCount(save)
        }
    }

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
