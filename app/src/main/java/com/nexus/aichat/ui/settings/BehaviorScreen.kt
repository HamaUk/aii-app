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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import com.nexus.aichat.core.model.AgentMode
import com.nexus.aichat.core.model.ReasoningVisibility
import com.nexus.aichat.core.model.ToolApprovalPolicy
import com.nexus.aichat.core.theme.NexusType
import com.nexus.aichat.ui.components.NexusCard
import kotlin.math.roundToInt

/**
 * Agent behaviour.
 *
 * These are the switches that decide whether the app is a chatbot or an agent, so each one is explained in
 * terms of what it *does*, not what it is called: "step budget" is meaningless; "how many times it may
 * call a tool before it stops and asks you" is a decision a user can make.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BehaviorScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val agent = settings.agent

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Back") }
                },
                title = { Text("Agent behaviour", style = MaterialTheme.typography.titleMedium) },
            )
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item("mode") {
                NexusCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Mode", style = MaterialTheme.typography.labelLarge)
                        AgentMode.entries.forEach { mode ->
                            val selected = agent.mode == mode
                            Column {
                                FilterChip(
                                    selected = selected,
                                    onClick = { viewModel.setAgentMode(mode) },
                                    label = { Text(mode.label) },
                                )
                                Text(
                                    text = mode.blurb,
                                    style = NexusType.timestamp(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                                )
                            }
                        }
                    }
                }
            }

            item("budget") {
                NexusCard {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Step budget: ${agent.maxSteps}", style = MaterialTheme.typography.labelLarge)
                        Text(
                            text = "How many reasoning rounds the agent may take before it stops and hands " +
                                "control back to you. Higher means more autonomous, and more tokens.",
                            style = NexusType.timestamp(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            value = agent.maxSteps.toFloat(),
                            onValueChange = { viewModel.setMaxSteps(it.roundToInt()) },
                            valueRange = 1f..24f,
                            steps = 22,
                        )
                    }
                }
            }

            item("approvals") {
                NexusCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Tool approvals", style = MaterialTheme.typography.labelLarge)
                        Text(
                            text = "When the agent wants to use a tool, should it just do it?",
                            style = NexusType.timestamp(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ToolApprovalPolicy.entries.forEach { policy ->
                            FilterChip(
                                selected = agent.approvalPolicy == policy,
                                onClick = { viewModel.setApprovalPolicy(policy) },
                                label = { Text(policy.label) },
                            )
                        }
                        Text(
                            text = "Read-only tools (fetching a page, reading time) are always allowed. The " +
                                "policy applies to anything that could leave your device or read your files.",
                            style = NexusType.timestamp(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item("reasoning") {
                NexusCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Thought process", style = MaterialTheme.typography.labelLarge)
                        ReasoningVisibility.entries.forEach { visibility ->
                            FilterChip(
                                selected = agent.showReasoning == visibility,
                                onClick = { viewModel.setReasoningVisibility(visibility) },
                                label = { Text(visibility.label) },
                            )
                        }
                        ToggleRow(
                            title = "Ask models to think when they can",
                            subtitle = "Enables extended thinking / reasoning effort on models that support it, " +
                                "and streams the tokens into the thought tree.",
                            checked = agent.autoEnableReasoning,
                            onChange = viewModel::setAutoReasoning,
                        )
                        ToggleRow(
                            title = "Let the agent ask me questions",
                            subtitle = "Allows the ask_user tool: the run pauses mid-flight until you answer, " +
                                "instead of guessing.",
                            checked = agent.allowClarificationPauses,
                            onChange = viewModel::setClarificationPauses,
                        )
                    }
                }
            }

            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}

@Composable
internal fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = NexusType.timestamp(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
