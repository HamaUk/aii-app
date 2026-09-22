package com.nexus.aichat.core.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.nexus.aichat.core.designsystem.theme.LocalNexusTokens
import com.nexus.aichat.core.designsystem.theme.NexusTokens

/**
 * Feature-level colour aliases.
 *
 * The design system (`:core:designsystem`) owns the six palettes and the M3 role mapping. This file
 * exists so chat-specific surfaces can ask for semantic names ("code background", "streaming
 * caret", "citation chip") instead of guessing which M3 role happens to look right today.
 */
object ChatColors {

    /** Code fences and inline code inside an assistant bubble. */
    val codeSurface: Color @Composable get() = LocalNexusTokens.current.codeSurface
    val codeText: Color @Composable get() = LocalNexusTokens.current.codeSurfaceText

    /** The pulsing caret that trails streaming text. */
    val streamingCaret: Color @Composable get() = androidx.compose.material3.MaterialTheme.colorScheme.primary

    /** Tool milestone rails in the thought tree. */
    val reasoningGutter: Color @Composable get() = LocalNexusTokens.current.reasoningGutter
    val toolChip: Color @Composable get() = LocalNexusTokens.current.toolChip

    /** Citation and source chips under an assistant message. */
    val citation: Color @Composable get() = androidx.compose.material3.MaterialTheme.colorScheme.tertiary

    /** Status semantics used by the reasoning tree and connection tester. */
    val success: Color @Composable get() = LocalNexusTokens.current.success
    val warning: Color @Composable get() = LocalNexusTokens.current.warning
    val info: Color @Composable get() = LocalNexusTokens.current.info
}

/** Raw fallbacks for previews and for the splash window, where no theme is available yet. */
internal object PreviewColors {
    val darkSurface = Color(0xFF0E1113)
    val darkBackground = Color(0xFF000000)
    val accent = Color(0xFF8AB4F8)
}

/** Convenience accessor so screens do not import the design system for one token. */
@Composable
internal fun tokens(): NexusTokens = LocalNexusTokens.current
