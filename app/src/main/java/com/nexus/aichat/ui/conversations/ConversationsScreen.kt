package com.nexus.aichat.ui.conversations

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexus.aichat.core.model.ConversationSummary
import com.nexus.aichat.core.util.toRelativeTime
import com.nexus.aichat.domain.model.ConversationPresentation
import com.nexus.aichat.ui.components.NexusCard

/**
 * The conversation drawer.
 *
 * A full screen rather than a modal drawer, because on a phone a list of chats is a *destination*: users
 * scroll it, search it, and delete from it. Grouping is by recency bucket with pinned chats floated to the
 * top, which is what makes a list of two hundred chats still navigable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    onOpenConversation: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: ConversationsViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<ConversationSummary?>(null) }

    val filtered = remember(conversations, ui.query) {
        if (ui.query.isBlank()) {
            conversations
        } else {
            conversations.filter { it.title.contains(ui.query, ignoreCase = true) }
        }
    }
    val ordered = remember(filtered) { filtered.sortedWith(ConversationPresentation.order) }
    val grouped = remember(ordered) {
        ordered.groupBy { ConversationPresentation.bucketOf(it.updatedAtEpochMs) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Chats", style = MaterialTheme.typography.titleMedium) },
            )
        },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = ui.query,
                onValueChange = viewModel::onQueryChange,
                placeholder = { Text("Search chats") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (ordered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No chats yet. Your conversations will appear here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    grouped.forEach { (bucket, rows) ->
                        item(key = "header-$bucket") {
                            Text(
                                text = bucket,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                            )
                        }
                        items(rows, key = { it.id }) { summary ->
                            ConversationRow(
                                summary = summary,
                                onOpen = { onOpenConversation(summary.id) },
                                onTogglePin = { viewModel.setPinned(summary.id, !summary.isPinned) },
                                onDelete = { pendingDelete = summary },
                            )
                        }
                    }
                    item { Spacer(Modifier.size(24.dp)) }
                }
            }
        }
    }

    pendingDelete?.let { summary ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this chat?") },
            text = {
                Text(
                    "\u201c${summary.title}\u201d and all of its messages, branches and tool logs will be " +
                        "removed from this device.",
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        viewModel.delete(summary.id)
                        pendingDelete = null
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ConversationRow(
    summary: ConversationSummary,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
) {
    NexusCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = summary.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (summary.isPinned) {
                    Icon(
                        Icons.Rounded.PushPin,
                        contentDescription = "Pinned",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = buildString {
                    append(summary.messageCount)
                    append(if (summary.messageCount == 1) " message" else " messages")
                    append(" \u00b7 ")
                    append(summary.updatedAtEpochMs.toRelativeTime())
                    summary.modelId?.let { append(" \u00b7 $it") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
