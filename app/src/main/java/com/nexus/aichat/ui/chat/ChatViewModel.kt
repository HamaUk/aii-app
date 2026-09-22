package com.nexus.aichat.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexus.aichat.core.common.logging.NexusLogger
import com.nexus.aichat.core.model.AgentEvent
import com.nexus.aichat.core.model.AgentPhase
import com.nexus.aichat.core.model.Attachment
import com.nexus.aichat.core.model.MessageRole
import com.nexus.aichat.core.model.ReasoningVisibility
import com.nexus.aichat.domain.agent.ReActEngine
import com.nexus.aichat.domain.agent.ThoughtProcessor
import com.nexus.aichat.core.ai.agent.ToolApprovalDecision
import com.nexus.aichat.domain.repository.ChatRepository
import com.nexus.aichat.domain.repository.ProviderRepository
import com.nexus.aichat.domain.repository.SettingsRepository
import com.nexus.aichat.domain.usecase.AttachFileUseCase
import com.nexus.aichat.domain.usecase.FetchModelsUseCase
import com.nexus.aichat.domain.usecase.ManageProviderUseCase
import com.nexus.aichat.domain.usecase.SendMessageUseCase
import com.nexus.aichat.ui.chat.state.ChatEffect
import com.nexus.aichat.ui.chat.state.ChatIntent
import com.nexus.aichat.ui.chat.state.ChatUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The chat screen's brain.
 *
 * The hard parts of a live agent UI, and how each is handled here:
 *
 *  - **Streaming without recomposing the world.** The live run is folded by `ThoughtProcessor` into an
 *    immutable `AgentTrace`, published as one field. The feed subscribes to `messages`; the streaming
 *    bubble subscribes to `trace`. Two different rates, two different subscriptions.
 *  - **Stop means stop.** [stop] cancels the collecting job, which cancels the HTTP stream inside the
 *    engine (Ktor cancels the socket) *and* resumes any tool parked on an approval. The repository then
 *    persists whatever text arrived, marked CANCELLED.
 *  - **Approvals and clarifications are states, not dialogs fired blind.** They arrive as events; the
 *    screen renders them as inline cards with buttons, and [resolveApproval]/[answerClarification] post
 *    the answer back to the parked run. Nothing is modal, so the user can scroll the reasoning that led
 *    to the question.
 *  - **Model switching is instant.** Changing the model updates the conversation row and the provider's
 *    selection; the next turn uses it. No re-navigation, no lost draft.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository,
    private val providerRepository: ProviderRepository,
    private val settingsRepository: SettingsRepository,
    private val sendMessage: SendMessageUseCase,
    private val manageProvider: ManageProviderUseCase,
    private val fetchModels: FetchModelsUseCase,
    private val attachFile: AttachFileUseCase,
    private val reactEngine: ReActEngine,
    private val thoughtProcessor: ThoughtProcessor,
    private val logger: NexusLogger,
) : ViewModel() {

    private val conversationId: String =
        savedStateHandle.get<String>("conversationId").orEmpty()

    private val _state = MutableStateFlow(ChatUiState(conversationId = conversationId))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val _effects = Channel<ChatEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    /** Live accumulator for the run in flight. Recreated per run so traces never leak across sends. */
    private var accumulator: ThoughtProcessor.Accumulator = thoughtProcessor.newAccumulator()
    private var runJob: Job? = null

    init {
        observeConversation()
        observeDefaults()
    }

    // --- observation --------------------------------------------------------------------------------

    private fun observeConversation() {
        if (conversationId.isBlank()) return

        viewModelScope.launch {
            chatRepository.observeConversation(conversationId)
                .combine(chatRepository.observeMessages(conversationId)) { conversation, messages -> conversation to messages }
                .collect { (conversation, messages) ->
                    _state.update { current ->
                        current.copy(
                            conversation = conversation,
                            messages = messages,
                            // Keep the drawer's model pill honest if the row changed elsewhere.
                            activeModel = conversation?.let { row ->
                                current.activeProvider?.models?.firstOrNull { model ->
                                    model.id == (row.modelId ?: current.activeProvider?.selectedModelId)
                                }
                                    ?: row.modelId?.takeIf { it.isNotBlank() }?.let { modelId ->
                                        com.nexus.aichat.core.model.ModelInfo(
                                            id = modelId,
                                            providerId = row.providerId ?: current.activeProvider?.id ?: "",
                                            source = com.nexus.aichat.core.model.ModelSource.MANUAL,
                                        )
                                    }
                            } ?: current.activeModel,
                        )
                    }
                }
        }

        viewModelScope.launch {
            providerRepository.observeEnabledProviders().collect { providers ->
                val active = providers.firstOrNull { it.id == _state.value.conversation?.providerId }
                    ?: providers.firstOrNull()
                _state.update { current ->
                    val model = active?.models?.firstOrNull { it.id == active.selectedModelId }
                        ?: active?.models?.firstOrNull()
                        ?: active?.selectedModelId?.takeIf { it.isNotBlank() }?.let { modelId ->
                            com.nexus.aichat.core.model.ModelInfo(
                                id = modelId,
                                providerId = active.id,
                                source = com.nexus.aichat.core.model.ModelSource.MANUAL,
                            )
                        }
                    current.copy(
                        providers = providers,
                        activeProvider = active,
                        activeModel = model,
                    )
                }
            }
        }

        viewModelScope.launch {
            chatRepository.observeUsage(conversationId).collect { usage ->
                _state.update { it.copy(usage = usage) }
            }
        }
    }

    private fun observeDefaults() {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _state.update { current ->
                    current.copy(
                        appearance = settings.appearance,
                        showThoughts = settings.agent.showReasoning != ReasoningVisibility.HIDDEN,
                        thoughtsExpanded = settings.agent.showReasoning != ReasoningVisibility.COLLAPSED,
                    )
                }
            }
        }
    }

    // --- intents ------------------------------------------------------------------------------------

    fun onIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.UpdateDraft -> _state.update { it.copy(draft = intent.text) }
            ChatIntent.Send -> send()
            ChatIntent.Stop -> stop()
            ChatIntent.RetryLast -> retryLast()
            is ChatIntent.Regenerate -> regenerate(intent.assistantMessageId)
            is ChatIntent.EditAndResend -> editAndResend(intent.messageId, intent.newText)
            is ChatIntent.SelectMessage -> _state.update { it.copy(selectedMessageId = intent.messageId) }
            is ChatIntent.Attach -> attach(intent.uri)
            is ChatIntent.RemoveAttachment -> _state.update { current ->
                current.copy(pendingAttachments = current.pendingAttachments.filterNot { it.id == intent.attachmentId })
            }
            ChatIntent.ClearAttachments -> _state.update { it.copy(pendingAttachments = emptyList()) }
            is ChatIntent.SwitchModel -> switchModel(intent.providerId, intent.modelId)
            is ChatIntent.FetchModels -> refreshModels(intent.providerId)
            ChatIntent.RefreshModels -> _state.value.activeProvider?.id?.let { refreshModels(it) }
            ChatIntent.OpenModelPicker -> _state.update { it.copy(modelPickerOpen = true) }
            ChatIntent.CloseModelPicker -> _state.update { it.copy(modelPickerOpen = false) }
            is ChatIntent.ApproveTool -> resolveApproval(intent.callId, intent.alwaysForRun)
            is ChatIntent.RejectTool -> resolveApproval(intent.callId, alwaysForRun = false, approve = false)
            is ChatIntent.AnswerClarification -> answerClarification(intent.requestId, intent.answer)
            ChatIntent.ToggleThoughts -> _state.update { it.copy(thoughtsExpanded = !it.thoughtsExpanded) }
            ChatIntent.DismissBanner -> _state.update { it.copy(error = null, info = null) }
            is ChatIntent.DeleteBranch -> deleteBranch(intent.messageId)
            is ChatIntent.SelectBranch -> selectBranch(intent.messageId, intent.direction)
        }
    }

    // --- sending ------------------------------------------------------------------------------------

    private fun send() {
        val current = _state.value
        if (!current.canSend || conversationId.isBlank()) return
        val text = current.draft.trim()
        val attachments = current.pendingAttachments

        // Clear the composer immediately: the user's next thought should never wait on the network.
        _state.update { it.copy(draft = "", pendingAttachments = emptyList(), error = null) }

        collectRun(sendMessage(conversationId, text, attachments))
    }

    private fun regenerate(assistantMessageId: String) {
        if (_state.value.isRunning) return
        collectRun(chatRepository.regenerate(conversationId, assistantMessageId))
    }

    private fun retryLast() {
        val lastAssistant = _state.value.messages.lastOrNull { it.role == MessageRole.ASSISTANT }
        if (lastAssistant != null) regenerate(lastAssistant.id)
    }

    private fun editAndResend(messageId: String, newText: String) {
        if (_state.value.isRunning) return
        viewModelScope.launch {
            // Editing rewrites the user's turn as a new branch, then immediately regenerates against it.
            chatRepository.editUserMessage(conversationId, messageId, newText)
            _effects.send(ChatEffect.ShowSnackbar("Edited - previous answer kept as a branch"))
        }
    }

    private fun deleteBranch(messageId: String) {
        viewModelScope.launch {
            chatRepository.deleteMessageBranch(conversationId, messageId)
            _state.update { it.copy(selectedMessageId = null) }
        }
    }

    private fun selectBranch(messageId: String, direction: Int) {
        viewModelScope.launch { chatRepository.selectBranch(messageId, direction) }
    }

    /**
     * The single collector for every run. Everything that distinguishes send / retry / regenerate has
     * already been decided by the repository; this only projects events into state.
     */
    private fun collectRun(flow: kotlinx.coroutines.flow.Flow<AgentEvent>) {
        runJob?.cancel()
        accumulator = thoughtProcessor.newAccumulator()

        runJob = viewModelScope.launch {
            _state.update { it.copy(trace = accumulator.snapshot(), runId = null, error = null) }
            try {
                flow.collect { event -> onEvent(event) }
            } catch (cancelled: CancellationException) {
                // User pressed Stop: leave the partial text visible, clear the running flag.
                _state.update { it.copy(trace = null, runId = null, phase = AgentPhase.IDLE) }
                throw cancelled
            } catch (failure: Throwable) {
                logger.e(TAG, "run failed", failure)
                _state.update { it.copy(trace = null, runId = null, error = failure.message ?: "The run failed") }
                _effects.send(ChatEffect.ShowSnackbar(failure.message ?: "The run failed"))
            } finally {
                runJob = null
            }
        }
    }

    private fun onEvent(event: AgentEvent) {
        val trace = accumulator.onEvent(event)
        val status = accumulator.status()

        _state.update { current ->
            current.copy(
                trace = trace,
                runId = if (event is AgentEvent.RunFinished || event is AgentEvent.RunFailed) null else event.runId,
                phase = status.phase,
                usage = if (status.usage.totalTokens > 0) status.usage else current.usage,
                awaitingApproval = trace.awaitingApproval,
                awaitingClarification = trace.awaitingClarification,
            )
        }

        when (event) {
            is AgentEvent.RunStarted -> _effects.trySend(ChatEffect.ScrollToBottom(true))
            is AgentEvent.AnswerDelta -> Unit
            is AgentEvent.ToolCallProposed -> if (event.requiresApproval) {
                _effects.trySend(ChatEffect.ShowSnackbar("${event.request.toolName} needs your approval"))
            }
            is AgentEvent.ClarificationRequested -> _effects.trySend(ChatEffect.ScrollToBottom(true))
            is AgentEvent.RunFinished -> {
                _state.update { it.copy(trace = null, runId = null, phase = AgentPhase.DONE) }
                _effects.trySend(ChatEffect.ScrollToBottom(true))
                if (event.reason.name == "MAX_STEPS_REACHED" || event.reason.name == "MAX_TOOL_CALLS_REACHED") {
                    _effects.trySend(ChatEffect.ShowSnackbar("Stopped at the step limit - send \"continue\" to carry on"))
                }
            }
            is AgentEvent.RunFailed -> {
                _state.update { it.copy(trace = null, runId = null, error = event.error.displayMessage) }
                _effects.trySend(ChatEffect.ShowSnackbar(event.error.displayMessage))
            }
            is AgentEvent.AssistantMessageFinalized -> {
                // The persisted message takes over from the live trace.
                _state.update { it.copy(trace = null, runId = null, phase = AgentPhase.DONE) }
            }
            else -> Unit
        }
    }

    fun stop() {
        val runId = _state.value.runId ?: accumulator.currentRunId
        reactEngine.cancel(runId)
        runJob?.cancel()
        runJob = null
        _state.update { it.copy(trace = null, runId = null, phase = AgentPhase.IDLE) }
        _effects.trySend(ChatEffect.ShowSnackbar("Stopped"))
    }

    // --- approvals, clarifications, attachments, models ----------------------------------------------

    private fun resolveApproval(callId: String, alwaysForRun: Boolean, approve: Boolean = true) {
        val runId = _state.value.runId ?: return
        viewModelScope.launch {
            val decision = when {
                !approve -> ToolApprovalDecision.REJECT
                alwaysForRun -> ToolApprovalDecision.APPROVE_FOR_RUN
                else -> ToolApprovalDecision.APPROVE
            }
            reactEngine.resolveApproval(runId, callId, decision)
            _state.update { it.copy(awaitingApproval = null) }
        }
    }

    private fun answerClarification(requestId: String, answer: String) {
        val runId = _state.value.runId ?: return
        viewModelScope.launch {
            val success = reactEngine.answerClarification(runId, requestId, answer)
            if (success) {
                _state.update { it.copy(awaitingClarification = null) }
            } else {
                _effects.trySend(ChatEffect.ShowSnackbar("Failed to send answer. Please try again."))
            }
        }
    }

    private fun attach(uri: String) {
        if (_state.value.pendingAttachments.size >= com.nexus.aichat.core.util.Constants.Limits.MAX_ATTACHMENTS_PER_MESSAGE) {
            _effects.trySend(ChatEffect.ShowSnackbar("Attachment limit reached"))
            return
        }
        _state.update { it.copy(isAttaching = true) }
        viewModelScope.launch {
            runCatching { attachFile(android.net.Uri.parse(uri)) }
                .onSuccess { attachment ->
                    _state.update { current ->
                        current.copy(pendingAttachments = current.pendingAttachments + attachment, isAttaching = false)
                    }
                    attachment.extractionError?.let { _effects.trySend(ChatEffect.ShowSnackbar(it)) }
                }
                .onFailure { failure ->
                    _state.update { it.copy(isAttaching = false) }
                    _effects.trySend(ChatEffect.ShowSnackbar(failure.message ?: "Could not attach that file"))
                }
        }
    }

    /**
     * Real discovery against the provider's `/models`. Failures are surfaced as an info line rather than
     * thrown: a stale catalogue is a nuisance, not a broken app.
     */
    private fun refreshModels(providerId: String) {
        if (_state.value.isRefreshingModels) return
        _state.update { it.copy(isRefreshingModels = true) }
        viewModelScope.launch {
            when (val result = manageProvider.fetchModels(providerId)) {
                is com.nexus.aichat.core.common.result.NexusResult.Success ->
                    _state.update { it.copy(isRefreshingModels = false, info = "${result.data.size} models available") }
                is com.nexus.aichat.core.common.result.NexusResult.Failure ->
                    _state.update { it.copy(isRefreshingModels = false, error = result.error.displayMessage) }
            }
        }
    }

    private fun switchModel(providerId: String, modelId: String) {
        viewModelScope.launch {
            // Two writes, both required:
            //  - the provider row remembers the selection for the next new chat;
            //  - this conversation row is what `runAgent` actually reads, so it must be updated too or
            //    the composer shows the new model while every send fails "No model selected".
            providerRepository.selectModel(providerId, modelId)
            if (conversationId.isNotBlank()) {
                chatRepository.setConversationModel(conversationId, providerId, modelId)
            }
            _state.update { current ->
                val provider = current.providers.firstOrNull { it.id == providerId } ?: current.activeProvider
                val model = provider?.models?.firstOrNull { it.id == modelId }
                    ?: com.nexus.aichat.core.model.ModelInfo(
                        id = modelId,
                        providerId = providerId,
                        source = com.nexus.aichat.core.model.ModelSource.MANUAL,
                    )
                current.copy(
                    activeProvider = provider,
                    activeModel = model,
                    info = "Switched to ${model.displayName ?: modelId}",
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // A chat screen leaving the back stack must not leave a stream running in the background.
        runJob?.cancel()
        reactEngine.cancel(accumulator.currentRunId)
    }

    private companion object {
        const val TAG = "ChatViewModel"
    }
}
