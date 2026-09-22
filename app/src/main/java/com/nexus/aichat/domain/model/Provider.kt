package com.nexus.aichat.domain.model

import com.nexus.aichat.core.model.AuthConfig as CoreAuthConfig
import com.nexus.aichat.core.model.AuthScheme
import com.nexus.aichat.core.model.ProviderConfig as CoreProviderConfig
import com.nexus.aichat.core.model.ProviderPreset as CoreProviderPreset
import com.nexus.aichat.core.model.ProviderPresets

typealias ProviderConfig = CoreProviderConfig
typealias ProviderPreset = CoreProviderPreset
typealias ProviderAuth = CoreAuthConfig

/** Presentation helpers for the provider list, setup wizard and model picker. */
object ProviderPresentation {

    val presets: List<ProviderPreset> get() = ProviderPresets.ALL

    /** Human label for the auth style shown in the wizard's "what do I paste?" hint. */
    fun authHint(scheme: AuthScheme): String = when (scheme) {
        AuthScheme.NONE -> "No key needed (local or unauthenticated endpoint)"
        AuthScheme.BEARER -> "Bearer token, sent as Authorization: Bearer …"
        AuthScheme.X_API_KEY -> "API key, sent as x-api-key: …"
        AuthScheme.X_GOOG_API_KEY -> "API key, sent as x-goog-api-key: …"
        AuthScheme.QUERY_PARAM -> "API key appended to the URL as a query parameter"
        AuthScheme.CUSTOM_HEADER -> "Value sent in a header name you choose"
    }

    /** A provider is "ready" when it has a URL, a model and (if required) a stored key. */
    fun readiness(config: ProviderConfig, hasStoredKey: Boolean): Readiness = when {
        config.baseUrl.isBlank() -> Readiness.NeedsBaseUrl
        config.auth.requiresSecret && !hasStoredKey -> Readiness.NeedsApiKey
        config.selectedModelId.isNullOrBlank() -> Readiness.NeedsModel
        else -> Readiness.Ready
    }

    enum class Readiness { NeedsBaseUrl, NeedsApiKey, NeedsModel, Ready }

    /** Emulator hosts cannot reach the dev machine's localhost; surface that before a failed call. */
    fun localHostWarning(config: ProviderConfig, isEmulator: Boolean): String? {
        if (!isEmulator) return null
        val isLoopback = config.baseUrl.contains("localhost") || config.baseUrl.contains("127.0.0.1")
        return if (isLoopback) {
            "On an emulator, use 10.0.2.2 instead of localhost to reach this machine."
        } else {
            null
        }
    }
}
