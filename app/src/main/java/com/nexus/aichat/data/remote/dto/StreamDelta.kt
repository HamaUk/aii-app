package com.nexus.aichat.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A streaming frame, typed.
 *
 * `delta` may be empty on the final frame (usage-only, when `stream_options.include_usage` is on) and
 * `tool_calls` arrive split across frames keyed by `index` - both are why this DTO exists: the
 * inspector shows the *raw* shape, and the app's own `StreamParser` decodes the tolerant version.
 */
@Serializable
data class StreamDeltaDto(
    val id: String? = null,
    val model: String? = null,
    @SerialName("created") val createdEpochSec: Long? = null,
    val choices: List<StreamChoiceDto> = emptyList(),
    val usage: UsageDto? = null,
    val error: WireErrorDto? = null,
) {
    val textDelta: String? get() = choices.firstOrNull()?.delta?.content
    val reasoningDelta: String? get() = choices.firstOrNull()?.delta?.reasoningContent
    val finishReason: String? get() = choices.firstOrNull()?.finishReason
}

@Serializable
data class StreamChoiceDto(
    val index: Int = 0,
    val delta: ResponseMessageDto? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)
