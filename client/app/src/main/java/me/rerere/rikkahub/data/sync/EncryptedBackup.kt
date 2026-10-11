package me.rerere.rikkahub.data.sync

import java.io.*
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Versioned, chunk-authenticated container. Bounded memory even on providers buffering GCM decrypt. */
internal object EncryptedBackup {
    private val magic = "MSABAK01".toByteArray(Charsets.US_ASCII)
    private const val iterations = 600_000
    private const val chunkSize = 256 * 1024
    const val maxBytes = 8L * 1024 * 1024 * 1024

    fun encrypt(source: File, target: File, password: CharArray, checkActive: () -> Unit = {}) {
        require(password.size in 12..1024) { "备份密码需为 12–1024 个字符" }
        require(source.length() in 1..maxBytes) { "备份大小超出支持范围" }
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(8).also(random::nextBytes)
        val header = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use {
            it.write(magic); it.writeInt(iterations); it.write(salt); it.write(nonce); it.writeLong(source.length())
        } }.toByteArray()
        val key = derive(password, salt)
        try {
            FileOutputStream(target).use { file ->
                val out = DataOutputStream(file)
                out.write(header)
                source.inputStream().use { input ->
                    val buffer = ByteArray(chunkSize)
                    var remaining = source.length(); var index = 0
                    while (remaining > 0) {
                        checkActive()
                        val size = minOf(remaining, chunkSize.toLong()).toInt()
                        DataInputStream(input).readFully(buffer, 0, size)
                        val cipher = cipher(Cipher.ENCRYPT_MODE, key, nonce, index++, header)
                        out.write(cipher.doFinal(buffer, 0, size)); remaining -= size
                    }
                    check(input.read() == -1) { "备份快照在加密期间改变" }
                }
                out.flush(); file.fd.sync()
            }
        } catch (error: Throwable) { target.delete(); throw error }
        finally { key.fill(0) }
    }

    /** Target is private staging only; callers must not use it until every tag and EOF verifies. */
    fun decrypt(source: File, target: File, password: CharArray, checkActive: () -> Unit = {}) {
        require(password.size in 12..1024) { "请输入备份密码（至少 12 个字符）" }
        try {
            DataInputStream(source.inputStream().buffered()).use { input ->
                val header = ByteArray(44); input.readFully(header)
                val info = DataInputStream(header.inputStream())
                val signature = ByteArray(8); info.readFully(signature)
                require(signature.contentEquals(magic) && info.readInt() == iterations) { "不支持的加密备份格式" }
                val salt = ByteArray(16); info.readFully(salt)
                val nonce = ByteArray(8); info.readFully(nonce)
                var remaining = info.readLong()
                require(remaining in 1..maxBytes) { "无效备份大小" }
                val key = derive(password, salt)
                try {
                    FileOutputStream(target).use { output ->
                        var index = 0
                        while (remaining > 0) {
                            checkActive()
                            val size = minOf(remaining, chunkSize.toLong()).toInt()
                            val encrypted = ByteArray(size + 16); input.readFully(encrypted)
                            output.write(cipher(Cipher.DECRYPT_MODE, key, nonce, index++, header).doFinal(encrypted))
                            remaining -= size
                        }
                        require(input.read() == -1) { "备份含额外数据" }
                        output.fd.sync()
                    }
                } finally { key.fill(0) }
            }
        } catch (error: Exception) {
            target.delete()
            if (error is java.util.concurrent.CancellationException) throw error
            throw IllegalArgumentException("密码错误、备份损坏或格式不受支持；原数据未改变", error)
        }
    }

    private fun derive(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, index: Int, header: ByteArray): Cipher {
        val iv = java.nio.ByteBuffer.allocate(12).put(nonce).putInt(index).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)); updateAAD(header)
        }
    }
}
