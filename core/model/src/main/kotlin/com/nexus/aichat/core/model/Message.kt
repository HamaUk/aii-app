package com.nexus.aichat.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class MessageRole { SYSTEM, USER, ASSISTANT, TOOL }

@Serializable
enum class MessageStatus {
    DRAFT,
    QUEUED,
    STREAMING,
    COMPLETE,
    CANCELLED,
    ERROR,
}

/** Lifecycle of a single tool invocation, rendered as a milestone in the thought tree. */
@Serializable
enum class ToolCallStatus { REQUESTED, AWAITING_APPROVAL, RUNNING, SUCCEEDED, FAILED, REJECTED }

@Serializable
enum class AttachmentKind { TEXT, MARKDOWN, JSON, CSV, PDF, IMAGE, AUDIO, OTHER }

/**
 * A file the user attached. [extractedText] holds the flattened representation that actually goes
 * into the context window, so re-sending a chat never re-parses the source document.
 */
@Serializable
data class Attachment(
    val id: String,
    val kind: AttachmentKind,
    val uri: String,
    val displayName: String,
    val mimeType: String? = null,
    val sizeBytes: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    val pageCount: Int? = null,
    val extractedText: String? = null,
    val extractionError: String? = null,
    val createdAtEpochMs: Long = 0,
) {
    val isImage: Boolean get() = kind == AttachmentKind.IMAGE
    val isTextual: Boolean get() = kind in setOf(AttachmentKind.TEXT, AttachmentKind.MARKDOWN, AttachmentKind.JSON, AttachmentKind.CSV)
    val humanSize: String
        get() = when {
            sizeBytes >= 1_048_576 -> "%.1f MB".format(sizeBytes / 1_048_576.0)
            sizeBytes >= 1_024 -> "%d KB".format(sizeBytes / 1_024)
            else -> "$sizeBytes B"
        }
}

/**
 * Ordered, typed content of a message. A single assistant turn can interleave reasoning, tool
 * calls and prose - rendering order is list order, which is exactly what the thought tree shows.
 */
@Serializable
sealed interface MessagePart {

    @Serializable
    data class Text(val value: String) : MessagePart

    @Serializable
    data class Reasoning(
        val text: String,
        val isStreaming: Boolean = false,
        val durationMs: Long? = null,
        val tokens: Int? = null,
        val provider: String? = null,
    ) : MessagePart

    @Serializable
    data class ToolCall(
        val callId: String,
        val toolName: String,
        val argumentsJson: String = "{}",
        val status: ToolCallStatus = ToolCallStatus.REQUESTED,
        val resultPreview: String? = null,
        val resultContent: String? = null,
        val isError: Boolean = false,
        val startedAtEpochMs: Long? = null,
        val finishedAtEpochMs: Long? = null,
        val approvalReason: String? = null,
    ) : MessagePart {
        val durationMs: Long?
            get() = if (startedAtEpochMs != null && finishedAtEpochMs != null) finishedAtEpochMs - startedAtEpochMs else null
    }

    @Serializable
    data class Image(val attachment: Attachment) : MessagePart

    @Serializable
    data class Document(val attachment: Attachment) : MessagePart

    @Serializable
    data class Citation(val url: String, val title: String? = null, val snippet: String? = null) : MessagePart

    /** The agent paused mid-run to ask the user something. [answer] is filled on resume. */
    @Serializable
    data class ClarificationRequest(val question: String, val answer: String? = null) : MessagePart
}

@Serializable
data class TokenUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val cachedInputTokens: Int = 0,
    val totalTokens: Int = 0,
    val estimatedCostUsd: Double? = null,
    val timeToFirstTokenMs: Long? = null,
    val tokensPerSecond: Double? = null,
    val providerReported: Boolean = true,
) {
    operator fun plus(other: TokenUsage) = TokenUsage(
        inputTokens = inputTokens + other.inputTokens,
        outputTokens = outputTokens + other.outputTokens,
        reasoningTokens = reasoningTokens + other.reasoningTokens,
        cachedInputTokens = cachedInputTokens + other.cachedInputTokens,
        totalTokens = (totalTokens + other.totalTokens).coerceAtLeast(inputTokens + other.inputTokens + outputTokens + other.outputTokens),
        estimatedCostUsd = listOfNotNull(estimatedCostUsd, other.estimatedCostUsd).takeIf { it.isNotEmpty() }?.sum(),
        timeToFirstTokenMs = timeToFirstTokenMs ?: other.timeToFirstTokenMs,
        tokensPerSecond = other.tokensPerSecond ?: tokensPerSecond,
    )
}

