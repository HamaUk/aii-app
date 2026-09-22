package com.nexus.aichat.core.ai.transport

import com.nexus.aichat.core.common.error.AppError
import com.nexus.aichat.core.common.logging.NexusLogger
import com.nexus.aichat.core.common.logging.Redaction
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Ktor-backed transport running on OkHttp (one connection pool, HTTP/2, transparent gzip).
 *
 * Streaming design notes:
 *  - `preparePost(...).execute { }` keeps the body *unbuffered*, so tokens reach the UI as they arrive
 *    instead of after the provider closes the response.
 *  - per-request read timeout defaults to infinite for streams: reasoning models (R1, o-series,
 *    Gemini thinking) routinely stay silent for 20-60s before the first token, and a client-side
 *    timeout there would look like a hang to the user.
 *  - cancelling the collecting coroutine closes the response body. That is what makes the Stop
 *    button actually abort in-flight generation instead of merely hiding it (and it stops billing).
 */
class KtorChatTransport(
    private val client: HttpClient,
    private val logger: NexusLogger,
) : ChatTransport {

    override fun stream(
        request: HttpRequestSpec,
        connectTimeoutMs: Long,
        readTimeoutMs: Long,
    ): Flow<SseEvent> = flow {
        val parser = SseParser()
        client.preparePost(request.url) {
            applySpec(request)
            timeout {
                connectTimeoutMillis = connectTimeoutMs
                requestTimeoutMillis = readTimeoutMs.takeIf { it > 0 } ?: INFINITE_TIMEOUT_MS
            }
            header(HttpHeaders.Accept, "text/event-stream")
            header(HttpHeaders.CacheControl, "no-cache")
            header("Accept-Encoding", "identity")   // some proxies mangle gzipped SSE
        }.execute { response: HttpResponse ->
            if (!response.status.isSuccess()) {
                val body = runCatching { response.bodyAsText() }.getOrElse { "" }
                logger.w(TAG, "${request.label} -> HTTP ${response.status.value}: ${Redaction.redact(body.take(600))}")
                throw HttpStatusException(
                    status = response.status.value,
                    body = body,
                    retryAfterRaw = response.headers[HttpHeaders.RetryAfter],
                )
            }
            val channel: ByteReadChannel = response.bodyAsChannel()
            while (true) {
                val line = channel.readUTF8Line() ?: break
                val event = parser.consume(line) ?: continue
                emit(event)
                if (SsePayloads.isDone(event.data)) break
            }
            // A stream that closes without a trailing blank line still carries a usable final frame.
            parser.flush()?.let { emit(it) }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun execute(
        request: HttpRequestSpec,
        connectTimeoutMs: Long,
        readTimeoutMs: Long,
    ): HttpResponseData = withDeadline(request, connectTimeoutMs, readTimeoutMs)

    private suspend fun withDeadline(
        request: HttpRequestSpec,
        connectTimeoutMs: Long,
        readTimeoutMs: Long,
    ): HttpResponseData {
        val response: HttpResponse = try {
            when (request.verb) {
                HttpVerb.GET -> client.get(request.url) {
                    applySpec(request)
                    timeout {
                        connectTimeoutMillis = connectTimeoutMs
                        requestTimeoutMillis = readTimeoutMs.takeIf { it > 0 } ?: INFINITE_TIMEOUT_MS
                    }
                }
                HttpVerb.POST -> client.post(request.url) {
                    applySpec(request)
                    timeout {
                        connectTimeoutMillis = connectTimeoutMs
                        requestTimeoutMillis = readTimeoutMs.takeIf { it > 0 } ?: INFINITE_TIMEOUT_MS
                    }
                }
            }
        } catch (t: Throwable) {
            logger.e(TAG, "${request.label} transport error: ${t.message}")
            throw t
        }
        val body = runCatching { response.bodyAsText() }.getOrElse { "" }
        return HttpResponseData(
            status = response.status.value,
            headers = response.headers.entries().associate { entry -> entry.key to entry.value.firstOrNull().orEmpty() },
            body = body,
        )
    }

    private fun HttpRequestBuilder.applySpec(request: HttpRequestSpec) {
        request.headers.forEach { (key, value) -> header(key, value) }
        request.body?.let {
            contentType(ContentType.parse(request.contentType))
            setBody(it)
        }
    }

    companion object {
        private const val TAG = "NexusTransport"
    }
}

/**
 * Thrown for non-2xx provider responses so the matching adapter can decode the provider's own error
 * envelope. [retryAfterSeconds] is parsed from the standard `retry-after` header.
 */
class HttpStatusException(
    val status: Int,
    val body: String,
    retryAfterRaw: String?,
) : Exception("HTTP $status") {

    val retryAfterSeconds: Long? = retryAfterRaw?.trim()?.toLongOrNull()

    fun toAppError(decoded: AppError?): AppError = when {
        decoded != null -> decoded
        status == 401 || status == 403 -> AppError.Auth("HTTP $status", cause = this)
        status == 402 -> AppError.RateLimited("Out of credits (HTTP 402)", cause = this)
        status == 429 -> AppError.RateLimited("HTTP 429", retryAfterSeconds, this)
        status >= 500 -> AppError.Provider("Provider unavailable (HTTP $status)", status, rawBody = body.take(2_000), cause = this)
        else -> AppError.Provider("HTTP $status", status, rawBody = body.take(2_000), cause = this)
    }
}
