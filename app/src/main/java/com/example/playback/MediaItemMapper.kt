package com.example.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.example.data.db.TrackEntity

object MediaItemMapper {

    const val EXTRA_TRACK_KEY = "soundsgood_track_key"
    const val EXTRA_DURATION_MS = "soundsgood_duration_ms"
    const val EXTRA_MIME_TYPE = "soundsgood_mime_type"
    const val EXTRA_MOOD_TAG = "soundsgood_mood_tag"
    const val EXTRA_GENRE = "soundsgood_genre"

    fun toMediaItem(track: TrackEntity): MediaItem {
        val extras = Bundle().apply {
            putString(EXTRA_TRACK_KEY, track.trackKey)
            putLong(EXTRA_DURATION_MS, track.durationMs)
            putString(EXTRA_MIME_TYPE, track.mimeType)
            putString(EXTRA_MOOD_TAG, track.moodTag)
            putString(EXTRA_GENRE, track.genre)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setGenre(track.genre)
            .setArtworkUri(track.albumArtUri?.let { Uri.parse(it) })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setExtras(extras)
            .build()

        return MediaItem.Builder()
            .setMediaId(track.trackKey)
            .setUri(Uri.parse(track.contentUri))
            .setMimeType(track.mimeType.takeIf { it.startsWith("audio/") })
            .setMediaMetadata(metadata)
            .build()
    }

    fun extractTrackKey(mediaItem: MediaItem?): String? {
        if (mediaItem == null) return null
        return mediaItem.mediaMetadata.extras?.getString(EXTRA_TRACK_KEY)
            ?: mediaItem.mediaId.takeIf { it.isNotBlank() }
    }
}
