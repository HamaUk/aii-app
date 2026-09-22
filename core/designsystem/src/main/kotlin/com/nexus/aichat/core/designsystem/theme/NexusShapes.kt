package com.nexus.aichat.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp
import com.nexus.aichat.core.model.CornerStyle

/**
 * Shape system with three personalities. The "organic" set is the default because morphing rather
 * than uniform rounding is what makes a modern assistant app feel alive: a user bubble has one
 * tighter corner (the tail), glass cards use 28dp, and the composer is a pill.
 */
object NexusShapes {

    fun forStyle(style: CornerStyle): Shapes = when (style) {
        CornerStyle.ORGANIC -> Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(36.dp),
        )
        CornerStyle.ROUNDED -> Shapes(
            extraSmall = RoundedCornerShape(4.dp),
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(12.dp),
            large = RoundedCornerShape(16.dp),
            extraLarge = RoundedCornerShape(24.dp),
        )
        CornerStyle.SHARP -> Shapes(
            extraSmall = RoundedCornerShape(2.dp),
            small = RoundedCornerShape(4.dp),
            medium = RoundedCornerShape(6.dp),
            large = RoundedCornerShape(8.dp),
            extraLarge = RoundedCornerShape(10.dp),
        )
    }

    /** Bubble tails: user bubbles keep the bottom-right tight, assistant bubbles the bottom-left. */
    fun userBubble(style: CornerStyle) = bubble(style, topStart = true, topEnd = true, bottomStart = true, bottomEnd = false)
    fun assistantBubble(style: CornerStyle) = bubble(style, topStart = true, topEnd = true, bottomStart = false, bottomEnd = true)
    fun codeBlock(style: CornerStyle) = bubble(style, true, true, true, true).let {
        when (style) {
            CornerStyle.ORGANIC -> RoundedCornerShape(18.dp)
            CornerStyle.ROUNDED -> RoundedCornerShape(12.dp)
            CornerStyle.SHARP -> RoundedCornerShape(6.dp)
        }
    }

    private fun bubble(
        style: CornerStyle,
        topStart: Boolean,
        topEnd: Boolean,
        bottomStart: Boolean,
        bottomEnd: Boolean,
    ): RoundedCornerShape {
        val big = when (style) {
            CornerStyle.ORGANIC -> 24.dp
            CornerStyle.ROUNDED -> 18.dp
            CornerStyle.SHARP -> 10.dp
        }
        val small = 6.dp
        return RoundedCornerShape(
            topStart = if (topStart) big else small,
            topEnd = if (topEnd) big else small,
            bottomStart = if (bottomStart) big else small,
            bottomEnd = if (bottomEnd) big else small,
        )
    }
}
