package com.nexus.aichat.data.remote.api

import com.nexus.aichat.core.model.ProviderConfig
import com.nexus.aichat.core.model.ProviderProtocol

/**
 * Google's Generative Language API.
 *
 * Gemini differs from the other two in ways the UI has to accommodate rather than hide:
 *  - the model id is part of the **path**, not the body;
 *  - streaming needs `alt=sse` (or the response is a JSON array, which is not what a chat wants);
 *  - the key can go in a header (`x-goog-api-key`) - preferred, because a key in a query string ends up
 *    in logs and screenshots.
 */
object GeminiApi {

    const val MODELS_PATH = "/models"

    const val API_KEY_HEADER = "x-goog-api-key"

    fun chatUrl(baseUrl: String, modelId: String, streaming: Boolean): String {
        val model = modelId.removePrefix("models/")
        val path = "/models/$model:${if (streaming) "streamGenerateContent" else "generateContent"}"
        val url = ProviderConfig.joinUrl(baseUrl, path)
        return if (streaming) "$url?alt=sse" else url
    }

    fun modelsUrl(baseUrl: String): String = ProviderConfig.joinUrl(baseUrl, MODELS_PATH)

    fun headerFor(apiKey: String): Pair<String, String> = API_KEY_HEADER to apiKey

    fun validateBaseUrl(baseUrl: String): String? {
        val trimmed = baseUrl.trim()
        if (trimmed.isEmpty()) return "Base URL is required"
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return "Base URL must start with http:// or https://"
        }
        if (trimmed.contains(":generateContent") || trimmed.contains(":streamGenerateContent")) {
            return "Use the base URL only - the app appends /models/<model>:generateContent"
        }
        return null
    }

    /** Google returns `models/gemini-…`; discovery strips the prefix and the wizard shows both. */
    fun normaliseModelId(raw: String): String = raw.removePrefix("models/")

    val suggestedModels = listOf("gemini-3-pro", "gemini-3-flash", "gemini-3-flash-lite")

    val protocol: ProviderProtocol = ProviderProtocol.GOOGLE_GEMINI
}
