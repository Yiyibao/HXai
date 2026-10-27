package me.rerere.rikkahub.feature.material

import android.content.Context
import android.content.ContextWrapper
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.sync.BackupManager
import me.rerere.rikkahub.data.sync.EncryptedBackup
import me.rerere.rikkahub.service.withIsolatedRestoreSettings
import me.rerere.rikkahub.service.restoreAppDictionary
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipFile

class MaterialLibraryLifecycleRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.also { check(it.packageName == "org.hxsi.agent.nativeqa") }
    private suspend fun fixture(store: MaterialFiles, owner: String, body: String = "# Chapter 1\nORIGINAL TEXT\n") =
        store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "教材.md", "md")) { it.writeText(body); "Synthetic original" }
    private fun clean(owner: String) { File(context.filesDir, "materials/$owner").deleteRecursively(); File(context.cacheDir, "material-evidence/$owner").deleteRecursively() }

    @Test fun authorizedSafTreeCopiesOnlySelectedScopeAndReportsIndividualFailures(): Unit = runBlocking {
        val owner = UUID.randomUUID().toString(); val store = MaterialFiles(context)
        val root = File(context.filesDir, "library-saf-fixture").apply { mkdirs() }
        val selected = File(root, "selected").apply { mkdirs() }
        File(selected, "same.md").writeText("TOP ORIGINAL")
        File(selected, "nested").mkdirs(); File(selected, "nested/same.md").writeText("NESTED ORIGINAL")
        File(selected, "bad.pdf").writeText("NOT A PDF")
        File(root, "outside").mkdirs(); File(root, "outside/private.md").writeText("OUTSIDE_SENTINEL")
        val tree = DocumentsContract.buildTreeDocumentUri("org.hxsi.agent.nativeqa.library-fixture", "selected")
        try {
            val first = MaterialDirectoryImport(context, store).import(owner, tree) {}
            assertEquals(true, first["enumerationComplete"])
            assertEquals(1, (first["failures"] as List<*>).size)
            val files = store.list(owner).filter { it.state == "SUCCEEDED" }
            assertEquals(2, files.size)
            assertEquals(setOf("", "nested"), files.map { it.folder }.toSet())
            assertEquals(setOf("TOP ORIGINAL", "NESTED ORIGINAL"), files.map { store.verifiedFile(owner, it.id).readText() }.toSet())
            assertTrue(runCatching { context.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, "outside/private.md"))?.use { it.read() } }.isFailure)
            val second = MaterialDirectoryImport(context, store).import(owner, tree) {}
            assertEquals(2, (second["importedIds"] as List<*>).size)
            assertEquals(4, MaterialFiles(context).list(owner).count { it.state == "SUCCEEDED" })
            assertTrue(store.list(owner).none { it.name.contains("private") })
        } finally { clean(owner); root.deleteRecursively() }
    }

    @Test fun organizationSurvivesReopenAndDeletionBlocksAllSourcesAndLatePublish(): Unit = runBlocking {
        val owner = UUID.randomUUID().toString(); val other = UUID.randomUUID().toString(); val store = MaterialFiles(context)
        try {
            val item = fixture(store, owner)
            store.organize(owner, item.id, item.sha256, "数学/教材", listOf("微积分", "复习"))
            val reopened = MaterialFiles(context)
            assertEquals("数学/教材", reopened.get(owner, item.id).folder)
            assertEquals(listOf("微积分", "复习"), reopened.get(owner, item.id).tags)
            assertTrue(runCatching { store.organize(other, item.id, item.sha256, "x", emptyList()) }.isFailure)
            assertTrue(runCatching { store.organize(owner, item.id, item.sha256, "../escape", emptyList()) }.isFailure)
            store.chapterIndex(owner, item.id, item.sha256, 0, 20)
            assertTrue(File(context.filesDir, "materials/$owner/${item.id}.chapters-v1.cache").isFile)
            val read = runCatching { store.readContentUsing(owner, item.id) { frozen, extension ->
                reopened.delete(owner, item.id, item.sha256)
                MaterialReader.read(frozen, extension)
            } }
            assertTrue(read.isFailure)
            assertTrue(store.list(owner).isEmpty())
            assertEquals(item.sha256, store.trash(owner).single().sha256)
            assertTrue(runCatching { store.record(item) }.isFailure)
            assertTrue(runCatching { store.readSourceTextPage(owner, item.id, item.sha256, 0, 100) }.isFailure)
            assertFalse(File(context.filesDir, "materials/$owner/${item.id}.chapters-v1.cache").exists())
            val search = store.searchLibraryText(owner, "ORIGINAL", "", 20, 5)
            assertEquals(emptyList<JsonElement>(), search.getValue("hits").jsonArray.toList())
            assertEquals(item.sha256, MaterialFiles.digest(store.file(item)))
            val restored = store.restore(owner, item.id)
            val repeated = MaterialFiles(context).restore(owner, item.id)
            assertEquals(restored.id, repeated.id)
            assertNotEquals(item.id, restored.id)
            assertEquals(item.id, restored.sourceId)
            assertEquals(item.sha256, MaterialFiles.digest(store.verifiedFile(owner, restored.id)))
            assertTrue(store.trash(owner).any { it.id == item.id })
            store.purge(owner, item.id)
            assertFalse(store.file(item).exists())
            assertTrue(store.trash(owner).any { it.id == item.id })
            assertEquals(item.sha256, MaterialFiles.digest(store.verifiedFile(owner, restored.id)))
        } finally { clean(owner); clean(other) }
    }

    @Test fun importRejectsWrongFormatAndRevokedReadWithoutLosingValidOriginals(): Unit = runBlocking {
        val owner = UUID.randomUUID().toString(); val store = MaterialFiles(context)
        val input = File.createTempFile("b04-import-", ".md", context.cacheDir)
        try {
            input.writeText("normal UTF-8 教材 x²")
            val good = store.import(owner, input.toUri(), "original.md")
            assertEquals("normal UTF-8 教材 x²", store.verifiedFile(owner, good.id).readText())
            assertTrue(runCatching { store.import(owner, input.toUri(), "fake.pdf") }.isFailure)
            input.writeBytes(ByteArray(32768) { 65 })
            var checks = 0
            assertTrue(runCatching { store.import(owner, input.toUri(), "revoked.md", stillAllowed = { check(++checks < 4) { "Synthetic revoked access" } }) }.isFailure)
            assertEquals(listOf(good.id), store.list(owner).filter { it.state == "SUCCEEDED" }.map { it.id })
            assertEquals(good.sha256, MaterialFiles.digest(store.verifiedFile(owner, good.id)))
            assertTrue(File(context.filesDir, "materials/$owner").listFiles().orEmpty().none { it.extension == "part" })
        } finally { input.delete(); clean(owner) }
    }

    @Test fun oldEncryptedBackupCannotReviveMaterialDeletedBeforeOrAfterConfirmation(): Unit = runBlocking {
        for (after in listOf(false, true)) {
            val directory = Files.createTempDirectory(context.cacheDir.toPath(), "b04-backup-").toFile()
            val isolated = object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
                override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
                override fun getNoBackupFilesDir() = File(directory, "no-backup").apply { mkdirs() }
                override fun getDatabasePath(name: String) = if (File(name).isAbsolute) File(name) else File(directory, "databases/$name").also { it.parentFile!!.mkdirs() }
            }
            var db = AppDatabaseFactory.create(isolated)
            try {
                val settings = GlobalContext.get().get<SettingsStore>()
                val owner = settings.settingsFlowRaw.first { !it.init }.activeProfileId
                val store = MaterialFiles(isolated)
                val item = fixture(store, owner)
                val manager = BackupManager(isolated, db, settings, JsonInstant)
                val password = "B04 synthetic backup password!".toCharArray()
                val old = manager.createEncryptedBackup(password)
                val preview = manager.prepareEncryptedRestore(old, password)
                if (after) manager.confirmRestore(preview)
                store.delete(owner, item.id, item.sha256)
                val fresh = manager.createEncryptedBackup(password)
                val plain = File(directory, "fresh.zip")
                EncryptedBackup.decrypt(fresh, plain, password)
                ZipFile(plain).use { zip ->
                    assertNull(zip.getEntry("materials/$owner/${item.id}.md"))
                    val metadata = zip.getInputStream(zip.getEntry("materials/$owner/${item.id}.meta.json")).reader().readText()
                    assertFalse(metadata.contains("ORIGINAL TEXT")); assertFalse(metadata.contains("教材"))
                }
                if (!after) manager.confirmRestore(preview)
                db.close()
                withIsolatedRestoreSettings(File(directory, "restore.preferences_pb")) { assertTrue(BackupManager.applyPendingRestore(isolated, JsonInstant)) }
                db = AppDatabaseFactory.create(isolated)
                val restored = MaterialFiles(isolated)
                assertTrue(restored.list(owner).isEmpty())
                assertEquals(item.id, restored.trash(owner).single().id)
                assertFalse(restored.file(item).exists())
                assertTrue(runCatching { restored.verifiedFile(owner, item.id) }.isFailure)
            } finally { db.close(); restoreAppDictionary(); directory.deleteRecursively() }
        }
    }
    @Test fun lateWriterKeepsUserOrganizationAndCannotPublishAfterDeletion(): Unit = runBlocking {
        for (delete in listOf(false, true)) {
            val owner = UUID.randomUUID().toString(); val store = MaterialFiles(context)
            val item = MaterialFile(UUID.randomUUID().toString(), owner, "in-progress.md", "md")
            try {
                val result = runCatching { store.runTask(item) { target ->
                    target.writeText("IMMUTABLE NEW SOURCE")
                    store.organize(owner, item.id, "", "用户/正在写入", listOf("保留用户标记"))
                    if (delete) store.delete(owner, item.id, "")
                    "Synthetic completed output"
                } }
                if (delete) {
                    assertTrue(result.isFailure)
                    assertTrue(store.list(owner).isEmpty())
                    assertNotNull(store.trash(owner).single().deletedAt)
                } else {
                    val actual = result.getOrThrow()
                    assertEquals("用户/正在写入", actual.folder)
                    assertEquals(listOf("保留用户标记"), actual.tags)
                    assertEquals("IMMUTABLE NEW SOURCE", store.verifiedFile(owner, actual.id).readText())
                }
            } finally { clean(owner) }
        }
    }
    @Test fun restoreAfterDeletingARestoreCreatesANewIdentityAndConcurrentAckDoesNotDuplicate(): Unit = runBlocking {
        val owner = UUID.randomUUID().toString(); val store = MaterialFiles(context)
        try {
            val source = fixture(store, owner); store.delete(owner, source.id, source.sha256)
            val first = coroutineScope { listOf(async { store.restore(owner, source.id) }, async { store.restore(owner, source.id) }).awaitAll() }
            assertEquals(first[0].id, first[1].id)
            store.delete(owner, first[0].id, first[0].sha256)
            val next = store.restore(owner, source.id)
            assertNotEquals(first[0].id, next.id)
            assertEquals(next.id, store.restore(owner, source.id).id)
            assertEquals(setOf(source.id, first[0].id), store.trash(owner).map { it.id }.toSet())
            assertEquals(source.sha256, MaterialFiles.digest(store.verifiedFile(owner, next.id)))
        } finally { clean(owner) }
    }

    @Test fun libraryPagingKeepsUnicodeAndFailedCacheCleanupDoesNotUndoDeletion(): Unit = runBlocking {
        val owner = UUID.randomUUID().toString(); val store = MaterialFiles(context)
        try {
            val body = "a".repeat(11999) + "😀" + "b"
            val item = fixture(store, owner, body)
            val first = store.readLibraryTextPage(owner, item.id, item.sha256, 0, 12000)
            assertEquals(11999, first.getValue("next_offset").jsonPrimitive.int)
            val second = store.readLibraryTextPage(owner, item.id, item.sha256, 11999, 12000)
            assertEquals(body, first.getValue("untrusted_file_data").jsonPrimitive.content + second.getValue("untrusted_file_data").jsonPrimitive.content)
            val blocked = File(context.filesDir, "materials/$owner/${item.id}.read-v6.cache").apply { mkdirs() }
            File(blocked, "derived").writeText("synthetic unremovable nonempty directory")
            assertTrue(runCatching { store.delete(owner, item.id, item.sha256) }.isFailure)
            assertTrue(store.list(owner).isEmpty())
            assertTrue(store.trash(owner).any { it.id == item.id })
            assertTrue(runCatching { store.readContent(owner, item.id) }.isFailure)
            assertEquals(item.sha256, MaterialFiles.digest(store.file(item)))
        } finally { clean(owner) }
    }
    @Test fun anotherStoreDoesNotRecoverActiveWritingOrReading(): Unit = runBlocking {
        val owner = UUID.randomUUID().toString(); val store = MaterialFiles(context)
        try {
            val writing = MaterialFile(UUID.randomUUID().toString(), owner, "active.md", "md")
            val saved = store.runTask(writing) { target ->
                target.writeText("ACTIVE ORIGINAL")
                val other = MaterialFiles(context)
                assertEquals("RUNNING", other.get(owner, writing.id).state)
                assertTrue(target.isFile)
                other.organize(owner, writing.id, "", "正在写入", listOf("保留"))
                "finished"
            }
            store.readContentUsing(owner, saved.id) { file, extension ->
                val other = MaterialFiles(context)
                assertEquals("RUNNING", other.get(owner, saved.id).processingState)
                other.organize(owner, saved.id, saved.sha256, "正在读取", listOf("保留"))
                MaterialReader.read(file, extension)
            }
            assertEquals("正在读取", store.get(owner, saved.id).folder)
            assertEquals(listOf("保留"), store.get(owner, saved.id).tags)
            assertEquals("SUCCEEDED", store.get(owner, saved.id).processingState)
            assertEquals("ACTIVE ORIGINAL", store.verifiedFile(owner, saved.id).readText())
        } finally { clean(owner) }
    }

    @Test fun deletedProfileIdentitySurvivesBackupWithoutIncludingItsLiveMaterial(): Unit = runBlocking {
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "b04-orphan-deletion-").toFile()
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(directory, "no-backup").apply { mkdirs() }
            override fun getDatabasePath(name: String) = if (File(name).isAbsolute) File(name) else File(directory, "databases/$name").also { it.parentFile!!.mkdirs() }
        }
        val db = AppDatabaseFactory.create(isolated)
        try {
            val settings = GlobalContext.get().get<SettingsStore>()
            // A historical restore can remove a later profile from settings; its deletion identity must remain portable.
            val removedOwner = UUID.randomUUID().toString()
            assertTrue(settings.settingsFlowRaw.first { !it.init }.learnerProfiles.none { it.id == removedOwner })
            val store = MaterialFiles(isolated)
            val deleted = fixture(store, removedOwner, "DELETED PRIVATE BODY")
            val live = fixture(store, removedOwner, "ABSENT PROFILE PRIVATE BODY")
            store.delete(removedOwner, deleted.id, deleted.sha256)
            val manager = BackupManager(isolated, db, settings, JsonInstant)
            val password = "B04 synthetic orphan-deletion password!".toCharArray()
            val archive = manager.createEncryptedBackup(password)
            val plain = File(directory, "readback.zip")
            EncryptedBackup.decrypt(archive, plain, password)
            ZipFile(plain).use { zip ->
                val metadata = zip.getEntry("materials/$removedOwner/${deleted.id}.meta.json")
                assertNotNull("Deletion identity was dropped with the absent profile", metadata)
                assertFalse(zip.getInputStream(metadata).reader().readText().contains("教材"))
                assertNull(zip.getEntry("materials/$removedOwner/${deleted.id}.md"))
                assertNull(zip.getEntry("materials/$removedOwner/${live.id}.meta.json"))
                assertNull(zip.getEntry("materials/$removedOwner/${live.id}.md"))
            }
            val preview = manager.prepareEncryptedRestore(archive, password)
            assertTrue(File(preview.staging, "payload/files/materials/$removedOwner/${deleted.id}.meta.json").isFile)
            assertTrue(JsonInstant.decodeFromString<me.rerere.rikkahub.data.datastore.Settings>(File(preview.staging, "settings.json").readText()).learnerProfiles.none { it.id == removedOwner })
            manager.discardRestore(preview)
        } finally { db.close(); restoreAppDictionary(); directory.deleteRecursively() }
    }
}
