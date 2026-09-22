package com.nexus.aichat.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nexus.aichat.core.model.AppearanceSettings
import com.nexus.aichat.core.model.CornerStyle
import com.nexus.aichat.core.model.ThemeBrightnessMode
import com.nexus.aichat.core.model.ThemePreset

/**
 * Extra design tokens that Material 3 has no role for.
 *
 * These exist because the app draws things M3 does not describe: glass chrome over a scrolling feed,
 * user vs assistant bubble fills, the reasoning-tree gutter, inline code on a coloured bubble, and
 * the code-block surface (which must stay readable in every palette).
 */
@Immutable
data class NexusTokens(
    val glassFill: Color,
    val glassBorder: Color,
    val glassHighlight: Color,
    val userBubble: Color,
    val userBubbleText: Color,
    val assistantBubble: Color,
    val assistantBubbleText: Color,
    val codeSurface: Color,
    val codeSurfaceText: Color,
    val reasoningGutter: Color,
    val toolChip: Color,
    val success: Color,
    val warning: Color,
    val info: Color,
    val scrim: Color,
    val isPureBlack: Boolean,
)

val LocalNexusTokens: ProvidableCompositionLocal<NexusTokens> = staticCompositionLocalOf {
    error("NexusTokens requested outside NexusTheme")
}

val LocalNexusAppearance: ProvidableCompositionLocal<AppearanceSettings> = staticCompositionLocalOf {
    AppearanceSettings()
}

val LocalNexusMotion: ProvidableCompositionLocal<NexusMotionSpec> = staticCompositionLocalOf {
    NexusMotionSpec()
}

/**
 * The single entry point every screen is wrapped in.
 *
 * Precedence for the colour scheme:
 *   1. Material You dynamic colour, when the preset is [ThemePreset.MATERIAL_YOU] and the device is
 *      Android 12+ with a wallpaper-based palette available;
 *   2. the hand-tuned palette for the selected preset;
 *   3. light fallback for [ThemeBrightnessMode.ALWAYS_LIGHT] / a light system theme.
 */
@Composable
fun NexusTheme(
    appearance: AppearanceSettings = AppearanceSettings(),
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (appearance.brightnessMode) {
        ThemeBrightnessMode.FOLLOW_SYSTEM -> systemDark
        ThemeBrightnessMode.ALWAYS_DARK -> true
        ThemeBrightnessMode.ALWAYS_LIGHT -> false
    }

    val context = LocalContext.current
    val scheme: ColorScheme = remember(schemeKey(appearance, dark)) {
        val useDynamic = appearance.useDynamicColor &&
            appearance.preset == ThemePreset.MATERIAL_YOU &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        when {
            useDynamic && dark -> runCatching { dynamicDarkColorScheme(context) }.getOrElse { fallback(appearance, dark) }
            useDynamic -> runCatching { dynamicLightColorScheme(context) }.getOrElse { fallback(appearance, dark) }
            else -> fallback(appearance, dark)
        }
    }

    val tokens = remember(scheme, appearance) { tokensFor(scheme, appearance) }

    CompositionLocalProvider(
        LocalNexusTokens provides tokens,
        LocalNexusAppearance provides appearance,
        LocalNexusMotion provides NexusMotionSpec(reduced = appearance.reducedMotion),
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = NexusTypography.Default,
            shapes = NexusShapes.forStyle(appearance.cornerStyle),
            content = content,
        )
    }
}

private fun schemeKey(appearance: AppearanceSettings, dark: Boolean) =
    "${appearance.preset.id}|$dark|${appearance.useDynamicColor}|${appearance.pureBlackInDark}"

private fun fallback(appearance: AppearanceSettings, dark: Boolean): ColorScheme =
    NexusPalettes.colorScheme(
        preset = appearance.preset,
        dark = dark,
        pureBlack = appearance.pureBlackInDark,
    )

private fun tokensFor(scheme: ColorScheme, appearance: AppearanceSettings): NexusTokens {
    val pureBlack = appearance.pureBlackInDark && appearance.preset == ThemePreset.AMOLED_PURE_BLACK
    return NexusTokens(
        glassFill = scheme.surfaceContainerHighest.copy(alpha = 0.72f),
        glassBorder = scheme.outlineVariant.copy(alpha = 0.55f),
        glassHighlight = Color.White.copy(alpha = 0.06f),
        userBubble = scheme.primaryContainer,
        userBubbleText = scheme.onPrimaryContainer,
        assistantBubble = if (pureBlack) scheme.surfaceContainerHigh else scheme.surfaceContainer,
        assistantBubbleText = scheme.onSurface,
        codeSurface = if (pureBlack) Color(0xFF07090B) else scheme.surfaceContainerLowest,
        codeSurfaceText = Color(0xFFD7E3F4),
        reasoningGutter = scheme.outlineVariant,
        toolChip = scheme.secondaryContainer.copy(alpha = 0.65f),
        success = Color(0xFF7ED99B),
        warning = Color(0xFFF5C66B),
        info = scheme.primary,
        scrim = scheme.scrim,
        isPureBlack = pureBlack,
    )
}
