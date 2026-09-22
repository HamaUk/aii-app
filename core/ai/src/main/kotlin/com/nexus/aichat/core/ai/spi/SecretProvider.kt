package com.nexus.aichat.core.ai.spi

import com.nexus.aichat.core.model.ProviderConfig

/**
 * Implemented by the Android layer on top of the Keystore-encrypted store.
 *
 * The engine never sees a persisted secret: it asks for one at call time, uses it for exactly one
 * request, and drops it. Configs only ever carry the *name* of the vault entry.
 */
interface SecretProvider {

    /** API key / token for a provider, or null when the provider needs none. */
    suspend fun apiKeyFor(config: ProviderConfig): String?

    /** Free-standing secrets (web-search API keys, proxy tokens) stored under an arbitrary vault key. */
    suspend fun secret(vaultKey: String): String?

    suspend fun hasSecret(vaultKey: String): Boolean
}

/** Test/offline implementation. */
class InMemorySecretProvider(private val secrets: Map<String, String> = emptyMap()) : SecretProvider {
    override suspend fun apiKeyFor(config: ProviderConfig): String? = config.auth.vaultKey?.let { secrets[it] }
    override suspend fun secret(vaultKey: String): String? = secrets[vaultKey]
    override suspend fun hasSecret(vaultKey: String): Boolean = secrets.containsKey(vaultKey)
}
