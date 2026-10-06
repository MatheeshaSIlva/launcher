package dev.launcher.app.statusbar

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
import dev.launcher.app.theme.Fonts
import java.util.Calendar

/**
 * The iOS status bar, in white or black like iOS (set from outside through [setDark]).
 * Left: the time (and the Focus moon while Do Not Disturb is on). Right: cellular bars (or "No SIM", "No Service", an
 * airplane in airplane mode), the network type when not on Wi-Fi, Wi-Fi, a VPN badge, and the battery with its percentage
 * inside (iOS 16+), green and a bolt while charging, yellow in Low Power mode, red when low.
 *
 * Measured on the iOS 26 press image (iPhone 16 Pro, pt): time SF 17 semibold centred 73.7 from the left; right group
 * right-aligned 35.4 from the edge: signal 19.2 x 11.7, Wi-Fi 17 x 11.2, battery 27 x 12.6, 7.5 apart. One point = width / 402.
 * Everything sits on the camera's centre line when the display has a top cut-out (as iOS centres on the Dynamic Island).
 *
 * Lives in an overlay window on gesture nav's UI thread: all state changes arrive through [post]. Hides itself when the app in
 * front hides the status bar (immersive). [preview] fixes a state for the dev preview screen.
 */
@SuppressLint("ViewConstructor")
class StatusBarView(ctx: Context) : View(ctx) {
    private val time = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.text(600) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.text(600) }
    private val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.text(700); textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT); typeface = Fonts.text(700); textAlign = Paint.Align.CENTER }
    private val r = RectF()
    private val path = Path()

    private var clock = ""
    private var batteryLevel = 100
    private var charging = false
    private var powerSave = false
    private var airplane = false
    private var dnd = false
    private var vpn = false
    private var simAbsent = false
    private var inService = true
    private var showPercent = true
    private var wifiLevel = -1          // -1 = no Wi-Fi, else 1..3 arcs
    private var cellLevel = 0           // 0..4 bars
    private var networkType = ""        // "5G", "LTE", ... (shown when not on Wi-Fi)
    private var dark = 0f               // 0 = white content, 1 = black (animated)
    private var darkTarget = 0f
    private var darkAnim: android.animation.ValueAnimator? = null
    private var previewing = false

    private val cm get() = context.getSystemService(ConnectivityManager::class.java)
    private val tm get() = context.getSystemService(TelephonyManager::class.java)

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

    /** The app in front hides the status bar (immersive): so do we. */
    fun setHiddenByApp(hidden: Boolean) {
        if (hidden == hiddenByApp) return
        hiddenByApp = hidden
        AppLog.log("[statusbar] ${if (hidden) "hidden (the app in front is full screen)" else "shown"}")
        // Fades (and slides a touch upward) like the stock bar does, instead of vanishing or appearing in one frame.
        animate().cancel()
        if (hidden) {
            animate().alpha(0f).translationY(-height * 0.25f).setDuration(160).withEndAction { visibility = INVISIBLE }.start()
        } else {
            visibility = VISIBLE
            animate().alpha(1f).translationY(0f).setDuration(200).start()
        }
    }

    private var hiddenByApp = false

    /** Black content (the app asks for a light status bar, or home's wallpaper is light under it). */
    fun setDark(d: Boolean) {
        val t = if (d) 1f else 0f
        if (t == darkTarget) return
        darkTarget = t
        darkAnim?.cancel()
        darkAnim = android.animation.ValueAnimator.ofFloat(dark, t).apply {
            duration = 220
            addUpdateListener { dark = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** Dev preview: a fixed state (every indicator at once when [busy]). */
    fun preview(busy: Boolean, level: Int, isCharging: Boolean) {
        previewing = true
        clock = "9:41"
        batteryLevel = level; charging = isCharging; powerSave = busy && !isCharging
        airplane = false; dnd = busy; vpn = busy; simAbsent = false; inService = true
        wifiLevel = 3; cellLevel = 3; networkType = "5G"
        invalidate()
    }

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
        }
    }

    private val wifi = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val rssi = caps.signalStrength
            @Suppress("DEPRECATION")
            val level = if (rssi == Int.MIN_VALUE || rssi == NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) 3 else WifiManager.calculateSignalLevel(rssi, 4)
            post { wifiLevel = level.coerceIn(1, 3); invalidate() }
        }
        override fun onLost(network: Network) { post { wifiLevel = -1; invalidate() } }
    }

    private val vpnCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { post { vpn = true; invalidate() } }
        override fun onLost(network: Network) { post { vpn = false; invalidate() } }
    }

    private val telephony: TelephonyCallback? = if (Build.VERSION.SDK_INT >= 31) object : TelephonyCallback(),
        TelephonyCallback.SignalStrengthsListener, TelephonyCallback.ServiceStateListener {
        override fun onSignalStrengthsChanged(ss: SignalStrength) { post { cellLevel = ss.level.coerceIn(0, 4); invalidate() } }
        override fun onServiceStateChanged(ss: ServiceState) { post { inService = ss.state == ServiceState.STATE_IN_SERVICE; readSim(); invalidate() } }
    } else null

    private val displayInfo: TelephonyCallback? = if (Build.VERSION.SDK_INT >= 31) object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
        override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) { post { networkType = typeLabel(info); invalidate() } }
    } else null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (previewing) return
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
        }
        darkAnim?.cancel()
        super.onDetachedFromWindow()
    }

    private fun updateClock() {
        val c = Calendar.getInstance()
        clock = DateFormat.format(if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm", c).toString()
        invalidate()
    }

    private fun readBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level >= 0 && scale > 0) batteryLevel = level * 100 / scale
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        invalidate()
    }

    private fun readPowerSave() {
        powerSave = try { context.getSystemService(PowerManager::class.java).isPowerSaveMode } catch (_: Throwable) { false }
        invalidate()
    }

    private fun readAirplane() {
        airplane = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        invalidate()
    }

    private fun readDnd() {
        dnd = try {
            context.getSystemService(NotificationManager::class.java).currentInterruptionFilter.let {
                it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
            }
        } catch (_: Throwable) { false }
        invalidate()
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

    // ------------------------------------------------------------------ drawing

    /** The line everything is centred on: the camera cut-out's centre if there is one at the top, else iOS's proportion. */
    private fun centreLine(): Float {
        val cutout = if (Build.VERSION.SDK_INT >= 29) try { display?.cutout?.boundingRectTop } catch (_: Throwable) { null } else null
        if (cutout != null && !cutout.isEmpty) {
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            val cy = cutout.exactCenterY() - loc[1]
            if (cy > height * 0.2f && cy < height * 0.8f) return cy
        }
        return height * 0.52f
    }

    override fun onDraw(c: Canvas) {
        val u = width / 402f
        val cy = centreLine()
        val k = (255 * (1f - dark)).toInt()
        val content = Color.rgb(k, k, k)
        val faint = Color.argb(77, k, k, k)

        // Time, and the Focus moon after it while Do Not Disturb is on.
        time.color = content
        time.textSize = 16.8f * u
        val tw = time.measureText(clock)
        val tx = 73.7f * u - tw / 2f
        c.drawText(clock, tx, cy + 0.727f * time.textSize / 2f, time)
        if (dnd) drawMoon(c, tx + tw + 12f * u, cy, 6.2f * u, content)

        // Battery (right-aligned), the percentage inside; a bolt after it while charging.
        var right = width - 35.4f * u
        if (charging) {
            drawBolt(c, right - 3.6f * u, cy, 9f * u, content)
            right -= 7.6f * u + 2.6f * u
        }
        right = drawBattery(c, right, cy, u, content) - 6.8f * u

        // VPN badge.
        if (vpn) right = drawBadge(c, "VPN", right, cy, u, content) - 6f * u

        // Wi-Fi, or the mobile network type when not on Wi-Fi.
        if (wifiLevel > 0) {
            drawWifi(c, right - 8.5f * u, cy + 5.6f * u, u, wifiLevel, content, faint)
            right -= 17f * u + 6.4f * u
        } else if (networkType.isNotEmpty() && !airplane && !simAbsent && inService) {
            right = drawLabel(c, networkType, right, cy, 12.5f * u, content) - 5.6f * u
        }

        // Cellular: bars, or what iOS says when there are none.
        when {
            airplane -> drawPlane(c, right - 8f * u, cy, 8.4f * u, content)
            simAbsent -> drawLabel(c, "No SIM", right, cy, 13.5f * u, content)
            !inService -> drawLabel(c, "No Service", right, cy, 13.5f * u, content)
            else -> {
                val barW = 3f * u
                val gap = 2.2f * u
                val heights = floatArrayOf(4.4f, 6.8f, 9.2f, 11.7f)
                val bottom = cy + 5.85f * u
                var x = right - (4 * barW + 3 * gap)
                for (i in 0 until 4) {
                    fill.color = if (i < cellLevel) content else faint
                    val h = heights[i] * u
                    c.drawRoundRect(x, bottom - h, x + barW, bottom, 1f * u, 1f * u, fill)
                    x += barW + gap
                }
            }
        }
    }

    /** The battery ending at [right]; returns its left edge. */
    private fun drawBattery(c: Canvas, right: Float, cy: Float, u: Float, content: Int): Float {
        val nubW = 1.6f * u
        val bodyW = 27f * u - nubW - 0.8f * u
        val bodyH = 12.6f * u
        val left = right - 27f * u
        r.set(left, cy - bodyH / 2f, left + bodyW, cy + bodyH / 2f)
        val rad = 4.2f * u
        val k = Color.red(content)
        val levelColor = when {
            charging -> 0xFF34C759.toInt()
            powerSave -> 0xFFFFCC00.toInt()
            batteryLevel <= 20 -> 0xFFFF3B30.toInt()
            else -> content
        }
        // Nub.
        fill.color = Color.argb(102, k, k, k)
        c.drawRoundRect(r.right + 0.8f * u, cy - 2.2f * u, r.right + 0.8f * u + nubW, cy + 2.2f * u, nubW, nubW, fill)
        val lvl = batteryLevel.coerceIn(0, 100) / 100f
        if (showPercent) {
            // iOS 16+: the body is the gauge (filled to the level, the rest faint), the number knocked out of it.
            val layer = c.saveLayer(r.left - u, r.top - u, r.right + u, r.bottom + u, null)
            fill.color = Color.argb(89, k, k, k)
            c.drawRoundRect(r, rad, rad, fill)
            c.save()
            c.clipRect(r.left, r.top, r.left + r.width() * lvl, r.bottom)
            fill.color = levelColor
            c.drawRoundRect(r, rad, rad, fill)
            c.restore()
            cut.textSize = 9.6f * u
            c.drawText(batteryLevel.toString(), r.centerX(), cy + 0.727f * cut.textSize / 2f, cut)
            c.restoreToCount(layer)
        } else {
            stroke.color = Color.argb(102, k, k, k)
            stroke.strokeWidth = 1f * u
            val s = stroke.strokeWidth / 2f
            c.drawRoundRect(r.left + s, r.top + s, r.right - s, r.bottom - s, rad, rad, stroke)
            val inset = 2f * u
            fill.color = levelColor
            val innerW = (bodyW - 2 * inset) * lvl.coerceAtLeast(0.04f)
            c.drawRoundRect(r.left + inset, r.top + inset, r.left + inset + innerW, r.bottom - inset, rad - inset * 0.6f, rad - inset * 0.6f, fill)
        }
        return left
    }

    private fun drawLabel(c: Canvas, text: String, right: Float, cy: Float, size: Float, color: Int): Float {
        label.color = color
        label.textSize = size
        val w = label.measureText(text)
        c.drawText(text, right - w, cy + 0.727f * size / 2f, label)
        return right - w
    }

    /** iOS's VPN badge: the letters in a rounded box. */
    private fun drawBadge(c: Canvas, text: String, right: Float, cy: Float, u: Float, color: Int): Float {
        label.textSize = 9.5f * u
        val w = label.measureText(text) + 5f * u
        r.set(right - w, cy - 6f * u, right, cy + 6f * u)
        stroke.color = color
        stroke.strokeWidth = 1.2f * u
        c.drawRoundRect(r, 3f * u, 3f * u, stroke)
        label.color = color
        c.drawText(text, r.left + 2.5f * u, cy + 0.727f * label.textSize / 2f, label)
        return r.left
    }

    private fun drawMoon(c: Canvas, cx: Float, cy: Float, rad: Float, color: Int) {
        val layer = c.saveLayer(cx - rad * 2, cy - rad * 2, cx + rad * 2, cy + rad * 2, null)
        fill.color = color
        c.drawCircle(cx, cy, rad, fill)
        cut.textSize = 1f
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

    /** iOS Wi-Fi glyph: three bands of a fan opening upwards from (cx, bottom); unreached bands faint. */
    private fun drawWifi(c: Canvas, cx: Float, bottom: Float, u: Float, level: Int, on: Int, off: Int) {
        val outer = floatArrayOf(3.4f, 7.4f, 11.2f)
        val band = floatArrayOf(3.4f, 2.6f, 2.6f)
        for (i in 0 until 3) {
            val ro = outer[i] * u
            val ri = (outer[i] - band[i]) * u
            path.reset()
            path.arcTo(cx - ro, bottom - ro, cx + ro, bottom + ro, 225f, 90f, true)
            if (ri > 0.5f) path.arcTo(cx - ri, bottom - ri, cx + ri, bottom + ri, 315f, -90f, false) else path.lineTo(cx, bottom)
            path.close()
            fill.color = if (i < level) on else off
            c.drawPath(path, fill)
        }
    }
}
