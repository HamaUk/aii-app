package com.nexus.aichat.core.ai.util

/**
 * Token accounting fallback.
 *
 * Providers usually report exact usage, but several do not report it on *streaming* responses
 * (OpenAI only sends usage when `stream_options.include_usage` is set; many self-hosted servers never
 * send it at all). Nexus therefore always shows a number, and marks whether it was provider-reported
 * or estimated so the UI can render "~" instead of lying with false precision.
 */
object TokenEstimator {

    /** Rough chars-per-token ratios tuned per content shape (BPE tokenisers are character-hungry on code). */
    private const val RATIO_PROSE = 4.0
    private const val RATIO_CODE = 3.2
    private const val RATIO_CJK = 1.6

    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        val cjk = text.count { it.code in 0x2E80..0x9FFF || it.code in 0xAC00..0xD7AF }
        val codey = text.count { it == '{' || it == ';' || it == '<' || it == ')' }
        val ratio = when {
            cjk > text.length / 8 -> RATIO_CJK
            codey > text.length / 40 -> RATIO_CODE
            else -> RATIO_PROSE
        }
        return (text.length / ratio).toInt().coerceAtLeast(1)
    }

    fun estimateMessages(promptChars: Int, attachmentChars: Int = 0): Int =
        estimate("x".repeat(promptChars)) + estimate("x".repeat(attachmentChars)) + 4

    /** Fills in a usage object when the provider stayed silent. */
    fun synthesize(inputChars: Int, outputChars: Int, reasoningChars: Int = 0): com.nexus.aichat.core.model.TokenUsage {
        val input = estimate("x".repeat(inputChars))
        val output = estimate("x".repeat(outputChars))
        val reasoning = if (reasoningChars > 0) estimate("x".repeat(reasoningChars)) else 0
        return com.nexus.aichat.core.model.TokenUsage(
            inputTokens = input,
            outputTokens = output + reasoning,
            reasoningTokens = reasoning,
            totalTokens = input + output + reasoning,
            providerReported = false,
        )
    }
}
