package com.nexus.aichat.ui.chat.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexus.aichat.core.theme.ChatColors
import com.nexus.aichat.core.theme.NexusType
import com.nexus.aichat.domain.model.ModelPresentation
import com.nexus.aichat.domain.model.ProviderConfig
import com.nexus.aichat.core.model.ModelInfo

/**
 * Quick model switch, inside the chat.
 *
 * The requirement that shaped this: switching models must never mean a trip to Settings. So the pill in
 * the composer opens a sheet that lists every enabled provider and its models, with capability badges and
 * the last measured latency, and switching is one tap that only affects *this* conversation's next turn.
 *
 * The refresh button runs real discovery against `/models`; a provider whose catalogue is stale is the
 * single most common reason a user thinks a model "doesn't work".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSwitcherSheet(
    visible: Boolean,
    providers: List<ProviderConfig>,
    activeProviderId: String?,
    activeModelId: String?,
    isRefreshing: Boolean,
    onDismiss: () -> Unit,
    onSelect: (providerId: String, modelId: String) -> Unit,
    onRefresh: (providerId: String) -> Unit,
    onManageProviders: () -> Unit,
) {
    if (!visible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .heightIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Choose a model", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onManageProviders) {
                    Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Providers")
                }
            }

            if (providers.isEmpty()) {
                Text(
                    text = "No providers yet. Add one to start chatting.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onManageProviders) { Text("Add a provider") }
                Spacer(Modifier.height(24.dp))
                return@Column
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                providers.forEach { provider ->
                    item(key = "provider-${provider.id}") {
                        ProviderHeader(
                            provider = provider,
                            isRefreshing = isRefreshing,
                            onRefresh = { onRefresh(provider.id) },
                        )
                    }
                    if (provider.models.isEmpty()) {
                        item(key = "empty-${provider.id}") {
                            Text(
                                text = "No models known yet - tap refresh, or add one by hand in the provider settings.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                    items(provider.models, key = { "${provider.id}:${it.id}" }) { model ->
                        ModelRow(
                            model = model,
                            selected = provider.id == activeProviderId && model.id == activeModelId,
                            onClick = { onSelect(provider.id, model.id) },
                        )
                    }
                    item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)) }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun ProviderHeader(
    provider: ProviderConfig,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = provider.displayName,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "  ${provider.models.size} models",
            style = NexusType.timestamp(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        if (isRefreshing) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = onRefresh) {
                Icon(
                    Icons.Rounded.CloudDownload,
                    contentDescription = "Fetch models",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ModelRow(
    model: ModelInfo,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scale by animateFloatAsState(if (selected) 1f else 0.98f, label = "model-scale")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.35f)
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = model.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(ModelPresentation.badges(model).joinToString(" \u00b7 "))
                    ModelPresentation.contextLabel(model)?.let { context ->
                        if (isNotEmpty()) append(" \u00b7 ")
                        append(context)
                    }
                },
                style = NexusType.timestamp(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        ModelPresentation.latencyLabel(model)?.let { label ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            when (ModelPresentation.isLatencyHealthy(model)) {
                                true -> ChatColors.success
                                false -> MaterialTheme.colorScheme.error
                                null -> MaterialTheme.colorScheme.outline
                            },
                        ),
                )
                Text(
                    text = label,
                    style = NexusType.timestamp(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (selected) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = "Selected",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * The pill itself. Kept separate from the sheet so the composer can render it without pulling the sheet's
 * state into its own recomposition scope.
 */
@Composable
fun ModelPill(
    label: String,
    providerLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (providerLabel.isNotBlank()) {
            Text(
                providerLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
