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
interface DocumentCacheDao {

    @Query("SELECT * FROM document_cache WHERE contentHash = :hash")
    suspend fun byHash(hash: String): DocumentCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: DocumentCacheEntity)

    @Query("DELETE FROM document_cache WHERE created_at < :beforeEpochMs")
    suspend fun prune(beforeEpochMs: Long)
}
