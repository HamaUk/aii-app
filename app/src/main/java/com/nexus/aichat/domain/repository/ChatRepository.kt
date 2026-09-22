package com.nexus.aichat.domain.repository

import com.nexus.aichat.core.model.AgentEvent
import com.nexus.aichat.core.model.Attachment
import com.nexus.aichat.core.model.Conversation
import com.nexus.aichat.core.model.ConversationSummary
import com.nexus.aichat.core.model.Message
import com.nexus.aichat.core.model.TokenUsage
import com.nexus.aichat.domain.model.ProviderConfig
import com.nexus.aichat.domain.model.ModelInfo
import kotlinx.coroutines.flow.Flow

/**
 * The chat surface's only door to data.
 *
 * Two kinds of operation, deliberately separated:
 *  - **queries** return flows the UI collects (history, drawer, usage);
 *  - **commands** suspend, and the one expensive command - [runAgent] - returns a cold `Flow<AgentEvent>`
 *    so the caller (a ViewModel) owns cancellation, which is what makes Stop instant and correct.
 */
interface ChatRepository {

    // --- conversations ---------------------------------------------------------------------------

    fun observeConversations(): Flow<List<ConversationSummary>>

    fun observeConversation(conversationId: String): Flow<Conversation?>

    fun observeMessages(conversationId: String): Flow<List<Message>>

    /**
     * Creates a conversation. Provider and model are optional so the app can open *somewhere* useful on
     * first launch - a chat with no provider yet is a valid state (the composer explains what to do),
     * whereas a crash or a blank screen because no key is configured is not.
     */
    suspend fun createConversation(
        provider: ProviderConfig? = null,
        model: ModelInfo? = null,
        title: String? = null,
        personaRuleIds: List<String> = emptyList(),
    ): Conversation

    /** Most recently touched conversation, or null on a fresh install. */
    suspend fun latestConversation(): ConversationSummary?

    suspend fun renameConversation(conversationId: String, title: String)

    suspend fun setPinned(conversationId: String, pinned: Boolean)

    suspend fun archiveConversation(conversationId: String, archived: Boolean)

    suspend fun deleteConversation(conversationId: String)

    // --- messages --------------------------------------------------------------------------------

    /**
     * Persists the user's turn, then runs the agent and streams its events.
     *
     * Ordering guarantee: the user message is committed *before* the flow is collected, so a crash or
     * a cancel can never lose what the user typed.
     */
    fun sendMessage(
        conversationId: String,
        text: String,
        attachments: List<Attachment> = emptyList(),
    ): Flow<AgentEvent>

    /** Re-runs the last user turn, creating a sibling assistant message (branch), not an overwrite. */
    fun regenerate(conversationId: String, assistantMessageId: String): Flow<AgentEvent>

    /** Edits a user message: the edit becomes a new sibling, and its old children stay reachable. */
    suspend fun editUserMessage(conversationId: String, messageId: String, newText: String)

    suspend fun deleteMessageBranch(conversationId: String, messageId: String)

    suspend fun setActiveBranch(conversationId: String, messageId: String)

    /**
     * Binds this conversation to a provider + model, which is what the next turn will run on.
     *
     * This has to be persisted, not just held in screen state: `runAgent` resolves the model from the
     * conversation row, so a model picked in the switcher that never reaches this method means the
     * composer shows the new model while every send fails the pre-flight.
     */
    suspend fun setConversationModel(conversationId: String, providerId: String, modelId: String)

    /** Switches between sibling answers to the same prompt. */
    suspend fun selectBranch(messageId: String, direction: Int)

    // --- attachments & telemetry -------------------------------------------------------------------

    suspend fun attach(conversationId: String, attachment: Attachment)

    fun observeUsage(conversationId: String): Flow<TokenUsage>
}
