package com.nexus.aichat.core.util

import android.util.Log
import com.nexus.aichat.core.common.logging.NexusLogger
import com.nexus.aichat.core.common.logging.Redaction

/**
 * Logcat sink with redaction applied on the way in and a hard debug/release split.
 *
 * `BuildConfig.DEBUG` gating matters: release builds must never write prompt text or headers to
 * logcat, where any app with READ_LOGS (or an OEM bug-report tool) could pick them up.
 */
class LogcatLogger(private val isDebug: Boolean) : NexusLogger {

    override fun d(tag: String, message: String) {
        if (isDebug) Log.d(normalise(tag), Redaction.redact(message))
    }

    override fun i(tag: String, message: String) {
        if (isDebug) Log.i(normalise(tag), Redaction.redact(message))
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        Log.w(normalise(tag), Redaction.redact(message), throwable)
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        // Errors always log, but still redacted: a stack trace can contain a URL with a key in it.
        Log.e(normalise(tag), Redaction.redact(message), throwable?.let { RedactingThrowable(it) })
    }

    /** Logcat truncates tags over 23 chars on older platforms. */
    private fun normalise(tag: String) = if (tag.length <= 23) tag else tag.take(23)

    private class RedactingThrowable(cause: Throwable) : Throwable(cause.message?.let(Redaction::redact), cause)
}
