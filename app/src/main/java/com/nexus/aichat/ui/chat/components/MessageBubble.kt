package com.nexus.aichat.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexus.aichat.core.model.Attachment
import com.nexus.aichat.core.model.Message
import com.nexus.aichat.core.model.MessagePart
import com.nexus.aichat.core.model.MessageRole
import com.nexus.aichat.core.model.MessageStatus
import com.nexus.aichat.core.model.TokenUsage
import com.nexus.aichat.core.theme.NexusShapes
import com.nexus.aichat.core.theme.NexusType
import com.nexus.aichat.core.util.toClockTime
import com.nexus.aichat.domain.model.AgentTrace
import com.nexus.aichat.domain.model.MessagePresentation
import com.nexus.aichat.ui.components.HapticPressSurface
import com.nexus.aichat.ui.components.NexusHaptics

/**
 * A single chat message.
 *
 * Layout rules that make the feed read well:
 *  - the user's turn is a compact, right-aligned bubble; the assistant's is a wide, left-aligned block
 *    with no bubble around long-form content (a bordered card around a 40-line answer is a cage);
 *  - the thought tree, when present, is collapsed above the answer, and the answer is always the last
 *    thing on screen - the user reads conclusions, and reasons only on demand;
 *  - citations, token counts and the model that produced the answer sit under the text in one quiet
 *    footer line, so costs and provenance are visible without being loud;
 *  - a failed or cancelled turn is visibly *different* (error tint, partial badge), never silently
 *    presented as a complete answer.
 */
@Composable
fun MessageBubble(
    message: Message,
    modelLabel: String?,
    showThoughts: Boolean,
    thoughtsExpanded: Boolean,
    branchInfo: Pair<Int, Int>?,
    haptics: NexusHaptics,
    onToggleThoughts: () -> Unit,
    onLongPress: () -> Unit,
    onOpenImage: (Attachment) -> Unit,
    onBranchStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == MessageRole.USER
    val thoughtParts = if (showThoughts && !isUser) MessagePresentation.thoughtParts(message) else emptyList()
    val answerParts = if (isUser) message.parts.filter { it is MessagePart.Text } else MessagePresentation.answerParts(message)
    val citations = message.parts.filterIsInstance<MessagePart.Citation>()

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        if (thoughtParts.isNotEmpty()) {
            ThoughtTree(
                parts = thoughtParts,
                expanded = thoughtsExpanded,
                onToggle = onToggleThoughts,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        val bubbleShape = if (isUser) NexusShapes.userBubble() else NexusShapes.assistantBubble()
        val containerColor = if (isUser) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.8f)
        }

        HapticPressSurface(
            onLongPress = onLongPress,
            haptics = haptics,
            modifier = Modifier.widthIn(max = if (isUser) 320.dp else 600.dp),
        ) {
            Column(
                modifier = Modifier
                    .clip(bubbleShape)
                    .background(containerColor)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                message.parts.filterIsInstance<MessagePart.Image>().forEach { part ->
                    ImageAttachment(part.attachment, onOpenViewer = { onOpenImage(part.attachment) })
                }
                message.parts.filterIsInstance<MessagePart.Document>().forEach { part ->
                    FileAttachmentCard(part.attachment)
                }

                answerParts.forEach { part ->
                    when (part) {
                        is MessagePart.Text -> MarkdownRenderer(part.value)
                        is MessagePart.ClarificationRequest -> ClarificationCard(part)
                        else -> Unit
                    }
                }

                if (answerParts.isEmpty() && message.status == MessageStatus.STREAMING) {
                    Text(
                        text = "Thinking\u2026",
                        style = NexusType.thoughtTree(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                message.error?.takeIf { message.status == MessageStatus.ERROR }?.let { error ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        if (citations.isNotEmpty()) {
            CitationRow(citations)
        }

        MessageFooter(
            message = message,
            modelLabel = modelLabel,
            branchInfo = branchInfo,
            onBranchStep = onBranchStep,
        )
    }
}

/** Tree of reasoning + tool milestones, collapsed by default once the answer has landed. */
@Composable
private fun ThoughtTree(
    parts: List<MessagePart>,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        ReasoningHeader(
            expanded = expanded,
            onToggle = onToggle,
            summary = buildString {
                val tools = parts.filterIsInstance<MessagePart.ToolCall>().size
                val reasoning = parts.filterIsInstance<MessagePart.Reasoning>().size
                append("$reasoning thought")
                if (reasoning != 1) append("s")
                if (tools > 0) append(" \u00b7 $tools tool ${if (tools == 1) "call" else "calls"}")
            },
        )
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                parts.forEach { part ->
                    when (part) {
                        is MessagePart.Reasoning -> ReasoningBlock(part)
                        is MessagePart.ToolCall -> ToolMilestoneRow(part)
                        is MessagePart.ClarificationRequest -> ClarificationCard(part)
                        is MessagePart.Text -> Text(
                            text = part.value,
                            style = NexusType.thoughtTree(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageFooter(
    message: Message,
    modelLabel: String?,
    branchInfo: Pair<Int, Int>?,
    onBranchStep: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (branchInfo != null) {
            IconButton(onClick = { onBranchStep(-1) }, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Rounded.ChevronLeft, contentDescription = "Previous answer", modifier = Modifier.size(16.dp))
            }
            Text(
                text = "${branchInfo.first}/${branchInfo.second}",
                style = NexusType.timestamp(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = { onBranchStep(1) }, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Rounded.ChevronRight, contentDescription = "Next answer", modifier = Modifier.size(16.dp))
            }
        }
        Text(
            text = message.createdAtEpochMs.toClockTime(),
            style = NexusType.timestamp(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        message.usage?.takeIf { it.totalTokens > 0 }?.let { usage -> TokenChip(usage) }
        if (!modelLabel.isNullOrBlank()) {
            Text(
                text = modelLabel,
                style = NexusType.timestamp(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (message.status == MessageStatus.CANCELLED) {
            Text("stopped", style = NexusType.timestamp(), color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

/** Token + cost chip: small, monospace, and only shown when the provider actually reported usage. */
@Composable
private fun TokenChip(usage: TokenUsage) {
    val text = buildString {
        append(usage.totalTokens)
        append(" tok")
        usage.estimatedCostUsd?.takeIf { it > 0 }?.let { cost -> append(" \u00b7 $${"%.4f".format(cost)}") }
    }
    Text(
        text = text,
        style = NexusType.tokenCounter(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun CitationRow(citations: List<MessagePart.Citation>) {
    Column(
        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        citations.take(6).forEach { citation ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("\u2197", style = NexusType.timestamp(), color = MaterialTheme.colorScheme.tertiary)
                Text(
                    text = citation.title ?: citation.url,
                    style = NexusType.timestamp(),
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The live bubble for a run in flight.
 *
 * Rendered from the `AgentTrace` rather than from a message, because the message does not exist until
 * the engine finalises it. This is what makes streaming look continuous: the tree grows, the answer
 * streams, and not one row in the persisted feed changes.
 */
@Composable
fun LiveAssistantBubble(
    trace: AgentTrace,
    expanded: Boolean,
    onToggle: () -> Unit,
    onApprove: (String, Boolean) -> Unit,
    onReject: (String) -> Unit,
    onAnswerClarification: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ThinkingTreeView(
            trace = trace,
            expanded = expanded,
            onToggle = onToggle,
            onApprove = onApprove,
            onReject = onReject,
            onAnswerClarification = onAnswerClarification,
        )

        if (trace.finalAnswer.isNotBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(NexusShapes.assistantBubble())
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.8f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                MarkdownRenderer(trace.finalAnswer)
            }
        }
    }
}
