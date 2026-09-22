package com.nexus.aichat.core.common.result

import com.nexus.aichat.core.common.error.AppError

/**
 * A domain result type.
 *
 * We intentionally avoid `kotlin.Result` because it cannot express *typed* failures, and every
 * layer of Nexus (transport -> adapter -> orchestrator -> repository) needs to reason about
 * retryability, auth failures and rate limits without string matching.
 */
sealed interface NexusResult<out T> {

    data class Success<out T>(val data: T) : NexusResult<T>

    data class Failure(val error: AppError) : NexusResult<Nothing>

    val isSuccess: Boolean get() = this is Success

    fun getOrNull(): T? = (this as? Success)?.data

    fun errorOrNull(): AppError? = (this as? Failure)?.error
}

inline fun <T, R> NexusResult<T>.map(transform: (T) -> R): NexusResult<R> = when (this) {
    is NexusResult.Success -> NexusResult.Success(transform(data))
    is NexusResult.Failure -> this
}

inline fun <T, R> NexusResult<T>.flatMap(transform: (T) -> NexusResult<R>): NexusResult<R> = when (this) {
    is NexusResult.Success -> transform(data)
    is NexusResult.Failure -> this
}

inline fun <T> NexusResult<T>.onSuccess(action: (T) -> Unit): NexusResult<T> {
    if (this is NexusResult.Success) action(data)
    return this
}

inline fun <T> NexusResult<T>.onFailure(action: (AppError) -> Unit): NexusResult<T> {
    if (this is NexusResult.Failure) action(error)
    return this
}

fun <T> NexusResult<T>.getOrElse(fallback: (AppError) -> T): T = when (this) {
    is NexusResult.Success -> data
    is NexusResult.Failure -> fallback(error)
}

/** Runs a suspending block and converts any thrown [Throwable] into a typed [AppError]. */
inline fun <T> runCatchingResult(block: () -> T): NexusResult<T> = try {
    NexusResult.Success(block())
} catch (cancellation: kotlinx.coroutines.CancellationException) {
    throw cancellation
} catch (t: Throwable) {
    NexusResult.Failure(AppError.from(t))
}
