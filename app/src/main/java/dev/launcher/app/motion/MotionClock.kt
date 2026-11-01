package dev.launcher.app.motion

/**
 * The time motion runs on: real time, or, for a test's trace, a fixed step per frame ([fixedStepNs]) so that two runs on a
 * busy emulator move exactly alike frame for frame (docs/PLAN_PANEL_TRANSITIONS.md, step 2b: the old choreography and the
 * new one compared value by value). [MotionValue] and the panels' transition players read it.
 */
object MotionClock {
    /** 0: real time (always, outside a test). Else every new frame moves time on by exactly this much. */
    @Volatile var fixedStepNs = 0L
        private set

    @Volatile private var virtualNs = 0L
    @Volatile private var lastFrameNs = 0L
    /** The thread whose frames move the fixed step on (the one under test): other threads' frames read it as it is. */
    @Volatile private var owner: Thread? = null

    /**
     * Starts (or, with 0, ends) the fixed step on the calling thread; time starts again from 0. Only that thread's frames
     * move it on: another thread's frame for an earlier vsync arriving later counted twice (home's motion on the main
     * thread made two runs of the shade differ by a frame).
     */
    @Synchronized fun fixed(stepNs: Long) {
        virtualNs = 0L
        lastFrameNs = 0L
        owner = if (stepNs == 0L) null else Thread.currentThread()
        fixedStepNs = stepNs
    }

    /** Now, for something that starts between frames (an animation's start). Other threads than the one under test: real time. */
    fun now(): Long = if (fixedStepNs == 0L || Thread.currentThread() !== owner) System.nanoTime() else virtualNs

    /**
     * The time a frame callback for [frameTimeNanos] runs at: that time, or with a fixed step the virtual time, moved on
     * once per new frame (every thread's callbacks for the same frame see the same time).
     */
    @Synchronized fun frame(frameTimeNanos: Long): Long {
        if (fixedStepNs == 0L) return frameTimeNanos
        if (Thread.currentThread() !== owner) return frameTimeNanos
        if (frameTimeNanos != lastFrameNs) {
            lastFrameNs = frameTimeNanos
            virtualNs += fixedStepNs
        }
        return virtualNs
    }
}
