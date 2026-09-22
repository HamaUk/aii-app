package com.nexus.aichat.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexus.aichat.core.model.MessagePart
import com.nexus.aichat.core.model.ToolCallStatus
import com.nexus.aichat.core.theme.ChatColors
import com.nexus.aichat.core.theme.NexusType
import com.nexus.aichat.domain.model.AgentStep
import com.nexus.aichat.domain.model.AgentTrace
import com.nexus.aichat.domain.model.ToolPresentation

/**
 * The thought tree - this project's version of Claude's extended thinking and DeepSeek R1's reasoning
 * pane.
 *
 * Two renderings, one visual language:
 *  - [ThinkingTreeView] renders a **live** run from an [AgentTrace] (grows as tokens stream);
 *  - the `ReasoningBlock` / `ToolMilestoneRow` / `ReasoningHeader` trio renders a **settled** run from
 *    persisted [MessagePart]s. Both share these composables so a finished answer looks exactly like the
 *    live one that produced it - no visual "jump" when the run finalises.
 *
 * The chain is always legible, in the order the agent actually worked:
 *   [Thinking] -> [Tool invocation] -> [Observation] -> [Thinking] -> [Answer].
 */
@Composable
fun ThinkingTreeView(
    trace: AgentTrace,
    expanded: Boolean,
    onToggle: () -> Unit,
    onApprove: (String, Boolean) -> Unit,
    onReject: (String) -> Unit,
    onAnswerClarification: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (trace.steps.isEmpty() && !trace.isRunning) return

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Always show clarification card outside the thought tree
        trace.awaitingClarification?.let { clarification ->
            ClarificationCard(
                request = MessagePart.ClarificationRequest(clarification.question, clarification.answer),
                requestId = clarification.id,
                onAnswer = onAnswerClarification,
            )
        }

        ReasoningHeader(
            expanded = expanded,
            onToggle = onToggle,
            summary = summarise(trace),
            isStreaming = trace.isRunning,
        )

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(120)) + expandVertically(tween(180)),
            exit = fadeOut(tween(80)) + shrinkVertically(tween(140)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                trace.steps.forEach { step ->
                    when (step) {
                        is AgentStep.Thinking -> ReasoningBlock(
                            part = MessagePart.Reasoning(
                                text = step.text,
                                isStreaming = step.isStreaming,
                                durationMs = step.durationMs,
                                tokens = step.tokens,
                            ),
                        )
                        is AgentStep.ToolInvocation -> ToolMilestoneRow(
                            part = MessagePart.ToolCall(
                                callId = step.callId,
                                toolName = step.toolName,
                                argumentsJson = step.argumentsJson,
                                status = step.status,
                                resultPreview = step.resultPreview,
                                resultContent = step.resultContent,
                                isError = step.isError,
                                startedAtEpochMs = step.startedAtEpochMs,
                                finishedAtEpochMs = step.durationMs?.let { step.startedAtEpochMs + it },
                                approvalReason = step.approvalReason,
                            ),
                            onApprove = { always -> onApprove(step.callId, always) },
                            onReject = { onReject(step.callId) },
                        )
                        is AgentStep.Narration -> Text(
                            text = step.text,
                            style = NexusType.thoughtTree(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        is AgentStep.Clarification -> Unit // Already shown outside the tree
                        is AgentStep.Answer -> Unit // rendered as the answer body, not in the tree
                    }
                }
            }
        }
    }
}

private fun summarise(trace: AgentTrace): String = buildString {
    val thoughts = trace.steps.count { it is AgentStep.Thinking }
    append("$thoughts thought")
    if (thoughts != 1) append("s")
    if (trace.toolCallCount > 0) {
        append(" \u00b7 ${trace.toolCallCount} tool ${if (trace.toolCallCount == 1) "call" else "calls"}")
    }
    trace.thinkingTokens.takeIf { it > 0 }?.let { append(" \u00b7 $it tok") }
}

/**
 * The one-line disclosure header. While a run is live it pulses and says what the agent is doing, which
 * is the single most important signal in an agentic app: the user must never wonder whether it is
 * thinking or stuck.
 */
