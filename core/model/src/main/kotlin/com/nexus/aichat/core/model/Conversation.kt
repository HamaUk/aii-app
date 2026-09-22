package com.nexus.aichat.core.model

import kotlinx.serialization.Serializable

/**
 * A chat thread. Sampling params, the active provider/model and the agent policy live here, so
 * every conversation can behave differently without touching global settings.
 */
@Serializable
data class Conversation(
    val id: String,
    val title: String = "New chat",
    val createdAtEpochMs: Long = 0,
    val updatedAtEpochMs: Long = 0,
    val providerId: String? = null,
    val modelId: String? = null,
    val systemRuleIds: List<String> = emptyList(),
    val systemPromptOverride: String? = null,
    val temperature: Double = 0.7,
    val topP: Double = 1.0,
    val maxOutputTokens: Int? = null,
    val agent: AgentOptions = AgentOptions(),
    val activeLeafMessageId: String? = null,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val totalUsage: TokenUsage = TokenUsage(),
    val lastError: String? = null,
) {
    val hasCustomInstructions: Boolean
        get() = systemPromptOverride?.isNotBlank() == true || systemRuleIds.isNotEmpty()
}

/** Conversation list row projection - avoids loading message trees for the drawer. */
@Serializable
data class ConversationSummary(
    val id: String,
    val title: String,
    val updatedAtEpochMs: Long,
    val messageCount: Int,
    val previewText: String?,
    val modelId: String?,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
)
