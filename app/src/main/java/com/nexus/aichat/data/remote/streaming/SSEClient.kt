package com.nexus.aichat.data.remote.streaming

import com.nexus.aichat.core.ai.transport.ChatTransport
import com.nexus.aichat.core.ai.transport.HttpRequestSpec
import com.nexus.aichat.core.ai.transport.SseEvent
import com.nexus.aichat.core.ai.transport.SsePayloads
import com.nexus.aichat.core.common.logging.NexusLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Frame-level view of a stream, for the parts of the app that need raw frames rather than typed events.
 *
 * The agent path never comes through here: `AgentOrchestrator` -> `ChatEngine` -> `ChatTransport`
 * decodes frames already. This exists for the two things that genuinely need the wire:
 *
 *  1. the **provider inspector**, which shows a custom endpoint's actual frames (the fastest way for a
 *     user to diagnose a self-hosted server that returns a slightly different envelope);
 *  2. `raw rest` providers whose streaming envelope the adapters do not recognise, where the app falls
 *     back to "show me the frames and I will tell you the path" ([SseTrace]).
 *
 * Every frame is recorded into [SseTrace] so the inspector can render a session after the fact - which
 * matters because a failed stream is exactly when you cannot reproduce it.
 */
@Singleton
class SSEClient @Inject constructor(
    private val transport: ChatTransport,
    private val trace: SseTrace,
    private val logger: NexusLogger,
) {

    fun frames(
        request: HttpRequestSpec,
        connectTimeoutMs: Long = 20_000,
        readTimeoutMs: Long = 0,
    ): Flow<SseEvent> = transport.stream(request, connectTimeoutMs, readTimeoutMs)
        .onEach { event -> trace.record(request.label, event.data) }

    /**
     * Follows a stream until the server closes it, yielding each frame's `data:` payload.
     * `[DONE]` is swallowed here - only the inspector wants to see it.
     */
    fun dataOnly(
        request: HttpRequestSpec,
        connectTimeoutMs: Long = 20_000,
        readTimeoutMs: Long = 0,
    ): Flow<String> = flow {
        logger.d(TAG, "streaming ${request.label} -> ${request.url}")
        frames(request, connectTimeoutMs, readTimeoutMs).collect { event ->
            if (!SsePayloads.isDone(event.data)) emit(event.data)
        }
    }

    private companion object {
        const val TAG = "SSEClient"
    }
}
