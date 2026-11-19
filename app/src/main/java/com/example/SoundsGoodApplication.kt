package com.example

import android.app.Application
import androidx.annotation.VisibleForTesting
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.example.data.db.SoundsGoodDatabase
import com.example.data.repository.LibraryRepository
import com.example.data.scanner.MediaStoreScanner
import com.example.playback.EmbeddedAudioArtFetcher
import com.example.playback.PlaybackClient
import com.example.sync.AndroidWifiNetworkSelector
import com.example.sync.LocalSyncManager
import com.example.sync.PinnedTlsSyncTransport
import com.example.sync.SyncFileStorage

class SoundsGoodApplication : Application(), ImageLoaderFactory {

    val database: SoundsGoodDatabase by lazy {
        SoundsGoodDatabase.getInstance(this)
    }

    val mediaStoreScanner: MediaStoreScanner by lazy {
        MediaStoreScanner(this)
    }

    private val defaultLibraryRepository: LibraryRepository by lazy {
        LibraryRepository(
            trackDao = database.trackDao(),
            statsDao = database.statsDao(),
            smartPlaylistDao = database.smartPlaylistDao(),
            playbackSnapshotDao = database.playbackSnapshotDao(),
            syncedPlaylistDao = database.syncedPlaylistDao(),
            scanner = mediaStoreScanner
        )
    }

    @VisibleForTesting
    internal var libraryRepositoryOverride: LibraryRepository? = null

    val libraryRepository: LibraryRepository
        get() = libraryRepositoryOverride ?: defaultLibraryRepository

    private val defaultLocalSyncManager: LocalSyncManager by lazy {
        LocalSyncManager(
            repository = libraryRepository,
            fileStorage = SyncFileStorage(this),
            transport = PinnedTlsSyncTransport(
                networkSelector = AndroidWifiNetworkSelector(this)
            )
        )
    }

    @VisibleForTesting
    internal var localSyncManagerOverride: LocalSyncManager? = null

    val localSyncManager: LocalSyncManager
        get() = localSyncManagerOverride ?: defaultLocalSyncManager

    val playbackClient: PlaybackClient by lazy {
        PlaybackClient(this)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(EmbeddedAudioArtFetcher.Factory(this@SoundsGoodApplication))
            }
            .crossfade(true)
            .build()
    }
}
