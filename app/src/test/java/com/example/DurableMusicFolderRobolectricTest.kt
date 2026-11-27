package com.example

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.SoundsGoodDatabase
import com.example.data.db.SyncedPlaylistEntity
import com.example.data.db.SyncedPlaylistEntryEntity
import com.example.data.db.TrackEntity
import com.example.data.db.TrackSourceType
import com.example.data.repository.LibraryRepository
import com.example.data.scanner.MediaStoreScanner
import com.example.playback.MediaItemMapper
import com.example.sync.DurableMusicFolder
import com.example.sync.LocalSyncManager
import com.example.sync.PairingCredentials
import com.example.sync.SyncFileStorage
import com.example.sync.SyncPhase
import com.example.sync.TransferFolderSource
import com.example.sync.SyncManifest
import com.example.sync.SyncTrackDto
import com.example.sync.SyncTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowStatFs
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [DurableMusicFolderRobolectricTest.FolderContentResolverShadow::class])
class DurableMusicFolderRobolectricTest {
    @get:Rule val temp = TemporaryFolder()

    private val authority = "com.android.externalstorage.documents"
    private val selectedId = "primary:Music/Fixture"
    private val tree: Uri get() = DocumentsContract.buildTreeDocumentUri(authority, selectedId)
    private val grant = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    @Implements(ContentResolver::class)
    class FolderContentResolverShadow : ShadowContentResolver() {
        @Implementation
        protected override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            if (uri.authority == "com.android.externalstorage.documents" && selection == null &&
                selectionArgs == null && sortOrder == null) return super.query(uri, projection, null, null)
            return super.query(uri, projection, selection, selectionArgs, sortOrder)
        }
    }

    /** A local Android DocumentsProvider with real file descriptors and changeable access. */
    private class LocalDocuments(val selectedId: String, folder: File) : DocumentsProvider() {
        val dir = folder.canonicalFile
        var accessible = true
        var deletions = 0
        private fun file(id: String): File {
            if (!accessible) throw SecurityException("Fixture access revoked")
            if (id == selectedId) return dir
            if (!id.startsWith(selectedId + "/")) throw FileNotFoundException()
            val child = File(dir, id.substring(selectedId.length + 1)).canonicalFile
            if (!child.toPath().startsWith(dir.canonicalFile.toPath())) throw FileNotFoundException()
            return child
        }
        private fun id(f: File) = if (f == dir) selectedId else selectedId + "/" + f.relativeTo(dir).invariantSeparatorsPath
        private fun documentRows(files: List<File>, projection: Array<out String>?): Cursor {
            val cols = projection?.toList() ?: listOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_FLAGS)
            return MatrixCursor(cols.toTypedArray()).apply {
                for (f in files) addRow(cols.map { col -> when (col) {
                    Document.COLUMN_DOCUMENT_ID -> id(f)
                    Document.COLUMN_DISPLAY_NAME -> f.name
                    Document.COLUMN_MIME_TYPE -> if (f.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream"
                    Document.COLUMN_SIZE -> if (f.isFile) f.length() else null
                    Document.COLUMN_FLAGS -> if (f.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_RENAME
                        else Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_DELETE
                    else -> null
                } })
            }
        }
        override fun onCreate() = true
        override fun queryRoots(projection: Array<out String>?): Cursor {
            val cols = projection?.toList() ?: listOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_AVAILABLE_BYTES)
            return MatrixCursor(cols.toTypedArray()).apply {
                addRow(cols.map { when (it) {
                    Root.COLUMN_ROOT_ID -> "primary"
                    Root.COLUMN_DOCUMENT_ID -> "primary:"
                    Root.COLUMN_AVAILABLE_BYTES -> 64L * 1024 * 1024
                    else -> null
                } })
            }
        }
        override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
            val document = file(documentId)
            if (!document.exists()) throw FileNotFoundException("Fixture document unavailable")
            return documentRows(listOf(document), projection)
        }
        override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
            documentRows(file(parentDocumentId).listFiles()?.toList().orEmpty(), projection)
        override fun isChildDocument(parentDocumentId: String, documentId: String) =
            documentId.startsWith(parentDocumentId + "/")
        override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
            val child = File(file(parentDocumentId), displayName)
            require(child.parentFile == file(parentDocumentId))
            check(if (mimeType == Document.MIME_TYPE_DIR) child.mkdir() else child.createNewFile())
            return id(child)
        }
        override fun renameDocument(documentId: String, displayName: String): String {
            val oldFile = file(documentId)
            val newFile = File(oldFile.parentFile, displayName)
            require(newFile.parentFile == oldFile.parentFile && !newFile.exists())
            check(oldFile.renameTo(newFile))
            return id(newFile)
        }
        override fun deleteDocument(documentId: String) {
            check(file(documentId).delete())
            deletions++
        }
        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
            ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))
    }

    private fun installProvider(context: Context, deviceFolder: File): LocalDocuments {
        val provider = LocalDocuments(selectedId, deviceFolder)
        provider.attachInfo(context, ProviderInfo().apply {
            authority = this@DurableMusicFolderRobolectricTest.authority
            applicationInfo = context.applicationInfo
            exported = true
            grantUriPermissions = true
            readPermission = Manifest.permission.MANAGE_DOCUMENTS
            writePermission = Manifest.permission.MANAGE_DOCUMENTS
        })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        return provider
    }

    private val offline = object : SyncTransport {
        override suspend fun fetchManifest(credentials: PairingCredentials): SyncManifest = error("Unexpected network access")
        override suspend fun downloadTrackAudio(credentials: PairingCredentials, revision: String, track: SyncTrackDto,
            partFile: File, onBytesProgress: (Long) -> Unit): Unit = error("Unexpected network access")
        override fun abortActiveConnections() = Unit
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun wave(): ByteArray {
        val frames = 320
        val dataBytes = frames * 2
        return ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".encodeToByteArray()); putInt(36 + dataBytes); put("WAVEfmt ".encodeToByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".encodeToByteArray()); putInt(dataBytes); repeat(frames) { putShort((it - 160).toShort()) }
        }.array()
    }
    private fun db(context: Context) = Room.inMemoryDatabaseBuilder(context, SoundsGoodDatabase::class.java)
        .allowMainThreadQueries().build()
    private fun repository(db: SoundsGoodDatabase, context: Context) = LibraryRepository(db.trackDao(), db.statsDao(),
        db.smartPlaylistDao(), db.playbackSnapshotDao(), db.syncedPlaylistDao(), MediaStoreScanner(context))
    private fun manager(repo: LibraryRepository, storage: SyncFileStorage) =
        LocalSyncManager(repo, storage, offline, Dispatchers.IO, Dispatchers.Unconfined)
    private suspend fun waitForSelection(manager: LocalSyncManager) {
        val state = withTimeout(15000) { manager.musicFolderState.first { !it.busy } }
        assertTrue(state.message, state.selected)
        assertEquals(state.message, 0, state.skipped)
        assertFalse(state.message, state.message.orEmpty().contains("stopped", ignoreCase = true))
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun assertReadableBytes(context: Context, row: TrackEntity, bytes: ByteArray) {
        assertNull(row.localFilePath)
        val uri = requireNotNull(MediaItemMapper.toMediaItem(row).localConfiguration).uri
        assertEquals("content", uri.scheme)
        val reader = DefaultDataSource.Factory(context).createDataSource()
        try {
            reader.open(DataSpec(uri))
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(256)
            while (true) {
                val count = reader.read(buffer, 0, buffer.size)
                if (count == C.RESULT_END_OF_INPUT) break
                assertTrue(count > 0)
                output.write(buffer, 0, count)
            }
            assertArrayEquals(bytes, output.toByteArray())
        } finally { reader.close() }
    }

    @Test fun migrateRescanRevokeThenRecoverIntoEmptyDatabaseUsingOnlySelectedFolder() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val deviceFolder = temp.newFolder("shared")
        val provider = installProvider(context, deviceFolder)
        val oldRoot = temp.newFolder("previous-install")
        val privateFiles = SyncFileStorage(oldRoot).also { it.ensureDirectories() }
        val bytes = wave()
        val sha = hash(bytes)
        val revisedBytes = bytes.copyOf().also { it[it.lastIndex] = 1 }
        val revisedSha = hash(revisedBytes)
        val original = privateFiles.targetFileFor(sha, "wav").apply { writeBytes(bytes) }
        val key = "sync:lib:one"
        val alias = "sync:lib:two"
        val pl = "syncpl:lib:mix"
        val foreign = File(deviceFolder, "user-owned.txt").apply { writeText("Existing file") }
        val originalTrack = TrackEntity(trackKey = key, contentUri = Uri.fromFile(original).toString(),
            sourceType = TrackSourceType.SYNCED_FILE, title = "Fixture", artist = "Artist", album = "Album",
            durationMs = 40L, sizeBytes = bytes.size.toLong(), mimeType = "audio/wav", syncLibraryId = "lib",
            syncTrackId = "one", contentSha256 = sha, localFilePath = original.absolutePath, dateAddedSec = 123L)
        val secondTrack = originalTrack.copy(trackKey = alias, syncTrackId = "two", title = "Same recording")
        val old = db(context)
        try {
            val repo = repository(old, context)
            val manager = manager(repo, privateFiles)
            repo.commitSyncedLibraryBatch(listOf(originalTrack, secondTrack),
                listOf(SyncedPlaylistEntity(pl, "lib", "Fixture library", "mix", "Mix", "r1", 123L)),
                mapOf(pl to listOf(SyncedPlaylistEntryEntity(pl, 0, key), SyncedPlaylistEntryEntity(pl, 1, key))))
            repo.setMoodTag(key, "Quiet")
            repo.recordListeningEventOnce(key, "PLAY", "session1", 40L, 40L)
            manager.chooseMusicFolder(context, tree)
            waitForSelection(manager)
            manager.migrateMusicFolder()
            waitForSelection(manager)
            val migratedTracks = repo.getAllSyncedTracks().associateBy { it.trackKey }
            assertEquals(setOf(key, alias), migratedTracks.keys)
            val migrated = migratedTracks.getValue(key)
            assertEquals(migrated.contentUri, migratedTracks.getValue(alias).contentUri)
            assertEquals(key, migrated.trackKey)
            assertReadableBytes(context, migrated, bytes)
            assertEquals("Quiet", migrated.moodTag)
            assertEquals(1, old.statsDao().getStatsForTrack(key)?.playCount)
            assertEquals(listOf(key, key), repo.getSyncedPlaylistEntries(pl).map { it.trackKey })
            assertArrayEquals(bytes, original.readBytes())
            assertTrue(deviceFolder.walk().any { it.name == ".nomedia" })
            repo.rescanMediaStoreLibrary()
            assertFalse(repo.getAllSyncedTracks().first { it.trackKey == key }.isMissingOrUnreadable)
            // A late private-path scan cannot mark the re-pointed shared row as missing.
            old.trackDao().updateSyncedScanStatus(key, originalTrack.contentUri, true, "old location")
            assertFalse(repo.getAllSyncedTracks().first { it.trackKey == key }.isMissingOrUnreadable)
            // Simulate publication of changed content followed by loss of the old database.
            // The other logical track continues to use the earlier audio blob.
            val revised = privateFiles.targetFileFor(revisedSha, "wav").apply { writeBytes(revisedBytes) }
            val revisedDto = SyncTrackDto("one", "New revision", "Artist", "Album", "", 40L,
                revisedBytes.size.toLong(), revisedSha, "audio/wav", "wav")
            DurableMusicFolder.open(context.contentResolver, tree).store("lib", "Fixture library", "r2", revisedDto, revised)
            val existingFiles = deviceFolder.walk().filter { it.isFile }.map { it.relativeTo(deviceFolder).path }.toSet()
            val deletionsBefore = provider.deletions
            context.contentResolver.releasePersistableUriPermission(tree, grant)
            provider.accessible = false
            repo.rescanMediaStoreLibrary()
            val revoked = repo.getAllSyncedTracks().first { it.trackKey == key }
            assertEquals(key, revoked.trackKey)
            assertTrue(revoked.isMissingOrUnreadable)
            assertEquals(migrated.contentUri, revoked.contentUri)
            assertEquals(deletionsBefore, provider.deletions)
            assertEquals(existingFiles, deviceFolder.walk().filter { it.isFile }.map { it.relativeTo(deviceFolder).path }.toSet())
            assertEquals(1, old.statsDao().getStatsForTrack(key)?.playCount)
        } finally { old.close() }
        // Simulate loss of app-owned media and Room. The user then grants the original tree again.
        assertTrue(oldRoot.deleteRecursively())
        context.getSharedPreferences("shared_music_folder", Context.MODE_PRIVATE).edit().clear().commit()
        provider.accessible = true
        val fresh = db(context)
        try {
            val repo = repository(fresh, context)
            assertTrue(repo.getAllSyncedTracks().isEmpty())
            val freshFiles = SyncFileStorage(temp.newFolder("fresh-install"))
            val manager = manager(repo, freshFiles)
            manager.chooseMusicFolder(context, tree)
            waitForSelection(manager)
            // A second reconnect against the same durable evidence must remain idempotent.
            manager.recoverMusicFolder()
            waitForSelection(manager)
            val rows = repo.getAllSyncedTracks()
            assertEquals(2, rows.size)
            val restored = rows.first { it.trackKey == key }
            assertEquals(revisedSha, restored.contentSha256)
            assertEquals("New revision", restored.title)
            assertReadableBytes(context, restored, revisedBytes)
            val other = rows.first { it.trackKey == alias }
            assertEquals(sha, other.contentSha256)
            assertReadableBytes(context, other, bytes)
            assertEquals(listOf(key, key), repo.getSyncedPlaylistEntries(pl).map { it.trackKey })
            assertTrue(freshFiles.audioDir.listFiles().isNullOrEmpty())
            assertEquals("Existing file", foreign.readText())
            assertFalse(original.exists())
        } finally { fresh.close() }
    }

    @Test fun bothTransferSourcesWriteToChosenFolderAndLostGrantPreventsPrivateFallback() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val deviceFolder = temp.newFolder("local-volume")
        installProvider(context, deviceFolder)
        val phone = temp.newFolder("phone-install")
        ShadowStatFs.registerStats(phone, 100000, 90000, 80000)
        val storage = SyncFileStorage(phone)
        val resolver = context.contentResolver
        val wifiBytes = wave()
        val usbBytes = wifiBytes.copyOf().also { it[it.lastIndex] = 2 }
        fun track(id: String, body: ByteArray) = SyncTrackDto(id, id, "Artist", "Album", "", 40L,
            body.size.toLong(), hash(body), "audio/wav", "wav")
        fun manifest(track: SyncTrackDto) =
            SyncManifest(1, "fixture", "Fixture library", "r1", listOf(track), emptyList(), emptyList())
        val wifiTrack = track("wifi", wifiBytes)
        val usbTrack = track("usb", usbBytes)
        var wifiDownloads = 0
        val wifi = object : SyncTransport {
            override suspend fun fetchManifest(credentials: PairingCredentials) = manifest(wifiTrack)
            override suspend fun downloadTrackAudio(credentials: PairingCredentials, revision: String, track: SyncTrackDto,
                partFile: File, onBytesProgress: (Long) -> Unit) {
                check(track == wifiTrack)
                wifiDownloads++
                partFile.writeBytes(wifiBytes)
                onBytesProgress(wifiBytes.size.toLong())
            }
            override fun abortActiveConnections() = Unit
        }
        val usb = File(deviceFolder, "USB").apply { check(mkdir()) }
        val usbAudio = File(usb, "audio").apply { check(mkdir()) }
        val usbBody = File(usbAudio, usbTrack.sha256 + ".wav").apply { writeBytes(usbBytes) }
        val usbJson = Json.encodeToString(manifest(usbTrack)).encodeToByteArray()
        File(usb, "manifest.json").writeBytes(usbJson)
        File(usb, "READY").writeText("SoundsGood-Transfer-v1\n" + hash(usbJson) + "\n")
        val usbTree = DocumentsContract.buildTreeDocumentUri(authority, selectedId + "/USB")
        val database = db(context)
        try {
            val repo = repository(database, context)
            val manager = LocalSyncManager(repo, storage, wifi, Dispatchers.IO, Dispatchers.Unconfined)
            manager.chooseMusicFolder(context, tree)
            waitForSelection(manager)
            suspend fun ready() {
                val state = withTimeout(15000) { manager.uiState.first { it.phase == SyncPhase.READY || it.phase == SyncPhase.ERROR } }
                assertEquals(state.errorMessage, SyncPhase.READY, state.phase)
            }
            suspend fun transfer() = run {
                manager.startSync()
                val state = withTimeout(15000) { manager.uiState.first { it.lastOutcome != null && !it.isConnectingOrSyncing } }
                requireNotNull(state.lastOutcome)
            }
            val pairing = "soundsgood://pair?v=1&host=192.168.1.42&port=8443&fingerprint=" +
                "0123456789abcdef".repeat(4) + "&token=ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef0123456789_"
            assertTrue(manager.pairAndConnect(pairing))
            ready()
            assertEquals(1, transfer().downloadedTracksCount)
            assertEquals(1, transfer().reusedOrUnchangedCount)
            assertEquals(1, wifiDownloads)
            manager.openTransferFolder(TransferFolderSource(resolver, usbTree))
            ready()
            assertEquals(1, transfer().downloadedTracksCount)
            // Corrupting the removable synthetic input cannot affect its verified local copy.
            usbBody.writeBytes(ByteArray(usbBytes.size))
            assertEquals(1, transfer().reusedOrUnchangedCount)
            val imported = repo.getAllSyncedTracks().associateBy { it.syncTrackId }
            assertReadableBytes(context, imported.getValue("wifi"), wifiBytes)
            assertReadableBytes(context, imported.getValue("usb"), usbBytes)
            assertTrue(storage.audioDir.listFiles().isNullOrEmpty())
            assertTrue(storage.stagingDir.listFiles().isNullOrEmpty())
            // The wired source is still selectable, while the destination grant has gone.
            resolver.releasePersistableUriPermission(tree, grant)
            val lost = transfer()
            assertTrue(lost.status != "complete")
            assertEquals(0, lost.downloadedTracksCount)
            assertEquals(0, lost.reusedOrUnchangedCount)
            assertEquals(imported.values.map { it.contentUri }.toSet(), repo.getAllSyncedTracks().map { it.contentUri }.toSet())
            assertTrue(storage.audioDir.listFiles().isNullOrEmpty())
            assertTrue(storage.stagingDir.listFiles().isNullOrEmpty())
        } finally { database.close() }
    }
}
