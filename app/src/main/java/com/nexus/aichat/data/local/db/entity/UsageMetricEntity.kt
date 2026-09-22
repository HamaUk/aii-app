package com.nexus.aichat.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
@Entity(
    tableName = "usage_metrics",
    indices = [Index("conversationId"), Index("createdAtEpochMs")],
)
data class UsageMetricEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: String,
    val messageId: String?,
    val providerId: String,
    val modelId: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val reasoningTokens: Int,
    val totalTokens: Int,
    val estimatedCostUsd: Double?,
    val timeToFirstTokenMs: Long?,
    val tokensPerSecond: Double?,
    val providerReported: Boolean,
    val createdAtEpochMs: Long,
)

/** Local cache of documents parsed for a conversation, so a PDF is parsed exactly once. */
