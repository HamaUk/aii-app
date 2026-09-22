package com.nexus.aichat.core.ai.transport

import com.nexus.aichat.core.common.logging.NexusLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.logging.Logger
import java.util.concurrent.TimeUnit

/**
 * Single shared HTTP engine for the whole app.
 *
 * Tuning rationale:
 *  - `socketTimeoutMillis = 120s` is the real watchdog for streaming. It is deliberately long: the
 *    first token from a large reasoning model can legitimately take a minute, while a dead socket is
 *    still detected inside two minutes.
 *  - connection pool of 8 with a 5-minute keep-alive: chat UIs make bursty, sequential calls and
 *    TLS handshakes dominate latency on mobile networks.
 *  - `expectSuccess = false` because every status code is mapped into [com.nexus.aichat.core.common.error.AppError]
 *    by the adapters, which know each provider's error envelope.
 */
/**
 * Ktor 3.x no longer exposes `HttpTimeout.INFINITE_TIMEOUT_MS`, so the engine declares its own.
 * Chat streams are open-ended by design: the socket timeout guards liveness, the wall clock is
 * instead bounded by the agent harness, which can always be cancelled.
 */
internal const val INFINITE_TIMEOUT_MS: Long = Long.MAX_VALUE

object HttpEngineFactory {

    fun create(
        logger: NexusLogger,
        userAgent: String = DEFAULT_USER_AGENT,
        debugLogging: Boolean = false,
    ): HttpClient = HttpClient(OkHttp) {
        expectSuccess = false
        install(UserAgent) { agent = userAgent }
        install(HttpTimeout) {
            connectTimeoutMillis = 20_000
            socketTimeoutMillis = 120_000
            requestTimeoutMillis = INFINITE_TIMEOUT_MS
        }
        if (debugLogging) {
            install(Logging) {
                level = LogLevel.INFO
                this.logger = object : Logger {
                    override fun log(message: String) {
                        // Bodies and headers both go through redaction before they hit logcat.
                        logger.d("NexusHttp", com.nexus.aichat.core.common.logging.Redaction.redact(message))
                    }
                }
                sanitizeHeader { header -> header.equals("Authorization", ignoreCase = true) }
            }
        }
        engine {
            config {
                connectTimeout(20, TimeUnit.SECONDS)
                readTimeout(120, TimeUnit.SECONDS)
                writeTimeout(30, TimeUnit.SECONDS)
                retryOnConnectionFailure(true)
                connectionPool(okhttp3.ConnectionPool(maxIdleConnections = 8, keepAliveDuration = 5, timeUnit = TimeUnit.MINUTES))
            }
        }
    }

    const val DEFAULT_USER_AGENT = "Nexus/1.0 (Android; +https://nexus.agent)"
}
