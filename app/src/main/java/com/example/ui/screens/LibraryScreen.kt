package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.db.TrackWithStats
import com.example.ui.components.ArtworkBox
import com.example.ui.components.MoodTagDialog
import com.example.ui.components.TrackInfoDialog
import com.example.ui.components.TrackRowItem
import com.example.ui.components.formatDurationMs
import com.example.ui.viewmodel.AlbumSummary
import com.example.ui.viewmodel.ArtistSummary
import com.example.ui.viewmodel.LibraryCategoryTab
import com.example.ui.viewmodel.LibrarySortOption
import com.example.ui.viewmodel.LibraryUiState

@Composable
fun LibraryScreen(
    state: LibraryUiState,
    currentTrackKey: String?,
    onSearchQueryChange: (String) -> Unit,
    onSortOptionSelect: (LibrarySortOption) -> Unit,
    onCategoryTabSelect: (LibraryCategoryTab) -> Unit,
    onSelectArtist: (String?) -> Unit,
    onSelectAlbum: (String?) -> Unit,
    onRescan: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onPickLocalFiles: () -> Unit,
    onPlayTracks: (List<TrackWithStats>, Int, String, Boolean?) -> Unit,
    onPlayNext: (TrackWithStats) -> Unit,
    onAddToQueue: (TrackWithStats) -> Unit,
    onSetMoodTag: (String, String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showLegalDisclosure by rememberSaveable { mutableStateOf(false) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var moodTarget by remember { mutableStateOf<TrackWithStats?>(null) }
    var detailsTarget by remember { mutableStateOf<TrackWithStats?>(null) }

    if (showLegalDisclosure) {
        LegalDisclosureDialog(onDismiss = { showLegalDisclosure = false })
    }

    // Support system back when drilled into an Artist or Album detail
    BackHandler(enabled = state.selectedArtist != null || state.selectedAlbum != null) {
        onSelectArtist(null)
        onSelectAlbum(null)
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // Header Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.selectedArtist != null || state.selectedAlbum != null) {
                IconButton(
                    onClick = {
                        onSelectArtist(null)
                        onSelectAlbum(null)
                    },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.selectedArtist ?: state.selectedAlbum.orEmpty(),
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (state.selectedArtist != null) "Artist" else "Album",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "SoundsGood",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "${state.allTracks.size} local track${if (state.allTracks.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(
                onClick = {
                    if (showSearch) {
                        onSearchQueryChange("")
                        showSearch = false
                    } else {
                        showSearch = true
                    }
                },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("library_search_toggle")
            ) {
                Icon(
                    imageVector = if (showSearch) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = stringResource(
                        if (showSearch) R.string.cd_clear_search else R.string.cd_search
                    )
                )
            }

            Box {
                IconButton(
                    onClick = { sortMenuExpanded = true },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("library_sort_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Sort,
                        contentDescription = "Sort library"
                    )
                }
                DropdownMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { sortMenuExpanded = false }
                ) {
                    LibrarySortOption.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            leadingIcon = {
                                if (state.sortOption == option) {
                                    Icon(Icons.Filled.Check, contentDescription = null)
                                }
                            },
                            onClick = {
                                sortMenuExpanded = false
                                onSortOptionSelect(option)
                            }
                        )
                    }
                }
            }

            IconButton(
                onClick = onPickLocalFiles,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("library_pick_files_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.FolderOpen,
                    contentDescription = stringResource(R.string.cd_pick_files)
                )
            }

            IconButton(
                onClick = onRescan,
                enabled = !state.isScanning,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("library_refresh_button")
            ) {
                if (state.isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.cd_refresh_library)
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                onClick = { showLegalDisclosure = true },
                modifier = Modifier.testTag("library_about_legal_button")
            ) {
                Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Personal Edition · About & legal")
            }
        }

        AnimatedVisibility(visible = showSearch) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Search songs, artists, albums, genre, mood...") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_clear_search))
                        }
                    }
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .testTag("library_search_input")
            )
        }

        // Category Chips (Songs / Artists / Albums)
        if (state.selectedArtist == null && state.selectedAlbum == null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LibraryCategoryTab.entries.forEach { tab ->
                    FilterChip(
                        selected = state.categoryTab == tab,
                        onClick = { onCategoryTabSelect(tab) },
                        label = { Text(tab.label) },
                        modifier = Modifier.testTag("library_tab_${tab.name.lowercase()}")
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (state.unreadableCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Filled.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "${state.unreadableCount} unreadable",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }

        // Compact permission hint banner if user picked files without granting full library access
        if (!state.hasAudioPermission && state.allTracks.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Showing picked local files. Grant audio permission to scan full MediaStore.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onRequestPermission) {
                        Text("Grant")
                    }
                }
            }
        }

        // Main Content State Machine
        when {
            state.isScanning && state.allTracks.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "Scanning local audio library...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            !state.hasAudioPermission && state.allTracks.isEmpty() -> {
                PermissionOnboardingCard(
                    scanError = state.scanError,
                    onRequestPermission = onRequestPermission,
                    onOpenAppSettings = onOpenAppSettings,
                    onPickLocalFiles = onPickLocalFiles
                )
            }

            state.allTracks.isEmpty() -> {
                EmptyLibraryCard(
                    onRescan = onRescan,
                    onPickLocalFiles = onPickLocalFiles
                )
            }

            state.selectedArtist != null -> {
                val artistTracks = state.filteredTracks.filter { it.track.artist == state.selectedArtist }
                TrackListWithActions(
                    tracks = artistTracks,
                    playlistLabel = state.selectedArtist,
                    currentTrackKey = currentTrackKey,
                    onPlayTracks = onPlayTracks,
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    onSetMoodTag = { moodTarget = it },
                    onShowDetails = { detailsTarget = it }
                )
            }

            state.selectedAlbum != null -> {
                val albumTracks = state.filteredTracks.filter { it.track.album == state.selectedAlbum }
                TrackListWithActions(
                    tracks = albumTracks,
                    playlistLabel = state.selectedAlbum,
                    currentTrackKey = currentTrackKey,
                    onPlayTracks = onPlayTracks,
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    onSetMoodTag = { moodTarget = it },
                    onShowDetails = { detailsTarget = it }
                )
            }

            state.categoryTab == LibraryCategoryTab.SONGS -> {
                if (state.filteredTracks.isEmpty()) {
                    NoSearchMatchesState(
                        query = state.searchQuery,
                        onClearSearch = { onSearchQueryChange("") }
                    )
                } else {
                    TrackListWithActions(
                        tracks = state.filteredTracks,
                        playlistLabel = "Library",
                        currentTrackKey = currentTrackKey,
                        onPlayTracks = onPlayTracks,
                        onPlayNext = onPlayNext,
                        onAddToQueue = onAddToQueue,
                        onSetMoodTag = { moodTarget = it },
                        onShowDetails = { detailsTarget = it }
                    )
                }
            }

            state.categoryTab == LibraryCategoryTab.ARTISTS -> {
                if (state.artists.isEmpty()) {
                    NoSearchMatchesState(
                        query = state.searchQuery,
                        onClearSearch = { onSearchQueryChange("") }
                    )
                } else {
                    ArtistList(
                        artists = state.artists,
                        onSelectArtist = { onSelectArtist(it.name) }
                    )
                }
            }

            state.categoryTab == LibraryCategoryTab.ALBUMS -> {
                if (state.albums.isEmpty()) {
                    NoSearchMatchesState(
                        query = state.searchQuery,
                        onClearSearch = { onSearchQueryChange("") }
                    )
                } else {
                    AlbumList(
                        albums = state.albums,
                        onSelectAlbum = { onSelectAlbum(it.name) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackListWithActions(
    tracks: List<TrackWithStats>,
    playlistLabel: String,
    currentTrackKey: String?,
    onPlayTracks: (List<TrackWithStats>, Int, String, Boolean?) -> Unit,
    onPlayNext: (TrackWithStats) -> Unit,
    onAddToQueue: (TrackWithStats) -> Unit,
    onSetMoodTag: (TrackWithStats) -> Unit,
    onShowDetails: (TrackWithStats) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item(key = "actions_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { onPlayTracks(tracks, 0, playlistLabel, false) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("play_all_button")
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Play All")
                }
                FilledTonalButton(
                    onClick = { onPlayTracks(tracks, 0, playlistLabel, true) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("shuffle_all_button")
                ) {
                    Icon(Icons.Filled.Shuffle, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Shuffle")
                }
            }
        }
        itemsIndexed(
            items = tracks,
            key = { _, item -> item.track.trackKey }
        ) { index, item ->
            TrackRowItem(
                item = item,
                isCurrentTrack = item.track.trackKey == currentTrackKey,
                onClick = { onPlayTracks(tracks, index, playlistLabel, null) },
                onPlayNext = { onPlayNext(item) },
                onAddToQueue = { onAddToQueue(item) },
                onSetMoodTag = { onSetMoodTag(item) },
                onShowDetails = { onShowDetails(item) }
            )
        }
    }
}

@Composable
private fun ArtistList(
    artists: List<ArtistSummary>,
    onSelectArtist: (ArtistSummary) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(artists, key = { it.name }) { artist ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onSelectArtist(artist) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ArtworkBox(
                        artworkUri = artist.sampleArtUri,
                        contentDescription = artist.name,
                        modifier = Modifier.size(52.dp),
                        cornerRadius = 26.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = artist.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${artist.trackCount} track${if (artist.trackCount == 1) "" else "s"} • ${artist.albumCount} album${if (artist.albumCount == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (artist.totalPlays > 0) {
                        Text(
                            text = "${artist.totalPlays} plays",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumList(
    albums: List<AlbumSummary>,
    onSelectAlbum: (AlbumSummary) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(albums, key = { "${it.name}_${it.artist}" }) { album ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onSelectAlbum(album) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ArtworkBox(
                        artworkUri = album.artUri,
                        contentDescription = album.name,
                        modifier = Modifier.size(56.dp),
                        cornerRadius = 12.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = album.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${album.artist} • ${album.trackCount} track${if (album.trackCount == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = formatDurationMs(album.totalDurationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionOnboardingCard(
    scanError: String?,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onPickLocalFiles: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .testTag("permission_onboarding_card"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_empty_library),
                    contentDescription = "Local audio studio setup",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop
                )
                Text(
                    text = "Access Your Local Audio",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "SoundsGood scans MP3, FLAC, M4A/AAC, and Ogg/Vorbis files directly on your device—completely offline with zero account or cloud dependency.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                if (!scanError.isNullOrBlank()) {
                    Text(
                        text = scanError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("grant_audio_permission_button")
                ) {
                    Icon(Icons.Filled.LibraryMusic, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Grant Audio Access")
                }
                FilledTonalButton(
                    onClick = onPickLocalFiles,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pick_local_files_button")
                ) {
                    Icon(Icons.Filled.AudioFile, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Choose Local Audio Files")
                }
                TextButton(
                    onClick = onOpenAppSettings,
                    modifier = Modifier.testTag("open_app_settings_button")
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Open App Settings")
                }
            }
        }
    }
}

@Composable
private fun EmptyLibraryCard(
    onRescan: () -> Unit,
    onPickLocalFiles: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .testTag("empty_library_card"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_empty_library),
                    contentDescription = "Empty local music library",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop
                )
                Text(
                    text = "No Audio Files Found",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "No music files were found in MediaStore yet. Add MP3, FLAC, M4A, or Ogg audio files to your device Music folder, or pick files directly.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onRescan,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Rescan")
                    }
                    Button(
                        onClick = onPickLocalFiles,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Pick Files")
                    }
                }
            }
        }
    }
}

@Composable
private fun NoSearchMatchesState(
    query: String,
    onClearSearch: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = "No matches for \"$query\"",
                style = MaterialTheme.typography.titleMedium
            )
            TextButton(onClick = onClearSearch) {
                Text("Clear search")
            }
        }
    }
}
