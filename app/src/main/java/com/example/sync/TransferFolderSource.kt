package com.example.sync

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Fixed messages can be displayed without leaking a document URI or provider exception. */
class TransferFolderUnavailable : SyncException("The transfer folder cannot be read. Reconnect the drive or choose the folder again.")

interface VerifiedTransferFolder {
    val manifest: SyncManifest
    suspend fun checkSnapshot()
    suspend fun copyTrack(track: SyncTrackDto, staging: File, progress: (Long) -> Unit)
}

/** Reads only explicitly chosen local shared storage or a removable volume. */
class TransferFolderSource(private val resolver: ContentResolver, private val tree: Uri) {
    private data class Child(val uri: Uri, val directory: Boolean)

    suspend fun open(): VerifiedTransferFolder {
        if (tree.scheme != ContentResolver.SCHEME_CONTENT ||
            tree.authority != "com.android.externalstorage.documents" || !DocumentsContract.isTreeUri(tree)) {
            throw SyncProtocolException("Choose a local folder on this phone or a connected USB drive.")
        }
        val job = currentCoroutineContext()
        return runInterruptible {
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            // A transfer root contains only a few required entries. Allow incidental Mac metadata.
            val top = children(root, 256, setOf("manifest.json", "READY", "audio")) { job.ensureActive() }
            val ready = top["READY"]?.takeUnless { it.directory } ?: throw SyncProtocolException("Copy the complete transfer folder, including READY.")
            val manifestFile = top["manifest.json"]?.takeUnless { it.directory } ?: throw SyncProtocolException("The transfer folder has no manifest.json.")
            val audio = top["audio"]?.takeIf { it.directory } ?: throw SyncProtocolException("The transfer folder has no audio directory.")
            val marker = read(ready.uri, TransferFolderBytes.MARKER_BYTES)
            val bytes = read(manifestFile.uri, SyncManifestValidator.MAX_MANIFEST_BYTES.toInt())
            if (!TransferFolderBytes.matchesManifest(marker, bytes)) {
                throw SyncProtocolException("READY does not match manifest.json. Finish copying the export, then choose it again.")
            }
            val parsed = when (val result = SyncManifestValidator.parseAndValidate(bytes)) {
                is ManifestValidationResult.Valid -> result.manifest
                is ManifestValidationResult.Invalid -> throw SyncProtocolException("The transfer manifest is invalid or uses an unsupported version.")
            }
            val names = parsed.tracks.mapTo(HashSet()) { blobName(it) }
            val blobs = children(audio.uri, SyncManifestValidator.MAX_TRACKS + 1024, names) { job.ensureActive() }
            val snapshot = object : VerifiedTransferFolder {
                override val manifest = parsed
                override suspend fun checkSnapshot() {
                    runInterruptible {
                        checkMarker(ready.uri, marker)
                        val current = read(manifestFile.uri, SyncManifestValidator.MAX_MANIFEST_BYTES.toInt())
                        if (!TransferFolderBytes.matchesManifest(marker, current)) {
                            throw SyncRevisionConflictException("The selected transfer manifest changed. Choose the folder again.")
                        }
                    }
                }
                override suspend fun copyTrack(track: SyncTrackDto, staging: File, progress: (Long) -> Unit) {
                    val context = currentCoroutineContext()
                    runInterruptible {
                        context.ensureActive()
                        val blob = blobs[blobName(track)]?.takeUnless { it.directory }
                        if (blob == null) {
                            checkMarker(ready.uri, marker)
                            throw SyncTrackTransferException("source_unavailable", 0, message = "Audio is missing from the selected folder.")
                        }
                        var observed = 0L
                        try {
                            val input = resolver.openInputStream(blob.uri) ?: throw IOException("Source stream unavailable")
                            input.use { source ->
                                val output = try { FileOutputStream(staging) }
                                    catch (error: IOException) { throw destinationFailure(error) }
                                try {
                                    TransferFolderBytes.copyVerified(source, output, track.sizeBytes, track.sha256,
                                        { context.ensureActive() }, { count -> observed = count; progress(count) })
                                } finally {
                                    try { output.close() } catch (error: IOException) { throw destinationFailure(error) }
                                }
                            }
                        } catch (error: TransferFolderBytes.CopyFailure) {
                            context.ensureActive()
                            when (error.kind) {
                                TransferFolderBytes.Failure.DESTINATION_WRITE -> throw destinationFailure(error)
                                TransferFolderBytes.Failure.SOURCE_READ -> {
                                    checkMarker(ready.uri, marker)
                                    throw SyncTrackTransferException("source_unavailable", error.received, message = "Could not read this audio file.")
                                }
                                TransferFolderBytes.Failure.HASH_MISMATCH -> throw SyncTrackTransferException("hash_mismatch", error.received, "mismatch", "The file hash differs from the manifest.")
                                TransferFolderBytes.Failure.EARLY_EOF -> {
                                    checkMarker(ready.uri, marker)
                                    throw SyncTrackTransferException("unexpected_eof", error.received, message = "The file ended before its declared size.")
                                }
                                TransferFolderBytes.Failure.TOO_LONG -> throw SyncTrackTransferException("size_mismatch", error.received, message = "The file exceeds its declared size.")
                            }
                        } catch (error: SyncException) {
                            throw error
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: RuntimeException) {
                            throw TransferFolderUnavailable()
                        } catch (error: IOException) {
                            context.ensureActive()
                            checkMarker(ready.uri, marker)
                            throw SyncTrackTransferException("source_unavailable", observed, message = "Could not open or finish reading this audio file.")
                        }
                    }
                }
            }
            checkMarker(ready.uri, marker)
            snapshot
        }
    }

