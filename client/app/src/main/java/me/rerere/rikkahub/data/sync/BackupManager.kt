package me.rerere.rikkahub.data.sync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.withoutCredentials
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import me.rerere.rikkahub.data.files.FileFolders
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Shared archive format and restore lifecycle for local, WebDAV and S3 backups. */
class BackupManager(
    private val context: Context,
    private val database: AppDatabase,
    private val settingsStore: SettingsStore,
    private val json: Json,
) {
    private val restoreMutex = Mutex()
    private val fingerprints = me.rerere.rikkahub.feature.memory.MemoryFingerprints(context)
    private val memories = me.rerere.rikkahub.feature.memory.LearningMemoryStore(database, fingerprints)
    private val memoryKey = me.rerere.rikkahub.feature.memory.PortableMemoryKey(context)

    suspend fun createEncryptedBackup(password: CharArray): File = withContext(Dispatchers.IO) {
        restoreMutex.withLock {
            memories.prepareBackup()
            val plain = buildBackup()
            val encrypted = File.createTempFile("mathstudy-", ".msab", context.cacheDir)
            try {
                BackupIntegrity.verify(plain)
                validateMaterials(plain)
                val operation = currentCoroutineContext()
                EncryptedBackup.encrypt(plain, encrypted, password) { operation.ensureActive() }
                encrypted
            } catch (error: Throwable) { encrypted.delete(); throw error }
            finally { plain.delete() }
        }
    }

    suspend fun prepareEncryptedRestore(archive: File, password: CharArray): RestorePreview = withContext(Dispatchers.IO) {
        val plain = File.createTempFile("restore-authenticated-", ".zip", context.cacheDir)
        try {
            val operation = currentCoroutineContext()
            EncryptedBackup.decrypt(archive, plain, password) { operation.ensureActive() }
            val manifest = BackupIntegrity.verify(plain)
            require(manifest.database && manifest.files) { "不是完整备份" }
            validateMaterials(plain)
            val staged = stageRestoreInternal(plain, manifest.sourceFilesDir)
            val settings = json.decodeFromString<Settings>(File(staged, "settings.json").readText())
            RestorePreview(staged, manifest.createdAt, settings.learnerProfiles.map { it.name }, manifest.entries.size)
        } finally { plain.delete() }
    }

    suspend fun confirmRestore(preview: RestorePreview) = withContext(Dispatchers.IO) {
        restoreMutex.withLock {
            require(preview.staging.parentFile?.canonicalFile == File(context.noBackupFilesDir, "backup-restore").canonicalFile && preview.staging.name.startsWith("preparing-"))
            BackupDeletionMerge.merge(context, json, preview.staging, database)
            File(preview.staging, "memory-key.json").delete()
            pendingRestore(context).publish(preview.staging)
        }
    }
    fun discardRestore(preview: RestorePreview) { preview.staging.deleteRecursively() }

    private fun validateMaterials(archive: File) {
        ZipFile(archive).use { zip ->
            val manifest = zip.getInputStream(zip.getEntry(BackupIntegrity.manifestName)).use { json.decodeFromString<BackupManifest>(it.reader().readText()) }
            manifest.entries.keys.filter { it.startsWith("materials/") && it.endsWith(".json") }.forEach { name ->
                if (!name.endsWith(".meta.json") && name.removeSuffix(".json") + ".meta.json" in manifest.entries) return@forEach
                require(manifest.entries.getValue(name).size <= 1024 * 1024) { "材料元数据过大" }
                val item = zip.getInputStream(zip.getEntry(name)).use { input ->
                    runCatching { json.decodeFromString<me.rerere.rikkahub.feature.material.MaterialFile>(input.reader().readText()) }.getOrNull()
                }
                if (item == null) { require(!name.endsWith(".meta.json")) { "材料元数据损坏" }; return@forEach }
                require(name in setOf("materials/${item.profileId}/${item.id}.json", "materials/${item.profileId}/${item.id}.meta.json")) { "材料档案归属不匹配" }
                if (item.state == "SUCCEEDED") {
                    val data = manifest.entries["materials/${item.profileId}/${item.id}.${item.extension}"]
                    require(data != null && data.size == item.size && data.sha256 == item.sha256) { "材料在备份期间改变或缺失，请等待处理完成后重试" }
                }
            }
            val profiles = zip.getInputStream(zip.getEntry("settings.json")).use {
                json.decodeFromString<Settings>(SettingsJsonMigrator.migrate(it.reader().readText())).learnerProfiles.map { profile -> profile.id }.toSet()
            }
            manifest.entries.keys.filter { it.startsWith("image_creation/") || it.startsWith("image_templates/") }.forEach { name ->
                require(isAttachment(name) && manifest.entries.getValue(name).size <= 1024 * 1024) { "图片元数据路径无效或过大" }
                val owner = name.split('/')[1]
                require(owner in profiles) { "图片档案不存在于备份" }
                val raw = zip.getInputStream(zip.getEntry(name)).use { it.reader().readText() }
                if (name.startsWith("image_templates/")) {
                    val template = json.decodeFromString<me.rerere.rikkahub.feature.imagecreation.ImageTemplate>(raw)
                    require(name == "image_templates/${template.profileId}/${template.id}.json" && template.profileId == owner) { "模板档案归属不匹配" }
                } else {
                    val task = json.decodeFromString<me.rerere.rikkahub.feature.imagecreation.ImageCreationTask>(raw)
                    require(name == "image_creation/${task.profileId}/${task.id}.json" && task.profileId == owner) { "图片任务档案归属不匹配" }
                    if (task.state == "SUCCEEDED") {
                        val metadata = task.artifactId?.let { zip.getEntry("materials/$owner/$it.meta.json") }
                        require(metadata != null) { "图片任务的成果元数据缺失" }
                        val artifact = zip.getInputStream(metadata).use { json.decodeFromString<me.rerere.rikkahub.feature.material.MaterialFile>(it.reader().readText()) }
                        require(artifact.profileId == owner && artifact.id == task.artifactId && artifact.output && artifact.state == "SUCCEEDED" && artifact.extension == "png") { "图片成果绑定无效" }
                    }
                }
            }
        }
    }

    private suspend fun buildBackup(): File = withContext(Dispatchers.IO) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val archive = File.createTempFile("backup_${timestamp}_", ".zip", context.cacheDir)
        val staging = Files.createTempDirectory(context.cacheDir.toPath(), "backup-").toFile()
        try {
            val settings = settingsStore.settingsFlowRaw.first { !it.init }
            val inventory = linkedMapOf<String, BackupEntry>()
            ZipOutputStream(FileOutputStream(archive)).use { zip ->
                val settingsFile = File(staging, "settings.json").apply { writeText(json.encodeToString(settings.withoutCredentials())) }
                inventory["settings.json"] = addFile(zip, settingsFile, "settings.json")
                val keyFile = File(staging, "memory-key.json").apply { writeText(fingerprints.exportKey()) }
                inventory["memory-key.json"] = addFile(zip, keyFile, "memory-key.json")
                val snapshot = File(staging, SQLiteConfiguration.DATABASE_NAME)
                DatabaseBackup.createSnapshot(database.openHelper.writableDatabase, snapshot)
                inventory[DatabaseBackup.ARCHIVE_DATABASE] = addFile(zip, snapshot, DatabaseBackup.ARCHIVE_DATABASE)
                for (folder in PendingRestore.managedFolders) {
                    val directory = File(context.filesDir, folder)
                    val files = if (folder == FileFolders.SKILLS || folder in profileFolders) directory.walkTopDown().asSequence()
                    else directory.listFiles().orEmpty().asSequence()
                    for (file in files.filter { it.isFile }) {
                        currentCoroutineContext().ensureActive()
                        val relative = file.relativeTo(directory).invariantSeparatorsPath
                        if (folder in profileFolders && !isProfileAttachment(folder, relative)) continue
                        if (folder in profileFolders && settings.learnerProfiles.none { it.id == relative.substringBefore('/') }) continue
                        PendingRestore.resolveInside(directory, relative)
                        inventory["$folder/$relative"] = addFile(zip, file, "$folder/$relative")
                    }
                }
                require(inventory.size < BackupIntegrity.maxEntries)
                zip.putNextEntry(ZipEntry(BackupIntegrity.manifestName))
                zip.write(json.encodeToString(BackupManifest(database = true, files = true, entries = inventory, sourceFilesDir = context.filesDir.absolutePath)).toByteArray())
                zip.closeEntry()
            }
            archive
        } catch (e: Throwable) {
            archive.delete()
            throw e
        } finally {
            staging.deleteRecursively()
        }
    }

    private suspend fun stageRestoreInternal(archive: File, sourceFilesDir: String): File =
        withContext(Dispatchers.IO) {
            restoreMutex.withLock {
                val restore = pendingRestore(context)
                val staging = restore.createStagingDirectory()
                var retained = false
                try {
                    val payload = File(staging, "payload")
                    val stagedDatabase = File(payload, "database/${SQLiteConfiguration.DATABASE_NAME}")
                    val stagedWal = File(stagedDatabase.path + "-wal")
                    val seen = mutableSetOf<String>()
                    var restoredEntries = 0
                    ZipFile(archive).use { zip ->
                        for (entry in zip.entries()) {
                            currentCoroutineContext().ensureActive()
                            if (entry.isDirectory) continue
                            val target = when (entry.name) {
                                "settings.json" -> File(staging, "settings.json")
                                "memory-key.json" -> File(staging, "memory-key.json")
                                DatabaseBackup.ARCHIVE_DATABASE -> stagedDatabase
                                DatabaseBackup.WAL -> stagedWal
                                DatabaseBackup.SHM -> null // Rebuilt by SQLite; never restore shared-memory state.
                                else -> if (isAttachment(entry.name)) {
                                    PendingRestore.resolveInside(File(payload, "files"), entry.name)
                                } else null
                            } ?: continue
                            require(seen.add(entry.name)) { "Duplicate backup entry: ${entry.name}" }
                            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) {
                                "Cannot create backup staging directory"
                            }
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(target).use { output ->
                                    input.copyTo(output)
                                    output.fd.sync()
                                }
                            }
                            restoredEntries++
                        }
                    }
                    require(restoredEntries > 0) { "No selected data found in the backup" }
                    require(!stagedWal.exists() || stagedDatabase.exists()) { "Backup WAL has no matching database" }
                    if (stagedDatabase.exists()) {
                        DatabaseBackup.normalize(context, stagedDatabase)
                        // Reject unsupported schemas before publishing; run supported old migrations on the copy.
                        val room = AppDatabaseFactory.create(context, stagedDatabase.absolutePath)
                        try {
                            room.learningMemoryDao().all().forEach {
                                val state = json.decodeFromString<me.rerere.rikkahub.feature.memory.MemoryState>(it.payload)
                                require(state.schemaVersion == 1 && state.fingerprintVersion == 1) { "不支持的记忆保护版本" }
                            }
                            for (id in room.conversationDao().getAllIds()) {
                                room.messageNodeDao().getNodesOfConversation(id).forEach { node ->
                                    val rebased = BackupPaths.rebase(json.parseToJsonElement(node.messages), sourceFilesDir, context.filesDir).toString()
                                    if (rebased != node.messages) room.messageNodeDao().update(node.copy(messages = rebased))
                                }
                                val conversation = room.conversationDao().getConversationById(id)!!
                                val queue = BackupPaths.rebase(json.parseToJsonElement(conversation.queueState), sourceFilesDir, context.filesDir).toString()
                                room.conversationDao().update(conversation.copy(queueState = queue))
                            }
                            DatabaseBackup.checkpoint(room.openHelper.writableDatabase)
                        } finally {
                            room.close()
                        }
                        DatabaseBackup.removeSidecars(stagedDatabase)
                    }

                    val settingsFile = File(staging, "settings.json")
                    if (settingsFile.exists()) {
                        val settings = json.decodeFromString<Settings>(SettingsJsonMigrator.migrate(settingsFile.readText()))
                        require(!settings.init) { "Backup contains uninitialized settings" }
                        // Persist the migrated value once, including generated IDs, for restart/retry consistency.
                        val portableSettings = BackupPaths.rebase(json.parseToJsonElement(json.encodeToString(settings.withoutCredentials())), sourceFilesDir, context.filesDir)
                        PendingRestore.writeDurably(settingsFile, portableSettings.toString())
                    }
                    val keyFile = File(staging, "memory-key.json")
                    require(keyFile.isFile && keyFile.length() <= 128 && settingsFile.isFile && stagedDatabase.isFile) { "完整备份缺少必需数据" }
                    val secret = keyFile.readText()
                    val reference = memoryKey.protectedReference(secret)
                    val keyTarget = File(payload, "files/${me.rerere.rikkahub.feature.memory.PortableMemoryKey.FILE_NAME}")
                    keyTarget.parentFile!!.mkdirs()
                    PendingRestore.writeDurably(keyTarget, reference)
                    PendingRestore.writeDurably(File(staging, "replace-folders.json"), json.encodeToString(PendingRestore.managedFolders.toList()))
                    currentCoroutineContext().ensureActive()
                    retained = true
                    staging
                } finally {
                    if (!retained) staging.deleteRecursively()
                }
            }
        }

    private fun isAttachment(name: String): Boolean {
        val folder = name.substringBefore('/')
        if (folder !in PendingRestore.managedFolders || '/' !in name) return false
        val relative = name.substringAfter('/')
        require(relative.isNotBlank()) { "Invalid backup attachment: $name" }
        require(if (folder in profileFolders) isProfileAttachment(folder, relative) else folder == FileFolders.SKILLS || '/' !in relative) { "Invalid backup attachment: $name" }
        return true
    }

    private fun isProfileAttachment(folder: String, relative: String): Boolean {
        if (folder == "materials") return isMaterialAttachment(relative)
        val parts = relative.split('/')
        return parts.size == 2 && me.rerere.rikkahub.feature.material.MaterialFiles.validProfile(parts[0]) &&
            parts[1].endsWith(".json") && runCatching { java.util.UUID.fromString(parts[1].removeSuffix(".json")) }.isSuccess
    }

    private fun isMaterialAttachment(relative: String): Boolean {
        val parts = relative.split('/')
        if (parts.size != 2) return false
        return me.rerere.rikkahub.feature.material.MaterialFiles.validProfile(parts[0]) && runCatching { java.util.UUID.fromString(parts[1].substringBefore('.')) }.isSuccess &&
            parts[1].substringAfter('.', "") in setOf("meta.json", "json", "md", "txt", "csv", "docx", "xlsx", "pptx", "pdf", "png", "jpg", "jpeg", "webp", "doc", "xls", "ppt")
    }

    private suspend fun addFile(zip: ZipOutputStream, file: File, name: String): BackupEntry {
        zip.putNextEntry(ZipEntry(name))
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var size = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer); if (n < 0) break
                size += n
                require(size <= BackupIntegrity.maxEntryBytes) { "备份单文件超出 2 GiB" }
                digest.update(buffer, 0, n); zip.write(buffer, 0, n)
            }
        }
        zip.closeEntry()
        return BackupEntry(size, BackupIntegrity.hex(digest.digest()))
    }

    companion object {
        private val profileFolders = setOf("materials", "image_creation", "image_templates")

        fun cleanupInterruptedWork(context: Context) {
            context.cacheDir.listFiles().orEmpty().filter { file ->
                listOf("backup-", "backup_", "restore-authenticated-", "selected-backup-", "mathstudy-").any(file.name::startsWith)
            }.forEach { it.deleteRecursively() }
        }
        private fun pendingRestore(context: Context) = PendingRestore(
            root = File(context.noBackupFilesDir, "backup-restore"),
            databaseFile = context.getDatabasePath(SQLiteConfiguration.DATABASE_NAME),
            filesDir = context.filesDir,
        )

        /** Must finish before Koin, Room, SettingsStore or any background consumers are initialized. */
        suspend fun applyPendingRestore(context: Context, json: Json): Boolean = withContext(Dispatchers.IO) {
            pendingRestore(context).apply(beforeInstall = { staging ->
                val live = AppDatabaseFactory.create(context, SQLiteConfiguration.DATABASE_NAME)
                try { BackupDeletionMerge.merge(context, json, staging, live) } finally { live.close() }
            }) { settingsJson ->
                SettingsStore.restoreBeforeInitialization(context, json.decodeFromString<Settings>(settingsJson))
            }
        }
    }
}
