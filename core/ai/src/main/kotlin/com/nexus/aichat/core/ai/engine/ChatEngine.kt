package com.nexus.aichat.core.ai.engine

import com.nexus.aichat.core.ai.protocol.AccumulatedTurn
import com.nexus.aichat.core.ai.protocol.AdapterRegistry
import com.nexus.aichat.core.ai.protocol.InlineToolCallParser
import com.nexus.aichat.core.ai.protocol.ProviderChatRequest
import com.nexus.aichat.core.ai.protocol.ProviderChatResponse
import com.nexus.aichat.core.ai.protocol.StreamAccumulator
import com.nexus.aichat.core.ai.protocol.authenticatedUrl
import com.nexus.aichat.core.ai.transport.ChatTransport
import com.nexus.aichat.core.ai.transport.HttpRequestSpec
import com.nexus.aichat.core.ai.transport.HttpStatusException
import com.nexus.aichat.core.ai.transport.HttpVerb
import com.nexus.aichat.core.ai.transport.SsePayloads
import com.nexus.aichat.core.ai.util.TokenEstimator
import com.nexus.aichat.core.common.di.NexusDispatchers
import com.nexus.aichat.core.common.error.AppError
import com.nexus.aichat.core.common.error.AppException
import com.nexus.aichat.core.common.logging.NexusLogger
import com.nexus.aichat.core.common.result.NexusResult
import com.nexus.aichat.core.common.time.TimeProvider
import com.nexus.aichat.core.model.ProviderConfig
import com.nexus.aichat.core.model.TokenUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/** Everything needed for one provider round-trip. */
data class ChatCall(
    val config: ProviderConfig,
    val apiKey: String?,
    val request: ProviderChatRequest,
    val maxRetries: Int = 2,
    /** Retries are only safe while no token has been shown; after that we surface the partial text. */
    val retryBeforeFirstTokenOnly: Boolean = true,
)

/**
 * The boundary the agent harness depends on. Depending on this interface (not on `ChatEngine`)
 * is what lets the whole ReAct loop be unit-tested against a scripted fake without HTTP.
 */
interface ChatCompletionClient {
    fun stream(call: ChatCall): Flow<StreamEvent>
    suspend fun complete(call: ChatCall): NexusResult<ProviderChatResponse>
}

/**
 * Provider-agnostic chat engine.
 *
 * Responsibilities, in order of how much they matter:
 *  1. pick the right [com.nexus.aichat.core.ai.protocol.ProviderAdapter] for the configured protocol;
 *  2. emit a normalised [StreamEvent] sequence, including live reasoning tokens;
 *  3. account for usage even when the provider stays silent, and measure time-to-first-token;
 *  4. retry transient failures *before* the first token, never after (no duplicated text);
 *  5. recover tool calls that a model emitted as text.
 */
