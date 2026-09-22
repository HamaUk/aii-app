package com.nexus.aichat.data.repository

import com.nexus.aichat.core.ai.agent.AgentOrchestrator
import com.nexus.aichat.core.ai.util.TokenEstimator
import com.nexus.aichat.core.common.di.NexusDispatchers
import com.nexus.aichat.core.common.error.AppError
import com.nexus.aichat.core.common.logging.NexusLogger
import com.nexus.aichat.core.common.time.TimeProvider
import com.nexus.aichat.core.model.AgentEvent
import com.nexus.aichat.core.model.AgentRunRequest
import com.nexus.aichat.core.model.Attachment
import com.nexus.aichat.core.model.Conversation
import com.nexus.aichat.core.model.ConversationSummary
import com.nexus.aichat.core.model.Message
import com.nexus.aichat.core.model.MessagePart
import com.nexus.aichat.core.model.MessageRole
import com.nexus.aichat.core.model.MessageStatus
import com.nexus.aichat.core.model.SamplingOptions
import com.nexus.aichat.core.model.SystemPromptComposer
import com.nexus.aichat.core.model.TokenUsage
import com.nexus.aichat.data.local.datastore.SettingsDataStore
import com.nexus.aichat.data.local.db.dao.AttachmentDao
import com.nexus.aichat.data.local.db.dao.ConversationDao
import com.nexus.aichat.data.local.db.dao.MessageDao
import com.nexus.aichat.data.local.db.dao.TelemetryDao
import com.nexus.aichat.data.local.db.entity.ToolInvocationEntity
import com.nexus.aichat.data.local.db.entity.UsageMetricEntity
import com.nexus.aichat.data.local.mapper.Mappers.toDomain
import com.nexus.aichat.data.local.mapper.Mappers.toEntity
import com.nexus.aichat.data.remote.streaming.ProviderStreamAdapter
import com.nexus.aichat.domain.agent.ToolRegistry
import com.nexus.aichat.domain.repository.ChatRepository
import com.nexus.aichat.domain.repository.ProviderRepository
import com.nexus.aichat.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Conversations, messages, branching - and the place where the agent harness meets the database.
 *
 * Three rules this class exists to enforce:
 *
 *  1. **Never lose the user's turn.** [sendMessage] commits the user message *before* the first network
 *     call, and every assistant update is written through as it arrives. Killing the app mid-stream
 *     leaves a message marked `CANCELLED` with the text received so far - not an empty bubble.
 *  2. **The engine is pure.** `AgentOrchestrator` cannot touch Room (it lives in a JVM-only module), so
 *     persistence of assistant output, tool invocations and token metrics is the repository's job,
 *     driven off the event stream. That keeps the loop unit-testable and this class mechanical.
 *  3. **A retry is a branch, not an overwrite.** Regenerating produces a sibling message with a higher
 *     `siblingIndex`; the old answer stays reachable, because "that was better, go back" is a real need.
 */
