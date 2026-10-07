package dev.launcher.app.shade

import android.app.NotificationManager
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import dev.launcher.app.AppLog
import dev.launcher.app.R
import dev.launcher.app.ShizukuLink
import java.util.concurrent.Executors

/** Most single controls: a circle, or (iOS 18+) a capsule showing their name. */
private val ONE_OR_WIDE = listOf(Control.Size(1, 1), Control.Size(2, 1))

/**
 * Every control Control Center offers, as iOS 18+ has them (adapted to Android where iOS's has no counterpart: Personal
 * Hotspot in place of AirDrop). [sizes]: the sizes it can take (columns x rows), its default first. [kind] decides how it
 * draws and behaves.
 */
enum class Control(val title: String, val icon: Int, val kind: Kind, val sizes: List<Size>, val accent: Int, val style: Style = Style.WHITE) {
    CONNECTIVITY("Connectivity", R.drawable.sym_wifi, Kind.CONNECTIVITY, listOf(Size(2, 2)), 0xFF0A84FF.toInt()),
    MEDIA("Now Playing", R.drawable.sym_music, Kind.MEDIA, listOf(Size(2, 2), Size(4, 2)), 0xFFFF375F.toInt()),
    BRIGHTNESS("Brightness", R.drawable.sym_sun, Kind.SLIDER, listOf(Size(1, 2), Size(1, 3)), 0xFFFFCC00.toInt()),
    VOLUME("Volume", R.drawable.sym_volume, Kind.SLIDER, listOf(Size(1, 2), Size(1, 3)), 0xFF32ADE6.toInt()),
    FOCUS("Focus", R.drawable.sym_moon, Kind.FOCUS, listOf(Size(2, 1), Size(1, 1)), 0xFF5E5CE6.toInt(), Style.COLOR),
    ROTATION_LOCK("Orientation Lock", R.drawable.sym_rotation_lock, Kind.TOGGLE, ONE_OR_WIDE, 0xFFFF453A.toInt()),
    SILENT("Silent Mode", R.drawable.sym_bell, Kind.TOGGLE, ONE_OR_WIDE, 0xFFFF453A.toInt()),
    FLASHLIGHT("Flashlight", R.drawable.sym_flashlight, Kind.TOGGLE, ONE_OR_WIDE, 0xFF0A84FF.toInt()),
    TIMER("Timer", R.drawable.sym_timer, Kind.LAUNCH, ONE_OR_WIDE, 0xFFFF9F0A.toInt()),
    CALCULATOR("Calculator", R.drawable.sym_calculator, Kind.LAUNCH, ONE_OR_WIDE, 0xFFFF9F0A.toInt()),
    CAMERA("Camera", R.drawable.sym_camera, Kind.LAUNCH, ONE_OR_WIDE, 0xFF8E8E93.toInt()),
    MIRRORING("Screen Mirroring", R.drawable.sym_mirroring, Kind.LAUNCH, ONE_OR_WIDE, 0xFF0A84FF.toInt()),
    SCAN_CODE("Scan Code", R.drawable.sym_qr, Kind.LAUNCH, ONE_OR_WIDE, 0xFF8E8E93.toInt()),
    DARK_MODE("Dark Mode", R.drawable.sym_dark_mode, Kind.TOGGLE, ONE_OR_WIDE, 0xFF1C1C1E.toInt()),
    LOW_POWER("Low Power Mode", R.drawable.sym_low_power, Kind.TOGGLE, ONE_OR_WIDE, 0xFFFFCC00.toInt()),
    AIRPLANE("Airplane Mode", R.drawable.sym_airplane, Kind.TOGGLE, ONE_OR_WIDE, 0xFFFF9F0A.toInt(), Style.COLOR),
    WIFI("Wi-Fi", R.drawable.sym_wifi, Kind.TOGGLE, ONE_OR_WIDE, 0xFF0A84FF.toInt(), Style.COLOR),
    BLUETOOTH("Bluetooth", R.drawable.sym_bluetooth, Kind.TOGGLE, ONE_OR_WIDE, 0xFF0A84FF.toInt(), Style.COLOR),
    CELLULAR("Cellular Data", R.drawable.sym_cellular, Kind.TOGGLE, ONE_OR_WIDE, 0xFF30D158.toInt(), Style.COLOR),
    HOTSPOT("Personal Hotspot", R.drawable.sym_hotspot, Kind.LAUNCH, ONE_OR_WIDE, 0xFF30D158.toInt(), Style.COLOR),
    LOCATION("Location", R.drawable.sym_location, Kind.TOGGLE, ONE_OR_WIDE, 0xFF0A84FF.toInt(), Style.COLOR),
    NFC("NFC", R.drawable.sym_nfc, Kind.TOGGLE, ONE_OR_WIDE, 0xFF0A84FF.toInt(), Style.COLOR),
    ALARM("Alarm", R.drawable.sym_alarm, Kind.LAUNCH, ONE_OR_WIDE, 0xFFFF9F0A.toInt()),
    STOPWATCH("Stopwatch", R.drawable.sym_stopwatch, Kind.LAUNCH, ONE_OR_WIDE, 0xFFFF9F0A.toInt()),
    NOTES("Quick Note", R.drawable.sym_note, Kind.LAUNCH, ONE_OR_WIDE, 0xFFFFCC00.toInt()),
    SETTINGS("Settings", R.drawable.sym_settings, Kind.LAUNCH, ONE_OR_WIDE, 0xFF8E8E93.toInt()),
    // iOS 18+'s other controls, where Android has them (the app-backed ones only where such an app is installed).
    QUICK_SHARE("Quick Share", R.drawable.sym_share, Kind.LAUNCH, ONE_OR_WIDE, 0xFF0A84FF.toInt(), Style.COLOR),
    VPN("VPN", R.drawable.sym_vpn, Kind.LAUNCH, ONE_OR_WIDE, 0xFF0A84FF.toInt(), Style.COLOR),
    DATA_SAVER("Data Saver", R.drawable.sym_data_saver, Kind.TOGGLE, ONE_OR_WIDE, 0xFF30D158.toInt(), Style.COLOR),
    VIDEO("Video", R.drawable.sym_video, Kind.LAUNCH, ONE_OR_WIDE, 0xFF8E8E93.toInt()),
    SELFIE("Selfie", R.drawable.sym_selfie, Kind.LAUNCH, ONE_OR_WIDE, 0xFF8E8E93.toInt()),
    VOICE_MEMO("Voice Memo", R.drawable.sym_mic, Kind.LAUNCH, ONE_OR_WIDE, 0xFFFF453A.toInt()),
    RECOGNIZE_MUSIC("Recognize Music", R.drawable.sym_recognize_music, Kind.LAUNCH, ONE_OR_WIDE, 0xFF0A84FF.toInt()),
    TRANSLATE("Translate", R.drawable.sym_translate, Kind.LAUNCH, ONE_OR_WIDE, 0xFF0A84FF.toInt()),
    MAGNIFIER("Magnifier", R.drawable.sym_magnifier, Kind.LAUNCH, ONE_OR_WIDE, 0xFF8E8E93.toInt()),
    WALLET("Wallet", R.drawable.sym_wallet, Kind.LAUNCH, ONE_OR_WIDE, 0xFF8E8E93.toInt()),
    HOME("Home", R.drawable.sym_home, Kind.LAUNCH, ONE_OR_WIDE, 0xFFFF9F0A.toInt()),
    TEXT_SIZE("Text Size", R.drawable.sym_text_size, Kind.LAUNCH, ONE_OR_WIDE, 0xFF8E8E93.toInt()),
    INVERT("Invert Colors", R.drawable.sym_invert, Kind.TOGGLE, ONE_OR_WIDE, 0xFF8E8E93.toInt()),
    GRAYSCALE("Color Filters", R.drawable.sym_grayscale, Kind.TOGGLE, ONE_OR_WIDE, 0xFF8E8E93.toInt()),
    EXTRA_DIM("Reduce White Point", R.drawable.sym_extra_dim, Kind.TOGGLE, ONE_OR_WIDE, 0xFFFFCC00.toInt()),
    LIVE_CAPTIONS("Live Captions", R.drawable.sym_captions, Kind.TOGGLE, ONE_OR_WIDE, 0xFF0A84FF.toInt(), Style.COLOR),
    ACCESSIBILITY("Accessibility Shortcut", R.drawable.sym_accessibility, Kind.LAUNCH, ONE_OR_WIDE, 0xFF0A84FF.toInt());

