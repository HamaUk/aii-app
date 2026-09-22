package com.nexus.aichat.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.nexus.aichat.data.local.db.entity.AttachmentEntity
import com.nexus.aichat.data.local.db.entity.ConversationEntity
import com.nexus.aichat.data.local.db.entity.DocumentCacheEntity
import com.nexus.aichat.data.local.db.entity.MessageEntity
import com.nexus.aichat.data.local.db.entity.ProviderEntity
import com.nexus.aichat.data.local.db.entity.SystemRuleEntity
import com.nexus.aichat.data.local.db.entity.ToolInvocationEntity
import com.nexus.aichat.data.local.db.entity.UsageMetricEntity
import kotlinx.coroutines.flow.Flow
@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAtEpochMs ASC")
    fun observeByConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAtEpochMs ASC")
    suspend fun byConversation(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun byId(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE parentId = :parentId ORDER BY siblingIndex ASC")
    suspend fun childrenOf(parentId: String): List<MessageEntity>

    @Query("SELECT COALESCE(MAX(siblingIndex), -1) FROM messages WHERE parentId = :parentId")
    suspend fun maxSiblingIndex(parentId: String?): Int

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAtEpochMs DESC LIMIT 1")
    suspend fun latestIn(conversationId: String): MessageEntity?

    @Upsert
    suspend fun upsert(message: MessageEntity)

    @Update
    suspend fun update(message: MessageEntity)

    @Query("UPDATE messages SET partsJson = :partsJson, status = :status, updatedAtEpochMs = :now WHERE id = :id")
    suspend fun updateParts(id: String, partsJson: String, status: String, now: Long)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: String)

    /** Deleting a subtree: children of a branch must never outlive their parent. */
    @Query("DELETE FROM messages WHERE parentId = :parentId")
    suspend fun deleteChildren(parentId: String)
}
