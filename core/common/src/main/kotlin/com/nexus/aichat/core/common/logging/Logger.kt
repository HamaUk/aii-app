package com.nexus.aichat.core.common.logging

/**
 * Minimal logging seam. The Android module installs a Logcat-backed implementation; the JVM
 * modules stay free of `android.util.Log` so they unit-test on a bare JVM.
 */
interface NexusLogger {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}

object NoOpLogger : NexusLogger {
    override fun d(tag: String, message: String) = Unit
    override fun i(tag: String, message: String) = Unit
    override fun w(tag: String, message: String, throwable: Throwable?) = Unit
    override fun e(tag: String, message: String, throwable: Throwable?) = Unit
}

/**
 * Redacts bearer tokens / API keys before anything reaches logcat, a bug report or a share sheet.
 * Non-negotiable: request headers and transcripts both flow through here.
 */
object Redaction {

    private val secretPatterns = listOf(
        Regex("""(?i)(sk-[A-Za-z0-9_\-]{6})[A-Za-z0-9_\-]+"""),
        Regex("""(?i)(Bearer\s+[A-Za-z0-9_\-]{6})[A-Za-z0-9_\-\.]+"""),
        Regex("""(?i)("?(?:api[_-]?key|apikey|authorization|x-api-key|x-goog-api-key)"?\s*[:=]\s*"?)([^"\s,}]{6,})"""),
    )

    fun redact(input: String): String = secretPatterns.fold(input) { acc, regex ->
        regex.replace(acc) { match -> "${match.groupValues[1]}...REDACTED" }
    }
}
