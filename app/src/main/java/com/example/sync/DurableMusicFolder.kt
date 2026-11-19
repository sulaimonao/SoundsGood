package com.example.sync

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class DurableRecoveryResult(val restored: Int, val skipped: Int, val playlistsRestored: Int, val playlistsSkipped: Int)

@Serializable
private data class MusicFolderReceipt(
    val version: Int = 1,
    val libraryId: String,
    val libraryName: String,
    val revision: String,
    val track: SyncTrackDto? = null,
    val audioName: String? = null,
    val playlist: SyncPlaylistDto? = null
)

private data class RecordHead(val record: DurableFolderNames.Record, val document: FolderDoc, val ambiguous: Boolean = false)

/** Shared audio and recovery records under the selected local SAF tree. */
class DurableMusicFolder private constructor(
    private val docs: DurableFolderDocuments,
    private val folders: OwnedFolders,
    val displayName: String
) {
    val tree: Uri get() = docs.tree
    private val lock = Mutex()
    private val audioByKey = HashMap<String, MutableList<FolderDoc>>()
    private val records = HashMap<String, RecordHead>()
    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = false }
    private val discardOutput = object : OutputStream() {
        override fun write(value: Int) {}
        override fun write(bytes: ByteArray, offset: Int, count: Int) {}
    }

    companion object {
        suspend fun open(resolver: ContentResolver, tree: Uri, takeGrant: Boolean = false): DurableMusicFolder = folderIO {
            DurableFolderDocuments.validateTree(tree)
            val docs = DurableFolderDocuments(resolver, tree)
            docs.acquireGrant(takeGrant)
            val folders = docs.prepare()
            val label = docs.rootId.substringAfter(':').let {
                if (it.substringAfterLast('/') == MANAGED_MUSIC) it else "$it/$MANAGED_MUSIC"
            }
            val result = DurableMusicFolder(docs, folders, label)
            val context = currentCoroutineContext()
            docs.children(folders.audio, 210_000, { context.ensureActive() }, result::indexAudio)
            docs.children(folders.records, 400_000, { context.ensureActive() }, result::indexRecord)
            result
        }
    }

    private fun indexAudio(document: FolderDoc) {
        if (document.isDirectory) return
        val key = DurableFolderNames.blobKey(document.name) ?: return
        audioByKey.getOrPut(key) { mutableListOf() }.add(document)
    }

    private fun indexRecord(document: FolderDoc) {
        if (document.isDirectory) return
        val record = DurableFolderNames.parseRecord(document.name) ?: return
        val prior = records[record.key]
        if (prior == null || record.generation > prior.record.generation) {
            records[record.key] = RecordHead(record, document)
        } else if (record.generation == prior.record.generation && record.digest != prior.record.digest) {
            records[record.key] = prior.copy(ambiguous = true)
        }
    }

    private fun blobKey(track: SyncTrackDto): String = track.sha256 + "." + track.extension

    suspend fun containsCandidate(track: SyncTrackDto): Boolean = lock.withLock {
        audioByKey[blobKey(track)]?.isNotEmpty() == true
    }

    suspend fun availableBytes(): Long? = folderIO { docs.capacity() }

    private suspend fun verified(doc: FolderDoc, track: SyncTrackDto): Boolean {
        if (doc.isDirectory || DurableFolderNames.blobKey(doc.name) != blobKey(track)) return false
        // The document can change after selection. Never trust listing size or filename alone.
        val context = currentCoroutineContext()
        try {
            val stream = docs.resolver.openInputStream(doc.uri) ?: throw IOException("Shared file unavailable")
            stream.use {
                TransferFolderBytes.copyVerified(it, discardOutput, track.sizeBytes, track.sha256,
                    { context.ensureActive() }, {})
            }
        } catch (_: FileNotFoundException) {
            return false
        } catch (error: TransferFolderBytes.CopyFailure) {
            if (error.kind == TransferFolderBytes.Failure.SOURCE_READ) throw error
            return false
        }
        return true
    }

    private suspend fun match(track: SyncTrackDto, preferred: Uri? = null): FolderDoc? {
        val candidates = audioByKey[blobKey(track)] ?: return null
        if (preferred != null) {
            val known = candidates.firstOrNull { it.uri == preferred }
            if (known != null && verified(known, track)) return known
        }
        for (candidate in candidates) if (candidate.uri != preferred && verified(candidate, track)) return candidate
        docs.checkReadable(folders.audio)
        return null
    }

    suspend fun findVerified(track: SyncTrackDto): Uri? = folderIO {
        lock.withLock { match(track)?.uri }
    }

    private fun headerValid(library: String, name: String, revision: String): Boolean =
        SyncManifestValidator.ID_REGEX.matches(library) && SyncManifestValidator.ID_REGEX.matches(revision) &&
            name.isNotBlank() && name.length <= SyncManifestValidator.MAX_METADATA_CODE_UNITS

    private fun validTrack(library: String, name: String, revision: String, track: SyncTrackDto): Boolean =
        track.sizeBytes > 0 && SyncManifestValidator.validate(
            SyncManifest(1, library, name, revision, listOf(track), emptyList(), emptyList())
        ) is ManifestValidationResult.Valid

    private fun validPlaylist(playlist: SyncPlaylistDto): Boolean =
        SyncManifestValidator.ID_REGEX.matches(playlist.id) && playlist.name.isNotBlank() &&
            playlist.name.length <= SyncManifestValidator.MAX_METADATA_CODE_UNITS &&
            playlist.trackIds.size.toLong() <= SyncManifestValidator.MAX_AGGREGATE_PLAYLIST_ENTRIES &&
            playlist.trackIds.all(SyncManifestValidator.ID_REGEX::matches)

    private suspend fun copyAudio(source: File, track: SyncTrackDto): FolderDoc {
        val context = currentCoroutineContext()
        var owned = docs.provisional(folders.audio)
        try {
            source.inputStream().use { input ->
                docs.write(owned) { output ->
                    TransferFolderBytes.copyVerified(input, output, track.sizeBytes, track.sha256,
                        { context.ensureActive() }, {})
                }
            }
            context.ensureActive()
            val name = DurableFolderNames.audioName(track.title, track.sha256, track.extension)
            owned = docs.rename(folders.audio, owned, name)
            if (!verified(owned, track)) throw IOException("Published audio did not pass its byte verification")
            indexAudio(owned)
            return owned
        } catch (error: Throwable) {
            docs.discard(owned)
            throw error
        }
    }

    private fun saveRecord(receipt: MusicFolderReceipt) {
        val id = receipt.track?.id ?: requireNotNull(receipt.playlist).id
        val key = DurableFolderNames.recordKey(receipt.playlist != null, receipt.libraryId, id)
        val bytes = json.encodeToString(receipt).toByteArray(Charsets.UTF_8)
        val limit = if (receipt.track == null) SyncManifestValidator.MAX_MANIFEST_BYTES.toInt() else 64 * 1024
        require(bytes.size <= limit)
        val digest = DurableFolderNames.sha256(bytes)
        val previous = records[key]
        if (previous != null && !previous.ambiguous && previous.record.digest == digest) {
            try {
                if (docs.read(previous.document, bytes.size).contentEquals(bytes)) return
            } catch (_: IOException) {
                // A missing or damaged prior receipt can be superseded without editing it.
            }
        }
        val next = maxOf(System.currentTimeMillis(), 1_000_000_000_000L, (previous?.record?.generation ?: 0L) + 1)
        val newName = DurableFolderNames.recordName(key, next, digest)
        indexRecord(docs.publishBytes(folders.records, newName, bytes))
    }

    private suspend fun recordAudio(libraryId: String, libraryName: String, revision: String,
                                    track: SyncTrackDto, audio: FolderDoc): Uri {
        currentCoroutineContext().ensureActive()
        saveRecord(MusicFolderReceipt(libraryId = libraryId, libraryName = libraryName, revision = revision,
            track = track, audioName = audio.name))
        return audio.uri
    }

    suspend fun reuseVerified(libraryId: String, libraryName: String, revision: String, track: SyncTrackDto): Uri? = folderIO {
        lock.withLock {
            require(validTrack(libraryId, libraryName, revision, track))
            match(track)?.let { recordAudio(libraryId, libraryName, revision, track, it) }
        }
    }

    /** The optional URI is a hint and is accepted only from this folder's indexed, verified blobs. */
    suspend fun store(libraryId: String, libraryName: String, revision: String, track: SyncTrackDto,
                      source: File?, verifiedUri: Uri? = null): Uri = folderIO {
        lock.withLock {
            require(validTrack(libraryId, libraryName, revision, track))
            val audio = match(track, verifiedUri) ?: copyAudio(
                source ?: throw SyncStorageException("The verified song is no longer available in the selected folder."), track)
            recordAudio(libraryId, libraryName, revision, track, audio)
        }
    }

    /** Caller invokes this for a fully committed, complete playlist snapshot. */
    suspend fun storePlaylist(libraryId: String, libraryName: String, revision: String, playlist: SyncPlaylistDto) = folderIO {
        lock.withLock {
            require(headerValid(libraryId, libraryName, revision) && validPlaylist(playlist))
            saveRecord(MusicFolderReceipt(libraryId = libraryId, libraryName = libraryName, revision = revision,
                playlist = playlist))
        }
    }

    private fun load(head: RecordHead): MusicFolderReceipt? {
        if (head.ambiguous) return null
        val playlist = head.record.key.startsWith("p-")
        val limit = if (playlist) SyncManifestValidator.MAX_MANIFEST_BYTES.toInt() else 64 * 1024
        val bytes = try { docs.read(head.document, limit) } catch (_: IOException) { return null }
        if (DurableFolderNames.sha256(bytes) != head.record.digest) return null
        val receipt = try { json.decodeFromString<MusicFolderReceipt>(bytes.decodeToString(throwOnInvalidSequence = true)) }
            catch (_: IllegalArgumentException) { return null }
            catch (_: java.nio.charset.CharacterCodingException) { return null }
        if (receipt.version != 1 || !headerValid(receipt.libraryId, receipt.libraryName, receipt.revision)) return null
        val id = if (playlist) {
            val p = receipt.playlist ?: return null
            if (receipt.track != null || receipt.audioName != null || !validPlaylist(p)) return null
            p.id
        } else {
            val track = receipt.track ?: return null
            val name = receipt.audioName ?: return null
            if (receipt.playlist != null || !validTrack(receipt.libraryId, receipt.libraryName, receipt.revision, track) ||
                DurableFolderNames.blobKey(name) != blobKey(track)) return null
            track.id
        }
        return receipt.takeIf { DurableFolderNames.recordKey(playlist, it.libraryId, id) == head.record.key }
    }

    suspend fun recover(onTrack: suspend (String, String, String, SyncTrackDto, Uri) -> Unit,
                        onPlaylist: suspend (String, String, String, SyncPlaylistDto) -> Unit = { _, _, _, _ -> }
    ): DurableRecoveryResult = folderIO {
        lock.withLock {
            var recovered = 0
            var skipped = 0
            var playlists = 0
            var incomplete = 0
            var entries = 0L
            val recoveredIds = HashMap<String, MutableSet<String>>()
            val verifiedByName = HashMap<String, Pair<Long, Uri>>()
            for (head in records.values.filter { it.record.key.startsWith("t-") }) {
                currentCoroutineContext().ensureActive()
                val receipt = load(head)
                val track = receipt?.track
                val name = receipt?.audioName
                if (receipt == null || track == null || name == null) { skipped++; continue }
                val audio = audioByKey[blobKey(track)]?.firstOrNull { it.name == name }
                if (audio == null) { skipped++; continue }
                val prior = verifiedByName[name]
                if (prior == null || prior.first != track.sizeBytes || prior.second != audio.uri) {
                    if (!verified(audio, track)) { skipped++; continue }
                    verifiedByName[name] = track.sizeBytes to audio.uri
                }
                onTrack(receipt.libraryId, receipt.libraryName, receipt.revision, track, audio.uri)
                recoveredIds.getOrPut(receipt.libraryId) { HashSet() }.add(track.id)
                recovered++
            }
            for (head in records.values.filter { it.record.key.startsWith("p-") }) {
                currentCoroutineContext().ensureActive()
                val receipt = load(head)
                val playlist = receipt?.playlist
                if (receipt == null || playlist == null) { incomplete++; continue }
                entries += playlist.trackIds.size.toLong()
                if (entries > SyncManifestValidator.MAX_AGGREGATE_PLAYLIST_ENTRIES ||
                    !playlist.trackIds.all { recoveredIds[receipt.libraryId]?.contains(it) == true }) {
                    incomplete++; continue
                }
                onPlaylist(receipt.libraryId, receipt.libraryName, receipt.revision, playlist)
                playlists++
            }
            // A removed or revoked volume must not be reported as a successful run with all songs skipped.
            docs.checkReadable(folders.audio)
            docs.checkReadable(folders.records)
            DurableRecoveryResult(recovered, skipped, playlists, incomplete)
        }
    }
}
