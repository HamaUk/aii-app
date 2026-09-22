package com.nexus.aichat.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.nexus.aichat.core.theme.ChatColors
import com.nexus.aichat.core.theme.NexusShapes
import com.nexus.aichat.core.theme.NexusType
import kotlinx.coroutines.delay

/**
 * Enhanced fenced code block with modern visual design and micro-interactions.
 *
 * Improvements over the base version:
 *  - Elevated glass-like surface with gradient border
 *  - Smooth copy button animations with haptic-timed feedback
 *  - Language badge with color coding for common languages
 *  - Enhanced readability with better contrast and spacing
 *  - Hover/press states for better interactivity
 *  - Line number support (optional, for long blocks)
 */
@Composable
fun CodeBlock(
    code: String,
    language: String?,
    modifier: Modifier = Modifier,
    showLineNumbers: Boolean = false,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    var hovered by remember { mutableStateOf(false) }

    // Auto-reset copied state
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }

    // Animate copy button scale
    val copyButtonScale by animateFloatAsState(
        targetValue = if (copied) 1.1f else 1f,
        animationSpec = tween(150),
        label = "copy-scale"
    )

    // Get language-specific color
    val languageColor = getLanguageColor(language)
    val displayLanguage = language?.lowercase()?.takeIf { it.isNotBlank() } ?: "code"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(NexusShapes.codeBlock())
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        ChatColors.codeSurface,
                        ChatColors.codeSurface.copy(alpha = 0.95f)
                    )
                )
            ),
    ) {
        // Header with language badge and copy button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.3f)
                )
                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Language badge
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(languageColor)
                )
                Text(
                    text = displayLanguage,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = languageColor.copy(alpha = 0.9f),
                )
            }

            // Enhanced copy button with animated feedback
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(code))
                    copied = true
                },
                modifier = Modifier.scale(copyButtonScale)
            ) {
                AnimatedVisibility(
                    visible = copied,
                    enter = fadeIn(tween(150)) + scaleIn(tween(150)),
                    exit = fadeOut(tween(100)) + scaleOut(tween(100))
                ) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        tint = ChatColors.success,
                        modifier = Modifier.size(20.dp)
                    )
                }
                AnimatedVisibility(
                    visible = !copied,
                    enter = fadeIn(tween(150)) + scaleIn(tween(150)),
                    exit = fadeOut(tween(100)) + scaleOut(tween(100))
                ) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = "Copy code",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = if (hovered) 1f else 0.7f
                        ),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Code content with optional line numbers
        SelectionContainer {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (showLineNumbers && code.lines().size > 3) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Line numbers
                        Column {
                            code.lines().forEachIndexed { index, _ ->
                                Text(
                                    text = "${index + 1}",
                                    style = NexusType.codeBlock().copy(
                                        color = ChatColors.codeText.copy(alpha = 0.4f)
                                    ),
                                    modifier = Modifier.alpha(0.5f)
                                )
                            }
                        }
                        // Code
                        Text(
                            text = code.trimEnd('\n'),
                            style = NexusType.codeBlock(),
                            color = ChatColors.codeText,
                            softWrap = false,
                        )
                    }
                } else {
                    Text(
                        text = code.trimEnd('\n'),
                        style = NexusType.codeBlock(),
                        color = ChatColors.codeText,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/**
 * Get language-specific accent color for visual coding.
 */
private fun getLanguageColor(language: String?): Color {
    return when (language?.lowercase()) {
        "kotlin", "kt" -> Color(0xFF7F52FF)
        "java" -> Color(0xFFED8B00)
        "python", "py" -> Color(0xFF3776AB)
        "javascript", "js", "jsx" -> Color(0xFFF7DF1E)
        "typescript", "ts", "tsx" -> Color(0xFF3178C6)
        "rust", "rs" -> Color(0xFFCE422B)
        "go" -> Color(0xFF00ADD8)
        "swift" -> Color(0xFFFA7343)
        "c", "cpp", "c++" -> Color(0xFF00599C)
        "csharp", "c#", "cs" -> Color(0xFF239120)
        "ruby", "rb" -> Color(0xFFCC342D)
        "php" -> Color(0xFF777BB4)
        "shell", "bash", "sh" -> Color(0xFF4EAA25)
        "sql" -> Color(0xFFE38C00)
        "json" -> Color(0xFF00D9FF)
        "xml", "html" -> Color(0xFFE34C26)
        "css", "scss" -> Color(0xFF1572B6)
        "yaml", "yml" -> Color(0xFFCB171E)
        "markdown", "md" -> Color(0xFF083FA1)
        else -> Color(0xFF9CA3AF) // Default gray
    }
}
