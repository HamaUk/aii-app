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
interface SystemRuleDao {

    @Query("SELECT * FROM system_rules ORDER BY isBuiltIn DESC, updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<SystemRuleEntity>>

    @Query("SELECT * FROM system_rules WHERE isEnabled = 1 AND appliesGlobally = 1")
    suspend fun enabledGlobal(): List<SystemRuleEntity>

    @Query("SELECT * FROM system_rules WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<SystemRuleEntity>

    @Upsert
    suspend fun upsert(rule: SystemRuleEntity)

    @Query("DELETE FROM system_rules WHERE id = :id AND isBuiltIn = 0")
    suspend fun delete(id: String)
}
