package dev.launcher.engine.adb

import androidx.annotation.Keep
import java.io.Closeable

/** Shizuku's SPAKE2/HKDF/AES-GCM bridge; serialized ownership prevents use-after-free on cancellation. */
@Keep
internal class PairingContext(password: ByteArray, client: Boolean = true) : Closeable {
    private var pointer = nativeConstructor(client, password).also { check(it != 0L) { "SPAKE2 creation failed" } }
    @Synchronized fun message(): ByteArray = nativeMsg(live())
    @Synchronized fun initCipher(message: ByteArray): Boolean = nativeInitCipher(live(), message)
    @Synchronized fun encrypt(bytes: ByteArray): ByteArray? = nativeEncrypt(live(), bytes)
    @Synchronized fun decrypt(bytes: ByteArray): ByteArray? = nativeDecrypt(live(), bytes)
    @Synchronized override fun close() {
        if (pointer != 0L) { nativeDestroy(pointer); pointer = 0 }
    }
    private fun live(): Long = pointer.also { check(it != 0L) { "Pairing context closed" } }
    private external fun nativeMsg(pointer: Long): ByteArray
    private external fun nativeInitCipher(pointer: Long, message: ByteArray): Boolean
    private external fun nativeEncrypt(pointer: Long, bytes: ByteArray): ByteArray?
    private external fun nativeDecrypt(pointer: Long, bytes: ByteArray): ByteArray?
    private external fun nativeDestroy(pointer: Long)

    companion object {
        const val MAX_MESSAGE_BYTES = 32 // BoringSSL SPAKE2_MAX_MSG_SIZE, also asserted by native compilation.
        init { System.loadLibrary("launcher_adb") }
        @JvmStatic private external fun nativeConstructor(client: Boolean, password: ByteArray): Long
    }
}
