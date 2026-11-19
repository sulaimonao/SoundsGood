package com.example.data.repository

import android.net.Uri
import com.example.data.db.PlaybackSnapshotDao
import com.example.data.db.PlaybackSnapshotEntity
import com.example.data.db.SmartPlaylistDao
import com.example.data.db.SmartPlaylistRuleEntity
import com.example.data.db.StatsDao
import com.example.data.db.SyncedPlaylistDao
import com.example.data.db.SyncedPlaylistEntity
import com.example.data.db.SyncedPlaylistEntryEntity
import com.example.data.db.TrackDao
import com.example.data.db.TrackEntity
import com.example.data.db.TrackSourceType
import com.example.data.db.TrackWithStats
import com.example.data.scanner.MediaScanResult
import com.example.data.scanner.MediaStoreScanner
import com.example.domain.SmartPlaylistEvaluator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

data class SyncedPlaylistTrackEntry(
    val position: Int,
    val trackWithStats: TrackWithStats
)

data class SyncedPlaylistWithTracks(
    val playlist: SyncedPlaylistEntity,
    val entries: List<SyncedPlaylistTrackEntry>
)

class LibraryRepository(
    private val trackDao: TrackDao,
    private val statsDao: StatsDao,
    private val smartPlaylistDao: SmartPlaylistDao,
    private val playbackSnapshotDao: PlaybackSnapshotDao,
    private val syncedPlaylistDao: SyncedPlaylistDao,
    private val scanner: MediaStoreScanner
) {
    private val json = Json { ignoreUnknownKeys = true }

    val allTracksWithStats: Flow<List<TrackWithStats>> = trackDao.observeAllTracksWithStats()
    val smartPlaylistRules: Flow<List<SmartPlaylistRuleEntity>> = smartPlaylistDao.observeAllRules()
    val playbackSnapshot: Flow<PlaybackSnapshotEntity?> = playbackSnapshotDao.observeSnapshot()

    val syncedPlaylists: Flow<List<SyncedPlaylistWithTracks>> = combine(
        syncedPlaylistDao.observeAllSyncedPlaylists(),
        syncedPlaylistDao.observeAllSyncedPlaylistEntries(),
        allTracksWithStats
    ) { playlists, allEntries, tracksWithStats ->
        val tracksByKey = tracksWithStats.associateBy { it.track.trackKey }
        val entriesByPlaylist = allEntries.groupBy { it.playlistKey }
        playlists.map { playlist ->
            val ordered = entriesByPlaylist[playlist.playlistKey]
                .orEmpty()
                .sortedBy { it.position }
                .mapNotNull { entry ->
                    tracksByKey[entry.trackKey]?.let { tws ->
                        SyncedPlaylistTrackEntry(
                            position = entry.position,
                            trackWithStats = tws
                        )
                    }
                }
            SyncedPlaylistWithTracks(
                playlist = playlist,
                entries = ordered
            )
        }
    }

    suspend fun ensureDefaultSmartPlaylists() = withContext(Dispatchers.IO) {
        if (smartPlaylistDao.countRules() == 0) {
            smartPlaylistDao.insertRules(SmartPlaylistEvaluator.defaultRules())
        }
    }

    suspend fun rescanMediaStoreLibrary(): MediaScanResult = withContext(Dispatchers.IO) {
        val result = scanner.scanMediaStoreAudio()
        if (result.errorMessage == null) {
            // Preserve user-assigned mood tags across rescans
            val existingMoods = trackDao.getTracksBySourceType(TrackSourceType.MEDIA_STORE)
                .mapNotNull { track -> track.moodTag?.let { track.trackKey to it } }
                .toMap()

            val merged = result.tracks.map { scanned ->
                val preservedMood = existingMoods[scanned.trackKey]
                if (preservedMood != null) scanned.copy(moodTag = preservedMood) else scanned
            }
            trackDao.syncMediaStoreTracks(merged)
        }

        // Also validate existing picked document URIs so moved/revoked files show clear status
        val docTracks = trackDao.getTracksBySourceType(TrackSourceType.DOCUMENT_URI)
        docTracks.forEach { docTrack ->
            val readable = scanner.isUriReadable(docTrack.contentUri)
            if (readable && docTrack.isMissingOrUnreadable) {
                trackDao.updateErrorStatus(docTrack.trackKey, false, null)
            } else if (!readable && !docTrack.isMissingOrUnreadable) {
                trackDao.updateErrorStatus(
                    docTrack.trackKey,
                    true,
                    "File unavailable or URI permission revoked"
                )
            }
        }
        // Check the stored playback location. Shared documents may be regranted later.
        val syncedTracks = trackDao.getTracksBySourceType(TrackSourceType.SYNCED_FILE)
        syncedTracks.forEach { syncedTrack ->
            val file = syncedTrack.localFilePath?.let { File(it) }
            val isShared = file == null && Uri.parse(syncedTrack.contentUri).scheme == "content"
            val readable = if (isShared) {
                scanner.isUriReadable(syncedTrack.contentUri)
            } else {
                file != null && file.isFile && file.length() == syncedTrack.sizeBytes
            }
            val unavailable = !readable
            if (syncedTrack.isMissingOrUnreadable != unavailable) {
                val error = when {
                    readable -> null
                    isShared -> "Music folder unavailable. Reconnect it in Local Sync."
                    else -> "Synced local audio file missing or size mismatch"
                }
                trackDao.updateSyncedScanStatus(
                    syncedTrack.trackKey, syncedTrack.contentUri, unavailable, error
                )
            }
        }
        result
    }

    suspend fun getAllSyncedTracks(): List<TrackEntity> = withContext(Dispatchers.IO) {
        trackDao.getTracksBySourceType(TrackSourceType.SYNCED_FILE)
    }

    suspend fun getSyncedPlaylistEntries(playlistKey: String): List<SyncedPlaylistEntryEntity> =
        withContext(Dispatchers.IO) { syncedPlaylistDao.getEntriesForPlaylist(playlistKey) }

    suspend fun getSyncedTracksForLibrary(libraryId: String): List<TrackEntity> =
        withContext(Dispatchers.IO) {
            trackDao.getSyncedTracksForLibrary(libraryId)
        }

    suspend fun getSyncedPlaylistsForLibrary(libraryId: String): List<SyncedPlaylistEntity> =
        withContext(Dispatchers.IO) {
            syncedPlaylistDao.getSyncedPlaylistsForLibrary(libraryId)
        }

    suspend fun commitSyncedLibraryBatch(
        tracksToUpsert: List<TrackEntity>,
        playlistsToUpsert: List<SyncedPlaylistEntity>,
        entriesToReplaceByPlaylist: Map<String, List<SyncedPlaylistEntryEntity>>
    ) = withContext(Dispatchers.IO) {
        syncedPlaylistDao.commitSyncBatch(
            tracksToUpsert = tracksToUpsert,
            playlistsToUpsert = playlistsToUpsert,
            entriesToReplaceByPlaylist = entriesToReplaceByPlaylist
        )
    }

    suspend fun importDocumentUris(uris: List<Uri>): Pair<List<TrackEntity>, List<String>> =
        withContext(Dispatchers.IO) {
            val imported = mutableListOf<TrackEntity>()
            val errors = mutableListOf<String>()

            uris.forEach { uri ->
                val inspection = scanner.inspectDocumentUri(uri)
                inspection.onSuccess { scanned ->
                    val existing = trackDao.getTrackByKey(scanned.trackKey)
                    val merged = if (existing?.moodTag != null) {
                        scanned.copy(moodTag = existing.moodTag)
                    } else {
                        scanned
                    }
                    trackDao.upsertTrack(merged)
                    if (merged.isMissingOrUnreadable) {
                        errors.add("${merged.title}: ${merged.lastError ?: "Unreadable audio"}")
                    } else {
                        imported.add(merged)
                    }
                }.onFailure { err ->
                    errors.add(err.localizedMessage ?: "Failed to open selected audio file")
                }
            }
            imported to errors
        }

    suspend fun setMoodTag(trackKey: String, moodTag: String?) = withContext(Dispatchers.IO) {
        trackDao.updateMoodTag(trackKey, moodTag?.trim()?.takeIf { it.isNotEmpty() })
    }

    suspend fun markTrackError(trackKey: String, error: String?) = withContext(Dispatchers.IO) {
        trackDao.updateErrorStatus(trackKey, error != null, error)
    }

    suspend fun recordListeningEventOnce(
        trackKey: String,
        eventType: String,
        playbackSessionToken: String,
        listenedMs: Long,
        trackDurationMs: Long
    ): Boolean = withContext(Dispatchers.IO) {
        statsDao.recordListeningEventOnce(
            trackKey = trackKey,
            eventType = eventType,
            playbackSessionToken = playbackSessionToken,
            listenedMs = listenedMs,
            trackDurationMs = trackDurationMs
        )
    }

    suspend fun saveSmartPlaylistRule(rule: SmartPlaylistRuleEntity): Long = withContext(Dispatchers.IO) {
        val stamped = rule.copy(updatedAtMs = System.currentTimeMillis())
        smartPlaylistDao.insertRule(stamped)
    }

    suspend fun deleteSmartPlaylistRule(ruleId: Long) = withContext(Dispatchers.IO) {
        smartPlaylistDao.deleteCustomRule(ruleId)
    }

    fun observeSmartPlaylistRule(ruleId: Long): Flow<SmartPlaylistRuleEntity?> =
        smartPlaylistDao.observeRuleById(ruleId)

    suspend fun getAllTracksWithStatsOnce(): List<TrackWithStats> = withContext(Dispatchers.IO) {
        trackDao.getAllTracksWithStatsOnce()
    }

    suspend fun savePlaybackSnapshot(
        currentTrackKey: String?,
        positionMs: Long,
        repeatMode: Int,
        shuffleEnabled: Boolean,
        queueTrackKeys: List<String>,
        shuffledTrackKeys: List<String>,
        activePlaylistLabel: String?
    ) = withContext(Dispatchers.IO) {
        playbackSnapshotDao.saveSnapshot(
            PlaybackSnapshotEntity(
                id = 1,
                currentTrackKey = currentTrackKey,
                positionMs = positionMs.coerceAtLeast(0L),
                repeatMode = repeatMode,
                shuffleEnabled = shuffleEnabled,
                queueTrackKeysJson = json.encodeToString(queueTrackKeys),
                shuffledTrackKeysJson = json.encodeToString(shuffledTrackKeys),
                activePlaylistLabel = activePlaylistLabel,
                updatedAtMs = System.currentTimeMillis()
            )
        )
    }

    suspend fun loadPlaybackSnapshot(): RestoredPlaybackState? = withContext(Dispatchers.IO) {
        val snapshot = playbackSnapshotDao.getSnapshot() ?: return@withContext null
        val queueKeys = runCatching {
            json.decodeFromString<List<String>>(snapshot.queueTrackKeysJson)
        }.getOrDefault(emptyList())
        if (queueKeys.isEmpty()) return@withContext null

        val shuffledKeys = runCatching {
            json.decodeFromString<List<String>>(snapshot.shuffledTrackKeysJson)
        }.getOrDefault(emptyList())

        val trackMap = trackDao.getTracksByKeys(queueKeys).associateBy { it.trackKey }
        val orderedTracks = queueKeys.mapNotNull { trackMap[it] }.filterNot { it.isMissingOrUnreadable }
        if (orderedTracks.isEmpty()) return@withContext null

        RestoredPlaybackState(
            tracks = orderedTracks,
            currentTrackKey = snapshot.currentTrackKey,
            positionMs = snapshot.positionMs,
            repeatMode = snapshot.repeatMode,
            shuffleEnabled = snapshot.shuffleEnabled,
            shuffledOrderKeys = shuffledKeys,
            activePlaylistLabel = snapshot.activePlaylistLabel
        )
    }
}

data class RestoredPlaybackState(
    val tracks: List<TrackEntity>,
    val currentTrackKey: String?,
    val positionMs: Long,
    val repeatMode: Int,
    val shuffleEnabled: Boolean,
    val shuffledOrderKeys: List<String>,
    val activePlaylistLabel: String?
)
