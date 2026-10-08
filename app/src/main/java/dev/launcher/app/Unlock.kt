package dev.launcher.app

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * "Unlock, then do it": what our shade opens from the lock screen (an app, a notification, a setting) waits for the user
 * to unlock, as iOS asks for Face ID first. [then] runs [action] at once when the phone is not locked; otherwise it asks
 * the system to show its unlock prompt (the bouncer: fingerprint, face or PIN; through the shell, as `wm dismiss-keyguard`)
 * and runs the action when the user is present (unlocked). Given up on (the screen goes off, or a minute passes), it is
 * dropped. Without the shell, an invisible activity over the lock screen asks instead ([UnlockActivity]).
 *
 * The action runs on the main thread right after the unlock, while our windows are on screen, so what it starts may
 * come to the front.
 */
object Unlock {
    private class Waiting(val action: () -> Unit, val at: Long, val why: String)
    private val pending = ConcurrentHashMap<Int, Waiting>()
    private val ids = AtomicInteger()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var listening = false

    fun locked(ctx: Context): Boolean = try { ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked } catch (_: Throwable) { false }

    /**
     * Called (on the caller's thread) when the unlock prompt is about to be asked for: the shade gives the lock screen its
     * focus back at once (a password's keyboard needs it), not only once its panel has closed.
     */
    @Volatile var onAsk: (() -> Unit)? = null

    fun then(ctx: Context, why: String, action: () -> Unit) {
        if (!locked(ctx)) { action(); return }
        onAsk?.invoke()
        listen(ctx.applicationContext)
        val id = ids.incrementAndGet()
        pending[id] = Waiting(action, SystemClock.uptimeMillis(), why)
        AppLog.log("[unlock] asked to unlock first: $why")
        val s = ShizukuLink.service
        if (s != null) {
            io.execute {
                val r = try { s.runShell("wm dismiss-keyguard") } catch (t: Throwable) { "ERROR: ${t.message}" }
                if (r.contains("ERROR")) { AppLog.log("[unlock] the unlock prompt could not be asked for ($r)"); main.post { viaActivity(ctx, id) } }
            }
        } else viaActivity(ctx, id)
    }

    private fun viaActivity(ctx: Context, id: Int) {
        try {
            ctx.startActivity(Intent(ctx, UnlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                .putExtra(EXTRA_ID, id))
        } catch (t: Throwable) {
            pending.remove(id)
            AppLog.log("[unlock] could not ask: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** USER_PRESENT runs what waited (if recent); SCREEN_OFF drops it (the user gave up). */
    private fun listen(app: Context) {
        if (listening) return
        listening = true
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == Intent.ACTION_SCREEN_OFF) { if (pending.isNotEmpty()) AppLog.log("[unlock] screen off: given up"); pending.clear(); return }
                val now = SystemClock.uptimeMillis()
                val due = pending.entries.sortedBy { it.key }
                pending.clear()
                for ((_, w) in due) {
                    if (now - w.at > MAX_WAIT_MS) continue
                    AppLog.log("[unlock] unlocked: ${w.why}")
                    try { w.action() } catch (t: Throwable) { AppLog.log("[unlock] action failed: ${t.javaClass.simpleName}: ${t.message}") }
                }
            }
        }, IntentFilter().apply { addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_SCREEN_OFF) }, null, main)
    }

    internal fun run(id: Int) {
        val w = pending.remove(id) ?: return
        try { w.action() } catch (t: Throwable) { AppLog.log("[unlock] action failed: ${t.javaClass.simpleName}: ${t.message}") }
    }

    internal fun drop(id: Int) { pending.remove(id) }

    internal const val EXTRA_ID = "unlock_id"
    private const val MAX_WAIT_MS = 60_000L
}

/** Fallback without the shell: invisible, over the lock screen, asks the system to unlock, then runs what waited ([Unlock]). */
class UnlockActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(Unlock.EXTRA_ID, -1)
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) { Unlock.run(id); finishQuietly(); return }
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { Unlock.run(id); finishQuietly() }
            override fun onDismissCancelled() { AppLog.log("[unlock] cancelled"); Unlock.drop(id); finishQuietly() }
            override fun onDismissError() { AppLog.log("[unlock] could not unlock"); Unlock.drop(id); finishQuietly() }
        })
    }

    private fun finishQuietly() {
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}
