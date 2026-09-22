package com.nexus.aichat.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
@Entity(
    tableName = "messages",
    indices = [
        Index("conversationId"),
        Index(value = ["conversationId", "parentId", "siblingIndex"]),
        Index("status"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val partsJson: String,
    val parentId: String?,
    val siblingIndex: Int,
    val status: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val providerId: String?,
    val modelId: String?,
    val usageJson: String?,
    val error: String?,
    val agentRunId: String?,
    val isPinned: Boolean,
    val finishReason: String?,
)
