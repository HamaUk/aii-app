package com.nexus.aichat.core.ai.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.nexus.aichat.core.ai.agent.AgentOrchestrator
import com.nexus.aichat.core.ai.agent.AgentPromptBuilder
import com.nexus.aichat.core.ai.agent.AgentTool
import com.nexus.aichat.core.ai.agent.ToolRegistry
import com.nexus.aichat.core.ai.catalog.ModelDiscoveryService
import com.nexus.aichat.core.ai.catalog.ProviderHealthService
import com.nexus.aichat.core.ai.engine.ChatCompletionClient
import com.nexus.aichat.core.ai.engine.ChatEngine
import com.nexus.aichat.core.ai.protocol.AdapterRegistry
import com.nexus.aichat.core.ai.spi.BinaryResolver
import com.nexus.aichat.core.ai.spi.ImageScaler
import com.nexus.aichat.core.ai.transport.ChatTransport
import com.nexus.aichat.core.common.di.DefaultNexusDispatchers
import com.nexus.aichat.core.common.di.NexusDispatchers
import com.nexus.aichat.core.common.logging.NexusLogger
import com.nexus.aichat.core.common.time.SystemTimeProvider
import com.nexus.aichat.core.common.time.TimeProvider
import javax.inject.Singleton

/**
 * Wiring for the AI core.
 *
 * Note what is deliberately *not* provided here: [NexusLogger], [com.nexus.aichat.core.ai.spi.SecretProvider],
 * [BinaryResolver], [ImageScaler], [com.nexus.aichat.core.ai.spi.DocumentTextExtractor],
 * [com.nexus.aichat.core.ai.spi.AttachmentProvider] and the default [WebSearchProvider]. Those are platform
 * concerns and are bound by the :app module (`AppModule`), which keeps this module a pure-JVM
 * library with no Android dependency at all.
 */
@Module
@InstallIn(SingletonComponent::class)
object AiCoreModule {

    @Provides
    @Singleton
    fun dispatchers(): NexusDispatchers = DefaultNexusDispatchers()

    @Provides
    @Singleton
    fun timeProvider(): TimeProvider = SystemTimeProvider()

    // HttpClient, ChatTransport and AdapterRegistry are deliberately NOT provided here: they are
    // platform networking concerns and are owned by the :app module (core/di/NetworkModule.kt).
    // This module provides the engine and the harness that consume those interfaces.

    @Provides
    @Singleton
    fun chatEngine(
        transport: ChatTransport,
        adapters: AdapterRegistry,
        time: TimeProvider,
        dispatchers: NexusDispatchers,
        logger: NexusLogger,
    ): ChatEngine = ChatEngine(transport, adapters, time, dispatchers, logger)

    @Provides
    @Singleton
    fun chatCompletionClient(engine: ChatEngine): ChatCompletionClient = engine

    @Provides
    @Singleton
    fun promptBuilder(
        binaryResolver: BinaryResolver,
        imageScaler: ImageScaler,
        logger: NexusLogger,
    ): AgentPromptBuilder = AgentPromptBuilder(binaryResolver, imageScaler, logger)

    // --- Tool plugins -------------------------------------------------------------------------
    // Tools are registered by the :app module (core/di/AppModule.kt) because they depend on user
    // settings and on platform SPIs. The registry below consumes whatever set the app provides.

    @Provides
    @Singleton
    fun toolRegistry(tools: Set<@JvmSuppressWildcards AgentTool>): ToolRegistry = ToolRegistry(tools.toSet())

    // --- Harness ------------------------------------------------------------------------------

    @Provides
    @Singleton
    fun agentOrchestrator(
        client: ChatCompletionClient,
        secrets: com.nexus.aichat.core.ai.spi.SecretProvider,
        tools: ToolRegistry,
        promptBuilder: AgentPromptBuilder,
        time: TimeProvider,
        dispatchers: NexusDispatchers,
        logger: NexusLogger,
    ): AgentOrchestrator = AgentOrchestrator(client, secrets, tools, promptBuilder, time, dispatchers, logger)

    @Provides
    @Singleton
    fun modelDiscovery(
        transport: ChatTransport,
        adapters: AdapterRegistry,
        secrets: com.nexus.aichat.core.ai.spi.SecretProvider,
        time: TimeProvider,
        logger: NexusLogger,
    ): ModelDiscoveryService = ModelDiscoveryService(transport, adapters, secrets, time, logger)

    @Provides
    @Singleton
    fun providerHealth(
        transport: ChatTransport,
        adapters: AdapterRegistry,
        secrets: com.nexus.aichat.core.ai.spi.SecretProvider,
        time: TimeProvider,
        logger: NexusLogger,
    ): ProviderHealthService = ProviderHealthService(transport, adapters, secrets, time, logger)
}
