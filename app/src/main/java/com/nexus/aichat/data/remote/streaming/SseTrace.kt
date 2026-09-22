package com.nexus.aichat.data.remote.streaming

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory ring buffer of the last few stream frames.
 *
 * Frames are kept **redacted** and truncated: the point is to let a user see the shape of a custom
 * endpoint's envelope, not to retain their prompts in a second place. Nothing here is persisted, so a
 * process death clears it - deliberately, since a debugging aid should not become a data store.
 */
@Singleton
class SseTrace @Inject constructor() {

    data class Frame(
        val label: String,
        val payload: String,
        val atEpochMs: Long = System.currentTimeMillis(),
    )

    private val _frames = MutableStateFlow<List<Frame>>(emptyList())
    val frames: StateFlow<List<Frame>> = _frames.asStateFlow()

    fun record(label: String, payload: String) {
        val trimmed = payload.take(MAX_FRAME_CHARS).replace(SECRET_PATTERN, "\"***\"")
        _frames.update { current -> (current + Frame(label, trimmed)).takeLast(MAX_FRAMES) }
    }

    fun clear() = _frames.update { emptyList() }

    /** Best-effort guess at the JSON path holding assistant text, offered to the user as a hint. */
    fun suggestTextPath(): String? {
        val sample = frames.value.lastOrNull { it.label.contains("chat") }?.payload ?: return null
        return when {
            sample.contains("\"delta\"") && sample.contains("\"content\"") -> "choices.0.delta.content"
            sample.contains("\"token\"") -> "token.text"
            sample.contains("\"response\"") -> "response"
            sample.contains("\"text\"") -> "text"
            else -> null
        }
    }

    private companion object {
        const val MAX_FRAMES = 60
        const val MAX_FRAME_CHARS = 2_000
        val SECRET_PATTERN = Regex("\"(api[-_]?key|authorization|token)\"\\s*:\\s*\"[^\"]*\"", RegexOption.IGNORE_CASE)
    }
}
