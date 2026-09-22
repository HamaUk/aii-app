package com.nexus.aichat.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
@Entity(tableName = "system_rules")
data class SystemRuleEntity(
    @PrimaryKey val id: String,
    val title: String,
    val body: String,
    val appliesGlobally: Boolean,
    val isEnabled: Boolean,
    val isBuiltIn: Boolean,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

/**
 * Every tool execution, kept for the audit trail the settings screen exposes.
 * This is what lets a user answer "what did this agent actually do on my behalf?".
 */
