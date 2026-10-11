package me.rerere.rikkahub.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

@Serializable internal data class BackupEntry(val size: Long, val sha256: String)
@Serializable internal data class BackupManifest(
    val version: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val database: Boolean,
    val files: Boolean,
    val entries: Map<String, BackupEntry>,
    val sourceFilesDir: String = "",
)

internal object BackupIntegrity {
    const val manifestName = "manifest.json"
    const val maxEntries = 100_000
    const val maxEntryBytes = 2L * 1024 * 1024 * 1024
    fun describe(file: File): BackupEntry = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192); var size = 0L
        while (true) { val n = input.read(buffer); if (n < 0) break; size += n; digest.update(buffer, 0, n) }
        BackupEntry(size, hex(digest.digest()))
    }
    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    fun verify(archive: File): BackupManifest = ZipFile(archive).use { zip ->
        val entries = buildList {
            for (entry in zip.entries()) { require(size < maxEntries) { "备份条目过多" }; add(entry) }
        }
        require(entries.size <= maxEntries && entries.map { it.name }.toSet().size == entries.size) { "备份条目过多或重复" }
        val manifest = zip.getEntry(manifestName) ?: error("缺少备份完整性清单")
        require(manifest.size in 1..(8L * 1024 * 1024)) { "无效备份清单大小" }
        val info = zip.getInputStream(manifest).use { input ->
            val bytes = java.io.ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(8192); var total = 0
                while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= 8 * 1024 * 1024); output.write(buffer, 0, n) }
                output.toByteArray()
            }
            Json.decodeFromString<BackupManifest>(bytes.toString(Charsets.UTF_8))
        }
        require(info.version == 1 && info.entries.keys == entries.filter { it.name != manifestName }.map { it.name }.toSet()) { "备份版本或条目清单不匹配" }
        var total = 0L
        entries.filter { it.name != manifestName }.forEach { entry ->
            require(!entry.isDirectory)
            PendingRestore.resolveInside(File("backup-validation"), entry.name)
            val expected = info.entries.getValue(entry.name)
            require(expected.size in 0..maxEntryBytes && entry.size == expected.size) { "无效备份条目大小" }
            val digest = MessageDigest.getInstance("SHA-256")
            zip.getInputStream(entry).use { input ->
                val buffer = ByteArray(8192); var size = 0L
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    size += n; total += n
                    require(size <= expected.size && total <= EncryptedBackup.maxBytes) { "备份解压大小超出限制" }
                    digest.update(buffer, 0, n)
                }
                require(size == expected.size && hex(digest.digest()) == expected.sha256) { "备份内容校验失败" }
            }
        }
        info
    }
}
