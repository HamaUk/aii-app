package com.nexus.aichat.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
@Entity(
    tableName = "conversations",
    indices = [Index("updatedAtEpochMs"), Index("isArchived")],
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val providerId: String?,
    val modelId: String?,
    val systemPromptOverride: String?,
    val systemRuleIdsJson: String,
    val temperature: Double,
    val topP: Double,
    val maxOutputTokens: Int?,
    val agentOptionsJson: String,
    val activeLeafMessageId: String?,
    val isPinned: Boolean,
    val isArchived: Boolean,
    val totalUsageJson: String,
    val lastError: String?,
)
