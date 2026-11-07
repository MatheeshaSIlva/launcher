package dev.launcher.engine.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * A lifecycle and saved state for a `ComposeView` whose window draws on a thread other than main (the shade's, on
 * gesture nav's `nav-ui`): `LifecycleRegistry.createUnsafe` leaves out the main-thread check, so every call must come from
 * that one thread. Ported from step 0's bench (`DeckSpike.ThreadOwner`), with [stop] for detaching.
 */
class ThreadOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry.createUnsafe(this)
    private val saved = SavedStateRegistryController.create(this)
    private var started = false
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    /** RESUMED (once): what a composition needs to run. */
    fun start() {
        if (started) return
        started = true
        saved.performAttach()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    /** DESTROYED: the composition disposed; this owner is not started again. */
    fun stop() {
        if (registry.currentState == Lifecycle.State.INITIALIZED) return
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
