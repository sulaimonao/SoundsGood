package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.example.R
import com.example.playback.PlaybackUiState
import com.example.playback.QueueEntryUi
import com.example.ui.components.ArtworkBox
import com.example.ui.components.MoodTagDialog
import com.example.ui.components.formatDurationMs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    playbackState: PlaybackUiState,
    activePlaylistLabel: String?,
    onNavigateToLibrary: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeatMode: () -> Unit,
    onJumpToQueueIndex: (Int) -> Unit,
    onRemoveFromQueue: (Int) -> Unit,
    onSetMoodTag: (String, String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var showQueueSheet by remember { mutableStateOf(false) }
    var showMoodDialog by remember { mutableStateOf(false) }

    if (showMoodDialog && playbackState.currentTrackKey != null) {
        MoodTagDialog(
            trackTitle = playbackState.title,
            currentMood = playbackState.moodTag,
            onSaveMood = { newMood -> onSetMoodTag(playbackState.currentTrackKey, newMood) },
            onDismiss = { showMoodDialog = false }
        )
    }

    if (showQueueSheet) {
        ModalBottomSheet(onDismissRequest = { showQueueSheet = false }) {
            QueuePane(
                queue = playbackState.queue,
                shuffleEnabled = playbackState.shuffleEnabled,
                repeatMode = playbackState.repeatMode,
                onJumpToIndex = {
                    onJumpToQueueIndex(it)
                    showQueueSheet = false
                },
                onRemoveIndex = onRemoveFromQueue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
            )
        }
    }

    if (!playbackState.hasActiveTrack) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.widthIn(max = 400.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(72.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
                Text(
                    text = "Nothing Playing Yet",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "Choose any song or Smart Playlist from your local library to begin playback.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Button(onClick = onNavigateToLibrary) {
                    Text("Browse Library")
                }
            }
        }
        return
    }

    val glowColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
    val secondaryGlow = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(glowColor, secondaryGlow, Color.Transparent),
                        center = center.copy(y = size.height * 0.28f),
                        radius = size.maxDimension * 0.65f
                    )
                )
            }
    ) {
        val isWideLayout = maxWidth >= 600.dp

        if (isWideLayout) {
            // Adaptive Landscape / Tablet 2-Pane Layout
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    NowPlayingControlsContent(
                        playbackState = playbackState,
                        activePlaylistLabel = activePlaylistLabel,
                        isCompactArtwork = true,
                        showQueueButton = false,
                        onOpenQueue = {},
                        onOpenMoodTag = { showMoodDialog = true },
                        onTogglePlayPause = onTogglePlayPause,
                        onSeekTo = onSeekTo,
                        onNext = onNext,
                        onPrevious = onPrevious,
                        onToggleShuffle = onToggleShuffle,
                        onCycleRepeatMode = onCycleRepeatMode
                    )
                }

                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    QueuePane(
                        queue = playbackState.queue,
                        shuffleEnabled = playbackState.shuffleEnabled,
                        repeatMode = playbackState.repeatMode,
                        onJumpToIndex = onJumpToQueueIndex,
                        onRemoveIndex = onRemoveFromQueue,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        } else {
            // Portrait Handheld Layout
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                NowPlayingControlsContent(
                    playbackState = playbackState,
                    activePlaylistLabel = activePlaylistLabel,
                    isCompactArtwork = false,
                    showQueueButton = true,
                    onOpenQueue = { showQueueSheet = true },
                    onOpenMoodTag = { showMoodDialog = true },
                    onTogglePlayPause = onTogglePlayPause,
                    onSeekTo = onSeekTo,
                    onNext = onNext,
                    onPrevious = onPrevious,
                    onToggleShuffle = onToggleShuffle,
                    onCycleRepeatMode = onCycleRepeatMode
                )
            }
        }
    }
}

