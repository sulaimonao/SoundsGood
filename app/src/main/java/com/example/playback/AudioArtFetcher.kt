package com.example.playback

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.graphics.drawable.toDrawable
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options

/**
 * Custom Coil Fetcher that extracts embedded cover art from audio content URIs
 * when MediaStore albumart URI is unavailable or for user-picked SAF audio files.
 */
class EmbeddedAudioArtFetcher(
    private val context: Context,
    private val uri: Uri
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val uriStr = uri.toString()
        val isSupported = if (uri.scheme == "file") {
            uri.path != null
        } else {
            uriStr.contains("/document/") ||
                uriStr.contains("/audio/media/") ||
                uriStr.startsWith("content://com.android.providers")
        }
        if (!isSupported) return null

        val retriever = MediaMetadataRetriever()
        return try {
            if (uri.scheme == "file" && uri.path != null) {
                retriever.setDataSource(uri.path)
            } else {
                retriever.setDataSource(context, uri)
            }
            val bytes = retriever.embeddedPicture ?: return null
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            DrawableResult(
                drawable = bitmap.toDrawable(context.resources),
                isSampled = false,
                dataSource = DataSource.DISK
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    class Factory(private val context: Context) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != "content" && data.scheme != "file") return null
            val path = data.toString()
            return if (path.contains("albumart")) {
                null
            } else {
                EmbeddedAudioArtFetcher(context.applicationContext, data)
            }
        }
    }
}
