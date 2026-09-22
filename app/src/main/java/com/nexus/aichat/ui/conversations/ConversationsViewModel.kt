package com.nexus.aichat.ui.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexus.aichat.core.model.ConversationSummary
import com.nexus.aichat.domain.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Search, pin and delete state for the conversation drawer. */
data class ConversationsUiState(
    val query: String = "",
    val showArchived: Boolean = false,
)

@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(ConversationsUiState())
    val ui: StateFlow<ConversationsUiState> = _ui.asStateFlow()

    /**
     * The full list, unfiltered. Filtering happens in the screen, in memory: a chat list is small, and a
     * database round trip per keystroke is not a trade worth making.
     */
    val conversations: StateFlow<List<ConversationSummary>> = chatRepository.observeConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onQueryChange(query: String) = _ui.update { it.copy(query = query) }

    fun toggleArchived() = _ui.update { it.copy(showArchived = !it.showArchived) }

    fun rename(conversationId: String, title: String) {
        viewModelScope.launch { chatRepository.renameConversation(conversationId, title) }
    }

    fun setPinned(conversationId: String, pinned: Boolean) {
        viewModelScope.launch { chatRepository.setPinned(conversationId, pinned) }
    }

    fun archive(conversationId: String, archived: Boolean) {
        viewModelScope.launch { chatRepository.archiveConversation(conversationId, archived) }
    }

    fun delete(conversationId: String) {
        viewModelScope.launch { chatRepository.deleteConversation(conversationId) }
    }
}
