package com.nexus.aichat.ui.providers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexus.aichat.core.model.AuthScheme
import com.nexus.aichat.core.model.ProviderConfig
import com.nexus.aichat.core.model.ProviderProtocol
import com.nexus.aichat.core.theme.ChatColors
import com.nexus.aichat.core.theme.NexusType
import com.nexus.aichat.data.remote.api.GenericOpenAICompatibleApi
import com.nexus.aichat.domain.model.ModelPresentation
import com.nexus.aichat.domain.model.ProviderPresentation
import com.nexus.aichat.ui.components.NexusCard

/**
 * Provider setup - the preset flow and the custom builder, in one screen.
 *
 * The design problem: a template needs three fields (template, key, done) while a self-hosted endpoint can
 * need a dozen (protocol, base URL, auth scheme, header name, model path, chat path override, timeouts,
 * raw body template, response path). Merging them into one long form punishes the common case.
 *
 * So there are two shapes, chosen by whether a provider id was passed in:
 *  - **new**: templates first, then a single key field, then a verification run that tells the user
 *    exactly which model answered and how fast;
 *  - **existing/new-custom**: the full form, with every advanced field present but folded away, plus
 *    inline model management.
 *
 * In both shapes, the app *proves* the configuration with a real request before the user leaves the
 * screen. "Saved" and "works" are different things, and only one of them is useful.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderSetupScreen(
    providerId: String,
    onFinished: () -> Unit,
    viewModel: ProvidersViewModel = hiltViewModel(),
) {
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val existing = remember(providers, providerId) { providers.firstOrNull { it.id == providerId } }
    val isNew = providerId.isBlank() || providerId == "new"

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = onFinished) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Back") }
                },
                title = {
                    Text(
                        text = when {
                            !isNew -> existing?.displayName ?: "Provider"
                            else -> "Add a provider"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
            )
        },
    ) { insets ->
        Column(modifier = Modifier.fillMaxSize().padding(insets)) {
            if (isNew) {
                PresetFirstFlow(viewModel = viewModel, onDone = onFinished)
            } else if (existing != null) {
                ProviderForm(provider = existing, viewModel = viewModel, onDone = onFinished)
            } else {
                Text(
                    text = "That provider no longer exists.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Template -> key -> verify. Three taps for the eleven providers that only need an API key. */
