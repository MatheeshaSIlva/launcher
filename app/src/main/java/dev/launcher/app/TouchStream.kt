package dev.launcher.app

import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileDescriptor
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shell process: the touchscreen's raw first-finger position, read from /dev/input (the shell may read it; apps may not)
 * and sent to the app while one pull lasts ([IShellService.watchTouch]).
 *
 * Why: when a pull starts at the top edge, the window manager hands the touch over to the stock status bar after 24 dp
 * (DisplayPolicy.requestTransientBars: "reroute the touch events ... to the status bar"), unless the app in front shows
 * its bars transiently. Our window then gets a cancel and nothing more of that touch, but the shade must keep following the
 * finger. Nothing here changes or blocks input: it only reads, one thread per pull polling every touchscreen, and stops
 * when every finger is up (or after [MAX_MS], or when the app stops it).
 */
internal class TouchStream {
    private class Device(val path: String, val maxX: Int, val maxY: Int)

    /** One device's parse state during a stream. */
    private class State {
        var slot = -1          // current slot (-1: not reported since we started: the finger was already down)
        var tracked = -2       // the slot we follow (-2: not chosen yet)
        var x = -1
        var y = -1
        var moved = false
    }

    @Volatile private var devices: List<Device>? = null
    private val gen = AtomicInteger()

    init {
        // Found once, ahead of the first pull (`getevent -pl` takes a few hundred ms: a pull's first moments are faster).
        Thread({ if (devices == null) devices = discover() }, "touch-stream-scan").apply { isDaemon = true; start() }
    }

    /** Starts a stream for [listener]; an earlier one stops. */
    fun watch(listener: ITouchStream) {
        val g = gen.incrementAndGet()
        Thread({ run(g, listener) }, "touch-stream").apply { isDaemon = true; start() }
    }

    fun stop() { gen.incrementAndGet() }

    // ---------------------------------------------------------------- the top edge (while an app hides the status bar)

    private val edgeGen = AtomicInteger()
    private var debugLogs = 0

    /**
     * Watches every touchscreen for a finger that comes down within [band] of the screen's top edge and moves [travel]
     * down (fractions of the screen's height, screen coordinates for display [rotation]); tells [listener] once per such
     * finger. Asleep in poll() between touches; until [stopEdge] or the next call.
     */
    fun watchEdge(listener: ITopEdge, rotation: Int, band: Float, travel: Float) {
        val g = edgeGen.incrementAndGet()
        Thread({ runEdge(g, listener, rotation, band, travel) }, "touch-edge").apply { isDaemon = true; start() }
    }

    fun stopEdge() { edgeGen.incrementAndGet() }

    /** One finger (a multi-touch slot) during the edge watch, in screen fractions. */
    private class Finger {
        var down = false
        var started = false
        var x = -1f
        var y = -1f
        var startY = 0f
        var fromEdge = false
        var told = false
    }

