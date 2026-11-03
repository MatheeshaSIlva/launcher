package dev.launcher.bench

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import dev.launcher.bench.kdl.KdlBench
import dev.launcher.bench.lua.LuaBench
import java.io.File

/**
 * Runs one step-0 suite (docs/BENCH_STEP0.md) and writes its lines to files/results/SUITE-FLAVOUR.txt (adb pulls it from
 * /sdcard/Android/data/dev.launcher.bench[.luak]/files/), then SUITE-FLAVOUR.done.
 *   am start -n dev.launcher.bench/.BenchActivity --es suite lua|kdl|glass|deck
 */
class BenchActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var surface: SurfaceView
    @Volatile private var surfaceReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // The display's fastest mode at its resolution: the deck is measured at the refresh rate the launcher holds.
        val d = display
        val mode = d?.supportedModes?.filter { it.physicalWidth == d.mode.physicalWidth }?.maxByOrNull { it.refreshRate }
        if (mode != null) window.attributes = window.attributes.also { it.preferredDisplayModeId = mode.modeId }
        surface = SurfaceView(this)
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false }
        })
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f; setPadding(32, 120, 32, 32) }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(surface)
            addView(status)
        })
        val suite = intent.getStringExtra("suite") ?: "lua"
        Thread({ run(suite) }, "bench").start()
    }

    private fun run(suite: String) {
        val flavour = if (packageName.endsWith(".luak")) "luak" else "luaj"
        val dir = File(getExternalFilesDir(null), "results").apply { mkdirs() }
        File(dir, "$suite-$flavour.done").delete()
        val r = Report(suite) { s ->
            Log.i("bench", s)
            runOnUiThread { status.append(s.take(160) + "\n") }
        }
        r.line("bench $suite ($flavour) on ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}, " +
            "ART ${System.getProperty("java.vm.version")}")
        Thread.sleep(1500)
        r.guard(suite) {
            when (suite) {
                "lua" -> for (vm in Vms.all) LuaBench.run(vm, r)
                "kdl" -> KdlBench.run(r)
                "glass" -> GlassRun.run(this, r, File(getExternalFilesDir(null), "glass"))
                "deck" -> {
                    while (!surfaceReady) Thread.sleep(20)
                    DeckSpike(this, surface, r).apply { onCrash = { write(dir, suite, flavour, r) } }.run()
                }
                else -> r.line("unknown suite $suite")
            }
        }
        r.line("end")
        write(dir, suite, flavour, r)
    }

    private fun write(dir: File, suite: String, flavour: String, r: Report) {
        File(dir, "$suite-$flavour.txt").writeText(r.lines.joinToString("\n", postfix = "\n"))
        File(dir, "$suite-$flavour.done").writeText("")
    }
}
