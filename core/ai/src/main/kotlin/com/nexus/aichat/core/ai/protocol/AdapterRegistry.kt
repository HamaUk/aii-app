package com.nexus.aichat.core.ai.protocol

import com.nexus.aichat.core.model.ProviderConfig
import com.nexus.aichat.core.model.ProviderProtocol

/**
 * Protocol -> adapter lookup. Every code path in the app resolves its wire format through here, which
 * is why a user-built custom provider needs no special handling anywhere else in the codebase.
 */
class AdapterRegistry(
    adapters: List<ProviderAdapter> = default(),
) {

    private val byProtocol: Map<ProviderProtocol, ProviderAdapter> = adapters.associateBy { it.protocol }

    fun forProtocol(protocol: ProviderProtocol): ProviderAdapter =
        byProtocol[protocol] ?: error("No adapter registered for $protocol")

    fun forConfig(config: ProviderConfig): ProviderAdapter = forProtocol(config.protocol)

    val registered: List<ProviderProtocol> get() = byProtocol.keys.toList()

    companion object {
        fun default(): List<ProviderAdapter> = listOf(
            OpenAiCompatibleAdapter(),
            AnthropicMessagesAdapter(),
            GeminiAdapter(),
            RawRestAdapter(),
        )
    }
}
