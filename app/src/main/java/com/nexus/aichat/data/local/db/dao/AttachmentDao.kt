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
interface AttachmentDao {

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun byId(id: String): AttachmentEntity?

    @Query("SELECT * FROM attachments WHERE conversationId = :conversationId ORDER BY createdAtEpochMs ASC")
    suspend fun byConversation(conversationId: String): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE messageId = :messageId")
    suspend fun byMessage(messageId: String): List<AttachmentEntity>

    @Upsert
    suspend fun upsert(attachment: AttachmentEntity)

    @Query("UPDATE attachments SET extractedText = :text, pageCount = :pageCount, extractionError = :error WHERE id = :id")
    suspend fun cacheExtraction(id: String, text: String?, pageCount: Int?, error: String?)

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun delete(id: String)
}
