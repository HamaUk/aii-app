package com.nexus.aichat.core.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import com.nexus.aichat.BuildConfig
import com.nexus.aichat.core.ai.agent.AgentTool
import com.nexus.aichat.core.ai.spi.AttachmentProvider
import com.nexus.aichat.core.ai.spi.BinaryResolver
import com.nexus.aichat.core.ai.spi.DocumentTextExtractor
import com.nexus.aichat.core.ai.spi.ImageScaler
import com.nexus.aichat.core.ai.spi.SecretProvider
import com.nexus.aichat.core.ai.spi.WebSearchProvider
import com.nexus.aichat.core.common.logging.NexusLogger
import com.nexus.aichat.core.security.SecureKeyStore
import com.nexus.aichat.core.util.LogcatLogger
import com.nexus.aichat.data.local.datastore.SettingsDataStore
import com.nexus.aichat.data.local.db.RoomAttachmentProvider
import com.nexus.aichat.data.local.file.AttachmentFileSource
import com.nexus.aichat.data.local.file.AttachmentTextExtractor
import com.nexus.aichat.data.local.file.ImageProcessor
import com.nexus.aichat.data.remote.api.SearchProviderRouter
import com.nexus.aichat.data.repository.ChatRepositoryImpl
import com.nexus.aichat.data.repository.ProviderRepositoryImpl
import com.nexus.aichat.data.repository.SettingsRepositoryImpl
import com.nexus.aichat.domain.repository.ChatRepository
import com.nexus.aichat.domain.repository.ProviderRepository
import com.nexus.aichat.domain.repository.SettingsRepository
import com.nexus.aichat.domain.tools.DateTimeTool
import com.nexus.aichat.domain.tools.FileReaderTool
import com.nexus.aichat.domain.tools.WebFetcherTool
import com.nexus.aichat.domain.tools.WebSearchTool
import javax.inject.Singleton

/**
 * The app's dependency graph.
 *
 * Two jobs, and nothing else:
 *  1. **bind every `:core:ai` SPI to a platform implementation** - this is the only file where the
 *     pure-JVM engine meets Android;
 *  2. **register the tool plugins** the harness may offer a model. Tools are constructed *here*, from
 *     user settings, so a policy change (e.g. "allow local network fetches") takes effect on the next
 *     run without rebuilding the registry.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindLogger(impl: LogcatLogger): NexusLogger

    @Binds
    @Singleton
    abstract fun bindSecretProvider(impl: SecureKeyStore): SecretProvider

    @Binds
    @Singleton
    abstract fun bindBinaryResolver(impl: AttachmentFileSource): BinaryResolver

    @Binds
    @Singleton
    abstract fun bindImageScaler(impl: ImageProcessor): ImageScaler

    @Binds
    @Singleton
    abstract fun bindDocumentExtractor(impl: AttachmentTextExtractor): DocumentTextExtractor

    @Binds
    @Singleton
    abstract fun bindAttachmentProvider(impl: RoomAttachmentProvider): AttachmentProvider

    @Binds
    @Singleton
    abstract fun bindWebSearch(impl: SearchProviderRouter): WebSearchProvider

    // --- Repositories ---------------------------------------------------------------------------
    // Bound as interfaces so ViewModels depend on contracts; the impls are the only classes that
    // know about Room DAOs, the vault and the agent orchestrator at the same time.

    @Binds
    @Singleton
    abstract fun bindChatRepository(impl: ChatRepositoryImpl): ChatRepository

    @Binds
    @Singleton
    abstract fun bindProviderRepository(impl: ProviderRepositoryImpl): ProviderRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    companion object {

        @Provides
        @Singleton
        fun logcatLogger(): LogcatLogger = LogcatLogger(BuildConfig.DEBUG)

        @Provides
        @Singleton
        fun settingsDataStore(@ApplicationContext context: Context): SettingsDataStore =
            SettingsDataStore(context)

        // --- Tool plugins ---------------------------------------------------------------------
        // Registered as a set; :core:ai folds them into its ToolRegistry. A tool that is disabled in
        // settings is filtered at run time, not removed here, so toggling never rebuilds the graph.

        @Provides
        @IntoSet
        fun webFetcherTool(settings: SettingsDataStore, logger: LogcatLogger): AgentTool =
            WebFetcherTool.create(settings, logger)

        @Provides
        @IntoSet
        fun webSearchTool(search: SearchProviderRouter): AgentTool = WebSearchTool.create(search)

        @Provides
        @IntoSet
        fun fileReaderTool(
            attachments: AttachmentProvider,
            extractor: DocumentTextExtractor,
        ): AgentTool = FileReaderTool.create(attachments, extractor)

        @Provides
        @IntoSet
        fun dateTimeTool(): AgentTool = DateTimeTool.create()
    }
}
