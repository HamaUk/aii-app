package com.nexus.aichat.domain.model

import com.nexus.aichat.core.model.Conversation as CoreConversation
import com.nexus.aichat.core.model.ConversationSummary as CoreConversationSummary

/**
 * The conversation model is owned by `:core:model` (it is engine input, not app state).
 *
 * This alias keeps `import com.nexus.aichat.domain.model.Conversation` valid for feature code while
 * guaranteeing there is exactly one definition of a Conversation in the process - a duplicate domain
 * model is the single most common way an app drifts out of Clean Architecture.
 */
typealias Conversation = CoreConversation
typealias ConversationSummary = CoreConversationSummary

/** Presentation-only helpers. Anything that changes persisted state belongs in ChatRepository. */
object ConversationPresentation {

    /** Drawer ordering: pinned first, then most recently touched. */
    val order: Comparator<ConversationSummary> =
        compareByDescending<ConversationSummary> { it.isPinned }.thenByDescending { it.updatedAtEpochMs }

    /** Groups the drawer by recency bucket so long histories stay navigable. */
    fun bucketOf(updatedAtEpochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val ageHours = (nowMs - updatedAtEpochMs) / 3_600_000
        return when {
            ageHours < 24 -> "Today"
            ageHours < 48 -> "Yesterday"
            ageHours < 168 -> "This week"
            ageHours < 720 -> "This month"
            else -> "Older"
        }
    }
}
