package com.nexus.aichat.core.ai.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SseParserTest {

    @Test
    fun `single data line produces one event`() {
        val parser = SseParser()
        assertNull(parser.consume("data: {\"a\":1}"))
        val event = parser.consume("")
        assertEquals("{\"a\":1}", event?.data)
    }

    @Test
    fun `multi line data is joined with newline`() {
        val parser = SseParser()
        parser.consume("data: line one")
        parser.consume("data: line two")
        val event = parser.consume("")
        assertEquals("line one\nline two", event?.data)
    }

    @Test
    fun `named events and ids are captured`() {
        val parser = SseParser()
        parser.consume("event: content_block_delta")
        parser.consume("id: 42")
        parser.consume("data: {\"type\":\"text_delta\"}")
        val event = parser.consume("")
        assertEquals("content_block_delta", event?.event)
        assertEquals("42", event?.id)
        assertEquals("{\"type\":\"text_delta\"}", event?.data)
    }

    @Test
    fun `comments and keep alives are ignored`() {
        val parser = SseParser()
        assertNull(parser.consume(": ping"))
        assertNull(parser.consume(": another"))
        assertNull(parser.consume(""))
    }

    @Test
    fun `data without space after colon is parsed`() {
        // Groq and several proxies omit the optional space.
        val parser = SseParser()
        parser.consume("data:{\"x\":true}")
        assertEquals("{\"x\":true}", parser.consume("")?.data)
    }

    @Test
    fun `crlf line endings are normalised`() {
        val parser = SseParser()
        parser.consume("data: hello\r")
        assertEquals("hello", parser.consume("\r")?.data)
    }

    @Test
    fun `partial block is flushed at end of stream`() {
        val parser = SseParser()
        parser.consume("data: final chunk without blank line")
        assertTrue(parser.hasPartialBlock)
        assertEquals("final chunk without blank line", parser.flush()?.data)
        assertFalse(parser.hasPartialBlock)
    }

    @Test
    fun `retry field is parsed`() {
        val parser = SseParser()
        parser.consume("retry: 5000")
        parser.consume("data: x")
        assertEquals(5_000L, parser.consume("")?.retryMs)
    }

    @Test
    fun `done sentinel is recognised`() {
        assertTrue(SsePayloads.isDone("[DONE]"))
        assertTrue(SsePayloads.isDone(" [DONE] "))
        assertFalse(SsePayloads.isDone("{\"data\":1}"))
    }
}
