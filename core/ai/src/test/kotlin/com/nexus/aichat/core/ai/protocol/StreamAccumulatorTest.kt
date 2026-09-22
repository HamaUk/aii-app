package com.nexus.aichat.core.ai.protocol

import com.nexus.aichat.core.model.TokenUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StreamAccumulatorTest {

    @Test
    fun `openai style tool call fragments are reassembled in order`() {
        val accumulator = StreamAccumulator()
        // Exactly what a real OpenAI stream looks like: the id/name arrive once, then JSON fragments.
        accumulator.accept(
            ProviderStreamChunk(
                toolCallDeltas = listOf(ProviderToolCallDelta(0, id = "call_1", name = "web_fetch", argumentsFragment = "{\"ur")),
            ),
        )
        accumulator.accept(ProviderStreamChunk(toolCallDeltas = listOf(ProviderToolCallDelta(0, argumentsFragment = "l\": \"https://e"))))
        accumulator.accept(ProviderStreamChunk(toolCallDeltas = listOf(ProviderToolCallDelta(0, argumentsFragment = "xample.com\"}"))))

        val calls = accumulator.toolCallRequests()
        assertEquals(1, calls.size)
        assertEquals("call_1", calls[0].callId)
        assertEquals("web_fetch", calls[0].toolName)
        assertEquals("{\"url\": \"https://example.com\"}", calls[0].argumentsJson)
    }

    @Test
    fun `parallel tool calls are kept separate by index`() {
        val accumulator = StreamAccumulator()
        accumulator.accept(
            ProviderStreamChunk(
                toolCallDeltas = listOf(
                    ProviderToolCallDelta(0, id = "a", name = "web_search", argumentsFragment = "{\"query\":\"kotlin\"}"),
                    ProviderToolCallDelta(1, id = "b", name = "get_datetime", argumentsFragment = "{}"),
                ),
            ),
        )
        val calls = accumulator.toolCallRequests()
        assertEquals(listOf("web_search", "get_datetime"), calls.map { it.toolName })
    }

    @Test
    fun `reasoning is separated from answer text`() {
        val accumulator = StreamAccumulator()
        accumulator.accept(ProviderStreamChunk(reasoningDelta = "Let me think. "))
        accumulator.accept(ProviderStreamChunk(reasoningDelta = "First, fetch the page."))
        accumulator.accept(ProviderStreamChunk(textDelta = "The answer is 42."))

        val turn = accumulator.snapshot()
        assertEquals("Let me think. First, fetch the page.", turn.reasoning)
        assertEquals("The answer is 42.", turn.text)
    }

    @Test
    fun `gemini complete function call replaces rather than appends`() {
        val accumulator = StreamAccumulator()
        accumulator.accept(
            ProviderStreamChunk(
                toolCallDeltas = listOf(
                    ProviderToolCallDelta(0, id = "g1", name = "web_fetch", argumentsFragment = "{\"url\":\"a\"}", replaceArgs = true),
                ),
            ),
        )
        accumulator.accept(
            ProviderStreamChunk(
                toolCallDeltas = listOf(
                    ProviderToolCallDelta(0, id = "g1", name = "web_fetch", argumentsFragment = "{\"url\":\"b\"}", replaceArgs = true),
                ),
            ),
        )
        assertEquals("{\"url\":\"b\"}", accumulator.toolCallRequests().single().argumentsJson)
    }

    @Test
    fun `usage frames accumulate across the stream`() {
        val accumulator = StreamAccumulator()
        accumulator.accept(ProviderStreamChunk(usage = TokenUsage(inputTokens = 120, outputTokens = 1, totalTokens = 121)))
        accumulator.accept(ProviderStreamChunk(usage = TokenUsage(outputTokens = 40, totalTokens = 40)))
        val usage = assertNotNull(accumulator.usage)
        assertEquals(120, usage.inputTokens)
        assertEquals(41, usage.outputTokens)
    }

    @Test
    fun `empty frames are not forwarded downstream`() {
        val accumulator = StreamAccumulator()
        assertEquals(null, accumulator.accept(ProviderStreamChunk()))
        assertEquals(null, accumulator.accept(ProviderStreamChunk(textDelta = "")))
    }

    @Test
    fun `mid stream provider errors are surfaced untouched`() {
        val accumulator = StreamAccumulator()
        val error = accumulator.accept(ProviderStreamChunk(errorMessage = "overloaded"))
        assertEquals("overloaded", error?.errorMessage)
    }

    @Test
    fun `blank arguments default to an empty json object`() {
        val accumulator = StreamAccumulator()
        accumulator.accept(ProviderStreamChunk(toolCallDeltas = listOf(ProviderToolCallDelta(0, id = "x", name = "get_datetime"))))
        assertEquals("{}", accumulator.toolCallRequests().single().argumentsJson)
    }

    @Test
    fun `snapshot diff only returns genuinely new text`() {
        val accumulator = StreamAccumulator()
        accumulator.accept(ProviderStreamChunk(textDelta = "Hello "))
        accumulator.accept(ProviderStreamChunk(textDelta = "world"))
        assertTrue(accumulator.diffAgainstRendered("Hello ")?.contains("world") == true)
        assertEquals(null, accumulator.diffAgainstRendered("Hello world"))
    }
}
