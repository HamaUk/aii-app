package com.nexus.aichat.data.remote.api

import com.nexus.aichat.core.model.ProviderConfig
import com.nexus.aichat.core.model.ProviderProtocol

/**
 * The OpenAI-compatible wire contract, in one place.
 *
 * Most of the eleven presets - Groq, DeepSeek, Mistral, OpenRouter, Together, Fireworks, xAI, vLLM,
 * LM Studio, Ollama and llama.cpp - speak this. Keeping the paths, headers and body shape here means
 * the custom-provider wizard can prefill them and the inspector can label traffic without a single
 * `if (provider == "openai")` anywhere else.
 *
 * Note what is *not* here: no client, no HTTP call. `:core:ai`'s adapters own the request; this is the
 * typed description of the protocol, which is what the UI needs.
 */
object OpenAIApi {

    const val CHAT_PATH = "/chat/completions"
    const val MODELS_PATH = "/models"

    /** Servers that reject `stream_options.include_usage` (older ollama, some proxies). */
    private val NO_USAGE_STREAM_OPTIONS = listOf("localhost", "127.0.0.1", "10.0.2.2", "192.168.")

    fun headerFor(apiKey: String): Pair<String, String> = "Authorization" to "Bearer $apiKey"

    fun chatUrl(baseUrl: String): String = ProviderConfig.joinUrl(baseUrl, CHAT_PATH)

    fun modelsUrl(baseUrl: String): String = ProviderConfig.joinUrl(baseUrl, MODELS_PATH)

    /**
     * A base URL is valid for this protocol when it is absolute and, for a local server, not pointing at
     * the emulator's own loopback - the single most common cause of "why does my Ollama not connect".
     */
    fun validateBaseUrl(baseUrl: String): String? {
        val trimmed = baseUrl.trim()
        if (trimmed.isEmpty()) return "Base URL is required"
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return "Base URL must start with http:// or https://"
        }
        if (trimmed.endsWith("/chat/completions")) return "Use the base URL only - the app appends /chat/completions"
        return null
    }

    fun suggestionsFor(baseUrl: String): List<String> = buildList {
        add("gpt-4o-mini")
        if (NO_USAGE_STREAM_OPTIONS.any { baseUrl.contains(it) }) {
            // Local servers: models are whatever the user has pulled, so discovery is the only truth.
            add("qwen3:8b")
            add("llama3.3")
        } else {
            add("gpt-5.2")
        }
    }

    val protocol: ProviderProtocol = ProviderProtocol.OPENAI_COMPATIBLE
}
