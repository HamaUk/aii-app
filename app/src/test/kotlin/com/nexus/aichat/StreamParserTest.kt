package com.nexus.aichat

import com.nexus.aichat.data.remote.streaming.ChatDelta
import com.nexus.aichat.data.remote.streaming.StreamParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frame-parser tests.
 *
 * The parser exists for self-hosted endpoints whose envelope is *almost* OpenAI's. The contract under test
 * is therefore one of tolerance: unknown frames return null, never an exception, and the three shapes a real
 * server emits (text delta, reasoning delta, tool fragment) all decode.
 */
class StreamParserTest {

    @Test
    fun `openai text delta`() {
        val delta = StreamParser.parse("""{"choices":[{"delta":{"content":"Hello"}}]}""")
        assertEquals(ChatDelta.Text("Hello"), delta)
    }

    @Test
    fun `deepseek reasoning delta is separate from content`() {
        val delta = StreamParser.parse("""{"choices":[{"delta":{"reasoning_content":"thinking..."}}]}""")
        assertEquals(ChatDelta.Reasoning("thinking..."), delta)
    }

    @Test
    fun `tool call fragment decodes name and arguments`() {
        val delta = StreamParser.parse(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"web_fetch","arguments":"{\"url\":"}}]}}]}""",
        )
        val fragment = delta as ChatDelta.ToolFragment
        assertEquals("web_fetch", fragment.name)
        assertEquals("call_1", fragment.id)
    }

    @Test
    fun `usage-only frame is recognised`() {
        val delta = StreamParser.parse("""{"usage":{"prompt_tokens":10,"completion_tokens":4}}""")
        assertEquals(ChatDelta.Usage(10, 4, 0), delta)
    }

    @Test
    fun `error frame is surfaced instead of silently dropped`() {
        val delta = StreamParser.parse("""{"error":{"message":"model not found"}}""")
        assertEquals(ChatDelta.ServerError("model not found"), delta)
    }

    @Test
    fun `keep-alives and done markers`() {
        assertNull(StreamParser.parse(""))
        assertNull(StreamParser.parse(": ping"))
        assertEquals(ChatDelta.Finish("stop"), StreamParser.parse("[DONE]"))
    }

    @Test
    fun `unknown json degrades to null rather than throwing`() {
        assertNull(StreamParser.parse("""{"weird":{"shape":true}}"""))
        assertNull(StreamParser.parse("not json at all"))
        assertTrue(StreamParser.isJson("""{"a":1}"""))
    }

    @Test
    fun `alternate envelopes are tolerated`() {
        assertEquals(ChatDelta.Text("hi"), StreamParser.parse("""{"response":"hi"}"""))
        assertEquals(ChatDelta.Text("hi"), StreamParser.parse("""{"token":{"text":"hi"}}"""))
    }
}
