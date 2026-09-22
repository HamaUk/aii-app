package com.nexus.aichat.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.nexus.aichat.core.ai.spi.SecretProvider
import com.nexus.aichat.core.common.error.AppError
import com.nexus.aichat.core.common.logging.NexusLogger
import com.nexus.aichat.core.model.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Secure local storage for API keys.
 *
 * Design: **the secret never touches Room, DataStore or a backup.** Provider configs hold only a
 * vault *key name*; the material itself lives in an `EncryptedSharedPreferences` file whose master
 * key is generated inside the Android Keystore and can never be exported, even from a rooted device
 * with the app's data directory.
 *
 * Concretely:
 *   - master key: AES-256-GCM, `setUserAuthenticationRequired(false)`, hardware-backed where the
 *     device supports it (StrongBox is not requested, to avoid device-specific breakage);
 *   - key material: AES-256-SIV (deterministic, so key names are searchable);
 *   - values: AES-256-GCM (authenticated, random IV per write);
 *   - the file is excluded from auto-backup (see `backup_rules.xml`), because a restored ciphertext
 *     without the Keystore key would be unreadable anyway and would fail confusingly.
 *
 * Failure mode handled explicitly: `KeyStore` errors after a biometric enrolment change or an OS
 * upgrade invalidate the master key. We surface a typed [AppError.KeyStore] instead of crashing, and
 * the UI nudges the user to re-enter the key.
 */
@javax.inject.Singleton
class SecureKeyStore @javax.inject.Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext context: Context,
    private val logger: NexusLogger,
) : SecretProvider {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext, MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // --- SecretProvider (what the AI core sees) ------------------------------------------------

    override suspend fun apiKeyFor(config: ProviderConfig): String? =
        config.auth.vaultKey?.let { key -> secret(key) }

    override suspend fun secret(vaultKey: String): String? = withContext(Dispatchers.IO) {
        runCatching { prefs.getString(prefixed(vaultKey), null) }
            .onFailure { t ->
                logger.e(TAG, "keystore read failed for '$vaultKey': ${t.message}", t)
                throw com.nexus.aichat.core.common.error.AppException(
                    AppError.KeyStore(
                        "Secure storage could not be read. Re-enter your API key for this provider.",
                        t,
                    ),
                )
            }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    override suspend fun hasSecret(vaultKey: String): Boolean = secret(vaultKey) != null

    // --- Management API used by the provider setup screens --------------------------------------

    suspend fun put(vaultKey: String, value: String) = withContext(Dispatchers.IO) {
        runCatching { prefs.edit().putString(prefixed(vaultKey), value).commit() }
            .getOrElse { t ->
                logger.e(TAG, "keystore write failed: ${t.message}", t)
                throw com.nexus.aichat.core.common.error.AppException(AppError.KeyStore("Could not save the key securely.", t))
            }
        Unit
    }

    suspend fun remove(vaultKey: String) = withContext(Dispatchers.IO) {
        prefs.edit().remove(prefixed(vaultKey)).apply()
        Unit
    }

    suspend fun keys(): Set<String> = withContext(Dispatchers.IO) {
        prefs.all.keys.mapNotNull { it.removePrefix(PREFIX).takeIf { _ -> it.startsWith(PREFIX) } }.toSet()
    }

    /** One-time onboarding / diagnostics: proves the Keystore round-trip works on this device. */
    suspend fun selfTest(): Boolean = runCatching {
        val probeKey = "__selftest"
        put(probeKey, "ok")
        val value = secret(probeKey)
        remove(probeKey)
        value == "ok"
    }.getOrDefault(false)

    private fun prefixed(vaultKey: String) = "$PREFIX$vaultKey"

    companion object {
        const val FILE_NAME = "nexus_secrets"
        const val MASTER_KEY_ALIAS = "nexus_master_key_v1"
        private const val PREFIX = "secret:"
        private const val TAG = "SecureKeyStore"

        /** Vault key naming convention, referenced by the search providers and settings. */
        const val KEY_BRAVE_SEARCH = "search.brave"
        const val KEY_TAVILY_SEARCH = "search.tavily"
    }
}
