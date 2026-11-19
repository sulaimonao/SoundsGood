package com.example.sync

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.example.data.db.SyncedPlaylistEntity
import com.example.data.db.SyncedPlaylistEntryEntity
import com.example.data.db.TrackEntity
import com.example.data.db.TrackSourceType
import com.example.data.repository.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class SyncPhase {
    UNPAIRED,
    CONNECTING,
    READY,
    SYNCING,
    COMPLETED,
    CANCELLED,
    ERROR
}

data class SyncPlaylistSummaryUi(
    val id: String,
    val name: String,
    val trackCount: Int,
    val selected: Boolean
)

data class SyncTransferProgress(
    val completedTracks: Int,
    val totalTracksToDownload: Int,
    val currentTrackTitle: String,
    val currentTrackBytesRead: Long,
    val currentTrackTotalBytes: Long,
    val overallBytesDownloaded: Long,
    val overallBytesToDownload: Long,
    val attemptedTracks: Int = 0,
    val failedTracks: Int = 0,
    val totalSelectedTracks: Int = 0
) {
    val overallFraction: Float
        get() = if (totalSelectedTracks > 0) {
            (attemptedTracks.toFloat() / totalSelectedTracks.toFloat()).coerceIn(0f, 1f)
        } else if (overallBytesToDownload > 0L) {
            (overallBytesDownloaded.toFloat() / overallBytesToDownload.toFloat()).coerceIn(0f, 1f)
        } else if (totalTracksToDownload > 0) {
            (completedTracks.toFloat() / totalTracksToDownload.toFloat()).coerceIn(0f, 1f)
        } else {
            1f
        }
}

data class SyncOutcome(
    val libraryName: String,
    val syncedTracksCount: Int,
    val downloadedTracksCount: Int,
    val reusedOrUnchangedCount: Int,
    val syncedPlaylistsCount: Int,
    val completedAtMs: Long,
    val status: String = "complete",
    val failedTracksCount: Int = 0,
    val notAttemptedTracksCount: Int = 0,
    val preservedPlaylistsCount: Int = 0,
    val deferredPlaylistsCount: Int = 0,
    val stopReason: String = "none",
    val reportDelivery: SyncReportDelivery? = null,
    val localReportSaved: Boolean = false,
    val failures: List<SyncTrackResult> = emptyList(),
    val reportId: String? = null
)

data class LocalSyncUiState(
    val phase: SyncPhase = SyncPhase.UNPAIRED,
    val pairedAddressDisplay: String? = null,
    val libraryId: String? = null,
    val libraryName: String? = null,
    val revision: String? = null,
    val syncAllMusic: Boolean = true,
    val playlists: List<SyncPlaylistSummaryUi> = emptyList(),
    val preview: SyncPreviewPlan? = null,
    val progress: SyncTransferProgress? = null,
    val lastOutcome: SyncOutcome? = null,
    val errorMessage: String? = null,
    val requiresRePair: Boolean = false,
    val isFolderImport: Boolean = false
) {
    val isPaired: Boolean
        get() = pairedAddressDisplay != null

    val isConnectingOrSyncing: Boolean
        get() = phase == SyncPhase.CONNECTING || phase == SyncPhase.SYNCING

    val isTransferActive: Boolean
        get() = phase == SyncPhase.SYNCING
}

data class MusicFolderState(
    val selected: Boolean = false,
    val displayName: String? = null,
    val busy: Boolean = false,
    val processed: Int = 0,
    val skipped: Int = 0,
    val message: String? = null
)

