package com.nexus.aichat.core.ai.protocol

import com.nexus.aichat.core.model.ProviderConfig
import com.nexus.aichat.core.model.ProviderProtocol
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Custom-endpoint decoding.
 *
 * The whole reason a Raw-REST provider is configurable is that its envelope is unknown, so
 * `responseTextPath` is *the* feature: it must actually reach the decoder. It did not - the wizard
 * persisted the path and the adapter ignored it, which meant a custom endpoint silently returned an
 * empty answer whenever its body did not happen to look like a known shape.
 */
class RawRestAdapterTest {

    private val adapter = RawRestAdapter()

    private fun config(path: String?) = ProviderConfig(
        id = "custom",
        displayName = "Custom",
        protocol = ProviderProtocol.RAW_REST,
        baseUrl = "http://192.168.1.42:8000/v1",
        responseTextPath = path,
    )

    @Test
    fun `configured response path is honoured over the built-in envelopes`() {
        // `content` is a known envelope key, so only the configured path can produce "hi".
        val body = """{"result":{"payload":{"answer":"hi"}},"content":"WRONG"}"""

        val response = adapter.decodeChatResponse(body, config("result.payload.answer"))

        assertEquals("hi", response.text)
    }

    @Test
    fun `configured response path walks arrays by index`() {
        val body = """{"output":[{"content":[{"text":"first"},{"text":"second"}]}]}"""

        val response = adapter.decodeChatResponse(body, config("output.0.content.1.text"))

        assertEquals("second", response.text)
    }

    @Test
    fun `without a configured path the known envelopes still decode`() {
        val response = adapter.decodeChatResponse("""{"content":"hi"}""", config(null))

        assertEquals("hi", response.text)
    }

    @Test
    fun `a missing path falls back instead of returning an empty answer`() {
        // A typo in the wizard must degrade to best-effort decoding, not to a blank bubble.
        val response = adapter.decodeChatResponse("""{"content":"hi"}""", config("nope.not.here"))

        assertEquals("hi", response.text)
    }

    @Test
    fun `decoding without a config is tolerated`() {
        val response = adapter.decodeChatResponse("""{"response":"hi"}""", null)

        assertEquals("hi", response.text)
    }
}
