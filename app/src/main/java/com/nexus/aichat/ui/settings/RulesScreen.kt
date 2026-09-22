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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexus.aichat.core.model.PersonaPresets
import com.nexus.aichat.core.model.SystemRule
import com.nexus.aichat.core.theme.NexusType
import com.nexus.aichat.ui.components.NexusCard

/**
 * Custom rules / persona.
 *
 * A rule is a named block of instructions. Global rules apply to every chat; a rule that is not global can
 * be attached to a specific conversation, which is how you get "this chat is about the Rust rewrite, follow
 * the house style guide" without polluting every other chat.
 *
 * The composer assembles them in a fixed, documented order (global -> chat-specific -> per-chat override),
 * so a user can predict which instruction wins when two of them disagree.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<SystemRule?>(null) }
    var creating by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("New rule") },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            )
        },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Back") }
                },
                title = { Text("Custom rules", style = MaterialTheme.typography.titleMedium) },
            )
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp),
        ) {
            item("intro") {
                Text(
                    text = "Rules are prepended to the system prompt on every request, in order: global " +
                        "rules first, then rules attached to a chat, then that chat's own instructions. " +
                        "Add rules the model keeps forgetting - tone, format, language, boundaries.",
                    style = NexusType.timestamp(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            items(rules, key = { it.id }) { rule ->
                NexusCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editing = rule },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = rule.title.ifBlank { "Untitled rule" },
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = rule.isEnabled,
                                onCheckedChange = { viewModel.setRuleEnabled(rule.id, it) },
                            )
                        }
                        Text(
                            text = rule.body.take(180),
                            style = NexusType.timestamp(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (rule.appliesGlobally) "applies to every chat" else "attach per chat",
                                style = NexusType.timestamp(),
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (rule.isBuiltIn) {
                                Text("built in", style = NexusType.timestamp(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { editing = rule }) {
                                Icon(Icons.Rounded.Edit, contentDescription = "Edit", modifier = Modifier.size(18.dp))
                            }
                            if (!rule.isBuiltIn) {
                                IconButton(onClick = { viewModel.deleteRule(rule.id) }) {
                                    Icon(Icons.Rounded.Delete, contentDescription = "Delete", modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }

            if (rules.none { it.isBuiltIn }) {
                item("built-ins") {
                    NexusCard {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Starting points", style = MaterialTheme.typography.labelLarge)
                            PersonaPresets.ALL.forEach { preset ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.createRule(preset.title, preset.body, global = true)
                                        },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(preset.title, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            text = preset.body.take(100),
                                            style = NexusType.timestamp(),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                        )
                                    }
                                    Icon(Icons.Rounded.Add, contentDescription = "Add", modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.size(24.dp)) }
        }
    }

    if (creating || editing != null) {
        RuleEditor(
            rule = editing,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { rule ->
                if (rule.id.isBlank()) {
                    viewModel.createRule(rule.title, rule.body, rule.appliesGlobally)
                } else {
                    viewModel.updateRule(rule)
                }
                creating = false
                editing = null
            },
        )
    }
}

@Composable
private fun RuleEditor(
    rule: SystemRule?,
    onDismiss: () -> Unit,
    onSave: (SystemRule) -> Unit,
) {
    var title by remember(rule) { mutableStateOf(rule?.title.orEmpty()) }
    var body by remember(rule) { mutableStateOf(rule?.body.orEmpty()) }
    var global by remember(rule) { mutableStateOf(rule?.appliesGlobally ?: true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (rule == null) "New rule" else "Edit rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Name") },
                    singleLine = true,
                    placeholder = { Text("Answer in British English") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Instruction") },
                    minLines = 4,
                    maxLines = 10,
                    placeholder = { Text("Always reply in British English, use -ise spellings, and keep answers under 200 words unless asked for detail.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                ToggleRow(
                    title = "Apply to every chat",
                    subtitle = "Off means you attach it to individual conversations.",
                    checked = global,
                    onChange = { global = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        (rule ?: SystemRule(id = "", title = "", body = "")).copy(
                            title = title.trim(),
                            body = body.trim(),
                            appliesGlobally = global,
                        ),
                    )
                },
                enabled = body.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