class LocalSyncManager(
    private val repository: LibraryRepository,
    private val fileStorage: SyncFileStorage,
    private val transport: SyncTransport,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) {
    private companion object { const val FOLDER_PREFS = "shared_music_folder" }
    private val _musicFolderState = MutableStateFlow(MusicFolderState())
    val musicFolderState: StateFlow<MusicFolderState> = _musicFolderState.asStateFlow()
    @Volatile private var folderContext: Context? = null
    @Volatile private var selectedTree: Uri? = null
    private var musicFolderJob: Job? = null

    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)

    private val _uiState = MutableStateFlow(LocalSyncUiState())
    val uiState: StateFlow<LocalSyncUiState> = _uiState.asStateFlow()

    @Volatile
    private var activeCredentials: PairingCredentials? = null

    @Volatile
    private var activeManifest: SyncManifest? = null
    private var activeFolder: VerifiedTransferFolder? = null

    @Volatile
    private var operationGeneration: Long = 0L
    private var activeJob: Job? = null
    private val operationMutex = Mutex()
    private var previewJob: Job? = null
    private var selectionGeneration: Long = 0L
    @Volatile
    private var cancellationMessage: String? = null

    init {
        fileStorage.applicationContext?.let(::attachMusicFolder)
        scope.launch(ioDispatcher) {
            operationMutex.withLock {
                fileStorage.cleanIncompleteStagingFiles()
                fileStorage.readLastReport()?.let { report ->
                    _uiState.update { state ->
                        if (state.phase == SyncPhase.UNPAIRED && state.lastOutcome == null) {
                            state.copy(lastOutcome = outcomeFor(report, "Previous local sync", true, null))
                        } else state
                    }
                }
            }
        }
    }

    /** Called during manager construction when the production storage is created with an app context. */
    fun attachMusicFolder(context: Context) {
        if (folderContext != null) return
        folderContext = context.applicationContext
        val prefs = context.getSharedPreferences(FOLDER_PREFS, Context.MODE_PRIVATE)
        selectedTree = prefs.getString("tree", null)?.let { Uri.parse(it) }
        _musicFolderState.value = MusicFolderState(selected = selectedTree != null,
            displayName = prefs.getString("name", null))
    }

    fun chooseMusicFolder(context: Context, tree: Uri) {
        attachMusicFolder(context)
        musicFolderWork {
            val app = requireNotNull(folderContext)
            val destination = DurableMusicFolder.open(app.contentResolver, tree, takeGrant = true)
            currentCoroutineContext().ensureActive()
            // Keep earlier grants: playback rows may still refer to the previously chosen folder.
            if (!app.getSharedPreferences(FOLDER_PREFS, Context.MODE_PRIVATE).edit()
                .putString("tree", destination.tree.toString()).putString("name", destination.displayName).commit()) {
                throw SyncStorageException("Could not remember the selected music folder.")
            }
            selectedTree = destination.tree
            _musicFolderState.update { it.copy(selected = true, displayName = destination.displayName) }
            recoverFolder(destination)
        }
    }

    fun recoverMusicFolder() = musicFolderWork { recoverFolder(requireNotNull(openMusicFolder())) }

    fun migrateMusicFolder() = musicFolderWork {
        val destination = requireNotNull(openMusicFolder())
        val libraries = repository.getAllSyncedTracks().groupBy { it.syncLibraryId }
        var playlistCount = 0
        for ((id, tracks) in libraries) {
            currentCoroutineContext().ensureActive()
            if (id == null || !SyncManifestValidator.ID_REGEX.matches(id)) {
                _musicFolderState.update { it.copy(skipped = it.skipped + tracks.size) }
                continue
            }
            val lists = repository.getSyncedPlaylistsForLibrary(id)
            val name = lists.firstOrNull()?.libraryName ?: id
            val revision = lists.maxByOrNull { it.syncedAtMs }?.revision ?: "local_migration"
            val copied = HashMap<String, String>()
            for (old in tracks) {
                currentCoroutineContext().ensureActive()
                val track = migrationTrack(old)
                if (track == null || old.trackKey != SyncManifestValidator.stableTrackKey(id, track.id)) {
                    _musicFolderState.update { it.copy(skipped = it.skipped + 1) }
                    continue
                }
                val shared = destination.reuseVerified(id, name, revision, track)
                val privateCopy = fileStorage.ownedPrivateAudio(old.localFilePath)
                var temporary: File? = null
                try {
                    val source = when {
                        shared != null -> null
                        privateCopy != null && fileStorage.verifyExistingFile(privateCopy, track.sizeBytes, track.sha256) -> privateCopy
                        else -> {
                            val uri = Uri.parse(old.contentUri)
                            if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority != "com.android.externalstorage.documents") null
                            else {
                                if (fileStorage.availableStorageBytes() < track.sizeBytes + 4L * 1024 * 1024) {
                                    throw SyncStorageException("Not enough space for a verified migration copy.", reason = "storage_full")
                                }
                                val part = fileStorage.newStagingPartFile()
                                temporary = part
                                if (copyReadableSharedTrack(uri, track, part)) part else null
                            }
                        }
                    }
                    if (source == null && shared == null) {
                        _musicFolderState.update { it.copy(skipped = it.skipped + 1) }
                        continue
                    }
                    val uri = shared ?: destination.store(id, name, revision, track, requireNotNull(source))
                    currentCoroutineContext().ensureActive()
                    withContext(NonCancellable) {
                        repository.commitSyncedLibraryBatch(listOf(old.copy(contentUri = uri.toString(),
                            albumArtUri = uri.toString(), localFilePath = null, isMissingOrUnreadable = false,
                            lastError = null)), emptyList(), emptyMap())
                        copied[old.trackKey] = track.id
                        _musicFolderState.update { it.copy(processed = it.processed + 1) }
                    }
                } finally {
                    temporary?.let(fileStorage::deletePartFileQuietly)
                }
            }
            for (list in lists) {
                currentCoroutineContext().ensureActive()
                val rows = repository.getSyncedPlaylistEntries(list.playlistKey)
                val ids = rows.mapNotNull { copied[it.trackKey] }
                if (ids.size == rows.size) {
                    destination.storePlaylist(id, list.libraryName, list.revision, SyncPlaylistDto(list.remotePlaylistId, list.name, ids))
                    playlistCount++
                }
            }
        }
        "Migration finished. Verified tracks now play from this folder. $playlistCount playlists saved. Earlier files were kept."
    }

    private fun migrationTrack(old: TrackEntity): SyncTrackDto? {
        val id = old.syncTrackId ?: return null
        val hash = old.contentSha256 ?: return null
        val filename = old.localFilePath ?: Uri.parse(old.contentUri).lastPathSegment ?: ""
        val ext = filename.substringAfterLast('.', "").lowercase()
        val mime = SyncManifestValidator.ALLOWED_EXTENSION_MIME_PAIRS[ext] ?: return null
        if (mime != old.mimeType || !SyncManifestValidator.ID_REGEX.matches(id) ||
            !SyncManifestValidator.SHA256_REGEX.matches(hash) || old.sizeBytes !in 0L..SyncManifestValidator.MAX_AUDIO_FILE_BYTES) return null
        return SyncTrackDto(id, old.title, old.artist, old.album, old.genre, old.durationMs, old.sizeBytes, hash, mime, ext)
    }

    private suspend fun copyReadableSharedTrack(uri: Uri, track: SyncTrackDto, out: File): Boolean {
        val context = currentCoroutineContext()
        val resolver = requireNotNull(folderContext).contentResolver
        val stream = try { resolver.openInputStream(uri) } catch (_: java.io.FileNotFoundException) { return false }
            catch (_: SecurityException) { return false }
        if (stream == null) return false
        return try {
            stream.use { input ->
                out.outputStream().use { output ->
                    TransferFolderBytes.copyVerified(input, output, track.sizeBytes, track.sha256,
                        { context.ensureActive() }, { })
                }
            }
            true
        } catch (e: TransferFolderBytes.CopyFailure) {
            if (e.kind == TransferFolderBytes.Failure.DESTINATION_WRITE) throw SyncStorageException("Could not stage a shared track.", e)
            false
        }
    }

    private suspend fun recoverFolder(destination: DurableMusicFolder): String {
        val existing = repository.getAllSyncedTracks().associateBy { it.trackKey }
        val available = HashSet<String>()
        val priorPlaylists = HashMap<String, Map<String, SyncedPlaylistEntity>>()
        var playlists = 0
        var protectedTracks = 0
        var protectedPlaylists = 0
        val result = destination.recover(
            onTrack = { libraryId, _, _, track, uri ->
                val key = SyncManifestValidator.stableTrackKey(libraryId, track.id)
                val old = existing[key]
                if (old != null && old.contentSha256 != track.sha256) {
                    // A folder chosen on an existing installation may carry a different revision.
                    protectedTracks++
                } else {
                    val row = trackEntity(libraryId, track, uri, null, old)
                    withContext(NonCancellable) {
                        repository.commitSyncedLibraryBatch(listOf(row), emptyList(), emptyMap())
                        available += key
                        _musicFolderState.update { it.copy(processed = it.processed + 1) }
                    }
                }
            },
            onPlaylist = { libraryId, name, revision, list ->
                val key = SyncManifestValidator.stablePlaylistKey(libraryId, list.id)
                val entries = list.trackIds.mapIndexed { position, id ->
                    SyncedPlaylistEntryEntity(key, position, SyncManifestValidator.stableTrackKey(libraryId, id))
                }
                val prior = priorPlaylists.getOrPut(libraryId) {
                    repository.getSyncedPlaylistsForLibrary(libraryId).associateBy { it.playlistKey }
                }[key]
                val wouldReplaceDifferentRevision = prior != null && prior.revision != revision &&
                    (prior.name != list.name || repository.getSyncedPlaylistEntries(key).map { it.trackKey } != entries.map { it.trackKey })
                if (!wouldReplaceDifferentRevision && entries.all { it.trackKey in available }) {
                    val entity = SyncedPlaylistEntity(key, libraryId, name, list.id, list.name, revision, System.currentTimeMillis())
                    repository.commitSyncedLibraryBatch(emptyList(), listOf(entity), mapOf(key to entries))
                    playlists++
                } else protectedPlaylists++
            }
        )
        _musicFolderState.update { it.copy(skipped = result.skipped + protectedTracks) }
        return "Folder ready. Recovered $playlists playlists. Skipped ${result.playlistsSkipped + protectedPlaylists} incomplete or conflicting playlists."
    }

    fun cancelMusicFolderWork() {
        musicFolderJob?.cancel()
    }

    private fun musicFolderWork(block: suspend () -> String) {
        val initial = _musicFolderState.value
        if (_uiState.value.isConnectingOrSyncing || initial.busy ||
            !_musicFolderState.compareAndSet(initial, initial.copy(busy = true, processed = 0, skipped = 0, message = null))) return
        previewJob?.cancel()
        selectionGeneration++
        musicFolderJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val message = try {
                operationMutex.withLock { withContext(ioDispatcher) { block() } }
            } catch (cancelled: CancellationException) {
                "Folder operation cancelled. Completed tracks were kept."
            } catch (error: Exception) {
                "Folder operation stopped. Check free space and select the local music folder again. Completed tracks were kept."
            }
            _musicFolderState.update { it.copy(busy = false, message = message) }
            activeManifest?.let { recomputePreviewAsync(it) }
        }
    }

    private suspend fun <T> folderStorage(operation: suspend () -> T): T {
        return try { operation() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val reason = (error as? SyncStorageException)?.reason ?: "local_storage_error"
            val message = if (reason == "storage_full") "The selected music folder is full."
                else "Check access and free space in the selected music folder, or choose it again."
            _musicFolderState.update { it.copy(message = message) }
            throw SyncStorageException(message, error, reason)
        }
    }

    private suspend fun openMusicFolder(): DurableMusicFolder? {
        val tree = selectedTree ?: return null
        return folderStorage { DurableMusicFolder.open(requireNotNull(folderContext).contentResolver, tree) }
    }

    private suspend fun buildPreview(manifest: SyncManifest, all: Boolean, selected: Set<String>,
                                     existing: List<TrackEntity>): SyncPreviewPlan =
        buildPreview(manifest, all, selected, existing, openMusicFolder())

    private suspend fun buildPreview(manifest: SyncManifest, all: Boolean, selected: Set<String>,
                                     existing: List<TrackEntity>, destination: DurableMusicFolder?): SyncPreviewPlan {
        if (destination == null) return SyncPlanner.buildPreview(manifest, all, selected, existing, fileStorage)
        return folderStorage {
            val shared = destination
            val ids = if (all) null else manifest.playlists.filter { it.id in selected }.flatMap { it.trackIds }.toSet()
            val found = HashSet<String>()
            for (track in manifest.tracks) {
                currentCoroutineContext().ensureActive()
                if ((ids == null || track.id in ids) && shared.containsCandidate(track)) found += track.sha256 + "." + track.extension
            }
            SyncPlanner.buildPreview(manifest, all, selected, existing, fileStorage,
                folderCandidates = found, folderCapacity = shared.availableBytes())
        }
    }

    fun pairAndConnect(rawUriInput: String): Boolean {
        if (_musicFolderState.value.busy) return false
        if (_uiState.value.isFolderImport && _uiState.value.isConnectingOrSyncing) return false
        return when (val parsed = PairingUriParser.parse(rawUriInput)) {
            is PairingParseResult.Invalid -> {
                _uiState.update {
                    it.copy(
                        phase = if (activeManifest != null) SyncPhase.READY else SyncPhase.ERROR,
                        errorMessage = parsed.reason,
                        requiresRePair = false
                    )
                }
                false
            }
            is PairingParseResult.Valid -> {
                cancelActiveWorkInternal(clearCredentials = false)
                activeFolder = null
                val creds = parsed.credentials
                activeCredentials = creds
                activeManifest = null
                _uiState.value = LocalSyncUiState(
                    phase = SyncPhase.CONNECTING,
                    pairedAddressDisplay = creds.displayAddress
                )
                launchManifestFetch(creds)
                true
            }
        }
    }

    /** Picking a different source is only allowed while this phone is idle. */
    fun openTransferFolder(source: TransferFolderSource) = prepareTransferFolder { source.open() }

    internal fun prepareTransferFolder(open: suspend () -> VerifiedTransferFolder) {
        if (_uiState.value.isConnectingOrSyncing || _musicFolderState.value.busy) return
        cancelActiveWorkInternal(clearCredentials = true)
        activeFolder = null
        activeManifest = null
        val generation = operationGeneration
        _uiState.value = LocalSyncUiState(phase = SyncPhase.CONNECTING, isFolderImport = true)
        activeJob = scope.launch {
            operationMutex.withLock {
                if (generation != operationGeneration) return@withLock
                try {
                    val (folder, preview) = withContext(ioDispatcher) {
                        val candidate = open()
                        val manifest = candidate.manifest
                        val existing = repository.getSyncedTracksForLibrary(manifest.libraryId)
                        candidate to buildPreview(manifest, true, emptySet(), existing)
                    }
                    ensureActive()
                    if (generation != operationGeneration) return@withLock
                    val manifest = folder.manifest
                    activeFolder = folder
                    activeManifest = manifest
                    _uiState.value = LocalSyncUiState(
                        phase = SyncPhase.READY, isFolderImport = true,
                        libraryId = manifest.libraryId, libraryName = manifest.libraryName, revision = manifest.revision,
                        playlists = manifest.playlists.map { SyncPlaylistSummaryUi(it.id, it.name, it.trackIds.size, false) },
                        preview = preview
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (generation != operationGeneration) return@withLock
                    val message = if (error is SyncProtocolException || error is TransferFolderUnavailable) {
                        error.message ?: "The transfer folder cannot be opened."
                    } else "The transfer folder cannot be opened. Check the drive and choose the exported folder again."
                    _uiState.update { it.copy(phase = SyncPhase.ERROR, errorMessage = message) }
                }
            }
        }
    }

    fun refreshManifest() {
        val creds = activeCredentials ?: return
        if (_uiState.value.isTransferActive || _musicFolderState.value.busy) return
        cancelActiveWorkInternal(clearCredentials = false)
        _uiState.update {
            it.copy(
                phase = SyncPhase.CONNECTING,
                errorMessage = null,
                requiresRePair = false
            )
        }
        launchManifestFetch(creds)
    }

    private fun launchManifestFetch(creds: PairingCredentials) {
        val myGeneration = ++operationGeneration
        activeJob = scope.launch {
            operationMutex.withLock {
            if (myGeneration != operationGeneration) return@withLock
            try {
                val manifest = withContext(ioDispatcher) {
                    transport.fetchManifest(creds)
                }
                ensureActive()
                if (myGeneration != operationGeneration) return@launch

                activeManifest = manifest
                val existingIds = _uiState.value.playlists
                    .filter { it.selected }
                    .map { it.id }
                    .toSet()
                val validSelectedIds = manifest.playlists
                    .map { it.id }
                    .filter { it in existingIds }
                    .toSet()

                val syncAll = _uiState.value.syncAllMusic || manifest.playlists.isEmpty()
                val preview = withContext(ioDispatcher) {
                    val existingTracks = repository.getSyncedTracksForLibrary(manifest.libraryId)
                    buildPreview(manifest, syncAll, validSelectedIds, existingTracks)
                }
                if (myGeneration != operationGeneration) return@launch

                _uiState.value = LocalSyncUiState(
                    phase = SyncPhase.READY,
                    pairedAddressDisplay = creds.displayAddress,
                    libraryId = manifest.libraryId,
                    libraryName = manifest.libraryName,
                    revision = manifest.revision,
                    syncAllMusic = syncAll,
                    playlists = manifest.playlists.map { pl ->
                        SyncPlaylistSummaryUi(
                            id = pl.id,
                            name = pl.name,
                            trackCount = pl.trackIds.size,
                            selected = pl.id in validSelectedIds
                        )
                    },
                    preview = preview
                )
            } catch (ce: CancellationException) {
                if (myGeneration == operationGeneration) {
                    _uiState.update {
                        it.copy(
                            phase = if (activeManifest != null) SyncPhase.READY else SyncPhase.CANCELLED,
                            errorMessage = "Connection cancelled."
                        )
                    }
                }
            } catch (auth: SyncAuthExpiredException) {
                if (myGeneration == operationGeneration) {
                    _uiState.update {
                        it.copy(
                            phase = SyncPhase.ERROR,
                            errorMessage = PairingUriParser.redactSecrets(auth.message, creds.token),
                            requiresRePair = true
                        )
                    }
                }
            } catch (e: Exception) {
                if (myGeneration == operationGeneration) {
                    _uiState.update {
                        it.copy(
                            phase = SyncPhase.ERROR,
                            errorMessage = PairingUriParser.redactSecrets(e.message, creds.token),
                            requiresRePair = false
                        )
                    }
                }
            }
            }
        }
    }

    fun setSyncAllMusic(syncAll: Boolean) {
        if (_uiState.value.isConnectingOrSyncing || _musicFolderState.value.busy) return
        val manifest = activeManifest ?: return
        _uiState.update { state ->
            state.copy(syncAllMusic = syncAll, errorMessage = null)
        }
        recomputePreviewAsync(manifest)
    }

    fun togglePlaylistSelection(playlistId: String) {
        if (_uiState.value.isConnectingOrSyncing || _musicFolderState.value.busy) return
        val manifest = activeManifest ?: return
        _uiState.update { state ->
            val updated = state.playlists.map { item ->
                if (item.id == playlistId) item.copy(selected = !item.selected) else item
            }
            val anySelected = updated.any { it.selected }
            state.copy(
                syncAllMusic = if (anySelected) false else state.syncAllMusic,
                playlists = updated,
                errorMessage = null
            )
        }
        recomputePreviewAsync(manifest)
    }

    private fun recomputePreviewAsync(manifest: SyncManifest) {
        val generationAtStart = operationGeneration
        val selectionAtStart = ++selectionGeneration
        val currentState = _uiState.value
        val selectedIds = currentState.playlists.filter { it.selected }.map { it.id }.toSet()
        previewJob?.cancel()
        previewJob = scope.launch {
            try {
                val preview = withContext(ioDispatcher) {
                    val existingTracks = repository.getSyncedTracksForLibrary(manifest.libraryId)
                    buildPreview(manifest, currentState.syncAllMusic, selectedIds, existingTracks)
                }
                if (generationAtStart != operationGeneration || selectionAtStart != selectionGeneration ||
                    activeManifest !== manifest || _uiState.value.isConnectingOrSyncing) return@launch
                _uiState.update { it.copy(preview = preview, phase = SyncPhase.READY) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generationAtStart == operationGeneration && selectionAtStart == selectionGeneration) {
                    _uiState.update { it.copy(errorMessage = "Could not check the local sync selection. Try refreshing.") }
                }
            }
        }
    }

    fun startSync(continuePastFileErrors: Boolean = true) {
        val folder = activeFolder
        val creds = activeCredentials
        if ((creds == null) == (folder == null)) return
        val manifest = activeManifest ?: return
        val currentState = _uiState.value
        if (currentState.isConnectingOrSyncing || _musicFolderState.value.busy) return
        val selectedIds = currentState.playlists.filter { it.selected }.map { it.id }.toSet()
        if (!currentState.syncAllMusic && selectedIds.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Select at least one playlist or enable All Available Music.") }
            return
        }
        previewJob?.cancel()
        selectionGeneration++
        cancellationMessage = null
        val myGeneration = ++operationGeneration
        _uiState.update {
            it.copy(phase = SyncPhase.SYNCING, errorMessage = null, requiresRePair = false,
                lastOutcome = null, progress = SyncTransferProgress(0, 0, "Preparing transfer...", 0, 0, 0, 0))
        }
        activeJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            operationMutex.withLock {
                if (myGeneration != operationGeneration) return@withLock
                runSelection(creds, folder, manifest, currentState.syncAllMusic, selectedIds,
                    continuePastFileErrors, myGeneration)
            }
        }
    }

    private suspend fun runSelection(
        creds: PairingCredentials?,
        folder: VerifiedTransferFolder?,
        manifest: SyncManifest,
        syncAll: Boolean,
        selectedIds: Set<String>,
        continuePastFileErrors: Boolean,
        generation: Long
    ) {
        val selectedPlaylists = if (syncAll) manifest.playlists else manifest.playlists.filter { it.id in selectedIds }
        val selectedTrackIds = selectedPlaylists.flatMap { it.trackIds }.toSet()
        val selectedTracks = if (syncAll) manifest.tracks else manifest.tracks.filter { it.id in selectedTrackIds }
        val results = linkedMapOf<String, SyncTrackResult>()
        val playlistResults = linkedMapOf<String, SyncPlaylistResult>()
        var existingPlaylistIds = emptySet<String>()
        var stopReason = "none"
        var status = "complete"
        var terminalTrackReason: String? = null
        var displayError: String? = null
        var reportDelivery: SyncReportDelivery? = null
        var observedTotal = 0L
        var committedCount = 0
        var fileFailureCount = 0
        var consecutiveConnectionFailures = 0

        suspend fun checkCurrent() {
            currentCoroutineContext().ensureActive()
            if (generation != operationGeneration) throw SyncCancelledException()
        }
        fun updateProgress(track: SyncTrackDto, observed: Long, totalBytes: Long, totalDownloads: Int) {
            if (generation != operationGeneration) return
            _uiState.update {
                it.copy(progress = SyncTransferProgress(
                    completedTracks = committedCount,
                    totalTracksToDownload = totalDownloads,
                    currentTrackTitle = track.title,
                    currentTrackBytesRead = observed,
                    currentTrackTotalBytes = track.sizeBytes,
                    overallBytesDownloaded = observedTotal + observed,
                    overallBytesToDownload = totalBytes,
                    attemptedTracks = results.size,
                    failedTracks = fileFailureCount,
                    totalSelectedTracks = selectedTracks.size
                ))
            }
        }
        try {
            withContext(ioDispatcher) {
            checkCurrent()
            fileStorage.ensureDirectories()
            existingPlaylistIds = repository.getSyncedPlaylistsForLibrary(manifest.libraryId)
                .map { it.remotePlaylistId }.toSet()
            folder?.checkSnapshot()
            val existingTracks = repository.getSyncedTracksForLibrary(manifest.libraryId)
            val existingById = existingTracks.associateBy { it.syncTrackId }
            val destination = openMusicFolder()
            val plan = buildPreview(manifest, syncAll, selectedIds, existingTracks, destination)
            checkCurrent()
            if (generation == operationGeneration) _uiState.update { it.copy(preview = plan) }
            if (selectedTracks.isEmpty() && selectedPlaylists.isEmpty()) {
                throw SyncProtocolException("Nothing selected to sync.")
            }
            if (!plan.hasEnoughStorage) {
                throw SyncStorageException("Not enough free storage for the selected sync.", reason = "storage_full")
            }
            for (track in selectedTracks) {
                checkCurrent()
                var receivedBytes = 0L
                var verified = false
                var importing = false
                var downloaded = false
                var partFile: File? = null
                updateProgress(track, 0, plan.requiredDownloadBytes, plan.tracksToDownload.size)
                try {
                    val previous = existingById[track.id]
                    val targetFile = fileStorage.targetFileFor(track.sha256, track.extension)
                    // Reuse only an app-owned file whose actual bytes still match this manifest.
                    val shared = destination?.let { chosen ->
                        folderStorage { chosen.reuseVerified(manifest.libraryId, manifest.libraryName, manifest.revision, track) }
                    }
                    val previousFile = fileStorage.ownedPrivateAudio(previous?.localFilePath)
                    var localFile: File? = when {
                        shared != null -> null
                        previousFile != null && previous?.contentSha256 == track.sha256 &&
                            fileStorage.verifyExistingFile(previousFile, track.sizeBytes, track.sha256) -> previousFile
                        previousFile != targetFile && fileStorage.verifyExistingFile(targetFile, track.sizeBytes, track.sha256) -> targetFile
                        else -> null
                    }
                    if (shared == null && localFile == null) {
                        if (fileStorage.availableStorageBytes() < track.sizeBytes + 4L * 1024L * 1024L) {
                            throw SyncStorageException("Not enough free storage for the next file.", reason = "storage_full")
                        }
                        val staging = fileStorage.newStagingPartFile()
                        partFile = staging
                        var lastUpdate = 0L
                        val onProgress: (Long) -> Unit = { bytes ->
                            receivedBytes = bytes
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate >= 80L || bytes == track.sizeBytes) {
                                lastUpdate = now
                                updateProgress(track, bytes, plan.requiredDownloadBytes, plan.tracksToDownload.size)
                            }
                        }
                        checkCurrent()
                        // Only the selected source can supply a track. Folder imports never open a network connection.
                        if (folder != null) folder.copyTrack(track, staging, onProgress)
                        else transport.downloadTrackAudio(requireNotNull(creds), manifest.revision, track, staging, onProgress)
                        checkCurrent()
                        // Production transport verifies too; keep this gate at the storage/DB boundary.
                        if (!fileStorage.verifyExistingFile(staging, track.sizeBytes, track.sha256)) {
                            throw SyncTrackTransferException("verification_failed", receivedBytes,
                                message = "The received file did not pass final verification.")
                        }
                        receivedBytes = track.sizeBytes
                        verified = true
                        if (destination == null) {
                            if (!fileStorage.publishVerifiedPart(staging, targetFile, track.sizeBytes, track.sha256)) {
                                throw SyncStorageException("Could not publish a verified local file.")
                            }
                            localFile = targetFile
                        } else {
                            // The private part is temporary. Only the selected tree receives the permanent copy.
                            localFile = staging
                        }
                        downloaded = true
                    } else {
                        verified = true
                    }
                    checkCurrent()
                    val uri = when {
                        shared != null -> shared
                        destination != null -> folderStorage {
                            destination.store(manifest.libraryId, manifest.libraryName, manifest.revision, track, requireNotNull(localFile))
                        }
                        else -> Uri.fromFile(requireNotNull(localFile))
                    }
                    checkCurrent()
                    val privatePath = if (destination == null) requireNotNull(localFile).absolutePath else null
                    val entity = trackEntity(manifest.libraryId, track, uri, privatePath, previous)
                    importing = true
                    // Finish this small transaction and record its result together. A cancellation
                    // racing a committed Room write must not be reported as a failed import.
                    withContext(NonCancellable) {
                        repository.commitSyncedLibraryBatch(listOf(entity), emptyList(), emptyMap())
                        results[track.id] = SyncTrackResult(track.id,
                            if (downloaded) "downloaded_verified_imported" else "reused_verified_imported",
                            if (downloaded) receivedBytes else 0L, "verified", "none")
                        committedCount++
                    }
                    consecutiveConnectionFailures = 0
                } catch (error: Exception) {
                    if (track.id !in results) {
                        val reason = reasonFor(error, importing)
                        val transfer = error as? SyncTrackTransferException
                        results[track.id] = SyncTrackResult(track.id, "failed",
                            transfer?.receivedBytes ?: receivedBytes,
                            transfer?.hashStatus ?: if (verified) "verified" else "not_checked", reason)
                        fileFailureCount++
                    }
                    if (error !is SyncTrackTransferException) {
                        terminalTrackReason = if (results[track.id]?.outcome == "failed") {
                            results[track.id]?.reason
                        } else reasonFor(error)
                        throw error
                    }
                    receivedBytes = error.receivedBytes
                    if (error.reason == "connection_failed" || error.reason == "timeout") {
                        consecutiveConnectionFailures++
                    }
                    if (!continuePastFileErrors || consecutiveConnectionFailures >= 2) {
                        terminalTrackReason = error.reason
                        throw error
                    }
                } finally {
                    partFile?.let { fileStorage.deletePartFileQuietly(it) }
                    observedTotal += receivedBytes
                    updateProgress(track, 0, plan.requiredDownloadBytes, plan.tracksToDownload.size)
                }
            }
            checkCurrent()
            folder?.checkSnapshot()
            val verifiedIds = results.values.filter { it.reason == "none" }.map { it.trackId }.toSet()
            val completePlaylists = selectedPlaylists.filter { playlist -> playlist.trackIds.all { it in verifiedIds } }
            val now = System.currentTimeMillis()
            val entities = completePlaylists.map { playlist ->
                SyncedPlaylistEntity(
                    SyncManifestValidator.stablePlaylistKey(manifest.libraryId, playlist.id),
                    manifest.libraryId, manifest.libraryName, playlist.id, playlist.name, manifest.revision, now)
            }
            val entries = completePlaylists.associate { playlist ->
                val key = SyncManifestValidator.stablePlaylistKey(manifest.libraryId, playlist.id)
                key to playlist.trackIds.mapIndexed { index, id ->
                    SyncedPlaylistEntryEntity(key, index, SyncManifestValidator.stableTrackKey(manifest.libraryId, id))
                }
            }
            checkCurrent()
            if (destination != null) {
                for ((index, playlist) in completePlaylists.withIndex()) {
                    checkCurrent()
                    val entity = entities[index]
                    folderStorage { destination.storePlaylist(manifest.libraryId, manifest.libraryName, manifest.revision, playlist) }
                    withContext(NonCancellable) {
                        repository.commitSyncedLibraryBatch(emptyList(), listOf(entity),
                            mapOf(entity.playlistKey to entries.getValue(entity.playlistKey)))
                        playlistResults[playlist.id] = SyncPlaylistResult(playlist.id, "updated")
                    }
                }
            } else if (completePlaylists.isNotEmpty()) {
                withContext(NonCancellable) {
                    repository.commitSyncedLibraryBatch(emptyList(), entities, entries)
                    completePlaylists.forEach { playlistResults[it.id] = SyncPlaylistResult(it.id, "updated") }
                }
            }
            if (results.values.any { it.outcome == "failed" } || completePlaylists.size != selectedPlaylists.size) {
                status = "partial"
            }
            }
        } catch (error: Exception) {
            stopReason = terminalTrackReason ?: reasonFor(error)
            status = if (stopReason == "cancelled") "cancelled" else "stopped"
            displayError = if (stopReason == "cancelled") {
                cancellationMessage ?: "Sync cancelled. Verified imports are available in your library."
            } else {
                if (folder != null && (error is TransferFolderUnavailable || error is SyncRevisionConflictException)) {
                    error.message
                } else safeStopMessage(stopReason)
            }
        }
        // A terminal report describes the entire captured selection, including unattempted work.
        for (track in selectedTracks) {
            if (track.id !in results) {
                results[track.id] = SyncTrackResult(track.id, "not_attempted", 0, "not_checked",
                    if (stopReason == "none") "not_attempted" else stopReason)
            }
        }
        for (playlist in selectedPlaylists) {
            if (playlist.id !in playlistResults) {
                playlistResults[playlist.id] = SyncPlaylistResult(playlist.id,
                    if (playlist.id in existingPlaylistIds) "preserved" else "deferred")
            }
        }
        val rows = selectedTracks.map { results.getValue(it.id) }
        val playlistRows = selectedPlaylists.map { playlistResults.getValue(it.id) }
        val report = SyncClientReport(manifest.libraryId, manifest.revision, UUID.randomUUID().toString(), rows, playlistRows,
            SyncRunSummary(status, stopReason, rows.size, playlistRows.size,
                rows.count { it.outcome == "downloaded_verified_imported" },
                rows.count { it.outcome == "reused_verified_imported" },
                rows.count { it.outcome == "failed" }, rows.count { it.outcome == "not_attempted" },
                playlistRows.count { it.outcome == "updated" }, playlistRows.count { it.outcome == "preserved" },
                playlistRows.count { it.outcome == "deferred" }))
        val runContext = currentCoroutineContext()
        withContext(NonCancellable) {
        val saved = withContext(ioDispatcher) { fileStorage.writeLastReport(report) }
        // Reporting is optional, local-only and independent of music commits. Do not reconnect
        // after cancellation, trust/auth/revision failure, storage failure or lost connectivity.
        val mayDeliver = stopReason in setOf("none", "source_unavailable", "unexpected_eof", "size_mismatch",
            "hash_mismatch", "unsupported_format", "verification_failed", "http_error")
        if (creds != null && saved && mayDeliver && generation == operationGeneration && runContext[Job]?.isActive != false) {
            try {
                reportDelivery = withContext(runContext + ioDispatcher) { SyncClientReports.deliver(report, creds, transport) }
            } catch (_: CancellationException) {
                reportDelivery = SyncReportDelivery.INCOMPLETE
            } catch (_: SyncCancelledException) {
                reportDelivery = SyncReportDelivery.INCOMPLETE
            } catch (_: Exception) {
                reportDelivery = SyncReportDelivery.INCOMPLETE
            }
        }
        if (generation == operationGeneration) {
            _uiState.update {
                it.copy(
                    phase = when (status) { "cancelled" -> SyncPhase.CANCELLED; "stopped" -> SyncPhase.ERROR; else -> SyncPhase.COMPLETED },
                    progress = null, preview = null, errorMessage = displayError,
                    requiresRePair = stopReason == "session_expired" || stopReason == "tls_failed",
                    lastOutcome = outcomeFor(report, manifest.libraryName, saved, reportDelivery))
            }
        }
        }
    }

    private fun trackEntity(libraryId: String, track: SyncTrackDto, location: Uri, privatePath: String?, previous: TrackEntity?): TrackEntity {
        val now = System.currentTimeMillis()
        val uri = location.toString()
        return TrackEntity(
            trackKey = SyncManifestValidator.stableTrackKey(libraryId, track.id),
            mediaStoreId = null, contentUri = uri, sourceType = TrackSourceType.SYNCED_FILE,
            title = track.title, artist = track.artist.ifBlank { "Unknown Artist" },
            album = track.album.ifBlank { "Unknown Album" }, albumId = null, albumArtUri = uri,
            genre = track.genre.ifBlank { "Unknown Genre" }, durationMs = track.durationMs,
            dateAddedSec = previous?.dateAddedSec ?: now / 1000L, mimeType = track.mimeType,
            sizeBytes = track.sizeBytes, moodTag = previous?.moodTag, isMissingOrUnreadable = false,
            lastError = null, lastScannedAtMs = now, syncLibraryId = libraryId,
            syncTrackId = track.id, contentSha256 = track.sha256, localFilePath = privatePath)
    }

    private fun reasonFor(error: Exception, importing: Boolean = false): String = when {
        error is CancellationException || error is SyncCancelledException -> "cancelled"
        error is SyncTrackTransferException -> error.reason
        error is TransferFolderUnavailable -> "source_unavailable"
        error is SyncAuthExpiredException -> "session_expired"
        error is SyncRevisionConflictException -> "manifest_changed"
        error is SyncTlsException -> "tls_failed"
        error is SyncStorageException -> error.reason
        generateSequence<Throwable>(error) { it.cause }.take(12).any { it is android.database.sqlite.SQLiteFullException } -> "storage_full"
        error is SyncNetworkException -> "connection_failed"
        importing -> "import_failed"
        error is SyncProtocolException -> "protocol_error"
        else -> "local_storage_error"
    }

    private fun safeStopMessage(reason: String): String = when (reason) {
        "session_expired" -> "The Mac session expired. Pair again. Verified imports were kept."
        "manifest_changed" -> "The Mac snapshot changed. Refresh or restart sharing before retrying. Verified imports were kept."
        "tls_failed" -> "The secure connection could not be verified. Pair again before retrying."
        "storage_full", "local_storage_error", "import_failed" -> "Sync stopped because local storage or import failed. Earlier verified imports were kept."
        "connection_failed", "timeout" -> "Sync stopped after the connection was lost or repeatedly failed. Earlier verified imports were kept."
        "protocol_error" -> "Sync stopped because the Mac response was invalid. Earlier verified imports were kept."
        else -> "Sync stopped at a file error. Earlier verified imports were kept. Enable Continue past file errors to skip recoverable files."
    }

    private fun outcomeFor(report: SyncClientReport, libraryName: String, saved: Boolean, delivery: SyncReportDelivery?): SyncOutcome {
        val summary = report.summary
        return SyncOutcome(libraryName, summary.downloadedImported + summary.reusedImported,
            summary.downloadedImported, summary.reusedImported, summary.playlistsUpdated, System.currentTimeMillis(),
            status = summary.outcome, failedTracksCount = summary.failed, notAttemptedTracksCount = summary.notAttempted,
            preservedPlaylistsCount = summary.playlistsPreserved, deferredPlaylistsCount = summary.playlistsDeferred,
            stopReason = summary.stopReason, reportDelivery = delivery, localReportSaved = saved,
            failures = report.results.filter { it.outcome == "failed" || it.outcome == "not_attempted" }, reportId = report.reportId)
    }

    /** Explicit local copy of the exact report shown when the user opened the picker. */
    suspend fun exportLastReport(resolver: ContentResolver, destination: Uri, reportId: String): Boolean {
        if (destination.scheme != ContentResolver.SCHEME_CONTENT ||
            destination.authority !in setOf("com.android.externalstorage.documents", "com.android.providers.downloads.documents")) return false
        val state = _uiState.value
        if (_musicFolderState.value.busy || state.isConnectingOrSyncing || state.lastOutcome?.reportId != reportId) return false
        // A concurrent new run may replace the on-disk last report. Never export another run's receipt.
        return withContext(ioDispatcher) {
            val report = fileStorage.readLastReport() ?: return@withContext false
            if (report.reportId != reportId) return@withContext false
            try {
                resolver.openOutputStream(destination, "wt")?.use { output ->
                    output.write(SyncClientReports.encodeLocal(report).toByteArray(Charsets.UTF_8))
                    output.flush()
                } ?: return@withContext false
                true
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { false }
        }
    }

    fun cancelActiveSync(reason: String = "Sync cancelled. Verified imports are available in your library.") {
        if (!_uiState.value.isConnectingOrSyncing) return
        cancellationMessage = reason
        if (activeCredentials != null) transport.abortActiveConnections()
        activeJob?.cancel(CancellationException(reason))
        // The operation mutex keeps a retry behind the cancelled run's small
        // commit/report cleanup even though the UI can immediately acknowledge Cancel.
        _uiState.update { it.copy(phase = SyncPhase.CANCELLED, progress = null, errorMessage = reason) }
    }

    fun cancelActiveSyncOnBackground() {
        if (_musicFolderState.value.busy) cancelMusicFolderWork()
        if (!_uiState.value.isConnectingOrSyncing) return
        cancelActiveSync("Sync cancelled because SoundsGood moved to the background. Keep SoundsGood open during sync.")
    }

    fun disconnectSession() {
        if (_musicFolderState.value.busy) return
        cancelActiveWorkInternal(clearCredentials = true)
        activeManifest = null
        activeFolder = null
        _uiState.value = LocalSyncUiState(phase = SyncPhase.UNPAIRED, lastOutcome = _uiState.value.lastOutcome)
    }

    private fun cancelActiveWorkInternal(clearCredentials: Boolean) {
        operationGeneration++
        selectionGeneration++
        previewJob?.cancel()
        if (activeCredentials != null) transport.abortActiveConnections()
        activeJob?.cancel()
        // The next operation takes the same mutex and waits for old work to finish.
        // Each transfer cleans only its own part file; it never deletes another run's staging.
        if (clearCredentials) activeCredentials = null
    }
}
