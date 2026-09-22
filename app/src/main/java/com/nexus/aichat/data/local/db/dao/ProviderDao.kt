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
interface ProviderDao {

    @Query("SELECT * FROM providers ORDER BY isBuiltInPreset DESC, displayName ASC")
    fun observeAll(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers WHERE isEnabled = 1 ORDER BY lastUsedEpochMs DESC")
    fun observeEnabled(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun byId(id: String): ProviderEntity?

    @Query("SELECT * FROM providers WHERE presetId = :presetId LIMIT 1")
    suspend fun byPreset(presetId: String): ProviderEntity?

    @Upsert
    suspend fun upsert(provider: ProviderEntity)

    @Query("UPDATE providers SET modelsJson = :modelsJson, selectedModelId = :selectedModelId WHERE id = :id")
    suspend fun updateModels(id: String, modelsJson: String, selectedModelId: String?)

    @Query("UPDATE providers SET lastUsedEpochMs = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long)

    @Query("DELETE FROM providers WHERE id = :id AND isBuiltInPreset = 0")
    suspend fun delete(id: String)
}