/**
 * One turn in a conversation. `parentId` + `siblingIndex` make this a *tree*: regenerating or
 * editing a prompt creates a sibling that the branch switcher in the message action bar flips
 * between, without ever destroying the previous answer.
 */
@Serializable
data class Message(
    val id: String,
    val conversationId: String,
    val role: MessageRole,
    val parts: List<MessagePart> = emptyList(),
    val parentId: String? = null,
    val siblingIndex: Int = 0,
    val status: MessageStatus = MessageStatus.COMPLETE,
    val createdAtEpochMs: Long = 0,
    val updatedAtEpochMs: Long = 0,
    val providerId: String? = null,
    val modelId: String? = null,
    val usage: TokenUsage? = null,
    val error: String? = null,
    val agentRunId: String? = null,
    val isPinned: Boolean = false,
    val finishReason: String? = null,
) {
    val plainText: String
        get() = parts.filterIsInstance<MessagePart.Text>().joinToString("\n\n") { it.value }

    val reasoningText: String
        get() = parts.filterIsInstance<MessagePart.Reasoning>().joinToString("\n\n") { it.text }

    val attachments: List<Attachment>
        get() = parts.mapNotNull {
            when (it) {
                is MessagePart.Image -> it.attachment
                is MessagePart.Document -> it.attachment
                else -> null
            }
        }

    val toolCalls: List<MessagePart.ToolCall> get() = parts.filterIsInstance<MessagePart.ToolCall>()

    val hasReasoning: Boolean get() = parts.any { it is MessagePart.Reasoning && it.text.isNotBlank() }

    val isStreaming: Boolean get() = status == MessageStatus.STREAMING

    val isConversationTitleWorthy: Boolean get() = role == MessageRole.USER && plainText.length >= 4
}

/**
 * Pure helpers over the message tree. Kept in the model layer (and unit-tested) so no ViewModel
 * has to reason about branching.
 */
object MessageTree {

    /** Walks parents from [leafId] to the root and returns the thread in chronological order. */
    fun linearize(messages: List<Message>, leafId: String?): List<Message> {
        if (leafId == null) return emptyList()
        val byId = messages.associateBy { it.id }
        val chain = ArrayDeque<Message>()
        var cursor: Message? = byId[leafId]
        val guard = HashSet<String>()
        while (cursor != null && guard.add(cursor.id)) {
            chain.addFirst(cursor)
            cursor = cursor.parentId?.let { byId[it] }
        }
        return chain.toList()
    }

    /** All direct children of [parentId], ordered by branch index (oldest first). */
    fun childrenOf(messages: List<Message>, parentId: String?): List<Message> =
        messages.filter { it.parentId == parentId }.sortedBy { it.siblingIndex }

    /** Sibling set of [messageId], used to render "2 / 3" branch chips. */
    fun siblingsOf(messages: List<Message>, messageId: String): List<Message> {
        val target = messages.firstOrNull { it.id == messageId } ?: return emptyList()
        return childrenOf(messages, target.parentId)
    }

    /** Deepest descendant of [messageId] following newest-child links - the active leaf. */
    fun activeLeafFrom(messages: List<Message>, messageId: String): Message {
        var current = messages.firstOrNull { it.id == messageId } ?: return messages.last()
        while (true) {
            val next = childrenOf(messages, current.id).maxByOrNull { it.siblingIndex } ?: return current
            current = next
        }
    }

    fun nextSiblingIndex(messages: List<Message>, parentId: String?): Int =
        (childrenOf(messages, parentId).maxOfOrNull { it.siblingIndex } ?: -1) + 1

    /** Flattened transcript for a provider call; excludes tool plumbing and drafts. */
    fun toContext(messages: List<Message>, leafId: String?): List<Message> =
        linearize(messages, leafId).filter { it.status != MessageStatus.DRAFT && it.role != MessageRole.TOOL }
}
