package me.rerere.rikkahub.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files

@Serializable private data class RestoreInputSeal(val version: Int = 1, val entries: Map<String, BackupEntry>)

/** Binds the normalized/rebased prepared inputs. Never re-hash corruption as a new valid input. */
internal object RestoreStagingIntegrity {
    private const val NAME = "prepared-inputs-v1.json"
    private fun inventory(staging: File): Map<String, BackupEntry> {
        require(staging.isDirectory && !Files.isSymbolicLink(staging.toPath()))
        val root = staging.canonicalFile
        val result = linkedMapOf<String, BackupEntry>()
        for (name in listOf("settings.json", "replace-folders.json")) {
            val file = PendingRestore.resolveInside(root, name)
            require(file.isFile && !Files.isSymbolicLink(file.toPath())) { "恢复准备文件缺失：$name" }
            result[name] = BackupIntegrity.describe(file)
        }
        val payload = PendingRestore.resolveInside(root, "payload")
        require(payload.isDirectory)
        payload.walkTopDown().forEach { file ->
            require(!Files.isSymbolicLink(file.toPath())) { "恢复准备目录中不允许符号链接" }
            if (file.isFile) {
                val relative = file.relativeTo(root).invariantSeparatorsPath
                PendingRestore.resolveInside(root, relative)
                require(file.length() <= BackupIntegrity.maxEntryBytes) { "恢复文件超过安全限额" }
                result[relative] = BackupIntegrity.describe(file)
                require(result.size < BackupIntegrity.maxEntries)
            }
        }
        require("payload/database/${me.rerere.rikkahub.data.db.SQLiteConfiguration.DATABASE_NAME}" in result) {
            "恢复准备数据库缺失"
        }
        return result
    }
    fun seal(staging: File, json: Json) {
        PendingRestore.writeDurably(File(staging, NAME), json.encodeToString(RestoreInputSeal(entries = inventory(staging))))
    }
    fun verify(staging: File, json: Json) {
        val file = File(staging, NAME)
        require(file.isFile && file.length() <= 16L * 1024 * 1024 && !Files.isSymbolicLink(file.toPath())) {
            "恢复准备清单缺失，请从完整加密备份重新准备；当前数据未修改"
        }
        val expected = json.decodeFromString<RestoreInputSeal>(file.readText())
        require(expected.version == 1 && expected.entries == inventory(staging)) {
            "恢复预览后的文件缺失或改变；当前数据未修改，请重新准备备份"
        }
    }
}
