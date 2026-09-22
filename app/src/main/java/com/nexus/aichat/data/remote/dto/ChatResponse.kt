package com.nexus.aichat.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The OpenAI-compatible response envelope, as the inspector and the buffered-fallback path see it.
 *
 * Decoding is intentionally lenient: every field is optional, because a self-hosted server may omit
 * `usage`, return `content: null` on a tool turn, or nest everything under a `data` key. A missing
 * field must never be the reason a user cannot use their own endpoint.
 */
@Serializable
data class ChatResponseDto(
    val id: String? = null,
    val model: String? = null,
    val choices: List<ChoiceDto> = emptyList(),
    val usage: UsageDto? = null,
    val error: WireErrorDto? = null,
) {
    /** First non-empty assistant text, with the tool-call-only case handled. */
    val text: String
        get() = choices.firstOrNull()?.message?.content.orEmpty()

    val finishReason: String? get() = choices.firstOrNull()?.finishReason
    val reasoning: String? get() = choices.firstOrNull()?.message?.reasoningContent
}

@Serializable
data class ChoiceDto(
    val index: Int = 0,
    val message: ResponseMessageDto? = null,
    val delta: ResponseMessageDto? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ResponseMessageDto(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<WireToolCallDto> = emptyList(),
)

@Serializable
data class UsageDto(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    @SerialName("prompt_tokens_details") val promptDetails: PromptTokenDetailsDto? = null,
    @SerialName("completion_tokens_details") val completionDetails: CompletionTokenDetailsDto? = null,
)

@Serializable
data class PromptTokenDetailsDto(
    @SerialName("cached_tokens") val cachedTokens: Int = 0,
)

@Serializable
data class CompletionTokenDetailsDto(
    @SerialName("reasoning_tokens") val reasoningTokens: Int = 0,
)

@Serializable
data class WireErrorDto(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null,
)
