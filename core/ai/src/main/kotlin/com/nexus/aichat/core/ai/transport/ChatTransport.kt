package com.nexus.aichat.core.ai.transport

import kotlinx.coroutines.flow.Flow

enum class HttpVerb { GET, POST }

/**
 * A transport-agnostic HTTP request. Deliberately not a Ktor/OkHttp type: the whole engine can be
 * re-pointed at a different stack (or a fake) without touching a single adapter.
 */
data class HttpRequestSpec(
    val verb: HttpVerb,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val contentType: String = "application/json",
    /** Free-form label for logs/telemetry, e.g. "openai.chat.stream". */
    val label: String = "request",
)

data class HttpResponseData(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: String,
) {
    val isSuccess: Boolean get() = status in 200..299
}

/**
 * The one and only way Nexus talks to a model provider - whether that provider came from the preset
 * catalog or from the custom-endpoint builder, and whether it is a hosted vendor, a vLLM box on the
 * LAN or a private proxy.
 */
interface ChatTransport {

    /** Opens an SSE stream and emits one [SseEvent] per frame. Cancelling the collector aborts the socket. */
    fun stream(
        request: HttpRequestSpec,
        connectTimeoutMs: Long = 20_000,
        readTimeoutMs: Long = 0,      // 0 == no read timeout: long generations must not be cut off
    ): Flow<SseEvent>

    /** Buffered request/response, for discovery, health checks and non-streaming completions. */
    suspend fun execute(
        request: HttpRequestSpec,
        connectTimeoutMs: Long = 20_000,
        readTimeoutMs: Long = 120_000,
    ): HttpResponseData
}
