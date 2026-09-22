package com.nexus.aichat.data.remote.api

import com.nexus.aichat.core.model.ProviderConfig
import com.nexus.aichat.core.model.ProviderProtocol

/**
 * The Anthropic Messages contract.
 *
 * Three details that break naive clients, documented here because the wizard has to explain them:
 *  - `max_tokens` is **required** (a request without it is a 400, not a default);
 *  - the API version must be pinned in a header - an unpinned client breaks when the vendor ships;
 *  - `temperature` must be exactly 1.0 while extended thinking is enabled.
 */
object AnthropicApi {

    const val CHAT_PATH = "/messages"
    const val MODELS_PATH = "/models"

    /** Pinned per the vendor's guidance; see `AnthropicMessagesAdapter` for where it is applied. */
    const val API_VERSION = "2023-06-01"
    const val API_VERSION_HEADER = "anthropic-version"

    const val DEFAULT_MAX_TOKENS = 8_192
    const val MIN_THINKING_BUDGET = 1_024

    fun headerFor(apiKey: String): Pair<String, String> = "x-api-key" to apiKey

    /** Gateways (LiteLLM, Bedrock proxies) often require an Authorization bearer *instead* of x-api-key. */
    fun bearerHeaderFor(apiKey: String): Pair<String, String> = "Authorization" to "Bearer $apiKey"

    fun chatUrl(baseUrl: String): String = ProviderConfig.joinUrl(baseUrl, CHAT_PATH)

    fun modelsUrl(baseUrl: String): String = ProviderConfig.joinUrl(baseUrl, MODELS_PATH)

    fun validateBaseUrl(baseUrl: String): String? {
        val trimmed = baseUrl.trim()
        if (trimmed.isEmpty()) return "Base URL is required"
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return "Base URL must start with http:// or https://"
        }
        if (trimmed.endsWith("/messages")) return "Use the base URL only - the app appends /messages"
        return null
    }

    /** Anthropic's own list, used as the wizard's suggestion before discovery runs. */
    val suggestedModels = listOf("claude-sonnet-4-6", "claude-opus-4-6", "claude-haiku-4-5")

    val protocol: ProviderProtocol = ProviderProtocol.ANTHROPIC_MESSAGES
}
