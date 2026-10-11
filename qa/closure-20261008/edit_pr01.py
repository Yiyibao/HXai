from pathlib import Path
root = Path(__file__).resolve().parents[2]
def edit(name, old, new):
    p = root / name
    s = p.read_text(encoding='utf-8')
    assert old in s, (name, old)
    p.write_text(s.replace(old, new), encoding='utf-8')
base='client/app/src/main/java/me/rerere/rikkahub/'
edit(base+'data/db/AppDatabase.kt','ConversationEntity::class,','ConversationEntity::class,\n        me.rerere.rikkahub.data.db.entity.ConversationDeletionEntity::class,')
edit(base+'data/db/AppDatabase.kt','version = 30,','version = 31,')
edit(base+'data/db/AppDatabase.kt','autoMigrations = [','autoMigrations = [\n        AutoMigration(from = 30, to = 31),')
edit(base+'data/model/Conversation.kt','val studyMode:', 'val lifecycleRevision: Long = 0,\n    val studyMode:')
edit(base+'data/db/entity/ConversationEntity.kt','val studyMode: String = "GUIDED",','val studyMode: String = "GUIDED",\n    @ColumnInfo("lifecycle_revision", defaultValue = "0")\n    val lifecycleRevision: Long = 0,')
edit(base+'data/db/dao/ConversationDAO.kt','interface ConversationDAO {','''interface ConversationDAO {
    @Query("SELECT * FROM ConversationDeletionEntity WHERE id = :id")
    suspend fun deletion(id: String): me.rerere.rikkahub.data.db.entity.ConversationDeletionEntity?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun recordDeletion(value: me.rerere.rikkahub.data.db.entity.ConversationDeletionEntity)
''')
repo=base+'data/repository/ConversationRepository.kt'
edit(repo,'database.withTransaction {\n            conversationDAO.insert(','''database.withTransaction {
            require(conversationDAO.deletion(conversation.id.toString()) == null) { "Conversation previously deleted; explicit restore required" }
            conversationDAO.insert(''')
edit(repo,'suspend fun updateConversation(conversation: Conversation)', '''/** Restoration is the only operation allowed to create a new incarnation of a deleted ID. */
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

    suspend fun updateConversation(conversation: Conversation)''')
edit(repo,'val stored = conversationDAO.getConversationById(conversation.id.toString())\n            require(stored?.profileId == conversation.profileId)', 'requireIncarnation(conversation)\n            val stored = conversationDAO.getConversationById(conversation.id.toString())\n            require(stored?.lifecycleRevision == conversation.lifecycleRevision && stored.profileId == conversation.profileId)')
edit(repo,'val id = conversation.id.toString()\n            require(conversationDAO.getConversationById(id)?.profileId', 'val id = conversation.id.toString()\n            requireIncarnation(conversation)\n            require(conversationDAO.getConversationById(id)?.profileId')
edit(repo,'val stored = conversationDAO.getConversationById(id)\n            require(stored == null', 'requireIncarnation(conversation)\n            val stored = conversationDAO.getConversationById(id)\n            require(stored != null || conversation.newConversation) { "Missing existing conversation" }\n            require(stored == null')
edit(repo,'learningMemories.invalidateConversation(conversation.profileId, conversation.id.toString())','''requireIncarnation(conversation)
            conversationDAO.recordDeletion(me.rerere.rikkahub.data.db.entity.ConversationDeletionEntity(
                conversation.id.toString(), conversation.profileId, conversation.lifecycleRevision, true,
            ))
            learningMemories.invalidateConversation(conversation.profileId, conversation.id.toString())''')
edit(repo,'if (stored.profileId != expected.profileId ||', 'if (stored.lifecycleRevision != expected.lifecycleRevision || stored.profileId != expected.profileId ||')
edit(repo,'studyMode = conversation.studyMode.name,','studyMode = conversation.studyMode.name,\n            lifecycleRevision = conversation.lifecycleRevision,')
edit(repo,'profileId = conversationEntity.profileId,','profileId = conversationEntity.profileId,\n            lifecycleRevision = conversationEntity.lifecycleRevision,')
service=base+'service/ChatService.kt'
edit(service,'if (!exists) {\n            conversationRepo.insertConversation(conversation)','if (!exists) {\n            require(conversation.newConversation) { "Missing existing conversation; explicit create or restore required" }\n            conversationRepo.insertConversation(conversation)')
edit(service,'''sessionManager.getOrCreate(conversation.id).restore(conversation) {
            conversationRepo.insertConversation(conversation)
        }''','''val session = sessionManager.getOrCreate(conversation.id)
        session.restoreCommitted { conversationRepo.restoreConversation(conversation) }''')
edit(service,'saveConversation(forkConversation.id, forkConversation)','saveConversation(forkConversation.id, forkConversation.copy(newConversation = true, lifecycleRevision = 0))')
edit(base+'service/ConversationSession.kt','internal suspend fun restore(conversation: Conversation, persist:', '''internal suspend fun restoreCommitted(persist: suspend () -> Conversation) = withContext(NonCancellable) { metadataMutex.withLock {
        val conversation = persist()
        invalidateBackgroundResults()
        deleted = false
        messageQueue.restore(conversation.queue.recovered())
        updateConversation(conversation)
    } }

    internal suspend fun restore(conversation: Conversation, persist:''')
