package com.nexus.aichat.core.ai.protocol

import com.nexus.aichat.core.common.error.AppError
import com.nexus.aichat.core.model.ModelInfo
import com.nexus.aichat.core.model.ProviderConfig
import com.nexus.aichat.core.model.ProviderProtocol
import com.nexus.aichat.core.model.SamplingOptions
import com.nexus.aichat.core.model.ToolCallRequest
import com.nexus.aichat.core.model.ToolSpec
import com.nexus.aichat.core.model.TokenUsage

// ---------------------------------------------------------------------------------------------
// Provider-agnostic request model
// ---------------------------------------------------------------------------------------------

enum class ProviderRole { SYSTEM, USER, ASSISTANT, TOOL }

sealed interface ProviderContentPart {
    data class Text(val text: String) : ProviderContentPart
    data class ImageBase64(val mimeType: String, val base64: String) : ProviderContentPart
    data class ToolResultBlock(val callId: String, val content: String, val isError: Boolean) : ProviderContentPart
}

data class ProviderMessage(
    val role: ProviderRole,
    val content: List<ProviderContentPart> = emptyList(),
    val toolCalls: List<ToolCallRequest> = emptyList(),
    val name: String? = null,
) {
    val text: String get() = content.filterIsInstance<ProviderContentPart.Text>().joinToString("\n") { it.text }
}

sealed interface ToolChoice {
    data object Auto : ToolChoice
    data object None : ToolChoice
    data object Required : ToolChoice
    data class Specific(val toolName: String) : ToolChoice
}

data class ProviderChatRequest(
    val model: String,
    val system: String?,
    val messages: List<ProviderMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val toolChoice: ToolChoice = ToolChoice.Auto,
    val sampling: SamplingOptions = SamplingOptions(),
    val streaming: Boolean = true,
)

// ---------------------------------------------------------------------------------------------
// Provider-agnostic response model
// ---------------------------------------------------------------------------------------------

/**
 * A single decoded streaming frame.
 *
 * Everything is nullable because providers only send what changed: OpenAI sends an id on the first
 * tool-call fragment only, Anthropic sends the tool name on `content_block_start` and the JSON
 * arguments as `partial_json` fragments, Gemini sends complete function calls in one part.
 */
data class ProviderStreamChunk(
    val textDelta: String? = null,
    val reasoningDelta: String? = null,
    val toolCallDeltas: List<ProviderToolCallDelta> = emptyList(),
    val usage: TokenUsage? = null,
    val finishReason: String? = null,
    val providerId: String? = null,
    val modelId: String? = null,
    /** Set when the provider emitted an error *inside* an otherwise-healthy SSE stream. */
    val errorMessage: String? = null,
) {
    val isEmpty: Boolean
        get() = textDelta.isNullOrEmpty() && reasoningDelta.isNullOrEmpty() && toolCallDeltas.isEmpty() &&
            usage == null && finishReason == null && errorMessage == null
}

data class ProviderToolCallDelta(
    val index: Int,
    val id: String? = null,
    val name: String? = null,
    /**
     * Fragment of the arguments object. Appended by default; set [replaceArgs] when the provider
     * resends the *complete* object (Gemini, and some proxies that repeat the accumulated state).
     */
    val argumentsFragment: String? = null,
    val replaceArgs: Boolean = false,
)

data class ProviderChatResponse(
    val text: String,
    val reasoning: String = "",
    val toolCalls: List<ToolCallRequest> = emptyList(),
    val usage: TokenUsage? = null,
    val finishReason: String? = null,
    val modelId: String? = null,
)

// ---------------------------------------------------------------------------------------------
// Adapter contract
// ---------------------------------------------------------------------------------------------

/**
 * Translates between the app's provider-agnostic conversation model and one wire protocol.
 *
 * Adding support for a new vendor family = one new adapter here. Adding support for a new *endpoint*
 * of an already-supported family = zero code, which is the entire point of the custom-provider flow.
 */
interface ProviderAdapter {

    val protocol: ProviderProtocol

    /** Some protocols embed the model in the path (Gemini) or switch path when streaming. */
    fun chatUrl(config: ProviderConfig, modelId: String, streaming: Boolean): String =
        ProviderConfig.joinUrl(config.baseUrl, config.chatPathOverride ?: "/chat/completions")

    /** Auth + custom headers for a chat or discovery call. Never logs the secret. */
    fun headers(config: ProviderConfig, apiKey: String?): Map<String, String>

    /** Serialises the conversation into the provider's request body. */
    fun encodeChat(request: ProviderChatRequest, config: ProviderConfig): String

    /** Decodes one SSE frame. Return null for keep-alives, pings and unknown event types. */
    fun decodeStreamEvent(eventName: String?, data: String): ProviderStreamChunk?

    /**
     * Decodes a non-streaming body (used by the health check and by non-streaming mode).
     *
     * [config] is passed because a custom endpoint's text location is configuration, not protocol:
     * `responseTextPath` points at the text inside a bespoke envelope, and without it a Raw-REST
     * provider's replies would only decode when they happen to match a known shape.
     */
    fun decodeChatResponse(body: String, config: ProviderConfig? = null): ProviderChatResponse

    /** Pulls the provider's own error envelope out, so users see "invalid_api_key", not "HTTP 401". */
    fun decodeError(status: Int, body: String, providerId: String? = null): AppError?

    // --- Model discovery ("Fetch models" + ping test) ------------------------------------------

    fun modelsUrl(config: ProviderConfig): String = ProviderConfig.joinUrl(config.baseUrl, config.modelsPath)

    /** OpenAI-compatible and Anthropic expose `/models`; providers that don't return an empty list. */
    fun decodeModels(body: String, providerId: String): List<ModelInfo> = emptyList()

    /** Fallback catalog when discovery is unavailable (discovery disabled on the provider). */
    fun fallbackModels(config: ProviderConfig): List<ModelInfo> = emptyList()
}
