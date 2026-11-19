package com.example.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object TrackSourceType {
    const val MEDIA_STORE = "MEDIA_STORE"
    const val DOCUMENT_URI = "DOCUMENT_URI"
    const val SYNCED_FILE = "SYNCED_FILE"
}

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val trackKey: String,
    val mediaStoreId: Long? = null,
    val contentUri: String,
    val sourceType: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long? = null,
    val albumArtUri: String? = null,
    val genre: String = "Unknown Genre",
    val durationMs: Long = 0L,
    val dateAddedSec: Long = 0L,
    val mimeType: String = "audio/*",
    val sizeBytes: Long = 0L,
    val moodTag: String? = null,
    val isMissingOrUnreadable: Boolean = false,
    val lastError: String? = null,
    val lastScannedAtMs: Long = 0L,
    val syncLibraryId: String? = null,
    val syncTrackId: String? = null,
    val contentSha256: String? = null,
    val localFilePath: String? = null
)

@Entity(tableName = "track_stats")
data class TrackStatsEntity(
    @PrimaryKey val trackKey: String,
    val playCount: Int = 0,
    val skipCount: Int = 0,
    val totalListenedMs: Long = 0L,
    val lastPlayedAtMs: Long = 0L,
    val lastSkippedAtMs: Long = 0L
)

data class TrackWithStats(
    @Embedded val track: TrackEntity,
    val playCount: Int = 0,
    val skipCount: Int = 0,
    val totalListenedMs: Long = 0L,
    val lastPlayedAtMs: Long = 0L
) {
    /**
     * Transparent local preference score:
     * +1.0 per completed/qualified play, -0.5 per intentional skip.
     */
    val preferenceScore: Double
        get() = (playCount * 1.0) - (skipCount * 0.5)

    val durationSec: Long
        get() = (track.durationMs / 1000L).coerceAtLeast(0L)
}

object ListeningEventType {
    const val PLAY = "PLAY"
    const val SKIP = "SKIP"
}

@Entity(
    tableName = "listening_events",
    indices = [
        Index(value = ["playbackSessionToken", "eventType"], unique = true),
        Index(value = ["trackKey"])
    ]
)
data class ListeningEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val trackKey: String,
    val eventType: String,
    val playbackSessionToken: String,
    val timestampMs: Long,
    val listenedMs: Long,
    val trackDurationMs: Long
)

enum class SmartSortOrder(val label: String) {
    PREFERENCE_RANKED("Preference Ranked"),
    MOST_PLAYED("Most Played"),
    RECENTLY_ADDED("Recently Added"),
    ALPHABETICAL("Title A–Z"),
    DURATION_ASC("Shortest First"),
    DURATION_DESC("Longest First")
}

object BuiltInSmartPlaylistType {
    const val RECENTLY_ADDED = "RECENTLY_ADDED"
    const val MOST_PLAYED = "MOST_PLAYED"
}

@Entity(tableName = "smart_playlist_rules")
data class SmartPlaylistRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val description: String = "",
    val isBuiltIn: Boolean = false,
    val builtInType: String? = null,
    val minDurationSec: Long? = null,
    val maxDurationSec: Long? = null,
    val artistFilter: String? = null,
    val albumFilter: String? = null,
    val genreFilter: String? = null,
    val moodTagFilter: String? = null,
    val minPlayCount: Int? = null,
    val sortOrder: String = SmartSortOrder.PREFERENCE_RANKED.name,
    val limitCount: Int? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "playback_snapshot")
data class PlaybackSnapshotEntity(
    @PrimaryKey val id: Int = 1,
    val currentTrackKey: String? = null,
    val positionMs: Long = 0L,
    val repeatMode: Int = 0,
    val shuffleEnabled: Boolean = false,
    val queueTrackKeysJson: String = "[]",
    val shuffledTrackKeysJson: String = "[]",
    val activePlaylistLabel: String? = null,
    val updatedAtMs: Long = 0L
)

@Entity(
    tableName = "synced_playlists",
    indices = [Index(value = ["libraryId", "remotePlaylistId"], unique = true)]
)
data class SyncedPlaylistEntity(
    @PrimaryKey val playlistKey: String,
    val libraryId: String,
    val libraryName: String,
    val remotePlaylistId: String,
    val name: String,
    val revision: String,
    val syncedAtMs: Long
)

@Entity(
    tableName = "synced_playlist_entries",
    primaryKeys = ["playlistKey", "position"],
    indices = [
        Index(value = ["playlistKey", "position"]),
        Index(value = ["trackKey"])
    ]
)
data class SyncedPlaylistEntryEntity(
    val playlistKey: String,
    val position: Int,
    val trackKey: String
)
