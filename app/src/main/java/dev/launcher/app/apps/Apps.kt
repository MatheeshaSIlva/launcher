package dev.launcher.app.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import dev.launcher.app.AppLog
import dev.launcher.app.DevActivity
import dev.launcher.app.HomeActivity
import java.text.Collator
import java.util.concurrent.Executors

/**
 * One launchable activity. [key] is stable across restarts and is what layouts save:
 * "package/class@userSerial" (the serial tells a work-profile copy from the personal one).
 */
class AppEntry(
    val key: String,
    val component: ComponentName,
    val user: UserHandle,
    val label: String,
    val category: AppCategory,
    val installedAt: Long,
    val system: Boolean,
    private val info: LauncherActivityInfo?,
    /** Our own screens (the dev panel): started directly, never through the launch card. */
    val internal: Boolean = false,
) {
    val pkg: String get() = component.packageName

    fun loadIcon(ctx: Context, density: Int): Drawable? = try {
        info?.getIcon(density) ?: ctx.packageManager.getApplicationIcon(pkg)
    } catch (_: Throwable) { null }
}

/**
 * Every launchable app, for all profiles (LauncherApps), kept current when apps are installed, removed or changed.
 * [all] is sorted by label. Listeners run on the main thread after each reload.
 */
object Apps {
    private lateinit var ctx: Context
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val listeners = mutableListOf<() -> Unit>()
    private val collator: Collator = Collator.getInstance().apply { strength = Collator.PRIMARY }

    @Volatile var all: List<AppEntry> = emptyList()
        private set
    @Volatile private var byKey: Map<String, AppEntry> = emptyMap()
    @Volatile private var byPkg: Map<String, AppEntry> = emptyMap()
    @Volatile var loaded = false
        private set

    private val launcherApps get() = ctx.getSystemService(LauncherApps::class.java)
    private val users get() = ctx.getSystemService(UserManager::class.java)

    fun init(context: Context) {
        ctx = context.applicationContext
        try { launcherApps.registerCallback(callback, main) } catch (t: Throwable) { AppLog.log("[apps] callback failed: ${t.message}") }
        reload()
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    operator fun get(key: String): AppEntry? = byKey[key]
    fun forPkg(pkg: String): AppEntry? = byPkg[pkg]

    fun keyFor(component: ComponentName, user: UserHandle): String =
        "${component.packageName}/${component.className}@${users.getSerialNumberForUser(user)}"

    /** Starts [e] as a new task (with [bounds] = where its icon is, and [options] e.g. "no system transition"). */
    fun launch(e: AppEntry, bounds: Rect?, options: Bundle?) {
        if (e.internal) {
            ctx.startActivity(Intent().setComponent(e.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        try {
            launcherApps.startMainActivity(e.component, e.user, bounds, options)
        } catch (t: Throwable) {
            AppLog.log("[apps] start ${e.key} failed (${t.javaClass.simpleName}); trying the launch intent")
            ctx.packageManager.getLaunchIntentForPackage(e.pkg)?.let {
                ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options)
            }
        }
    }

    private var reloadPending = false

    /** Reloads on the worker (coalesced): many package events arrive in a burst during an update. */
    fun reload() {
        if (reloadPending) return
        reloadPending = true
        main.postDelayed({
            reloadPending = false
            io.execute {
                val t = System.currentTimeMillis()
                val list = try { load() } catch (e: Throwable) { AppLog.log("[apps] load failed: ${e.javaClass.simpleName}: ${e.message}"); null }
                main.post {
                    if (list == null) return@post
                    all = list
                    byKey = list.associateBy { it.key }
                    byPkg = LinkedHashMap<String, AppEntry>().apply { for (e in list) putIfAbsent(e.pkg, e) }
                    loaded = true
                    AppLog.log("[apps] ${list.size} apps (${System.currentTimeMillis() - t} ms)")
                    listeners.toList().forEach { it() }
                }
            }
        }, 150)
    }

    private val installTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun load(): List<AppEntry> {
        val pm = ctx.packageManager
        val out = ArrayList<AppEntry>()
        val home = ComponentName(ctx, HomeActivity::class.java)
        for (user in users.userProfiles) {
            val infos = try { launcherApps.getActivityList(null, user) } catch (_: Throwable) { emptyList() }
            for (info in infos) {
                val cn = info.componentName
                if (cn == home) continue   // we are the home screen; it is not an app to open
                val ai: ApplicationInfo? = info.applicationInfo
                val system = ai != null && ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                val installed = installTimes.getOrPut(cn.packageName) {
                    try { pm.getPackageInfo(cn.packageName, 0).firstInstallTime } catch (_: Throwable) { 0L }
                }
                out += AppEntry(
                    key = keyFor(cn, user),
                    component = cn,
                    user = user,
                    label = info.label?.toString()?.trim().orEmpty().ifEmpty { cn.packageName },
                    category = AppCategory.classify(cn.packageName, ai, system),
                    installedAt = installed,
                    system = system,
                    info = info,
                )
            }
        }
        // The developer panel (updater, log) stays reachable from the App Library and search.
        val dev = ComponentName(ctx, DevActivity::class.java)
        out += AppEntry(keyFor(dev, android.os.Process.myUserHandle()), dev, android.os.Process.myUserHandle(), "Launcher Dev",
            AppCategory.UTILITIES, 0L, true, null, internal = true)
        out.sortWith { a, b -> collator.compare(a.label, b.label) }
        return out
    }

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) { installTimes.remove(packageName); reload() }
        override fun onPackageAdded(packageName: String, user: UserHandle) { reload() }
        override fun onPackageChanged(packageName: String, user: UserHandle) { reload() }
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) { reload() }
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) { reload() }
    }
}
