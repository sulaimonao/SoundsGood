package com.example

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.SoundsGoodDatabase
import com.example.data.repository.LibraryRepository
import com.example.data.scanner.MediaStoreScanner
import com.example.sync.*
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
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TransferFolderImportRobolectricTest {
    @get:Rule val temp = TemporaryFolder()

    private val authority = "com.android.externalstorage.documents"
    private val rootId = "volume:SoundsGood"
    private val tree: Uri get() = DocumentsContract.buildTreeDocumentUri(authority, rootId)

    /** Back a SAF tree with opaque document IDs unrelated to display names. */
    private class FilesProvider(val root: String, val files: File) : ContentProvider() {
        val rows = mutableMapOf<String, MutableList<Triple<String, String, String>>>()
        val bodies = mutableMapOf<String, File>()
        var openedAudio = 0
        fun add(parent: String, id: String, name: String, bytes: ByteArray?) {
            rows.getOrPut(parent) { mutableListOf() }.add(Triple(id, name,
                if (bytes == null) Document.MIME_TYPE_DIR else "application/octet-stream"))
            if (bytes != null) bodies[id] = File.createTempFile("doc", ".bin", files).apply { writeBytes(bytes) }
        }
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                           selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val columns = requireNotNull(projection).map { it }.toTypedArray()
            val cursor = MatrixCursor(columns)
            for ((id, name, type) in rows[DocumentsContract.getDocumentId(uri)].orEmpty()) {
                cursor.addRow(columns.map { when (it) {
                    Document.COLUMN_DOCUMENT_ID -> id
                    Document.COLUMN_DISPLAY_NAME -> name
                    Document.COLUMN_MIME_TYPE -> type
                    else -> null
                } })
            }
            return cursor
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val id = DocumentsContract.getDocumentId(uri)
            val f = bodies[id] ?: throw FileNotFoundException()
            if (id.startsWith("blob")) openedAudio++
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
        }
        override fun getType(uri: Uri): String = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("No inserts expected")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("No update expected")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("No deletion expected")
    }

    private val noNetwork = object : SyncTransport {
        override suspend fun fetchManifest(credentials: PairingCredentials): SyncManifest = error("Unexpected Wi-Fi manifest")
        override suspend fun downloadTrackAudio(credentials: PairingCredentials, revision: String, track: SyncTrackDto,
            partFile: File, onBytesProgress: (Long) -> Unit): Unit = error("Unexpected Wi-Fi audio")
        override suspend fun sendClientReportBatch(credentials: PairingCredentials, revision: String, body: ByteArray):
            SyncClientReportPostResult = error("Unexpected Wi-Fi report")
        override fun abortActiveConnections(): Unit = error("A folder import does not own the Wi-Fi transport")
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun track(id: String, bytes: ByteArray) =
        SyncTrackDto(id, id, "Fixture", "Album", "", 100, bytes.size.toLong(), hash(bytes), "audio/mpeg", "mp3")
    private fun installProvider(context: Context, manifest: SyncManifest): FilesProvider {
        val provider = FilesProvider(rootId, temp.root)
        provider.attachInfo(context, ProviderInfo().apply {
            authority = this@TransferFolderImportRobolectricTest.authority
            applicationInfo = context.applicationInfo
            grantUriPermissions = true
        })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val json = Json.encodeToString(manifest).encodeToByteArray()
        provider.add(rootId, "json", "manifest.json", json)
        provider.add(rootId, "done", "READY", ("SoundsGood-Transfer-v1\n" + hash(json) + "\n").encodeToByteArray())
        provider.add(rootId, "assets", "audio", null)
        return provider
    }

    @Test fun realPickerSourceFeedsRoomAndReusesVerifiedTracksAcrossOfflineRetries() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(4, 5, 6)
        val ta = track("a", a)
        val tb = track("b", b)
        val manifest = SyncManifest(1, "library", "Synthetic library", "r1", listOf(ta, tb),
            listOf(SyncPlaylistDto("ordered", "Ordered", listOf("a", "a")),
                SyncPlaylistDto("pending", "Pending", listOf("b", "a"))), emptyList())
        val provider = installProvider(context, manifest)
        provider.add("assets", "blob-a", ta.sha256 + ".mp3", a)
        provider.add("assets", "blob-b", tb.sha256 + ".mp3", byteArrayOf(9, 9, 9))
        provider.add(rootId, "report", "result.json", byteArrayOf())
        val dataDir = temp.newFolder("phone")
        ShadowStatFs.registerStats(dataDir, 100000, 90000, 80000)
        val db = Room.inMemoryDatabaseBuilder(context, SoundsGoodDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repository = LibraryRepository(db.trackDao(), db.statsDao(), db.smartPlaylistDao(),
                db.playbackSnapshotDao(), db.syncedPlaylistDao(), MediaStoreScanner(context))
            val manager = LocalSyncManager(repository, SyncFileStorage(dataDir), noNetwork, Dispatchers.IO, Dispatchers.Unconfined)
            suspend fun runImport() {
                manager.openTransferFolder(TransferFolderSource(context.contentResolver, tree))
                val ready = withTimeout(15000) { manager.uiState.first { it.phase == SyncPhase.READY || it.phase == SyncPhase.ERROR } }
                assertEquals(ready.errorMessage, SyncPhase.READY, ready.phase)
                assertFalse(ready.isPaired)
                assertTrue(ready.isFolderImport)
                manager.startSync(true)
                withTimeout(15000) { manager.uiState.first { it.lastOutcome != null && !it.isConnectingOrSyncing } }
            }
            runImport()
            val first = requireNotNull(manager.uiState.value.lastOutcome)
            assertEquals("partial", first.status)
            assertEquals(1, first.downloadedTracksCount)
            assertEquals(1, first.deferredPlaylistsCount)
            assertEquals("hash_mismatch", first.failures.single().reason)
            assertNull(first.reportDelivery)
            val playlists = withTimeout(10000) { repository.syncedPlaylists.first() }
            assertEquals(listOf("sync:library:a", "sync:library:a"), playlists.single().entries.map { it.trackWithStats.track.trackKey })
            val out = DocumentsContract.buildDocumentUriUsingTree(tree, "report")
            assertTrue(manager.exportLastReport(context.contentResolver, out, requireNotNull(first.reportId)))
            val exported = requireNotNull(SyncClientReports.decodeLocal(provider.bodies.getValue("report").readText()))
            assertEquals(first.reportId, exported.reportId)
            assertEquals(1, exported.summary.failed)
            provider.bodies.getValue("blob-b").writeBytes(b)
            val reads = provider.openedAudio
            runImport()
            val second = requireNotNull(manager.uiState.value.lastOutcome)
            assertEquals("complete", second.status)
            assertEquals(1, second.reusedOrUnchangedCount)
            assertEquals(1, second.downloadedTracksCount)
            assertEquals(reads + 1, provider.openedAudio)
            assertEquals(2, db.trackDao().getSyncedTracksForLibrary("library").size)
            assertFalse(manager.exportLastReport(context.contentResolver, out, requireNotNull(first.reportId)))
        } finally { db.close() }
    }

    @Test fun rejectsRemoteProviderAndChangedOrIncompletePackageBeforeOpeningAudio() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manifest = SyncManifest(1, "library", "Library", "r1", emptyList(), emptyList(), emptyList())
        val provider = installProvider(context, manifest)
        val remote = DocumentsContract.buildTreeDocumentUri("example.remote.documents", "root")
        try { TransferFolderSource(context.contentResolver, remote).open(); fail("Foreign provider accepted") }
        catch (_: SyncProtocolException) { }
        provider.bodies.getValue("done").writeText("incomplete")
        try { TransferFolderSource(context.contentResolver, tree).open(); fail("Bad marker accepted") }
        catch (_: SyncProtocolException) { }
        assertEquals(0, provider.openedAudio)
    }
}
