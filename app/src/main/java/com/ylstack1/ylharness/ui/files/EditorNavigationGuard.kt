package com.ylstack1.ylharness.ui.files

/** Lets the containing workspace ask the editor before replacing an unsaved file. */
class EditorNavigationGuard {
    internal var confirm: ((() -> Unit) -> Unit)? = null
    fun navigate(action: () -> Unit) { confirm?.invoke(action) ?: action() }
}
