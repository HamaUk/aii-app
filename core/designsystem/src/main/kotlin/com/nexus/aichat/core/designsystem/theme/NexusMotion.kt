package com.nexus.aichat.core.designsystem.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * Motion tokens.
 *
 * Physics-based springs are the default for anything the user directly manipulates (bubbles,
 * sheets, the composer, expand/collapse of the thought tree) because duration-based easing feels
 * mechanical when the gesture ends early. Tween with an expressive curve is reserved for opacity
 * and colour, where springs overshoot visibly.
 *
 * [NexusMotion.reduced] exists so `Settings > Accessibility > Remove animations` and our own
 * "reduced motion" toggle can both shorten everything to a near-instant, non-springy transition.
 */
data class NexusMotionSpec(
    val reduced: Boolean = false,
) {
    private fun <T> springy(stiffness: Float, dampingRatio: Float, reducedSpec: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> =
        if (reduced) reducedSpec else spring(dampingRatio = dampingRatio, stiffness = stiffness)

    /** Bubbles, tool rows, thought-tree steps arriving in the feed. */
    fun <T> enter(): FiniteAnimationSpec<T> = springy(Spring.StiffnessMediumLow, Spring.DampingRatioLowBouncy, tween(90))

    /** Anything that leaves: never bounce on the way out. */
    fun <T> exit(): FiniteAnimationSpec<T> = tween(if (reduced) 60 else 160, easing = NexusEasing.standard)

    /** Expand/collapse of the thinking tree, sheets, accordions. */
    fun <T> expand(): FiniteAnimationSpec<T> = springy(Spring.StiffnessLow, Spring.DampingRatioNoBouncy, tween(110))

    /** Hover/press elevation and scale feedback. */
    fun <T> press(): FiniteAnimationSpec<T> = tween(if (reduced) 40 else 110, easing = NexusEasing.emphasized)

    /** Screen-to-screen transitions. */
    fun <T> screen(): FiniteAnimationSpec<T> = springy(Spring.StiffnessMedium, Spring.DampingRatioNoBouncy, tween(140))

    /** Slide offsets for the nav/sheet layers. */
    fun slideOffset(): FiniteAnimationSpec<IntOffset> = springy(Spring.StiffnessMediumLow, Spring.DampingRatioNoBouncy, tween(140))

    companion object {
        val BubbleMorph = 24.dp
        val StreamingPulse = 900
    }
}

object NexusEasing {
    /** M3 "emphasized" curve: the acceleration profile used across Google's own apps. */
    val emphasized: Easing = CubicBezierEasing(0.2f, 0.0f, 0f, 1.0f)
    val standard: Easing = CubicBezierEasing(0.2f, 0.0f, 0f, 1.0f)
    val decelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    val accelerate: Easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
}
