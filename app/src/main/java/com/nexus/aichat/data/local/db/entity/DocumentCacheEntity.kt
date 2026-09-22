package com.nexus.aichat.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
@Entity(tableName = "document_cache", indices = [Index("contentHash")])
data class DocumentCacheEntity(
    @PrimaryKey val contentHash: String,
    val displayName: String,
    val pageCount: Int?,
    val extractedText: String,
    @ColumnInfo(name = "created_at") val createdAtEpochMs: Long,
)
