package com.nexus.aichat.core.common.di

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Injected dispatchers. Tests substitute a `StandardTestDispatcher`, which is impossible when
 * production code hard-codes `Dispatchers.IO`.
 *
 * Note: `Dispatchers.Main` resolves through the `kotlinx-coroutines-android` MainDispatcherFactory
 * that the :app module puts on the runtime classpath, so this JVM module stays Android-free.
 */
interface NexusDispatchers {
    val default: CoroutineDispatcher
    val io: CoroutineDispatcher
    val main: CoroutineDispatcher

    /** Bounded pool for the tool harness (web fetch, PDF parse) to avoid unbounded fan-out. */
    val toolPool: CoroutineDispatcher
}

class DefaultNexusDispatchers : NexusDispatchers {
    override val default: CoroutineDispatcher get() = Dispatchers.Default
    override val io: CoroutineDispatcher get() = Dispatchers.IO
    override val main: CoroutineDispatcher get() = Dispatchers.Main
    override val toolPool: CoroutineDispatcher get() = Dispatchers.IO
}
