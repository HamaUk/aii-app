package com.nexus.aichat.ui.providers

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexus.aichat.core.model.ProviderConfig
import com.nexus.aichat.core.theme.ChatColors
import com.nexus.aichat.core.theme.NexusType
import com.nexus.aichat.domain.model.ProviderPresentation
import com.nexus.aichat.ui.components.NexusCard

/**
 * Provider list.
 *
 * Two sections, and the order matters: **connected** providers first (what the user configured, with the
 * model they chose and its verified latency), then the **templates** they have not used yet. A preset is
 * never "installed" until a key is stored, so this screen never lies about what is ready to use.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(
    onAddProvider: () -> Unit,
    onEditProvider: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: ProvidersViewModel = hiltViewModel(),
) {
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val storedKeys by viewModel.storedKeyNames.collectAsStateWithLifecycle()

    LaunchedEffect(providers.size) { viewModel.refreshVault() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddProvider,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("Add provider") },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            )
        },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Back") }
                },
                title = { Text("Providers", style = MaterialTheme.typography.titleMedium) },
            )
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp),
        ) {
            ui.statusMessage?.let { message ->
                item("status") {
                    Text(message, style = MaterialTheme.typography.bodySmall, color = ChatColors.success)
                }
            }
            ui.errorMessage?.let { message ->
                item("error") {
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }

            if (providers.isEmpty()) {
                item("empty") {
                    Text(
                        text = "No providers configured. Start from a template - you only need an API key - or " +
                            "point the app at your own endpoint.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(providers, key = { it.id }) { provider ->
                ProviderCard(
                    provider = provider,
                    hasKey = provider.auth.vaultKey in storedKeys,
                    busy = ui.busyProviderId == provider.id,
                    expanded = ui.expandedProviderId == provider.id,
                    onToggleExpand = {},
                    onEdit = { onEditProvider(provider.id) },
                    onFetch = { viewModel.fetchModels(provider.id) },
                    onPing = { viewModel.pingModels(provider.id) },
                    onDelete = { viewModel.delete(provider.id) },
                )
            }

            item("templates-header") {
                Text(
                    text = "TEMPLATES",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, start = 4.dp),
                )
            }

            items(ui.presets, key = { "preset-${it.id}" }) { preset ->
                val configured = providers.any { it.presetId == preset.id }
                PresetRow(
                    title = preset.displayName,
                    subtitle = preset.tagline,
                    configured = configured,
                    busy = ui.busyProviderId == preset.id,
                    onClick = onAddProvider,
                )
            }

            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}

@Composable
private fun ProviderCard(
    provider: ProviderConfig,
    hasKey: Boolean,
    busy: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onEdit: () -> Unit,
    onFetch: () -> Unit,
    onPing: () -> Unit,
    onDelete: () -> Unit,
) {
    val readiness = ProviderPresentation.readiness(provider, hasKey)
    NexusCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            when (readiness) {
                                ProviderPresentation.Readiness.Ready -> ChatColors.success
                                ProviderPresentation.Readiness.NeedsApiKey -> ChatColors.warning
                                else -> MaterialTheme.colorScheme.error
                            },
                        ),
                )
                Text(
                    text = provider.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }

            Text(
                text = buildString {
                    append(provider.protocol.label)
                    append(" \u00b7 ")
                    append(provider.selectedModelId ?: "no model selected")
                    append(" \u00b7 ")
                    append(provider.models.size)
                    append(" models")
                },
                style = NexusType.timestamp(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            when (readiness) {
                ProviderPresentation.Readiness.NeedsApiKey -> Text(
                    text = "API key required",
                    style = MaterialTheme.typography.bodySmall,
                    color = ChatColors.warning,
                )
                ProviderPresentation.Readiness.NeedsModel -> Text(
                    text = "Fetch models and pick one",
                    style = MaterialTheme.typography.bodySmall,
                    color = ChatColors.warning,
                )
                else -> Unit
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onFetch) {
                    Icon(Icons.Rounded.CloudDownload, contentDescription = "Fetch models", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onPing) {
                    Icon(Icons.Rounded.Speed, contentDescription = "Test latency", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Rounded.Key, contentDescription = "Key and settings", modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.weight(1f))
                if (!provider.isBuiltInPreset) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Remove", modifier = Modifier.size(18.dp))
                    }
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    provider.models.take(5).forEach { model ->
                        Text(
                            text = "${model.displayName} \u00b7 ${model.contextWindow ?: "-"} ctx",
                            style = NexusType.timestamp(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetRow(
    title: String,
    subtitle: String,
    configured: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
) {
    NexusCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else if (configured) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = "Configured", tint = ChatColors.success)
            } else {
                Icon(Icons.Rounded.Add, contentDescription = "Add", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
