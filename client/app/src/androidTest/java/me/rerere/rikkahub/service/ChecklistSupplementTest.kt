package me.rerere.rikkahub.service

import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.studychat.LearnerProfile
import me.rerere.rikkahub.ui.pages.history.HistoryVM
import me.rerere.rikkahub.ui.pages.chat.ChatVM
import me.rerere.rikkahub.utils.JsonInstant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.parameter.parametersOf
import java.io.File
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** Explicit host seed -> force-stop -> verify. No remote model or real-user file is touched. */
@RunWith(AndroidJUnit4::class)
class ChecklistSupplementTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val koin get() = GlobalContext.get()
    private fun session(service: ChatService, id: Uuid): ConversationSession {
        val manager = ChatService::class.java.getDeclaredField("sessionManager").apply { isAccessible = true }.get(service) as ConversationSessionManager
        return manager.getOrCreate(id)
    }
    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun trace(stage: String, data: String = "") {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
            putString("qa_stage", stage); putString("qa_snapshot", data); putString("qa_pid", android.os.Process.myPid().toString())
        })
    }
    private suspend fun register(store: SettingsStore, original: Settings, owner: String) {
        @Suppress("UNCHECKED_CAST")
        val dataStore = SettingsStore::class.java.getDeclaredField("dataStore").apply { isAccessible = true }.get(store) as androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
        dataStore.edit { it[SettingsStore.LEARNER_PROFILES] = JsonInstant.encodeToString(original.learnerProfiles + LearnerProfile(owner, "QA 复审补充", memoryEnabled = false)) }
        withTimeout(20000) { store.settingsFlow.first { it.learnerProfiles.any { p -> p.id == owner } } }
    }

    @Test fun controlledBoundariesSurviveIndependentProcess() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val fixture = requireNotNull(args.getString("fixtureId"))
        require(Regex("[a-f0-9-]{36}").matches(fixture))
        val phase = requireNotNull(args.getString("phase"))
        require(phase in listOf("seed", "verify", "cleanup"))
        val manifestFile = File(context.cacheDir, "checklist-$fixture.json")
        val manifest = if (phase == "seed") JSONObject().put("owner", Uuid.random().toString()).put("entries", JSONArray()).put("created", JSONArray()) else JSONObject(manifestFile.readText())
        val owner = manifest.getString("owner")
        val entries = manifest.getJSONArray("entries")
        val settings = koin.get<SettingsStore>()
        val original = withTimeout(20000) { settings.settingsFlow.first { !it.init } }
        val repo = koin.get<ConversationRepository>(); val service = koin.get<ChatService>(); val db = koin.get<AppDatabase>()
        suspend fun sql(statement: String) = db.withTransaction { db.openHelper.writableDatabase.execSQL(statement) }
        suspend fun create(label: String, nodes: List<MessageNode>): Conversation {
            val c = Conversation.ofId(Uuid.random(), original.assistantId).copy(profileId = owner, title = label, messageNodes = nodes)
            manifest.getJSONArray("created").put(c.id.toString()); manifestFile.writeText(manifest.toString())
            repo.insertConversation(c); service.addConversationReference(c.id); service.initializeConversation(c.id)
            return c
        }
        fun remember(label: String, c: Conversation, deleted: Boolean = false, file: File? = null) {
            entries.put(JSONObject().put("label", label).put("conversation", JsonInstant.encodeToString(c)).put("deleted", deleted).apply {
                file?.let { put("file", it.path); put("sha256", digest(it)) }
            })
            manifestFile.writeText(manifest.toString())
        }
        suspend fun failUpdates(id: Uuid, action: suspend () -> Unit) {
            // Distinct first/second write failures prove the pause error does not mask approval.
            sql("CREATE TRIGGER qa_checklist_fail BEFORE UPDATE ON ConversationEntity WHEN OLD.id='$id' BEGIN SELECT CASE WHEN NEW.queue_state != OLD.queue_state THEN RAISE(ABORT, 'QA_SECONDARY_PAUSE_FAILURE') ELSE RAISE(ABORT, 'QA_ORIGINAL_APPROVAL_FAILURE') END; END")
            try { action() } finally { sql("DROP TRIGGER qa_checklist_fail") }
        }
        try {
            register(settings, original, owner); service.switchLearnerProfile(owner)
            if (phase == "seed") {
                // An explicit result gate is outside the metadata mutex, like model generation.
                for (metadata in listOf("title", "suggestions")) for (change in listOf("stream", "edit", "branch")) {
                    val old = create("QA_${fixture}_${metadata}_$change", listOf(UIMessage.assistant("old body").toMessageNode()))
                    val s = session(service, old.id); val token = s.backgroundToken()
                    val read = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                    val result = async(start = CoroutineStart.UNDISPATCHED) {
                        val expected = s.state.value; read.complete(Unit)
                        // Cancellation-independent completion tests the final-state guard.
                        withContext(NonCancellable) { release.await() }
                        s.applyBackgroundMetadata(expected, token, title = if (metadata == "title") "late title" else null,
                            suggestions = if (metadata == "suggestions") listOf("late suggestion") else null) {
                            repo.updateGeneratedMetadata(expected, title = if (metadata == "title") "late title" else null,
                                suggestions = if (metadata == "suggestions") listOf("late suggestion") else null)
                        }
                    }
                    read.await(); trace("A01-read-$metadata-$change", JsonInstant.encodeToString(old))
                    val selected = old.currentMessages.single().copy(parts = listOf(UIMessagePart.Text("new $change body")))
                    if (change == "stream") {
                        s.checkpointGeneration(listOf(selected), repo::checkpointMessages)
                        s.recordGenerationState("COMPLETED", repo::checkpointMessages)
                    } else {
                        val node = if (change == "branch") old.messageNodes.single().copy(messages = listOf(old.currentMessages.single(), selected.copy(id = Uuid.random())), selectIndex = 1)
                            else old.messageNodes.single().copy(messages = listOf(selected))
                        service.saveConversation(old.id, old.copy(messageNodes = listOf(node)))
                    }
                    val expected = s.state.value; release.complete(Unit); assertFalse(result.await())
                    assertEquals(expected.messageNodes, service.getConversationFlow(old.id).value.messageNodes)
                    assertEquals(expected.messageNodes, repo.getConversationById(old.id, owner)!!.messageNodes)
                    assertEquals(old.title, repo.getConversationById(old.id, owner)!!.title)
                    assertTrue(repo.getConversationById(old.id, owner)!!.chatSuggestions.isEmpty())
                    trace("A01-released-$metadata-$change", JsonInstant.encodeToString(expected)); remember("A01-$metadata-$change", expected)
                }
                for (entry in listOf("history", "chat")) {
                    val untouched = create("QA_${fixture}_untouched_$entry", listOf(UIMessage.user("unrelated synthetic message").toMessageNode()))
                    val old = create("QA_${fixture}_deleted_$entry", listOf(UIMessage.user("QA_${fixture}_search_$entry").toMessageNode()))
                    val s = session(service, old.id); val token = s.backgroundToken()
                    val release = CompletableDeferred<Unit>(); val read = CompletableDeferred<Unit>()
                    val result = CompletableDeferred<Boolean>()
                    val background = launch(start = CoroutineStart.UNDISPATCHED) {
                        val expected = s.state.value; read.complete(Unit)
                        withContext(NonCancellable) {
                            release.await()
                            result.complete(s.applyBackgroundMetadata(expected, token, suggestions = listOf("late")) { repo.updateGeneratedMetadata(expected, suggestions = listOf("late")) })
                        }
                    }
                    read.await()
                    if (entry == "history") HistoryVM(repo, settings, service).deleteConversation(old)
                    else withContext(Dispatchers.Main) { koin.get<ChatVM>(parameters = { parametersOf(old.id.toString()) }).deleteConversation(old) }.join()
                    withTimeout(20000) { while (repo.getConversationById(old.id, owner) != null) yield() }
                    background.cancel(); release.complete(Unit); assertFalse(result.await()); background.join(); assertNull(repo.getConversationById(old.id, owner))
                    assertTrue(repo.searchMessages("QA_${fixture}_search_$entry", profileId = owner).none { it.conversationId == old.id.toString() })
                    assertTrue(runCatching { service.saveConversation(old.id, old) }.isFailure)
                    assertTrue(koin.get<me.rerere.rikkahub.feature.memory.LearningMemoryStore>().read(owner).sources.none { it.conversationId == old.id.toString() && it.valid })
                    assertEquals(untouched.messageNodes, repo.getConversationById(untouched.id, owner)!!.messageNodes)
                    remember("A02-unrelated-$entry", untouched)
                    remember("A02-$entry", old, deleted = true); trace("A02-late-rejected-$entry")
                }
                for (decision in listOf("approve", "deny", "answer")) {
                    val unrelated = create("QA_${fixture}_active_sibling_$decision", listOf(UIMessage.user("synthetic other active task").toMessageNode()))
                    val unrelatedStored = repo.getConversationById(unrelated.id, owner)!!
                    val siblingJob = launch { awaitCancellation() }
                    session(service, unrelated.id).setJob(siblingJob)
                    try {
                    val tools = listOf(UIMessagePart.Tool("target", "ask_user", "{}", approvalState = ToolApprovalState.Pending), UIMessagePart.Tool("blocker", "ask_user", "{}", approvalState = ToolApprovalState.Pending))
                    val initialPending = create("QA_${fixture}_approval_$decision", listOf(UIMessage(role = MessageRole.ASSISTANT, parts = tools).toMessageNode()))
                    session(service, initialPending.id).updateQueue(repo::commitRuntimeState) { it.enqueue(listOf(UIMessagePart.Text("synthetic queued $decision"))) }
                    val pending = repo.getConversationById(initialPending.id, owner)!!
                    failUpdates(pending.id) {
                        service.handleToolApproval(pending.id, "target", decision != "deny", "synthetic deny", if (decision == "answer") "synthetic answer" else null)
                        withTimeout(20000) { session(service, pending.id).getJob()?.join() }
                        assertTrue(service.getConversationFlow(pending.id).value.currentMessages.single().getTools().first().isPending)
                        assertTrue(repo.getConversationById(pending.id, owner)!!.currentMessages.single().getTools().first().isPending)
                        assertTrue(service.errors.value.any { it.conversationId == pending.id })
                        val error = service.errors.value.last { it.conversationId == pending.id }.error
                        assertTrue(generateSequence(error) { it.cause }.any { it.message?.contains("QA_ORIGINAL_APPROVAL_FAILURE") == true })
                        assertFalse(generateSequence(error) { it.cause }.any { it.message?.contains("QA_SECONDARY_PAUSE_FAILURE") == true })
                        assertTrue(session(service, pending.id).messageQueue.state.value.paused)
                        assertEquals(pending.queue, repo.getConversationById(pending.id, owner)!!.queue)
                        assertEquals(pending.queue.messages, session(service, pending.id).messageQueue.persisted().messages)
                    }
                    remember("A03-$decision", pending); trace("A03-failed-still-pending-$decision")
                    val initialRetry = create("QA_${fixture}_same_process_$decision", pending.messageNodes.map { node -> node.copy(id = Uuid.random(), messages = node.messages.map { it.copy(id = Uuid.random()) }) })
                    session(service, initialRetry.id).updateQueue(repo::commitRuntimeState) { it.enqueue(listOf(UIMessagePart.Text("synthetic same process queue"))) }
                    val retry = repo.getConversationById(initialRetry.id, owner)!!
                    failUpdates(retry.id) {
                        service.handleToolApproval(retry.id, "target", decision != "deny", "synthetic deny", if (decision == "answer") "synthetic answer" else null)
                        withTimeout(20000) { session(service, retry.id).getJob()?.join() }
                        assertTrue(repo.getConversationById(retry.id, owner)!!.currentMessages.single().getTools().first().isPending)
                        assertTrue(service.errors.value.any { it.conversationId == retry.id })
                        assertTrue(session(service, retry.id).messageQueue.state.value.paused)
                    }
                    service.handleToolApproval(retry.id, "target", decision != "deny", "synthetic deny", if (decision == "answer") "synthetic answer" else null)
                    // Same, conflicting and late decisions queue behind the first accepted decision.
                    service.handleToolApproval(retry.id, "target", decision != "deny", "synthetic deny", if (decision == "answer") "synthetic answer" else null)
                    service.handleToolApproval(retry.id, "target", decision == "deny", "late conflict")
                    withTimeout(20000) { session(service, retry.id).getJob()?.join() }
                    val accepted = repo.getConversationById(retry.id, owner)!!
                    assertEquals(when (decision) { "approve" -> ToolApprovalState.Approved; "deny" -> ToolApprovalState.Denied("synthetic deny"); else -> ToolApprovalState.Answered("synthetic answer") }, accepted.currentMessages.single().getTools().first().approvalState)
                    service.handleToolApproval(retry.id, "target", decision == "deny", "late conflict")
                    assertEquals(accepted.messageNodes, repo.getConversationById(retry.id, owner)!!.messageNodes)
                    assertTrue(accepted.currentMessages.single().getTools().last().isPending)
                    assertEquals(retry.queue.messages, accepted.queue.messages)
                    assertTrue(accepted.queue.paused)
                    assertSame(siblingJob, session(service, unrelated.id).getJob())
                    assertTrue(siblingJob.isActive)
                    assertEquals(unrelatedStored, repo.getConversationById(unrelated.id, owner))
                    // Execute only this accepted target through the real loop, maxSteps=1.
                    // The sibling Pending tool remains in Room. No provider request is needed.
                    val model = Model(modelId = "qa-no-network")
                    val config = original.copy(providers = listOf(me.rerere.ai.provider.ProviderSetting.OpenAI(
                        models = listOf(model), baseUrl = "http://127.0.0.1:1", apiKey = "synthetic")))
                    val calls = java.util.concurrent.atomic.AtomicInteger()
                    val tool = me.rerere.ai.core.Tool("ask_user", "synthetic counted tool", execute = { calls.incrementAndGet(); emptyList() })
                    val targetMessages = accepted.currentMessages.map { message -> message.copy(parts = message.parts.filterNot {
                        it is UIMessagePart.Tool && it.toolCallId == "blocker"
                    }) }
                    var completedTools: List<UIMessagePart.Tool> = emptyList()
                    koin.get<me.rerere.rikkahub.data.ai.GenerationLoop>().generateText(config, model, targetMessages,
                        assistant = Assistant(localTools = emptyList()), tools = listOf(tool), maxSteps = 1).collect { chunk ->
                        when (chunk) {
                            is me.rerere.rikkahub.data.ai.GenerationChunk.Messages -> completedTools = chunk.messages.last().getTools()
                            is me.rerere.rikkahub.data.ai.GenerationChunk.DurableCheckpoint -> chunk.committed.complete(Unit)
                        }
                    }
                    assertEquals(if (decision == "approve") 1 else 0, calls.get())
                    assertTrue(completedTools.single().isExecuted)
                    assertEquals(accepted, repo.getConversationById(retry.id, owner))
                    trace("A03-exact-tool-count-$decision", calls.get().toString())
                    remember("same-process-$decision", accepted); trace("A03-same-process-retry-$decision")
                    } finally { siblingJob.cancelAndJoin(); session(service, unrelated.id).setJob(null) }
                }
                val file = File(context.filesDir, "upload/qa-checklist-$fixture.txt").apply { parentFile!!.mkdirs(); writeText("synthetic unchanged attachment") }
                val part = UIMessagePart.Document(file.toUri().toString(), file.name, "text/plain")
                val attached = create("QA_${fixture}_attachment", listOf(UIMessage.user("attachment").copy(parts = listOf(part)).toMessageNode()))
                val before = digest(file)
                failUpdates(attached.id) { assertTrue(runCatching { service.saveConversation(attached.id, attached.copy(messageNodes = emptyList())) }.isFailure) }
                assertEquals(before, digest(file)); assertEquals(attached.messageNodes, service.getConversationFlow(attached.id).value.messageNodes)
                remember("A04-failed-attachment", attached, file = file); trace("A04-file-preserved", before)
                // Other alternatives inside the same node retain a file even when not selected.
                val branchFile = File(context.filesDir, "upload/qa-branch-$fixture.txt").apply { writeText("synthetic branch bytes") }
                val branchPart = UIMessagePart.Document(branchFile.toUri().toString(), branchFile.name, "text/plain")
                val branch = create("QA_${fixture}_branch_reference", listOf(MessageNode(messages = listOf(UIMessage.user("chosen").copy(parts = listOf(branchPart)), UIMessage.user("other").copy(parts = listOf(branchPart))))))
                val retained = branch.messageNodes.single().copy(messages = listOf(branch.currentMessages.single().copy(parts = listOf(UIMessagePart.Text("selected attachment removed"))), branch.messageNodes.single().messages.last()))
                service.saveConversation(branch.id, branch.copy(messageNodes = listOf(retained))); assertTrue(branchFile.exists())
                service.saveConversation(branch.id, branch.copy(messageNodes = listOf(UIMessage.user("no reference").toMessageNode())))
                assertFalse(branchFile.exists()); remember("A04-branch-final-cleaned", repo.getConversationById(branch.id, owner)!!)
                val queueFile = File(context.filesDir, "upload/qa-queue-$fixture.txt").apply { writeText("synthetic queue bytes") }
                val queuePart = UIMessagePart.Document(queueFile.toUri().toString(), queueFile.name, "text/plain")
                val queued = create("QA_${fixture}_queue_reference", listOf(UIMessage.user("attached").copy(parts = listOf(queuePart)).toMessageNode()))
                val qs = session(service, queued.id)
                qs.updateQueue(repo::commitRuntimeState) { it.enqueue(listOf(queuePart)); it.pause() }
                service.saveConversation(queued.id, queued.copy(messageNodes = emptyList())); assertTrue(queueFile.exists())
                val queueId = qs.messageQueue.state.value.messages.single().id
                qs.updateQueue(repo::commitRuntimeState) { it.remove(queueId) }
                service.saveConversation(queued.id, queued); service.saveConversation(queued.id, queued.copy(messageNodes = emptyList()))
                assertFalse(queueFile.exists()); remember("A04-queue-final-cleaned", repo.getConversationById(queued.id, owner)!!)
                val materials = koin.get<me.rerere.rikkahub.feature.material.MaterialFiles>()
                val privateItem = materials.import(owner, file.toUri(), "QA-private.txt")
                val privateFile = materials.verifiedFile(owner, privateItem.id); val privateHash = digest(privateFile)
                val privateChat = create("QA_${fixture}_private_material", listOf(UIMessage.user("private material").copy(parts = listOf(UIMessagePart.Document(privateFile.toUri().toString(), "QA-private.txt", "text/plain"))).toMessageNode()))
                service.saveConversation(privateChat.id, privateChat.copy(messageNodes = emptyList()))
                assertEquals(privateHash, digest(materials.verifiedFile(owner, privateItem.id)))
                remember("A04-private-library-retained", repo.getConversationById(privateChat.id, owner)!!, file = privateFile)
                service.clearAllErrors()
                trace("seed-completed", manifest.toString())
            } else if (phase == "verify") {
                for (index in 0 until entries.length()) {
                    val record = entries.getJSONObject(index)
                    val expected = JsonInstant.decodeFromString<Conversation>(record.getString("conversation"))
                    val stored = repo.getConversationById(expected.id, owner)
                    if (record.getBoolean("deleted")) { assertNull(stored); trace("reopen-deleted-absent", record.getString("label")); continue }
                    assertNotNull(stored); assertEquals(expected.messageNodes, stored!!.messageNodes); assertEquals(expected.title, stored.title)
                    service.addConversationReference(expected.id); service.initializeConversation(expected.id)
                    assertEquals(expected.messageNodes, service.getConversationFlow(expected.id).value.messageNodes)
                    if (record.has("file")) assertEquals(record.getString("sha256"), digest(File(record.getString("file"))))
                    if (record.getString("label").startsWith("A03")) {
                        assertEquals(expected.queue.messages, stored.queue.messages)
                        assertEquals(expected.queue.messages, session(service, expected.id).messageQueue.persisted().messages)
                        assertTrue(session(service, expected.id).messageQueue.state.value.paused)
                        assertTrue(repo.getConversationById(expected.id, owner)!!.queue.paused)
                        val decision = record.getString("label").substringAfter("A03-")
                        service.handleToolApproval(expected.id, "target", decision != "deny", "synthetic deny", if (decision == "answer") "synthetic answer" else null)
                        withTimeout(20000) { session(service, expected.id).getJob()?.join() }
                        val accepted = repo.getConversationById(expected.id, owner)!!.currentMessages.single().getTools().first().approvalState
                        assertEquals(when (decision) { "approve" -> ToolApprovalState.Approved; "deny" -> ToolApprovalState.Denied("synthetic deny"); else -> ToolApprovalState.Answered("synthetic answer") }, accepted)
                        service.handleToolApproval(expected.id, "target", decision == "deny", "late conflict")
                        assertEquals(accepted, repo.getConversationById(expected.id, owner)!!.currentMessages.single().getTools().first().approvalState)
                        assertTrue(repo.getConversationById(expected.id, owner)!!.currentMessages.single().getTools().last().isPending)
                    }
                    trace("reopen-verified", record.getString("label"))
                }
                trace("verify-completed")
            }
        } finally {
            sql("DROP TRIGGER IF EXISTS qa_checklist_fail")
            if (phase != "seed") {
                // Exact IDs written before each synthetic insertion; never enumerate/delete a profile.
                val ids = manifest.optJSONArray("created") ?: JSONArray((0 until entries.length()).map { JsonInstant.decodeFromString<Conversation>(entries.getJSONObject(it).getString("conversation")).id.toString() })
                for (index in 0 until ids.length()) {
                    val id = Uuid.parse(ids.getString(index))
                    repo.getConversationById(id, owner)?.let { c ->
                        require(c.profileId == owner && c.title.startsWith("QA_${fixture}_"))
                        service.deleteConversation(c)
                    }
                    service.removeConversationReference(id)
                }
                for (index in 0 until entries.length()) {
                    val record = entries.getJSONObject(index)
                    if (record.getString("label") == "A04-private-library-retained") {
                        val file = File(record.getString("file"))
                        require(file.canonicalFile.parentFile == File(context.filesDir, "materials/$owner").canonicalFile)
                        require(Regex("[a-f0-9-]{36}\\.txt").matches(file.name))
                        file.delete(); File(file.parentFile, "${file.nameWithoutExtension}.meta.json").delete()
                    }
                }
            }
            val createdIds = manifest.getJSONArray("created")
            val ids = (0 until createdIds.length()).map { Uuid.parse(createdIds.getString(it)) }.toSet()
            service.errors.value.filter { it.conversationId in ids }.forEach { service.dismissError(it.id) }
            restoreMemoryProfileFixture(original, settings)
        }
    }

    @Test fun skillsInstallWorksWhileAuthoringAndEditingAreDisabled() = runBlocking {
        val manager = koin.get<SkillManager>()
        val name = "qa-install-${Uuid.random()}"
        val source = File(context.cacheDir, "$name.md")
        val directory = File(manager.getSkillsDir(), name)
        require(directory.canonicalFile.parentFile == manager.getSkillsDir().canonicalFile)
        val content = "---\nname: $name\ndescription: synthetic installation\n---\nInstalled instructions."
        try {
            source.writeText(content)
            val vm = me.rerere.rikkahub.ui.pages.extensions.skills.SkillsVM(manager)
            val result = CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main) { vm.importSkillFromFile(context, source.toUri()) { success, _ -> result.complete(success) } }
            assertTrue(withTimeout(20000) { result.await() })
            assertEquals(content, manager.readSkillContent(name))
            var authored: Boolean? = null
            vm.saveSkill(name, "replacement") { authored = it }
            assertEquals(false, authored)
            val detail = me.rerere.rikkahub.ui.pages.extensions.skills.SkillDetailVM(manager)
            detail.init(name)
            assertTrue(detail.readOnly.value)
            val saved = CompletableDeferred<String?>()
            detail.saveFile("SKILL.md", "replacement") { saved.complete(it) }
            assertNotNull(withTimeout(20000) { saved.await() })
            assertEquals(content, manager.readSkillContent(name))
            trace("skills-install-readonly-passed", name)
        } finally {
            source.delete()
            File(directory, "SKILL.md").delete()
            directory.delete()
        }
    }

    @Test fun installedSkillsCanBeUsedOnlyWhenEnabled() = runBlocking {
        val settings = withTimeout(20000) { koin.get<SettingsStore>().settingsFlow.first { !it.init } }
        val manager = koin.get<SkillManager>()
        val name = "qa-checklist-${Uuid.random()}"
        val directory = File(manager.getSkillsDir(), name)
        require(directory.canonicalFile.parentFile == manager.getSkillsDir().canonicalFile)
        directory.mkdir(); val source = File(directory, "SKILL.md")
        try {
            source.writeText("---\nname: $name\ndescription: synthetic QA boundary\n---\nSynthetic instructions only.")
            val assistant = Assistant(name = "QA unopened Skills", enabledSkills = setOf(name), localTools = emptyList(), enableWebSearch = false, enableRecentChatsReference = false)
            val tools = koin.get<ChatToolFactory>().createTools(settings, assistant, Model(), profileId = settings.activeProfileId)
            trace("D07-synthetic-tools", tools.map { it.name }.joinToString(","))
            assertTrue("Installed and enabled skill is available", tools.any { it.name == "use_skill" })
            val disabled = koin.get<ChatToolFactory>().createTools(settings, assistant.copy(enabledSkills = emptySet()), Model(), profileId = settings.activeProfileId)
            assertFalse("Installed skill is not enabled implicitly", disabled.any { it.name == "use_skill" })
        } finally { source.delete(); directory.delete() }
    }
}
