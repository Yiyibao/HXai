package me.rerere.rikkahub.service

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.datastore.preferences.core.edit
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.feature.studychat.LearnerProfile
import me.rerere.rikkahub.feature.imagecreation.ImageCreation
import me.rerere.rikkahub.feature.imagecreation.ImageCreationTask
import me.rerere.rikkahub.feature.imagecreation.ImageTemplates
import me.rerere.rikkahub.feature.material.MaterialFile
import me.rerere.rikkahub.feature.material.MaterialFiles
import me.rerere.rikkahub.data.db.*
import me.rerere.rikkahub.data.sync.*
import me.rerere.rikkahub.feature.memory.*
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BackupReliabilityTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings get() = org.koin.core.context.GlobalContext.get().get<SettingsStore>()
    private fun sandbox(root: File): Context = object : ContextWrapper(app) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
        override fun getDatabasePath(name: String) = if (File(name).isAbsolute) File(name) else File(root, "database/$name").apply { parentFile!!.mkdirs() }
    }
    private fun manager(context: Context, db: AppDatabase) = BackupManager(context, db, settings, JsonInstant)
    private fun pending(context: Context) = PendingRestore(File(context.noBackupFilesDir, "backup-restore"), context.getDatabasePath(SQLiteConfiguration.DATABASE_NAME), context.filesDir)
    private suspend fun seed(context: Context, db: AppDatabase, profile: String): LearningMemoryStore {
        val fingerprints = MemoryFingerprints(context)
        val store = LearningMemoryStore(db, fingerprints)
        val attachment = File(context.filesDir, "upload/fixture.txt").apply { parentFile!!.mkdirs(); writeText("附件内容") }
        val user = me.rerere.ai.ui.UIMessage.user("喜欢反例").copy(parts = listOf(me.rerere.ai.ui.UIMessagePart.Text("喜欢反例"),
            me.rerere.ai.ui.UIMessagePart.Document(java.net.URI("file", "", attachment.absolutePath, null).toString(), "fixture.txt")))
        val chatId = UUID.randomUUID().toString(); val nodeId = UUID.randomUUID().toString()
        val queue = me.rerere.rikkahub.data.model.ConversationQueue(messages = listOf(me.rerere.rikkahub.data.model.PendingInput(kotlin.uuid.Uuid.random(), user.parts)))
        db.conversationDao().insert(me.rerere.rikkahub.data.db.entity.ConversationEntity(chatId, UUID.randomUUID().toString(), "QA 完整聊天", "[]", 1, 1, "[]", true,
            profileId = profile, queueState = JsonInstant.encodeToString(queue), isArchived = true))
        db.messageNodeDao().insert(me.rerere.rikkahub.data.db.entity.MessageNodeEntity(nodeId, chatId, 0,
            JsonInstant.encodeToString(listOf(user, me.rerere.ai.ui.UIMessage.user("另一分支"))), 0))
        val definition = """{"version":1,"title":"备份交互","components":[{"id":"amount","type":"slider","label":"人数","min":1,"max":10,"value":2}]}"""
        val revision = me.rerere.rikkahub.feature.interactive.InteractiveSurface.hash(definition)
        val input = kotlinx.serialization.json.buildJsonObject { put("definition", kotlinx.serialization.json.JsonPrimitive(definition)) }
        val tool = me.rerere.ai.ui.UIMessagePart.Tool("backup-surface", "render_interactive_ui", input.toString(), output = me.rerere.rikkahub.feature.interactive.interactiveUiTool().execute(input), executionState = me.rerere.ai.ui.ToolExecutionState.COMPLETED, metadata = kotlinx.serialization.json.buildJsonObject {
            put("interactive_state", kotlinx.serialization.json.buildJsonObject { put("version", kotlinx.serialization.json.JsonPrimitive(1)); put("revision", kotlinx.serialization.json.JsonPrimitive(revision)); put("values", kotlinx.serialization.json.buildJsonObject { put("amount", kotlinx.serialization.json.JsonPrimitive(7)) }) })
        })
        val assistant = me.rerere.ai.ui.UIMessage.assistant("").copy(parts = listOf(tool))
        db.messageNodeDao().insert(me.rerere.rikkahub.data.db.entity.MessageNodeEntity(UUID.randomUUID().toString(), chatId, 1, JsonInstant.encodeToString(listOf(assistant)), 0))
        val source = MemorySource("source", chatId, nodeId, user.id.toString(), fingerprints.of(profile, "content:${user.toText()}"), 1)
        val item = LearningMemory(id = "item", type = MemoryType.PREFERENCE, topicKey = "教学", factKey = "顺序", content = "喜欢反例",
            evidenceType = EvidenceType.SELF_REPORTED, sourceIds = listOf("source"), status = "ACTIVE", createdAt = 1, updatedAt = 1)
        db.learningMemoryDao().put(LearningMemoryStateEntity(profile, JsonInstant.encodeToString(MemoryState(fingerprintVersion = 1, sources = listOf(source), items = listOf(item)))))
        return store
    }

    private fun writePng(file: File, color: Int) {
        val bitmap = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            file.outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    @Test fun startupRestoreReplacesProfilesActiveSelectionAndMemoryFlagsDurably() = runBlocking {
        val root = File(app.cacheDir, "qa-restore-profiles-${UUID.randomUUID()}").apply { mkdirs() }
        val context = sandbox(root)
        val profiles = listOf(LearnerProfile(UUID.randomUUID().toString(), "恢复档案", false),
            LearnerProfile(UUID.randomUUID().toString(), "第二档案", true))
        val snapshot = Settings(activeProfileId = profiles.first().id, learnerProfiles = profiles)
        val file = File(root, "restored.preferences_pb")
        try {
            withIsolatedRestoreSettings(file) { store ->
                val unrelated = LearnerProfile(UUID.randomUUID().toString(), "目标安装旧档案", true)
                store.edit {
                    it[SettingsStore.ACTIVE_PROFILE] = unrelated.id
                    it[SettingsStore.LEARNER_PROFILES] = JsonInstant.encodeToString(listOf(unrelated))
                }
                SettingsStore.restoreBeforeInitialization(context, snapshot)
            }
            withIsolatedRestoreSettings(file) { store ->
                val preferences = store.data.first()
                assertEquals(snapshot.activeProfileId, preferences[SettingsStore.ACTIVE_PROFILE])
                val restored = JsonInstant.decodeFromString<List<LearnerProfile>>(
                    preferences[SettingsStore.LEARNER_PROFILES]!!)
                assertEquals(profiles, restored)
                assertFalse(restored.first().memoryEnabled)
                assertTrue(restored.last().memoryEnabled)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun encryptedSnapshotPortableKeyWrongPasswordAndPostConfirmationDeletion(): Unit = runBlocking {
        val root = File(app.cacheDir, "qa-backup-${UUID.randomUUID()}").apply { mkdirs() }
        val context = sandbox(File(root, "source"))
        val target = sandbox(File(root, "target"))
        var db = AppDatabaseFactory.create(context)
        var targetDb = AppDatabaseFactory.create(target)
        try {
            val snapshotSettings = settings.settingsFlowRaw.first { !it.init }
            val profile = snapshotSettings.activeProfileId
            val store = seed(context, db, profile)
            File(context.filesDir, "skills/nested").mkdirs()
            File(context.filesDir, "skills/nested/SKILL.md").writeText("backup fixture")
            val materials = me.rerere.rikkahub.feature.material.MaterialFiles(context)
            val material = me.rerere.rikkahub.feature.material.MaterialFile(UUID.randomUUID().toString(), profile, "成果.txt", "txt", output = true)
            val data = materials.file(material).apply { writeText("真实成果") }
            materials.record(material.copy(state = "SUCCEEDED", size = data.length(), sha256 = me.rerere.rikkahub.feature.material.MaterialFiles.digest(data)))
            val jsonMaterial = materials.runTask(me.rerere.rikkahub.feature.material.MaterialFile(UUID.randomUUID().toString(), profile, "raw.json", "json")) {
                it.writeText("{\"value\":\"数学\"}"); "JSON 文件 fixture"
            }
            assertEquals("{\"value\":\"数学\"}", materials.verifiedFile(profile, jsonMaterial.id).readText())
            val image = materials.runTask(MaterialFile(UUID.randomUUID().toString(), profile,
                "original.png", "png", output = true)) { writePng(it, android.graphics.Color.CYAN); "Root image fixture" }
            val creation = ImageCreation(context, settings, materials)
            val conversationId = db.conversationDao().getAllIds().single()
            creation.registerToolImage(profile, conversationId, image, "原始图片提示", "qa-image")
            val parent = creation.get(profile, image.id)
            val edited = materials.runTask(MaterialFile(UUID.randomUUID().toString(), profile,
                "edited.png", "png", output = true)) { writePng(it, android.graphics.Color.MAGENTA); "Edited image fixture" }
            val child = parent.copy(id = edited.id, artifactId = edited.id, prompt = "编辑图片提示",
                references = listOf(image.id), parentId = parent.id, rootId = parent.id, revision = 2,
                size = "1536x1024", quality = "high", createdAt = parent.createdAt + 1)
            PendingRestore.writeDurably(File(context.filesDir, "image_creation/$profile/${child.id}.json"),
                JsonInstant.encodeToString(child))
            val template = ImageTemplates(File(context.filesDir, "image_templates")).save(
                profile, null, "课堂示意", "为 {主题} 绘制清晰示意图", "1536x1024", "medium")
            val archive = manager(context, db).createEncryptedBackup("strong fixture password!".toCharArray())
            val before = db.learningMemoryDao().get(profile)
            try { manager(context, db).prepareEncryptedRestore(archive, "wrong fixture password!".toCharArray()); fail("wrong password accepted") }
            catch (_: IllegalArgumentException) { }
            assertEquals(before, db.learningMemoryDao().get(profile))
            assertFalse(File(context.noBackupFilesDir, "backup-restore/pending").exists())

            // Simulate a blank installation with an unrelated device-local portable secret.
            val unrelated = MemoryFingerprints(target).exportKey()
            assertNotEquals(MemoryFingerprints(context).exportKey(), unrelated)
            val staleId = UUID.randomUUID().toString()
            assertTrue(File(target.filesDir, "image_creation/$profile").mkdirs())
            PendingRestore.writeDurably(File(target.filesDir, "image_creation/$profile/$staleId.json"),
                JsonInstant.encodeToString(parent.copy(id = staleId, rootId = staleId,
                    artifactId = null, state = "INTERRUPTED")))
            ImageTemplates(File(target.filesDir, "image_templates")).save(
                profile, null, "目标旧模板", "不应保留 {主题}", "1024x1024", "low")
            val targetManager = manager(target, targetDb)
            val preview = targetManager.prepareEncryptedRestore(archive, "strong fixture password!".toCharArray())
            assertTrue(preview.entries >= 4)
            targetManager.confirmRestore(preview)
            targetDb.close()
            withIsolatedRestoreSettings(File(root, "target.preferences_pb")) { preferences ->
                assertTrue(BackupManager.applyPendingRestore(target, JsonInstant))
                val stored = preferences.data.first()
                assertEquals(snapshotSettings.activeProfileId, stored[SettingsStore.ACTIVE_PROFILE])
                assertEquals(snapshotSettings.learnerProfiles, JsonInstant.decodeFromString<List<LearnerProfile>>(
                    stored[SettingsStore.LEARNER_PROFILES]!!))
            }
            targetDb = AppDatabaseFactory.create(target)
            assertEquals("喜欢反例", LearningMemoryStore(targetDb, MemoryFingerprints(target)).read(profile).items.single().content)
            assertEquals(MemoryFingerprints(context).exportKey(), MemoryFingerprints(target).exportKey())
            val chat = targetDb.conversationDao().getConversationById(targetDb.conversationDao().getAllIds().single())!!
            assertEquals("QA 完整聊天", chat.title); assertEquals(profile, chat.profileId)
            assertTrue(chat.isPinned && chat.isArchived)
            val node = targetDb.messageNodeDao().getNodesOfConversation(chat.id).first { it.nodeIndex == 0 }
            val messages = JsonInstant.decodeFromString<List<me.rerere.ai.ui.UIMessage>>(node.messages)
            val originalNode = db.messageNodeDao().getNodesOfConversation(chat.id).first { it.nodeIndex == 0 }
            val originalMessages = JsonInstant.decodeFromString<List<me.rerere.ai.ui.UIMessage>>(originalNode.messages)
            assertEquals(2, messages.size); assertEquals(0, node.selectIndex); assertEquals(originalMessages.first().toText(), messages.first().toText())
            assertEquals(LearningMemoryStore(targetDb, MemoryFingerprints(target)).read(profile).sources.single().hash,
                MemoryFingerprints(target).of(profile, "content:${messages.first().toText()}"))
            val url = messages.first().parts.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Document>().single().url
            assertEquals(File(target.filesDir, "upload/fixture.txt").absolutePath, java.net.URI(url).path)
            assertEquals("附件内容", File(java.net.URI(url)).readText())
            val surfaceNode = targetDb.messageNodeDao().getNodesOfConversation(chat.id).first { it.nodeIndex == 1 }
            val savedSurface = JsonInstant.decodeFromString<List<me.rerere.ai.ui.UIMessage>>(surfaceNode.messages).single().parts.single() as me.rerere.ai.ui.UIMessagePart.Tool
            val state = kotlinx.serialization.json.Json.parseToJsonElement(savedSurface.metadata!!.getValue("interactive_state").toString()) as kotlinx.serialization.json.JsonObject
            val restoredRaw = (kotlinx.serialization.json.Json.parseToJsonElement(savedSurface.input) as kotlinx.serialization.json.JsonObject).getValue("definition") as kotlinx.serialization.json.JsonPrimitive
            val restoredDefinition = me.rerere.rikkahub.feature.interactive.InteractiveSurface.validate(restoredRaw.content)
            val values = state.getValue("values") as kotlinx.serialization.json.JsonObject
            me.rerere.rikkahub.feature.interactive.InteractiveSurface.validateState(restoredDefinition, values)
            assertTrue(values.toString().contains("\"amount\":7"))
            val queue = JsonInstant.decodeFromString<me.rerere.rikkahub.data.model.ConversationQueue>(chat.queueState)
            assertEquals(url, queue.messages.single().parts.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Document>().single().url)
            assertEquals("真实成果", me.rerere.rikkahub.feature.material.MaterialFiles(target).verifiedFile(profile, material.id).readText())
            assertEquals("{\"value\":\"数学\"}", me.rerere.rikkahub.feature.material.MaterialFiles(target).verifiedFile(profile, jsonMaterial.id).readText())
            assertEquals("backup fixture", File(target.filesDir, "skills/nested/SKILL.md").readText())
            val targetMaterials = MaterialFiles(target)
            val restoredImages = ImageCreation(target, settings, targetMaterials)
            assertEquals(setOf(parent.id, child.id), restoredImages.list(profile).map { it.id }.toSet())
            assertEquals(child, restoredImages.get(profile, child.id))
            assertEquals(image.sha256, MaterialFiles.digest(targetMaterials.verifiedFile(profile, image.id)))
            assertEquals(edited.sha256, MaterialFiles.digest(targetMaterials.verifiedFile(profile, edited.id)))
            assertEquals(listOf(template), ImageTemplates(File(target.filesDir, "image_templates")).list(profile))

            // New deletion after the user confirmed restoration must be merged again on startup.
            val sourceManager = manager(context, db)
            sourceManager.confirmRestore(sourceManager.prepareEncryptedRestore(archive, "strong fixture password!".toCharArray()))
            store.delete(profile, "item", 1)
            db.close()
            withIsolatedRestoreSettings(File(root, "source.preferences_pb")) {
                assertTrue(BackupManager.applyPendingRestore(context, JsonInstant))
            }
            db = AppDatabaseFactory.create(context)
            val restored = LearningMemoryStore(db, MemoryFingerprints(context)).read(profile)
            assertTrue(restored.items.isEmpty()); assertTrue(restored.suppressedSources.isNotEmpty())
            assertFalse(pending(context).apply { fail("restoration replayed") })
            archive.delete()
        } finally { db.close(); targetDb.close(); restoreAppDictionary(); root.deleteRecursively() }
    }

    @Test fun imageTemplateOwnershipMismatchRejectsTheCompleteBackup() = runBlocking {
        val root = File(app.cacheDir, "qa-backup-image-owner-${UUID.randomUUID()}").apply { mkdirs() }
        val context = sandbox(root)
        val db = AppDatabaseFactory.create(context)
        try {
            val profile = settings.settingsFlowRaw.first { !it.init }.activeProfileId
            val template = ImageTemplates(File(context.filesDir, "image_templates")).save(
                profile, null, "归属校验", "为 {主题} 绘图", "1024x1024", "low")
            PendingRestore.writeDurably(File(context.filesDir, "image_templates/$profile/${template.id}.json"),
                JsonInstant.encodeToString(template.copy(profileId = UUID.randomUUID().toString())))
            val failure = runCatching {
                manager(context, db).createEncryptedBackup("strong fixture password!".toCharArray())
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals("模板档案归属不匹配", failure?.message)
            assertFalse(File(context.noBackupFilesDir, "backup-restore/pending").exists())
        } finally { db.close(); restoreAppDictionary(); root.deleteRecursively() }
    }

    @Test fun legacyDeletedFactsArePreservedWithAutomaticExtractionBlocked() = runBlocking {
        val root = File(app.cacheDir, "qa-legacy-memory-${UUID.randomUUID()}").apply { mkdirs() }
        val context = sandbox(root); val db = AppDatabaseFactory.create(context)
        try {
            val fingerprints = MemoryFingerprints(context)
            val user = me.rerere.ai.ui.UIMessage.user("请记住我喜欢先看反例")
            val chatId = UUID.randomUUID().toString(); val nodeId = UUID.randomUUID().toString()
            db.conversationDao().insert(me.rerere.rikkahub.data.db.entity.ConversationEntity(chatId, UUID.randomUUID().toString(), "QA legacy", "[]", 1, 1, "[]", false, profileId = "legacy"))
            db.messageNodeDao().insert(me.rerere.rikkahub.data.db.entity.MessageNodeEntity(nodeId, chatId, 0, JsonInstant.encodeToString(listOf(user)), 0))
            val source = MemorySource(UUID.randomUUID().toString(), chatId, nodeId, user.id.toString(), fingerprints.legacyOf("legacy", "content:${user.toText()}"), 1)
            val state = MemoryState(sources = listOf(source), suppressedSources = setOf(fingerprints.legacyOf("legacy", "message:${user.id}")),
                suppressedFacts = setOf(fingerprints.legacyOf("legacy", "PREFERENCE:教学:顺序")))
            db.learningMemoryDao().put(LearningMemoryStateEntity("legacy", JsonInstant.encodeToString(state)))
            val store = LearningMemoryStore(db, fingerprints)
            val migrated = store.read("legacy")
            assertEquals(1, migrated.fingerprintVersion); assertTrue(migrated.recoveryBlocked)
            assertEquals(state.suppressedFacts, migrated.suppressedFacts)
            assertTrue(migrated.sources.single().valid)
            assertEquals(fingerprints.of("legacy", "content:${user.toText()}"), migrated.sources.single().hash)
            assertTrue(fingerprints.of("legacy", "message:${user.id}") in migrated.suppressedSources)
            assertNull(store.claim("legacy", true, System.currentTimeMillis()))
        } finally { db.close(); restoreAppDictionary(); root.deleteRecursively() }
    }

    @Test fun twentyRequestBudgetLeaseRecoveryAndCompactionSurviveReopen() = runBlocking {
        val root = File(app.cacheDir, "qa-budget-${UUID.randomUUID()}").apply { mkdirs() }
        val context = sandbox(root); var db = AppDatabaseFactory.create(context)
        try {
            val store = LearningMemoryStore(db, MemoryFingerprints(context))
            val now = System.currentTimeMillis()
            val jobs = (0..24).map { ExtractionJob(id = "job-$it", conversationId = "chat", sourceIds = listOf("s-$it"), rangeHash = "h", epoch = 0, expectedRevisions = emptyMap()) }
            db.learningMemoryDao().put(LearningMemoryStateEntity("budget", JsonInstant.encodeToString(MemoryState(fingerprintVersion = 1, jobs = jobs))))
            repeat(20) { assertNotNull(store.claim("budget", true, now)) }
            assertNull(store.claim("budget", true, now))
            db.close(); db = AppDatabaseFactory.create(context)
            val reopened = LearningMemoryStore(db, MemoryFingerprints(context))
            assertNull(reopened.claim("budget", true, now))
            assertNotNull(reopened.claim("budget", true, now + 86_400_000))
            val terminal = (0..349).map { jobs.first().copy(id = "finished-$it", sourceIds = listOf("done-$it"), state = "SUCCEEDED") }
            db.learningMemoryDao().put(LearningMemoryStateEntity("compact", JsonInstant.encodeToString(MemoryState(fingerprintVersion = 1, jobs = terminal))))
            reopened.change("compact") { it to Unit }
            assertEquals(200, reopened.read("compact").jobs.size)
            assertEquals(150, reopened.read("compact").processedSources.size)
        } finally { db.close(); restoreAppDictionary(); root.deleteRecursively() }
    }
}
