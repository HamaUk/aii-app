package com.nexus.aichat.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.nexus.aichat.data.local.db.dao.AttachmentDao
import com.nexus.aichat.data.local.db.dao.ConversationDao
import com.nexus.aichat.data.local.db.dao.DocumentCacheDao
import com.nexus.aichat.data.local.db.dao.MessageDao
import com.nexus.aichat.data.local.db.dao.ProviderDao
import com.nexus.aichat.data.local.db.dao.SystemRuleDao
import com.nexus.aichat.data.local.db.dao.TelemetryDao
import com.nexus.aichat.data.local.db.entity.AttachmentEntity
import com.nexus.aichat.data.local.db.entity.ConversationEntity
import com.nexus.aichat.data.local.db.entity.DocumentCacheEntity
import com.nexus.aichat.data.local.db.entity.MessageEntity
import com.nexus.aichat.data.local.db.entity.ProviderEntity
import com.nexus.aichat.data.local.db.entity.SystemRuleEntity
import com.nexus.aichat.data.local.db.entity.ToolInvocationEntity
import com.nexus.aichat.data.local.db.entity.UsageMetricEntity

/**
 * The single Room database.
 *
 * Schema export is enabled (`room.schemaLocation` in `build.gradle.kts`), so every version's JSON
 * schema is committed: a migration can be written and reviewed without guessing what the old schema
 * looked like. Destructive migration is deliberately *not* enabled - losing a user's chat history on
 * an app update is not an acceptable trade for developer convenience.
 */
@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        AttachmentEntity::class,
        ProviderEntity::class,
        SystemRuleEntity::class,
        ToolInvocationEntity::class,
        UsageMetricEntity::class,
        DocumentCacheEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class NexusDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun providerDao(): ProviderDao
    abstract fun systemRuleDao(): SystemRuleDao
    abstract fun telemetryDao(): TelemetryDao
    abstract fun documentCacheDao(): DocumentCacheDao

    companion object {
        const val NAME = "nexus.db"

        /**
         * Migrations live here as `Migration(1, 2) { … }` entries. Until the first one exists, the
         * builder is configured with `fallbackToDestructiveMigration` *disabled*, which turns a
         * forgotten migration into a loud crash in CI rather than silent data loss in production.
         */
        val ALL_MIGRATIONS: Array<androidx.room.migration.Migration> = emptyArray()
    }
}
