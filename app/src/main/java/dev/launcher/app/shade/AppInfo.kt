package dev.launcher.app.shade

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Other apps' names and icons for the shade (a notification's app), read off the UI threads. Reading another app's name
 * or icon loads that app's resources (`LoadApkAssets`: 65 ms for Google's Permission Controller on the S24), and the shade
 * read them while drawing, on gesture nav's thread: Notification Center's first pull after a start had one 82 ms frame.
 * [Notifs] has a new app's read here before it publishes its notifications ([ensure]); the painters only look them up.
 */
object AppInfo {
    private val labels = ConcurrentHashMap<String, String>()
    /** An app's icon as the system gives it (its constant state: each thread makes its own drawable), or [NONE]. */
    private val icons = ConcurrentHashMap<String, Any>()
    private val NONE = Any()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "app-info").apply { priority = Thread.MIN_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())

    /** Reads what is missing of [pkgs] on a worker, then runs [then] on the main thread (at once if nothing is missing). */
    fun ensure(ctx: Context, pkgs: Collection<String>, then: () -> Unit) {
        val missing = pkgs.filter { !labels.containsKey(it) }.distinct()
        if (missing.isEmpty()) { then(); return }
        val app = ctx.applicationContext
        io.execute {
            for (p in missing) read(app, p)
            main.post(then)
        }
    }

    private fun read(ctx: Context, pkg: String) {
        if (labels.containsKey(pkg)) return
        val pm = ctx.packageManager
        icons[pkg] = try { pm.getApplicationIcon(pkg).let { it.constantState ?: it } } catch (_: Throwable) { null } ?: NONE
        // (The name last: [ensure] asks by it whether an app was read.)
        labels[pkg] = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Throwable) { pkg }
    }

    /** [pkg]'s name (read here, blocking, only for an app that came some other way than a notification). */
    fun label(ctx: Context, pkg: String): String = labels[pkg] ?: run { read(ctx, pkg); labels[pkg] ?: pkg }

    /** A new drawable of [pkg]'s icon as the system gives it, or null (read here, blocking, as [label]). */
    fun icon(ctx: Context, pkg: String): Drawable? {
        if (!icons.containsKey(pkg)) read(ctx, pkg)
        return when (val v = icons[pkg]) {
            is Drawable.ConstantState -> try { v.newDrawable(ctx.resources) } catch (_: Throwable) { null }
            is Drawable -> v   // (one with no constant state: shared as it is)
            else -> null
        }
    }
}
