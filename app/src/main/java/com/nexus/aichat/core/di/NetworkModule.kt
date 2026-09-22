package com.nexus.aichat.core.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.nexus.aichat.core.ai.protocol.AdapterRegistry
import com.nexus.aichat.core.ai.transport.ChatTransport
import com.nexus.aichat.core.ai.transport.HttpEngineFactory
import com.nexus.aichat.core.ai.transport.KtorChatTransport
import com.nexus.aichat.core.common.logging.NexusLogger
import io.ktor.client.HttpClient
import javax.inject.Singleton

/**
 * Networking, owned by the app.
 *
 * Split rationale: `:core:ai` provides the *engine* (adapters, ChatEngine, orchestrator) and depends
 * on interfaces; the concrete HTTP stack is a platform concern, so it is constructed here. That is
 * what lets `:core:ai` stay a pure-JVM module whose tests run without OkHttp, TLS or a network.
 *
 * There is exactly one [HttpClient] in the process: one connection pool, one HTTP/2 session pool,
 * one TLS cache. Multiple clients is the classic way an app ends up with three cold-start handshakes.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun httpClient(logger: NexusLogger): HttpClient = HttpEngineFactory.create(
        logger = logger,
        // Header/body logging is debug-only; the engine redacts secrets before anything is written.
        debugLogging = true,
        userAgent = com.nexus.aichat.core.ai.transport.HttpEngineFactory.DEFAULT_USER_AGENT,
    )

    @Provides
    @Singleton
    fun chatTransport(client: HttpClient, logger: NexusLogger): ChatTransport =
        KtorChatTransport(client, logger)

    /**
     * Protocol dispatch table. Every provider - preset or user-built - resolves its wire format here,
     * which is the single reason a custom endpoint needs no code anywhere else.
     */
    @Provides
    @Singleton
    fun adapterRegistry(): AdapterRegistry = AdapterRegistry()
}
