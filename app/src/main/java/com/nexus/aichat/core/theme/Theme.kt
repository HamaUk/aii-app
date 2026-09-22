package com.nexus.aichat.core.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.nexus.aichat.core.designsystem.theme.NexusTheme
import com.nexus.aichat.core.model.AppearanceSettings
import com.nexus.aichat.core.model.ThemeBrightnessMode

/**
 * The app's single theming entry point.
 *
 * `:core:designsystem` implements the palettes, tokens, motion and shapes; this wrapper exists so
 * features depend on *the app's* theme API and a future design-system refactor touches one file.
 * It also owns the app-specific default: Nexus ships dark-first (AMOLED), because a chat app is used
 * at night more than a spreadsheet is.
 */
@Composable
fun NexusAppTheme(
    appearance: AppearanceSettings,
    content: @Composable () -> Unit,
) {
    // Resolve FOLLOW_SYSTEM here, once, so neither the design system nor any feature ever has to
    // ask whether it is dark: a Material You preset and an AMOLED preset must behave identically.
    val resolved = when (appearance.brightnessMode) {
        ThemeBrightnessMode.FOLLOW_SYSTEM -> appearance.copy(
            brightnessMode = if (isSystemInDarkTheme()) {
                ThemeBrightnessMode.ALWAYS_DARK
            } else {
                ThemeBrightnessMode.ALWAYS_LIGHT
            },
        )
        else -> appearance
    }
    NexusTheme(appearance = resolved, content = content)
}

/** Preview/dev convenience: the shipped default appearance, ignoring user settings. */
@Composable
internal fun PreviewTheme(content: @Composable () -> Unit) =
    NexusTheme(appearance = AppearanceSettings(), content = content)
