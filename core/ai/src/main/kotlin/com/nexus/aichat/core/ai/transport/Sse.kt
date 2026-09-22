package com.nexus.aichat.core.ai.transport

/**
 * A single Server-Sent-Events frame, normalised across providers.
 *
 * `data` is the raw payload (still JSON for every provider we support). Multi-line `data:` fields
 * arrive here already joined with `\n`, per the EventSource spec.
 */
data class SseEvent(
    val data: String,
    val event: String? = null,
    val id: String? = null,
    val retryMs: Long? = null,
)

/**
 * Incremental SSE parser.
 *
 * Why hand-rolled instead of a library: we need per-line control to (a) stop reading the moment the
 * user taps Stop, (b) keep a partial buffer when a connection dies mid-frame, and (c) tolerate the
 * many subtly non-conformant providers (Groq sends `data:` without a space, some proxies omit the
 * blank separator, Anthropic interleaves `event:` + `data:` + `: ping` comments).
 *
 * Feed it lines; it returns one event per completed block (or null while buffering).
 */
class SseParser {

    private val dataBuffer = StringBuilder()
    private var eventName: String? = null
    private var lastId: String? = null
    private var retryMs: Long? = null

    /** @return a completed [SseEvent], or null if the block is still being assembled. */
    fun consume(rawLine: String): SseEvent? {
        // Normalise line endings defensively (CRLF -> LF, stray CR).
        val line = rawLine.removeSuffix("\r").removeSuffix("\n")

        if (line.isEmpty()) return flush()

        // Comment / keep-alive: ": ping" and friends. Ignored, but they reset the idle watchdog.
        if (line.startsWith(":")) return null

        val colon = line.indexOf(':')
        if (colon < 0) {
            // Field with no value; per spec the whole line is the field name with empty value.
            return consumeField(line, "")
        }
        val field = line.substring(0, colon)
        var value = line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)   // exactly one optional space
        return consumeField(field, value)
    }

    private fun consumeField(field: String, value: String): SseEvent? = when (field) {
        "data" -> {
            if (dataBuffer.isNotEmpty()) dataBuffer.append('\n')
            dataBuffer.append(value)
            null
        }
        "event" -> { eventName = value; null }
        "id" -> { if (!value.contains('\u0000')) lastId = value; null }
        "retry" -> { value.toLongOrNull()?.let { retryMs = it }; null }
        else -> null   // unknown fields are ignored per spec
    }

    /** Emits the buffered block. Called on a blank line and at end-of-stream. */
    fun flush(): SseEvent? {
        if (dataBuffer.isEmpty() && eventName == null) return null
        val event = SseEvent(
            data = dataBuffer.toString(),
            event = eventName,
            id = lastId,
            retryMs = retryMs,
        )
        dataBuffer.clear()
        eventName = null
        return event
    }

    /** True when bytes are buffered but no terminating blank line arrived (stream died mid-frame). */
    val hasPartialBlock: Boolean get() = dataBuffer.isNotEmpty() || eventName != null

    fun reset() {
        dataBuffer.clear()
        eventName = null
    }
}

/** Helpers shared by every adapter. */
object SsePayloads {

    const val DONE = "[DONE]"

    fun isDone(data: String): Boolean = data.trim() == DONE

    /** Providers that keep the socket warm with `: ping` comments or empty data frames. */
    fun isKeepAlive(data: String): Boolean = data.isBlank() || data.trim() == ":"
}
