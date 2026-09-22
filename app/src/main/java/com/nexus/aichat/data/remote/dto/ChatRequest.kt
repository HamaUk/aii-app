package com.nexus.aichat.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTOs.
 *
 * Why these exist in `:app` when `:core:ai` already speaks every protocol: they are **not** on the
 * request path. They are the typed view used by the request inspector - the panel that shows a user
 * exactly what the app sent to their custom endpoint. Having a typed shape means the inspector can
 * pretty-print nested JSON without hand-rolled string surgery, and it doubles as documentation of the
 * OpenAI-compatible envelope the app assumes.
 *
 * Anything here that drifts from the adapters is a bug in the inspector only - never in a chat call.
 */
@Serializable
data class ChatRequestDto(
    val model: String,
    val messages: List<ChatMessageDto> = emptyList(),
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    val stream: Boolean = true,
    val tools: List<ToolDto> = emptyList(),
    @SerialName("tool_choice") val toolChoice: String? = null,
    @SerialName("stream_options") val streamOptions: StreamOptionsDto? = null,
    val system: String? = null,
)

@Serializable
data class ChatMessageDto(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<WireToolCallDto> = emptyList(),
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
)

@Serializable
data class ToolDto(
    val type: String = "function",
    val function: ToolFunctionDto,
)

@Serializable
data class ToolFunctionDto(
    val name: String,
    val description: String? = null,
    val parameters: String? = null,
)

@Serializable
data class WireToolCallDto(
    val id: String? = null,
    val type: String = "function",
    val function: WireToolFunctionCallDto? = null,
)

@Serializable
data class WireToolFunctionCallDto(
    val name: String? = null,
    val arguments: String? = null,
)

@Serializable
data class StreamOptionsDto(
    val includeUsage: Boolean = true,
)
