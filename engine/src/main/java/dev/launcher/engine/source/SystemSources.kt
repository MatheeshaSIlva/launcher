package dev.launcher.engine.source

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.PowerManager
import dev.launcher.engine.expr.Value
import kotlinx.coroutines.channels.awaitClose
import java.util.TimeZone

/** `time`: the clock at the last minute tick, and the zone (`time.now`, `time.zone`; the `now` of expressions). */
data class Time(val epochMs: Long, val zoneId: String) : SourceValue {
    override fun member(name: String) = when (name) {
        "now" -> Value.Num(epochMs.toDouble())
        "zone" -> Value.Str(zoneId)
        else -> Value.Null
    }
}

/**
 * `battery`: the level in percent, whether it charges (or is full on the charger), and Low Power mode; `plugged`, the
 * moment charging starts (true for [SystemSources.PLUGGED_MS], T-014: the island shows a plug-in, not the whole charge).
 */
data class Battery(val level: Int, val charging: Boolean, val powerSave: Boolean, val plugged: Boolean = false) : SourceValue {
    override fun member(name: String) = when (name) {
        "level" -> Value.Num(level.toDouble())
        "charging" -> Value.Bool.of(charging)
        "power-save" -> Value.Bool.of(powerSave)
        "plugged" -> Value.Bool.of(plugged)
        else -> Value.Null
    }

    /** The next value after [b], read as StatusBarView reads it (`readBattery`, `readPowerSave`). */
    fun after(b: Broadcast, powerSaveNow: () -> Boolean): Battery = when (b.action) {
        Intent.ACTION_BATTERY_CHANGED -> {
            val level = b.int(BatteryManager.EXTRA_LEVEL, -1)
            val scale = b.int(BatteryManager.EXTRA_SCALE, 100)
            val status = b.int(BatteryManager.EXTRA_STATUS, -1)
            copy(level = if (level >= 0 && scale > 0) level * 100 / scale else this.level,
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
        }
        else -> copy(powerSave = powerSaveNow())
    }
}

object SystemSources {
    private val TIME = listOf(Intent.ACTION_TIME_TICK, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)
    private val BATTERY = listOf(Intent.ACTION_BATTERY_CHANGED, PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)

    fun time(r: Receivers, clock: () -> Long = System::currentTimeMillis, zone: () -> String = { TimeZone.getDefault().id }) =
        Source("time", "none", { Time(clock(), zone()) }, r.flow(TIME) { Time(clock(), zone()) })

    /** How long `battery.plugged` stays true after charging starts (owner, 2026-11-13: "a few seconds at plug-in"). */
    const val PLUGGED_MS = 3000L

    /**
     * [powerSaveNow]: `PowerManager.isPowerSaveMode` (false where it cannot be read, as StatusBarView does). [after] runs
     * a function after a delay on the source's thread and returns its cancel (the handler's `postDelayed` on a device;
     * tests step it by hand); without one, `plugged` never clears by time (it still clears when charging stops).
     */
    fun battery(r: Receivers, after: (Long, () -> Unit) -> () -> Unit = { _, _ -> {} }, powerSaveNow: () -> Boolean): Source<Battery> {
        fun now(): Battery {
            val start = Battery(100, false, powerSaveNow())   // StatusBarView's starting values
            return r.sticky(Intent.ACTION_BATTERY_CHANGED)?.let { start.after(it, powerSaveNow) } ?: start
        }
        return Source("battery", "none", ::now, kotlinx.coroutines.flow.callbackFlow {
            var last = now()
            var clear: (() -> Unit)? = null
            trySend(last)
            val off = r.register(BATTERY) { b ->
                val next = last.after(b, powerSaveNow)
                if (next.charging && !last.charging) {
                    // The plug-in moment: on now, off after PLUGGED_MS (a new plug-in starts it again).
                    clear?.invoke()
                    last = next.copy(plugged = true)
                    clear = after(PLUGGED_MS) { last = last.copy(plugged = false); trySend(last) }
                } else last = next.copy(plugged = last.plugged && next.charging)
                trySend(last)
            }
            awaitClose { clear?.invoke(); off() }
        })
    }

    fun all(context: Context, handler: Handler, display: DisplayInfo? = null): Sources {
        val r = AndroidReceivers(context, handler)
        val power = context.getSystemService(PowerManager::class.java)
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        val after: (Long, () -> Unit) -> () -> Unit = { ms, f -> val run = Runnable(f); handler.postDelayed(run, ms); { handler.removeCallbacks(run) } }
        return Sources(listOf(time(r), battery(r, after) { try { power.isPowerSaveMode } catch (_: Throwable) { false } },
            NetworkSources.network(r, AndroidNetwork(context, handler)),
            NetworkSources.dnd(r) { try { nm.currentInterruptionFilter } catch (_: Throwable) { android.app.NotificationManager.INTERRUPTION_FILTER_ALL } }) +
            listOfNotNull(display?.let(DeviceSource::of)))
    }
}

/** Receivers registered on [handler]'s thread, as StatusBarView's are. */
class AndroidReceivers(private val context: Context, private val handler: Handler) : Receivers {
    private fun Intent.broadcast() = Broadcast(action ?: "", ::getIntExtra)

    override fun register(actions: List<String>, deliver: (Broadcast) -> Unit): () -> Unit {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = deliver(i.broadcast())
        }
        val filter = IntentFilter().apply { actions.forEach(::addAction) }
        context.registerReceiver(receiver, filter, null, handler)?.let { deliver(it.broadcast()) }
        return { try { context.unregisterReceiver(receiver) } catch (_: Throwable) { } }
    }

    override fun sticky(action: String): Broadcast? = context.registerReceiver(null, IntentFilter(action))?.broadcast()
}
