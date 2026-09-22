package com.nexus.aichat.ui.chat.state

import com.nexus.aichat.core.model.AgentPhase
import com.nexus.aichat.core.model.AppearanceSettings
import com.nexus.aichat.core.model.Attachment
import com.nexus.aichat.core.model.Conversation
import com.nexus.aichat.core.model.Message
import com.nexus.aichat.core.model.TokenUsage
import com.nexus.aichat.domain.model.AgentStep
import com.nexus.aichat.domain.model.AgentTrace
import com.nexus.aichat.domain.model.ModelInfo
import com.nexus.aichat.domain.model.ProviderConfig

/**
 * Everything the chat screen renders.
 *
 * One immutable object rather than a dozen flows, for a reason that shows up in performance rather than
 * in taste: the feed, the composer, the status chip and the drawer all change at different rates. A
 * single state object with `distinctUntilChanged` per-field lets each composable subscribe to exactly
 * the slice it draws, so streaming a token does not recompose the model picker or the top bar.
 *
 * The live run is *not* part of [messages]: it is [trace], rendered as a pinned bubble at the end of the
 * feed. Persisted history and in-flight output therefore never alias, which is what makes Stop
 * lossless - the persisted list is simply not touched until the run finalises.
 */
data class ChatUiState(
    val conversationId: String = "",
    val conversation: Conversation? = null,
    val messages: List<Message> = emptyList(),
    val providers: List<ProviderConfig> = emptyList(),
    val activeProvider: ProviderConfig? = null,
    val activeModel: ModelInfo? = null,

    /** Live run: thoughts, tool milestones, streaming answer. Null when nothing is running. */
    val trace: AgentTrace? = null,
    val runId: String? = null,
    val phase: AgentPhase = AgentPhase.IDLE,
    val usage: TokenUsage = TokenUsage(),

    /** Composer. */
    val draft: String = "",
    val pendingAttachments: List<Attachment> = emptyList(),
    val isAttaching: Boolean = false,

    /** Interaction gates. */
    val awaitingApproval: AgentStep.ToolInvocation? = null,
    val awaitingClarification: AgentStep.Clarification? = null,
    val error: String? = null,
    val info: String? = null,

    /** Thought tree presentation. */
    val thoughtsExpanded: Boolean = true,
    val modelPickerOpen: Boolean = false,
    val isRefreshingModels: Boolean = false,
    val showThoughts: Boolean = true,
    val selectedMessageId: String? = null,
    val appearance: AppearanceSettings = AppearanceSettings(),
) {
    val isRunning: Boolean get() = trace?.isRunning == true || runId != null
    val canSend: Boolean get() = (draft.isNotBlank() || pendingAttachments.isNotEmpty()) && !isRunning
    val hasConversation: Boolean get() = conversationId.isNotBlank()
    val isEmpty: Boolean get() = messages.isEmpty() && trace == null

    /** The composer's model pill label, e.g. "GPT-5.2" or "Pick a model". */
    val modelLabel: String get() = activeModel?.displayName ?: activeProvider?.selectedModelId ?: "Pick a model"

    val providerLabel: String get() = activeProvider?.displayName.orEmpty()
}

/**
 * User intent. MVI rather than a dozen callbacks: the ViewModel has one entry point, which makes
 * "what can this screen do?" answerable by reading one sealed interface.
 */
sealed interface ChatIntent {
    data class UpdateDraft(val text: String) : ChatIntent
    data object Send : ChatIntent
    data object Stop : ChatIntent
    data object RetryLast : ChatIntent
    data class Regenerate(val assistantMessageId: String) : ChatIntent
    data class EditAndResend(val messageId: String, val newText: String) : ChatIntent
    data class SelectMessage(val messageId: String?) : ChatIntent
    data class Attach(val uri: String) : ChatIntent
    data class RemoveAttachment(val attachmentId: String) : ChatIntent
    data object ClearAttachments : ChatIntent
    data class SwitchModel(val providerId: String, val modelId: String) : ChatIntent
    data class FetchModels(val providerId: String) : ChatIntent
    data object RefreshModels : ChatIntent
    data object OpenModelPicker : ChatIntent
    data object CloseModelPicker : ChatIntent
    data class ApproveTool(val callId: String, val alwaysForRun: Boolean) : ChatIntent
    data class RejectTool(val callId: String) : ChatIntent
    data class AnswerClarification(val requestId: String, val answer: String) : ChatIntent
    data object ToggleThoughts : ChatIntent
    data object DismissBanner : ChatIntent
    data class DeleteBranch(val messageId: String) : ChatIntent
    data class SelectBranch(val messageId: String, val direction: Int) : ChatIntent
}

/** One-shot effects: things that must happen once and not survive recomposition. */
sealed interface ChatEffect {
    data class ShowSnackbar(val message: String) : ChatEffect
    data class ScrollToBottom(val animate: Boolean) : ChatEffect
    data object FocusComposer : ChatEffect
    data object OpenConversationDrawer : ChatEffect
}
