package com.example.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SyncManifest(
    val protocolVersion: Int,
    val libraryId: String,
    val libraryName: String,
    val revision: String,
    val tracks: List<SyncTrackDto>,
    val playlists: List<SyncPlaylistDto>,
    val skipped: List<SyncSkippedDto>
)

@Serializable
data class SyncTrackDto(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val sha256: String,
    val mimeType: String,
    val extension: String
)

@Serializable
data class SyncPlaylistDto(
    val id: String,
    val name: String,
    val trackIds: List<String>
)

@Serializable
data class SyncSkippedDto(
    val title: String,
    val reason: String
)

sealed interface ManifestValidationResult {
    data class Valid(val manifest: SyncManifest) : ManifestValidationResult
    data class Invalid(val reason: String) : ManifestValidationResult
}

object SyncManifestValidator {

    const val MAX_MANIFEST_BYTES: Long = 16L * 1024L * 1024L
    const val MAX_AUDIO_FILE_BYTES: Long = 2L * 1024L * 1024L * 1024L
    const val MAX_TRACKS: Int = 100_000
    const val MAX_PLAYLISTS: Int = 10_000
    const val MAX_AGGREGATE_PLAYLIST_ENTRIES: Long = 1_000_000L
    const val MAX_METADATA_CODE_UNITS: Int = 1024

    val ID_REGEX = Regex("^[A-Za-z0-9_-]{1,100}$")
    val SHA256_REGEX = Regex("^[0-9a-f]{64}$")

    val ALLOWED_EXTENSION_MIME_PAIRS: Map<String, String> = mapOf(
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "aac" to "audio/aac",
        "flac" to "audio/flac",
        "ogg" to "audio/ogg",
        "opus" to "audio/ogg",
        "wav" to "audio/wav",
        "aif" to "audio/aiff",
        "aiff" to "audio/aiff"
    )

    private val strictJson = Json {
        ignoreUnknownKeys = true
        isLenient = false
        explicitNulls = true
    }

    fun parseAndValidate(rawBytes: ByteArray): ManifestValidationResult {
        if (rawBytes.size.toLong() > MAX_MANIFEST_BYTES) {
            return ManifestValidationResult.Invalid("Manifest exceeds the 16 MiB size limit.")
        }
        val text = try {
            rawBytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: Exception) {
            return ManifestValidationResult.Invalid("Manifest is not valid UTF-8.")
        }

        val manifest = try {
            strictJson.decodeFromString<SyncManifest>(text)
        } catch (e: Exception) {
            return ManifestValidationResult.Invalid("Malformed manifest JSON: ${e.message ?: "invalid fields"}")
        }

        return validate(manifest)
    }

    fun validate(manifest: SyncManifest): ManifestValidationResult {
        if (manifest.protocolVersion != 1) {
            return ManifestValidationResult.Invalid("Unsupported manifest protocolVersion=${manifest.protocolVersion}.")
        }
        if (!ID_REGEX.matches(manifest.libraryId)) {
            return ManifestValidationResult.Invalid("Invalid libraryId format.")
        }
        if (!ID_REGEX.matches(manifest.revision)) {
            return ManifestValidationResult.Invalid("Invalid manifest revision format.")
        }
        if (manifest.libraryName.isBlank() || manifest.libraryName.length > MAX_METADATA_CODE_UNITS) {
            return ManifestValidationResult.Invalid("Invalid libraryName length.")
        }
        if (manifest.tracks.size > MAX_TRACKS) {
            return ManifestValidationResult.Invalid("Manifest exceeds maximum track limit ($MAX_TRACKS).")
        }
        if (manifest.playlists.size > MAX_PLAYLISTS) {
            return ManifestValidationResult.Invalid("Manifest exceeds maximum playlist limit ($MAX_PLAYLISTS).")
        }

        val declaredTrackIds = HashSet<String>(manifest.tracks.size)
        for (track in manifest.tracks) {
            if (!ID_REGEX.matches(track.id)) {
                return ManifestValidationResult.Invalid("Invalid track id '${track.id}'.")
            }
            if (!declaredTrackIds.add(track.id)) {
                return ManifestValidationResult.Invalid("Duplicate track id '${track.id}'.")
            }
            if (track.title.isBlank() || track.title.length > MAX_METADATA_CODE_UNITS) {
                return ManifestValidationResult.Invalid("Invalid track title length for '${track.id}'.")
            }
            if (track.artist.length > MAX_METADATA_CODE_UNITS ||
                track.album.length > MAX_METADATA_CODE_UNITS ||
                track.genre.length > MAX_METADATA_CODE_UNITS
            ) {
                return ManifestValidationResult.Invalid("Metadata string exceeds 1024 code units for '${track.id}'.")
            }
            if (track.durationMs < 0L) {
                return ManifestValidationResult.Invalid("Negative duration for track '${track.id}'.")
            }
            if (track.sizeBytes < 0L || track.sizeBytes > MAX_AUDIO_FILE_BYTES) {
                return ManifestValidationResult.Invalid("Invalid sizeBytes for track '${track.id}'.")
            }
            if (!SHA256_REGEX.matches(track.sha256)) {
                return ManifestValidationResult.Invalid("Invalid sha256 digest for track '${track.id}'.")
            }
            val expectedMime = ALLOWED_EXTENSION_MIME_PAIRS[track.extension]
            if (expectedMime == null || expectedMime != track.mimeType) {
                return ManifestValidationResult.Invalid(
                    "Unsupported or mismatched extension/mimeType (${track.extension}, ${track.mimeType}) for '${track.id}'."
                )
            }
        }

        val declaredPlaylistIds = HashSet<String>(manifest.playlists.size)
        var aggregateEntries = 0L
        for (playlist in manifest.playlists) {
            if (!ID_REGEX.matches(playlist.id)) {
                return ManifestValidationResult.Invalid("Invalid playlist id '${playlist.id}'.")
            }
            if (!declaredPlaylistIds.add(playlist.id)) {
                return ManifestValidationResult.Invalid("Duplicate playlist id '${playlist.id}'.")
            }
            if (playlist.name.isBlank() || playlist.name.length > MAX_METADATA_CODE_UNITS) {
                return ManifestValidationResult.Invalid("Invalid playlist name for '${playlist.id}'.")
            }
            aggregateEntries += playlist.trackIds.size.toLong()
            if (aggregateEntries > MAX_AGGREGATE_PLAYLIST_ENTRIES) {
                return ManifestValidationResult.Invalid(
                    "Manifest exceeds maximum aggregate playlist entries ($MAX_AGGREGATE_PLAYLIST_ENTRIES)."
                )
            }
            for (refId in playlist.trackIds) {
                if (!ID_REGEX.matches(refId) || refId !in declaredTrackIds) {
                    return ManifestValidationResult.Invalid(
                        "Playlist '${playlist.id}' references undeclared track '$refId'."
                    )
                }
            }
        }

        for (skip in manifest.skipped) {
            if (skip.title.length > MAX_METADATA_CODE_UNITS || skip.reason.length > MAX_METADATA_CODE_UNITS) {
                return ManifestValidationResult.Invalid("Skipped item metadata exceeds 1024 code units.")
            }
        }

        return ManifestValidationResult.Valid(manifest)
    }

    fun stableTrackKey(libraryId: String, trackId: String): String = "sync:$libraryId:$trackId"

    fun stablePlaylistKey(libraryId: String, playlistId: String): String = "syncpl:$libraryId:$playlistId"
}
