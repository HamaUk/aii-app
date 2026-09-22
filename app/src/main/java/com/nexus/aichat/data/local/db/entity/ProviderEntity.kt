package com.nexus.aichat.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
@Entity(tableName = "providers", indices = [Index("presetId"), Index("isEnabled")])
data class ProviderEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val protocol: String,
    val baseUrl: String,
    val authJson: String,
    val extraHeadersJson: String,
    val modelsPath: String,
    val chatPathOverride: String?,
    val requestTimeoutMs: Long,
    val connectTimeoutMs: Long,
    val supportsNativeTools: Boolean,
    val includeStreamUsage: Boolean,
    val requestBodyTemplate: String?,
    val responseTextPath: String?,
    val presetId: String?,
    val isEnabled: Boolean,
    val modelsJson: String,
    val selectedModelId: String?,
    val createdAtEpochMs: Long,
    val lastUsedEpochMs: Long?,
    val isBuiltInPreset: Boolean,
    val notes: String?,
)
