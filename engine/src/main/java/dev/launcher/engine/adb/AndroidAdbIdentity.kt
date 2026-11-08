package dev.launcher.engine.adb

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App-private, excluded from backup; AtomicFile rolls an interrupted write back on the next open. */
object AndroidAdbIdentity {
    fun store(context: Context): AdbIdentityStore = AdbIdentityStore(
        AtomicIdentityStorage(File(context.noBackupFilesDir, "adb-identity")), KeystoreIdentityWrapper(),
    )

    /** Deletes the identity ("Forget pairing", T-001 slice 3); the next pairing makes a new one. */
    fun forget(context: Context) { AtomicFile(File(context.noBackupFilesDir, "adb-identity")).delete() }
}

internal class AtomicIdentityStorage(file: File) : AdbIdentityStorage {
    private val atomic = AtomicFile(file)

    override fun read(): ByteArray? {
        val stream = try { atomic.openRead() } catch (error: java.io.FileNotFoundException) {
            if (atomic.baseFile.exists()) throw error
            return null
        }
        return stream.use { input ->
            val buffer = ByteArray(AdbIdentityStore.MAX_STORED_BYTES + 1)
            var size = 0
            while (size < buffer.size) {
                val count = input.read(buffer, size, buffer.size - size)
                if (count < 0) break
                size += count
            }
            buffer.copyOf(size)
        }
    }

    override fun write(bytes: ByteArray) {
        require(bytes.size <= AdbIdentityStore.MAX_STORED_BYTES)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }
}

internal class KeystoreIdentityWrapper(private val alias: String = "not-a-launcher-adb-wrap-v1") : AdbIdentityWrapper {
    private fun key(create: Boolean): SecretKey {
        try {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(alias, null) as? SecretKey)?.let { return it }
            if (!create) throw AdbWrappingKeyUnavailable()
            return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
            }.generateKey()
        } catch (_: java.security.GeneralSecurityException) { throw AdbWrappingKeyUnavailable() }
    }

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        check(cipher.iv.size == 12)
        cipher.updateAAD(AAD)
        return cipher.iv + cipher.doFinal(plaintext)
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size in 29..AdbIdentityStore.MAX_STORED_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        try { cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, ciphertext, 0, 12)) }
        catch (_: java.security.InvalidKeyException) { throw AdbWrappingKeyUnavailable() }
        cipher.updateAAD(AAD)
        return cipher.doFinal(ciphertext, 12, ciphertext.size - 12)
    }

    companion object {
        private val AAD = "not-a-launcher-adb-identity-v1".toByteArray(Charsets.US_ASCII)
    }
}