    private fun blobName(track: SyncTrackDto): String {
        require(SyncManifestValidator.SHA256_REGEX.matches(track.sha256))
        require(SyncManifestValidator.ALLOWED_EXTENSION_MIME_PAIRS[track.extension] == track.mimeType)
        return track.sha256 + "." + track.extension
    }

    private fun checkMarker(uri: Uri, expected: ByteArray) {
        if (!read(uri, TransferFolderBytes.MARKER_BYTES).contentEquals(expected)) {
            throw SyncRevisionConflictException("The selected transfer folder changed. Choose it again.")
        }
    }

    private fun read(uri: Uri, limit: Int): ByteArray {
        try {
            return resolver.openInputStream(uri)?.use { TransferFolderBytes.readBounded(it, limit) }
                ?: throw TransferFolderUnavailable()
        } catch (error: CancellationException) { throw error }
          catch (error: IOException) { throw TransferFolderUnavailable() }
          catch (error: RuntimeException) { throw TransferFolderUnavailable() }
    }

    private fun children(parent: Uri, limit: Int, wanted: Set<String>, checkActive: () -> Unit): Map<String, Child> {
        val query = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        val found = HashMap<String, Child>()
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        try {
            val cursor = resolver.query(query, columns, null, null, null) ?: throw TransferFolderUnavailable()
            cursor.use {
                var count = 0
                while (it.moveToNext()) {
                    checkActive()
                    if (++count > limit) throw SyncProtocolException("The selected folder has too many entries.")
                    val name = it.getString(1) ?: continue
                    if (name !in wanted) continue
                    val id = it.getString(0) ?: throw TransferFolderUnavailable()
                    if (found.containsKey(name)) throw SyncProtocolException("The selected folder contains duplicate required names.")
                    found[name] = Child(DocumentsContract.buildDocumentUriUsingTree(tree, id), it.getString(2) == Document.MIME_TYPE_DIR)
                }
                if (it.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) throw TransferFolderUnavailable()
            }
        } catch (error: CancellationException) { throw error }
          catch (error: SyncException) { throw error }
          catch (error: RuntimeException) { throw TransferFolderUnavailable() }
        return found
    }

    private fun destinationFailure(error: IOException): SyncStorageException {
        val full = generateSequence<Throwable>(error) { it.cause }.take(8)
            .any { it.message?.contains("ENOSPC", true) == true || it.message?.contains("No space left", true) == true }
        return SyncStorageException("Could not store audio on this phone.", error, if (full) "storage_full" else "local_storage_error")
    }
}
