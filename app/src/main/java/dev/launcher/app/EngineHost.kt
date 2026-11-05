package dev.launcher.app

import android.content.ComponentName

/**
 * What the engine's code asks of the launcher that hosts it (E1, docs/E1_INVENTORY.md cuts 1 and 2): the code moving into
 * the engine module may not name the app's classes (GestureNav, LauncherApp, HomeActivity, Badges), so it reads them
 * through this. Implemented by [LauncherApp]; in the app's package until it moves with the rest of the knot (slice M3).
 */
interface EngineHost {
    /** The build's stamp (`BUILD <sha7>`, "local"): the Shizuku service's version and the updater's installed build. */
    fun buildStamp(): String

    /** The notification listener's component (home's badges, the shade's notifications): granted access through Shizuku. */
    fun listenerComponent(): ComponentName

    /** Home's own activity: left out of the app list (we are the home screen, not an app to open). */
    fun homeComponent(): ComponentName

    /** Gesture navigation. */
    val nav: NavHost

    companion object {
        @Volatile private var host: EngineHost? = null

        /** Set by [LauncherApp.onCreate] before anything else, once per process. */
        fun install(h: EngineHost) { host = h }

        /**
         * The host, for reading [what] (named if it is not set yet). Fails loudly instead of answering for a host that is
         * not there: an init-order bug must crash in the emulator smoke test, not quietly read "not ready".
         *
         * Only the app's process after [LauncherApp.onCreate] may get here. Never from ShizukuProvider's path (its onCreate,
         * Shizuku's binder listeners it triggers: a content provider is created before Application.onCreate), nor from
         * the shell process (ShellService runs under app_process, where no LauncherApp is ever created).
         */
        fun current(what: String): EngineHost = host ?: throw IllegalStateException(
            "EngineHost read for $what before it was set: LauncherApp.onCreate must call EngineHost.install(this) before anything else"
        )
    }
}

/**
 * Gesture navigation as the engine's code reads it: the members of [GestureNav] it used directly, with their names and
 * meaning unchanged (GestureNav implements this).
 */
interface NavHost {
    /** Our windows can be shown (accessibility service connected). Gesture flags are only set while this is true. */
    val ready: Boolean
    /** Our status bar is on screen: only then are the stock clock and icons hidden, and the stock shade blocked. */
    val statusBarShown: Boolean
    /** No gesture and no card moving (a hint for work that would cost a frame, not a lock). */
    val idle: Boolean
    /** Home is in front (HomeActivity resumed). */
    val homeVisible: Boolean
    /** Shows or removes our windows to match the current state (any thread). */
    fun update()
    /** One of our own screens (safe settings) has drawn its first frame: a launch card waiting for it can go. Any thread. */
    fun ownScreenDrawn(): Boolean
    fun attach(service: NavAccessibilityService)
    fun detach(service: NavAccessibilityService)
    /** A window came to the front (accessibility event). */
    fun onWindowStateChanged(pkg: String?, className: String?)
    /** The app in front changed what it shows (accessibility event). */
    fun onContentChanged(pkg: String?)
}
