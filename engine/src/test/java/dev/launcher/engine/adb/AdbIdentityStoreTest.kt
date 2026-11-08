package dev.launcher.engine.adb

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class AdbIdentityStoreTest {
    private class Storage : AdbIdentityStorage {
        var bytes: ByteArray? = null
        var failWrite = false
        var dropWrite = false
        override fun read() = bytes?.copyOf()
        override fun write(bytes: ByteArray) {
            if (failWrite) throw java.io.IOException()
            if (!dropWrite) this.bytes = bytes.copyOf()
        }
    }
    private class Wrapper : AdbIdentityWrapper {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        var missing = false
        override fun encrypt(plaintext: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key); iv + doFinal(plaintext)
        }
        override fun decrypt(ciphertext: ByteArray): ByteArray {
            if (missing) throw AdbWrappingKeyUnavailable()
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ciphertext, 0, 12))
                doFinal(ciphertext, 12, ciphertext.size - 12)
            }
        }
    }

    @Test fun inspectingReadsOnceSoADeleteMeanwhileMakesNoKey() {      // Forget in the instant of a status check
        val made = Storage()
        val wrapper = Wrapper()
        AdbIdentityStore(made, wrapper).load()
        val saved = made.read()!!
        var reads = 0
        var writes = 0
        val deleted = object : AdbIdentityStorage {
            override fun read(): ByteArray? = if (reads++ == 0) saved else null    // gone after the first read
            override fun write(bytes: ByteArray) { writes++ }
        }
        org.junit.Assert.assertTrue(AdbIdentityStore(deleted, wrapper).inspect() is AdbIdentityResult.Ready)
        org.junit.Assert.assertEquals(1, reads)
        org.junit.Assert.assertEquals(0, writes)
    }

    @Test fun inspectingMakesNoIdentityAndReportsWhatIsThere() {
        val storage = Storage()
        val store = AdbIdentityStore(storage, Wrapper())
        org.junit.Assert.assertNull(store.inspect())
        org.junit.Assert.assertNull(storage.read())                        // nothing was made
        store.load()
        org.junit.Assert.assertTrue(store.inspect() is AdbIdentityResult.Ready)
        storage.write(ByteArray(64))
        org.junit.Assert.assertTrue(store.inspect() is AdbIdentityResult.IdentityLost)
    }

    @Test fun missingStorageCreatesPersistedIdentityAndReloadsSamePublicKey() {
        val storage = Storage()
        val wrapper = Wrapper()
        val first = AdbIdentityStore(storage, wrapper).load() as AdbIdentityResult.Ready
        assertTrue(first.created)
        val second = AdbIdentityStore(storage, wrapper).load() as AdbIdentityResult.Ready
        assertFalse(second.created)
        assertArrayEquals(first.key.adbPublicKey, second.key.adbPublicKey)
        assertFalse(storage.bytes!!.contentEquals(wrapper.decrypt(storage.bytes!!)))
    }

    @Test fun corruptCiphertextRequiresRepairAndIsNeverReplaced() {
        val storage = Storage()
        val wrapper = Wrapper()
        AdbIdentityStore(storage, wrapper).load()
        storage.bytes!![20] = (storage.bytes!![20].toInt() xor 1).toByte()
        val before = storage.read()
        val result = AdbIdentityStore(storage, wrapper).load() as AdbIdentityResult.IdentityLost
        assertEquals(AdbIdentityResult.Reason.CORRUPT_IDENTITY, result.reason)
        assertArrayEquals(before, storage.read())
    }

    @Test fun missingOrInvalidatedWrapperRequiresRepairWithoutOverwritingIdentity() {
        val storage = Storage()
        val wrapper = Wrapper()
        AdbIdentityStore(storage, wrapper).load()
        val before = storage.read()
        wrapper.missing = true
        val result = AdbIdentityStore(storage, wrapper).load() as AdbIdentityResult.IdentityLost
        assertEquals(AdbIdentityResult.Reason.WRAPPING_KEY_UNAVAILABLE, result.reason)
        assertArrayEquals(before, storage.read())
    }

    @Test fun failedAndUnconfirmedWriteNeverReportReady() {
        for (drop in listOf(false, true)) {
            val storage = Storage().apply { failWrite = !drop; dropWrite = drop }
            assertTrue(AdbIdentityStore(storage, Wrapper()).load() is AdbIdentityResult.StorageFailure)
        }
    }

    @Test fun oversizedAndTruncatedIdentityAreRefused() {
        for (size in listOf(0, 28, AdbIdentityStore.MAX_STORED_BYTES + 1)) {
            val storage = Storage().apply { bytes = ByteArray(size) }
            assertTrue(AdbIdentityStore(storage, Wrapper()).load() is AdbIdentityResult.IdentityLost)
        }
    }

    @Test fun publicKeyHasAndroidStructAndBoundedName() {
        val key = (AdbIdentityStore(Storage(), Wrapper()).load("test@host") as AdbIdentityResult.Ready).key
        val text = key.adbPublicKey.toString(Charsets.US_ASCII)
        assertTrue(text.endsWith(" test@host\u0000"))
        val struct = Base64.getDecoder().decode(text.substringBefore(' '))
        assertEquals(524, struct.size)
        val bytes = ByteBuffer.wrap(struct).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, bytes.int)
        val n0inv = bytes.int.toLong() and 0xffffffffL
        val n0 = bytes.int.toLong() and 0xffffffffL
        assertEquals(0xffffffffL, (n0 * n0inv) and 0xffffffffL)
        assertEquals(65537, ByteBuffer.wrap(struct).order(ByteOrder.LITTLE_ENDIAN).getInt(520))
        val exported = key.adbPublicKey
        exported[0] = 0
        assertFalse(exported.contentEquals(key.adbPublicKey))
    }
}
