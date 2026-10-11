package me.rerere.rikkahub.data.files

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore

class SkillManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val renameTree: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
    private val removeTree: (File) -> Boolean = ::deleteSkillTree,
) {
    companion object {
        private const val TAG = "SkillManager"
    }

    private val builtinLock = Any()

    @Volatile
    private var builtinExtracted = false

    fun getSkillsDir(): File {
        val dir = context.filesDir.resolve(FileFolders.SKILLS)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getBuiltinSkillsDir(): File {
        val dir = context.filesDir.resolve(FileFolders.BUILTIN_SKILLS)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 确保内置技能已从 assets 解压到 [getBuiltinSkillsDir]，每个进程只检查一次。
     */
    fun ensureBuiltinSkillsExtracted() {
        if (builtinExtracted) return
        synchronized(builtinLock) {
            if (builtinExtracted) return
            runCatching {
                BuiltinSkills.extractIfNeeded(context, getBuiltinSkillsDir())
            }.onFailure {
                Log.w(TAG, "ensureBuiltinSkillsExtracted: Failed to extract builtin skills", it)
            }
            builtinExtracted = true
        }
    }

    /**
     * 列出所有可用技能：用户技能 + 内置技能，同名时用户技能覆盖内置技能。
     */
    fun listSkills(): List<SkillMetadata> = mergeWithBuiltinSkills(
        local = listSkillsIn(getSkillsDir(), builtin = false),
        builtin = listBuiltinSkills(),
    )

    fun findSkill(name: String): SkillMetadata? = listSkills().firstOrNull { it.name == name }

    private fun listBuiltinSkills(): List<SkillMetadata> {
        ensureBuiltinSkillsExtracted()
        return listSkillsIn(getBuiltinSkillsDir(), builtin = true)
    }

    private fun listSkillsIn(root: File, builtin: Boolean): List<SkillMetadata> {
        return root.listFiles()
            // 跳过隐藏目录，如原子写入残留的 .<name>.staging.N.tmp
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.mapNotNull { dir ->
                val safeDir = SkillPaths.resolveSkillDir(root, dir.name) ?: return@mapNotNull null
                val skillFile = SkillPaths.resolveSkillFile(safeDir, "SKILL.md") ?: return@mapNotNull null
                if (!skillFile.exists()) return@mapNotNull null
                parseSkillFile(skillFile, safeDir, builtin)
            }
            ?: emptyList()
    }

    fun readSkillBody(skillName: String): String? {
        val skillFile = resolveSkillFile(skillName, "SKILL.md") ?: return null
        if (!skillFile.exists()) return null
        return SkillFrontmatterParser.extractBody(skillFile.inputStream().use { SkillImportLimits.read(it, SkillImportLimits.MARKDOWN_BYTES) }.toString(Charsets.UTF_8))
    }

    fun readSkillContent(skillName: String): String? {
        val skillFile = resolveSkillFile(skillName, "SKILL.md") ?: return null
        if (!skillFile.exists()) return null
        return skillFile.inputStream().use { SkillImportLimits.read(it, SkillImportLimits.MARKDOWN_BYTES) }.toString(Charsets.UTF_8)
    }

    fun saveSkill(name: String, content: String): SkillMetadata? {
        // 通过原子写入(staging + rename)落盘，避免直接 mkdirs 失败时
        // writeText 抛出 FileNotFoundException 导致崩溃
        if (!saveSkillFileBytesAtomically(name, mapOf("SKILL.md" to content.toByteArray()))) {
            return null
        }
        val skillDir = resolveSkillDir(name) ?: return null
        return parseSkillFile(skillDir.resolve("SKILL.md"), skillDir)
    }

    suspend fun deleteSkill(name: String): Boolean = withContext(Dispatchers.IO) {
        val skillDir = resolveSkillDir(name) ?: return@withContext false
        // 目录不存在时 deleteRecursively 也返回 true，需提前拦截，避免误清理内置技能的启用状态
        if (!skillDir.exists()) return@withContext false
        // Keep a complete rollback tree before a deletion can partially consume files.
        val root = getSkillsDir()
        val backup = createTempSkillDir(root, name, "uninstall-backup") ?: return@withContext false
        if (!copySkillTree(skillDir, backup)) {
            removeTree(backup)
            return@withContext false
        }
        val trash = createTempSkillDir(root, name, "uninstall") ?: run {
            removeTree(backup)
            return@withContext false
        }
        if (!renameTree(skillDir, trash)) {
            removeTree(trash); removeTree(backup)
            return@withContext false
        }
        var committed = false
        try {
            if (!removeTree(trash)) {
                restoreBackup(backup, skillDir)
                return@withContext false
            }
            // An overriding user skill can be removed without disabling its builtin.
            if (listBuiltinSkills().none { it.name == name }) {
                settingsStore.update { settings ->
                    settings.copy(assistants = settings.assistants.map { assistant ->
                        assistant.copy(enabledSkills = assistant.enabledSkills - name)
                    })
                }
            }
            committed = true
            true
        } catch (error: Exception) {
            restoreBackup(backup, skillDir)
            Log.w(TAG, "Skill uninstall rolled back")
            if (error is kotlinx.coroutines.CancellationException) throw error
            false
        } finally {
            if (committed) removeTree(backup)
            if (skillDir.exists()) removeTree(trash)
        }
    }

    /**
     * 清理所有助手 enabledSkills 中已不存在于磁盘的技能名。
     *
     * 当用户在 App 外直接删除 /skills/ 目录下的技能时，不会走 [deleteSkill] 的清理逻辑，
     * 导致 enabledSkills 残留"幽灵"技能名，使扩展入口角标计数偏大。
     */
    suspend fun pruneOrphanedEnabledSkills(): List<SkillMetadata> = withContext(Dispatchers.IO) {
        val skills = listSkills()
        val existing = skills.mapTo(HashSet()) { it.name }
        settingsStore.update { settings ->
            var changed = false
            val newAssistants = settings.assistants.map { assistant ->
                val pruned = assistant.enabledSkills.filterTo(LinkedHashSet()) { it in existing }
                if (pruned.size != assistant.enabledSkills.size) {
                    changed = true
                    assistant.copy(enabledSkills = pruned)
                } else {
                    assistant
                }
            }
            if (changed) settings.copy(assistants = newAssistants) else settings
        }
        skills
    }

    fun getSkillDir(skillName: String): File? = resolveSkillDir(skillName)

    fun saveSkillFile(skillName: String, relativePath: String, content: String): Boolean {
        val skillDir = resolveSkillDir(skillName) ?: return false
        val target = SkillPaths.resolveSkillFile(skillDir, relativePath) ?: return false
        val parent = target.parentFile ?: return false
        // 先写同目录临时文件再 rename 覆盖，避免写到一半失败时损坏原文件；
        // IO 异常（如 mkdirs 失败导致 FileNotFoundException）转为返回 false，不向调用方抛出
        val tempFile = parent.resolve(".${target.name}.tmp")
        return try {
            if (!parent.exists() && !parent.mkdirs()) return false
            tempFile.writeText(content)
            tempFile.renameTo(target)
        } catch (e: Exception) {
            Log.w(TAG, "saveSkillFile: Failed to save $skillName/$relativePath", e)
            false
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    fun saveSkillFileBytesAtomically(skillName: String, files: Map<String, ByteArray>): Boolean {
        if (files.size > SkillImportLimits.ENTRIES || files.values.any { it.size > SkillImportLimits.FILE_BYTES } ||
            files.values.sumOf { it.size.toLong() } > SkillImportLimits.TOTAL_BYTES ||
            (files["SKILL.md"]?.size ?: 0) > SkillImportLimits.MARKDOWN_BYTES) return false
        if (runCatching { files.keys.forEach { SkillImportLimits.path(it) } }.isFailure) return false
        val skillsDir = getSkillsDir()
        val targetDir = resolveSkillDir(skillName) ?: return false
        val stagingDir = createTempSkillDir(skillsDir, skillName, "staging") ?: return false
        var backupDir: File? = null

        try {
            for ((relativePath, content) in files) {
                val target = SkillPaths.resolveSkillFile(stagingDir, relativePath) ?: return false
                target.parentFile?.mkdirs()
                target.writeBytes(content)
            }

            if (!stagingDir.resolve("SKILL.md").exists()) return false

            if (targetDir.exists()) {
                backupDir = createTempSkillDir(skillsDir, skillName, "backup") ?: return false
                if (!renameTree(targetDir, backupDir)) return false
            }

            if (!renameTree(stagingDir, targetDir)) {
                if (backupDir != null && !targetDir.exists()) {
                    restoreBackup(backupDir, targetDir)
                }
                return false
            }

            backupDir?.let(removeTree)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "saveSkillFilesAtomically: Failed to save $skillName", e)
            if (backupDir != null && !targetDir.exists()) {
                restoreBackup(backupDir, targetDir)
            }
            return false
        } finally {
            if (stagingDir.exists()) {
                removeTree(stagingDir)
            }
            if (backupDir?.exists() == true && targetDir.exists()) {
                removeTree(backupDir)
            }
        }
    }

    private fun restoreBackup(backup: File, target: File): Boolean {
        if (renameTree(backup, target)) return true
        // File.renameTo can fail without throwing. Use the NIO move as a second
        // rollback path; if storage refuses both, keep the only original copy.
        return runCatching {
            java.nio.file.Files.move(backup.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            true
        }.getOrElse {
            Log.w(TAG, "Skill rollback deferred; original backup retained")
            false
        }
    }

    fun deleteSkillFile(skillName: String, relativePath: String): Boolean {
        val skillDir = resolveSkillDir(skillName) ?: return false
        val target = SkillPaths.resolveSkillFile(skillDir, relativePath) ?: return false
        return target.delete()
    }

    fun resolveSkillFile(skillName: String, relativePath: String): File? {
        val skillDir = resolveSkillDir(skillName) ?: return null
        return SkillPaths.resolveSkillFile(skillDir, relativePath)
    }

    private fun resolveSkillDir(skillName: String): File? {
        return SkillPaths.resolveSkillDir(getSkillsDir(), skillName)
    }

    private fun createTempSkillDir(skillsRoot: File, skillName: String, suffix: String): File? {
        repeat(100) { attempt ->
            val candidate = skillsRoot.resolve(".$skillName.$suffix.$attempt.tmp")
            if (!candidate.exists() && candidate.mkdirs()) {
                return candidate
            }
        }
        return null
    }

    private fun parseSkillFile(skillFile: File, skillDir: File, builtin: Boolean = false): SkillMetadata? {
        return runCatching {
            val safeFile = SkillPaths.resolveSkillFile(skillDir, "SKILL.md") ?: return null
            if (skillFile.canonicalFile != safeFile) return null
            val content = safeFile.inputStream().use { SkillImportLimits.read(it, SkillImportLimits.MARKDOWN_BYTES) }.toString(Charsets.UTF_8)
            val frontmatter = SkillFrontmatterParser.parse(content)
            val name = frontmatter["name"]?.takeIf { it.isNotBlank() } ?: return null
            val description = frontmatter["description"]?.takeIf { it.isNotBlank() } ?: return null
            SkillMetadata(
                name = name,
                description = description,
                compatibility = frontmatter["compatibility"],
                license = frontmatter["license"],
                allowedTools = frontmatter["allowed-tools"],
                skillDir = skillDir,
                builtin = builtin,
            )
        }.getOrElse {
            Log.w(TAG, "parseSkillFile: Failed to parse ${skillFile.absolutePath}", it)
            null
        }
    }
}

data class SkillMetadata(
    val name: String,
    val description: String,
    val compatibility: String? = null,
    val license: String? = null,
    val allowedTools: String? = null,
    val skillDir: File,
    /** 内置技能，来自 assets 解压，只读 */
    val builtin: Boolean = false,
) {
    val skillFile: File get() = skillDir.resolve("SKILL.md")
}
