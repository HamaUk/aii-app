package com.nexus.aichat.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
@Entity(
    tableName = "tool_invocations",
    indices = [Index("conversationId"), Index("runId"), Index("toolName")],
)
data class ToolInvocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: String,
    val conversationId: String,
    val messageId: String?,
    val toolName: String,
    val argumentsJson: String,
    val resultPreview: String?,
    val isError: Boolean,
    val wasApprovedByUser: Boolean,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long?,
    val durationMs: Long?,
)

/** Per-message token/cost telemetry. Powers the usage screen and per-conversation cost totals. */
