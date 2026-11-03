package me.rerere.rikkahub.data.repository

import android.database.sqlite.SQLiteBlobTooBigException
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.map
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.db.fts.MessageSearchSort
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import java.time.Instant
import kotlin.uuid.Uuid

class ConversationRepository(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val favoriteDAO: FavoriteDAO,
    private val database: AppDatabase,
    private val filesManager: FilesManager,
    private val messageFtsManager: MessageFtsManager,
    private val settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore,
    private val learningMemories: me.rerere.rikkahub.feature.memory.LearningMemoryStore,
) {
    val activeProfileId get() = settingsStore.settingsFlow.value.activeProfileId
    val profileFlow = settingsStore.settingsFlow.map { it.activeProfileId }

    companion object {
        private const val PAGE_SIZE = 20
        private const val INITIAL_LOAD_SIZE = 40
    }

    suspend fun requireConversationAccess(id: Uuid, profileId: String = activeProfileId) {
        val marker = conversationDAO.deletion(id.toString())
        require(marker == null || (!marker.deleted && marker.profileId == profileId)) { "Conversation deleted or access denied" }
        val owner = conversationDAO.getConversationById(id.toString())?.profileId
        require(owner == null || owner == profileId) { "Conversation access denied" }
    }

    suspend fun hasFileReference(fileUrl: String): Boolean =
        messageNodeDAO.hasFileReference(JsonInstant.encodeToString(fileUrl)) ||
            conversationDAO.hasQueuedFileReference(JsonInstant.encodeToString(fileUrl))

    suspend fun getRecentConversations(assistantId: Uuid, limit: Int = 10, profileId: String = activeProfileId, archived: Boolean = false): List<Conversation> {
        return conversationDAO.getRecentConversationsOfAssistant(
            assistantId = assistantId.toString(),
            limit = limit,
            profileId = profileId,
            archived = archived,
        ).map { entity ->
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes)
        }
    }

    fun getConversationsOfAssistant(assistantId: Uuid) =
        profileFlow.distinctUntilChanged().flatMapLatest { profileId ->
            getConversationsOfAssistant(assistantId, profileId)
                .onStart { emit(emptyList()) }
        }

    fun getConversationsOfAssistant(assistantId: Uuid, profileId: String): Flow<List<Conversation>> {
        return conversationDAO
            .getConversationsOfAssistant(assistantId.toString(), profileId = profileId)
            .map { flow ->
                flow.map { entity ->
                    // 列表视图不需要完整的 nodes，使用空列表
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun getConversationsOfAssistantPaging(assistantId: Uuid) =
        profileFlow.distinctUntilChanged().flatMapLatest { profileId ->
            getConversationsOfAssistantPaging(assistantId, profileId)
                .onStart { emit(PagingData.empty()) }
        }

    fun getConversationsOfAssistantPaging(assistantId: Uuid, profileId: String): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfAssistantPaging(assistantId.toString(), profileId = profileId) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getUnfiledConversationsOfAssistantPaging(assistantId: Uuid) =
        profileFlow.distinctUntilChanged().flatMapLatest { profileId ->
            getUnfiledConversationsOfAssistantPaging(assistantId, profileId)
                .onStart { emit(PagingData.empty()) }
        }

    fun getUnfiledConversationsOfAssistantPaging(assistantId: Uuid, profileId: String): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getUnfiledConversationsOfAssistantPaging(assistantId.toString(), profileId = profileId) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getConversationsOfFolderPaging(folderId: Uuid) =
        profileFlow.distinctUntilChanged().flatMapLatest { profileId ->
            getConversationsOfFolderPaging(folderId, profileId)
                .onStart { emit(PagingData.empty()) }
        }

    fun getConversationsOfFolderPaging(folderId: Uuid, profileId: String): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfFolderPaging(folderId.toString(), profileId = profileId) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    suspend fun getConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
        profileId: String = activeProfileId,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.getConversationsOfAssistantPaging(assistantId.toString(), profileId = profileId)
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun searchConversationsOfAssistantPage(
        assistantId: Uuid,
        titleKeyword: String,
        offset: Int,
        limit: Int,
        profileId: String = activeProfileId,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.searchConversationsOfAssistantPaging(
            assistantId = assistantId.toString(),
            searchText = titleKeyword,
            profileId = profileId,
        )
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun getUnfiledConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
        profileId: String = activeProfileId,
    ): ConversationPageResult = loadConversationPage(
        conversationDAO.getUnfiledConversationsOfAssistantPaging(assistantId.toString(), profileId = profileId),
        offset,
        limit,
    )

    suspend fun getConversationsOfFolderPage(
        folderId: Uuid,
        offset: Int,
        limit: Int,
        profileId: String = activeProfileId,
    ): ConversationPageResult = loadConversationPage(
        conversationDAO.getConversationsOfFolderPaging(folderId.toString(), profileId = profileId),
        offset,
        limit,
    )

    private suspend fun loadConversationPage(
        pagingSource: PagingSource<Int, LightConversationEntity>,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    fun searchConversations(titleKeyword: String) =
        profileFlow.distinctUntilChanged().flatMapLatest { profileId ->
            searchConversations(titleKeyword, profileId)
                .onStart { emit(emptyList()) }
        }

    fun searchConversations(titleKeyword: String, profileId: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversations(titleKeyword,
                    profileId = profileId,
                )
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun searchConversationsPaging(titleKeyword: String) =
        profileFlow.distinctUntilChanged().flatMapLatest { profileId ->
            searchConversationsPaging(titleKeyword, profileId)
                .onStart { emit(PagingData.empty()) }
        }

    fun searchConversationsPaging(titleKeyword: String, profileId: String): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.searchConversationsPaging(titleKeyword,
                    profileId = profileId,
                ) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun searchConversationsOfAssistant(assistantId: Uuid, titleKeyword: String) =
        profileFlow.distinctUntilChanged().flatMapLatest { profileId ->
            searchConversationsOfAssistant(assistantId, titleKeyword, profileId)
                .onStart { emit(emptyList()) }
        }

    fun searchConversationsOfAssistant(assistantId: Uuid, titleKeyword: String, profileId: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversationsOfAssistant(assistantId.toString(), titleKeyword,
                    profileId = profileId,
                )
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun searchConversationsOfAssistantPaging(assistantId: Uuid, titleKeyword: String) =
        profileFlow.distinctUntilChanged().flatMapLatest { profileId ->
            searchConversationsOfAssistantPaging(assistantId, titleKeyword, profileId)
                .onStart { emit(PagingData.empty()) }
        }

    fun searchConversationsOfAssistantPaging(assistantId: Uuid, titleKeyword: String, profileId: String): Flow<PagingData<Conversation>> =
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                initialLoadSize = INITIAL_LOAD_SIZE,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                conversationDAO.searchConversationsOfAssistantPaging(
                    assistantId.toString(),
                    titleKeyword,
                    profileId = profileId,
                )
            }
        ).flow.map { pagingData ->
            pagingData.map { entity ->
                conversationSummaryToConversation(entity)
            }
        }

    suspend fun getConversationById(uuid: Uuid, profileId: String = activeProfileId): Conversation? {
        val entity = conversationDAO.getConversationById(uuid.toString())
        return if (entity != null && entity.profileId == profileId) {
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes)
        } else null
    }

    suspend fun existsConversationById(uuid: Uuid, profileId: String = activeProfileId): Boolean {
        return conversationDAO.getConversationById(uuid.toString())?.profileId == profileId
    }

    // Update-only, in the same transaction as the source check. A late response cannot
    // insert a deleted conversation or replace messages committed while it was running.
    suspend fun updateGeneratedMetadata(
        expected: Conversation,
        title: String? = null,
        suggestions: List<String>? = null,
    ): Boolean = database.withTransaction {
        val id = expected.id.toString()
        val stored = conversationDAO.getConversationById(id) ?: return@withTransaction false
        if (stored.lifecycleRevision != expected.lifecycleRevision || stored.profileId != expected.profileId || loadMessageNodes(id) != expected.messageNodes ||
            (title != null && stored.title != expected.title)) return@withTransaction false
        if (title != null) {
            if (conversationDAO.updateTitle(id, expected.profileId, title) != 1) return@withTransaction false
            // The full-message save previously refreshed this derived title too.
            // Keep the search index consistent without rebuilding or touching messages.
            database.openHelper.writableDatabase.execSQL(
                "UPDATE message_fts SET title = ? WHERE conversation_id = ?", arrayOf(title, id),
            )
        }
        if (suggestions != null && conversationDAO.updateSuggestions(id, expected.profileId, JsonInstant.encodeToString(suggestions)) != 1) return@withTransaction false
        true
    }

    suspend fun countConversations(profileId: String = activeProfileId): Int {
        return conversationDAO.countAll(profileId = profileId)
    }

    suspend fun countConversationsByAssistant(profileId: String = activeProfileId): Map<Uuid, Int> {
        return conversationDAO.countByAssistant(profileId = profileId).mapNotNull { row ->
            runCatching { Uuid.parse(row.assistantId) }.getOrNull()?.let { it to row.count }
        }.toMap()
    }

    suspend fun insertConversation(conversation: Conversation) {
        require(conversation.profileId.isNotBlank())
        database.withTransaction {
            require(conversationDAO.deletion(conversation.id.toString()) == null) { "Conversation previously deleted; explicit restore required" }
            conversationDAO.insert(
                conversationToConversationEntity(conversation)
            )
            saveMessageNodes(conversation.id.toString(), conversation.messageNodes)
            learningMemories.reconcile(conversation)
        }
        indexCommittedConversation(conversation)
    }

    /** Restoration is the only operation allowed to create a new incarnation of a deleted ID. */
    suspend fun restoreConversation(conversation: Conversation): Conversation {
        val restored = database.withTransaction {
            val marker = requireNotNull(conversationDAO.deletion(conversation.id.toString())) { "Conversation was not deleted" }
            require(marker.deleted && marker.profileId == conversation.profileId) { "Conversation restore denied" }
            val next = conversation.copy(lifecycleRevision = marker.revision + 1, newConversation = false)
            conversationDAO.insert(conversationToConversationEntity(next))
            saveMessageNodes(next.id.toString(), next.messageNodes)
            learningMemories.reconcile(next)
            conversationDAO.recordDeletion(marker.copy(revision = next.lifecycleRevision, deleted = false))
            next
        }
        indexCommittedConversation(restored)
        return restored
    }

    private suspend fun requireIncarnation(conversation: Conversation) {
        val marker = conversationDAO.deletion(conversation.id.toString())
        require(marker == null || (!marker.deleted && marker.profileId == conversation.profileId && marker.revision == conversation.lifecycleRevision)) {
            "Conversation deleted or obsolete incarnation"
        }
    }

    suspend fun updateConversation(conversation: Conversation) = updateConversationState(conversation, persistQueue = false)

    /** An approval retry must persist its in-memory safety pause with the decision. */
    suspend fun commitToolApproval(conversation: Conversation) = updateConversationState(conversation, persistQueue = true)

    private suspend fun updateConversationState(conversation: Conversation, persistQueue: Boolean) {
        database.withTransaction {
            requireIncarnation(conversation)
            val stored = conversationDAO.getConversationById(conversation.id.toString())
            require(stored?.lifecycleRevision == conversation.lifecycleRevision && stored.profileId == conversation.profileId) { "Conversation ownership mismatch" }
            val entity = conversationToConversationEntity(conversation)
            conversationDAO.update(if (persistQueue) entity else entity.copy(queueState = stored.queueState))
            // 删除旧的节点，插入新的节点
            messageNodeDAO.deleteByConversation(conversation.id.toString())
            saveMessageNodes(conversation.id.toString(), conversation.messageNodes)
            learningMemories.reconcile(conversation)
        }
        indexCommittedConversation(conversation)
    }

    private suspend fun indexCommittedConversation(conversation: Conversation) {
        // FTS is derived data. Its failure after commit must not make a durable
        // tool decision appear uncommitted, inviting another execution.
        try {
            messageFtsManager.indexConversation(conversation)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            android.util.Log.w("ConversationRepository", "Committed conversation index refresh deferred")
        }
    }

    // Streaming checkpoints preserve metadata and avoid rebuilding FTS for every delta.
    // Use the conversation's owner, never the currently selected learner.
    suspend fun checkpointMessages(conversation: Conversation) {
        database.withTransaction {
            val id = conversation.id.toString()
            requireIncarnation(conversation)
            require(conversationDAO.getConversationById(id)?.profileId == conversation.profileId) {
                "Conversation ownership mismatch"
            }
            val existing = messageNodeDAO.getNodesOfConversation(id).associateBy { it.id }
            val nodes = conversation.messageNodes.mapIndexed { index, node ->
                MessageNodeEntity(
                    id = node.id.toString(), conversationId = id, nodeIndex = index,
                    messages = JsonInstant.encodeToString(node.messages), selectIndex = node.selectIndex,
                )
            }
            val retained = nodes.map { it.id }.toSet()
            existing.keys.filterNot { it in retained }.forEach { messageNodeDAO.deleteById(it) }
            val changedNodes = nodes.filter { existing[it.id] != it }
            messageNodeDAO.insertAll(changedNodes)
            fun userSource(node: MessageNodeEntity?): String? {
                if (node == null) return null
                val message = JsonInstant.decodeFromString<List<UIMessage>>(node.messages).getOrNull(node.selectIndex)
                return message?.takeIf { it.role == me.rerere.ai.core.MessageRole.USER }?.let { "${it.id}:${it.toText()}" }
            }
            if (changedNodes.any { userSource(it) != userSource(existing[it.id]) } ||
                existing.values.any { it.id !in retained && userSource(it) != null }) {
                learningMemories.reconcile(conversation)
            }
        }
    }

    /** Queue removal and the user message share one transaction and a stable message ID. */
    suspend fun commitRuntimeState(conversation: Conversation) {
        database.withTransaction {
            val id = conversation.id.toString()
            requireIncarnation(conversation)
            val stored = conversationDAO.getConversationById(id)
            require(stored == null || stored.lifecycleRevision == conversation.lifecycleRevision) { "Obsolete conversation incarnation" }
            require(stored != null || conversation.newConversation) { "Missing existing conversation" }
            require(stored == null || stored.profileId == conversation.profileId) { "Conversation ownership mismatch" }
            if (stored == null) conversationDAO.insert(conversationToConversationEntity(conversation))
            check(conversationDAO.updateQueue(id, conversation.profileId, JsonInstant.encodeToString(conversation.queue)) == 1)
            checkpointMessages(conversation)
        }
    }

    suspend fun recoverConversation(conversation: Conversation) {
        commitRuntimeState(conversation)
        messageFtsManager.indexConversation(conversation)
    }

    suspend fun deleteConversation(conversation: Conversation) {
        require(conversation.profileId == activeProfileId) { "Conversation access denied" }
        require(conversationDAO.getConversationById(conversation.id.toString())?.profileId == conversation.profileId) { "Conversation access denied" }
        // 获取完整的 Conversation（包含 messageNodes）以正确清理文件
        val fullConversation = if (conversation.messageNodes.isEmpty()) {
            getConversationById(conversation.id) ?: conversation
        } else {
            conversation
        }
        messageFtsManager.deleteConversation(conversation.id.toString())
        database.withTransaction {
            requireIncarnation(conversation)
            conversationDAO.recordDeletion(me.rerere.rikkahub.data.db.entity.ConversationDeletionEntity(
                conversation.id.toString(), conversation.profileId, conversation.lifecycleRevision, true,
            ))
            learningMemories.invalidateConversation(conversation.profileId, conversation.id.toString())
            // message_node 会通过 CASCADE 自动删除
            conversationDAO.delete(
                conversationToConversationEntity(conversation)
            )
        }
        runCatching {
            filesManager.deleteChatFiles(fullConversation.files.filterNot { hasFileReference(it.toString()) })
        }.onFailure {
            android.util.Log.w("ConversationRepository", "Deleted conversation attachment cleanup deferred")
        }
    }

    suspend fun searchMessages(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
        assistantId: Uuid? = null,
        profileId: String = activeProfileId,
    ) = messageFtsManager.search(keyword, sort, assistantId?.toString(), profileId)

    /** Search all persisted titles and indexed message text, with literal substring semantics. */
    suspend fun searchHistory(query: String, profileId: String, limit: Int, offset: Int, archived: Boolean = false): List<Conversation> {
        require(query.length in 1..200 && limit in 1..100 && offset >= 0)
        val ids = mutableListOf<String>()
        database.openHelper.readableDatabase.query(
            """SELECT id FROM conversationentity c WHERE profile_id = ? AND is_archived = ? AND
                (instr(lower(title), lower(?)) > 0 OR EXISTS
                 (SELECT 1 FROM message_fts f WHERE f.conversation_id = c.id AND instr(lower(f.text), lower(?)) > 0))
                ORDER BY is_pinned DESC, update_at DESC, id LIMIT ? OFFSET ?""",
            arrayOf<Any>(profileId, if (archived) 1 else 0, query, query, limit, offset)
        ).use { cursor -> while (cursor.moveToNext()) ids += cursor.getString(0) }
        return ids.mapNotNull { getConversationById(Uuid.parse(it), profileId) }
    }

    suspend fun rebuildAllIndexes(onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }) {
        messageFtsManager.deleteAll()
        val allIds = conversationDAO.getAllIds()
        val total = allIds.size
        allIds.forEachIndexed { index, id ->
            val entity = conversationDAO.getConversationById(id) ?: return@forEachIndexed
            val nodes = loadMessageNodes(entity.id)
            val conversation = conversationEntityToConversation(entity, nodes)
            messageFtsManager.indexConversation(conversation)
            onProgress(index + 1, total)
        }
    }

    suspend fun deleteConversationOfAssistant(assistantId: Uuid) {
        getConversationsOfAssistant(assistantId).first().forEach { conversation ->
            deleteConversation(conversation)
        }
    }

    fun conversationToConversationEntity(conversation: Conversation): ConversationEntity {
        require(conversation.messageNodes.none { it.messages.any { message -> message.hasBase64Part() } })
        return ConversationEntity(
            id = conversation.id.toString(),
            title = conversation.title,
            nodes = "[]",  // nodes 现在存储在单独的表中
            createAt = conversation.createAt.toEpochMilli(),
            updateAt = conversation.updateAt.toEpochMilli(),
            assistantId = conversation.assistantId.toString(),
            chatSuggestions = JsonInstant.encodeToString(conversation.chatSuggestions),
            isPinned = conversation.isPinned,
            isArchived = conversation.isArchived,
            customSystemPrompt = conversation.customSystemPrompt ?: "",
            modeInjectionIds = JsonInstant.encodeToString(conversation.modeInjectionIds),
            lorebookIds = JsonInstant.encodeToString(conversation.lorebookIds),
            workspaceCwd = conversation.workspaceCwd ?: "",
            folderId = conversation.folderId?.toString() ?: "",
            profileId = conversation.profileId,
            queueState = JsonInstant.encodeToString(conversation.queue),
            studyMode = conversation.studyMode.name,
            lifecycleRevision = conversation.lifecycleRevision,
        )
    }

    fun conversationEntityToConversation(
        conversationEntity: ConversationEntity,
        messageNodes: List<MessageNode>
    ): Conversation {
        return Conversation(
            id = Uuid.parse(conversationEntity.id),
            profileId = conversationEntity.profileId,
            lifecycleRevision = conversationEntity.lifecycleRevision,
            queue = JsonInstant.decodeFromString(conversationEntity.queueState),
            studyMode = me.rerere.rikkahub.feature.studychat.StudyMode.entries.firstOrNull { it.name == conversationEntity.studyMode }
                ?: me.rerere.rikkahub.feature.studychat.StudyMode.GUIDED,
            title = conversationEntity.title,
            messageNodes = messageNodes.filter { it.messages.isNotEmpty() },
            createAt = Instant.ofEpochMilli(conversationEntity.createAt),
            updateAt = Instant.ofEpochMilli(conversationEntity.updateAt),
            assistantId = Uuid.parse(conversationEntity.assistantId),
            chatSuggestions = JsonInstant.decodeFromString(conversationEntity.chatSuggestions),
            isPinned = conversationEntity.isPinned,
            isArchived = conversationEntity.isArchived,
            customSystemPrompt = conversationEntity.customSystemPrompt.ifEmpty { null },
            modeInjectionIds = JsonInstant.decodeFromString(conversationEntity.modeInjectionIds),
            lorebookIds = JsonInstant.decodeFromString(conversationEntity.lorebookIds),
            workspaceCwd = conversationEntity.workspaceCwd.ifEmpty { null },
            folderId = conversationEntity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
        )
    }

    fun getPinnedConversations() =
        profileFlow.distinctUntilChanged().flatMapLatest { profileId ->
            getPinnedConversations(profileId)
                .onStart { emit(emptyList()) }
        }

    fun getPinnedConversations(profileId: String): Flow<List<Conversation>> {
        return conversationDAO
            .getPinnedConversations(profileId = profileId)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    suspend fun updatePinStatus(conversationId: Uuid, isPinned: Boolean) {
        require(getConversationById(conversationId) != null) { "Conversation access denied" }
        conversationDAO.updatePinStatus(
            id = conversationId.toString(),
            isPinned = isPinned,
            profileId = activeProfileId,
        )
    }

    suspend fun updateArchiveStatus(conversationId: Uuid, archived: Boolean) {
        require(getConversationById(conversationId) != null) { "Conversation access denied" }
        conversationDAO.updateArchiveStatus(conversationId.toString(), archived, activeProfileId)
    }

    suspend fun updateConversationAssistant(conversationId: Uuid, assistantId: Uuid) {
        require(getConversationById(conversationId) != null) { "Conversation access denied" }
        conversationDAO.updateAssistantId(conversationId.toString(), assistantId.toString(), activeProfileId)
    }

    /**
     * 单列更新会话的文件夹归属，folderId 为 null 表示移出文件夹（未归类）。
     */
    suspend fun updateConversationFolderId(conversationId: Uuid, folderId: Uuid?) {
        val profileId = activeProfileId
        database.withTransaction {
            val conversation = conversationDAO.getConversationById(conversationId.toString())
            require(conversation?.profileId == profileId) { "Conversation access denied" }
            if (folderId != null) {
                val folder = database.folderDao().getFolderById(folderId.toString(), profileId)
                require(folder != null && folder.assistantId == conversation.assistantId) { "Folder access denied" }
            }
            conversationDAO.updateFolderId(conversationId.toString(), folderId?.toString() ?: "", profileId)
        }
    }

    private fun conversationSummaryToConversation(entity: LightConversationEntity): Conversation {
        return Conversation(
            id = Uuid.parse(entity.id),
            profileId = entity.profileId,
            assistantId = Uuid.parse(entity.assistantId),
            title = entity.title,
            isPinned = entity.isPinned,
            createAt = Instant.ofEpochMilli(entity.createAt),
            updateAt = Instant.ofEpochMilli(entity.updateAt),
            messageNodes = emptyList(),
            folderId = entity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
        )
    }

    private suspend fun loadMessageNodes(conversationId: String): List<MessageNode> {
        val favoriteNodeIds = favoriteDAO
            .getFavoriteNodeIdsOfConversation(conversationId)
            .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
            .toSet()

        return database.withTransaction {
            val nodes = mutableListOf<MessageNode>()
            var offset = 0
            val pageSize = 64
            while (true) {
                val page = try {
                    messageNodeDAO.getNodesOfConversationPaged(conversationId, pageSize, offset)
                } catch (e: SQLiteBlobTooBigException) {
                    e.printStackTrace()
                    offset += pageSize
                    continue
                } catch (e: IllegalStateException) {
                    e.printStackTrace()
                    offset += pageSize
                    continue
                }
                if (page.isEmpty()) break
                page.forEach { entity ->
                    val messages = JsonInstant.decodeFromString<List<UIMessage>>(entity.messages)
                    val nodeId = Uuid.parse(entity.id)
                    nodes.add(
                        MessageNode(
                            id = nodeId,
                            messages = messages,
                            selectIndex = entity.selectIndex,
                            isFavorite = favoriteNodeIds.contains(nodeId)
                        )
                    )
                }
                offset += page.size
            }
            nodes
        }
    }

    private suspend fun saveMessageNodes(conversationId: String, nodes: List<MessageNode>) {
        val entities = nodes.mapIndexed { index, node ->
            MessageNodeEntity(
                id = node.id.toString(),
                conversationId = conversationId,
                nodeIndex = index,
                messages = JsonInstant.encodeToString(node.messages),
                selectIndex = node.selectIndex
            )
        }
        messageNodeDAO.insertAll(entities)
    }
}

/**
 * 轻量级的会话查询结果，不包含 nodes 和 suggestions 字段
 */
data class LightConversationEntity(
    val id: String,
    val assistantId: String,
    val title: String,
    val isPinned: Boolean,
    val createAt: Long,
    val updateAt: Long,
    val folderId: String = "",
    val profileId: String = me.rerere.rikkahub.data.datastore.DEFAULT_PROFILE_ID,
)

data class ConversationPageResult(
    val items: List<Conversation>,
    val nextOffset: Int?,
)
