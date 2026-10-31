package dev.launcher.app.shade

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Bundle
import android.view.SurfaceControl
import android.view.WindowManager
import dev.launcher.app.AppLog
import dev.launcher.app.ShizukuLink

/**
 * Probe (2026-10-31): can the live sheet's edge refract what is behind it live? The shell takes the screen under our
 * windows ([dev.launcher.app.ShellCapture]); this hands it our overlay windows' layers to leave out, logs the times and
 * saves the last capture to `files/dev-out/capture.png`.
 *
 * `am broadcast -a dev.launcher.app.TEST_SHADE -p dev.launcher.app --es do capture-probe [--ei n 60] [--ef scale 0.5]
 * [--ei top 0 --ei bottom 600] [--ei gap 8] [--ez exclude true]`
 */
internal object CaptureProbe {
    /** Our overlay windows' layers (every window of the process that is not an activity's), and what was found. */
    fun overlays(): Pair<List<SurfaceControl>, String> {
        val notes = StringBuilder()
        val out = ArrayList<SurfaceControl>()
        try {
            val g = Class.forName("android.view.WindowManagerGlobal")
            val inst = g.getMethod("getInstance").invoke(null)
            val roots = g.getDeclaredField("mRoots").apply { isAccessible = true }.get(inst) as List<*>
            for (r in roots) {
                if (r == null) continue
                val view = try { r.javaClass.getMethod("getView").invoke(r) as? android.view.View } catch (t: Throwable) { null }
                val lp = view?.layoutParams as? WindowManager.LayoutParams
                val type = lp?.type ?: -1
                val title = lp?.title ?: "?"
                if (type in WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW..WindowManager.LayoutParams.LAST_APPLICATION_WINDOW) { notes.append("[$title app] "); continue }
                val sc = try { r.javaClass.getMethod("getSurfaceControl").invoke(r) as? SurfaceControl } catch (t: Throwable) {
                    try { r.javaClass.getDeclaredField("mSurfaceControl").apply { isAccessible = true }.get(r) as? SurfaceControl } catch (t2: Throwable) { notes.append("[$title: ${t2.javaClass.simpleName}] "); null }
                }
                if (sc != null && sc.isValid) { out += sc; notes.append("[$title $type] ") } else notes.append("[$title invalid] ")
            }
        } catch (t: Throwable) {
            notes.append("FAILED ${t.javaClass.simpleName}: ${t.message}")
        }
        return out to notes.toString()
    }

    fun run(ctx: Context, i: Intent) {
        Thread {
            val s = ShizukuLink.service ?: run { AppLog.log("[probe] capture: no shell"); return@Thread }
            val (layers, notes) = if (i.getBooleanExtra("exclude", true)) overlays() else (emptyList<SurfaceControl>() to "none")
            AppLog.log("[probe] capture: leaving out ${layers.size}: $notes")
            val args = Bundle().apply {
                putInt("n", i.getIntExtra("n", 60))
                putFloat("scale", i.getFloatExtra("scale", 0.5f))
                putInt("gapMs", i.getIntExtra("gap", 0))
                putLong("uid", i.getIntExtra("uid", -1).toLong())
                val bottom = i.getIntExtra("bottom", 0)
                if (bottom > 0) putParcelable("crop", Rect(0, i.getIntExtra("top", 0), ctx.resources.displayMetrics.widthPixels, bottom))
                putParcelableArray("exclude", layers.toTypedArray())
            }
            val r = try { s.probeCapture(args) } catch (t: Throwable) { AppLog.log("[probe] capture FAILED: $t"); return@Thread }
            AppLog.log("[probe] capture: ${r.getString("report")}")
            @Suppress("DEPRECATION") val hb = r.getParcelable<HardwareBuffer>("buffer") ?: return@Thread
            try {
                val bmp = Bitmap.wrapHardwareBuffer(hb, android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
                val soft = bmp?.copy(Bitmap.Config.ARGB_8888, false)
                val dir = java.io.File(ctx.getExternalFilesDir(null), "dev-out").apply { mkdirs() }
                java.io.File(dir, "capture.png").outputStream().use { soft?.compress(Bitmap.CompressFormat.PNG, 100, it) }
                AppLog.log("[probe] capture saved ${soft?.width}x${soft?.height}")
            } catch (t: Throwable) { AppLog.log("[probe] capture save FAILED: $t") } finally { hb.close() }
        }.start()
    }
}
