package dev.launcher.app.statusbar

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
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
 * The iOS status bar: time on the left, cellular bars, Wi-Fi (or the mobile network type) and battery on the right, in white
 * or black like iOS (set from outside through [setDark]). Measured on the iOS 26 press image (iPhone 16 Pro, pt): time SF 17
 * semibold centred 73.7 from the left; right group right-aligned 35.4 from the edge: signal 19.2 x 11.7, Wi-Fi 17 x 11.2,
 * battery 27 x 12.6, 7.5 apart; everything centred on the camera's line. One iOS point = width / 402.
 *
 * Lives in an overlay window on gesture nav's UI thread: all state changes arrive through [post]. Hides itself when the app in
 * front hides the status bar (immersive).
 */
@SuppressLint("ViewConstructor")
class StatusBarView(ctx: Context) : View(ctx) {
    private val time = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.text(600) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.text(600) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()
    private val path = Path()

    private var clock = ""
    private var batteryLevel = 100
    private var charging = false
    private var wifiLevel = -1          // -1 = no Wi-Fi, else 1..3 arcs
    private var cellLevel = 0           // 0..4 bars
    private var networkType = ""        // "5G", "LTE", ... (shown when not on Wi-Fi)
    private var dark = 0f               // 0 = white content, 1 = black (animated)
    private var darkTarget = 0f
    private var darkAnim: android.animation.ValueAnimator? = null

    private val cm get() = context.getSystemService(ConnectivityManager::class.java)
    private val tm get() = context.getSystemService(TelephonyManager::class.java)

    init {
        // Immersive apps hide the status bar: so do we.
        setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) v.visibility = if (insets.isVisible(WindowInsets.Type.statusBars())) VISIBLE else INVISIBLE
            insets
        }
    }

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

    // ------------------------------------------------------------------ sources

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_BATTERY_CHANGED) readBattery(i) else updateClock()
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

    private val telephony: TelephonyCallback? = if (Build.VERSION.SDK_INT >= 31) object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
        override fun onSignalStrengthsChanged(ss: SignalStrength) { post { cellLevel = ss.level.coerceIn(0, 4); invalidate() } }
    } else null

    private val displayInfo: TelephonyCallback? = if (Build.VERSION.SDK_INT >= 31) object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
        override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) { post { networkType = typeLabel(info); invalidate() } }
    } else null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateClock()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        context.registerReceiver(receiver, f, null, handler)?.let { readBattery(it) }
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifi, handler)
        } catch (t: Throwable) { AppLog.log("[statusbar] Wi-Fi state unavailable: ${t.message}") }
        if (Build.VERSION.SDK_INT >= 31) {
            val exec = context.mainExecutor
            try { telephony?.let { tm.registerTelephonyCallback(exec, it) } } catch (t: Throwable) { AppLog.log("[statusbar] signal strength unavailable: ${t.message}") }
            try { displayInfo?.let { tm.registerTelephonyCallback(exec, it) } } catch (_: Throwable) { /* needs READ_PHONE_STATE: no network type */ }
        }
    }

    override fun onDetachedFromWindow() {
        try { context.unregisterReceiver(receiver) } catch (_: Throwable) { }
        try { cm.unregisterNetworkCallback(wifi) } catch (_: Throwable) { }
        if (Build.VERSION.SDK_INT >= 31) {
            try { telephony?.let { tm.unregisterTelephonyCallback(it) } } catch (_: Throwable) { }
            try { displayInfo?.let { tm.unregisterTelephonyCallback(it) } } catch (_: Throwable) { }
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

    override fun onDraw(c: Canvas) {
        val u = width / 402f
        val cy = height * 0.52f
        val k = (255 * (1f - dark) + 0 * dark).toInt()
        val content = Color.rgb(k, k, k)
        val faint = Color.argb(77, k, k, k)

        // Time
        time.color = content
        time.textSize = 16.8f * u
        val tw = time.measureText(clock)
        c.drawText(clock, 73.7f * u - tw / 2f, cy + 0.727f * time.textSize / 2f, time)

        // Battery (right-aligned)
        val bRight = width - 35.4f * u
        val nubW = 1.6f * u
        val bodyW = 27f * u - nubW - 0.8f * u
        val bodyH = 12.6f * u
        val bLeft = bRight - 27f * u
        r.set(bLeft, cy - bodyH / 2f, bLeft + bodyW, cy + bodyH / 2f)
        stroke.color = Color.argb(102, k, k, k)
        stroke.strokeWidth = 1f * u
        val rad = 4f * u
        c.drawRoundRect(r.left + stroke.strokeWidth / 2f, r.top + stroke.strokeWidth / 2f, r.right - stroke.strokeWidth / 2f, r.bottom - stroke.strokeWidth / 2f, rad, rad, stroke)
        fill.color = Color.argb(102, k, k, k)
        c.drawRoundRect(r.right + 0.8f * u, cy - 2.2f * u, r.right + 0.8f * u + nubW, cy + 2.2f * u, nubW, nubW, fill)
        fill.color = when {
            charging -> 0xFF34C759.toInt()
            batteryLevel <= 20 -> 0xFFFF3B30.toInt()
            else -> content
        }
        val inset = 2f * u
        val innerW = (bodyW - 2 * inset) * (batteryLevel.coerceIn(4, 100) / 100f)
        c.drawRoundRect(r.left + inset, r.top + inset, r.left + inset + innerW, r.bottom - inset, rad - inset * 0.6f, rad - inset * 0.6f, fill)

        // Wi-Fi, or the mobile network type when not on Wi-Fi
        var right = bLeft - 7.6f * u
        if (wifiLevel > 0) {
            drawWifi(c, right - 8.5f * u, cy + 5.6f * u, u, wifiLevel, content, faint)
            right -= 17f * u + 7.5f * u
        } else if (networkType.isNotEmpty()) {
            label.color = content
            label.textSize = 12.5f * u
            val lw = label.measureText(networkType)
            c.drawText(networkType, right - lw, cy + 0.727f * label.textSize / 2f, label)
            right -= lw + 6f * u
        }

        // Cellular bars
        val barW = 3f * u
        val gap = 2.4f * u
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
