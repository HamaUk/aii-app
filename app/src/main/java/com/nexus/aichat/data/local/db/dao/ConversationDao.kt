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
interface ConversationDao {

    @Query("SELECT * FROM conversations WHERE isArchived = 0 ORDER BY isPinned DESC, updatedAtEpochMs DESC")
    fun observeActive(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeById(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun byId(id: String): ConversationEntity?

    /**
     * Drawer projection. The correlated sub-select gives an accurate message count without loading a
     * single message row, which matters once a user has hundreds of conversations.
     */
    @Query(
        """
        SELECT c.id AS id,
               c.title AS title,
               c.updatedAtEpochMs AS updatedAtEpochMs,
               c.modelId AS modelId,
               c.providerId AS providerId,
               c.isPinned AS isPinned,
               (SELECT COUNT(*) FROM messages m WHERE m.conversationId = c.id) AS messageCount
        FROM conversations c
        WHERE c.isArchived = 0
        ORDER BY c.isPinned DESC, c.updatedAtEpochMs DESC
        """,
    )
    fun observeSummaries(): Flow<List<ConversationSummaryRow>>

    @Upsert
    suspend fun upsert(conversation: ConversationEntity)

    @Query("UPDATE conversations SET title = :title, updatedAtEpochMs = :now WHERE id = :id")
    suspend fun rename(id: String, title: String, now: Long)

    @Query("UPDATE conversations SET activeLeafMessageId = :messageId, updatedAtEpochMs = :now WHERE id = :id")
    suspend fun setActiveLeaf(id: String, messageId: String?, now: Long)

    /**
     * Re-binds a conversation to a provider + model.
     *
     * The active leaf is cleared with it: a branch position is only meaningful against the model that
     * produced it, and after a provider switch there is a new turn to send anyway.
     */
    @Query(
        """
        UPDATE conversations
        SET providerId = :providerId,
            modelId = :modelId,
            activeLeafMessageId = NULL,
            updatedAtEpochMs = :now
        WHERE id = :id
        """,
    )
    suspend fun setProviderAndModel(id: String, providerId: String?, modelId: String?, now: Long)

    @Query("UPDATE conversations SET isPinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE conversations SET isArchived = :archived WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM conversations WHERE isArchived = 1")
    suspend fun deleteAllArchived()
}

/** Projection for the drawer: no message bodies loaded. */
data class ConversationSummaryRow(
    val id: String,
    val title: String,
    val updatedAtEpochMs: Long,
    val modelId: String?,
    val providerId: String?,
    val isPinned: Boolean,
    val messageCount: Int,
)
