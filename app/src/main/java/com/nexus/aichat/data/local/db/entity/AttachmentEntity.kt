package com.nexus.aichat.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
@Entity(
    tableName = "attachments",
    indices = [Index("conversationId"), Index("messageId")],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val conversationId: String?,
    val messageId: String?,
    val kind: String,
    val uri: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val width: Int?,
    val height: Int?,
    val pageCount: Int?,
    val extractedText: String?,
    val extractionError: String?,
    val createdAtEpochMs: Long,
)
