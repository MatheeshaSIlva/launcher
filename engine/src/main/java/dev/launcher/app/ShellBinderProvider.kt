package dev.launcher.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import dev.launcher.engine.shell.Starter

/**
 * Where our own shell process hands its binder back (T-001 slice 2b, `Starter`). Exported with no permission: every
 * method but [Starter.METHOD] from uid 2000 refuses before anything else is read. MEASUREMENT BUILD: accepts any uid-2000
 * call; the start attempt's capability check (slice 2a's StartAttempt) replaces this before it reaches engine.
 */
class ShellBinderProvider : ContentProvider() {
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uid = Binder.getCallingUid()
        if (uid != SHELL_UID || method != Starter.METHOD) { AppLog.log("[shell] handover refused: caller or method"); return null }
        val binder = try { extras?.getBinder("binder") } catch (_: Throwable) { null } ?: run { AppLog.log("[shell] handover refused: no binder"); return null }
        val service = IShellService.Stub.asInterface(binder)
        AppLog.log("[shell] handover from pid ${Binder.getCallingPid()}: ${try { service.identity() } catch (t: Throwable) { "identity failed: ${t.javaClass.simpleName}" }}")
        measured = service
        return Bundle().apply { putBinder("token", ShizukuLink.clientToken) }
    }

    override fun onCreate() = true
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        /** The service of the last accepted handover (measurement only). */
        @Volatile var measured: IShellService? = null
        /** The shell's uid (android.os.Process.SHELL_UID, a system API). */
        private const val SHELL_UID = 2000
    }
}
