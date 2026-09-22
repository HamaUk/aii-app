package com.nexus.aichat.core.common.error

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The single error taxonomy of the app. Provider HTTP codes, Ktor exceptions and tool failures all
 * funnel into this tree, which is the only thing the UI layer ever renders.
 */
@Serializable
sealed class AppError {

    /**
     * Human-readable detail. Declared abstract rather than as a base-constructor property: a
     * serialisable property on the base that subclasses `override` is rejected by kotlinx.serialization
     * as a duplicate serial name, so each variant owns exactly one `message` field.
     */
    abstract val message: String?

    /**
     * The originating throwable. `@Transient` because exceptions have no stable wire form - this exists
     * for logging and crash reports, and must never reach the database or the network.
     */
    @Transient
    open val cause: Throwable? = null


    /** Nothing reached the network stack: airplane mode, DNS failure, TLS interception. */
    @Serializable
    data class Network(
        override val message: String?,
        @Transient override val cause: Throwable? = null,
        val retryable: Boolean = true,
    ) : AppError()

    /** Connect/read timeout - distinct from [Network] so the UI can offer "retry, longer timeout". */
    @Serializable
    data class Timeout(override val message: String?, @Transient override val cause: Throwable? = null) : AppError()

    /** Provider rejected the key (401/403) or the key is missing entirely. */
    @Serializable
    data class Auth(
        override val message: String?,
        val providerId: String? = null,
        @Transient override val cause: Throwable? = null,
    ) : AppError()

    /** 402/429 - out of credits or throttled. [retryAfterSeconds] mirrors the `retry-after` header. */
    @Serializable
    data class RateLimited(
        override val message: String?,
        val retryAfterSeconds: Long? = null,
        @Transient override val cause: Throwable? = null,
    ) : AppError()

    /** Provider returned a structured error envelope we understood. */
    @Serializable
    data class Provider(
        override val message: String?,
        val httpStatus: Int? = null,
        val errorCode: String? = null,
        val rawBody: String? = null,
        @Transient override val cause: Throwable? = null,
    ) : AppError()

    /** Stream died mid-flight after tokens were already rendered - keep the partial text. */
    @Serializable
    data class StreamInterrupted(
        override val message: String?,
        val partialText: String? = null,
        @Transient override val cause: Throwable? = null,
    ) : AppError()

    /** The model burned its step budget without producing a final answer. */
    @Serializable
    data class AgentBudgetExhausted(
        override val message: String?,
        val steps: Int = 0,
        @Transient override val cause: Throwable? = null,
    ) : AppError()

    /** A tool raised. The agent loop may still self-correct, so this is rarely fatal. */
    @Serializable
    data class ToolExecution(
        override val message: String?,
        val toolName: String? = null,
        @Transient override val cause: Throwable? = null,
    ) : AppError()

    /** Key material unavailable: Keystore invalidated after a biometric/credential change. */
    @Serializable
    data class KeyStore(override val message: String?, @Transient override val cause: Throwable? = null) : AppError()

    /** Malformed JSON from a provider, or a schema violation in our own models. */
    @Serializable
    data class Serialization(override val message: String?, @Transient override val cause: Throwable? = null) : AppError()

    /** User hit the Stop button, or the scope went away. Never surfaced as an error banner. */
    @Serializable
    data object Cancelled : AppError() {
        @Transient override val message: String? = null
        @Transient override val cause: Throwable? = null
    }

    @Serializable
    data class Unknown(override val message: String?, @Transient override val cause: Throwable? = null) : AppError()

    /** Short, human-readable copy for inline error cards and snackbars. */
    val displayMessage: String
        get() = when (this) {
            is Network -> "Can't reach the provider. Check your connection."
            is Timeout -> "The provider took too long to respond."
            is Auth -> "Authentication failed. Verify the API key for ${providerId ?: "this provider"}."
            is RateLimited -> buildString {
                append("Rate limited or out of quota.")
                retryAfterSeconds?.let { append(" Retry in ${it}s.") }
            }
            is Provider -> message ?: "Provider error" + (httpStatus?.let { " (HTTP $it)" } ?: "") + "."
            is StreamInterrupted -> "Stream interrupted. Partial response kept."
            is AgentBudgetExhausted -> "Stopped after $steps steps without a final answer."
            is ToolExecution -> "Tool '$toolName' failed: ${message ?: "unknown error"}"
            is KeyStore -> "Secure storage unavailable. Re-enter your API key."
            is Serialization -> "Received a malformed response from the provider."
            is Cancelled -> "Cancelled."
            is Unknown -> message ?: "Something went wrong."
        }

    val isRetryable: Boolean
        get() = when (this) {
            is Network -> retryable
            is Timeout -> true
            is RateLimited -> true
            is Provider -> (httpStatus ?: 0) >= 500
            is StreamInterrupted -> partialText.isNullOrEmpty()
            else -> false
        }

    companion object {
        fun from(t: Throwable): AppError = when (t) {
            is AppError -> t
            is SocketTimeoutException -> Timeout(t.message, t)
            is UnknownHostException -> Network(t.message, t, retryable = false)
            is IOException -> Network(t.message, t)
            is kotlinx.serialization.SerializationException -> Serialization(t.message, t)
            is kotlinx.coroutines.CancellationException -> Cancelled
            else -> Unknown(t.message, t)
        }
    }
}

/** Adapter so domain errors can be thrown from guard clauses and caught by the mapper above. */
class AppException(val error: AppError) : Exception(error.displayMessage, error.cause)
