package dev.launcher.app.apps

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The background each app's own launch screen uses (Android 12+ splash screen): windowSplashScreenBackground from the
 * launch activity's theme, else its windowBackground colour, else colorBackground. A launch card for an app without a
 * snapshot uses it, so the card turns into what the app itself shows while starting. Resolved on a worker, cached.
 */
object SplashColors {
    private val cache = ConcurrentHashMap<String, Int>()
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val io = Executors.newSingleThreadExecutor()

    fun cached(pkg: String): Int? = cache[pkg]

    /** Resolves [pkg]'s colour (if not known yet) and passes it to [cb] on the worker thread. */
    fun resolve(ctx: Context, pkg: String, cb: (Int) -> Unit = {}) {
        cache[pkg]?.let { cb(it); return }
        if (pkg in missing) return
        val app = ctx.applicationContext
        io.execute {
            val c = cache[pkg] ?: compute(app, pkg)
            if (c == null) missing += pkg else { cache[pkg] = c; cb(c) }
        }
    }

    /** Resolves colours for apps likely to be launched soon (home pages, dock), in the background. */
    fun warm(ctx: Context, pkgs: Collection<String>) { for (p in pkgs) resolve(ctx, p) }

    private fun compute(ctx: Context, pkg: String): Int? = try {
        val pm = ctx.packageManager
        val cn = pm.getLaunchIntentForPackage(pkg)?.component ?: throw IllegalStateException("no launch activity")
        val themeRes = pm.getActivityInfo(cn, 0).themeResource
        val pctx = ctx.createPackageContext(pkg, 0)
        val theme = pctx.resources.newTheme().apply { applyStyle(themeRes, true) }
        // obtainStyledAttributes needs the attributes in ascending order.
        val wanted = intArrayOf(android.R.attr.windowSplashScreenBackground, android.R.attr.windowBackground, android.R.attr.colorBackground)
        val sorted = wanted.sortedArray()
        val a = theme.obtainStyledAttributes(sorted)
        fun colorAt(attr: Int): Int? {
            val i = sorted.indexOf(attr)
            val v = a.peekValue(i) ?: return null
            return when {
                v.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT -> v.data
                else -> (try { a.getDrawable(i) } catch (_: Throwable) { null } as? ColorDrawable)?.color
            }?.takeIf { Color.alpha(it) > 200 }
        }
        val c = colorAt(android.R.attr.windowSplashScreenBackground) ?: colorAt(android.R.attr.windowBackground) ?: colorAt(android.R.attr.colorBackground)
        a.recycle()
        c
    } catch (_: Throwable) { null }
}
