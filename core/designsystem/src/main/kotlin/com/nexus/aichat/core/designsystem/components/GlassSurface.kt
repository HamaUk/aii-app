package com.nexus.aichat.core.designsystem.components

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nexus.aichat.core.designsystem.theme.EnhancedEasing
import com.nexus.aichat.core.designsystem.theme.LocalNexusTokens

/**
 * Enhanced glassmorphism surface with improved visual depth and modern aesthetics.
 *
 * Used by the floating composer, model-switcher pill, thought-tree cards, and bottom sheets.
 * This enhanced version provides:
 *   - Multi-layer gradient fills with improved depth perception
 *   - Adaptive border highlighting based on elevation
 *   - Optional backdrop blur on API 31+ for premium glass effect
 *   - Smooth animated transitions for elevation changes
 *   - Enhanced shadow with better depth cues
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    elevation: Dp = 10.dp,
    alphaBoost: Float = 0f,
    enableBackdropBlur: Boolean = false,
    animated: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val tokens = LocalNexusTokens.current

    // Animate elevation changes for smooth transitions
    val animatedElevation by animateFloatAsState(
        targetValue = elevation.value,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = if (animated) 200 else 0,
            easing = EnhancedEasing.emphasizedDecelerate
        ),
        label = "glass-elevation"
    )

    // Calculate adaptive alpha based on elevation (higher = more opaque for better readability)
    val baseAlpha = tokens.glassFill.alpha
    val elevationBoost = (animatedElevation / 20f).coerceIn(0f, 0.15f)
    val topAlpha = (baseAlpha + 0.10f + alphaBoost + elevationBoost).coerceAtMost(0.98f)
    val bottomAlpha = (baseAlpha - 0.04f).coerceAtLeast(0.3f)

    // Enhanced gradient with multiple stops for better depth
    val fillGradient = Brush.verticalGradient(
        0.0f to tokens.glassFill.copy(alpha = topAlpha),
        0.3f to tokens.glassFill.copy(alpha = topAlpha * 0.95f),
        0.7f to tokens.glassFill.copy(alpha = bottomAlpha * 1.05f),
        1.0f to tokens.glassFill.copy(alpha = bottomAlpha)
    )

    // Enhanced border with subtle shimmer effect
    val borderGradient = Brush.verticalGradient(
        0.0f to tokens.glassHighlight.copy(alpha = tokens.glassHighlight.alpha * 1.2f),
        0.15f to tokens.glassHighlight,
        0.5f to tokens.glassBorder,
        1.0f to tokens.glassBorder.copy(alpha = tokens.glassBorder.alpha * 0.7f)
    )

    Box(
        modifier = modifier
            .shadow(
                elevation = animatedElevation.dp,
                shape = shape,
                clip = false,
                ambientColor = tokens.scrim.copy(alpha = 0.12f),
                spotColor = tokens.scrim.copy(alpha = 0.08f)
            )
            .then(
                // Apply backdrop blur on Android 12+ if enabled
                if (enableBackdropBlur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Modifier.blur(radius = 16.dp)
                } else {
                    Modifier
                }
            )
            .clip(shape)
            .background(brush = fillGradient)
            .border(
                width = 1.dp,
                brush = borderGradient,
                shape = shape
            ),
        content = content
    )
}

/**
 * Elevated glass surface variant for prominent UI elements.
 */
@Composable
fun ElevatedGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    GlassSurface(
        modifier = modifier,
        shape = shape,
        elevation = 16.dp,
        alphaBoost = 0.08f,
        enableBackdropBlur = true,
        content = content
    )
}

/**
 * Subtle glass surface for background cards and containers.
 */
@Composable
fun SubtleGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    GlassSurface(
        modifier = modifier,
        shape = shape,
        elevation = 4.dp,
        alphaBoost = -0.05f,
        content = content
    )
}
