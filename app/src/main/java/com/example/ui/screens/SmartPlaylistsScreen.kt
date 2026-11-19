package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.db.SmartPlaylistRuleEntity
import com.example.data.db.SmartSortOrder
import com.example.data.db.TrackWithStats
import com.example.data.repository.SyncedPlaylistWithTracks
import com.example.domain.SmartPlaylistEvaluator
import com.example.ui.components.MoodTagDialog
import com.example.ui.components.TrackInfoDialog
import com.example.ui.components.TrackRowItem
import com.example.ui.viewmodel.SmartPlaylistItemUi

@Composable
fun SmartPlaylistsScreen(
    playlists: List<SmartPlaylistItemUi>,
    syncedPlaylists: List<SyncedPlaylistWithTracks> = emptyList(),
    selectedPlaylistId: Long?,
    selectedSyncedPlaylistKey: String? = null,
    currentTrackKey: String?,
    onSelectPlaylist: (Long?) -> Unit,
    onSelectSyncedPlaylist: (String?) -> Unit = {},
    onSaveRule: (SmartPlaylistRuleEntity) -> Unit,
    onDeleteRule: (Long) -> Unit,
    onPlayTracks: (List<TrackWithStats>, Int, String, Boolean?) -> Unit,
    onPlayNext: (TrackWithStats) -> Unit,
    onAddToQueue: (TrackWithStats) -> Unit,
    onSetMoodTag: (String, String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var editingRule by remember { mutableStateOf<SmartPlaylistRuleEntity?>(null) }
    var showRuleInfoDialog by remember { mutableStateOf(false) }
    var moodTarget by remember { mutableStateOf<TrackWithStats?>(null) }
    var detailsTarget by remember { mutableStateOf<TrackWithStats?>(null) }

    val selectedItem = playlists.firstOrNull { it.rule.id == selectedPlaylistId }
    val selectedSyncedItem = syncedPlaylists.firstOrNull { it.playlist.playlistKey == selectedSyncedPlaylistKey }

    BackHandler(enabled = selectedItem != null || selectedSyncedItem != null) {
        onSelectPlaylist(null)
        onSelectSyncedPlaylist(null)
    }

    editingRule?.let { ruleToEdit ->
        SmartRuleEditorDialog(
            initialRule = ruleToEdit,
            onSave = { updated ->
                onSaveRule(updated)
                editingRule = null
            },
            onDelete = if (!ruleToEdit.isBuiltIn && ruleToEdit.id != 0L) {
                {
                    onDeleteRule(ruleToEdit.id)
                    editingRule = null
                }
            } else {
                null
            },
            onDismiss = { editingRule = null }
        )
    }

    if (showRuleInfoDialog) {
        CountingFormulaInfoDialog(onDismiss = { showRuleInfoDialog = false })
    }

    moodTarget?.let { target ->
        MoodTagDialog(
            trackTitle = target.track.title,
            currentMood = target.track.moodTag,
            onSaveMood = { newMood -> onSetMoodTag(target.track.trackKey, newMood) },
            onDismiss = { moodTarget = null }
        )
    }

    detailsTarget?.let { target ->
        TrackInfoDialog(
            item = target,
            onDismiss = { detailsTarget = null }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            if (selectedItem == null && selectedSyncedItem == null) {
                FloatingActionButton(
                    onClick = {
                        editingRule = SmartPlaylistRuleEntity(
                            name = "New Smart Mix",
                            description = "Custom rule-based playlist",
                            sortOrder = SmartSortOrder.PREFERENCE_RANKED.name,
                            limitCount = 30
                        )
                    },
                    modifier = Modifier.testTag("create_smart_playlist_fab")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Create Smart Playlist")
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            when {
                selectedSyncedItem != null -> {
                    SyncedPlaylistDetail(
                        item = selectedSyncedItem,
                        currentTrackKey = currentTrackKey,
                        onBack = { onSelectSyncedPlaylist(null) },
                        onPlayTracks = onPlayTracks,
                        onPlayNext = onPlayNext,
                        onAddToQueue = onAddToQueue,
                        onSetMoodTag = { moodTarget = it },
                        onShowDetails = { detailsTarget = it }
                    )
                }
                selectedItem != null -> {
                    SmartPlaylistDetail(
                        item = selectedItem,
                        currentTrackKey = currentTrackKey,
                        onBack = { onSelectPlaylist(null) },
                        onEditRule = { editingRule = selectedItem.rule },
                        onShowInfo = { showRuleInfoDialog = true },
                        onPlayTracks = onPlayTracks,
                        onPlayNext = onPlayNext,
                        onAddToQueue = onAddToQueue,
                        onSetMoodTag = { moodTarget = it },
                        onShowDetails = { detailsTarget = it }
                    )
                }
                else -> {
                    SmartPlaylistOverview(
                        playlists = playlists,
                        syncedPlaylists = syncedPlaylists,
                        onSelectPlaylist = { onSelectPlaylist(it.rule.id) },
                        onSelectSyncedPlaylist = { onSelectSyncedPlaylist(it.playlist.playlistKey) },
                        onEditRule = { editingRule = it.rule },
                        onShowInfo = { showRuleInfoDialog = true },
                        onPlayTracks = onPlayTracks
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SmartPlaylistOverview(
    playlists: List<SmartPlaylistItemUi>,
    syncedPlaylists: List<SyncedPlaylistWithTracks>,
    onSelectPlaylist: (SmartPlaylistItemUi) -> Unit,
    onSelectSyncedPlaylist: (SyncedPlaylistWithTracks) -> Unit,
    onEditRule: (SmartPlaylistItemUi) -> Unit,
    onShowInfo: () -> Unit,
    onPlayTracks: (List<TrackWithStats>, Int, String, Boolean?) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 12.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Playlists",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        text = "Smart mixes & playlists synced from your Mac",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onShowInfo,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = "Counting and ranking rules"
                    )
                }
            }
        }

        if (syncedPlaylists.isNotEmpty()) {
            item(key = "synced_section_header") {
                Text(
                    text = "Synced from Mac",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            items(syncedPlaylists, key = { it.playlist.playlistKey }) { synced ->
                val orderedTracks = synced.entries.map { it.trackWithStats }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .clickable { onSelectSyncedPlaylist(synced) }
                        .testTag("synced_playlist_card_${synced.playlist.playlistKey}"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Computer,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = synced.playlist.name,
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Mac • ${synced.playlist.libraryName} • ${orderedTracks.size} track${if (orderedTracks.size == 1) "" else "s"}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer
                            ) {
                                Text(
                                    text = "Mac Sync",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    onPlayTracks(
                                        orderedTracks,
                                        0,
                                        "${synced.playlist.name} (Mac)",
                                        false
                                    )
                                },
                                enabled = orderedTracks.isNotEmpty(),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("Play")
                            }
                            FilledTonalButton(
                                onClick = {
                                    onPlayTracks(
                                        orderedTracks,
                                        0,
                                        "${synced.playlist.name} (Mac)",
                                        true
                                    )
                                },
                                enabled = orderedTracks.isNotEmpty(),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.Shuffle, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("Shuffle")
                            }
                        }
                    }
                }
            }
            item(key = "smart_section_header") {
                Text(
                    text = "Smart Playlists",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        items(playlists, key = { it.rule.id }) { item ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .clickable { onSelectPlaylist(item) }
                    .testTag("smart_playlist_card_${item.rule.id}"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (item.rule.isBuiltIn) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AutoAwesome,
                                contentDescription = null,
                                tint = if (item.rule.isBuiltIn) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.secondary
                                },
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.rule.name,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${item.matchedTracks.size} matching track${if (item.matchedTracks.size == 1) "" else "s"}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (!item.rule.isBuiltIn) {
                            IconButton(
                                onClick = { onEditRule(item) },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Edit,
                                    contentDescription = "Edit playlist rules"
                                )
                            }
                        }
                    }

                    if (item.rule.description.isNotBlank()) {
                        Text(
                            text = item.rule.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        item.ruleChips.forEach { chip ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = chip,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onPlayTracks(item.matchedTracks, 0, item.rule.name, false) },
                            enabled = item.matchedTracks.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Play")
                        }
                        FilledTonalButton(
                            onClick = { onPlayTracks(item.matchedTracks, 0, item.rule.name, true) },
                            enabled = item.matchedTracks.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Shuffle, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Shuffle")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SmartPlaylistDetail(
    item: SmartPlaylistItemUi,
    currentTrackKey: String?,
    onBack: () -> Unit,
    onEditRule: () -> Unit,
    onShowInfo: () -> Unit,
    onPlayTracks: (List<TrackWithStats>, Int, String, Boolean?) -> Unit,
    onPlayNext: (TrackWithStats) -> Unit,
    onAddToQueue: (TrackWithStats) -> Unit,
    onSetMoodTag: (TrackWithStats) -> Unit,
    onShowDetails: (TrackWithStats) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item(key = "detail_header") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.rule.name,
                            style = MaterialTheme.typography.headlineSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${item.matchedTracks.size} track${if (item.matchedTracks.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (!item.rule.isBuiltIn) {
                        IconButton(
                            onClick = onEditRule,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("edit_smart_rule_button")
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit rule")
                        }
                    }
                    IconButton(
                        onClick = onShowInfo,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Outlined.Info, contentDescription = "Rule and counting info")
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item.ruleChips.forEach { chip ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Text(
                                text = chip,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { onPlayTracks(item.matchedTracks, 0, item.rule.name, false) },
                        enabled = item.matchedTracks.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Play All")
                    }
                    FilledTonalButton(
                        onClick = { onPlayTracks(item.matchedTracks, 0, item.rule.name, true) },
                        enabled = item.matchedTracks.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Shuffle, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Shuffle")
                    }
                }
            }
        }

        if (item.matchedTracks.isEmpty()) {
            item(key = "empty_rule_matches") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "No Tracks Match These Rules Yet",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Add audio to your library, listen to build play history, or relax the rule filters.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!item.rule.isBuiltIn) {
                            OutlinedButton(onClick = onEditRule) {
                                Text("Adjust Rules")
                            }
                        }
                    }
                }
            }
        } else {
            itemsIndexed(
                items = item.matchedTracks,
                key = { index, track -> "${index}_${track.track.trackKey}" }
            ) { index, track ->
                TrackRowItem(
                    item = track,
                    isCurrentTrack = track.track.trackKey == currentTrackKey,
                    onClick = { onPlayTracks(item.matchedTracks, index, item.rule.name, null) },
                    onPlayNext = { onPlayNext(track) },
                    onAddToQueue = { onAddToQueue(track) },
                    onSetMoodTag = { onSetMoodTag(track) },
                    onShowDetails = { onShowDetails(track) }
                )
            }
        }
    }
}

@Composable
private fun SyncedPlaylistDetail(
    item: SyncedPlaylistWithTracks,
    currentTrackKey: String?,
    onBack: () -> Unit,
    onPlayTracks: (List<TrackWithStats>, Int, String, Boolean?) -> Unit,
    onPlayNext: (TrackWithStats) -> Unit,
    onAddToQueue: (TrackWithStats) -> Unit,
    onSetMoodTag: (TrackWithStats) -> Unit,
    onShowDetails: (TrackWithStats) -> Unit
) {
    val orderedTracks = remember(item.entries) { item.entries.map { it.trackWithStats } }
    val label = "${item.playlist.name} (Mac)"

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item(key = "synced_detail_header") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.playlist.name,
                            style = MaterialTheme.typography.headlineSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Synced from Mac (${item.playlist.libraryName}) • ${orderedTracks.size} track${if (orderedTracks.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { onPlayTracks(orderedTracks, 0, label, false) },
                        enabled = orderedTracks.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Play All")
                    }
                    FilledTonalButton(
                        onClick = { onPlayTracks(orderedTracks, 0, label, true) },
                        enabled = orderedTracks.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Shuffle, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Shuffle")
                    }
                }
            }
        }

        itemsIndexed(
            items = item.entries,
            key = { _, entry -> "${entry.position}_${entry.trackWithStats.track.trackKey}" }
        ) { index, entry ->
            val tws = entry.trackWithStats
            TrackRowItem(
                item = tws,
                isCurrentTrack = tws.track.trackKey == currentTrackKey,
                onClick = { onPlayTracks(orderedTracks, index, label, null) },
                onPlayNext = { onPlayNext(tws) },
                onAddToQueue = { onAddToQueue(tws) },
                onSetMoodTag = { onSetMoodTag(tws) },
                onShowDetails = { onShowDetails(tws) }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SmartRuleEditorDialog(
    initialRule: SmartPlaylistRuleEntity,
    onSave: (SmartPlaylistRuleEntity) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialRule.name) }
    var description by remember { mutableStateOf(initialRule.description) }
    var minDurationText by remember { mutableStateOf(initialRule.minDurationSec?.toString().orEmpty()) }
    var maxDurationText by remember { mutableStateOf(initialRule.maxDurationSec?.toString().orEmpty()) }
    var artistFilter by remember { mutableStateOf(initialRule.artistFilter.orEmpty()) }
    var albumFilter by remember { mutableStateOf(initialRule.albumFilter.orEmpty()) }
    var genreFilter by remember { mutableStateOf(initialRule.genreFilter.orEmpty()) }
    var moodTagFilter by remember { mutableStateOf(initialRule.moodTagFilter.orEmpty()) }
    var minPlayCountText by remember { mutableStateOf(initialRule.minPlayCount?.toString().orEmpty()) }
    var limitCountText by remember { mutableStateOf(initialRule.limitCount?.toString().orEmpty()) }
    var sortOrder by remember {
        mutableStateOf(
            runCatching { SmartSortOrder.valueOf(initialRule.sortOrder) }
                .getOrDefault(SmartSortOrder.PREFERENCE_RANKED)
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (initialRule.id == 0L) "New Smart Playlist" else "Edit Smart Playlist")
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Delete playlist",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Playlist Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rule_name_input")
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = minDurationText,
                        onValueChange = { minDurationText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Min sec") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = maxDurationText,
                        onValueChange = { maxDurationText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Max sec") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = artistFilter,
                    onValueChange = { artistFilter = it },
                    label = { Text("Artist contains (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = albumFilter,
                    onValueChange = { albumFilter = it },
                    label = { Text("Album contains (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = genreFilter,
                    onValueChange = { genreFilter = it },
                    label = { Text("Genre contains (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Mood Tag Filter (explicit user tags)", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SmartPlaylistEvaluator.DEFAULT_MOOD_TAGS.forEach { preset ->
                        FilterChip(
                            selected = moodTagFilter.equals(preset, ignoreCase = true),
                            onClick = {
                                moodTagFilter = if (moodTagFilter.equals(preset, ignoreCase = true)) "" else preset
                            },
                            label = { Text(preset) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = minPlayCountText,
                        onValueChange = { minPlayCountText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Min plays") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = limitCountText,
                        onValueChange = { limitCountText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Max tracks") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text("Sort Order", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SmartSortOrder.entries.forEach { option ->
                        FilterChip(
                            selected = sortOrder == option,
                            onClick = { sortOrder = option },
                            label = { Text(option.label) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val cleanName = name.trim().ifEmpty { "Smart Mix" }
                    onSave(
                        initialRule.copy(
                            name = cleanName,
                            description = description.trim(),
                            minDurationSec = minDurationText.toLongOrNull(),
                            maxDurationSec = maxDurationText.toLongOrNull(),
                            artistFilter = artistFilter.trim().takeIf { it.isNotEmpty() },
                            albumFilter = albumFilter.trim().takeIf { it.isNotEmpty() },
                            genreFilter = genreFilter.trim().takeIf { it.isNotEmpty() },
                            moodTagFilter = moodTagFilter.trim().takeIf { it.isNotEmpty() },
                            minPlayCount = minPlayCountText.toIntOrNull(),
                            limitCount = limitCountText.toIntOrNull(),
                            sortOrder = sortOrder.name
                        )
                    )
                },
                modifier = Modifier.testTag("save_smart_rule_button")
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun CountingFormulaInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Counting & Ranking Rules") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Play Events: Counted once per active listening session after continuous playback reaches 30s (or 50% of shorter tracks, min 5s) or natural track completion. Seeking or rotating the screen never inflates counts.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Skip Events: Counted once per session when you skip away before a play qualifies, provided at least 1.5s played.",
                    style = MaterialTheme.typography.bodySmall
                )
                HorizontalDivider()
                Text(
                    text = "Preference Ranking: Score = (Plays × 1.0) − (Skips × 0.5). Used only for preference-ranked playlists; Shuffle uses an unbiased Fisher-Yates permutation.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it")
            }
        }
    )
}
