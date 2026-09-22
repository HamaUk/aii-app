package com.nexus.aichat.core.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import com.nexus.aichat.data.local.db.NexusDatabase
import com.nexus.aichat.data.local.db.dao.AttachmentDao
import com.nexus.aichat.data.local.db.dao.ConversationDao
import com.nexus.aichat.data.local.db.dao.DocumentCacheDao
import com.nexus.aichat.data.local.db.dao.MessageDao
import com.nexus.aichat.data.local.db.dao.ProviderDao
import com.nexus.aichat.data.local.db.dao.SystemRuleDao
import com.nexus.aichat.data.local.db.dao.TelemetryDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): NexusDatabase =
        Room.databaseBuilder(context, NexusDatabase::class.java, NexusDatabase.NAME)
            .addMigrations(*NexusDatabase.ALL_MIGRATIONS)
            // WAL: the chat feed reads while the agent writes, and a stalled writer must never block
            // scrolling on a mid-range device.
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()

    @Provides
    fun conversationDao(db: NexusDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun messageDao(db: NexusDatabase): MessageDao = db.messageDao()

    @Provides
    fun attachmentDao(db: NexusDatabase): AttachmentDao = db.attachmentDao()

    @Provides
    fun providerDao(db: NexusDatabase): ProviderDao = db.providerDao()

    @Provides
    fun systemRuleDao(db: NexusDatabase): SystemRuleDao = db.systemRuleDao()

    @Provides
    fun telemetryDao(db: NexusDatabase): TelemetryDao = db.telemetryDao()

    @Provides
    fun documentCacheDao(db: NexusDatabase): DocumentCacheDao = db.documentCacheDao()
}