    private fun runEdge(g: Int, listener: ITopEdge, rotation: Int, band: Float, travel: Float) {
        val list = devices ?: discover().also { devices = it }
        android.util.Log.i("Launcher", "[touch-edge] watching ${list.size} touchscreens, rotation $rotation, band $band, travel $travel")
        if (list.isEmpty()) return
        val fds = ArrayList<FileDescriptor>()
        try {
            for (d in list) fds += Os.open(d.path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK, 0)
            val polls = Array(fds.size) { i -> StructPollfd().apply { fd = fds[i]; events = OsConstants.POLLIN.toShort() } }
            val fingers = Array(fds.size) { Array(MAX_SLOTS) { Finger() } }
            val slots = IntArray(fds.size)
            val buf = ByteArray(EVENT * 64)
            while (edgeGen.get() == g) {
                // Long waits: nothing runs while nobody touches (it is woken by a touch, or checks it is still wanted).
                if (Os.poll(polls, 1000) <= 0) continue
                for (i in polls.indices) {
                    if ((polls[i].revents.toInt() and OsConstants.POLLIN) == 0) continue
                    val n = try { Os.read(fds[i], buf, 0, buf.size) } catch (_: Throwable) { 0 }
                    var off = 0
                    val d = list[i]
                    val fs = fingers[i]
                    while (off + EVENT <= n) {
                        val type = u16(buf, off + 16)
                        val code = u16(buf, off + 18)
                        val value = s32(buf, off + 20)
                        off += EVENT
                        val f = fs[slots[i].coerceIn(0, MAX_SLOTS - 1)]
                        when {
                            type == EV_ABS && code == ABS_MT_SLOT -> slots[i] = value
                            type == EV_ABS && code == ABS_MT_TRACKING_ID -> if (value == -1) { f.down = false } else {
                                f.down = true; f.started = false; f.told = false; f.fromEdge = false
                            }
                            type == EV_ABS && code == ABS_MT_POSITION_X -> f.x = value / d.maxX.toFloat()
                            type == EV_ABS && code == ABS_MT_POSITION_Y -> f.y = value / d.maxY.toFloat()
                            type == EV_SYN && code == SYN_REPORT -> for (k in fs) {
                                if (!k.down) continue
                                // The panel's natural coordinates to the screen's, for the display's rotation. The kernel
                                // reports an axis only when it changes (a straight swipe down never says x): only the
                                // axis that runs down the screen is needed, the other is taken as the middle until known.
                                val down = if (rotation == 1 || rotation == 3) k.x else k.y
                                if (down < 0f) continue
                                val across = (if (rotation == 1 || rotation == 3) k.y else k.x).let { if (it < 0f) 0.5f else it }
                                val sy = when (rotation) { 2 -> 1f - down; 1 -> 1f - down; else -> down }
                                val sx = when (rotation) { 2 -> 1f - across; 3 -> 1f - across; else -> across }
                                if (!k.started) {
                                    k.started = true; k.startY = sy; k.fromEdge = sy <= band
                                    if (debugLogs < 20) { debugLogs++; android.util.Log.i("Launcher", "[touch-edge] down on ${d.path} at y $sy (raw ${k.x}, ${k.y}), from the edge ${k.fromEdge}") }
                                    continue
                                }
                                if (k.fromEdge && !k.told && sy - k.startY >= travel) {
                                    k.told = true
                                    android.util.Log.i("Launcher", "[touch-edge] swipe from the top edge (x $sx)")
                                    if (edgeGen.get() == g) try { listener.onEdgeSwipe(sx) } catch (_: Throwable) { return }
                                }
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            android.util.Log.i("Launcher", "[touch-edge] failed: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            for (fd in fds) try { Os.close(fd) } catch (_: Throwable) { }
        }
    }

    private fun run(g: Int, listener: ITouchStream) {
        val list = devices ?: discover().also { devices = it; android.util.Log.i("Launcher", "[touch-stream] touchscreens: ${it.joinToString { d -> "${d.path} (${d.maxX}x${d.maxY})" }}") }
        if (list.isEmpty()) return
        val fds = ArrayList<FileDescriptor>()
        try {
            for (d in list) fds += Os.open(d.path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK, 0)
            val polls = Array(fds.size) { i -> StructPollfd().apply { fd = fds[i]; events = OsConstants.POLLIN.toShort() } }
            val states = Array(fds.size) { State() }
            val buf = ByteArray(EVENT * 64)
            val end = SystemClock.uptimeMillis() + MAX_MS
            while (gen.get() == g && SystemClock.uptimeMillis() < end) {
                if (Os.poll(polls, 100) <= 0) continue
                for (i in polls.indices) {
                    if ((polls[i].revents.toInt() and OsConstants.POLLIN) == 0) continue
                    val n = try { Os.read(fds[i], buf, 0, buf.size) } catch (_: Throwable) { 0 }
                    if (n > 0 && !parse(buf, n, states[i], list[i], g, listener)) return
                }
            }
        } catch (t: Throwable) {
            android.util.Log.i("Launcher", "[touch-stream] failed: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            for (fd in fds) try { Os.close(fd) } catch (_: Throwable) { }
        }
    }

    /** Reports what [n] bytes of events say; false once every finger is up (the stream ends). */
    private fun parse(buf: ByteArray, n: Int, s: State, d: Device, g: Int, listener: ITouchStream): Boolean {
        var off = 0
        while (off + EVENT <= n) {
            val type = u16(buf, off + 16)
            val code = u16(buf, off + 18)
            val value = s32(buf, off + 20)
            off += EVENT
            when (type) {
                EV_ABS -> when (code) {
                    // Started mid-touch, the finger's slot was never reported: once slots appear (another finger), the
                    // first finger is taken to be slot 0, as it almost always is.
                    ABS_MT_SLOT -> { s.slot = value; if (s.tracked == -1) s.tracked = 0 }
                    // Our finger lifted (some touchscreens never send BTN_TOUCH: the emulator's).
                    ABS_MT_TRACKING_ID -> if (value == -1 && s.tracked != -2 && (s.slot == s.tracked || (s.slot == -1 && s.tracked <= 0))) {
                        if (gen.get() == g) try { listener.onTouch(1, s.x / d.maxX.toFloat(), s.y / d.maxY.toFloat(), System.nanoTime()) } catch (_: Throwable) { }
                        return false
                    }
                    ABS_MT_POSITION_X, ABS_MT_POSITION_Y -> {
                        if (s.tracked == -2) s.tracked = s.slot
                        if (s.slot == s.tracked || (s.slot == -1 && s.tracked <= 0)) {
                            if (code == ABS_MT_POSITION_X) s.x = value else s.y = value
                            s.moved = true
                        }
                    }
                }
                EV_KEY -> if (code == BTN_TOUCH && value == 0) {
                    if (gen.get() == g) try { listener.onTouch(1, s.x / d.maxX.toFloat(), s.y / d.maxY.toFloat(), System.nanoTime()) } catch (_: Throwable) { }
                    return false
                }
                // The kernel drops repeated values: a straight vertical pull may never report x (unknown: -1).
                EV_SYN -> if (code == SYN_REPORT && s.moved && (s.x >= 0 || s.y >= 0)) {
                    s.moved = false
                    if (gen.get() == g) try { listener.onTouch(2, s.x / d.maxX.toFloat(), s.y / d.maxY.toFloat(), System.nanoTime()) } catch (_: Throwable) { return false }
                }
            }
        }
        return true
    }

    /** Devices with multi-touch positions, from `getevent -pl` (parsed once). */
    private fun discover(): List<Device> {
        val out = ArrayList<Device>()
        val text = try {
            val p = ProcessBuilder("getevent", "-pl").redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText().also { p.waitFor() }
        } catch (_: Throwable) { return out }
        var path: String? = null
        var maxX = -1
        var maxY = -1
        fun flush() { path?.let { if (maxX > 0 && maxY > 0) out += Device(it, maxX, maxY) } }
        for (line in text.lines()) {
            val t = line.trim()
            when {
                t.startsWith("add device") -> { flush(); path = t.substringAfter(": ").trim(); maxX = -1; maxY = -1 }
                t.startsWith("ABS_MT_POSITION_X") -> maxX = Regex("max (\\d+)").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                t.startsWith("ABS_MT_POSITION_Y") -> maxY = Regex("max (\\d+)").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            }
        }
        flush()
        return out
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun s32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private companion object {
        /** struct input_event on 64-bit: timeval (16) + type (2) + code (2) + value (4). */
        const val EVENT = 24
        const val EV_SYN = 0
        const val EV_KEY = 1
        const val EV_ABS = 3
        const val SYN_REPORT = 0
        const val BTN_TOUCH = 0x14a
        const val ABS_MT_SLOT = 0x2f
        const val ABS_MT_TRACKING_ID = 0x39
        const val MAX_SLOTS = 10
        const val ABS_MT_POSITION_X = 0x35
        const val ABS_MT_POSITION_Y = 0x36
        /** No pull lasts longer than this: the stream ends whatever happens. */
        const val MAX_MS = 20_000L
    }
}
