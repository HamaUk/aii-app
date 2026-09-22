package com.nexus.aichat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexus.aichat.core.model.Attachment
import com.nexus.aichat.core.model.Message
import com.nexus.aichat.domain.model.MessagePresentation
import com.nexus.aichat.ui.chat.components.ChatInputBar
import com.nexus.aichat.ui.chat.components.FloatingActionBar
import com.nexus.aichat.ui.chat.components.ImageViewerDialog
import com.nexus.aichat.ui.chat.components.LiveAssistantBubble
import com.nexus.aichat.ui.chat.components.MessageBubble
import com.nexus.aichat.ui.chat.components.ModelSwitcherSheet
import com.nexus.aichat.ui.chat.state.ChatEffect
import com.nexus.aichat.ui.chat.state.ChatIntent
import com.nexus.aichat.ui.components.MessageSkeleton
import com.nexus.aichat.ui.components.rememberHaptics
import kotlinx.coroutines.launch

/**
 * The chat screen.
 *
 * Structure, and the reason for each choice:
 *  - **Scaffold + LazyColumn**: the feed is the only scroller; the top bar and composer float over it so
 *    the content breathes edge-to-edge, which is what makes a chat feel spacious on a tall phone.
 *  - **The live run is a pinned item**, not a persisted message, so there is exactly one source of truth
 *    at any moment: while the agent works, the trace; the moment it finalises, the database row.
 *  - **Auto-scroll follows the stream but yields to the user**: if the user scrolls up mid-answer, we stop
 *    yanking the viewport - a chat that fights your thumb is the fastest way to feel cheap.
 *  - **The action bar is anchored above the composer**, not at the message, so it never covers the text it
 *    acts on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String,
    onOpenConversations: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchConversation: (String) -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Appearance comes from settings; haptics are a user preference, not a screen constant.
    val haptics = rememberHaptics(state.appearance)
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    var viewerAttachment by remember { mutableStateOf<Attachment?>(null) }
    var editTarget by remember { mutableStateOf<Message?>(null) }

    // Effects: one-shot, non-recomposition events.
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ChatEffect.ShowSnackbar -> snackbarHostState.showSnackbar(effect.message)
                is ChatEffect.ScrollToBottom -> scope.launch {
                    val last = listState.layoutInfo.totalItemsCount - 1
                    if (last >= 0) listState.animateScrollToItem(last)
                }
                ChatEffect.FocusComposer -> Unit
                ChatEffect.OpenConversationDrawer -> onOpenConversations()
            }
        }
    }

    // Follow the stream, unless the user has scrolled away from the bottom.
    LaunchedEffect(state.messages.size, state.trace?.finalAnswer?.length) {
        val atBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            ?.let { it >= listState.layoutInfo.totalItemsCount - 2 } ?: true
        if (atBottom) {
            val last = listState.layoutInfo.totalItemsCount - 1
            if (last >= 0) listState.scrollToItem(last)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                navigationIcon = {
                    IconButton(onClick = { haptics.tick(); onOpenConversations() }) {
                        Icon(Icons.Rounded.Menu, contentDescription = "Conversations")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = state.conversation?.title ?: "Nexus",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (state.isRunning) {
                            Text(
                                text = state.phase.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { haptics.tick(); onOpenProviders() }) {
                        Icon(Icons.Rounded.Add, contentDescription = "Providers")
                    }
                    IconButton(onClick = { haptics.tick(); onOpenSettings() }) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { insets ->
        Box(modifier = Modifier.fillMaxSize().padding(insets)) {
            Column(modifier = Modifier.fillMaxSize()) {
                Feed(
                    state = state,
                    listState = listState,
                    onToggleThoughts = { viewModel.onIntent(ChatIntent.ToggleThoughts) },
                    onLongPressMessage = { id -> viewModel.onIntent(ChatIntent.SelectMessage(id)) },
                    onOpenImage = { viewerAttachment = it },
                    onBranchStep = { id, direction -> viewModel.onIntent(ChatIntent.SelectBranch(id, direction)) },
                    onApprove = { callId, always -> viewModel.onIntent(ChatIntent.ApproveTool(callId, always)) },
                    onReject = { callId -> viewModel.onIntent(ChatIntent.RejectTool(callId)) },
                    onAnswerClarification = { requestId, answer ->
                        viewModel.onIntent(ChatIntent.AnswerClarification(requestId, answer))
                    },
                    haptics = haptics,
                    modifier = Modifier.weight(1f),
                )

                // Contextual action bar for the selected message.
                val selected = state.messages.firstOrNull { it.id == state.selectedMessageId }
                FloatingActionBar(
                    visible = selected != null,
                    isUserMessage = selected?.role == com.nexus.aichat.core.model.MessageRole.USER,
                    isPinned = selected?.isPinned == true,
                    onCopy = {
                        selected?.let { clipboard.setText(AnnotatedString(it.plainText)) }
                        viewModel.onIntent(ChatIntent.SelectMessage(null))
                    },
                    onShare = {
                        selected?.let { message ->
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, message.plainText)
                            }
                            context.startActivity(android.content.Intent.createChooser(intent, "Share answer"))
                        }
                        viewModel.onIntent(ChatIntent.SelectMessage(null))
                    },
                    onRetry = {
                        selected?.let { viewModel.onIntent(ChatIntent.Regenerate(it.id)) }
                        viewModel.onIntent(ChatIntent.SelectMessage(null))
                    },
                    onEdit = {
                        editTarget = selected
                        viewModel.onIntent(ChatIntent.SelectMessage(null))
                    },
                    onPin = { viewModel.onIntent(ChatIntent.SelectMessage(null)) },
                    onDismiss = { viewModel.onIntent(ChatIntent.SelectMessage(null)) },
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )

                if (state.isRunning) {
                    RunStatusRow(state.phase.label, state.usage.totalTokens)
                }

                ChatInputBar(
                    draft = state.draft,
                    onDraftChange = { viewModel.onIntent(ChatIntent.UpdateDraft(it)) },
                    attachments = state.pendingAttachments,
                    onAttach = { viewModel.onIntent(ChatIntent.Attach(it)) },
                    onRemoveAttachment = { viewModel.onIntent(ChatIntent.RemoveAttachment(it)) },
                    onSend = { viewModel.onIntent(ChatIntent.Send) },
                    onStop = { viewModel.onIntent(ChatIntent.Stop) },
                    onOpenModelPicker = { viewModel.onIntent(ChatIntent.OpenModelPicker) },
                    isRunning = state.isRunning,
                    canSend = state.canSend,
                    isAttaching = state.isAttaching,
                    modelLabel = state.modelLabel,
                    providerLabel = state.providerLabel,
                    haptics = haptics,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp)
                        .navigationBarsPadding()
                        .imePadding(),
                )
            }

            // Error / info banner, dismissible, never modal.
            AnimatedVisibility(
                visible = state.error != null,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
            ) {
                BannerRow(
                    text = state.error.orEmpty(),
                    isError = true,
                    onDismiss = { viewModel.onIntent(ChatIntent.DismissBanner) },
                )
            }
        }
    }

    // Model switcher: quick switch without leaving the chat.
    ModelSwitcherSheet(
        visible = state.modelPickerOpen,
        providers = state.providers,
        activeProviderId = state.activeProvider?.id,
        activeModelId = state.activeModel?.id,
        isRefreshing = state.isRefreshingModels,
        onDismiss = { viewModel.onIntent(ChatIntent.CloseModelPicker) },
        onSelect = { providerId, modelId ->
            viewModel.onIntent(ChatIntent.SwitchModel(providerId, modelId))
            viewModel.onIntent(ChatIntent.CloseModelPicker)
        },
        onRefresh = { viewModel.onIntent(ChatIntent.FetchModels(it)) },
        onManageProviders = {
            viewModel.onIntent(ChatIntent.CloseModelPicker)
            onOpenProviders()
        },
    )

    viewerAttachment?.let { attachment ->
        ImageViewerDialog(attachment = attachment, onDismiss = { viewerAttachment = null })
    }

    editTarget?.let { message ->
        EditPromptDialog(
            initialText = message.plainText,
            onDismiss = { editTarget = null },
            onConfirm = { newText ->
                viewModel.onIntent(ChatIntent.EditAndResend(message.id, newText))
                editTarget = null
            },
        )
    }
}

@Composable
private fun Feed(
    state: com.nexus.aichat.ui.chat.state.ChatUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onToggleThoughts: () -> Unit,
    onLongPressMessage: (String) -> Unit,
    onOpenImage: (Attachment) -> Unit,
    onBranchStep: (String, Int) -> Unit,
    onApprove: (String, Boolean) -> Unit,
    onReject: (String) -> Unit,
    onAnswerClarification: (String, String) -> Unit,
    haptics: com.nexus.aichat.ui.components.NexusHaptics,
    modifier: Modifier = Modifier,
) {
    // Siblings are resolved once per feed pass: the branch chip needs them, and doing it per row would
    // turn an O(n) grouping into O(n²) on a long conversation.
    val siblingsByParent = remember(state.messages) { state.messages.groupBy { it.parentId } }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.isEmpty) {
            item(key = "empty-state") { EmptyState(onOpenProviders = {}) }
        }

        items(state.messages, key = { it.id }) { message ->
            MessageBubble(
                message = message,
                modelLabel = message.modelId?.substringBefore('/'),
                showThoughts = state.showThoughts,
                thoughtsExpanded = state.thoughtsExpanded,
                branchInfo = MessagePresentation.branchLabel(
                    siblings = siblingsByParent[message.parentId].orEmpty(),
                    message = message,
                ),
                haptics = haptics,
                onToggleThoughts = onToggleThoughts,
                onLongPress = { onLongPressMessage(message.id) },
                onOpenImage = onOpenImage,
                onBranchStep = { direction -> onBranchStep(message.id, direction) },
            )
        }

        state.trace?.let { trace ->
            item(key = "live-run") {
                LiveAssistantBubble(
                    trace = trace,
                    expanded = state.thoughtsExpanded,
                    onToggle = onToggleThoughts,
                    onApprove = onApprove,
                    onReject = onReject,
                    onAnswerClarification = onAnswerClarification,
                )
            }
        }
    }
}

@Composable
private fun RunStatusRow(phase: String, tokens: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
        Text(
            text = phase,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (tokens > 0) {
            Text(
                text = "\u00b7 $tokens tokens",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BannerRow(
    text: String,
    isError: Boolean,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .clip(MaterialTheme.shapes.large)
            .background(
                if (isError) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
            )
            .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
            modifier = Modifier.weight(1f, fill = false),
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Rounded.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
        }
    }
}

/** First-run state: say what the app can do, and the one thing the user must do first. */
@Composable
private fun EmptyState(onOpenProviders: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 80.dp, start = 24.dp, end = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Ask anything", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Nexus runs the agent loop on your device and talks to the model APIs you connect. " +
                "Nothing is sent anywhere you did not configure.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        MessageSkeleton(lines = 3)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Try: \u201cFetch this page and summarise the pricing table\u201d, \u201cRead the attached PDF " +
                "and list every action item\u201d, or \u201cCompare these two approaches and pick one\u201d.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Edit-and-resend: an inline dialog, because the edit is a small correction, not a new screen. */
@Composable
private fun EditPromptDialog(
    initialText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit and resend") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                minLines = 3,
                maxLines = 10,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text("Resend") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
