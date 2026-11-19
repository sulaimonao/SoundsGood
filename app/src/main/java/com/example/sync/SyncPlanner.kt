package com.example.sync

import com.example.data.db.TrackEntity
import java.io.File

data class SyncPreviewPlan(
    val libraryId: String,
    val libraryName: String,
    val revision: String,
    val syncAllMusic: Boolean,
    val selectedPlaylistIds: Set<String>,
    val newTracksCount: Int,
    val changedTracksCount: Int,
    val unchangedTracksCount: Int,
    val reusedVerifiedFilesCount: Int,
    val tracksToDownload: List<SyncTrackDto>,
    val selectedTracks: List<SyncTrackDto>,
    val selectedPlaylists: List<SyncPlaylistDto>,
    val requiredDownloadBytes: Long,
    val requiredWithReserveBytes: Long,
    val availableStorageBytes: Long,
    val hasEnoughStorage: Boolean,
    val storageOverflow: Boolean,
    val skippedItems: List<SyncSkippedDto>
) {
    val totalSelectedTracksCount: Int
        get() = selectedTracks.size
}

object SyncPlanner {

    private const val STORAGE_SAFETY_RESERVE_MIN = 16L * 1024L * 1024L // 16 MiB

    fun buildPreview(
        manifest: SyncManifest,
        syncAllMusic: Boolean,
        selectedPlaylistIds: Set<String>,
        existingSyncedTracks: List<TrackEntity>,
        fileStorage: SyncFileStorage,
        availableStorageBytes: Long = fileStorage.availableStorageBytes()
    ): SyncPreviewPlan {
        val existingByKey = existingSyncedTracks.associateBy { it.trackKey }
        val trackById = manifest.tracks.associateBy { it.id }

        val selectedPlaylists = if (syncAllMusic) {
            manifest.playlists
        } else {
            manifest.playlists.filter { it.id in selectedPlaylistIds }
        }
        val selectedTracks: List<SyncTrackDto> = if (syncAllMusic) {
            manifest.tracks
        } else {
            val seenIds = LinkedHashSet<String>()
            val list = mutableListOf<SyncTrackDto>()
            selectedPlaylists.forEach { playlist ->
                playlist.trackIds.forEach { tid ->
                    if (seenIds.add(tid)) {
                        trackById[tid]?.let { list.add(it) }
                    }
                }
            }
            list
        }

        var newCount = 0
        var changedCount = 0
        var unchangedCount = 0
        var reusedCount = 0
        var requiredBytes = 0L
        var overflow = false
        val toDownload = mutableListOf<SyncTrackDto>()

        for (dto in selectedTracks) {
            val stableKey = SyncManifestValidator.stableTrackKey(manifest.libraryId, dto.id)
            val existing = existingByKey[stableKey]
            val targetFile = fileStorage.targetFileFor(dto.sha256, dto.extension)
            val existingLocalFile = existing?.localFilePath?.let { File(it) }

            val dbMatchesAndDiskVerified = existing != null &&
                existing.contentSha256 == dto.sha256 &&
                existingLocalFile != null &&
                fileStorage.verifyExistingFile(existingLocalFile, dto.sizeBytes, dto.sha256)

            if (dbMatchesAndDiskVerified) {
                unchangedCount++
            } else if (fileStorage.verifyExistingFile(targetFile, dto.sizeBytes, dto.sha256)) {
                // Previously completed & verified file on disk (e.g. from interrupted sync before DB commit)
                reusedCount++
                if (existing != null && existing.contentSha256 == dto.sha256) {
                    unchangedCount++
                } else if (existing != null) {
                    changedCount++
                } else {
                    newCount++
                }
            } else {
                toDownload.add(dto)
                if (existing != null) {
                    changedCount++
                } else {
                    newCount++
                }
                if (!overflow) {
                    if (Long.MAX_VALUE - requiredBytes < dto.sizeBytes) {
                        overflow = true
                        requiredBytes = Long.MAX_VALUE
                    } else {
                        requiredBytes += dto.sizeBytes
                    }
                }
            }
        }

        val reserveMargin = if (requiredBytes == 0L) {
            0L
        } else {
            maxOf(STORAGE_SAFETY_RESERVE_MIN, requiredBytes / 20L)
        }

        val requiredWithReserve = when {
            overflow -> Long.MAX_VALUE
            requiredBytes == 0L -> 0L
            Long.MAX_VALUE - requiredBytes < reserveMargin -> {
                overflow = true
                Long.MAX_VALUE
            }
            else -> requiredBytes + reserveMargin
        }

        val hasEnough = !overflow && requiredWithReserve <= availableStorageBytes

        return SyncPreviewPlan(
            libraryId = manifest.libraryId,
            libraryName = manifest.libraryName,
            revision = manifest.revision,
            syncAllMusic = syncAllMusic,
            selectedPlaylistIds = selectedPlaylistIds,
            newTracksCount = newCount,
            changedTracksCount = changedCount,
            unchangedTracksCount = unchangedCount,
            reusedVerifiedFilesCount = reusedCount,
            tracksToDownload = toDownload,
            selectedTracks = selectedTracks,
            selectedPlaylists = selectedPlaylists,
            requiredDownloadBytes = requiredBytes,
            requiredWithReserveBytes = requiredWithReserve,
            availableStorageBytes = availableStorageBytes,
            hasEnoughStorage = hasEnough,
            storageOverflow = overflow,
            skippedItems = manifest.skipped
        )
    }
}
