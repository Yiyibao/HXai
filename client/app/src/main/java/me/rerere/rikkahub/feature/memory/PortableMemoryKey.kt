package me.rerere.rikkahub.feature.memory

import android.content.Context
import me.rerere.rikkahub.data.datastore.CredentialVault
import me.rerere.rikkahub.data.sync.PendingRestore
import java.io.File
import java.security.SecureRandom
import java.util.Base64

/** Exportable random HMAC key, protected by device Keystore at rest and the backup password in transit. */
internal class PortableMemoryKey(private val context: Context) {
    private val vault = CredentialVault(context)
    private val file get() = File(context.filesDir, FILE_NAME)
    fun existing(): String? = synchronized(lock) {
        if (file.isFile) vault.resolve(file.readText()) else null
    }
    fun get(): String = synchronized(lock) {
        existing() ?: Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }).also {
            PendingRestore.writeDurably(file, protectedReference(it))
        }
    }
    fun protectedReference(secret: String): String {
        require(Base64.getDecoder().decode(secret).size == 32) { "无效记忆保护密钥" }
        return vault.protect(secret)
    }
    companion object {
        const val FILE_NAME = "memory-protection.ref"
        private val lock = Any()
    }
}
