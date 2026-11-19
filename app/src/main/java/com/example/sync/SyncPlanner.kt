package com.example.sync

import com.example.data.db.TrackEntity

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
    val skippedItems: List<SyncSkippedDto>,
    val usesMusicFolder: Boolean = false,
    val destinationCapacityUnknown: Boolean = false,
    val requiredFolderBytes: Long = 0L,
    val stagingBytes: Long = 0L
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
        availableStorageBytes: Long = fileStorage.availableStorageBytes(),
        folderCandidates: Set<String>? = null,
        folderCapacity: Long? = null
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
        val destinationBlobs = HashSet<String>()
        val missingBlobs = HashSet<String>()
        fun addBytes(total: Long, amount: Long): Long {
            if (amount < 0L || amount > Long.MAX_VALUE - total) {
                overflow = true
                return Long.MAX_VALUE
            }
            return total + amount
        }
        var folderBytes = 0L
        var stagingBytes = 0L

        for (dto in selectedTracks) {
            val stableKey = SyncManifestValidator.stableTrackKey(manifest.libraryId, dto.id)
            val existing = existingByKey[stableKey]
            val targetFile = fileStorage.targetFileFor(dto.sha256, dto.extension)
            val existingLocalFile = fileStorage.ownedPrivateAudio(existing?.localFilePath)
            if (folderCandidates != null) {
                val blobName = targetFile.name
                val inFolder = blobName in folderCandidates
                // File presence/size is a preview estimate. The transfer verifies bytes before every commit.
                val hasPrivate = !inFolder && ((existing?.contentSha256 == dto.sha256 && existingLocalFile?.isFile == true &&
                    existingLocalFile.length() == dto.sizeBytes) || (targetFile.isFile && targetFile.length() == dto.sizeBytes))
                if (existing == null) newCount++ else if (existing.contentSha256 == dto.sha256 && (inFolder || hasPrivate)) unchangedCount++
                else changedCount++
                if (!inFolder && destinationBlobs.add(blobName)) folderBytes = addBytes(folderBytes, dto.sizeBytes)
                if (inFolder || hasPrivate || !missingBlobs.add(blobName)) {
                    reusedCount++
                } else {
                    toDownload.add(dto)
                    requiredBytes = addBytes(requiredBytes, dto.sizeBytes)
                    stagingBytes = maxOf(stagingBytes, dto.sizeBytes)
                }
                continue
            }

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

        val folderWithReserve = if (folderBytes > 0) addBytes(folderBytes, maxOf(STORAGE_SAFETY_RESERVE_MIN, folderBytes / 20)) else 0L
        val stagingWithReserve = if (stagingBytes > 0) addBytes(stagingBytes, 4L * 1024L * 1024L) else 0L
        val hasEnough = !overflow && if (folderCandidates == null) requiredWithReserve <= availableStorageBytes
            else stagingWithReserve <= availableStorageBytes && (folderCapacity == null || folderWithReserve <= folderCapacity)

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
            requiredWithReserveBytes = if (folderCandidates == null) requiredWithReserve else folderWithReserve,
            availableStorageBytes = if (folderCandidates == null) availableStorageBytes else folderCapacity ?: 0L,
            hasEnoughStorage = hasEnough,
            storageOverflow = overflow,
            skippedItems = manifest.skipped,
            usesMusicFolder = folderCandidates != null, destinationCapacityUnknown = folderCandidates != null && folderCapacity == null,
            requiredFolderBytes = folderBytes, stagingBytes = stagingBytes
        )
    }
}
