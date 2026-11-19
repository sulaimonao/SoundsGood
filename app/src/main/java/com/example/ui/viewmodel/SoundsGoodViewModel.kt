package com.example.ui.viewmodel

import android.Manifest
import android.content.Context
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.db.SmartPlaylistRuleEntity
import com.example.data.db.TrackEntity
import com.example.data.db.TrackWithStats
import com.example.data.repository.LibraryRepository
import com.example.data.repository.SyncedPlaylistWithTracks
import com.example.domain.SmartPlaylistEvaluator
import com.example.playback.PlaybackBus
import com.example.playback.PlaybackClient
import com.example.playback.PlaybackUiState
import com.example.sync.LocalSyncManager
import com.example.sync.MusicFolderState
import com.example.sync.TransferFolderSource
import com.example.sync.LocalSyncUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class LibrarySortOption(val label: String) {
    ALPHABETICAL("Alphabetical"),
    DATE_ADDED("Date Added"),
    LISTENING_FREQUENCY("Listening Frequency")
}

enum class LibraryCategoryTab(val label: String) {
    SONGS("Songs"),
    ARTISTS("Artists"),
    ALBUMS("Albums")
}

data class ArtistSummary(
    val name: String,
    val trackCount: Int,
    val albumCount: Int,
    val totalPlays: Int,
    val sampleArtUri: String?
)

data class AlbumSummary(
    val name: String,
    val artist: String,
    val trackCount: Int,
    val totalDurationMs: Long,
    val artUri: String?
)

data class SmartPlaylistItemUi(
    val rule: SmartPlaylistRuleEntity,
    val matchedTracks: List<TrackWithStats>,
    val ruleChips: List<String>
)

data class LibraryUiState(
    val hasAudioPermission: Boolean = false,
    val isScanning: Boolean = false,
    val scanError: String? = null,
    val statusBanner: String? = null,
    val searchQuery: String = "",
    val sortOption: LibrarySortOption = LibrarySortOption.ALPHABETICAL,
    val categoryTab: LibraryCategoryTab = LibraryCategoryTab.SONGS,
    val selectedArtist: String? = null,
    val selectedAlbum: String? = null,
    val allTracks: List<TrackWithStats> = emptyList(),
    val filteredTracks: List<TrackWithStats> = emptyList(),
    val artists: List<ArtistSummary> = emptyList(),
    val albums: List<AlbumSummary> = emptyList(),
    val unreadableCount: Int = 0
)

