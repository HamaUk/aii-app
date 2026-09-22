package com.nexus.aichat.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PersonOutline
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexus.aichat.core.theme.ChatColors
import com.nexus.aichat.core.theme.NexusType
import com.nexus.aichat.ui.components.NexusCard

/**
 * Settings hub.
 *
 * Five destinations, each answering one question a user actually asks: *how does it look*, *how does it
 * behave*, *what is it allowed to do*, *how does it talk to me*, *where do my keys live*. Everything else -
 * timeouts, paths, raw templates - lives inside the thing it configures (a provider's own screen) instead
 * of in a global list of unrelated switches.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenAppearance: () -> Unit,
    onOpenBehavior: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenProviders: () -> Unit,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val keystoreHealthy by viewModel.keystoreHealthy.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Back") }
                },
                title = { Text("Settings", style = MaterialTheme.typography.titleMedium) },
            )
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item("appearance") {
                SettingsRow(
                    icon = Icons.Rounded.Palette,
                    title = "Appearance",
                    subtitle = "${settings.appearance.preset.label} \u00b7 ${settings.appearance.brightnessMode.name.lowercase().replace('_', ' ')}",
                    onClick = onOpenAppearance,
                )
            }
            item("behavior") {
                SettingsRow(
                    icon = Icons.Rounded.AutoAwesome,
                    title = "Agent behaviour",
                    subtitle = "${settings.agent.mode.label} \u00b7 up to ${settings.agent.maxSteps} steps",
                    onClick = onOpenBehavior,
                )
            }
            item("tools") {
                SettingsRow(
                    icon = Icons.Rounded.Extension,
                    title = "Tools",
                    subtitle = buildString {
                        val tools = settings.tools
                        val on = listOfNotNull(
                            "web fetch".takeIf { tools.webFetchEnabled },
                            "web search".takeIf { tools.webSearchEnabled },
                            "documents".takeIf { tools.documentReaderEnabled },
                            "time".takeIf { tools.dateTimeEnabled },
                        )
                        append(if (on.isEmpty()) "All disabled" else on.joinToString(", "))
                    },
                    onClick = onOpenTools,
                )
            }
            item("rules") {
                SettingsRow(
                    icon = Icons.Rounded.PersonOutline,
                    title = "Custom rules / persona",
                    subtitle = "Global instructions applied to every chat",
                    onClick = onOpenRules,
                )
            }

            item("security-header") {
                Text(
                    text = "SECURITY",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, start = 4.dp),
                )
            }
            item("providers") {
                SettingsRow(
                    icon = Icons.Rounded.Security,
                    title = "Providers and keys",
                    subtitle = "Add an endpoint, paste a key, fetch models",
                    onClick = onOpenProviders,
                )
            }
            item("vault") {
                NexusCard(modifier = Modifier.fillMaxWidth().clickable { viewModel.runKeystoreSelfTest() }) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Keystore self-test", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = when (keystoreHealthy) {
                                null -> "Tap to verify that this device can encrypt and read back a secret."
                                true -> "Passed - keys are encrypted with a hardware-backed key."
                                false -> "Failed - key storage is unavailable on this device."
                            },
                            style = NexusType.timestamp(),
                            color = when (keystoreHealthy) {
                                false -> MaterialTheme.colorScheme.error
                                true -> ChatColors.success
                                null -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    NexusCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = NexusType.timestamp(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
