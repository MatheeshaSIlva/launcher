package dev.launcher.engine.adb

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import java.net.InetAddress

/**
 * Framework-only, test APK UID. The lead owns all device operations. Build :engine:assembleDebugAndroidTest.
 * adb shell am instrument -w -e mode selftest dev.launcher.engine.test/dev.launcher.engine.adb.AdbProofInstrumentation
 * mode pair: -e host ADDRESS -e pairingPort PORT -e connectPort PORT -e code SIX_DIGITS
 * mode reload: -e host ADDRESS -e connectPort PORT (a fresh instrumentation process; no code)
 * mode wrong-code: -e host ADDRESS -e pairingPort PORT -e code WRONG_SIX_DIGITS
 * Never pass an actual code to wrong-code. This runner never prints arguments, commands, output, or exceptions.
 */
class AdbProofInstrumentation : Instrumentation() {
    private lateinit var args: Bundle
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        args = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val result = Bundle()
        if (android.os.Build.VERSION.SDK_INT < 31) {
            result.putString("result", "FAIL (proof requires API 31+)")
            finish(Activity.RESULT_CANCELED, result)
            return
        }
        try {
            when (args.getString("mode", "selftest")) {
                "selftest" -> { nativeTests(); storageTests() }
                "pair" -> {
                    val identity = ready()
                    AdbPairingClient(endpoint("pairingPort"), identity.key).use { it.pair(required("code")) }
                    shellProof(identity.key)
                }
                "reload" -> {
                    val identity = ready()
                    check(!identity.created) { "No persisted identity" }
                    shellProof(identity.key)
                }
                "wrong-code" -> {
                    val key = ready().key
                    var rejected = false
                    try { AdbPairingClient(endpoint("pairingPort"), key).use { it.pair(required("code")) } }
                    catch (_: AdbPairingRejectedException) { rejected = true }
                    check(rejected) { "Wrong code was not cryptographically rejected" }
                }
                // E2 slice 6's component lifecycle and the developer switch's refusal (T-004; the lead's, beside this proof).
                "component-lifecycle" -> dev.launcher.engine.ui.ComponentProof(context).lifecycle(result)
                "component-refusal" -> dev.launcher.engine.ui.ComponentProof(context).refusal(result)
                // T-001 2b's real-binder refusals from this APK's uid (the lead's device checks).
                "shell-wrong-uid" -> dev.launcher.engine.shell.ShellProof(this).wrongUid(result)
                "shell-forwarded" -> dev.launcher.engine.shell.ShellProof(this).forwarded(result)
                // T-005: glyph preparation on a real icon-bearing component (Codex's review).
                "component-icons" -> dev.launcher.engine.ui.ComponentProof(context).preparation(result)
                "component-icon-reveal" -> dev.launcher.engine.ui.IconRevealProof(this).run(result)
                // T-006: knockout and reveal on the GPU.
                "component-layer" -> dev.launcher.engine.ui.LayerProof(this).run(result)
                // T-009 13a-1b: node motion on both drawing paths against its movers.
                "component-motion" -> dev.launcher.engine.ui.MotionProof(this).run(result)
                "component-roll" -> dev.launcher.engine.ui.RollProof(this).run(result)
                "component-placement" -> dev.launcher.engine.ui.PlacementProof(this).run(result)
                "device-display" -> dev.launcher.engine.ui.DeviceDisplayProof(this).run(result)
                "component-shape" -> dev.launcher.engine.ui.ShapeProof(this).run(result)
                "component-measure" -> dev.launcher.engine.ui.MeasureProof(this).run(result)
                "component-island" -> dev.launcher.engine.ui.IslandProof(this, args.getString("cutout") ?: "emu01").run(result)
                "media-sessions" -> dev.launcher.engine.source.MediaSessionProof(this).run(result)
                "media-hold" -> dev.launcher.engine.source.MediaHold(this, args).run(result)
                else -> error("Unknown proof mode")
            }
            result.putString("result", "PASS (test APK UID; no launcher UID claim)")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            // Messages/stacks from providers may contain peer input. Return only a fixed status and exception type.
            result.putString("result", "FAIL")
            result.putString("failureType", error.javaClass.simpleName)
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun required(name: String): String = requireNotNull(args.getString(name)) { "Missing proof argument" }
    private fun endpoint(port: String) = AdbEndpoint(InetAddress.getByName(required("host")), required(port).toInt(), null)
    private fun ready(): AdbIdentityResult.Ready =
        AndroidAdbIdentity.store(context).load() as? AdbIdentityResult.Ready ?: error("Identity unavailable")
    private fun shellProof(key: AdbKey) {
        val output = AdbClient(endpoint("connectPort"), key).use { it.connect(); it.shell("id") }
        check(output.toString(Charsets.UTF_8).contains("uid=2000(shell)")) { "Shell UID proof failed" }
    }

    private fun nativeTests() {
        var checks = 0
        fun exchange(passwordB: String = "test-password", action: (PairingContext, PairingContext) -> Unit) {
            PairingContext("test-password".toByteArray()).use { client ->
                PairingContext(passwordB.toByteArray(), client = false).use { server ->
                    check(client.message().size in 1..PairingContext.MAX_MESSAGE_BYTES)
                    check(client.initCipher(server.message()))
                    check(server.initCipher(client.message()))
                    action(client, server)
                }
            }
        }
        exchange { a, b ->
            val peer = ByteArray(8192) { (it % 251).toByte() }
            repeat(2) {
                check(peer.contentEquals(b.decrypt(checkNotNull(a.encrypt(peer)))))
                check(peer.contentEquals(a.decrypt(checkNotNull(b.encrypt(peer)))))
            }
            checks++
        }
        exchange("wrong-password") { a, b -> check(b.decrypt(checkNotNull(a.encrypt(byteArrayOf(1)))) == null); checks++ }
        exchange { a, b ->
            val ciphertext = checkNotNull(a.encrypt(byteArrayOf(1)))
            ciphertext[0] = (ciphertext[0].toInt() xor 1).toByte()
            check(b.decrypt(ciphertext) == null)
            checks++
        }
        for (size in listOf(0, 1, 33)) PairingContext("test-password".toByteArray()).use {
            check(!it.initCipher(ByteArray(size))); checks++
        }
        PairingContext("test-password".toByteArray()).also { it.close(); it.close(); checks++ }
        check(checks == 7)
    }

    private fun storageTests() {
        val alias = "adb-instrumentation-test-wrap"
        val file = java.io.File(context.noBackupFilesDir, "adb-instrumentation-test-identity")
        val keystore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val atomic = android.util.AtomicFile(file)
        atomic.delete()
        keystore.deleteEntry(alias)
        try {
            fun store() = AdbIdentityStore(AtomicIdentityStorage(file), KeystoreIdentityWrapper(alias))
            val first = store().load() as AdbIdentityResult.Ready
            val second = store().load() as AdbIdentityResult.Ready
            check(first.created && !second.created)
            check(first.key.adbPublicKey.contentEquals(second.key.adbPublicKey))
            keystore.deleteEntry(alias)
            val lost = store().load() as AdbIdentityResult.IdentityLost
            check(lost.reason == AdbIdentityResult.Reason.WRAPPING_KEY_UNAVAILABLE)
            check(!keystore.containsAlias(alias)) // Loading lost identity must not regenerate the wrapping key.
        } finally { atomic.delete(); keystore.deleteEntry(alias) }
    }
}