@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val attachmentDao: AttachmentDao,
    private val telemetryDao: TelemetryDao,
    private val providerRepository: ProviderRepository,
    private val settingsRepository: SettingsRepository,
    private val settings: SettingsDataStore,
    private val orchestrator: AgentOrchestrator,
    private val toolRegistry: ToolRegistry,
    private val streamAdapter: ProviderStreamAdapter,
    private val time: TimeProvider,
    private val dispatchers: NexusDispatchers,
    private val logger: NexusLogger,
) : ChatRepository {

    /** Telemetry + titles run off the send path so a slow title call never delays a delete. */
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)

    // --- queries -----------------------------------------------------------------------------------

    override fun observeConversations(): Flow<List<ConversationSummary>> =
        conversationDao.observeSummaries().map { rows -> rows.map { it.toSummary() } }

    override fun observeConversation(conversationId: String): Flow<Conversation?> =
        conversationDao.observeById(conversationId).map { entity -> entity?.toDomain() }

    override fun observeMessages(conversationId: String): Flow<List<Message>> =
        messageDao.observeByConversation(conversationId).map { rows -> rows.map { it.toDomain() } }

    // --- conversation lifecycle ---------------------------------------------------------------------

    override suspend fun latestConversation(): ConversationSummary? = withContext(dispatchers.io) {
        // observeSummaries() already excludes archived chats; `first()` gives the newest by updatedAt.
        conversationDao.observeSummaries().first().firstOrNull()?.toSummary()
    }

    override suspend fun createConversation(
        provider: com.nexus.aichat.domain.model.ProviderConfig?,
        model: com.nexus.aichat.domain.model.ModelInfo?,
        title: String?,
        personaRuleIds: List<String>,
    ): Conversation = withContext(dispatchers.io) {
        val now = time.nowEpochMillis()
        val conversation = Conversation(
            id = UUID.randomUUID().toString(),
            title = title?.takeIf { it.isNotBlank() } ?: "New chat",
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            providerId = provider?.id,
            modelId = model?.id,
            systemRuleIds = personaRuleIds,
            temperature = 0.7,
            topP = 1.0,
            agent = settings.current.agent.toOptions(),
        )
        conversationDao.upsert(conversation.toEntity())
        conversation
    }

    override suspend fun renameConversation(conversationId: String, title: String) = withContext(dispatchers.io) {
        conversationDao.rename(conversationId, title.trim().take(120).ifBlank { "New chat" }, time.nowEpochMillis())
    }

    override suspend fun setPinned(conversationId: String, pinned: Boolean) = withContext(dispatchers.io) {
        conversationDao.setPinned(conversationId, pinned)
    }

    override suspend fun archiveConversation(conversationId: String, archived: Boolean) = withContext(dispatchers.io) {
        conversationDao.setArchived(conversationId, archived)
    }

    override suspend fun deleteConversation(conversationId: String) = withContext(dispatchers.io) {
        // Messages, attachments and metrics go with it via ON DELETE CASCADE. Any live run for this
        // conversation is cancelled by the ViewModel that owns it (it holds the runId).
        conversationDao.delete(conversationId)
    }

    // --- sending ------------------------------------------------------------------------------------

    override fun sendMessage(
        conversationId: String,
        text: String,
        attachments: List<Attachment>,
    ): Flow<AgentEvent> = flow {
        val conversation = requireConversation(conversationId)
        val parentId = conversation.activeLeafMessageId
        val now = time.nowEpochMillis()

        val parts = buildList {
            text.takeIf { it.isNotBlank() }?.let { add(MessagePart.Text(it)) }
            attachments.forEach { attachment ->
                add(
                    if (attachment.isImage) {
                        MessagePart.Image(attachment)
                    } else {
                        MessagePart.Document(attachment)
                    },
                )
            }
        }

        val userMessage = Message(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = MessageRole.USER,
            parts = parts,
            parentId = parentId,
            siblingIndex = nextSiblingIndex(parentId),
            status = MessageStatus.COMPLETE,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )

        // Commit first, stream second: everything after this point is recoverable.
        messageDao.upsert(userMessage.toEntity())
        attachments.forEach { attachment ->
            attachmentDao.upsert(attachment.toEntity(conversationId, userMessage.id))
        }
        conversationDao.setActiveLeaf(conversationId, userMessage.id, now)

        val history = messageDao.byConversation(conversationId)
            .map { it.toDomain() }
            .filter { it.id != userMessage.id && it.status != MessageStatus.DRAFT }

        emitAll(runAgent(conversation, userMessage, history))
    }.flowOn(dispatchers.io)

    override fun regenerate(conversationId: String, assistantMessageId: String): Flow<AgentEvent> = flow {
        val conversation = requireConversation(conversationId)
        val target = messageDao.byId(assistantMessageId)?.toDomain()
            ?: error("Message $assistantMessageId not found")

        // The user turn being answered is the parent; regenerating must not duplicate it.
        val userMessage = target.parentId?.let { parentId -> messageDao.byId(parentId)?.toDomain() }
            ?: messageDao.byConversation(conversationId)
                .map { it.toDomain() }
                .lastOrNull { it.role == MessageRole.USER }
            ?: error("Nothing to regenerate")

        val history = messageDao.byConversation(conversationId)
            .map { it.toDomain() }
            .filter { it.id != userMessage.id && it.createdAtEpochMs <= userMessage.createdAtEpochMs }

        emitAll(runAgent(conversation, userMessage, history, branchFrom = target.id))
    }.flowOn(dispatchers.io)

    /**
     * The one place a run is assembled. Both a fresh send and a regenerate funnel through here so the
     * system-prompt composition, tool gating and persistence cannot drift between the two paths.
     */
    private fun runAgent(
        conversation: Conversation,
        userMessage: Message,
        history: List<Message>,
        branchFrom: String? = null,
    ): Flow<AgentEvent> = flow {
        val provider = conversation.providerId?.let { providerRepository.provider(it) }
        // `selectedModel` does a list lookup by ID. When the user picks a model via the pill
        // before model-discovery has persisted the full list (or after a models-JSON wipe), the
        // lookup returns null even though `selectedModelId` is correctly stored. Fall back to a
        // minimal stub so the run is not blocked — the API only needs the string ID, not metadata.
        val model = provider?.selectedModel
            ?: provider?.selectedModelId?.takeIf { it.isNotBlank() }?.let { id ->
                com.nexus.aichat.core.model.ModelInfo(
                    id = id,
                    providerId = provider.id,
                    source = com.nexus.aichat.core.model.ModelSource.MANUAL,
                )
            }
            ?: conversation.modelId?.takeIf { it.isNotBlank() }?.let { id ->
                com.nexus.aichat.core.model.ModelInfo(
                    id = id,
                    providerId = provider?.id ?: conversation.providerId ?: "",
                    source = com.nexus.aichat.core.model.ModelSource.MANUAL,
                )
            }
        if (provider == null || model == null) {
            emit(
                AgentEvent.RunFailed(
                    runId = "preflight",
                    error = AppError.Provider("No model selected. Pick a provider and model to continue."),
                ),
            )
            return@flow
        }

        val rules = settingsRepository.effectiveRules(conversation.systemRuleIds)
        val persona = SystemPromptComposer.compose(
            SystemPromptComposer.Sources(
                // The harness contract is added by AgentPromptBuilder in the engine, which knows the
                // tool protocol; this is only the user's own instructions.
                harnessContract = null,
                globalRules = rules.filter { it.appliesGlobally },
                conversationRules = rules.filterNot { it.appliesGlobally },
                conversationOverride = conversation.systemPromptOverride,
                toolInstructions = null,
            ),
        ).ifBlank { null }

        val options = toolRegistry.optionsFor(model, conversation.agent)
        val request = AgentRunRequest(
            runId = UUID.randomUUID().toString(),
            conversationId = conversation.id,
            provider = provider,
            model = model,
            history = history,
            userMessage = userMessage,
            personaSystemPrompt = persona,
            options = options,
            sampling = SamplingOptions(
                temperature = conversation.temperature,
                topP = conversation.topP,
                maxOutputTokens = conversation.maxOutputTokens,
                reasoningBudgetTokens = if (options.autoEnableReasoning && model.supportsReasoning) {
                    options.defaultReasoningBudgetTokens
                } else {
                    null
                },
            ),
            streaming = true,
        )

        val assistantId = UUID.randomUUID().toString()
        val startedAt = time.nowEpochMillis()
        var accumulatedText = StringBuilder()
        var accumulatedReasoning = StringBuilder()
        var finalized = false

        /**
         * The orchestrator reports the *cumulative* run usage twice - once as
         * [AgentEvent.UsageUpdated] before the message is finalised and once on
         * [AgentEvent.RunFinished] - and both carry the same totals. `usage_metrics` has an
         * auto-generated primary key, so an `INSERT OR IGNORE` cannot catch that duplicate, and
         * recording both would double every conversation's token count and cost. One row per
         * assistant message is also what the events describe, so dedupe on the id.
         */
        var usageRecorded = false

        orchestrator.run(request)
            .onEach { event ->
                when (event) {
                    // The engine hands over its finished message; we own the id and the tree position.
                    is AgentEvent.AssistantMessageFinalized -> {
                        finalized = true
                        persistAssistant(
                            conversation = conversation,
                            message = event.message.copy(
                                id = assistantId,
                                conversationId = conversation.id,
                                parentId = userMessage.id,
                                siblingIndex = if (branchFrom != null) nextSiblingIndex(userMessage.id) else 0,
                                status = MessageStatus.COMPLETE,
                            ),
                            partial = false,
                        )
                    } else -> Unit
                }

                when (event) {
                    is AgentEvent.AnswerDelta -> accumulatedText.append(event.text)
                    is AgentEvent.AnswerRevised -> {
                        accumulatedText = StringBuilder(event.fullText)
                    }
                    is AgentEvent.ThoughtDelta -> accumulatedReasoning.append(event.text)
                    is AgentEvent.ToolCallFinished -> recordToolInvocation(conversation.id, event)
                    is AgentEvent.ToolCallProposed -> recordToolProposal(conversation.id, event)
                    else -> Unit
                }

                if (event is AgentEvent.UsageUpdated && !usageRecorded) {
                    usageRecorded = true
                    recordUsage(conversation, assistantId, event.usage, model.id, provider.id)
                }

                if (event is AgentEvent.RunFinished) {
                    if (!usageRecorded) {
                        usageRecorded = true
                        recordUsage(conversation, assistantId, event.usage, model.id, provider.id)
                    }
                    conversationDao.setActiveLeaf(conversation.id, assistantId, time.nowEpochMillis())
                }
            }
            .collect { event -> emit(event) }

        // Streaming ended without a finalised message: a cancel or a mid-stream failure. Keep the text
        // that was actually received rather than discarding a half-written answer.
        if (!finalized) {
            val fallback = Message(
                id = assistantId,
                conversationId = conversation.id,
                role = MessageRole.ASSISTANT,
                parts = buildList {
                    accumulatedReasoning.takeIf { it.isNotEmpty() }?.let { add(MessagePart.Reasoning(it.toString())) }
                    accumulatedText.takeIf { it.isNotEmpty() }?.let { add(MessagePart.Text(it.toString())) }
                },
                parentId = userMessage.id,
                siblingIndex = if (branchFrom != null) nextSiblingIndex(userMessage.id) else 0,
                status = if (accumulatedText.isEmpty()) MessageStatus.ERROR else MessageStatus.CANCELLED,
                createdAtEpochMs = startedAt,
                updatedAtEpochMs = time.nowEpochMillis(),
                providerId = provider.id,
                modelId = model.id,
                agentRunId = request.runId,
                error = if (accumulatedText.isEmpty()) "The run ended before any text arrived." else null,
            )
            persistAssistant(conversation, fallback, partial = true)
        } else {
            conversationDao.setActiveLeaf(conversation.id, assistantId, time.nowEpochMillis())
        }

        maybeNameConversation(conversation, provider, model, userMessage, accumulatedText.toString())
    }.flowOn(dispatchers.io)

    // --- branching & edits --------------------------------------------------------------------------

    override suspend fun editUserMessage(conversationId: String, messageId: String, newText: String) =
        withContext(dispatchers.io) {
            val original = messageDao.byId(messageId)?.toDomain() ?: return@withContext
            val now = time.nowEpochMillis()
            // An edit is an alternative user turn, not a mutation: the old branch keeps its answers.
            val edited = original.copy(
                id = UUID.randomUUID().toString(),
                parts = listOf(MessagePart.Text(newText)) + original.parts.filterNot { it is MessagePart.Text },
                siblingIndex = nextSiblingIndex(original.parentId),
                status = MessageStatus.COMPLETE,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
            )
            messageDao.upsert(edited.toEntity())
            conversationDao.setActiveLeaf(conversationId, edited.id, now)
        }

    override suspend fun deleteMessageBranch(conversationId: String, messageId: String) = withContext(dispatchers.io) {
        // Deleting a message deletes the subtree that hangs off it (via ON DELETE CASCADE), then the
        // conversation is re-pointed at whatever leaf survives - never at a row that no longer exists.
        messageDao.delete(messageId)
        val leaf = messageDao.byConversation(conversationId).map { it.toDomain() }.lastOrNull()?.id
        conversationDao.setActiveLeaf(conversationId, leaf, time.nowEpochMillis())
    }

    override suspend fun setActiveBranch(conversationId: String, messageId: String) = withContext(dispatchers.io) {
        conversationDao.setActiveLeaf(conversationId, messageId, time.nowEpochMillis())
    }

    override suspend fun setConversationModel(conversationId: String, providerId: String, modelId: String) =
        withContext(dispatchers.io) {
            conversationDao.setProviderAndModel(conversationId, providerId, modelId, time.nowEpochMillis())
        }

    override suspend fun selectBranch(messageId: String, direction: Int) = withContext(dispatchers.io) {
        val message = messageDao.byId(messageId)?.toDomain() ?: return@withContext
        val siblings = messageDao.childrenOf(message.parentId ?: "").map { it.toDomain() }
            .ifEmpty { return@withContext }
            .sortedBy { it.siblingIndex }
        val index = siblings.indexOfFirst { it.id == messageId }.coerceAtLeast(0)
        val next = siblings[(index + direction).mod(siblings.size)]
        conversationDao.setActiveLeaf(message.conversationId, next.id, time.nowEpochMillis())
    }

    override suspend fun attach(conversationId: String, attachment: Attachment) = withContext(dispatchers.io) {
        attachmentDao.upsert(attachment.toEntity(conversationId, null))
    }

    override fun observeUsage(conversationId: String): Flow<TokenUsage> =
        telemetryDao.observeConversationTotals(conversationId).map { row ->
            TokenUsage(
                inputTokens = row.inputTokens,
                outputTokens = row.outputTokens,
                reasoningTokens = row.reasoningTokens,
                totalTokens = row.totalTokens,
                estimatedCostUsd = row.costUsd.takeIf { it > 0 },
            )
        }

    // --- persistence helpers ------------------------------------------------------------------------

    private suspend fun persistAssistant(conversation: Conversation, message: Message, partial: Boolean) {
        messageDao.upsert(message.toEntity())
        conversationDao.setActiveLeaf(conversation.id, message.id, time.nowEpochMillis())
        if (partial) logger.w(TAG, "persisted partial assistant message ${message.id} (${message.status})")
    }

    private suspend fun recordToolProposal(conversationId: String, event: AgentEvent.ToolCallProposed) {
        telemetryDao.recordToolInvocation(
            ToolInvocationEntity(
                runId = event.runId,
                conversationId = conversationId,
                messageId = null,
                toolName = event.request.toolName,
                argumentsJson = event.request.argumentsJson,
                resultPreview = null,
                isError = false,
                wasApprovedByUser = event.requiresApproval,
                startedAtEpochMs = time.nowEpochMillis(),
                finishedAtEpochMs = null,
                durationMs = null,
            ),
        )
    }

    private suspend fun recordToolInvocation(conversationId: String, event: AgentEvent.ToolCallFinished) {
        telemetryDao.recordToolInvocation(
            ToolInvocationEntity(
                runId = event.runId,
                conversationId = conversationId,
                messageId = null,
                toolName = event.result.toolName,
                argumentsJson = "{}",
                resultPreview = event.result.preview.take(500),
                isError = event.result.isError,
                wasApprovedByUser = false,
                startedAtEpochMs = time.nowEpochMillis() - event.result.durationMs,
                finishedAtEpochMs = time.nowEpochMillis(),
                durationMs = event.result.durationMs,
            ),
        )
    }

    private suspend fun recordUsage(
        conversation: Conversation,
        messageId: String,
        usage: TokenUsage,
        modelId: String,
        providerId: String,
    ) {
        if (usage.totalTokens == 0 && usage.inputTokens == 0 && usage.outputTokens == 0) return
        telemetryDao.recordUsage(
            UsageMetricEntity(
                conversationId = conversation.id,
                messageId = messageId,
                providerId = providerId,
                modelId = modelId,
                inputTokens = usage.inputTokens,
                outputTokens = usage.outputTokens,
                reasoningTokens = usage.reasoningTokens,
                totalTokens = usage.totalTokens,
                estimatedCostUsd = usage.estimatedCostUsd,
                timeToFirstTokenMs = usage.timeToFirstTokenMs,
                tokensPerSecond = usage.tokensPerSecond,
                providerReported = usage.providerReported,
                createdAtEpochMs = time.nowEpochMillis(),
            ),
        )
        val totals = conversation.totalUsage + usage
        conversationDao.upsert(conversation.copy(totalUsage = totals, updatedAtEpochMs = time.nowEpochMillis()).toEntity())
    }

    /**
     * Names a conversation after its first exchange - off the critical path, and only when the user has
     * not already named it. A failed title is a non-event: the first message still says what the chat is
     * about.
     */
    private fun maybeNameConversation(
        conversation: Conversation,
        provider: com.nexus.aichat.domain.model.ProviderConfig,
        model: com.nexus.aichat.domain.model.ModelInfo,
        userMessage: Message,
        answer: String,
    ) {
        if (conversation.title != "New chat" || answer.isBlank()) return
        scope.launch {
            runCatching {
                val title = streamAdapter.suggestTitle(provider, model, userMessage.plainText, answer)
                if (title is com.nexus.aichat.core.common.result.NexusResult.Success) {
                    val cleaned = title.data.trim()
                    if (cleaned.isNotBlank()) renameConversation(conversation.id, cleaned)
                }
            }.onFailure { if (it !is CancellationException) logger.w(TAG, "title generation failed: ${it.message}") }
        }
    }

    private suspend fun requireConversation(conversationId: String): Conversation =
        conversationDao.byId(conversationId)?.toDomain() ?: error("Conversation $conversationId not found")

    /**
     * `parentId = NULL` compares false in SQL, so a root message gets `COALESCE(MAX(...), -1) = -1` and
     * therefore sibling index 0. That is the intended behaviour, not a fallthrough.
     */
    private suspend fun nextSiblingIndex(parentId: String?): Int = messageDao.maxSiblingIndex(parentId) + 1

    /**
     * Rough context size for the composer's live token counter. Deliberately local: a per-keystroke
     * counter must never cost a network round trip.
     */
    suspend fun estimateContextTokens(conversationId: String): Int = withContext(dispatchers.default) {
        val chars = messageDao.byConversation(conversationId).sumOf { it.partsJson.length }
        TokenEstimator.estimateMessages(promptChars = chars)
    }

    private companion object {
        const val TAG = "ChatRepository"
    }
}

/** Maps the drawer projection without loading message trees. */
private fun com.nexus.aichat.data.local.db.dao.ConversationSummaryRow.toSummary(): ConversationSummary =
    ConversationSummary(
        id = id,
        title = title,
        updatedAtEpochMs = updatedAtEpochMs,
        messageCount = messageCount,
        previewText = null,
        modelId = modelId,
        isPinned = isPinned,
    )
