package com.nexus.aichat.core.designsystem.theme

import androidx.compose.animation.core.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * Enhanced motion system with fluid physics-based transitions and organic animations.
 *
 * Inspired by Google Gemini and Claude's motion design language, this provides:
 * - Physics-based springs for natural feel
 * - Organic shape morphing and transitions
 * - Micro-interactions with haptic feedback timing
 * - Smooth anticipation and follow-through
 */
data class EnhancedMotionSpec(
    val reduced: Boolean = false,
) {
    // Spring configurations for different interaction types
    private val organicSpring = Spring.StiffnessLow
    private val snappySpring = Spring.StiffnessMedium
    private val quickSpring = Spring.StiffnessMediumLow

    // Damping for natural motion
    private val bouncyDamping = Spring.DampingRatioLowBouncy
    private val smoothDamping = Spring.DampingRatioNoBouncy
    private val mediumDamping = Spring.DampingRatioMediumBouncy

    private fun <T> springy(stiffness: Float, dampingRatio: Float, reducedSpec: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> =
        if (reduced) reducedSpec else spring(dampingRatio = dampingRatio, stiffness = stiffness)

    /** Bubbles entering the feed - organic with subtle bounce */
    fun <T> bubbleEnter(): FiniteAnimationSpec<T> =
        springy(quickSpring, mediumDamping, tween(100))

    /** Smooth exit - no bounce */
    fun <T> bubbleExit(): FiniteAnimationSpec<T> =
        tween(if (reduced) 70 else 180, easing = EnhancedEasing.standardDecelerate)

    /** Thought tree expansion - fluid and organic */
    fun <T> thoughtTreeExpand(): FiniteAnimationSpec<T> =
        springy(organicSpring, smoothDamping, tween(130))

    /** Sheet/modal transitions - smooth and confident */
    fun <T> sheetTransition(): FiniteAnimationSpec<T> =
        springy(snappySpring, smoothDamping, tween(160))

    /** Micro-interactions (press, hover) - immediate feedback */
    fun <T> microInteraction(): FiniteAnimationSpec<T> =
        tween(if (reduced) 50 else 130, easing = EnhancedEasing.emphasized)

    /** Streaming content appearance - gentle fade-in */
    fun <T> streamingReveal(): FiniteAnimationSpec<T> =
        tween(if (reduced) 80 else 220, easing = EnhancedEasing.emphasizedDecelerate)

    /** Model switcher pill morph */
    fun <T> pillMorph(): FiniteAnimationSpec<T> =
        springy(quickSpring, mediumDamping, tween(140))

    /** Glass surface appearance */
    fun <T> glassMorph(): FiniteAnimationSpec<T> =
        springy(organicSpring, smoothDamping, tween(180))

    /** Attachment chip animations */
    fun <T> attachmentChip(): FiniteAnimationSpec<T> =
        springy(snappySpring, mediumDamping, tween(110))

    /** Error/success banner slide */
    fun <T> bannerSlide(): FiniteAnimationSpec<T> =
        springy(quickSpring, smoothDamping, tween(150))

    /** Floating action bar appearance */
    fun <T> actionBarReveal(): FiniteAnimationSpec<T> =
        springy(snappySpring, mediumDamping, tween(140))

    /** Slide offset for navigation */
    fun slideOffset(): FiniteAnimationSpec<IntOffset> =
        springy(snappySpring, smoothDamping, tween(160))

    /** Rotation for loading indicators */
    fun rotation(): InfiniteRepeatableSpec<Float> =
        infiniteRepeatable(
            animation = tween(if (reduced) 800 else 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )

    /** Pulse for streaming indicators */
    fun pulse(): InfiniteRepeatableSpec<Float> =
        infiniteRepeatable(
            animation = tween(if (reduced) 600 else 1100, easing = EnhancedEasing.smoothPulse),
            repeatMode = RepeatMode.Reverse
        )

    /** Shimmer travel animation */
    fun shimmer(): InfiniteRepeatableSpec<Float> =
        infiniteRepeatable(
            animation = tween(if (reduced) 900 else 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )

    companion object {
        val BubbleMorph = 28.dp
        val StreamingPulse = 1100
        val MicroInteractionDelay = 60L
        val HapticFeedbackDelay = 5L
    }
}

/**
 * Enhanced easing curves for fluid motion.
 */
object EnhancedEasing {
    /** Material 3 emphasized curve - acceleration profile for prominent transitions */
    val emphasized: Easing = CubicBezierEasing(0.2f, 0.0f, 0f, 1.0f)

    /** Emphasized decelerate - smooth arrival */
    val emphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

    /** Emphasized accelerate - quick departure */
    val emphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

    /** Standard curve - balanced motion */
    val standard: Easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

    /** Standard decelerate - common for enter animations */
    val standardDecelerate: Easing = CubicBezierEasing(0f, 0f, 0.2f, 1.0f)

    /** Standard accelerate - common for exit animations */
    val standardAccelerate: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    /** Smooth pulse - for breathing animations */
    val smoothPulse: Easing = CubicBezierEasing(0.45f, 0.05f, 0.55f, 0.95f)

    /** Organic bounce - natural elastic feel */
    val organicBounce: Easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1.0f)
}