class ChatEngine(
    private val transport: ChatTransport,
    private val adapters: AdapterRegistry,
    private val clock: TimeProvider,
    private val dispatchers: NexusDispatchers,
    private val logger: NexusLogger,
) : ChatCompletionClient {

    override fun stream(call: ChatCall): Flow<StreamEvent> = flow {
        val adapter = adapters.forConfig(call.config)
        val url = call.config.authenticatedUrl(
            adapter.chatUrl(call.config, call.request.model, streaming = true),
            call.apiKey,
        )
        val spec = HttpRequestSpec(
            verb = HttpVerb.POST,
            url = url,
            headers = adapter.headers(call.config, call.apiKey),
            body = adapter.encodeChat(call.request, call.config),
            label = "${call.config.protocol.name.lowercase()}.chat.stream",
        )

        val knownToolNames = call.request.tools.map { it.name }.toSet()
        var attempt = 0

        emit(StreamEvent.Started(clock.nowEpochMillis(), call.request.model, call.config.id))

        while (true) {
            val accumulator = StreamAccumulator()
            val startedAt = clock.elapsedMillis()
            var firstTokenAt: Long? = null
            var emittedContent = false
            var emittedChars = 0

            try {
                transport.stream(
                    request = spec,
                    connectTimeoutMs = call.config.connectTimeoutMs,
                    readTimeoutMs = 0,
                ).collect { sse ->
                    if (SsePayloads.isKeepAlive(sse.data)) return@collect

                    val chunk = adapter.decodeStreamEvent(sse.event, sse.data) ?: return@collect
                    chunk.errorMessage?.let { message ->
                        throw AppException(
                            adapter.decodeError(200, """{"error":{"message":${quote(message)}}}""")
                                ?: AppError.Provider(message, 200),
                        )
                    }

                    val delta = accumulator.accept(chunk) ?: return@collect

                    delta.textDelta?.takeIf { it.isNotEmpty() }?.let { text ->
                        firstTokenAt = firstTokenAt ?: clock.elapsedMillis()
                        emittedContent = true
                        emittedChars += text.length
                        emit(StreamEvent.TextDelta(text))
                    }
                    delta.reasoningDelta?.takeIf { it.isNotEmpty() }?.let { text ->
                        firstTokenAt = firstTokenAt ?: clock.elapsedMillis()
                        emittedContent = true
                        emit(StreamEvent.ReasoningDelta(text))
                    }
                    delta.toolCallDeltas.forEach { callDelta ->
                        emit(
                            StreamEvent.ToolCallDelta(
                                index = callDelta.index,
                                id = callDelta.id,
                                name = callDelta.name,
                                argumentsFragment = callDelta.argumentsFragment,
                            ),
                        )
                    }
                    delta.usage?.let { emit(StreamEvent.Usage(it, firstTokenAt?.minus(startedAt))) }
                }

                val durationMs = clock.elapsedMillis() - startedAt
                val turn = accumulator.snapshot()
                val finalTurn = recoverInlineToolCalls(turn, knownToolNames, emitSink = { emit(it) })
                val usage = finalTurn.usage ?: synthesizeUsage(spec.body.orEmpty(), finalTurn, firstTokenAt, startedAt)

                emit(
                    StreamEvent.Finished(
                        finishReason = finalTurn.finishReason,
                        text = finalTurn.text,
                        reasoning = finalTurn.reasoning,
                        toolCalls = finalTurn.toolCalls,
                        usage = usage,
                        durationMs = durationMs,
                        timeToFirstTokenMs = firstTokenAt?.minus(startedAt),
                    ),
                )
                return@flow
            } catch (cancellation: CancellationException) {
                // Stop button: the socket is already closed by flow cancellation. Nothing to retry.
                logger.i(TAG, "stream cancelled after $emittedChars chars")
                throw cancellation
            } catch (t: Throwable) {
                val error = mapError(t, adapter, call, emittedChars)
                val canRetry = attempt < call.maxRetries &&
                    error.isRetryable &&
                    (!call.retryBeforeFirstTokenOnly || !emittedContent)

                if (!canRetry) throw AppException(error)

                attempt++
                val backoff = backoffMillis(error, attempt)
                logger.w(TAG, "retry $attempt/${call.maxRetries} in ${backoff}ms after ${error::class.simpleName}")
                delay(backoff)
                // Loop re-opens the stream. Anything already emitted stays on screen, which is why
                // retries are gated on `emittedContent` above.
            }
        }
    }

    override suspend fun complete(call: ChatCall): NexusResult<ProviderChatResponse> = withContext(dispatchers.io) {
        val adapter = adapters.forConfig(call.config)
        val url = call.config.authenticatedUrl(
            adapter.chatUrl(call.config, call.request.model, streaming = false),
            call.apiKey,
        )
        val spec = HttpRequestSpec(
            verb = HttpVerb.POST,
            url = url,
            headers = adapter.headers(call.config, call.apiKey),
            body = adapter.encodeChat(call.request.copy(streaming = false), call.config),
            label = "${call.config.protocol.name.lowercase()}.chat.complete",
        )
        try {
            val response = transport.execute(spec, call.config.connectTimeoutMs, call.config.requestTimeoutMs)
            if (!response.isSuccess) {
                val decoded = adapter.decodeError(response.status, response.body, call.config.id)
                val error = HttpStatusException(response.status, response.body, response.headers["retry-after"]).toAppError(decoded)
                return@withContext NexusResult.Failure(error)
            }
            NexusResult.Success(adapter.decodeChatResponse(response.body, call.config))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            NexusResult.Failure(mapError(t, adapter, call, emittedChars = 0))
        }
    }

    /**
     * Models served without native tool support print their calls instead. We honour those calls and
     * tell the UI to replace the bubble text, so the user never sees raw JSON as the answer.
     */
    private suspend fun recoverInlineToolCalls(
        turn: AccumulatedTurn,
        knownToolNames: Set<String>,
        emitSink: suspend (StreamEvent) -> Unit,
    ): AccumulatedTurn {
        if (turn.toolCalls.isNotEmpty() || knownToolNames.isEmpty() || turn.text.isBlank()) return turn

        val extracted = InlineToolCallParser.extract(turn.text, knownToolNames)
        if (!extracted.found) return turn

        logger.i(TAG, "recovered ${extracted.calls.size} inline tool call(s) from model text")
        emitSink(StreamEvent.TextRevised(extracted.cleanedText, reason = "tool_calls_recovered"))
        return turn.copy(text = extracted.cleanedText, toolCalls = extracted.calls)
    }

    private fun synthesizeUsage(
        requestBody: String,
        turn: AccumulatedTurn,
        firstTokenAt: Long?,
        startedAt: Long,
    ): TokenUsage {
        val usage = TokenEstimator.synthesize(
            inputChars = requestBody.length,
            outputChars = turn.text.length,
            reasoningChars = turn.reasoning.length,
        )
        val generationMs = firstTokenAt?.let { clock.elapsedMillis() - it }
        val tokensPerSecond = generationMs?.takeIf { it > 250 }?.let { ms -> usage.outputTokens / (ms / 1000.0) }
        return usage.copy(
            timeToFirstTokenMs = firstTokenAt?.minus(startedAt),
            tokensPerSecond = tokensPerSecond?.takeIf { it.isFinite() && it > 0 },
        )
    }

    private fun mapError(
        throwable: Throwable,
        adapter: com.nexus.aichat.core.ai.protocol.ProviderAdapter,
        call: ChatCall,
        emittedChars: Int,
    ): AppError = when (throwable) {
        is AppException -> throwable.error
        is HttpStatusException -> throwable.toAppError(
            adapter.decodeError(throwable.status, throwable.body, call.config.id),
        )
        else -> {
            val base = AppError.from(throwable)
            // If we already streamed tokens, a hard failure is a *partial* answer, not a total loss.
            if (emittedChars > 0 && base !is AppError.Cancelled) {
                AppError.StreamInterrupted(base.message ?: "Stream interrupted", partialText = null, cause = throwable)
            } else {
                base
            }
        }
    }

    private fun backoffMillis(error: AppError, attempt: Int): Long {
        (error as? AppError.RateLimited)?.retryAfterSeconds?.let { return it * 1_000L }
        return (500L shl (attempt - 1)).coerceAtMost(8_000L)
    }

    private fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        private const val TAG = "ChatEngine"
    }
}
