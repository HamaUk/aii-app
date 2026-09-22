package com.nexus.aichat.domain.model

import com.nexus.aichat.core.model.Message as CoreMessage
import com.nexus.aichat.core.model.MessagePart
import com.nexus.aichat.core.model.MessageRole
import com.nexus.aichat.core.model.MessageStatus
import com.nexus.aichat.core.model.MessageTree

typealias Message = CoreMessage

/**
 * The rendering contract of a chat message.
 *
 * A message is a *tree*: reasoning, narration, tool milestones and the final answer are all parts in
 * order. These helpers encode the one rule the UI depends on, so no composable has to re-derive it:
 *
 *   everything AFTER the last tool milestone is the answer body;
 *   everything before it belongs to the collapsible thought tree.
 */
object MessagePresentation {

    /** Index of the last tool call part, or -1 when the message never used a tool. */
    fun lastToolCallIndex(message: Message): Int =
        message.parts.indexOfLast { it is MessagePart.ToolCall }

    /** The bottom line. After a tool run, everything following the last milestone; otherwise the last prose block. */
    fun answerParts(message: Message): List<MessagePart.Text> {
        val cut = lastToolCallIndex(message)
        return if (cut >= 0) {
            message.parts.drop(cut + 1).filterIsInstance<MessagePart.Text>()
        } else {
            message.parts.filterIsInstance<MessagePart.Text>().takeLast(1)
        }
    }

    /**
     * Everything that belongs behind the "Thought process" disclosure: reasoning, tool milestones,
     * clarifications and pre-tool narration - but never the final answer.
     */
    fun thoughtParts(message: Message): List<MessagePart> {
        val cut = lastToolCallIndex(message)
        if (cut >= 0) return message.parts.take(cut + 1).filterNot { it is MessagePart.Citation }
        val answerText = message.parts.filterIsInstance<MessagePart.Text>().lastOrNull()
        return message.parts.filter { part ->
            when (part) {
                is MessagePart.Reasoning, is MessagePart.ToolCall, is MessagePart.ClarificationRequest -> true
                is MessagePart.Text -> part !== answerText
                else -> false
            }
        }
    }

    val Message.isUserInput: Boolean get() = role == MessageRole.USER
    val Message.isAssistantOutput: Boolean get() = role == MessageRole.ASSISTANT
    val Message.isStreamingNow: Boolean get() = status == MessageStatus.STREAMING
    val Message.hasThoughts: Boolean get() = hasReasoning || toolCalls.isNotEmpty()

    /** Branch chip label: "2 / 3" when a message has siblings, null otherwise. */
    fun branchLabel(siblings: List<Message>, message: Message): Pair<Int, Int>? {
        if (siblings.size <= 1) return null
        val index = siblings.indexOfFirst { it.id == message.id }
        return if (index < 0) null else (index + 1) to siblings.size
    }

    /** Deepest active leaf of the thread, used to decide which branch is displayed. */
    fun activeLeaf(messages: List<Message>, from: String): Message = MessageTree.activeLeafFrom(messages, from)
}
