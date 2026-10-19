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

/**
 * A search field that declines the keyboard's requests for where its cursor is on screen. Samsung's keyboard asks for them
 * continuously, and Android then works the cursor's position out and sends it to the keyboard at every frame the field
 * moves (Spotlight's field rises with its pull and with the keyboard: 17 % of the main thread over the opening, simpleperf
 * on the S24). Our search fields have nothing to place by the cursor; the keyboard types into them the same.
 */
open class SearchEditText(ctx: android.content.Context) : android.widget.EditText(ctx) {
    override fun onCreateInputConnection(outAttrs: android.view.inputmethod.EditorInfo): android.view.inputmethod.InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs) ?: return null
        return object : android.view.inputmethod.InputConnectionWrapper(ic, false) {
            override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false
            override fun requestCursorUpdates(cursorUpdateMode: Int, cursorUpdateFilter: Int): Boolean = false
        }
    }
}