    enum class Kind { TOGGLE, LAUNCH, SLIDER, CONNECTIVITY, MEDIA, FOCUS }

    /** WHITE: an active control turns white with its glyph in [accent] (iOS's flashlight, orientation lock); COLOR: it fills with [accent]. */
    enum class Style { WHITE, COLOR }

    data class Size(val w: Int, val h: Int)

    val defaultSize get() = sizes.first()
}

/**
 * Reads the state of every control and changes it. Reading: settings and system services in our process (watched, so
 * the controls follow changes made anywhere). Changing: what our process may do itself (torch, volume, ringer, Do Not
 * Disturb once access is granted), the rest through the shell (Shizuku): `svc wifi`, `cmd connectivity airplane-mode`,
 * brightness through the display manager. Listeners run on [handler] (the shade's thread).
 */
class ControlState(private val ctx: Context, private val handler: Handler) {
    private val io = Executors.newSingleThreadExecutor()
    private val listeners = ArrayList<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    private fun changed() = handler.post { for (l in listeners.toList()) l() }

    var wifi = false; private set
    var bluetooth = false; private set
    var cellular = false; private set
    var airplane = false; private set
    var location = false; private set
    var nfc = false; private set
    var hotspot = false; private set
    var rotationLock = false; private set
    var silent = false; private set
    var torch = false; private set
    var dnd = false; private set
    var darkMode = false; private set
    var lowPower = false; private set
    /** Brightness as the slider shows it (0..1, perceptual), and media volume (0..1). */
    var brightness = 0.5f; private set
    var volume = 0.5f; private set
    var hasNfc = false; private set
    var dataSaver = false; private set
    var invert = false; private set
    var grayscale = false; private set
    var extraDim = false; private set
    var liveCaptions = false; private set
    /** Expanded modules' details: the Wi-Fi network's name, Night Light, automatic brightness, the torch's strength. */
    var wifiName: String? = null; private set
    var nightLight = false; private set
    var autoBrightness = false; private set
    var torchLevels = 1; private set
    var torchLevel = 1; private set

