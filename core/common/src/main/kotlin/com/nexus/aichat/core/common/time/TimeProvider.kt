package com.nexus.aichat.core.common.time

/**
 * All time reads flow through this seam so agent transcripts, tokens/second metrics and message
 * trees stay deterministic under test.
 */
interface TimeProvider {
    fun nowEpochMillis(): Long
    fun elapsedMillis(): Long
}

class SystemTimeProvider : TimeProvider {
    private val start = System.nanoTime()
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
    override fun elapsedMillis(): Long = (System.nanoTime() - start) / 1_000_000
}

class FakeTimeProvider(private var now: Long = 1_700_000_000_000L) : TimeProvider {
    override fun nowEpochMillis(): Long = now
    override fun elapsedMillis(): Long = now
    fun advanceBy(millis: Long) { now += millis }
}
