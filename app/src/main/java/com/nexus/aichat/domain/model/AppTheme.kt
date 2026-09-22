package com.nexus.aichat.domain.model

import com.nexus.aichat.core.model.AppearanceSettings
import com.nexus.aichat.core.model.CornerStyle
import com.nexus.aichat.core.model.ThemeBrightnessMode
import com.nexus.aichat.core.model.ThemePreset

/**
 * Theme vocabulary, as the app layer refers to it.
 *
 * The palettes themselves live in `:core:designsystem` and the *choice* lives in `:core:model`
 * ([ThemePreset], [AppearanceSettings]) - both of which a non-UI module may need to read. This file is the
 * app-facing name for that choice, so screens and settings code never import the design system just to talk
 * about which theme is selected.
 */
typealias AppTheme = AppearanceSettings

/** The six presets shipped with the app, in the order the picker shows them. */
val AppThemeChoices: List<ThemePreset> get() = ThemePreset.entries

/** Dark-first by default; the picker offers follow-system, always-dark and always-light. */
val DefaultBrightness: ThemeBrightnessMode get() = ThemeBrightnessMode.ALWAYS_DARK

/** Corner personality, separate from colour: organic, rounded or sharp. */
val AppCornerStyles: List<CornerStyle> get() = CornerStyle.entries

/** Theme identity for analytics-free logging and for the drawer's compact label. */
val AppTheme.id: String get() = "${preset.id}|${brightnessMode.name}|${cornerStyle.name}"
