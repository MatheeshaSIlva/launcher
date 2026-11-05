package dev.launcher.app

import android.graphics.Rect
import java.util.function.Consumer

/**
 * Runs in the shell service (which holds ACCESS_SURFACE_FLINGER): the live frames of some windows, from SurfaceFlinger's
 * window information (what input uses to find the window under a finger, so it follows a window wherever SystemUI moves
 * it: a mini player dragged, snapped to an edge, flung, resized, expanding). Through Android's own listener for it
 * (`android.window.WindowInfosListenerForTest`, Android 14+), which hands over the whole list on every change; only a change
 * of the watched windows goes on to the app.
 */
object WindowWatch {
    private var listener: IWindowWatch? = null
    private var names: Array<String> = emptyArray()
    private var source: Any? = null
    private var consumer: Consumer<List<*>>? = null
    private var lastWhich: IntArray? = null
    private var lastFrames: FloatArray? = null

    /** Reports the windows whose names contain one of [watch] to [l] from now on (replacing the previous watch). */
    @Synchronized
    fun watch(l: IWindowWatch, watch: Array<String>): String {
        listener = l
        names = watch
        lastWhich = null
        lastFrames = null
        if (source != null) return "ok"
        return try {
            val cls = Class.forName("android.window.WindowInfosListenerForTest")
            val inst = cls.getConstructor().newInstance()
            val c = Consumer<List<*>> { list -> onInfos(list) }
            cls.getMethod("addWindowInfosListener", Consumer::class.java).invoke(inst, c)
            source = inst
            consumer = c
            "ok"
        } catch (t: Throwable) {
            listener = null
            "ERROR: ${t.javaClass.simpleName}: ${(t.cause ?: t).message}"
        }
    }

    @Synchronized
    fun stop() {
        listener = null
        val s = source ?: return
        try { s.javaClass.getMethod("removeWindowInfosListener", Consumer::class.java).invoke(s, consumer) } catch (_: Throwable) { }
        source = null
        consumer = null
    }

    private fun onInfos(list: List<*>) {
        val at = android.os.SystemClock.uptimeMillis()
        val (l, n) = synchronized(this) { listener to names }
        l ?: return
        val which = ArrayList<Int>()
        val frames = ArrayList<Float>()
        for (w in list) {
            w ?: continue
            val name = field(w, "name") as? String ?: continue
            val k = n.indexOfFirst { name.contains(it) }
            if (k < 0) continue
            if (field(w, "isVisible") == false) continue
            val b = field(w, "bounds") as? Rect ?: continue
            if (b.isEmpty) continue
            which += k
            frames += b.left.toFloat(); frames += b.top.toFloat(); frames += b.right.toFloat(); frames += b.bottom.toFloat()
        }
        val wa = which.toIntArray()
        val fa = frames.toFloatArray()
        synchronized(this) {
            if (wa contentEquals lastWhich && fa contentEquals lastFrames) return
            lastWhich = wa
            lastFrames = fa
        }
        try { l.onWindows(wa, fa, at) } catch (_: Throwable) { stop() }
    }

    // The window information's fields, looked up once (SurfaceFlinger waits for every listener to finish with a report
    // before it sends the next, input's included: this runs for every window on every change, so it stays light).
    private class Fields(val cls: Class<*>) {
        val name = find("name")
        val isVisible = find("isVisible")
        val bounds = find("bounds")
        private fun find(n: String) = try { cls.getField(n) } catch (_: Throwable) { null }
    }

    @Volatile private var fields: Fields? = null

    private fun field(o: Any, name: String): Any? {
        val fs = fields?.takeIf { it.cls === o.javaClass } ?: Fields(o.javaClass).also { fields = it }
        val f = when (name) { "name" -> fs.name; "isVisible" -> fs.isVisible; else -> fs.bounds }
        return try { f?.get(o) } catch (_: Throwable) { null }
    }
}