@Composable
private fun NowPlayingControlsContent(
    playbackState: PlaybackUiState,
    activePlaylistLabel: String?,
    isCompactArtwork: Boolean,
    showQueueButton: Boolean,
    onOpenQueue: () -> Unit,
    onOpenMoodTag: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeatMode: () -> Unit
) {
    // Top Header
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "PLAYING FROM",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = activePlaylistLabel?.takeIf { it.isNotBlank() } ?: "Local Library",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(
                onClick = onOpenMoodTag,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Icon(
                    imageVector = Icons.Outlined.Mood,
                    contentDescription = "Set mood tag"
                )
            }
            if (showQueueButton) {
                IconButton(
                    onClick = onOpenQueue,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("open_queue_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = stringResource(R.string.cd_queue)
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))

    // Artwork
    ArtworkBox(
        artworkUri = playbackState.artworkUri,
        contentDescription = stringResource(R.string.cd_album_art),
        modifier = if (isCompactArtwork) {
            Modifier.size(190.dp)
        } else {
            Modifier
                .fillMaxWidth(0.82f)
                .widthIn(max = 310.dp)
                .aspectRatio(1f)
        },
        cornerRadius = 24.dp
    )

    Spacer(Modifier.height(18.dp))

    // Metadata
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = playbackState.title.ifBlank { "Untitled Track" },
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("now_playing_title")
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${playbackState.artist.ifBlank { "Unknown Artist" }} • ${playbackState.album.ifBlank { "Unknown Album" }}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!playbackState.moodTag.isNullOrBlank() || playbackState.genre.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (playbackState.genre.isNotBlank() && playbackState.genre != "Unknown Genre") {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Text(
                            text = playbackState.genre,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                playbackState.moodTag?.let { mood ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = "Mood: $mood",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))

    // Seek Bar + Timecodes
    val duration = playbackState.durationMs.coerceAtLeast(1L)
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val currentFraction = (playbackState.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    val displayedFraction = if (isDragging) dragFraction else currentFraction
    val displayedPositionMs = if (isDragging) (dragFraction * duration).toLong() else playbackState.positionMs

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = displayedFraction,
            onValueChange = { fraction ->
                isDragging = true
                dragFraction = fraction
            },
            onValueChangeFinished = {
                isDragging = false
                onSeekTo((dragFraction * duration).toLong())
            },
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("now_playing_seek_slider")
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatDurationMs(displayedPositionMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatDurationMs(playbackState.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Spacer(Modifier.height(8.dp))

    // Transport Controls
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onToggleShuffle,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("now_playing_shuffle")
        ) {
            Icon(
                imageVector = Icons.Filled.Shuffle,
                contentDescription = stringResource(R.string.cd_shuffle),
                tint = if (playbackState.shuffleEnabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }

        IconButton(
            onClick = onPrevious,
            modifier = Modifier
                .size(52.dp)
                .testTag("now_playing_previous")
        ) {
            Icon(
                imageVector = Icons.Filled.SkipPrevious,
                contentDescription = stringResource(R.string.cd_previous),
                modifier = Modifier.size(34.dp)
            )
        }

        FilledIconButton(
            onClick = onTogglePlayPause,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            modifier = Modifier
                .size(68.dp)
                .testTag("now_playing_play_pause")
        ) {
            Icon(
                imageVector = if (playbackState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (playbackState.isPlaying) R.string.cd_pause else R.string.cd_play
                ),
                modifier = Modifier.size(36.dp)
            )
        }

        IconButton(
            onClick = onNext,
            modifier = Modifier
                .size(52.dp)
                .testTag("now_playing_next")
        ) {
            Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = stringResource(R.string.cd_next),
                modifier = Modifier.size(34.dp)
            )
        }

        IconButton(
            onClick = onCycleRepeatMode,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("now_playing_repeat")
        ) {
            val repeatIcon = if (playbackState.repeatMode == Player.REPEAT_MODE_ONE) {
                Icons.Filled.RepeatOne
            } else {
                Icons.Filled.Repeat
            }
            val repeatTint = if (playbackState.repeatMode == Player.REPEAT_MODE_OFF) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            }
            Icon(
                imageVector = repeatIcon,
                contentDescription = stringResource(R.string.cd_repeat),
                tint = repeatTint
            )
        }
    }

    // Clear Repeat / Shuffle state indicator row
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val repeatText = when (playbackState.repeatMode) {
            Player.REPEAT_MODE_ONE -> "Repeat One"
            Player.REPEAT_MODE_ALL -> "Repeat All"
            else -> "Repeat Off"
        }
        val shuffleText = if (playbackState.shuffleEnabled) "Shuffle On (Fisher-Yates)" else "Shuffle Off"
        Text(
            text = "$shuffleText  •  $repeatText",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun QueuePane(
    queue: List<QueueEntryUi>,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    onJumpToIndex: (Int) -> Unit,
    onRemoveIndex: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Up Next Queue (${queue.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (shuffleEnabled) "Consistent shuffled order" else "Sequential order",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val repeatBadge = when (repeatMode) {
                Player.REPEAT_MODE_ALL -> "Repeat All"
                Player.REPEAT_MODE_ONE -> "Repeat One"
                else -> null
            }
            if (repeatBadge != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = repeatBadge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(queue, key = { "${it.mediaIndex}_${it.trackKey}" }) { entry ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onJumpToIndex(entry.mediaIndex) },
                    color = if (entry.isCurrent) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    } else {
                        Color.Transparent
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ArtworkBox(
                            artworkUri = entry.artworkUri,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            cornerRadius = 8.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (entry.isCurrent) FontWeight.Bold else FontWeight.Medium,
                                color = if (entry.isCurrent) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = entry.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = formatDurationMs(entry.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!entry.isCurrent) {
                            IconButton(
                                onClick = { onRemoveIndex(entry.mediaIndex) },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.DeleteOutline,
                                    contentDescription = "Remove from queue",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
