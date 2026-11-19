package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material.icons.outlined.QueuePlayNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.db.TrackSourceType
import com.example.data.db.TrackWithStats
import com.example.domain.SmartPlaylistEvaluator
import com.example.playback.PlaybackUiState

fun formatDurationMs(durationMs: Long): String {
    val totalSec = (durationMs / 1000L).coerceAtLeast(0L)
    val hours = totalSec / 3600L
    val mins = (totalSec % 3600L) / 60L
    val secs = totalSec % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, mins, secs)
    } else {
        "%d:%02d".format(mins, secs)
    }
}

@Composable
fun ArtworkBox(
    artworkUri: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp
) {
    val shape = RoundedCornerShape(cornerRadius)
    val context = LocalContext.current

    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), shape),
        contentAlignment = Alignment.Center
    ) {
        if (artworkUri.isNullOrBlank()) {
            Image(
                painter = painterResource(id = R.drawable.img_artwork_fallback),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context)
                    .data(artworkUri)
                    .crossfade(true)
                    .build(),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                loading = {
                    Image(
                        painter = painterResource(id = R.drawable.img_artwork_fallback),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                },
                error = {
                    Image(
                        painter = painterResource(id = R.drawable.img_artwork_fallback),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            )
        }
    }
}

@Composable
fun TrackRowItem(
    item: TrackWithStats,
    isCurrentTrack: Boolean,
    onClick: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onSetMoodTag: () -> Unit,
    onShowDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val track = item.track
    val containerColor = if (isCurrentTrack) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0f)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag("track_row_${track.trackKey}"),
        color = containerColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(contentAlignment = Alignment.Center) {
                ArtworkBox(
                    artworkUri = track.albumArtUri,
                    contentDescription = stringResource(R.string.cd_album_art),
                    modifier = Modifier.size(52.dp),
                    cornerRadius = 10.dp
                )
                if (track.isMissingOrUnreadable) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ErrorOutline,
                            contentDescription = "Unreadable audio file",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isCurrentTrack) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (isCurrentTrack) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${track.artist} • ${track.album}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (track.moodTag != null || item.playCount > 0 || track.isMissingOrUnreadable || track.sourceType == TrackSourceType.SYNCED_FILE) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (track.sourceType == TrackSourceType.SYNCED_FILE) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer
                            ) {
                                Text(
                                    text = "Mac Sync",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        track.moodTag?.let { mood ->
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = mood,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (item.playCount > 0) {
                            Text(
                                text = "${item.playCount} play${if (item.playCount == 1) "" else "s"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (track.isMissingOrUnreadable) {
                            Text(
                                text = "Unreadable",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = formatDurationMs(track.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.cd_more_options),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Play Next") },
                        leadingIcon = { Icon(Icons.Outlined.QueuePlayNext, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onPlayNext()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Add to Queue") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onAddToQueue()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Set Mood Tag") },
                        leadingIcon = { Icon(Icons.Outlined.Mood, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onSetMoodTag()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Track Details & Stats") },
                        leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onShowDetails()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun MiniPlayerBar(
    playbackState: PlaybackUiState,
    activePlaylistLabel: String?,
    onOpenNowPlaying: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!playbackState.hasActiveTrack) return
    val progress = if (playbackState.durationMs > 0L) {
        (playbackState.positionMs.toFloat() / playbackState.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onOpenNowPlaying)
            .testTag("mini_player_bar"),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 4.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ArtworkBox(
                    artworkUri = playbackState.artworkUri,
                    contentDescription = stringResource(R.string.cd_album_art),
                    modifier = Modifier.size(44.dp),
                    cornerRadius = 10.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = playbackState.title.ifBlank { "Ready to Play" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append(playbackState.artist.ifBlank { "Unknown Artist" })
                            if (!activePlaylistLabel.isNullOrBlank()) {
                                append(" • ")
                                append(activePlaylistLabel)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("mini_player_play_pause")
                ) {
                    Icon(
                        imageVector = if (playbackState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(
                            if (playbackState.isPlaying) R.string.cd_pause else R.string.cd_play
                        ),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = onNext,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("mini_player_next")
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = stringResource(R.string.cd_next)
                    )
                }
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoodTagDialog(
    trackTitle: String,
    currentMood: String?,
    onSaveMood: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    var customMood by remember(currentMood) { mutableStateOf(currentMood.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Explicit Mood Tag") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Assign an explicit mood tag for \"$trackTitle\" so Smart Playlists can filter by mood without guessing from missing metadata.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SmartPlaylistEvaluator.DEFAULT_MOOD_TAGS.forEach { preset ->
                        FilterChip(
                            selected = customMood.equals(preset, ignoreCase = true),
                            onClick = {
                                customMood = if (customMood.equals(preset, ignoreCase = true)) "" else preset
                            },
                            label = { Text(preset) }
                        )
                    }
                }
                OutlinedTextField(
                    value = customMood,
                    onValueChange = { customMood = it },
                    label = { Text("Custom mood tag (leave blank to clear)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSaveMood(customMood.trim().takeIf { it.isNotEmpty() })
                onDismiss()
            }) {
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
fun TrackInfoDialog(
    item: TrackWithStats,
    onDismiss: () -> Unit
) {
    val track = item.track
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = track.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailRow("Artist", track.artist)
                DetailRow("Album", track.album)
                DetailRow("Genre", track.genre)
                DetailRow("Mood Tag", track.moodTag ?: "None")
                DetailRow("Duration", formatDurationMs(track.durationMs))
                DetailRow("Format", track.mimeType)
                val sourceLabel = when (track.sourceType) {
                    TrackSourceType.DOCUMENT_URI -> "Picked File"
                    TrackSourceType.SYNCED_FILE -> "Mac Wi-Fi Sync"
                    else -> "MediaStore"
                }
                DetailRow("Source", sourceLabel)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                DetailRow("Plays", item.playCount.toString())
                DetailRow("Skips", item.skipCount.toString())
                DetailRow("Preference Score", "%.1f".format(item.preferenceScore))
                if (track.isMissingOrUnreadable && !track.lastError.isNullOrBlank()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        text = "Error: ${track.lastError}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
