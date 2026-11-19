package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query(
        """
        SELECT t.*, 
               COALESCE(s.playCount, 0) AS playCount,
               COALESCE(s.skipCount, 0) AS skipCount,
               COALESCE(s.totalListenedMs, 0) AS totalListenedMs,
               COALESCE(s.lastPlayedAtMs, 0) AS lastPlayedAtMs
        FROM tracks t
        LEFT JOIN track_stats s ON t.trackKey = s.trackKey
        ORDER BY t.title COLLATE NOCASE ASC
        """
    )
    fun observeAllTracksWithStats(): Flow<List<TrackWithStats>>

    @Query(
        """
        SELECT t.*, 
               COALESCE(s.playCount, 0) AS playCount,
               COALESCE(s.skipCount, 0) AS skipCount,
               COALESCE(s.totalListenedMs, 0) AS totalListenedMs,
               COALESCE(s.lastPlayedAtMs, 0) AS lastPlayedAtMs
        FROM tracks t
        LEFT JOIN track_stats s ON t.trackKey = s.trackKey
        """
    )
    suspend fun getAllTracksWithStatsOnce(): List<TrackWithStats>

    @Query("SELECT * FROM tracks WHERE trackKey = :trackKey LIMIT 1")
    suspend fun getTrackByKey(trackKey: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE trackKey IN (:keys)")
    suspend fun getTracksByKeys(keys: List<String>): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE sourceType = :sourceType")
    suspend fun getTracksBySourceType(sourceType: String): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE sourceType = 'SYNCED_FILE' AND syncLibraryId = :libraryId")
    suspend fun getSyncedTracksForLibrary(libraryId: String): List<TrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTracks(tracks: List<TrackEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrack(track: TrackEntity)

    @Query("DELETE FROM tracks WHERE sourceType = :sourceType AND trackKey NOT IN (:activeKeys)")
    suspend fun deleteRemovedTracksForSource(sourceType: String, activeKeys: List<String>)

    @Query("DELETE FROM tracks WHERE sourceType = :sourceType")
    suspend fun deleteAllTracksForSource(sourceType: String)

    @Query("UPDATE tracks SET moodTag = :moodTag WHERE trackKey = :trackKey")
    suspend fun updateMoodTag(trackKey: String, moodTag: String?)

    @Query("UPDATE tracks SET isMissingOrUnreadable = :unreadable, lastError = :error WHERE trackKey = :trackKey")
    suspend fun updateErrorStatus(trackKey: String, unreadable: Boolean, error: String?)

    // A scan may finish after a sync has moved the track to another URI.
    @Query("""
        UPDATE tracks SET isMissingOrUnreadable = :unreadable, lastError = :error
        WHERE trackKey = :trackKey AND contentUri = :scannedUri AND sourceType = 'SYNCED_FILE'
    """)
    suspend fun updateSyncedScanStatus(trackKey: String, scannedUri: String, unreadable: Boolean, error: String?)

    @Transaction
    suspend fun syncMediaStoreTracks(scannedTracks: List<TrackEntity>) {
        if (scannedTracks.isEmpty()) {
            deleteAllTracksForSource(TrackSourceType.MEDIA_STORE)
        } else {
            val activeKeys = scannedTracks.map { it.trackKey }
            // SQLite bind parameter limit safety: chunk deletions if huge, or handle typical libraries directly
            if (activeKeys.size <= 900) {
                deleteRemovedTracksForSource(TrackSourceType.MEDIA_STORE, activeKeys)
            } else {
                val existingKeys = getTracksBySourceType(TrackSourceType.MEDIA_STORE).map { it.trackKey }.toSet()
                val activeSet = activeKeys.toHashSet()
                val toDelete = existingKeys.filterNot { it in activeSet }
                toDelete.chunked(500).forEach { chunk ->
                    deleteByKeys(chunk)
                }
            }
            upsertTracks(scannedTracks)
        }
    }

    @Query("DELETE FROM tracks WHERE trackKey IN (:keys)")
    suspend fun deleteByKeys(keys: List<String>)
}

@Dao
interface StatsDao {
    @Query("SELECT * FROM track_stats WHERE trackKey = :trackKey LIMIT 1")
    suspend fun getStatsForTrack(trackKey: String): TrackStatsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStats(stats: TrackStatsEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertListeningEventIgnore(event: ListeningEventEntity): Long

    @Query("SELECT * FROM listening_events ORDER BY timestampMs DESC LIMIT :limit")
    fun observeRecentEvents(limit: Int = 50): Flow<List<ListeningEventEntity>>

    @Transaction
    suspend fun recordListeningEventOnce(
        trackKey: String,
        eventType: String,
        playbackSessionToken: String,
        listenedMs: Long,
        trackDurationMs: Long,
        timestampMs: Long = System.currentTimeMillis()
    ): Boolean {
        val rowId = insertListeningEventIgnore(
            ListeningEventEntity(
                trackKey = trackKey,
                eventType = eventType,
                playbackSessionToken = playbackSessionToken,
                timestampMs = timestampMs,
                listenedMs = listenedMs,
                trackDurationMs = trackDurationMs
            )
        )
        if (rowId == -1L) {
            return false // Already counted for this playback session token + event type
        }
        val current = getStatsForTrack(trackKey) ?: TrackStatsEntity(trackKey = trackKey)
        val updated = when (eventType) {
            ListeningEventType.PLAY -> current.copy(
                playCount = current.playCount + 1,
                totalListenedMs = current.totalListenedMs + listenedMs.coerceAtLeast(0L),
                lastPlayedAtMs = timestampMs
            )
            ListeningEventType.SKIP -> current.copy(
                skipCount = current.skipCount + 1,
                totalListenedMs = current.totalListenedMs + listenedMs.coerceAtLeast(0L),
                lastSkippedAtMs = timestampMs
            )
            else -> current
        }
        upsertStats(updated)
        return true
    }
}

@Dao
interface SmartPlaylistDao {
    @Query("SELECT * FROM smart_playlist_rules ORDER BY isBuiltIn DESC, updatedAtMs DESC")
    fun observeAllRules(): Flow<List<SmartPlaylistRuleEntity>>

    @Query("SELECT * FROM smart_playlist_rules WHERE id = :id LIMIT 1")
    fun observeRuleById(id: Long): Flow<SmartPlaylistRuleEntity?>

    @Query("SELECT * FROM smart_playlist_rules WHERE id = :id LIMIT 1")
    suspend fun getRuleById(id: Long): SmartPlaylistRuleEntity?

    @Query("SELECT COUNT(*) FROM smart_playlist_rules")
    suspend fun countRules(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: SmartPlaylistRuleEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRules(rules: List<SmartPlaylistRuleEntity>)

    @Update
    suspend fun updateRule(rule: SmartPlaylistRuleEntity)

    @Query("DELETE FROM smart_playlist_rules WHERE id = :id AND isBuiltIn = 0")
    suspend fun deleteCustomRule(id: Long)
}

@Dao
interface PlaybackSnapshotDao {
    @Query("SELECT * FROM playback_snapshot WHERE id = 1 LIMIT 1")
    suspend fun getSnapshot(): PlaybackSnapshotEntity?

    @Query("SELECT * FROM playback_snapshot WHERE id = 1 LIMIT 1")
    fun observeSnapshot(): Flow<PlaybackSnapshotEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSnapshot(snapshot: PlaybackSnapshotEntity)
}

@Dao
interface SyncedPlaylistDao {
    @Query("SELECT * FROM synced_playlists ORDER BY name COLLATE NOCASE ASC, libraryName COLLATE NOCASE ASC")
    fun observeAllSyncedPlaylists(): Flow<List<SyncedPlaylistEntity>>

    @Query("SELECT * FROM synced_playlist_entries ORDER BY playlistKey ASC, position ASC")
    fun observeAllSyncedPlaylistEntries(): Flow<List<SyncedPlaylistEntryEntity>>

    @Query("SELECT * FROM synced_playlists WHERE libraryId = :libraryId")
    suspend fun getSyncedPlaylistsForLibrary(libraryId: String): List<SyncedPlaylistEntity>

    @Query("SELECT * FROM synced_playlist_entries WHERE playlistKey = :playlistKey ORDER BY position ASC")
    suspend fun getEntriesForPlaylist(playlistKey: String): List<SyncedPlaylistEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncedPlaylists(playlists: List<SyncedPlaylistEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistEntries(entries: List<SyncedPlaylistEntryEntity>)

    @Query("DELETE FROM synced_playlist_entries WHERE playlistKey IN (:playlistKeys)")
    suspend fun deleteEntriesForPlaylists(playlistKeys: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTracks(tracks: List<TrackEntity>)

    @Query("SELECT trackKey, moodTag FROM tracks WHERE trackKey IN (:trackKeys) AND moodTag IS NOT NULL")
    suspend fun getMoodTagsForKeys(trackKeys: List<String>): List<TrackMoodRow>

    @Transaction
    suspend fun commitSyncBatch(
        tracksToUpsert: List<TrackEntity>,
        playlistsToUpsert: List<SyncedPlaylistEntity>,
        entriesToReplaceByPlaylist: Map<String, List<SyncedPlaylistEntryEntity>>
    ) {
        if (tracksToUpsert.isNotEmpty()) {
            val moodMap = mutableMapOf<String, String?>()
            tracksToUpsert.map { it.trackKey }.chunked(500).forEach { chunk ->
                getMoodTagsForKeys(chunk).forEach { row ->
                    moodMap[row.trackKey] = row.moodTag
                }
            }
            val merged = tracksToUpsert.map { track ->
                val preservedMood = moodMap[track.trackKey]
                if (preservedMood != null) track.copy(moodTag = preservedMood) else track
            }
            upsertTracks(merged)
        }

        if (playlistsToUpsert.isNotEmpty()) {
            upsertSyncedPlaylists(playlistsToUpsert)
        }

        val keysToReplace = entriesToReplaceByPlaylist.keys.toList()
        keysToReplace.chunked(500).forEach { chunk ->
            deleteEntriesForPlaylists(chunk)
        }
        val allEntries = entriesToReplaceByPlaylist.values.flatten()
        allEntries.chunked(500).forEach { chunk ->
            insertPlaylistEntries(chunk)
        }
    }
}

data class TrackMoodRow(
    val trackKey: String,
    val moodTag: String?
)
