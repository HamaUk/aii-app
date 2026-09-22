package com.nexus.aichat.ui.chat.components

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.nexus.aichat.core.model.Attachment
import com.nexus.aichat.core.util.Constants
import com.nexus.aichat.ui.components.GlassBar
import com.nexus.aichat.ui.components.NexusHaptics
import java.io.File

/**
 * The composer.
 *
 * Details that make it feel like the apps this is measured against:
 *  - **grows to six lines, then scrolls** - a long prompt must not push the feed off screen, and a
 *    one-line prompt must not leave a giant empty box;
 *  - **attach, model pill and send live in one row**; the attach menu is a small dropdown (Camera /
 *    Gallery / File) rather than a screen, because attaching is a detour you want to complete in one tap;
 *  - **send becomes stop** while a run is in flight, in the same position, so the muscle memory works
 *    under pressure;
 *  - attachments appear as removable chips *above* the text, so it is obvious what is going with the
 *    message before it is sent;
 *  - every primary action ticks (see `NexusHaptics`) - the composer is where touch feedback matters most.
 */
@Composable
fun ChatInputBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    attachments: List<Attachment>,
    onAttach: (String) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenModelPicker: () -> Unit,
    isRunning: Boolean,
    canSend: Boolean,
    isAttaching: Boolean,
    modelLabel: String,
    providerLabel: String,
    haptics: NexusHaptics,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    // --- pickers -----------------------------------------------------------------------------------

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { onAttach(it.toString()) }
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onAttach(it.toString()) }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        if (saved) pendingCameraUri?.let { onAttach(it.toString()) }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val target = createCaptureTarget(context)
            pendingCameraUri = target
            cameraLauncher.launch(target)
        }
    }

    fun launchCamera() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            val target = createCaptureTarget(context)
            pendingCameraUri = target
            cameraLauncher.launch(target)
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    GlassBar(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AnimatedVisibility(visible = attachments.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(attachments, key = { it.id }) { attachment ->
                        AttachmentChip(attachment, onRemove = { onRemoveAttachment(attachment.id) })
                    }
                }
            }

            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Box {
                    IconButton(onClick = { menuOpen = true }, enabled = !isAttaching) {
                        if (isAttaching) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.Add, contentDescription = "Attach")
                        }
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Camera") },
                            leadingIcon = { Icon(Icons.Rounded.PhotoCamera, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                haptics.tick()
                                launchCamera()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Gallery") },
                            leadingIcon = { Icon(Icons.Rounded.Image, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                haptics.tick()
                                galleryLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("File") },
                            leadingIcon = { Icon(Icons.Rounded.Description, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                haptics.tick()
                                fileLauncher.launch(
                                    arrayOf(
                                        "text/*",
                                        "application/pdf",
                                        "application/json",
                                        "text/csv",
                                        "application/octet-stream",
                                    ),
                                )
                            },
                        )
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    BasicTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 40.dp, max = 6 * 22.dp)
                            .padding(vertical = 10.dp),
                    )
                    if (draft.isEmpty()) {
                        Text(
                            text = "Message Nexus\u2026",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                    }
                }

                FilledIconButton(
                    onClick = {
                        haptics.commit()
                        if (isRunning) onStop() else onSend()
                    },
                    enabled = isRunning || canSend,
                    colors = if (isRunning) {
                        IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    } else {
                        IconButtonDefaults.filledIconButtonColors()
                    },
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Rounded.Stop else Icons.Rounded.Send,
                        contentDescription = if (isRunning) "Stop" else "Send",
                    )
                }
            }

            ModelPillRow(
                modelLabel = modelLabel,
                providerLabel = providerLabel,
                onClick = onOpenModelPicker,
                haptics = haptics,
                enabled = !isRunning,
            )
        }
    }
}

/** Compact, secondary row: which model will answer this message, and what it is attached to. */
@Composable
private fun ModelPillRow(
    modelLabel: String,
    providerLabel: String,
    onClick: () -> Unit,
    haptics: NexusHaptics,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f))
                .clickable(enabled = enabled) {
                    haptics.tick()
                    onClick()
                }
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
                Text(
                    text = modelLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (providerLabel.isNotBlank()) {
                    Text(
                        text = providerLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** A picked attachment, before send: removable, with a thumbnail-less kind badge. */
@Composable
private fun AttachmentChip(attachment: Attachment, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f))
            .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (attachment.isImage) Icons.Rounded.Image else Icons.Rounded.Description,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = attachment.displayName,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 180.dp),
        )
        Text(attachment.humanSize, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = onRemove, modifier = Modifier.size(20.dp)) {
            Icon(Icons.Rounded.Close, contentDescription = "Remove attachment", modifier = Modifier.size(14.dp))
        }
    }
}

/**
 * A camera capture target inside the app's cache, exposed through the manifest's FileProvider.
 * The file is written into `cacheDir` so an unused capture is reclaimed by the system.
 */
private fun createCaptureTarget(context: android.content.Context): Uri {
    val directory = File(context.cacheDir, "captures").apply { mkdirs() }
    val file = File(directory, "capture_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
