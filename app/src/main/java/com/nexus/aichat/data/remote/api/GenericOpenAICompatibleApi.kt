package com.nexus.aichat.data.remote.api

import com.nexus.aichat.core.model.ModelCapability
import com.nexus.aichat.core.model.ProviderConfig
import com.nexus.aichat.core.model.ProviderProtocol

/**
 * The "anything else" path: a self-hosted Ollama, LM Studio, vLLM, llama.cpp, a corporate LiteLLM
 * gateway or a private proxy.
 *
 * This object answers the two questions a user actually has when adding such an endpoint:
 *  - *what do I type for the base URL?* - [guessFromHost] recognises the ports these servers ship on;
 *  - *will my key be sent correctly?* - [previewHeaders] shows the exact headers the app will send,
 *    because "bearer or x-api-key?" is the single most common misconfiguration on a gateway.
 */
object GenericOpenAICompatibleApi {

    /** Port -> what is almost certainly running there. */
    private val KNOWN_PORTS = mapOf(
        11_434 to "Ollama",
        1_234 to "LM Studio",
        8_000 to "vLLM",
        8_080 to "llama.cpp / text-generation-webui",
        4_000 to "LiteLLM proxy",
        5_000 to "LocalAI",
    )

    fun guessFromHost(host: String): String? {
        val port = host.substringAfterLast(':', "").toIntOrNull() ?: return null
        return KNOWN_PORTS[port]
    }

    /**
     * Suggests a base URL, including the `/v1` suffix that OpenAI-compatible servers expect.
     *
     * `10.0.2.2` is the Android emulator's alias for the host machine - worth being explicit about,
     * since `localhost` inside the emulator is the emulator itself.
     */
    fun suggestBaseUrl(host: String, isEmulator: Boolean): String {
        val trimmed = host.trim().trimEnd('/')
        val withAlias = if (isEmulator) trimmed.replace("localhost", "10.0.2.2") else trimmed
        return if (withAlias.endsWith("/v1")) withAlias else "$withAlias/v1"
    }

    fun validateBaseUrl(baseUrl: String): String? {
        val trimmed = baseUrl.trim()
        if (trimmed.isEmpty()) return "Base URL is required"
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return "Base URL must start with http:// or https://"
        }
        if (!trimmed.startsWith("https://") && trimmed.contains("://") && trimmed.contains(".com")) {
            return "Public endpoints should use https:// - a plain http:// call would send your key in clear text"
        }
        return null
    }

    /** Exactly what will be sent, so the user can compare against their gateway's docs. */
    fun previewHeaders(config: ProviderConfig, apiKey: String?): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        put("Accept", "text/event-stream")
        when (config.auth.scheme) {
            com.nexus.aichat.core.model.AuthScheme.NONE -> Unit
            com.nexus.aichat.core.model.AuthScheme.BEARER ->
                put("Authorization", "Bearer ${apiKey?.masked() ?: "<no key stored>"}")
            com.nexus.aichat.core.model.AuthScheme.X_API_KEY ->
                put("x-api-key", apiKey?.masked() ?: "<no key stored>")
            com.nexus.aichat.core.model.AuthScheme.X_GOOG_API_KEY ->
                put("x-goog-api-key", apiKey?.masked() ?: "<no key stored>")
            com.nexus.aichat.core.model.AuthScheme.CUSTOM_HEADER ->
                put(config.auth.headerName ?: "Authorization", apiKey?.masked() ?: "<no key stored>")
            com.nexus.aichat.core.model.AuthScheme.QUERY_PARAM ->
                put("(query)", "${config.auth.queryParamName ?: "key"}=… (appended to the URL)")
        }
        putAll(config.extraHeaders)
    }

    /**
     * Capabilities for a model the user typed by hand. Heuristics only - the ids below are the ones the
     * open-weight ecosystem actually ships - and everything stays editable.
     */
    fun inferCapabilities(modelId: String): Set<ModelCapability> {
        val id = modelId.lowercase()
        val caps = mutableSetOf(ModelCapability.TEXT, ModelCapability.STREAMING)
        if (id.contains("vl") || id.contains("vision") || id.contains("llava") || id.contains("pixtral")) {
            caps += ModelCapability.VISION
        }
        if (id.contains("r1") || id.contains("qwq") || id.contains("reason") || id.contains("thinking")) {
            caps += ModelCapability.REASONING
        }
        // Tool calling is a coin flip across the GGUF ecosystem; assume yes and let the ping prove it.
        caps += ModelCapability.TOOL_CALLING
        return caps
    }

    private fun String.masked(): String = when {
        length <= 8 -> "••••"
        else -> "${take(4)}••••${takeLast(4)}"
    }

    val protocol: ProviderProtocol = ProviderProtocol.OPENAI_COMPATIBLE
}
