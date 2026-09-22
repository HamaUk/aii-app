package com.nexus.aichat.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexus.aichat.core.model.CornerStyle
import com.nexus.aichat.core.model.ThemeBrightnessMode
import com.nexus.aichat.core.model.ThemePreset
import com.nexus.aichat.core.theme.NexusType
import com.nexus.aichat.ui.components.NexusCard

/**
 * Appearance: the theme selector.
 *
 * The palette swatch is rendered from the preset's own colours, not a stock image - the point of a theme
 * picker is that you can see the difference *before* applying it. Applying is immediate (no "save"
 * button): a theme you cannot feel while choosing is a theme you have to guess at.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val appearance = settings.appearance

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Back") }
                },
                title = { Text("Appearance", style = MaterialTheme.typography.titleMedium) },
            )
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item("presets-header") {
                Text(
                    text = "THEME",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }

            items(ThemePreset.entries.toList(), key = { it.id }) { preset ->
                NexusCard(modifier = Modifier.fillMaxWidth().clickable { viewModel.setPreset(preset) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ThemeSwatch(preset)
                        Column(Modifier.weight(1f)) {
                            Text(preset.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                preset.blurb,
                                style = NexusType.timestamp(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (preset == appearance.preset) {
                            Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            item("brightness") {
                NexusCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Brightness", style = MaterialTheme.typography.labelLarge)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(ThemeBrightnessMode.entries.toList(), key = { it.name }) { mode ->
                                FilterChip(
                                    selected = appearance.brightnessMode == mode,
                                    onClick = { viewModel.setBrightness(mode) },
                                    label = {
                                        Text(
                                            when (mode) {
                                                ThemeBrightnessMode.FOLLOW_SYSTEM -> "System"
                                                ThemeBrightnessMode.ALWAYS_DARK -> "Always dark"
                                                ThemeBrightnessMode.ALWAYS_LIGHT -> "Always light"
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }

            item("shapes") {
                NexusCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Corner style", style = MaterialTheme.typography.labelLarge)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(CornerStyle.entries.toList(), key = { it.name }) { style ->
                                FilterChip(
                                    selected = appearance.cornerStyle == style,
                                    onClick = { viewModel.setCornerStyle(style) },
                                    label = { Text(style.label) },
                                )
                            }
                        }
                        Text(
                            text = CornerStyle.entries.first { it == appearance.cornerStyle }.label + ": " +
                                CornerStyle.entries.first { it == appearance.cornerStyle }.blurb,
                            style = NexusType.timestamp(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item("toggles") {
                NexusCard {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ToggleRow(
                            title = "AMOLED pure black",
                            subtitle = "Use true #000000 on dark screens. Saves power on OLED panels.",
                            checked = appearance.pureBlackInDark,
                            onChange = viewModel::setPureBlack,
                        )
                        ToggleRow(
                            title = "Material You colours",
                            subtitle = "Derive the palette from your wallpaper (Android 12+, works with the Material You theme).",
                            checked = appearance.useDynamicColor,
                            onChange = viewModel::setDynamicColor,
                        )
                        ToggleRow(
                            title = "Haptic feedback",
                            subtitle = "Short ticks on send, stop, approval and long-press.",
                            checked = appearance.hapticsEnabled,
                            onChange = viewModel::setHaptics,
                        )
                        ToggleRow(
                            title = "Reduce motion",
                            subtitle = "Shortens or removes springs, pulses and shimmer - useful for vestibular comfort and older hardware.",
                            checked = appearance.reducedMotion,
                            onChange = viewModel::setReducedMotion,
                        )
                    }
                }
            }

            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}

/** Three-colour preview of a preset: background, surface, accent. */
@Composable
private fun ThemeSwatch(preset: ThemePreset) {
    val colors = com.nexus.aichat.core.designsystem.theme.NexusPalettes.colorScheme(
        preset = preset,
        dark = true,
        pureBlack = true,
    )
    Row(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(colors.background),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(colors.primary))
        Box(Modifier.size(10.dp).clip(CircleShape).background(colors.surfaceContainerHigh))
    }
}
