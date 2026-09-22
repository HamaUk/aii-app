package com.nexus.aichat.domain.usecase

import com.nexus.aichat.core.model.AgentEvent
import com.nexus.aichat.core.model.Attachment
import com.nexus.aichat.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Sends a user turn and streams the agent's work back.
 *
 * A use case rather than a repository call because there is a real pre-flight policy here: reject
 * empty sends, cap attachments, and normalise text-embedded URLs into attachments the file reader can
 * see. The ViewModel then knows only "send this, render that flow".
 */
class SendMessageUseCase @Inject constructor(
    private val chatRepository: ChatRepository,
) {

    operator fun invoke(
        conversationId: String,
        text: String,
        attachments: List<Attachment> = emptyList(),
    ): Flow<AgentEvent> {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty() || attachments.isNotEmpty()) { "Nothing to send" }

        val capped = attachments.take(com.nexus.aichat.core.util.Constants.Limits.MAX_ATTACHMENTS_PER_MESSAGE)
        return chatRepository.sendMessage(conversationId, trimmed, capped)
    }
}