    fun isOn(c: Control): Boolean = when (c) {
        Control.WIFI -> wifi
        Control.BLUETOOTH -> bluetooth
        Control.CELLULAR -> cellular
        Control.AIRPLANE -> airplane
        Control.LOCATION -> location
        Control.NFC -> nfc
        Control.HOTSPOT -> hotspot
        Control.ROTATION_LOCK -> rotationLock
        Control.SILENT -> silent
        Control.FLASHLIGHT -> torch
        Control.FOCUS -> dnd
        Control.DARK_MODE -> darkMode
        Control.LOW_POWER -> lowPower
        Control.DATA_SAVER -> dataSaver
        Control.INVERT -> invert
        Control.GRAYSCALE -> grayscale
        Control.EXTRA_DIM -> extraDim
        Control.LIVE_CAPTIONS -> liveCaptions
        else -> false
    }

    // ------------------------------------------------------------------ reading

    fun readAll() {
        val cr = ctx.contentResolver
        airplane = Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        bluetooth = Settings.Global.getInt(cr, "bluetooth_on", 0) == 1
        cellular = Settings.Global.getInt(cr, "mobile_data", 1) == 1
        wifi = try { ctx.getSystemService(WifiManager::class.java).isWifiEnabled } catch (_: Throwable) { Settings.Global.getInt(cr, "wifi_on", 0) == 1 }
        location = try { ctx.getSystemService(LocationManager::class.java).isLocationEnabled } catch (_: Throwable) { false }
        rotationLock = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1) == 0
        val am = ctx.getSystemService(AudioManager::class.java)
        silent = am.ringerMode != AudioManager.RINGER_MODE_NORMAL
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        if (!volumeHeld) volume = am.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
        dnd = ctx.getSystemService(NotificationManager::class.java).currentInterruptionFilter.let {
            it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        }
        darkMode = (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        lowPower = try { ctx.getSystemService(PowerManager::class.java).isPowerSaveMode } catch (_: Throwable) { false }
        val nfcAdapter = try { android.nfc.NfcAdapter.getDefaultAdapter(ctx) } catch (_: Throwable) { null }
        hasNfc = nfcAdapter != null
        nfc = try { nfcAdapter?.isEnabled == true } catch (_: Throwable) { false }
        if (!brightnessHeld) readBrightness()
        // Hidden settings an app may not read since Android 12 throw: those are read through the shell ([readDetails]).
        secure("night_display_activated")?.let { n -> nightLight = n == 1 || system("blue_light_filter") == 1 } ?: system("blue_light_filter")?.let { if (it == 1) nightLight = true }
        secure("accessibility_display_inversion_enabled")?.let { invert = it == 1 }
        secure("accessibility_display_daltonizer_enabled")?.let { grayscale = it == 1 }
        secure("reduce_bright_colors_activated")?.let { extraDim = it == 1 }
        secure("odi_captions_enabled")?.let { liveCaptions = it == 1 }
        dataSaver = try {
            ctx.getSystemService(android.net.ConnectivityManager::class.java).restrictBackgroundStatus == android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        } catch (_: Throwable) { false }
        autoBrightness = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        if (!wifi) wifiName = null
        changed()
    }

