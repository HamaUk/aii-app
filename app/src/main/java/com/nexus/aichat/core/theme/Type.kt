package com.nexus.aichat.core.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.nexus.aichat.core.designsystem.theme.NexusTypography

/**
 * Chat-surface text styles.
 *
 * The design system ships the M3 type scale; these are the *content* styles on top of it: markdown
 * headings inside a bubble, code, timestamps, token counters. They are defined once here so the
 * markdown renderer and the code block never drift apart.
 */
object NexusType {

    /** Markdown headings rendered inside a message bubble (deliberately smaller than screen titles). */
    @Composable
    fun markdownHeading(level: Int): TextStyle {
        val base = MaterialTheme.typography
        return when (level) {
            1 -> base.headlineSmall.copy(fontSize = 22.sp, lineHeight = 28.sp)
            2 -> base.titleLarge.copy(fontSize = 19.sp, lineHeight = 26.sp)
            3 -> base.titleMedium.copy(fontSize = 17.sp, lineHeight = 24.sp)
            else -> base.titleSmall.copy(fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    fun body(): TextStyle = MaterialTheme.typography.bodyLarge

    /** Inline `code` inside prose: monospace one step down from body, so it sits on the baseline. */
    @Composable
    fun inlineCode(): TextStyle = NexusTypography.Mono.copy(
        fontSize = 13.5.sp,
        lineHeight = 20.sp,
    )

    @Composable
    fun codeBlock(): TextStyle = NexusTypography.Mono.copy(fontSize = 13.sp, lineHeight = 20.sp)

    @Composable
    fun timestamp(): TextStyle = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.3.sp)

    @Composable
    fun tokenCounter(): TextStyle = NexusTypography.Mono.copy(fontSize = 11.sp, lineHeight = 14.sp)

    @Composable
    fun thoughtTree(): TextStyle = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp)
}
