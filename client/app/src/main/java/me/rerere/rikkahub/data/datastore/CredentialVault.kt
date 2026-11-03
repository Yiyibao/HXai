package me.rerere.rikkahub.data.datastore

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class CredentialUnavailableException : IllegalStateException("Protected settings unavailable; stored data preserved")

/** Immutable encrypted records. Preferences commit only after a verified durable write. */
internal class CredentialVault(context: Context, private val keyAlias: String = ALIAS) {
    private val directory = File(context.noBackupFilesDir, "credentials-v1")

    fun protect(value: String, previous: String? = null): String {
        if (previous != null && isReference(previous) && resolve(previous) == value) return previous
        val reference = PREFIX + UUID.randomUUID()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(reference.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(directory.exists() || directory.mkdirs()) { "Cannot create credential storage" }
        val file = AtomicFile(record(reference))
        val stream = file.startWrite()
        try {
            stream.write(byteArrayOf(1, cipher.iv.size.toByte()))
            stream.write(cipher.iv)
            stream.write(encrypted)
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
        check(resolve(reference) == value) { "Credential verification failed" }
        return reference
    }

    fun resolve(value: String): String = try {
        decrypt(value)
    } catch (_: Exception) {
        throw CredentialUnavailableException()
    }

    private fun decrypt(value: String): String {
        if (!isReference(value)) return value // Legacy input, only before migration.
        val bytes = AtomicFile(record(value)).readFully()
        require(bytes.size > 2 && bytes[0] == 1.toByte()) { "Invalid credential record" }
        val ivSize = bytes[1].toInt() and 255
        require(ivSize == 12 && bytes.size >= 2 + ivSize + 16) { "Invalid credential record" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, bytes.copyOfRange(2, 2 + ivSize)))
        cipher.updateAAD(value.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(bytes.copyOfRange(2 + ivSize, bytes.size)).toString(Charsets.UTF_8)
    }

    private fun record(reference: String): File {
        val id = reference.removePrefix(PREFIX)
        require(UUID.fromString(id).toString() == id) { "Invalid credential reference" }
        return File(directory, "$id.bin")
    }

    /** Called only during explicit recovery; ordinary reads never delete or replace a key. */
    fun renewInvalidatedKey() = synchronized(KEY_LOCK) {
        try {
            Cipher.getInstance("AES/GCM/NoPadding").init(Cipher.ENCRYPT_MODE, key(create = true))
        } catch (_: android.security.keystore.KeyPermanentlyInvalidatedException) {
            replaceInvalidatedKey()
        } catch (_: java.security.UnrecoverableKeyException) {
            replaceInvalidatedKey()
        }
    }

    private fun replaceInvalidatedKey() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(keyAlias) }
        key(create = true)
    }

    private fun key(create: Boolean): SecretKey = synchronized(KEY_LOCK) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey) ?: run {
            check(create) { "Credential key unavailable" }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build())
            }.generateKey()
        }
    }

    companion object {
        private const val PREFIX = "credential-v1:"
        private const val ALIAS = "mathstudy.credentials.v1"
        private val KEY_LOCK = Any()
        fun isReference(value: String) = value.startsWith(PREFIX)
    }
}