    private fun secure(name: String): Int? = try { Settings.Secure.getInt(ctx.contentResolver, name, 0) } catch (_: Throwable) { null }
    private fun system(name: String): Int? = try { Settings.System.getInt(ctx.contentResolver, name, 0) } catch (_: Throwable) { null }

    /** The hidden settings behind some controls, read through the shell (an app may not read them since Android 12). */
    private fun readHidden() {
        val s = ShizukuLink.service ?: return
        io.execute {
            val keys = listOf("night_display_activated", "accessibility_display_inversion_enabled", "accessibility_display_daltonizer_enabled",
                "reduce_bright_colors_activated", "odi_captions_enabled")
            val out = try { s.runShell(keys.joinToString("; ") { "settings get secure $it" }) } catch (_: Throwable) { return@execute }
            val v = out.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("[exit") }
            if (v.size < keys.size) return@execute
            fun on(i: Int) = v[i] == "1"
            handler.post {
                nightLight = on(0) || system("blue_light_filter") == 1
                invert = on(1); grayscale = on(2); extraDim = on(3); liveCaptions = on(4)
                changed()
            }
        }
    }

    /**
     * What an expanded module shows beyond on and off, read when it opens: the Wi-Fi network's name (from the shell: our
     * app would need location access for it) and the torch's strength levels.
     */
    fun readDetails() {
        readHidden()
        val s = ShizukuLink.service
        if (s != null && wifi) io.execute {
            val out = try { s.runShell("cmd wifi status") } catch (_: Throwable) { "" }
            val name = Regex("connected to \"([^\"]+)\"").find(out)?.groupValues?.get(1)
            handler.post { wifiName = name; changed() }
        }
        val id = torchId ?: return
        if (android.os.Build.VERSION.SDK_INT >= 33) try {
            val cm = ctx.getSystemService(CameraManager::class.java)
            torchLevels = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
            torchLevel = if (torch) cm.getTorchStrengthLevel(id) else torchLevel.coerceAtLeast(1)
        } catch (_: Throwable) { torchLevels = 1 }
    }

    /** The torch at [level] (1..[torchLevels]; 0 turns it off). */
    fun setTorchLevel(level: Int) {
        val id = torchId ?: return
        val cm = ctx.getSystemService(CameraManager::class.java)
        try {
            if (level <= 0) { cm.setTorchMode(id, false); torch = false }
            else if (android.os.Build.VERSION.SDK_INT >= 33 && torchLevels > 1) { cm.turnOnTorchWithStrengthLevel(id, level.coerceIn(1, torchLevels)); torch = true; torchLevel = level }
            else { cm.setTorchMode(id, true); torch = true }
        } catch (t: Throwable) { AppLog.log("[controls] torch level: ${t.message}") }
        changed()
    }

    /** Night Light (One UI: Eye comfort shield), through the shell. */
    fun setNightLight(on: Boolean) {
        nightLight = on; changed()
        shell("cmd color_display set-night-display-activated $on; settings put system blue_light_filter ${if (on) 1 else 0}")
    }

    fun setAutoBrightness(on: Boolean) {
        autoBrightness = on; changed()
        shell("settings put system screen_brightness_mode ${if (on) 1 else 0}")
    }

    /** Starts a timer of [seconds] in the clock app without opening it. */
    fun startTimer(seconds: Int): Boolean = try {
        ctx.startActivity(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (t: Throwable) { AppLog.log("[controls] timer: ${t.javaClass.simpleName}"); false }

    private fun readBrightness() {
        val s = ShizukuLink.service
        if (s != null) io.execute {
            val v = try { s.brightness() } catch (_: Throwable) { -1f }
            if (v >= 0f) handler.post { if (!brightnessHeld) { brightness = Brightness.toSlider(v, bMin, bMax); changed() } }
        } else {
            val v = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            brightness = Brightness.toSlider(v / 255f, 0f, 1f)
        }
    }

    private var bMin = 0f
    private var bMax = 1f

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) { readAll() }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { readAll() }
    }

    private val torchCb = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchId) { torch = enabled; changed() }
        }
    }

    private var torchId: String? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        val cr = ctx.contentResolver
        for (name in listOf(Settings.Global.AIRPLANE_MODE_ON, "bluetooth_on", "mobile_data", "wifi_on", "low_power"))
            cr.registerContentObserver(Settings.Global.getUriFor(name), false, observer)
        cr.registerContentObserver(Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false, observer)
        cr.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, observer)
        cr.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE), false, observer)
        try { cr.registerContentObserver(Settings.System.getUriFor("blue_light_filter"), false, observer) } catch (_: Throwable) { }
        try { cr.registerContentObserver(Settings.Secure.getUriFor("night_display_activated"), false, observer) } catch (_: Throwable) { }
        for (name in listOf("accessibility_display_inversion_enabled", "accessibility_display_daltonizer_enabled", "reduce_bright_colors_activated", "odi_captions_enabled"))
            try { cr.registerContentObserver(Settings.Secure.getUriFor(name), false, observer) } catch (_: Throwable) { }
        cr.registerContentObserver(Settings.Secure.getUriFor("location_mode"), false, observer)
        val f = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction("android.bluetooth.adapter.action.STATE_CHANGED")
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(LocationManager.MODE_CHANGED_ACTION)
            addAction("android.nfc.action.ADAPTER_STATE_CHANGED")
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
            addAction(android.net.ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED)
        }
        try { ctx.registerReceiver(receiver, f, null, handler) } catch (t: Throwable) { AppLog.log("[controls] receiver: ${t.message}") }
        try {
            val cm = ctx.getSystemService(CameraManager::class.java)
            torchId = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            cm.registerTorchCallback(torchCb, handler)
        } catch (t: Throwable) { AppLog.log("[controls] torch: ${t.message}") }
        val s = ShizukuLink.service
        if (s != null) io.execute {
            val range = try { s.brightnessRange() } catch (_: Throwable) { null }
            if (range != null && range.size == 2 && range[1] > range[0]) handler.post { bMin = range[0]; bMax = range[1]; readBrightness() }
        }
        readAll()
        readHidden()
    }

    fun stop() {
        if (!started) return
        started = false
        try { ctx.contentResolver.unregisterContentObserver(observer) } catch (_: Throwable) { }
        try { ctx.unregisterReceiver(receiver) } catch (_: Throwable) { }
        try { ctx.getSystemService(CameraManager::class.java).unregisterTorchCallback(torchCb) } catch (_: Throwable) { }
    }

    // ------------------------------------------------------------------ changing

    /**
     * Flips [c]. The control shows the new state at once (optimistic) and settles on the real one when the system reports
     * it. Returns false if this control cannot be switched here (it opens its settings instead: [settingsFor]).
     */
    fun toggle(c: Control): Boolean {
        val to = !isOn(c)
        when (c) {
            Control.FLASHLIGHT -> {
                val id = torchId ?: return false
                torch = to; changed()
                try { ctx.getSystemService(CameraManager::class.java).setTorchMode(id, to) } catch (t: Throwable) { AppLog.log("[controls] torch: ${t.message}"); torch = !to; changed() }
                return true
            }
            Control.SILENT -> {
                silent = to; changed()
                val am = ctx.getSystemService(AudioManager::class.java)
                try { am.ringerMode = if (to) AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_NORMAL } catch (t: Throwable) { shell("cmd media_session volume --stream 2 --set 0") }
                return true
            }
            Control.FOCUS -> {
                dnd = to; changed()
                val nm = ctx.getSystemService(NotificationManager::class.java)
                if (nm.isNotificationPolicyAccessGranted) {
                    nm.setInterruptionFilter(if (to) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
                } else shell(if (to) "cmd notification set_dnd priority" else "cmd notification set_dnd off")
                return true
            }
            else -> {}
        }
        val cmd = when (c) {
            Control.WIFI -> if (to) "svc wifi enable" else "svc wifi disable"
            Control.BLUETOOTH -> if (to) "svc bluetooth enable" else "svc bluetooth disable"
            Control.CELLULAR -> if (to) "svc data enable" else "svc data disable"
            Control.AIRPLANE -> "cmd connectivity airplane-mode ${if (to) "enable" else "disable"}"
            Control.LOCATION -> "cmd location set-location-enabled $to"
            Control.NFC -> if (to) "svc nfc enable" else "svc nfc disable"
            Control.ROTATION_LOCK -> "settings put system accelerometer_rotation ${if (to) 0 else 1}"
            Control.DARK_MODE -> "cmd uimode night ${if (to) "yes" else "no"}"
            Control.LOW_POWER -> "cmd power set-mode ${if (to) 1 else 0}"
            Control.DATA_SAVER -> "cmd netpolicy set restrict-background $to"
            Control.INVERT -> "settings put secure accessibility_display_inversion_enabled ${if (to) 1 else 0}"
            Control.GRAYSCALE -> if (to) "settings put secure accessibility_display_daltonizer 0; settings put secure accessibility_display_daltonizer_enabled 1"
                else "settings put secure accessibility_display_daltonizer_enabled 0"
            Control.EXTRA_DIM -> "settings put secure reduce_bright_colors_activated ${if (to) 1 else 0}"
            Control.LIVE_CAPTIONS -> "settings put secure odi_captions_enabled ${if (to) 1 else 0}"
            else -> return false
        }
        if (ShizukuLink.service == null) return false
        set(c, to)
        shell(cmd)
        return true
    }

    private fun set(c: Control, v: Boolean) {
        when (c) {
            Control.WIFI -> wifi = v
            Control.BLUETOOTH -> bluetooth = v
            Control.CELLULAR -> cellular = v
            Control.AIRPLANE -> airplane = v
            Control.LOCATION -> location = v
            Control.NFC -> nfc = v
            Control.ROTATION_LOCK -> rotationLock = v
            Control.DARK_MODE -> darkMode = v
            Control.LOW_POWER -> lowPower = v
            Control.DATA_SAVER -> dataSaver = v
            Control.INVERT -> invert = v
            Control.GRAYSCALE -> grayscale = v
            Control.EXTRA_DIM -> extraDim = v
            Control.LIVE_CAPTIONS -> liveCaptions = v
            else -> {}
        }
        changed()
        // If the system does not confirm within a while, show what it really is.
        handler.postDelayed({ readAll() }, 2500)
    }

    private fun shell(cmd: String) {
        val s = ShizukuLink.service ?: return
        io.execute {
            val out = try { s.runShell(cmd).trim() } catch (t: Throwable) { "ERROR: ${t.message}" }
            if (out.contains("ERROR") || out.contains("Exception") || out.contains("exit 1") || out.contains("Error")) AppLog.log("[controls] $cmd -> ${out.take(160)}")
            handler.postDelayed({ readAll() }, 300)
        }
    }

    // Sliders: the finger moves them and the system follows (brightness: temporary while dragging, saved on release, as
    // SystemUI's slider does; a oneway call per frame).
    private var brightnessHeld = false
    private var volumeHeld = false

    fun setBrightness(v: Float, final: Boolean) {
        brightness = v.coerceIn(0f, 1f)
        brightnessHeld = !final
        val s = ShizukuLink.service
        if (s == null) {
            try { Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (Brightness.toLinear(brightness, 0f, 1f) * 255).toInt().coerceIn(1, 255)) } catch (_: Throwable) { }
            return
        }
        try { s.setBrightness(Brightness.toLinear(brightness, bMin, bMax), final) } catch (_: Throwable) { }
    }

    fun setVolume(v: Float, final: Boolean) {
        volume = v.coerceIn(0f, 1f)
        volumeHeld = !final
        val am = ctx.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val step = Math.round(volume * max)
        if (step != am.getStreamVolume(AudioManager.STREAM_MUSIC)) try { am.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0) } catch (_: Throwable) { }
        if (final) volume = step / max.coerceAtLeast(1).toFloat()
    }

    // ------------------------------------------------------------------ opening things

    /** What tapping a launching control (or long-pressing a toggle) opens. */
    fun intentFor(c: Control): Intent? = when (c) {
        Control.TIMER -> Intent(AlarmClock.ACTION_SHOW_TIMERS)
        Control.ALARM -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
        Control.STOPWATCH -> Intent("android.intent.action.SHOW_STOPWATCH").takeIf { resolves(it) } ?: Intent(AlarmClock.ACTION_SHOW_TIMERS)
        Control.CALCULATOR -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR)
        Control.CAMERA, Control.SCAN_CODE -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        Control.MIRRORING -> Intent(Settings.ACTION_CAST_SETTINGS)
        Control.NOTES -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, "android.intent.category.APP_NOTES").takeIf { resolves(it) }
            ?: Intent("android.intent.action.CREATE_NOTE")
        Control.SETTINGS -> Intent(Settings.ACTION_SETTINGS)
        Control.HOTSPOT -> Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.TetherSettings")).takeIf { resolves(it) }
            ?: Intent(Settings.ACTION_WIRELESS_SETTINGS)
        Control.QUICK_SHARE -> Intent("com.google.android.gms.RECEIVE_NEARBY").takeIf { resolves(it) } ?: launcherOf(QUICK_SHARE_APPS)
        Control.VPN -> Intent(Settings.ACTION_VPN_SETTINGS)
        Control.VIDEO -> Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA)
        Control.SELFIE -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            .putExtra("android.intent.extras.CAMERA_FACING", 1).putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
            .putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
        Control.VOICE_MEMO -> Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION).takeIf { resolves(it) } ?: launcherOf(RECORDER_APPS)
        Control.RECOGNIZE_MUSIC -> launcherOf(listOf("com.shazam.android")) ?: Intent("com.google.android.googlequicksearchbox.MUSIC_SEARCH").takeIf { resolves(it) }
        Control.TRANSLATE -> launcherOf(listOf("com.google.android.apps.translate", "com.samsung.android.app.interpreter"))
        Control.MAGNIFIER -> launcherOf(listOf("com.google.android.apps.accessibility.magnifier", "com.samsung.android.app.magnifier"))
        Control.WALLET -> launcherOf(listOf("com.google.android.apps.walletnfcrel", "com.samsung.android.spay"))
        Control.HOME -> launcherOf(listOf("com.google.android.apps.chromecast.app", "com.samsung.android.oneconnect"))
        Control.TEXT_SIZE -> Intent("android.settings.TEXT_READING_SETTINGS").takeIf { resolves(it) } ?: Intent(Settings.ACTION_DISPLAY_SETTINGS)
        Control.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        else -> settingsFor(c)
    }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The settings screen behind a toggle (a long press opens it, as iOS's controls expand). */
    fun settingsFor(c: Control): Intent? = when (c) {
        Control.WIFI -> Intent(Settings.ACTION_WIFI_SETTINGS)
        Control.BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        Control.CELLULAR -> Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)
        Control.AIRPLANE -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
        Control.LOCATION -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        Control.NFC -> Intent(Settings.ACTION_NFC_SETTINGS)
        Control.FOCUS -> Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS)
        Control.DARK_MODE -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
        Control.LOW_POWER -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        Control.BRIGHTNESS -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
        Control.VOLUME, Control.SILENT -> Intent(Settings.ACTION_SOUND_SETTINGS)
        Control.CONNECTIVITY -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
        Control.DATA_SAVER -> Intent("android.settings.DATA_SAVER_SETTINGS").takeIf { resolves(it) } ?: Intent(Settings.ACTION_WIRELESS_SETTINGS)
        Control.INVERT, Control.GRAYSCALE, Control.EXTRA_DIM, Control.LIVE_CAPTIONS -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        else -> null
    }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The launch screen of the first of [pkgs] that is installed. */
    private fun launcherOf(pkgs: List<String>): Intent? = pkgs.firstNotNullOfOrNull { p ->
        try { ctx.packageManager.getLaunchIntentForPackage(p) } catch (_: Throwable) { null }
    }

    private fun resolves(i: Intent) = try { ctx.packageManager.resolveActivity(i, 0) != null } catch (_: Throwable) { false }

    /** Starts [i] from our process (it is visible, so it may). */
    fun start(i: Intent?): Boolean {
        i ?: return false
        return try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (t: Throwable) {
            AppLog.log("[controls] could not open ${i.action ?: i.component}: ${t.javaClass.simpleName}"); false
        }
    }

    /** Whether a control can work on this phone at all (no NFC chip: no NFC control). */
    fun available(c: Control): Boolean = when (c) {
        Control.NFC -> hasNfc
        Control.FLASHLIGHT -> torchId != null || !started
        // The app-backed controls only where such an app is installed.
        Control.QUICK_SHARE, Control.VOICE_MEMO, Control.RECOGNIZE_MUSIC, Control.TRANSLATE, Control.MAGNIFIER, Control.WALLET,
        Control.HOME -> intentFor(c) != null
        else -> true
    }

    private companion object {
        val QUICK_SHARE_APPS = listOf("com.samsung.android.app.sharelive")
        val RECORDER_APPS = listOf("com.sec.android.app.voicenote", "com.google.android.apps.recorder")
    }

    /** The UI mode service, used to read dark mode where the configuration lags. */
    @Suppress("unused") private val uiMode get() = ctx.getSystemService(UiModeManager::class.java)
}

/**
 * The brightness slider's curve: Android's (SettingsLib BrightnessUtils), so the slider moves as the system's does: the
 * slider's position is perceptual (gamma, Hybrid Log-Gamma), the display's brightness linear between [min] and [max].
 */
object Brightness {
    private const val R = 0.5f
    private const val A = 0.17883277f
    private const val B = 0.28466892f
    private const val C = 0.55991073f

    fun toLinear(slider: Float, min: Float, max: Float): Float {
        val v = slider.coerceIn(0f, 1f)
        val ret = if (v <= R) (v / R) * (v / R) else kotlin.math.exp((v - C) / A) + B
        return min + (max - min) * (ret.coerceIn(0f, 12f) / 12f)
    }

    fun toSlider(linear: Float, min: Float, max: Float): Float {
        if (max <= min) return 0.5f
        val n = ((linear - min) / (max - min)).coerceIn(0f, 1f) * 12f
        val ret = if (n <= 1f) kotlin.math.sqrt(n) * R else A * kotlin.math.ln(n - B) + C
        return ret.coerceIn(0f, 1f)
    }
}
