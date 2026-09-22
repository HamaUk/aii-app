package com.nexus.aichat.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /models` envelopes.
 *
 * Three shapes exist in the wild and users hit all of them: the OpenAI list, Anthropic's `data` list
 * (same shape), Google's `models` list with `name: "models/gemini-…"`, and a bare array that some
 * self-hosted servers return. All three decode here so "Fetch models" works on a vLLM box the same way
 * it works on OpenAI.
 */
@Serializable
data class ModelListResponseDto(
    @SerialName("data") val data: List<ModelDto> = emptyList(),
    @SerialName("models") val models: List<ModelDto> = emptyList(),
    @SerialName("object") val objectType: String? = null,
) {
    /** Normalised list, with Gemini's `models/` prefix stripped. */
    val entries: List<ModelDto>
        get() = (if (models.isNotEmpty()) models else data).map { entry ->
            if (entry.id.startsWith("models/")) entry.copy(id = entry.id.removePrefix("models/")) else entry
        }
}

@Serializable
data class ModelDto(
    val id: String,
    @SerialName("object") val objectType: String? = null,
    @SerialName("owned_by") val ownedBy: String? = null,
    @SerialName("created") val created: Long? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("context_window") val contextWindow: Int? = null,
    @SerialName("max_input_tokens") val maxInputTokens: Int? = null,
    /** Gemini exposes this; other providers imply capability from the id. */
    @SerialName("supported_generation_methods") val supportedGenerationMethods: List<String> = emptyList(),
) {
    /** Google reports capability through the method list; OpenAI-compatible servers do not. */
    val supportsGenerateContent: Boolean
        get() = supportedGenerationMethods.isEmpty() || "generateContent" in supportedGenerationMethods

    val effectiveContextWindow: Int? get() = contextWindow ?: maxInputTokens
}
