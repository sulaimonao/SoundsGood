package com.example.ui.screens

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.sync.LocalSyncUiState
import com.example.sync.MusicFolderState
import com.example.sync.SyncPhase
import com.example.sync.SyncReportDelivery

@Composable
fun LocalSyncScreen(
    state: LocalSyncUiState,
    onPairAndConnect: (String) -> Boolean,
    onRefreshManifest: () -> Unit,
    onSetSyncAllMusic: (Boolean) -> Unit,
    onTogglePlaylist: (String) -> Unit,
    onStartSync: (Boolean) -> Unit,
    onOpenTransferFolder: (ContentResolver, Uri) -> Unit,
    onExportReport: suspend (ContentResolver, Uri, String) -> Boolean,
    onCancelSync: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onOpenLibrary: () -> Unit,
    modifier: Modifier = Modifier,
    musicFolder: MusicFolderState = MusicFolderState(),
    onChooseMusicFolder: ((Uri) -> Unit)? = null,
    onCopyExistingDownloads: () -> Unit = {},
    onRecheckMusicFolder: () -> Unit = {},
    onCancelMusicFolder: () -> Unit = {}
) {
    // Session pairing input stays strictly local to composition and is cleared immediately after consumption.
    var pairingInput by remember { mutableStateOf("") }
    var continuePastFileErrors by rememberSaveable { mutableStateOf(true) }

    val resolver = LocalContext.current.applicationContext.contentResolver
    val saveScope = rememberCoroutineScope()
    var pendingReportId by rememberSaveable { mutableStateOf<String?>(null) }
    var reportExportNotice by remember(state.lastOutcome?.reportId) { mutableStateOf<String?>(null) }
    val localReportContract = remember {
        object : ActivityResultContracts.CreateDocument("application/json") {
            override fun createIntent(context: Context, input: String): Intent =
                super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        }
    }
    val saveReport = rememberLauncherForActivityResult(localReportContract) { destination ->
        val id = pendingReportId
        pendingReportId = null
        if (destination != null && id != null) saveScope.launch {
            reportExportNotice = if (onExportReport(resolver, destination, id)) "Report saved."
            else "Could not save that report. Choose a folder on this phone or a USB drive and try again."
        }
    }
    val localFolderContract = remember {
        object : ActivityResultContracts.OpenDocumentTree() {
            override fun createIntent(context: Context, input: Uri?): Intent =
                super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        }
    }
    val chooseTransferFolder = rememberLauncherForActivityResult(localFolderContract) { selected ->
        if (selected != null) onOpenTransferFolder(resolver, selected)
    }
    val musicDestinationContract = remember {
        object : ActivityResultContracts.OpenDocumentTree() {
            override fun createIntent(context: Context, input: Uri?): Intent =
                super.createIntent(context, input)
                    .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
    }
    val chooseMusicDestination = rememberLauncherForActivityResult(musicDestinationContract) { uri ->
        if (uri != null) onChooseMusicFolder?.invoke(uri)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Local Sync",
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    text = "Sync over Wi-Fi or import a transfer folder from your Mac.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "SoundsGood ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("sync_build_version")
                )
            }
        }

        if (onChooseMusicFolder != null) item(key = "music_destination") {
            Card(modifier = Modifier.fillMaxWidth().testTag("music_folder_card")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("On-device music folder", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (musicFolder.selected) {
                            "Saved in ${musicFolder.displayName ?: "your chosen folder"}. Choose the same folder after reinstalling to recover saved music."
                        } else {
                            "App storage is cleared on uninstall. Choose a folder such as Music to keep your synced songs on this device."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (musicFolder.busy) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("Processed ${musicFolder.processed} · Skipped ${musicFolder.skipped}")
                        OutlinedButton(onClick = onCancelMusicFolder) { Text("Stop") }
                    } else {
                        OutlinedButton(
                            enabled = !state.isConnectingOrSyncing,
                            onClick = {
                                val music = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Music")
                                chooseMusicDestination.launch(music)
                            }
                        ) { Text(if (musicFolder.selected) "Change or reconnect folder" else "Choose music folder") }
                        if (musicFolder.selected) {
                            OutlinedButton(onClick = onCopyExistingDownloads, enabled = !state.isConnectingOrSyncing) {
                                Text("Copy existing downloads")
                            }
                            OutlinedButton(onClick = onRecheckMusicFolder, enabled = !state.isConnectingOrSyncing) {
                                Text("Check saved music")
                            }
                        }
                    }
                    musicFolder.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (musicFolder.busy) return@LazyColumn

        // Foreground sync notice banner
        item(key = "foreground_notice") {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Wifi,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = "Keep SoundsGood open during sync. Screen stays awake while transferring; leaving the app cancels active downloads safely.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        item(key = "transfer_folder") {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Import from USB or phone storage", style = MaterialTheme.typography.titleMedium)
                    Text("Choose the Mac export folder that contains READY, manifest.json, and audio. You can preview its tracks and playlists before importing.",
                        style = MaterialTheme.typography.bodySmall)
                    if (state.isFolderImport && state.libraryName != null) Text("Selected: ${state.libraryName}")
                    OutlinedButton(onClick = { chooseTransferFolder.launch(null) }, enabled = !state.isConnectingOrSyncing) {
                        Text("Choose transfer folder")
                    }
                    if (state.isFolderImport && !state.isConnectingOrSyncing) {
                        OutlinedButton(onClick = onDisconnect) { Text("Close folder") }
                    }
                }
            }
        }

        // Pairing link paste / connection card
        if (!state.isFolderImport) item(key = "pairing_card") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (!state.isPaired) {
                        Text(
                            text = "Pair with Mac Companion",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Paste a soundsgood://pair link from your Mac companion, or open it from a QR/link handler. Pairing stays in memory for this session only.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = pairingInput,
                            onValueChange = { pairingInput = it },
                            label = { Text("Pairing link (soundsgood://pair?...)") },
                            singleLine = true,
                            enabled = !state.isConnectingOrSyncing,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("sync_pairing_input")
                        )
                        Button(
                            onClick = {
                                val candidate = pairingInput
                                if (onPairAndConnect(candidate)) {
                                    // Clear sensitive token input immediately after consuming
                                    pairingInput = ""
                                }
                            },
                            enabled = pairingInput.isNotBlank() && !state.isConnectingOrSyncing,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("sync_connect_button")
                        ) {
                            Icon(Icons.Filled.Sync, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Connect to Mac")
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Computer,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = state.libraryName ?: "Connecting to Mac...",
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.testTag("sync_library_name")
                                )
                                Text(
                                    text = buildString {
                                        append(state.pairedAddressDisplay.orEmpty())
                                        state.revision?.let { append(" • Rev ${it.take(12)}") }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("sync_paired_address")
                                )
                            }
                            IconButton(
                                onClick = onRefreshManifest,
                                enabled = !state.isConnectingOrSyncing,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("sync_refresh_button")
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Refresh Mac manifest")
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    pairingInput = ""
                                    onDisconnect()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("sync_disconnect_button")
                            ) {
                                Icon(Icons.Filled.LinkOff, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Disconnect / Forget")
                            }
                        }
                    }
                }
            }
        }

        // Connecting indicator
        if (state.phase == SyncPhase.CONNECTING) {
            item(key = "connecting_card") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(if (state.isFolderImport) "Checking transfer folder…" else "Verifying TLS Pin & Fetching Library...", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (state.isFolderImport) "Reading the manifest and locating its audio files." else "Confirming Wi-Fi route and Mac certificate fingerprint.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(onClick = onCancelSync) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }

        // Error card (also supports pasting fresh link when session expired)
        state.errorMessage?.let { err ->
            item(key = "error_card") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sync_error_card"),
                    colors = CardDefaults.cardColors(
                        containerColor = if (state.phase == SyncPhase.CANCELLED) {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        }
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (state.phase == SyncPhase.CANCELLED) {
                                    Icons.Filled.Cancel
                                } else {
                                    Icons.Filled.ErrorOutline
                                },
                                contentDescription = null,
                                tint = if (state.phase == SyncPhase.CANCELLED) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                            )
                            Text(
                                text = if (state.phase == SyncPhase.CANCELLED) "Sync Cancelled" else "Sync Issue",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (state.phase == SyncPhase.CANCELLED) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onErrorContainer
                                }
                            )
                        }
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.phase == SyncPhase.CANCELLED) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            }
                        )
                        if (state.requiresRePair) {
                            OutlinedButton(
                                onClick = {
                                    pairingInput = ""
                                    onDisconnect()
                                }
                            ) {
                                Text("Paste New Pairing Link")
                            }
                        }
                    }
                }
            }
        }

        // Retain every terminal outcome; partial/stopped runs are not successes.
        state.lastOutcome?.let { outcome ->
                item(key = "completed_card") {
                    val complete = outcome.status == "complete"
                    val cancelled = outcome.status == "cancelled"
                    val heading = when (outcome.status) {
                        "complete" -> "Sync complete"
                        "partial" -> "Sync finished with file errors"
                        "stopped" -> "Sync stopped"
                        "cancelled" -> "Sync cancelled"
                        else -> "Sync results"
                    }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("sync_completed_card"),
                        colors = CardDefaults.cardColors(containerColor = if (complete) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        })
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = when {
                                        complete -> Icons.Filled.CheckCircle
                                        cancelled -> Icons.Filled.Cancel
                                        else -> Icons.Filled.ErrorOutline
                                    },
                                    contentDescription = null,
                                    tint = if (complete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Last result: $heading",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (complete) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                text = outcome.libraryName,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (complete) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            PreviewStatRow("Verified and imported", "${outcome.syncedTracksCount}")
                            PreviewStatRow(if (state.isFolderImport) "Copied and imported" else "Downloaded and imported", "${outcome.downloadedTracksCount}")
                            PreviewStatRow("Verified files reused", "${outcome.reusedOrUnchangedCount}")
                            PreviewStatRow("Failed files", "${outcome.failedTracksCount}")
                            PreviewStatRow("Not attempted", "${outcome.notAttemptedTracksCount}")
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            PreviewStatRow("Playlists updated", "${outcome.syncedPlaylistsCount}")
                            PreviewStatRow("Existing playlists preserved", "${outcome.preservedPlaylistsCount}")
                            PreviewStatRow("New playlists deferred", "${outcome.deferredPlaylistsCount}")
                            if (outcome.stopReason != "none") {
                                Text(
                                    text = syncReasonLabel(outcome.stopReason),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = when (outcome.reportDelivery) {
                                    SyncReportDelivery.DELIVERED -> "Results delivered to the Mac's Desktop report."
                                    SyncReportDelivery.UNSUPPORTED -> "This Mac companion does not support phone results in its Desktop report."
                                    SyncReportDelivery.INCOMPLETE -> "Desktop report delivery was not confirmed."
                                    null -> if (state.isFolderImport) "Folder import results stay on this phone." else "Phone results were not sent to the Mac report."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("sync_report_delivery")
                            )
                            Text(
                                text = if (outcome.localReportSaved) {
                                    "The full result is saved privately on this phone."
                                } else {
                                    "The full result was not saved on this phone."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val reportId = outcome.reportId
                            if (outcome.localReportSaved && reportId != null) {
                                OutlinedButton(enabled = !state.isConnectingOrSyncing && pendingReportId == null, onClick = {
                                    pendingReportId = reportId
                                    reportExportNotice = null
                                    saveReport.launch("SoundsGood-results-${reportId.take(8)}.json")
                                }) { Text("Save last report") }
                                reportExportNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                            if (outcome.failures.isNotEmpty()) {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                Text("Files needing attention", style = MaterialTheme.typography.titleSmall)
                                outcome.failures.take(30).forEach { failure ->
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = failure.trackId,
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = (if (failure.outcome == "not_attempted") "Not attempted: " else "Failed: ") + syncReasonLabel(failure.reason),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                if (outcome.failures.size > 30) {
                                    Text(
                                        text = if (outcome.localReportSaved) {
                                            "Showing 30 of ${outcome.failures.size} entries. The full list is in the saved phone report."
                                        } else {
                                            "Showing 30 of ${outcome.failures.size} entries."
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = onOpenLibrary) {
                                    Text("Browse Library")
                                }
                                FilledTonalButton(onClick = onOpenPlaylists) {
                                    Text("Open Playlists")
                                }
                            }
                        }
                    }
                }
        }

        // Active transfer progress
        state.progress?.let { prog ->
            if (state.phase == SyncPhase.SYNCING) {
                item(key = "progress_card") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("sync_progress_card"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Checked ${prog.attemptedTracks}/${prog.totalSelectedTracks} files",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "${formatBytesHuman(prog.overallBytesDownloaded)} / ${formatBytesHuman(prog.overallBytesToDownload)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            LinearProgressIndicator(
                                progress = { prog.overallFraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                            )
                            Text(
                                text = "${prog.completedTracks} verified and imported • ${prog.failedTracks} failed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Current: ${prog.currentTrackTitle}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            OutlinedButton(
                                onClick = onCancelSync,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("sync_cancel_button")
                            ) {
                                Icon(Icons.Filled.Cancel, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Cancel Sync")
                            }
                        }
                    }
                }
            }
        }

        // Scope Selection + Preview when connected
        if (state.libraryId != null) {
            item(key = "scope_card") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("What to Sync", style = MaterialTheme.typography.titleMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("All Available Music", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(
                                    "Sync every eligible track and playlist from this Mac library",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = state.syncAllMusic,
                                onCheckedChange = onSetSyncAllMusic,
                                enabled = !state.isConnectingOrSyncing,
                                modifier = Modifier.testTag("sync_all_music_toggle")
                            )
                        }

                        if (!state.syncAllMusic && state.playlists.isNotEmpty()) {
                            HorizontalDivider()
                            Text(
                                "Select Mac Playlists",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            state.playlists.forEach { playlist ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable(enabled = !state.isConnectingOrSyncing) {
                                            onTogglePlaylist(playlist.id)
                                        }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = playlist.selected,
                                        onCheckedChange = { onTogglePlaylist(playlist.id) },
                                        enabled = !state.isConnectingOrSyncing,
                                        modifier = Modifier.testTag("sync_playlist_checkbox_${playlist.id}")
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = playlist.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${playlist.trackCount} track${if (playlist.trackCount == 1) "" else "s"}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        } else if (!state.syncAllMusic && state.playlists.isEmpty()) {
                            Text(
                                text = "This Mac library contains no playlists. Enable All Available Music to sync its tracks.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            state.preview?.let { plan ->
                item(key = "preview_card") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("sync_preview_card"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Sync Preview", style = MaterialTheme.typography.titleMedium)
                            PreviewStatRow("New Tracks", "${plan.newTracksCount}")
                            PreviewStatRow("Changed Audio Files", "${plan.changedTracksCount}")
                            PreviewStatRow("Unchanged Audio", "${plan.unchangedTracksCount}")
                            if (plan.reusedVerifiedFilesCount > 0) {
                                PreviewStatRow(if (plan.usesMusicFolder) "Local matches to verify" else "Verified Files Reused", "${plan.reusedVerifiedFilesCount}")
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            PreviewStatRow(if (plan.usesMusicFolder) "Estimated transfer" else if (state.isFolderImport) "Required copy" else "Required Download", formatBytesHuman(plan.requiredDownloadBytes))
                            if (plan.usesMusicFolder) {
                                PreviewStatRow("Estimated new space in music folder", formatBytesHuman(plan.requiredFolderBytes))
                                PreviewStatRow("Music folder free space", if (plan.destinationCapacityUnknown) "Unknown" else formatBytesHuman(plan.availableStorageBytes))
                            } else {
                                PreviewStatRow("Device Free Storage", formatBytesHuman(plan.availableStorageBytes))
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Continue past file errors", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    Text(
                                        "Skip a file that cannot transfer or pass verification, then try the next. Turning this off stops at the first file error.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = continuePastFileErrors,
                                    onCheckedChange = { continuePastFileErrors = it },
                                    enabled = !state.isConnectingOrSyncing,
                                    modifier = Modifier.testTag("sync_continue_file_errors_toggle")
                                )
                            }
                            Text(
                                if (state.isFolderImport) "Keep the folder available and SoundsGood open until import finishes."
                                else "Pairing or certificate failures, Wi-Fi loss, library changes, storage problems, and invalid server responses still stop the run.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (!plan.hasEnoughStorage) {
                                Text(
                                    text = if (plan.usesMusicFolder) "More space is needed in the music folder or on this device to stage the next track."
                                           else "Not enough free storage (includes 16 MiB+ reserve).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }

                            if (!state.isTransferActive) {
                                Spacer(Modifier.height(4.dp))
                                Button(
                                    onClick = { onStartSync(continuePastFileErrors) },
                                    enabled = !state.isConnectingOrSyncing &&
                                        plan.hasEnoughStorage &&
                                        (plan.selectedTracks.isNotEmpty() || plan.selectedPlaylists.isNotEmpty()),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("sync_start_button")
                                ) {
                                    Icon(Icons.Filled.Sync, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (plan.tracksToDownload.isEmpty()) {
                                            "Apply Metadata & Playlists"
                                        } else if (state.isFolderImport) {
                                            "Import files (${plan.tracksToDownload.size})"
                                        } else {
                                            "Start Sync (${plan.tracksToDownload.size} file${if (plan.tracksToDownload.size == 1) "" else "s"})"
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                if (plan.skippedItems.isNotEmpty()) {
                    item(key = "skipped_header") {
                        Text(
                            text = "Skipped by Mac Companion (${plan.skippedItems.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    items(plan.skippedItems.take(50)) { skipped ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = skipped.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = skipped.reason,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun syncReasonLabel(reason: String): String = when (reason) {
    "source_unavailable" -> "The source changed or could not be opened."
    "connection_failed" -> "The connection to the Mac failed."
    "timeout" -> "The transfer timed out."
    "unexpected_eof" -> "The audio stream ended before the full file arrived."
    "size_mismatch" -> "The received file size did not match the library."
    "hash_mismatch" -> "The received file did not match its expected SHA-256."
    "storage_full" -> "The phone does not have enough free storage."
    "unsupported_format" -> "The audio format is unsupported."
    "cancelled" -> "Sync was cancelled."
    "manifest_changed" -> "The Mac library changed. Refresh it before continuing."
    "verification_failed" -> "The file could not be verified."
    "import_failed" -> "The phone could not finish a library update."
    "session_expired" -> "The Mac pairing session ended. Pair again."
    "http_error" -> "The Mac could not serve this file."
    "not_attempted" -> "No transfer was attempted."
    "local_storage_error" -> "The phone could not write or publish the file."
    "tls_failed" -> "The Mac certificate or secure connection could not be verified."
    "protocol_error" -> "The Mac returned an invalid response."
    "none" -> "No additional error."
    else -> "This result needs review."
}

@Composable
private fun PreviewStatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

fun formatBytesHuman(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kib = 1024.0
    val mib = kib * 1024.0
    val gib = mib * 1024.0
    return when {
        bytes >= gib -> "%.2f GB".format(bytes / gib)
        bytes >= mib -> "%.1f MB".format(bytes / mib)
        bytes >= kib -> "%.0f KB".format(bytes / kib)
        else -> "$bytes B"
    }
}