@Composable
fun ReasoningHeader(
    expanded: Boolean,
    onToggle: () -> Unit,
    summary: String,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "reasoning")
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f))
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Psychology,
            contentDescription = null,
            modifier = Modifier.size(16.dp).alpha(if (isStreaming) pulse else 1f),
            tint = ChatColors.reasoningGutter,
        )
        Text(
            text = if (isStreaming) "Reasoning \u00b7 $summary" else summary,
            style = NexusType.thoughtTree(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        Icon(
            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = if (expanded) "Collapse thoughts" else "Expand thoughts",
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A reasoning block, with a left gutter that ties it to the rest of the trace.
 *
 * Long traces are clamped to a scrollable window rather than dumped into the feed: a model can emit
 * thousands of lines of thinking, and the feed must stay scrollable at 60fps regardless.
 */
@Composable
fun ReasoningBlock(part: MessagePart.Reasoning, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .width(2.dp)
                .heightIn(min = 20.dp)
                .background(ChatColors.reasoningGutter.copy(alpha = 0.5f), RoundedCornerShape(2.dp)),
        )
        Column {
            Text(
                text = part.text.trim(),
                style = NexusType.thoughtTree(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
            )
            val meta = buildString {
                part.durationMs?.let { append("%.1fs".format(it / 1000.0)) }
                part.tokens?.let { append(if (isNotEmpty()) " \u00b7 " else ""); append("$it tok") }
                if (part.isStreaming) append(if (isNotEmpty()) " \u00b7 " else ""); append("streaming\u2026")
            }
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = NexusType.timestamp(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/**
 * One tool invocation, as a milestone.
 *
 * Everything the user needs to judge whether to trust the answer is here in one row: what was called,
 * with what arguments, whether it succeeded, how long it took, and - on tap - the full output. Approval
 * buttons appear inline, on the milestone itself, because a modal dialog would hide the reasoning that
 * motivated the call.
 */
@Composable
fun ToolMilestoneRow(
    part: MessagePart.ToolCall,
    modifier: Modifier = Modifier,
    onApprove: ((Boolean) -> Unit)? = null,
    onReject: (() -> Unit)? = null,
) {
    var showOutput by remember(part.callId) { mutableStateOf(false) }
    val awaiting = part.status == ToolCallStatus.AWAITING_APPROVAL

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.4f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (part.status) {
                ToolCallStatus.RUNNING -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                )
                else -> Icon(
                    imageVector = Icons.Rounded.Build,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = when {
                        part.isError -> MaterialTheme.colorScheme.error
                        part.status == ToolCallStatus.SUCCEEDED -> ChatColors.success
                        awaiting -> ChatColors.warning
                        else -> ChatColors.toolChip
                    },
                )
            }
            Text(
                text = part.toolName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (part.argumentsJson != "{}" && part.argumentsJson.isNotBlank()) {
                Text(
                    text = ToolPresentation.prettyArguments(part.argumentsJson),
                    style = NexusType.timestamp(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .horizontalScroll(rememberScrollState()),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = buildString {
                    append(ToolPresentation.statusLabel(part.status))
                    part.durationMs?.takeIf { it > 0 }?.let { append(" \u00b7 ${it}ms") }
                },
                style = NexusType.timestamp(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        part.resultPreview?.takeIf { it.isNotBlank() }?.let { preview ->
            Text(
                text = preview,
                style = NexusType.thoughtTree(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (showOutput) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { showOutput = !showOutput },
            )
            val fullOutput = part.resultContent
            if (fullOutput != null && fullOutput.length > preview.length) {
                TextButton(onClick = { showOutput = !showOutput }) {
                    Text(if (showOutput) "Show less" else "Show full output", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        if (part.isError) {
            Text(
                text = "This tool failed. The agent was told and continued.",
                style = NexusType.timestamp(),
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (awaiting && onApprove != null && onReject != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onApprove(false) }) { Text("Allow") }
                OutlinedButton(onClick = { onApprove(true) }) { Text("Allow for this run") }
                TextButton(onClick = onReject) { Text("Skip") }
            }
            part.approvalReason?.let { reason ->
                Text(reason, style = NexusType.timestamp(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * The `ask_user` card. The agent stopping to ask a question is a feature, not a failure: it is what stops
 * a run from confidently answering the wrong question.
 */
@Composable
fun ClarificationCard(
    request: MessagePart.ClarificationRequest,
    modifier: Modifier = Modifier,
    requestId: String? = null,
    onAnswer: ((String, String) -> Unit)? = null,
) {
    var draft by remember(request.question) { mutableStateOf("") }
    val answer = request.answer

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.HelpOutline,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Question from the agent",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = request.question,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        when {
            !answer.isNullOrBlank() -> {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Your answer:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = answer,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            onAnswer != null && requestId != null -> {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("Type your answer here...") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { if (draft.isNotBlank()) onAnswer(requestId, draft.trim()) },
                        enabled = draft.isNotBlank(),
                    ) { Text("Send Answer") }
                    TextButton(onClick = { onAnswer(requestId, "You decide - use your best judgement.") }) {
                        Text("Let AI Decide")
                    }
                }
            }
        }
    }
}

/** Elapsed-time chip used by the status row above the composer. */
@Composable
fun ElapsedChip(startedAtEpochMs: Long, modifier: Modifier = Modifier) {
    var tick by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(startedAtEpochMs) {
        while (true) {
            kotlinx.coroutines.delay(1_000)
            tick++
        }
    }
    val elapsed = (System.currentTimeMillis() - startedAtEpochMs).coerceAtLeast(0)
    Text(
        text = "${elapsed / 1000}s",
        style = NexusType.timestamp(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
