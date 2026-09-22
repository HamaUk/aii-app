package com.nexus.aichat.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shimmer placeholders and the streaming "thinking" pulse.
 *
 * Two uses, one implementation: skeleton lines while a screen loads, and the travelling highlight on a
 * token counter that is still counting. Reduced-motion is honoured by the *caller* (see
 * `AppearanceSettings.reducedMotion`) - an infinite animation is exactly what that setting is for.
 */
@Composable
fun ShimmerBrush(
    travel: Float,
    widthPx: Float,
): Brush {
    val scheme = MaterialTheme.colorScheme
    val highlight = scheme.surfaceContainerHighest
    val base = scheme.surfaceContainerHigh
    val center = (travel * 2f - 0.5f) * widthPx
    return Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(center - widthPx / 3f, 0f),
        end = Offset(center + widthPx / 3f, 0f),
    )
}

/** Three skeleton lines, used when opening a long conversation from the drawer. */
@Composable
fun MessageSkeleton(
    modifier: Modifier = Modifier,
    lines: Int = 3,
    lineHeight: Dp = 14.dp,
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val travel by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_400), RepeatMode.Restart),
        label = "skeleton-travel",
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(lines) { index ->
            val fraction = if (index == lines - 1) 0.6f else 1f
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(lineHeight)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(ShimmerBrush(travel, 900f)),
            )
        }
    }
}
