package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.entity.FavoriteEntity
import me.rerere.rikkahub.data.favorite.NodeFavoriteAdapter
import me.rerere.rikkahub.data.model.FavoriteType
import me.rerere.rikkahub.data.model.NodeFavoriteTarget
import kotlin.uuid.Uuid

class FavoriteRepository(
    private val dao: FavoriteDAO,
    private val settingsStore: SettingsStore,
) {
    private val activeProfileId get() = settingsStore.settingsFlow.value.activeProfileId

    fun listAll(): Flow<List<FavoriteEntity>> = settingsStore.settingsFlow
        .map { it.activeProfileId }
        .distinctUntilChanged()
        .flatMapLatest { profileId -> dao.listForProfile(profileId).onStart { emit(emptyList()) } }
        .map { rows -> rows.filter(::isValidReference) }

    fun listByType(type: FavoriteType): Flow<List<FavoriteEntity>> =
        listAll().map { rows -> rows.filter { it.type == type.value } }

    suspend fun getByRefKey(refKey: String): FavoriteEntity? {
        val profileId = activeProfileId
        val entity = dao.getRefForProfile(refKey, profileId) ?: return null
        return entity.takeIf { activeProfileId == profileId && isValidReference(it) }
    }

    suspend fun existsByRefKey(refKey: String): Boolean = getByRefKey(refKey) != null

    suspend fun deleteByRefKey(refKey: String): Int = dao.deleteRefForProfile(refKey, activeProfileId)

    suspend fun deleteById(id: String): Int = dao.deleteIdForProfile(id, activeProfileId)

    private fun isValidReference(entity: FavoriteEntity): Boolean {
        val ref = NodeFavoriteAdapter.decodeRef(entity) ?: return false
        return entity.refKey == NodeFavoriteAdapter.buildRefKey(ref.conversationId.toString(), ref.nodeId.toString())
    }

    suspend fun upsert(entity: FavoriteEntity) {
        require(isValidReference(entity)) { "Favorite reference mismatch" }
        dao.upsertForProfile(entity, activeProfileId)
    }

    suspend fun addNodeFavorite(target: NodeFavoriteTarget): FavoriteEntity {
        val refKey = NodeFavoriteAdapter.buildRefKey(target)
        val profileId = activeProfileId
        require(dao.canAccessNode(refKey, profileId)) { "Favorite access denied" }
        val existing = dao.getRefForProfile(refKey, profileId)
        val favorite = NodeFavoriteAdapter.buildFavoriteEntity(
            target = target,
            existing = existing,
        )
        require(activeProfileId == profileId) { "Active profile changed" }
        dao.upsertForProfile(favorite, profileId)
        return favorite
    }

    suspend fun removeNodeFavorite(conversationId: Uuid, nodeId: Uuid): Int {
        return deleteByRefKey(NodeFavoriteAdapter.buildRefKey(conversationId.toString(), nodeId.toString()))
    }

    suspend fun isNodeFavorited(conversationId: Uuid, nodeId: Uuid): Boolean {
        return existsByRefKey(NodeFavoriteAdapter.buildRefKey(conversationId.toString(), nodeId.toString()))
    }
}
