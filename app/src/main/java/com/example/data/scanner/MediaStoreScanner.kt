package com.example.data.scanner

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.example.data.db.TrackEntity
import com.example.data.db.TrackSourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MediaScanResult(
    val tracks: List<TrackEntity>,
    val skippedCount: Int = 0,
    val errorMessage: String? = null
)

class MediaStoreScanner(private val context: Context) {

    private val contentResolver: ContentResolver = context.contentResolver

    suspend fun scanMediaStoreAudio(): MediaScanResult = withContext(Dispatchers.IO) {
        val nowMs = System.currentTimeMillis()
        val genreMap = loadLegacyGenreMapIfNeeded()
        val tracks = mutableListOf<TrackEntity>()
        var skipped = 0

        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.IS_MUSIC)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.Audio.Media.GENRE)
            }
        }.toTypedArray()

        val selection = "(${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.MIME_TYPE} LIKE 'audio/%')"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        try {
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val dateAddedCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                val mimeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val displayNameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val isMusicCol = cursor.getColumnIndex(MediaStore.Audio.Media.IS_MUSIC)
                val genreCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.GENRE)
                } else {
                    -1
                }

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val durationMs = cursor.getLongOrZero(durationCol)
                    val isMusic = cursor.getIntOrZero(isMusicCol) != 0
                    val mimeType = cursor.getStringOrNull(mimeCol)?.takeIf { it.isNotBlank() } ?: "audio/*"

                    // Skip zero-byte / non-music tiny system clips (< 1s) unless explicitly marked music
                    val sizeBytes = cursor.getLongOrZero(sizeCol)
                    if (!isMusic && durationMs in 1..1499) {
                        skipped++
                        continue
                    }

                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    val displayName = cursor.getStringOrNull(displayNameCol)
                    val rawTitle = cursor.getStringOrNull(titleCol)
                    val title = cleanTagOrFallback(rawTitle)
                        ?: cleanFileName(displayName)
                        ?: "Untitled Track #$id"

                    val artist = cleanTagOrFallback(cursor.getStringOrNull(artistCol))
                        ?: "Unknown Artist"
                    val album = cleanTagOrFallback(cursor.getStringOrNull(albumCol))
                        ?: "Unknown Album"
                    val albumId = if (albumIdCol >= 0 && !cursor.isNull(albumIdCol)) {
                        cursor.getLong(albumIdCol)
                    } else {
                        null
                    }
                    val albumArtUri = albumId?.let {
                        ContentUris.withAppendedId(
                            Uri.parse("content://media/external/audio/albumart"),
                            it
                        ).toString()
                    }
                    val rawGenre = if (genreCol >= 0) cursor.getStringOrNull(genreCol) else null
                    val genre = cleanTagOrFallback(rawGenre)
                        ?: genreMap[id]
                        ?: "Unknown Genre"

                    val dateAddedSec = cursor.getLongOrZero(dateAddedCol)

                    tracks.add(
                        TrackEntity(
                            trackKey = "ms:$id",
                            mediaStoreId = id,
                            contentUri = contentUri.toString(),
                            sourceType = TrackSourceType.MEDIA_STORE,
                            title = title,
                            artist = artist,
                            album = album,
                            albumId = albumId,
                            albumArtUri = albumArtUri,
                            genre = genre,
                            durationMs = durationMs,
                            dateAddedSec = dateAddedSec,
                            mimeType = mimeType,
                            sizeBytes = sizeBytes,
                            isMissingOrUnreadable = sizeBytes == 0L && durationMs == 0L,
                            lastError = if (sizeBytes == 0L && durationMs == 0L) "Empty or unreadable audio file" else null,
                            lastScannedAtMs = nowMs
                        )
                    )
                }
            }
            MediaScanResult(tracks = tracks, skippedCount = skipped)
        } catch (se: SecurityException) {
            MediaScanResult(
                tracks = emptyList(),
                errorMessage = "Audio permission is required to scan your device library."
            )
        } catch (e: Exception) {
            MediaScanResult(
                tracks = emptyList(),
                errorMessage = "Failed to scan audio library: ${e.localizedMessage ?: "Unknown error"}"
            )
        }
    }

    suspend fun inspectDocumentUri(uri: Uri): Result<TrackEntity> = withContext(Dispatchers.IO) {
        try {
            val nowMs = System.currentTimeMillis()
            var displayName: String? = null
            var sizeBytes = 0L

            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) displayName = cursor.getStringOrNull(nameIdx)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) sizeBytes = cursor.getLong(sizeIdx)
                }
            }

            val mimeType = contentResolver.getType(uri) ?: "audio/*"
            val retriever = MediaMetadataRetriever()
            var title: String? = null
            var artist: String? = null
            var album: String? = null
            var genre: String? = null
            var durationMs = 0L
            var unreadableError: String? = null

            try {
                retriever.setDataSource(context, uri)
                title = cleanTagOrFallback(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
                artist = cleanTagOrFallback(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))
                album = cleanTagOrFallback(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM))
                genre = cleanTagOrFallback(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE))
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            } catch (e: Exception) {
                unreadableError = "Could not decode audio metadata (${e.localizedMessage ?: "unsupported format"})"
            } finally {
                runCatching { retriever.release() }
            }

            val resolvedTitle = title ?: cleanFileName(displayName) ?: "Local Audio File"
            val track = TrackEntity(
                trackKey = "doc:$uri",
                mediaStoreId = null,
                contentUri = uri.toString(),
                sourceType = TrackSourceType.DOCUMENT_URI,
                title = resolvedTitle,
                artist = artist ?: "Unknown Artist",
                album = album ?: "Local Files",
                albumId = null,
                albumArtUri = uri.toString(),
                genre = genre ?: "Unknown Genre",
                durationMs = durationMs,
                dateAddedSec = nowMs / 1000L,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                isMissingOrUnreadable = unreadableError != null,
                lastError = unreadableError,
                lastScannedAtMs = nowMs
            )
            Result.success(track)
        } catch (se: SecurityException) {
            Result.failure(IllegalStateException("Permission denied reading selected file.", se))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun isUriReadable(uriString: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(uriString)
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun loadLegacyGenreMapIfNeeded(): Map<Long, String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return emptyMap()
        val map = mutableMapOf<Long, String>()
        return try {
            val genresUri = MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI
            contentResolver.query(
                genresUri,
                arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME),
                null,
                null,
                null
            )?.use { genreCursor ->
                val gIdCol = genreCursor.getColumnIndex(MediaStore.Audio.Genres._ID)
                val gNameCol = genreCursor.getColumnIndex(MediaStore.Audio.Genres.NAME)
                while (genreCursor.moveToNext()) {
                    val genreId = genreCursor.getLongOrZero(gIdCol)
                    val genreName = cleanTagOrFallback(genreCursor.getStringOrNull(gNameCol)) ?: continue
                    val membersUri = MediaStore.Audio.Genres.Members.getContentUri("external", genreId)
                    contentResolver.query(
                        membersUri,
                        arrayOf(MediaStore.Audio.Genres.Members.AUDIO_ID),
                        null,
                        null,
                        null
                    )?.use { memberCursor ->
                        val aIdCol = memberCursor.getColumnIndex(MediaStore.Audio.Genres.Members.AUDIO_ID)
                        while (memberCursor.moveToNext()) {
                            val audioId = memberCursor.getLongOrZero(aIdCol)
                            if (audioId > 0L) map[audioId] = genreName
                        }
                    }
                }
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun cleanTagOrFallback(raw: String?): String? {
        val trimmed = raw?.trim() ?: return null
        if (trimmed.isEmpty() || trimmed.equals("<unknown>", ignoreCase = true) || trimmed.equals("unknown", ignoreCase = true)) {
            return null
        }
        return trimmed
    }

    private fun cleanFileName(displayName: String?): String? {
        val trimmed = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val dotIndex = trimmed.lastIndexOf('.')
        return if (dotIndex > 0) trimmed.substring(0, dotIndex) else trimmed
    }

    private fun Cursor.getStringOrNull(index: Int): String? =
        if (index >= 0 && !isNull(index)) getString(index) else null

    private fun Cursor.getLongOrZero(index: Int): Long =
        if (index >= 0 && !isNull(index)) getLong(index) else 0L

    private fun Cursor.getIntOrZero(index: Int): Int =
        if (index >= 0 && !isNull(index)) getInt(index) else 0
}
