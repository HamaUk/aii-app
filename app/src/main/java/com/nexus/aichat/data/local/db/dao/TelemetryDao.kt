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
interface TelemetryDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun recordToolInvocation(invocation: ToolInvocationEntity): Long

    @Query("SELECT * FROM tool_invocations WHERE conversationId = :conversationId ORDER BY startedAtEpochMs DESC")
    fun observeToolInvocations(conversationId: String): Flow<List<ToolInvocationEntity>>

    @Query("SELECT * FROM tool_invocations WHERE runId = :runId ORDER BY startedAtEpochMs ASC")
    suspend fun toolInvocationsForRun(runId: String): List<ToolInvocationEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun recordUsage(metric: UsageMetricEntity): Long

    @Query(
        """
        SELECT COALESCE(SUM(inputTokens),0) AS inputTokens,
               COALESCE(SUM(outputTokens),0) AS outputTokens,
               COALESCE(SUM(reasoningTokens),0) AS reasoningTokens,
               COALESCE(SUM(totalTokens),0) AS totalTokens,
               COALESCE(SUM(estimatedCostUsd),0) AS costUsd
        FROM usage_metrics WHERE conversationId = :conversationId
        """,
    )
    fun observeConversationTotals(conversationId: String): Flow<UsageTotalsRow>

    @Query("DELETE FROM usage_metrics WHERE createdAtEpochMs < :beforeEpochMs")
    suspend fun pruneUsage(beforeEpochMs: Long)
}

data class UsageTotalsRow(
    val inputTokens: Int,
    val outputTokens: Int,
    val reasoningTokens: Int,
    val totalTokens: Int,
    val costUsd: Double,
)
