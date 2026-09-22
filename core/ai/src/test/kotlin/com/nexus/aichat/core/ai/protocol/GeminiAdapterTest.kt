package com.nexus.aichat.core.ai.protocol

import com.nexus.aichat.core.model.ProviderConfig
import com.nexus.aichat.core.model.ProviderProtocol
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Gemini tool round-tripping.
 *
 * Gemini correlates a `functionResponse` with the `functionCall` it answers by **function name** -
 * there is no call id on the wire. The adapter used to reverse-engineer the name from the call id,
 * which produced the literal string `"tool"` (and a 400 from the API) for every tool result, so the
 * second turn of any Gemini tool conversation failed.
 */
class GeminiAdapterTest {

    private val adapter = GeminiAdapter()

    private val config = ProviderConfig(
        id = "gemini",
        displayName = "Gemini",
        protocol = ProviderProtocol.GOOGLE_GEMINI,
        baseUrl = "https://generativelanguage.googleapis.com/v1beta",
    )

    private fun toolResult(role: ProviderRole, callId: String, name: String?): String =
        adapter.encodeChat(
            ProviderChatRequest(
                model = "gemini-3-pro",
                system = null,
                messages = listOf(
                    ProviderMessage(
                        role = role,
                        content = listOf(ProviderContentPart.ToolResultBlock(callId = callId, content = "42", isError = false)),
                        name = name,
                    ),
                ),
            ),
            config,
        )

    @Test
    fun `a tool result whose call id carries the name uses that name`() {
        val body = toolResult(ProviderRole.TOOL, callId = "abc123:web_fetch", name = "web_fetch")

        assertTrue(body.contains("\"name\":\"web_fetch\""), body)
    }

    @Test
    fun `a tool result with an opaque call id falls back to the message name`() {
        // The native API returns no id for a functionCall, so the harness-recorded tool name is the
        // only reliable source. "tool" here would be rejected as an unknown function.
        val body = toolResult(ProviderRole.TOOL, callId = "gemini_0", name = "read_document")

        assertTrue(body.contains("\"name\":\"read_document\""), body)
        assertTrue(!body.contains("\"name\":\"tool\""), body)
    }
}
