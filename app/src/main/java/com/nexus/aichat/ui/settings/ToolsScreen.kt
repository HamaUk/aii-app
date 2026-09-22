package com.nexus.aichat.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexus.aichat.core.model.ApprovalRequirement
import com.nexus.aichat.core.theme.ChatColors
import com.nexus.aichat.core.theme.NexusType
import com.nexus.aichat.domain.tools.ToolPlugin
import com.nexus.aichat.ui.components.NexusCard

/**
 * Tools.
 *
 * Each row states three things, because all three matter to the decision: what the tool can reach, whether
 * it needs the network, and whether it is destructive. The "reach the local network" switch is the one that
 * needs the most care - it is how a user enables a self-hosted endpoint *and* how an SSRF hole gets opened -
 * so it is explained rather than just labelled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val tools by viewModel.tools.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Back") }
                },
                title = { Text("Tools", style = MaterialTheme.typography.titleMedium) },
            )
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item("intro") {
                Text(
                    text = "Tools are what make the agent useful: it can read a page, search, page through a " +
                        "document and check the time. Everything disabled here is also hidden from the model, " +
                        "so it cannot be called by mistake.",
                    style = NexusType.timestamp(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            items(tools, key = { it.name }) { descriptor ->
                val plugin = ToolPlugin.from(descriptor)
                NexusCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(plugin.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(plugin.displayName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = plugin.blurb,
                                style = NexusType.timestamp(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = buildString {
                                    append(plugin.category.name.lowercase())
                                    if (plugin.requiresNetwork) append(" \u00b7 network")
                                    if (plugin.isDestructive) append(" \u00b7 writes")
                                    if (descriptor.defaultApproval == ApprovalRequirement.ASK_ONCE_PER_RUN) {
                                        append(" \u00b7 asks first")
                                    }
                                },
                                style = NexusType.timestamp(),
                                color = ChatColors.toolChip,
                            )
                        }
                        androidx.compose.material3.Switch(
                            checked = descriptor.isEnabled,
                            onCheckedChange = { viewModel.setToolEnabled(descriptor.name, it) },
                        )
                    }
                }
            }

            item("local-network") {
                NexusCard {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ToggleRow(
                            title = "Allow fetching from the local network",
                            subtitle = "Lets the web tool read http://192.168.x.x and localhost. Needed if you " +
                                "want the agent to fetch from a machine in your home or office network. Off by " +
                                "default: an agent that can reach your LAN can also reach router admin pages.",
                            checked = settings.tools.allowPrivateHosts,
                            onChange = viewModel::setAllowPrivateHosts,
                        )
                    }
                }
            }

            item("search-keys") {
                NexusCard {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Web search provider", style = MaterialTheme.typography.labelLarge)
                        Text(
                            text = "Nexus does not proxy anything. Search runs through a provider you " +
                                "configure - add a Brave or Tavily key under Providers to enable web_search. " +
                                "Until then the agent can still fetch URLs you give it.",
                            style = NexusType.timestamp(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}
