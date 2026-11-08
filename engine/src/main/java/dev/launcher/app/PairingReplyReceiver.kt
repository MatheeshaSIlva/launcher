package dev.launcher.app

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The pairing notification's reply (T-001 slice 3). Not exported: only the session's own PendingIntent reaches it, and
 * holding that is the authority (no sender uid is required: Android's shade sends it). Takes the session id from the
 * intent's data and the reply's text, nothing else; [PairingSession] decides whether it counts and does the network
 * work on its own worker, never here.
 */
class PairingReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PairingSession.ACTION_REPLY) return
        val id = intent.data?.takeIf { it.scheme == "launcher-pairing" }?.schemeSpecificPart
        val text = try { RemoteInput.getResultsFromIntent(intent)?.getCharSequence(PairingSession.KEY_CODE) } catch (_: Throwable) { null }
        PairingSession.reply(context, id, text)
    }
}
