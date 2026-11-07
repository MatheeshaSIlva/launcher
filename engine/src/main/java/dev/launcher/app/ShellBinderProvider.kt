package dev.launcher.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/**
 * Where our own shell process hands its binder back (T-001 slice 2b, `Starter`). Exported with no permission: the call
 * goes to [OwnHost.handover], whose [dev.launcher.engine.shell.StartAttempt] checks the calling uid and the method before
 * anything else and accepts only the live attempt's capability; with Shizuku as the host there is no attempt and every
 * call refuses. Every other operation of a provider does nothing.
 */
class ShellBinderProvider : ContentProvider() {
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? =
        OwnHost.current?.handover(Binder.getCallingUid(), method, extras)

    override fun onCreate() = true
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