@Composable
private fun PresetFirstFlow(
    viewModel: ProvidersViewModel,
    onDone: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var selectedPresetId by remember { mutableStateOf<String?>(null) }
    var apiKey by remember { mutableStateOf("") }
    var customMode by remember { mutableStateOf(false) }

    val preset = ui.presets.firstOrNull { it.id == selectedPresetId }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
    ) {
        item("intro") {
            Text(
                text = "Pick a template. Nexus only needs an API key for these - everything else " +
                    "(URLs, protocol, headers) is already configured, and you can override it afterwards.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        items(ui.presets, key = { it.id }) { item ->
            val selected = item.id == selectedPresetId
            NexusCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selectedPresetId = item.id },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(item.displayName, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "${item.tagline} \u00b7 ${item.baseUrl}",
                            style = NexusType.timestamp(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (selected) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = ChatColors.success)
                }
            }
        }

        item("custom-toggle") {
            OutlinedButton(onClick = { customMode = !customMode }, modifier = Modifier.fillMaxWidth()) {
                Text(if (customMode) "Hide custom endpoint builder" else "I have my own endpoint (Ollama, LM Studio, vLLM, proxy)")
            }
        }

        if (customMode) {
            item("custom") {
                CustomDraftCard(viewModel = viewModel, onSaved = onDone)
            }
        }

        preset?.let { selected ->
            item("key") {
                NexusCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("API key for ${selected.displayName}", style = MaterialTheme.typography.labelLarge)
                        Text(
                            text = viewModel.authHint(selected.authScheme) +
                                (selected.keyHint.takeIf { it.isNotBlank() }?.let { " \u00b7 looks like $it" } ?: ""),
                            style = NexusType.timestamp(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it.trim() },
                            singleLine = true,
                            placeholder = { Text(selected.keyHint.ifBlank { "Paste your key" }) },
                            visualTransformation = PasswordVisualTransformation(),
                            leadingIcon = { Icon(Icons.Rounded.Key, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "Stored in the Android Keystore-backed vault on this device only. " +
                                "It is never written to the database or to logs.",
                            style = NexusType.timestamp(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = { viewModel.connectPreset(selected, apiKey.ifBlank { null }) },
                            enabled = ui.busyProviderId == null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (ui.busyProviderId == selected.id) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.size(8.dp))
                            }
                            Text("Connect and verify")
                        }
                        ui.statusMessage?.let { message ->
                            Text(message, style = MaterialTheme.typography.bodySmall, color = ChatColors.success)
                        }
                        ui.errorMessage?.let { message ->
                            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

/** The custom builder, trimmed to the fields that change behaviour. */
@Composable
private fun CustomDraftCard(
    viewModel: ProvidersViewModel,
    onSaved: () -> Unit,
) {
    var draft by remember { mutableStateOf(viewModel.draft(ProviderProtocol.OPENAI_COMPATIBLE)) }
    var apiKey by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }

    NexusCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Custom endpoint", style = MaterialTheme.typography.labelLarge)

            OutlinedTextField(
                value = draft.displayName,
                onValueChange = { draft = draft.copy(displayName = it) },
                label = { Text("Name") },
                singleLine = true,
                placeholder = { Text("Ollama on my desktop") },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = draft.baseUrl,
                onValueChange = { draft = draft.copy(baseUrl = it) },
                label = { Text("Base URL") },
                singleLine = true,
                placeholder = { Text("http://192.168.1.20:11434/v1") },
                supportingText = {
                    Text(
                        GenericOpenAICompatibleApi.guessFromHost(draft.baseUrl)
                            ?.let { "Looks like $it - a /v1 suffix is usually required" }
                            ?: "The app appends /chat/completions and /models",
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ProviderProtocol.entries.forEach { protocol ->
                    FilterChip(
                        selected = draft.protocol == protocol,
                        onClick = { draft = draft.copy(protocol = protocol) },
                        label = { Text(protocol.label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AuthScheme.entries.forEach { scheme ->
                    FilterChip(
                        selected = draft.auth.scheme == scheme,
                        onClick = { draft = draft.copy(auth = draft.auth.copy(scheme = scheme)) },
                        label = { Text(scheme.name.lowercase(), style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }

            if (draft.auth.scheme == AuthScheme.CUSTOM_HEADER) {
                OutlinedTextField(
                    value = draft.auth.headerName.orEmpty(),
                    onValueChange = { draft = draft.copy(auth = draft.auth.copy(headerName = it)) },
                    label = { Text("Header name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (draft.auth.scheme != AuthScheme.NONE) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it.trim() },
                    label = { Text("API key / token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Text(
                text = "Headers this app will send: " +
                    GenericOpenAICompatibleApi.previewHeaders(draft, apiKey)
                        .entries.joinToString(", ") { "${it.key}: ${it.value}" },
                style = NexusType.timestamp(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            var advanced by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth()) {
                Text(if (advanced) "Hide advanced" else "Advanced (paths, timeouts, raw REST)")
            }

            if (advanced) {
                OutlinedTextField(
                    value = draft.modelsPath,
                    onValueChange = { draft = draft.copy(modelsPath = it) },
                    label = { Text("Models path") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.chatPathOverride.orEmpty(),
                    onValueChange = { draft = draft.copy(chatPathOverride = it.ifBlank { null }) },
                    label = { Text("Chat path override (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.responseTextPath.orEmpty(),
                    onValueChange = { draft = draft.copy(responseTextPath = it.ifBlank { null }) },
                    label = { Text("Response text path (e.g. choices.0.delta.content)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (draft.protocol == ProviderProtocol.RAW_REST) {
                    OutlinedTextField(
                        value = draft.requestBodyTemplate.orEmpty(),
                        onValueChange = { draft = draft.copy(requestBodyTemplate = it.ifBlank { null }) },
                        label = { Text("Request body template") },
                        minLines = 3,
                        supportingText = { Text("Placeholders: {{model}} {{system}} {{messages}} {{prompt}} {{stream}}") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = draft.includeStreamUsage,
                        onCheckedChange = { draft = draft.copy(includeStreamUsage = it) },
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Request stream usage totals", style = MaterialTheme.typography.bodySmall)
                }
            }

            validationError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            StatusMessages(viewModel)

            Button(
                onClick = {
                    validationError = viewModel.validate(draft)
                    if (validationError == null) {
                        viewModel.saveCustom(draft, apiKey.ifBlank { null })
                        onSaved()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save and fetch models") }
        }
    }
}

/** The full form for an existing provider: connection, credentials, models. */
@Composable
private fun ProviderForm(
    provider: ProviderConfig,
    viewModel: ProvidersViewModel,
    onDone: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var draft by remember(provider.id) { mutableStateOf(provider) }
    var apiKey by remember { mutableStateOf("") }
    var manualModel by remember { mutableStateOf("") }

    LaunchedEffect(provider.id) { viewModel.refreshVault() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
    ) {
        item("connection") {
            NexusCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Connection", style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = "${draft.protocol.label} \u00b7 ${draft.baseUrl}",
                        style = NexusType.timestamp(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = ProviderPresentation.localHostWarning(draft, isEmulator = false)
                            ?: "Base URL is used for both chat and model discovery.",
                        style = NexusType.timestamp(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = draft.baseUrl,
                        onValueChange = { draft = draft.copy(baseUrl = it) },
                        label = { Text("Base URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draft.selectedModelId.orEmpty(),
                        onValueChange = { draft = draft.copy(selectedModelId = it.ifBlank { null }) },
                        label = { Text("Selected model id") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item("credentials") {
            NexusCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Credentials", style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = viewModel.authHint(draft.auth.scheme),
                        style = NexusType.timestamp(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it.trim() },
                        label = { Text("Replace stored key (optional)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.update(draft, apiKey.ifBlank { null }) }) { Text("Save") }
                        OutlinedButton(onClick = { draft.auth.vaultKey?.let(viewModel::clearKey) }) { Text("Clear key") }
                    }
                }
            }
        }

        item("models-header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Models", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { viewModel.fetchModels(provider.id) }) {
                    if (ui.busyProviderId == provider.id) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Fetch", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        items(draft.models, key = { it.id }) { model ->
            NexusCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        draft = draft.copy(selectedModelId = model.id)
                        viewModel.selectModel(provider.id, model.id)
                    },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(model.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        Text(
                            text = ModelPresentation.badges(model).joinToString(" \u00b7 "),
                            style = NexusType.timestamp(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ModelPresentation.latencyLabel(model)?.let {
                        Text(it, style = NexusType.timestamp(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { viewModel.pingModels(provider.id) }) {
                        Icon(Icons.Rounded.Speed, contentDescription = "Test", modifier = Modifier.size(16.dp))
                    }
                    if (draft.selectedModelId == model.id) {
                        Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = ChatColors.success)
                    }
                }
            }
        }

        item("manual-model") {
            NexusCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Add a model by hand", style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = "For servers whose /models endpoint is missing or wrong - a common case on " +
                            "self-hosted runners.",
                        style = NexusType.timestamp(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = manualModel,
                        onValueChange = { manualModel = it },
                        label = { Text("Model id") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            if (manualModel.isNotBlank()) viewModel.addManualModel(draft, manualModel)
                        },
                    ) { Text("Add model") }
                }
            }
        }

        item("danger") {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            if (!provider.isBuiltInPreset) {
                OutlinedButton(
                    onClick = {
                        viewModel.delete(provider.id)
                        onDone()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Remove this provider")
                }
            }
        }

        item("messages") { StatusMessages(viewModel) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** Shared status/error pair, so every step of the wizard reports the same way. */
@Composable
private fun StatusMessages(viewModel: ProvidersViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    Column {
        ui.statusMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ChatColors.success) }
        ui.errorMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
