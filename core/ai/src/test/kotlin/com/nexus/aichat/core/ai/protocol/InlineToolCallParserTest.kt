package com.nexus.aichat.core.ai.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InlineToolCallParserTest {

    private val known = setOf("web_fetch", "web_search", "get_datetime")

    @Test
    fun `hermes style envelope is extracted and stripped`() {
        val text = """
            I will look that up.
            <tool_call>
            {"name": "web_fetch", "arguments": {"url": "https://example.com"}}
            </tool_call>
        """.trimIndent()

        val result = InlineToolCallParser.extract(text, known)
        assertTrue(result.found)
        assertEquals(1, result.calls.size)
        assertEquals("web_fetch", result.calls.single().toolName)
        assertTrue(result.calls.single().argumentsJson.contains("example.com"))
        assertFalse(result.cleanedText.contains("<tool_call>"))
        assertTrue(result.cleanedText.contains("I will look that up."))
    }

    @Test
    fun `fenced json block naming a real tool is executed`() {
        val text = """
            ```json
            {"name": "get_datetime", "arguments": {"timezone": "Europe/London"}}
            ```
        """.trimIndent()
        val result = InlineToolCallParser.extract(text, known)
        assertTrue(result.found)
        assertEquals("get_datetime", result.calls.single().toolName)
    }

    @Test
    fun `ordinary code blocks are left alone`() {
        val text = """
            Here is the config you asked for:
            ```json
            {"name": "my-service", "replicas": 3}
            ```
        """.trimIndent()
        val result = InlineToolCallParser.extract(text, known)
        assertFalse(result.found)
        assertTrue(result.cleanedText.contains("my-service"))
    }

    @Test
    fun `unknown tool names are ignored`() {
        val text = """<tool_call>{"name": "delete_everything", "arguments": {}}</tool_call>"""
        val result = InlineToolCallParser.extract(text, known)
        assertFalse(result.found)
        assertTrue(result.cleanedText.contains("delete_everything"))
    }

    @Test
    fun `openai style function wrapper is understood`() {
        val text = """```json
        {"type":"function","function":{"name":"web_search","arguments":"{\"query\":\"jetpack compose\"}"}}
        ```"""
        val result = InlineToolCallParser.extract(text, known)
        assertTrue(result.found)
        assertEquals("{\"query\":\"jetpack compose\"}", result.calls.single().argumentsJson)
    }

    @Test
    fun `bare json line is only accepted when the tool is known`() {
        val text = """{"name": "web_search", "arguments": {"query": "latest kotlin"}}"""
        assertTrue(InlineToolCallParser.extract(text, known).found)
        assertFalse(InlineToolCallParser.extract(text, emptySet()).found)
    }
}
