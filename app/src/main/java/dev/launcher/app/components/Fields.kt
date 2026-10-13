package dev.launcher.app.components

import android.view.KeyEvent
import android.widget.TextView

/**
 * [action] once for each Go (the keyboard's action key) or Enter on this field: a hardware Enter calls a field's editor
 * action twice (its key down and its key up), and a search launched its first result twice.
 */
fun TextView.onGo(action: () -> Unit) = setOnEditorActionListener { _, _, event ->
    if (event == null || event.action == KeyEvent.ACTION_DOWN) action()
    true
}
