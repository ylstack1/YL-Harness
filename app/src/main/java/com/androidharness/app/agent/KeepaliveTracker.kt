package com.androidharness.app.agent

/** Background services share the wake lease without advertising an active task. */
internal class KeepaliveTracker(
    private val onAcquire: () -> Unit,
    private val onRelease: () -> Unit,
    private val onActiveChanged: (Boolean) -> Unit,
) {
    private var tasks = 0
    private var background = 0

    @Synchronized fun acquire(activeTask: Boolean = true) {
        val wasIdle = tasks + background == 0
        if (activeTask) {
            if (tasks++ == 0) onActiveChanged(true)
        } else background++
        if (wasIdle) onAcquire()
    }

    @Synchronized fun release(activeTask: Boolean = true) {
        if (activeTask) {
            if (tasks == 0) return
            if (--tasks == 0) onActiveChanged(false)
        } else {
            if (background == 0) return
            background--
        }
        if (tasks + background == 0) onRelease()
    }
}
