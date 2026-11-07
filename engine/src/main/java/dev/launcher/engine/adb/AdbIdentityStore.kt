package dev.launcher.engine.adb

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec

/** Seams keep persistence failures distinct from an identity which must be paired again. */
interface AdbIdentityStorage {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
}

interface AdbIdentityWrapper {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray): ByteArray
}

sealed class AdbIdentityResult {
    class Ready(val key: AdbKey, val created: Boolean) : AdbIdentityResult()
    class IdentityLost(val reason: Reason) : AdbIdentityResult()
    class StorageFailure : AdbIdentityResult()
    enum class Reason { CORRUPT_IDENTITY, WRAPPING_KEY_UNAVAILABLE }
}

class AdbWrappingKeyUnavailable : java.security.GeneralSecurityException()

/** Never overwrites unreadable identity data. Recovery/re-pair is an explicit future caller decision. */
class AdbIdentityStore(private val storage: AdbIdentityStorage, private val wrapper: AdbIdentityWrapper) {
    fun load(name: String = "not-a-launcher"): AdbIdentityResult = synchronized(loadLock) { loadLocked(name) }

    private fun loadLocked(name: String): AdbIdentityResult {
        val saved = try { storage.read() } catch (_: Exception) { return AdbIdentityResult.StorageFailure() }
        var plaintext: ByteArray? = null
        return try {
            val created = saved == null
            val privateKey = if (created) {
                (KeyPairGenerator.getInstance("RSA").apply {
                    initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
                }.generateKeyPair().private as RSAPrivateCrtKey)
            } else {
                require(saved!!.size in 29..MAX_STORED_BYTES)
                plaintext = wrapper.decrypt(saved)
                KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(plaintext)) as RSAPrivateCrtKey
            }
            val key = AdbKey(privateKey, name)
            if (created) {
                plaintext = privateKey.encoded
                val encrypted = wrapper.encrypt(plaintext)
                require(encrypted.size in 29..MAX_STORED_BYTES)
                try {
                    storage.write(encrypted)
                    if (!encrypted.contentEquals(storage.read())) return AdbIdentityResult.StorageFailure()
                } catch (_: Exception) { return AdbIdentityResult.StorageFailure() }
            }
            AdbIdentityResult.Ready(key, created)
        } catch (_: AdbWrappingKeyUnavailable) {
            AdbIdentityResult.IdentityLost(AdbIdentityResult.Reason.WRAPPING_KEY_UNAVAILABLE)
        } catch (_: Exception) {
            if (saved == null) AdbIdentityResult.StorageFailure()
            else AdbIdentityResult.IdentityLost(AdbIdentityResult.Reason.CORRUPT_IDENTITY)
        } finally { plaintext?.fill(0) }
    }

    companion object {
        const val MAX_STORED_BYTES = 4096
        private val loadLock = Any() // Also serialize separate store instances for the same app-private identity.
    }
}