class SoundsGoodViewModel(
    private val repository: LibraryRepository,
    val playbackClient: PlaybackClient,
    private val localSyncManager: LocalSyncManager? = null
) : ViewModel() {

    private val _hasAudioPermission = MutableStateFlow(false)
    private val _isScanning = MutableStateFlow(false)
    private val _scanError = MutableStateFlow<String?>(null)
    private val _statusBanner = MutableStateFlow<String?>(null)
    private val _searchQuery = MutableStateFlow("")
    private val _sortOption = MutableStateFlow(LibrarySortOption.ALPHABETICAL)
    private val _categoryTab = MutableStateFlow(LibraryCategoryTab.SONGS)
    private val _selectedArtist = MutableStateFlow<String?>(null)
    private val _selectedAlbum = MutableStateFlow<String?>(null)

    private val _selectedPlaylistId = MutableStateFlow<Long?>(null)
    val selectedPlaylistId: StateFlow<Long?> = _selectedPlaylistId.asStateFlow()

    private val _selectedSyncedPlaylistKey = MutableStateFlow<String?>(null)
    val selectedSyncedPlaylistKey: StateFlow<String?> = _selectedSyncedPlaylistKey.asStateFlow()

    private val _navigateToSyncRequested = MutableStateFlow(false)
    val navigateToSyncRequested: StateFlow<Boolean> = _navigateToSyncRequested.asStateFlow()

    val syncUiState: StateFlow<LocalSyncUiState> =
        localSyncManager?.uiState ?: MutableStateFlow(LocalSyncUiState()).asStateFlow()
    val musicFolderState: StateFlow<MusicFolderState> =
        localSyncManager?.musicFolderState ?: MutableStateFlow(MusicFolderState()).asStateFlow()

    val playbackState: StateFlow<PlaybackUiState> = playbackClient.state
    val playbackError: StateFlow<String?> = PlaybackBus.playbackError
    val activePlaylistLabel: StateFlow<String?> = PlaybackBus.activePlaylistLabel

    private data class FilterParams(
        val searchQuery: String,
        val sortOption: LibrarySortOption,
        val categoryTab: LibraryCategoryTab,
        val selectedArtist: String?,
        val selectedAlbum: String?
    )

    private data class ScanMeta(
        val hasPermission: Boolean,
        val isScanning: Boolean,
        val scanError: String?,
        val statusBanner: String?
    )

    private val filterParams = combine(
        _searchQuery,
        _sortOption,
        _categoryTab,
        _selectedArtist,
        _selectedAlbum
    ) { query, sort, tab, artist, album ->
        FilterParams(query, sort, tab, artist, album)
    }

    private val scanMeta = combine(
        _hasAudioPermission,
        _isScanning,
        _scanError,
        _statusBanner
    ) { hasPerm, scanning, err, banner ->
        ScanMeta(hasPerm, scanning, err, banner)
    }

    val libraryUiState: StateFlow<LibraryUiState> = combine(
        repository.allTracksWithStats,
        filterParams,
        scanMeta
    ) { tracks, params, meta ->
        val query = params.searchQuery.trim()
        val searched = if (query.isEmpty()) {
            tracks
        } else {
            tracks.filter { item ->
                item.track.title.contains(query, ignoreCase = true) ||
                    item.track.artist.contains(query, ignoreCase = true) ||
                    item.track.album.contains(query, ignoreCase = true) ||
                    item.track.genre.contains(query, ignoreCase = true) ||
                    (item.track.moodTag?.contains(query, ignoreCase = true) == true)
            }
        }

        val sorted = when (params.sortOption) {
            LibrarySortOption.ALPHABETICAL -> searched.sortedBy { it.track.title.lowercase() }
            LibrarySortOption.DATE_ADDED -> searched.sortedWith(
                compareByDescending<TrackWithStats> { it.track.dateAddedSec }
                    .thenBy { it.track.title.lowercase() }
            )
            LibrarySortOption.LISTENING_FREQUENCY -> searched.sortedWith(
                compareByDescending<TrackWithStats> { it.playCount }
                    .thenByDescending { it.preferenceScore }
                    .thenByDescending { it.totalListenedMs }
                    .thenBy { it.track.title.lowercase() }
            )
        }

        val artists = searched
            .groupBy { it.track.artist }
            .map { (artistName, artistTracks) ->
                ArtistSummary(
                    name = artistName,
                    trackCount = artistTracks.size,
                    albumCount = artistTracks.map { it.track.album }.distinct().size,
                    totalPlays = artistTracks.sumOf { it.playCount },
                    sampleArtUri = artistTracks.firstNotNullOfOrNull { it.track.albumArtUri }
                )
            }
            .let { list ->
                when (params.sortOption) {
                    LibrarySortOption.ALPHABETICAL -> list.sortedBy { it.name.lowercase() }
                    LibrarySortOption.DATE_ADDED -> list.sortedByDescending { a ->
                        searched.filter { it.track.artist == a.name }.maxOfOrNull { it.track.dateAddedSec } ?: 0L
                    }
                    LibrarySortOption.LISTENING_FREQUENCY -> list.sortedWith(
                        compareByDescending<ArtistSummary> { it.totalPlays }
                            .thenBy { it.name.lowercase() }
                    )
                }
            }

        val albums = searched
            .groupBy { it.track.album }
            .map { (albumName, albumTracks) ->
                AlbumSummary(
                    name = albumName,
                    artist = albumTracks.firstOrNull()?.track?.artist ?: "Unknown Artist",
                    trackCount = albumTracks.size,
                    totalDurationMs = albumTracks.sumOf { it.track.durationMs },
                    artUri = albumTracks.firstNotNullOfOrNull { it.track.albumArtUri }
                )
            }
            .let { list ->
                when (params.sortOption) {
                    LibrarySortOption.ALPHABETICAL -> list.sortedBy { it.name.lowercase() }
                    LibrarySortOption.DATE_ADDED -> list.sortedByDescending { al ->
                        searched.filter { it.track.album == al.name }.maxOfOrNull { it.track.dateAddedSec } ?: 0L
                    }
                    LibrarySortOption.LISTENING_FREQUENCY -> list.sortedByDescending { al ->
                        searched.filter { it.track.album == al.name }.sumOf { it.playCount }
                    }
                }
            }

        LibraryUiState(
            hasAudioPermission = meta.hasPermission,
            isScanning = meta.isScanning,
            scanError = meta.scanError,
            statusBanner = meta.statusBanner,
            searchQuery = params.searchQuery,
            sortOption = params.sortOption,
            categoryTab = params.categoryTab,
            selectedArtist = params.selectedArtist,
            selectedAlbum = params.selectedAlbum,
            allTracks = tracks,
            filteredTracks = sorted,
            artists = artists,
            albums = albums,
            unreadableCount = tracks.count { it.track.isMissingOrUnreadable }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LibraryUiState()
    )

    val smartPlaylists: StateFlow<List<SmartPlaylistItemUi>> = combine(
        repository.smartPlaylistRules,
        repository.allTracksWithStats
    ) { rules, tracks ->
        rules.map { rule ->
            SmartPlaylistItemUi(
                rule = rule,
                matchedTracks = SmartPlaylistEvaluator.evaluate(rule, tracks),
                ruleChips = SmartPlaylistEvaluator.summarizeRuleChips(rule)
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val syncedPlaylists: StateFlow<List<SyncedPlaylistWithTracks>> = repository.syncedPlaylists
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        viewModelScope.launch {
            repository.ensureDefaultSmartPlaylists()
        }
    }

    fun syncPermissionState(context: Context, triggerScanIfGranted: Boolean = true) {
        val granted = hasReadAudioPermission(context)
        val wasGranted = _hasAudioPermission.value
        _hasAudioPermission.value = granted
        if (granted && (triggerScanIfGranted || !wasGranted)) {
            rescanLibrary()
        }
    }

    fun rescanLibrary() {
        if (_isScanning.value) return
        viewModelScope.launch {
            _isScanning.value = true
            _scanError.value = null
            val result = repository.rescanMediaStoreLibrary()
            _isScanning.value = false
            if (result.errorMessage != null) {
                _scanError.value = result.errorMessage
            } else {
                _statusBanner.value = "Library synced: ${result.tracks.size} audio track(s) indexed."
            }
        }
    }

    fun importDocumentUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _isScanning.value = true
            val (imported, errors) = repository.importDocumentUris(uris)
            _isScanning.value = false
            if (errors.isNotEmpty() && imported.isEmpty()) {
                _scanError.value = errors.first()
            } else if (errors.isNotEmpty()) {
                _statusBanner.value = "Added ${imported.size} file(s); ${errors.size} unreadable."
            } else if (imported.isNotEmpty()) {
                _statusBanner.value = "Added ${imported.size} local audio file(s)."
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSortOption(option: LibrarySortOption) {
        _sortOption.value = option
    }

    fun setCategoryTab(tab: LibraryCategoryTab) {
        _categoryTab.value = tab
        _selectedArtist.value = null
        _selectedAlbum.value = null
    }

    fun selectArtist(artist: String?) {
        _selectedArtist.value = artist
    }

    fun selectAlbum(album: String?) {
        _selectedAlbum.value = album
    }

    fun clearStatusBanner() {
        _statusBanner.value = null
        _scanError.value = null
    }

    fun clearPlaybackError() {
        PlaybackBus.clearError()
    }

    fun playTracks(
        tracks: List<TrackWithStats>,
        startIndex: Int = 0,
        playlistLabel: String = "Library",
        shuffle: Boolean? = null
    ) {
        val entities = mutableListOf<TrackEntity>()
        var resolvedIndex = 0
        tracks.forEachIndexed { idx, item ->
            if (!item.track.isMissingOrUnreadable) {
                if (idx == startIndex) {
                    resolvedIndex = entities.size
                }
                entities.add(item.track)
            }
        }
        if (entities.isEmpty()) {
            PlaybackBus.postError("No playable audio tracks in this selection.")
            return
        }
        playbackClient.playQueue(
            tracks = entities,
            startIndex = resolvedIndex,
            playlistLabel = playlistLabel,
            shuffle = shuffle
        )
    }

    fun playTrackNext(track: TrackWithStats) {
        if (track.track.isMissingOrUnreadable) {
            PlaybackBus.postError(track.track.lastError ?: "This audio file is unreadable.")
            return
        }
        playbackClient.playNext(track.track)
        _statusBanner.value = "Playing \"${track.track.title}\" next."
    }

    fun addTrackToQueue(track: TrackWithStats) {
        if (track.track.isMissingOrUnreadable) {
            PlaybackBus.postError(track.track.lastError ?: "This audio file is unreadable.")
            return
        }
        playbackClient.addToQueue(track.track)
        _statusBanner.value = "Added \"${track.track.title}\" to queue."
    }

    fun setMoodTag(trackKey: String, moodTag: String?) {
        viewModelScope.launch {
            repository.setMoodTag(trackKey, moodTag)
        }
    }

    fun selectSmartPlaylist(ruleId: Long?) {
        _selectedSyncedPlaylistKey.value = null
        _selectedPlaylistId.value = ruleId
    }

    fun selectSyncedPlaylist(playlistKey: String?) {
        _selectedPlaylistId.value = null
        _selectedSyncedPlaylistKey.value = playlistKey
    }

    fun handleIncomingPairingUri(rawUri: String) {
        _navigateToSyncRequested.value = true
        localSyncManager?.pairAndConnect(rawUri)
    }

    fun consumeNavigateToSyncRequest() {
        _navigateToSyncRequested.value = false
    }

    fun pairWithMacLink(rawUri: String): Boolean {
        return localSyncManager?.pairAndConnect(rawUri) ?: false
    }

    fun openTransferFolder(resolver: ContentResolver, selected: Uri) {
        localSyncManager?.openTransferFolder(TransferFolderSource(resolver, selected))
    }

    fun restoreMusicFolderSelection(context: Context) {
        localSyncManager?.attachMusicFolder(context.applicationContext)
    }

    fun chooseMusicFolder(context: Context, selected: Uri) {
        localSyncManager?.chooseMusicFolder(context.applicationContext, selected)
    }

    fun copyExistingDownloads() {
        localSyncManager?.migrateMusicFolder()
    }

    fun recheckMusicFolder() {
        localSyncManager?.recoverMusicFolder()
    }

    fun cancelMusicFolderWork() {
        localSyncManager?.cancelMusicFolderWork()
    }

    suspend fun exportSyncReport(resolver: ContentResolver, destination: Uri, reportId: String): Boolean =
        localSyncManager?.exportLastReport(resolver, destination, reportId) == true

    fun refreshSyncManifest() {
        localSyncManager?.refreshManifest()
    }

    fun setSyncAllMusic(syncAll: Boolean) {
        localSyncManager?.setSyncAllMusic(syncAll)
    }

    fun toggleSyncPlaylist(playlistId: String) {
        localSyncManager?.togglePlaylistSelection(playlistId)
    }

    fun startLocalSync(continuePastFileErrors: Boolean = true) {
        localSyncManager?.startSync(continuePastFileErrors)
    }

    fun cancelLocalSync() {
        localSyncManager?.cancelActiveSync()
    }

    fun disconnectMacSession() {
        localSyncManager?.disconnectSession()
    }

    fun saveSmartPlaylistRule(rule: SmartPlaylistRuleEntity, onSaved: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = repository.saveSmartPlaylistRule(rule)
            onSaved(id)
        }
    }

    fun deleteSmartPlaylistRule(ruleId: Long) {
        viewModelScope.launch {
            repository.deleteSmartPlaylistRule(ruleId)
            if (_selectedPlaylistId.value == ruleId) {
                _selectedPlaylistId.value = null
            }
        }
    }

    companion object {
        fun requiredAudioPermission(): String {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
        }

        fun hasReadAudioPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                requiredAudioPermission()
            ) == PackageManager.PERMISSION_GRANTED
        }

        fun provideFactory(
            repository: LibraryRepository,
            playbackClient: PlaybackClient,
            localSyncManager: LocalSyncManager? = null
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SoundsGoodViewModel(repository, playbackClient, localSyncManager) as T
            }
        }
    }
}
