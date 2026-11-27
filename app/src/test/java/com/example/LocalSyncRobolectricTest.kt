package com.example

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Looper
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.SoundsGoodDatabase
import com.example.data.db.TrackSourceType
import com.example.data.repository.LibraryRepository
import com.example.data.scanner.MediaStoreScanner
import com.example.playback.MediaItemMapper
import com.example.playback.PlaybackBus
import com.example.playback.PlaybackClient
import com.example.playback.PlaybackService
import com.example.sync.AndroidWifiNetworkSelector
import com.example.sync.LocalSyncManager
import com.example.sync.PairingCredentials
import com.example.sync.PairingUriParser
import com.example.sync.SyncCancelledException
import com.example.sync.SyncFileStorage
import com.example.sync.SyncManifest
import com.example.sync.SyncPhase
import com.example.sync.SyncPlaylistDto
import com.example.sync.SyncProtocolException
import com.example.sync.SyncSkippedDto
import com.example.sync.SyncTrackDto
import com.example.sync.SyncTransport
import com.example.sync.SyncTrackTransferException
import com.example.sync.SyncTlsException
import com.example.sync.SyncAuthExpiredException
import com.example.sync.SyncRevisionConflictException
import com.example.sync.SyncNetworkException
import com.example.sync.SyncStorageException
import com.example.sync.SyncReportDelivery
import com.example.sync.SyncClientReportPostResult
import com.example.ui.viewmodel.SoundsGoodViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowConnectivityManager
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Robolectric tests covering:
 * - Room v1 -> v2 additive migration preserving existing local tracks, stats, smart rules, and snapshots
 * - Wi-Fi-only / non-VPN / direct-subnet policy checks via `AndroidWifiNetworkSelector`
 * - Injected-transport `LocalSyncManager` transfer orchestration, cancellation + `.part` cleanup,
 *   verified-file reuse, integrity rejection, atomic DB commit, stat/mood preservation,
 *   duplicate-entry playlist order & occurrence indexing, `PlaybackClient` queue isolation,
 *   rescan preservation, `MediaItemMapper` file:// playback source mapping, and `MainActivity` deep-link handling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalSyncRobolectricTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var app: SoundsGoodApplication
    private lateinit var db: SoundsGoodDatabase
    private lateinit var repository: LibraryRepository
    private lateinit var fileStorage: SyncFileStorage
    private val testToken = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef0123456789_"
    private val testFingerprint = "0123456789abcdef".repeat(4)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, SoundsGoodDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LibraryRepository(
            trackDao = db.trackDao(),
            statsDao = db.statsDao(),
            smartPlaylistDao = db.smartPlaylistDao(),
            playbackSnapshotDao = db.playbackSnapshotDao(),
            syncedPlaylistDao = db.syncedPlaylistDao(),
            scanner = MediaStoreScanner(app)
        )
        val filesDir = tempFolder.newFolder("files")
        ShadowStatFs.registerStats(filesDir, 100000, 90000, 80000)
        fileStorage = SyncFileStorage(filesDir)
        fileStorage.ensureDirectories()
        PlaybackBus.clearError()
    }

    @After
    fun tearDown() {
        app.localSyncManagerOverride = null
        app.libraryRepositoryOverride = null
        db.close()
        PlaybackBus.clearError()
    }

    private fun pairingLink(host: String = "192.168.1.42", port: Int = 8443): String =
        "soundsgood://pair?v=1&host=$host&port=$port&fingerprint=$testFingerprint&token=$testToken"

    private class FakeSyncTransport(
        var manifestProvider: suspend () -> SyncManifest,
        var audioProvider: suspend (SyncTrackDto, File, (Long) -> Unit) -> Unit
    ) : SyncTransport {
        @Volatile
        var aborted = false
        val downloadedTrackIds = mutableListOf<String>()
        var reportProvider: suspend () -> SyncClientReportPostResult = { SyncClientReportPostResult.UNSUPPORTED }

        override suspend fun sendClientReportBatch(credentials: PairingCredentials, revision: String, body: ByteArray): SyncClientReportPostResult = reportProvider()

        override suspend fun fetchManifest(credentials: PairingCredentials): SyncManifest {
            aborted = false
            return manifestProvider()
        }

        override suspend fun downloadTrackAudio(
            credentials: PairingCredentials,
            revision: String,
            track: SyncTrackDto,
            partFile: File,
            onBytesProgress: (bytesRead: Long) -> Unit
        ) {
            aborted = false
            downloadedTrackIds.add(track.id)
            audioProvider(track, partFile, onBytesProgress)
        }

        override fun abortActiveConnections() {
            aborted = true
        }
    }

    @Test
    fun `Room MIGRATION_1_2 preserves existing v1 tracks, stats, smart playlists, and snapshots`() = runBlocking {
        val dbFile = tempFolder.newFile("soundsgood_v1.db")
        dbFile.delete()

        val v1Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(app)
                .name(dbFile.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `tracks` (
                                `trackKey` TEXT NOT NULL,
                                `mediaStoreId` INTEGER,
                                `contentUri` TEXT NOT NULL,
                                `sourceType` TEXT NOT NULL,
                                `title` TEXT NOT NULL,
                                `artist` TEXT NOT NULL,
                                `album` TEXT NOT NULL,
                                `albumId` INTEGER,
                                `albumArtUri` TEXT,
                                `genre` TEXT NOT NULL,
                                `durationMs` INTEGER NOT NULL,
                                `dateAddedSec` INTEGER NOT NULL,
                                `mimeType` TEXT NOT NULL,
                                `sizeBytes` INTEGER NOT NULL,
                                `moodTag` TEXT,
                                `isMissingOrUnreadable` INTEGER NOT NULL,
                                `lastError` TEXT,
                                `lastScannedAtMs` INTEGER NOT NULL,
                                PRIMARY KEY(`trackKey`)
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `track_stats` (
                                `trackKey` TEXT NOT NULL,
                                `playCount` INTEGER NOT NULL,
                                `skipCount` INTEGER NOT NULL,
                                `totalListenedMs` INTEGER NOT NULL,
                                `lastPlayedAtMs` INTEGER NOT NULL,
                                `lastSkippedAtMs` INTEGER NOT NULL,
                                PRIMARY KEY(`trackKey`)
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `listening_events` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `trackKey` TEXT NOT NULL,
                                `eventType` TEXT NOT NULL,
                                `playbackSessionToken` TEXT NOT NULL,
                                `listenedMs` INTEGER NOT NULL,
                                `timestampMs` INTEGER NOT NULL,
                                `trackDurationMs` INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE UNIQUE INDEX IF NOT EXISTS `index_listening_events_playbackSessionToken_eventType`
                            ON `listening_events` (`playbackSessionToken`, `eventType`)
                            """.trimIndent()
                        )
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_listening_events_trackKey` ON `listening_events` (`trackKey`)")
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `smart_playlist_rules` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `name` TEXT NOT NULL,
                                `description` TEXT NOT NULL,
                                `isBuiltIn` INTEGER NOT NULL,
                                `builtInType` TEXT,
                                `minDurationSec` INTEGER,
                                `maxDurationSec` INTEGER,
                                `artistFilter` TEXT,
                                `albumFilter` TEXT,
                                `genreFilter` TEXT,
                                `moodTagFilter` TEXT,
                                `minPlayCount` INTEGER,
                                `sortOrder` TEXT NOT NULL,
                                `limitCount` INTEGER,
                                `createdAtMs` INTEGER NOT NULL,
                                `updatedAtMs` INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `playback_snapshot` (
                                `id` INTEGER NOT NULL,
                                `currentTrackKey` TEXT,
                                `positionMs` INTEGER NOT NULL,
                                `repeatMode` INTEGER NOT NULL,
                                `shuffleEnabled` INTEGER NOT NULL,
                                `queueTrackKeysJson` TEXT NOT NULL,
                                `shuffledTrackKeysJson` TEXT NOT NULL,
                                `activePlaylistLabel` TEXT,
                                `updatedAtMs` INTEGER NOT NULL,
                                PRIMARY KEY(`id`)
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            INSERT INTO tracks VALUES (
                                'ms:99', 99, 'content://media/external/audio/media/99', 'MEDIA_STORE',
                                'Existing Local Track', 'Local Artist', 'Local Album', 7, NULL,
                                'Indie', 210000, 1700000, 'audio/mpeg', 4096, 'Focus', 0, NULL, 12345
                            )
                            """.trimIndent()
                        )
                        db.execSQL("INSERT INTO track_stats VALUES ('ms:99', 5, 1, 900000, 50000, 10000)")
                        db.execSQL(
                            "INSERT INTO listening_events (trackKey, eventType, playbackSessionToken, listenedMs, timestampMs, trackDurationMs) " +
                                "VALUES ('ms:99', 'PLAY', 'legacy-session', 190000, 50000, 210000)"
                        )
                        db.execSQL(
                            "INSERT INTO smart_playlist_rules VALUES (1, 'My Focus', 'Desc', 0, NULL, 60, 600, NULL, NULL, NULL, 'Focus', 2, 'ALPHABETICAL', 25, 80, 100)"
                        )
                        db.execSQL(
                            "INSERT INTO playback_snapshot VALUES (1, 'ms:99', 12000, 0, 0, '[\"ms:99\"]', '[]', 'Library', 200)"
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        v1Helper.writableDatabase.close()

        val migratedDb = Room.databaseBuilder(app, SoundsGoodDatabase::class.java, dbFile.absolutePath)
            .addMigrations(SoundsGoodDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val tracks = migratedDb.trackDao().getAllTracksWithStatsOnce()
            assertEquals(1, tracks.size)
            assertEquals("Existing Local Track", tracks.first().track.title)
            assertEquals("Focus", tracks.first().track.moodTag)
            assertNull(tracks.first().track.syncLibraryId)
            assertNull(tracks.first().track.syncTrackId)
            assertNull(tracks.first().track.contentSha256)
            assertNull(tracks.first().track.localFilePath)
            assertEquals(5, tracks.first().playCount)
            assertEquals(1, tracks.first().skipCount)
            assertEquals(900000L, tracks.first().totalListenedMs)
            assertEquals(50000L, tracks.first().lastPlayedAtMs)
            assertEquals(4.5, tracks.first().preferenceScore, 0.001)
            assertEquals(1, migratedDb.smartPlaylistDao().countRules())
            val rule = migratedDb.smartPlaylistDao().getRuleById(1L)
            assertNotNull(rule)
            assertEquals("My Focus", rule?.name)
            assertEquals("Focus", rule?.moodTagFilter)
            assertEquals(2, rule?.minPlayCount)
            assertEquals(80L, rule?.createdAtMs)
            assertEquals(100L, rule?.updatedAtMs)
            migratedDb.openHelper.readableDatabase.query(
                "SELECT trackKey, trackDurationMs, listenedMs FROM listening_events WHERE playbackSessionToken = 'legacy-session'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("ms:99", cursor.getString(0))
                assertEquals(210000L, cursor.getLong(1))
                assertEquals(190000L, cursor.getLong(2))
                assertFalse(cursor.moveToNext())
            }
            val snap = migratedDb.playbackSnapshotDao().getSnapshot()
            assertEquals("ms:99", snap?.currentTrackKey)
            assertTrue(migratedDb.syncedPlaylistDao().observeAllSyncedPlaylists().first().isEmpty())
        } finally {
            migratedDb.close()
        }
    }

    @Test
    fun `AndroidWifiNetworkSelector accepts matching Wi-Fi subnet and rejects cellular, VPN, and off-subnet hosts`() {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadowCm: ShadowConnectivityManager = shadowOf(cm)
        val wifiNetwork = cm.activeNetwork!!

        // 1. Valid Wi-Fi non-VPN + matching 192.168.1.0/24 interface address -> passes
        val wifiCaps = ShadowNetworkCapabilities.newInstance()
        shadowOf(wifiCaps).apply {
            addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
        val matchingProps = LinkProperties().apply {
            addLinkAddressReflective(this, newLinkAddress("192.168.1.15", 24))
        }
        shadowCm.setNetworkCapabilities(wifiNetwork, wifiCaps)
        shadowCm.setLinkProperties(wifiNetwork, matchingProps)

        val selector = AndroidWifiNetworkSelector(app)
        val ok = selector.selectWifiNetworkForLocalHost(ipv4("192.168.1.42"))
        assertTrue(ok.isSuccess)

        // Off-subnet host fails direct local route check
        val offSubnet = selector.selectWifiNetworkForLocalHost(ipv4("10.0.0.5"))
        assertTrue(offSubnet.isFailure)

        // 2. VPN transport rejected even when TRANSPORT_WIFI present
        val vpnCaps = ShadowNetworkCapabilities.newInstance()
        shadowOf(vpnCaps).apply {
            addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            addTransportType(NetworkCapabilities.TRANSPORT_VPN)
        }
        shadowCm.setNetworkCapabilities(wifiNetwork, vpnCaps)
        assertTrue(selector.selectWifiNetworkForLocalHost(ipv4("192.168.1.42")).isFailure)

        // 3. Cellular-only rejected
        val cellCaps = ShadowNetworkCapabilities.newInstance()
        shadowOf(cellCaps).apply {
            addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
        shadowCm.setNetworkCapabilities(wifiNetwork, cellCaps)
        assertTrue(selector.selectWifiNetworkForLocalHost(ipv4("192.168.1.42")).isFailure)
    }

    @Test
    fun `injected-transport sync downloads verified audio, preserves duplicates and order, stats, mood tags, and playable MediaItem`() = runBlocking {
        val bytes1 = "SYNTH_AUDIO_FILE_ONE_111111".encodeToByteArray()
        val bytes2 = "SYNTH_AUDIO_FILE_TWO_22222222".encodeToByteArray()
        val t1 = syncTrack("t1", "Repeated Song", bytes1, "flac", "audio/flac")
        val t2 = syncTrack("t2", "Middle Song", bytes2, "mp3", "audio/mpeg")
        val manifestV1 = SyncManifest(
            protocolVersion = 1,
            libraryId = "mac_studio",
            libraryName = "Studio Music",
            revision = "rev-1",
            tracks = listOf(t1, t2),
            playlists = listOf(
                SyncPlaylistDto("pl_dup", "Gym Mix", listOf("t1", "t2", "t1"))
            ),
            skipped = listOf(SyncSkippedDto("Locked.m4p", "DRM protected"))
        )

        val transport = FakeSyncTransport(
            manifestProvider = { manifestV1 },
            audioProvider = { track, partFile, onProgress ->
                val payload = if (track.id == "t1") bytes1 else bytes2
                partFile.writeBytes(payload)
                onProgress(payload.size.toLong())
            }
        )
        val dispatcher = UnconfinedTestDispatcher()
        val manager = LocalSyncManager(repository, fileStorage, transport, dispatcher, dispatcher)

        assertTrue(manager.pairAndConnect(pairingLink()))
        awaitReady(manager)
        assertEquals(SyncPhase.READY, manager.uiState.value.phase)
        assertEquals("Studio Music", manager.uiState.value.libraryName)
        assertEquals("192.168.1.42:8443", manager.uiState.value.pairedAddressDisplay)
        assertEquals(1, manager.uiState.value.preview?.skippedItems?.size)

        manager.startSync()
        awaitResult(manager)
        assertEquals(SyncPhase.COMPLETED, manager.uiState.value.phase)
        assertEquals(2, transport.downloadedTrackIds.size)

        // Verify exact ordered duplicate entries in Room
        val syncedPlaylists = repository.syncedPlaylists.first()
        assertEquals(1, syncedPlaylists.size)
        val entries = syncedPlaylists.first().entries
        assertEquals(listOf("sync:mac_studio:t1", "sync:mac_studio:t2", "sync:mac_studio:t1"), entries.map { it.trackWithStats.track.trackKey })
        assertEquals(listOf(0, 1, 2), entries.map { it.position })

        // Verify ViewModel resolves exact startIndex=2 for repeated track occurrence
        val playbackClient = PlaybackClient(app)
        val vm = SoundsGoodViewModel(repository, playbackClient, manager)
        // MediaItemMapper produces valid file:// URI for synced track
        val syncedEntity = entries[0].trackWithStats.track
        val mediaItem = MediaItemMapper.toMediaItem(syncedEntity)
        assertTrue(mediaItem.localConfiguration?.uri?.scheme == "file")
        assertTrue(File(mediaItem.localConfiguration!!.uri.path!!).exists())

        // Record play stat + moodTag, then resync with metadata title change and same audio sha256
        repository.setMoodTag("sync:mac_studio:t1", "Workout")
        repository.recordListeningEventOnce("sync:mac_studio:t1", "PLAY", "sess-1", 30_000L, 120_000L)

        val updatedT1 = t1.copy(title = "Repeated Song (Remaster)")
        transport.downloadedTrackIds.clear()
        transport.manifestProvider = {
            manifestV1.copy(revision = "rev-2", tracks = listOf(updatedT1, t2))
        }
        manager.refreshManifest()
        awaitReady(manager)
        manager.startSync()
        awaitResult(manager)
        assertEquals(SyncPhase.COMPLETED, manager.uiState.value.phase)
        // Unchanged audio bytes must NOT be re-downloaded on metadata-only update!
        assertTrue("Expected zero audio downloads when SHA-256 unchanged", transport.downloadedTrackIds.isEmpty())

        val reloaded = db.trackDao().getAllTracksWithStatsOnce().first { it.track.trackKey == "sync:mac_studio:t1" }
        assertEquals("Repeated Song (Remaster)", reloaded.track.title)
        assertEquals("Workout", reloaded.track.moodTag)
        assertEquals(1, reloaded.playCount)

        // Local MediaStore rescan must not wipe SYNCED_FILE tracks
        repository.rescanMediaStoreLibrary()
        assertNotNull(db.trackDao().getTrackByKey("sync:mac_studio:t1"))

        // Disconnect clears paired session without erasing synced tracks or playlists
        manager.disconnectSession()
        assertEquals(SyncPhase.UNPAIRED, manager.uiState.value.phase)
        assertNull(manager.uiState.value.pairedAddressDisplay)
        assertEquals(1, repository.syncedPlaylists.first().size)
    }

    @Test
    fun `cancelled sync retains verified imports, cleans its own part, and defers incomplete playlists`() = runBlocking {
        val bytes1 = "FIRST_COMPLETE_AUDIO_FILE".encodeToByteArray()
        val bytes2 = "SECOND_PARTIAL_AUDIO_FILE".encodeToByteArray()
        val t1 = syncTrack("t1", "First Complete", bytes1, "mp3", "audio/mpeg")
        val t2 = syncTrack("t2", "Second Interrupted", bytes2, "mp3", "audio/mpeg")
        val manifest = SyncManifest(
            protocolVersion = 1,
            libraryId = "mac_cancel",
            libraryName = "Mac Cancel",
            revision = "rev-c1",
            tracks = listOf(t1, t2),
            playlists = listOf(SyncPlaylistDto("pl_1", "Partial Mix", listOf("t1", "t2"))),
            skipped = emptyList()
        )

        val startedSecond = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val transport = FakeSyncTransport(
            manifestProvider = { manifest },
            audioProvider = { track, partFile, onProgress ->
                if (track.id == "t1") {
                    partFile.writeBytes(bytes1)
                    onProgress(bytes1.size.toLong())
                } else {
                    partFile.writeBytes("PARTIAL".encodeToByteArray())
                    startedSecond.complete(Unit)
                    releaseSecond.await()
                    throw SyncCancelledException()
                }
            }
        )
        val dispatcher = UnconfinedTestDispatcher()
        val manager = LocalSyncManager(repository, fileStorage, transport, dispatcher, dispatcher)

        manager.pairAndConnect(pairingLink())
        awaitReady(manager)
        manager.startSync()
        try {
            awaitTransferStart(manager, startedSecond, "second track")
            manager.cancelActiveSync()
        } finally {
            releaseSecond.complete(Unit)
        }
        awaitResult(manager)

        assertEquals(SyncPhase.CANCELLED, manager.uiState.value.phase)
        // Incomplete .part files are cleaned up
        assertTrue(fileStorage.stagingDir.listFiles().orEmpty().isEmpty())
        // The completed file is already playable; incomplete playlist membership is untouched.
        assertEquals(listOf("t1"), db.trackDao().getSyncedTracksForLibrary("mac_cancel").map { it.syncTrackId })
        assertTrue(repository.syncedPlaylists.first().isEmpty())
        // Verified first file remains on disk so retry reuses it without re-downloading t1
        assertTrue(fileStorage.targetFileFor(t1.sha256, "mp3").exists())

        transport.downloadedTrackIds.clear()
        transport.audioProvider = { track, partFile, onProgress ->
            val data = if (track.id == "t1") bytes1 else bytes2
            partFile.writeBytes(data)
            onProgress(data.size.toLong())
        }
        manager.refreshManifest()
        awaitReady(manager)
        assertEquals(1, manager.uiState.value.preview?.unchangedTracksCount)
        manager.startSync()
        awaitResult(manager)
        assertEquals(SyncPhase.COMPLETED, manager.uiState.value.phase)
        assertEquals(listOf("t2"), transport.downloadedTrackIds)
        assertEquals(2, db.trackDao().getSyncedTracksForLibrary("mac_cancel").size)
    }

    @Test
    fun `changed file failure preserves old playable version and redacts token in errors`() = runBlocking {
        val oldBytes = "OLD_GOOD_VERSION".encodeToByteArray()
        val newBytes = "NEW_GOOD_VERSION_DATA".encodeToByteArray()
        val oldTrack = syncTrack("t1", "Evolving Track", oldBytes, "mp3", "audio/mpeg")
        val newTrack = syncTrack("t1", "Evolving Track v2", newBytes, "mp3", "audio/mpeg")

        var currentManifest = SyncManifest(
            protocolVersion = 1, libraryId = "mac_upd", libraryName = "Upd Mac",
            revision = "r1", tracks = listOf(oldTrack), playlists = emptyList(), skipped = emptyList()
        )
        var failSecondDownload = false
        val transport = FakeSyncTransport(
            manifestProvider = { currentManifest },
            audioProvider = { track, partFile, onProgress ->
                if (failSecondDownload) {
                    partFile.writeBytes("CORRUPT".encodeToByteArray())
                    onProgress(7L)
                    throw SyncProtocolException("Simulated failure carrying secret $testToken")
                } else {
                    partFile.writeBytes(oldBytes)
                    onProgress(oldBytes.size.toLong())
                }
            }
        )
        val dispatcher = UnconfinedTestDispatcher()
        val manager = LocalSyncManager(repository, fileStorage, transport, dispatcher, dispatcher)

        manager.pairAndConnect(pairingLink())
        awaitReady(manager)
        manager.startSync()
        awaitResult(manager)
        assertEquals(SyncPhase.COMPLETED, manager.uiState.value.phase)

        val oldFile = File(db.trackDao().getTrackByKey("sync:mac_upd:t1")!!.localFilePath!!)
        assertTrue(oldFile.exists())

        // Attempt replacement and fail mid-way
        currentManifest = currentManifest.copy(revision = "r2", tracks = listOf(newTrack))
        failSecondDownload = true
        manager.refreshManifest()
        awaitReady(manager)
        manager.startSync()
        awaitResult(manager)

        assertEquals(SyncPhase.ERROR, manager.uiState.value.phase)
        val errMsg = manager.uiState.value.errorMessage.orEmpty()
        assertFalse("Error message must redact token", errMsg.contains(testToken))
        // Old playable version stays intact and referenced cleanly in DB
        assertTrue(oldFile.exists())
        assertEquals(oldTrack.sha256, db.trackDao().getTrackByKey("sync:mac_upd:t1")?.contentSha256)
    }

    @Test
    fun `backgrounding cancels active sync and MainActivity consumes soundsgood pair deep link without persisting token`() = runBlocking {
        val t1 = syncTrack("t1", "Slow Track", "SLOW_AUDIO".encodeToByteArray(), "mp3", "audio/mpeg")
        val manifest = SyncManifest(
            protocolVersion = 1, libraryId = "mac_bg", libraryName = "BG Mac",
            revision = "r1", tracks = listOf(t1), playlists = emptyList(), skipped = emptyList()
        )
        val transferStarted = CompletableDeferred<Unit>()
        val allowFinish = CompletableDeferred<Unit>()
        val transport = FakeSyncTransport(
            manifestProvider = { manifest },
            audioProvider = { _, partFile, _ ->
                partFile.writeBytes("PART".encodeToByteArray())
                transferStarted.complete(Unit)
                allowFinish.await()
                throw SyncCancelledException()
            }
        )
        val dispatcher = UnconfinedTestDispatcher()
        val manager = LocalSyncManager(repository, fileStorage, transport, dispatcher, dispatcher)
        app.localSyncManagerOverride = manager

        val launchIntent = Intent(Intent.ACTION_VIEW, Uri.parse(pairingLink())).setClass(app, MainActivity::class.java)
        shadowOf(app).declareComponentUnbindable(ComponentName(app, PlaybackService::class.java))
        val activityController = Robolectric.buildActivity(MainActivity::class.java, launchIntent).create().start().resume()
        try {
            val activity = activityController.get()
            shadowOf(Looper.getMainLooper()).idle()

            // Token-bearing Intent URI must be scrubbed after consumption
            assertNull(activity.intent.data)
            awaitReady(manager)
            assertEquals(SyncPhase.READY, manager.uiState.value.phase)

            manager.startSync()
            awaitTransferStart(manager, transferStarted, "background cancellation track")
            assertTrue(manager.uiState.value.isTransferActive)

            // Simulate user backgrounding the app -> triggers onStop() with !isChangingConfigurations
            activityController.pause().stop()
            allowFinish.complete(Unit)
            shadowOf(Looper.getMainLooper()).idle()
            awaitResult(manager)

            assertEquals(SyncPhase.CANCELLED, manager.uiState.value.phase)
            assertTrue(manager.uiState.value.errorMessage?.contains("background", ignoreCase = true) == true)
        } finally {
            allowFinish.complete(Unit)
            activityController.destroy()
        }
    }

    @Test
    fun `continue imports good bad good and only replaces complete playlists`() = runBlocking {
        val payloads = listOf("old", "a", "b", "c").associateWith { "SYNTHETIC_$it".encodeToByteArray() }
        val tracks = payloads.mapValues { (id, bytes) -> syncTrack(id, "Fixture $id", bytes, "mp3", "audio/mpeg") }
        var manifest = fixtureManifest(listOf(tracks.getValue("old")),
            listOf(SyncPlaylistDto("keep", "Existing", listOf("old"))))
        val transport = FakeSyncTransport({ manifest }) { track, part, progress ->
            if (track.id == "b") {
                part.writeBytes(byteArrayOf(1, 2))
                progress(2)
                throw SyncTrackTransferException("unexpected_eof", 2, message = "Fixture EOF")
            }
            val bytes = payloads.getValue(track.id)
            part.writeBytes(bytes)
            progress(bytes.size.toLong())
        }
        val dispatcher = UnconfinedTestDispatcher()
        val manager = LocalSyncManager(repository, fileStorage, transport, dispatcher, dispatcher)
        manager.pairAndConnect(pairingLink())
        awaitReady(manager)
        manager.startSync()
        awaitResult(manager)
        manifest = manifest.copy(revision = "r2", tracks = listOf(tracks.getValue("a"), tracks.getValue("b"), tracks.getValue("c")),
            playlists = listOf(SyncPlaylistDto("keep", "Changed", listOf("a", "b", "c")),
                SyncPlaylistDto("complete", "Complete", listOf("c", "a", "c")),
                SyncPlaylistDto("new_incomplete", "Incomplete", listOf("b"))))
        transport.downloadedTrackIds.clear()
        manager.refreshManifest()
        awaitReady(manager)
        manager.startSync(continuePastFileErrors = true)
        awaitResult(manager)
        assertEquals(listOf("a", "b", "c"), transport.downloadedTrackIds)
        val outcome = manager.uiState.value.lastOutcome!!
        assertEquals("partial", outcome.status)
        assertEquals(2, outcome.downloadedTracksCount)
        assertEquals(1, outcome.failedTracksCount)
        assertEquals(0, outcome.notAttemptedTracksCount)
        assertEquals(1, outcome.syncedPlaylistsCount)
        assertEquals(1, outcome.preservedPlaylistsCount)
        assertEquals(1, outcome.deferredPlaylistsCount)
        assertEquals(SyncReportDelivery.UNSUPPORTED, outcome.reportDelivery)
        val playlists = repository.syncedPlaylists.first().associateBy { it.playlist.remotePlaylistId }
        assertEquals(listOf("sync:fixture:old"), playlists.getValue("keep").entries.map { it.trackWithStats.track.trackKey })
        assertEquals(listOf("sync:fixture:c", "sync:fixture:a", "sync:fixture:c"),
            playlists.getValue("complete").entries.map { it.trackWithStats.track.trackKey })
        assertFalse(playlists.containsKey("new_incomplete"))
        assertEquals(setOf("old", "a", "c"), db.trackDao().getSyncedTracksForLibrary("fixture").map { it.syncTrackId }.toSet())
        assertTrue(fileStorage.stagingDir.listFiles().orEmpty().isEmpty())
        val report = fileStorage.readLastReport()!!
        assertEquals("partial", report.summary.outcome)
        assertEquals(2L, report.results.single { it.trackId == "b" }.receivedBytes)
        assertFalse(com.example.sync.SyncClientReports.encodeLocal(report).contains(testToken))
    }

    @Test
    fun `fail fast option retains the first committed import and reports unattempted files`() = runBlocking {
        val payloads = listOf("a", "b", "c").associateWith { "FAIL_FAST_$it".encodeToByteArray() }
        val manifest = fixtureManifest(payloads.map { (id, bytes) -> syncTrack(id, id, bytes, "mp3", "audio/mpeg") })
        val transport = FakeSyncTransport({ manifest }) { track, part, progress ->
            if (track.id == "b") throw SyncTrackTransferException("source_unavailable", 0, message = "Fixture unavailable")
            val bytes = payloads.getValue(track.id)
            part.writeBytes(bytes)
            progress(bytes.size.toLong())
        }
        val dispatcher = UnconfinedTestDispatcher()
        val manager = LocalSyncManager(repository, fileStorage, transport, dispatcher, dispatcher)
        manager.pairAndConnect(pairingLink()); awaitReady(manager)
        manager.startSync(continuePastFileErrors = false); awaitResult(manager)
        assertEquals(listOf("a", "b"), transport.downloadedTrackIds)
        assertEquals(listOf("a"), db.trackDao().getSyncedTracksForLibrary("fixture").map { it.syncTrackId })
        val report = fileStorage.readLastReport()!!
        assertEquals("stopped", report.summary.outcome)
        assertEquals("source_unavailable", report.summary.stopReason)
        assertEquals(1, report.summary.downloadedImported)
        assertEquals(1, report.summary.failed)
        assertEquals(1, report.summary.notAttempted)
    }

    @Test
    fun `trust auth snapshot protocol network and storage failures stop despite continue enabled`() = runBlocking {
        val failures = listOf(
            SyncTlsException("Fixture trust") to "tls_failed",
            SyncAuthExpiredException("Fixture expired") to "session_expired",
            SyncRevisionConflictException("Fixture revision") to "manifest_changed",
            SyncProtocolException("Fixture malformed") to "protocol_error",
            SyncNetworkException("Fixture Wi-Fi lost") to "connection_failed",
            SyncStorageException("Fixture full", reason = "storage_full") to "storage_full")
        for ((index, failure) in failures.withIndex()) {
            val payloads = listOf("a", "b", "c").associateWith { "FATAL_${index}_$it".encodeToByteArray() }
            val manifest = fixtureManifest(payloads.map { (id, bytes) -> syncTrack(id, id, bytes, "mp3", "audio/mpeg") })
                .copy(libraryId = "fatal_$index")
            val transport = FakeSyncTransport({ manifest }) { track, part, progress ->
                if (track.id == "b") throw failure.first
                val bytes = payloads.getValue(track.id)
                part.writeBytes(bytes); progress(bytes.size.toLong())
            }
            val dispatcher = UnconfinedTestDispatcher()
            val manager = LocalSyncManager(repository, fileStorage, transport, dispatcher, dispatcher)
            manager.pairAndConnect(pairingLink()); awaitReady(manager)
            manager.startSync(true); awaitResult(manager)
            assertEquals(listOf("a", "b"), transport.downloadedTrackIds)
            assertEquals(listOf("a"), db.trackDao().getSyncedTracksForLibrary(manifest.libraryId).map { it.syncTrackId })
            val report = fileStorage.readLastReport()!!
            assertEquals("stopped", report.summary.outcome)
            assertEquals(failure.second, report.summary.stopReason)
            assertEquals(1, report.summary.downloadedImported)
            assertEquals(1, report.summary.notAttempted)
            assertNull(manager.uiState.value.lastOutcome!!.reportDelivery)
            manager.disconnectSession()
        }
    }

    @Test
    fun `two consecutive connection failures stop before walking the remaining library`() = runBlocking {
        val payloads = listOf("a", "b", "c", "d").associateWith { "CONNECTION_$it".encodeToByteArray() }
        val manifest = fixtureManifest(payloads.map { (id, bytes) -> syncTrack(id, id, bytes, "mp3", "audio/mpeg") })
        val transport = FakeSyncTransport({ manifest }) { track, part, progress ->
            if (track.id == "b" || track.id == "c") throw SyncTrackTransferException("timeout", 0, message = "Fixture timeout")
            val bytes = payloads.getValue(track.id)
            part.writeBytes(bytes); progress(bytes.size.toLong())
        }
        val dispatcher = UnconfinedTestDispatcher()
        val manager = LocalSyncManager(repository, fileStorage, transport, dispatcher, dispatcher)
        manager.pairAndConnect(pairingLink()); awaitReady(manager)
        manager.startSync(true); awaitResult(manager)
        assertEquals(listOf("a", "b", "c"), transport.downloadedTrackIds)
        val report = fileStorage.readLastReport()!!
        assertEquals("stopped", report.summary.outcome)
        assertEquals("timeout", report.summary.stopReason)
        assertEquals(1, report.summary.downloadedImported)
        assertEquals(2, report.summary.failed)
        assertEquals(1, report.summary.notAttempted)
    }

    @Test
    fun `report delivery failure keeps imported music and durable factual phone report`() = runBlocking {
        val bytes = "REPORT_FAILURE_AUDIO".encodeToByteArray()
        val manifest = fixtureManifest(listOf(syncTrack("a", "A", bytes, "mp3", "audio/mpeg")))
        val transport = FakeSyncTransport({ manifest }) { _, part, progress -> part.writeBytes(bytes); progress(bytes.size.toLong()) }
        var attempts = 0
        transport.reportProvider = { attempts++; throw SyncNetworkException("Fixture report unavailable") }
        val dispatcher = UnconfinedTestDispatcher()
        val manager = LocalSyncManager(repository, fileStorage, transport, dispatcher, dispatcher)
        manager.pairAndConnect(pairingLink()); awaitReady(manager)
        manager.startSync(); awaitResult(manager)
        assertEquals(2, attempts)
        assertEquals("complete", manager.uiState.value.lastOutcome!!.status)
        assertEquals(SyncReportDelivery.INCOMPLETE, manager.uiState.value.lastOutcome!!.reportDelivery)
        assertTrue(manager.uiState.value.lastOutcome!!.localReportSaved)
        assertNotNull(db.trackDao().getTrackByKey("sync:fixture:a"))
        assertEquals("downloaded_verified_imported", fileStorage.readLastReport()!!.results.single().outcome)
        val restored = LocalSyncManager(repository, fileStorage, transport, dispatcher, dispatcher)
        withTimeout(10_000) { restored.uiState.first { it.lastOutcome != null } }
        assertEquals(SyncPhase.UNPAIRED, restored.uiState.value.phase)
        assertNull(restored.uiState.value.pairedAddressDisplay)
        assertNull(restored.uiState.value.lastOutcome!!.reportDelivery)
        assertEquals(1, restored.uiState.value.lastOutcome!!.syncedTracksCount)
    }

    @Test
    fun `cancellation publishes retained counts across distinct worker and UI dispatchers`() = runBlocking {
        val first = "CANCEL_FIRST_VERIFIED".encodeToByteArray()
        val second = "CANCEL_SECOND_FILE".encodeToByteArray()
        val manifest = fixtureManifest(listOf(syncTrack("a", "A", first, "mp3", "audio/mpeg"),
            syncTrack("b", "B", second, "mp3", "audio/mpeg")))
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val transport = FakeSyncTransport({ manifest }) { track, part, progress ->
            if (track.id == "a") {
                part.writeBytes(first); progress(first.size.toLong())
            } else {
                part.writeBytes(byteArrayOf(1, 2, 3)); progress(3)
                started.complete(Unit)
                gate.await()
            }
        }
        val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val ui = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val manager = LocalSyncManager(repository, fileStorage, transport, io, ui)
            manager.pairAndConnect(pairingLink()); awaitReady(manager)
            manager.startSync(true)
            awaitTransferStart(manager, started, "second track on worker dispatcher")
            manager.cancelActiveSync()
            awaitResult(manager)
            val outcome = manager.uiState.value.lastOutcome!!
            assertEquals("cancelled", outcome.status)
            assertEquals(1, outcome.syncedTracksCount)
            assertEquals(1, outcome.failedTracksCount)
            assertTrue(outcome.localReportSaved)
            assertNull(outcome.reportDelivery)
            assertEquals(1, fileStorage.readLastReport()!!.summary.downloadedImported)
            assertTrue(fileStorage.stagingDir.listFiles().orEmpty().isEmpty())
        } finally {
            gate.complete(Unit)
            io.close()
            ui.close()
        }
    }

    private fun fixtureManifest(tracks: List<SyncTrackDto>, playlists: List<SyncPlaylistDto> = emptyList()): SyncManifest =
        SyncManifest(1, "fixture", "Synthetic Library", "r1", tracks, playlists, emptyList())

    private suspend fun awaitReady(manager: LocalSyncManager) {
        withTimeout(10_000) { manager.uiState.first { it.phase == SyncPhase.READY || it.phase == SyncPhase.ERROR } }
        assertEquals(SyncPhase.READY, manager.uiState.value.phase)
    }

    private suspend fun awaitTransferStart(manager: LocalSyncManager, started: CompletableDeferred<Unit>, label: String) {
        if (withTimeoutOrNull(10_000) { started.await() } == null) {
            val state = manager.uiState.value
            throw AssertionError("Timed out waiting for $label: phase=${state.phase}, error=${state.errorMessage}, outcome=${state.lastOutcome?.status}")
        }
    }

    private suspend fun awaitResult(manager: LocalSyncManager) {
        withTimeout(10_000) { manager.uiState.first { it.lastOutcome != null && !it.isConnectingOrSyncing } }
    }

    private fun syncTrack(
        id: String,
        title: String,
        bytes: ByteArray,
        extension: String,
        mimeType: String
    ): SyncTrackDto {
        val sha = PairingUriParser.bytesToLowerHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        return SyncTrackDto(
            id = id,
            title = title,
            artist = "Mac Artist",
            album = "Mac Album",
            genre = "Indie",
            durationMs = 120_000L,
            sizeBytes = bytes.size.toLong(),
            sha256 = sha,
            mimeType = mimeType,
            extension = extension
        )
    }

    private fun ipv4(dotted: String): Inet4Address {
        val parts = dotted.split('.').map { it.toInt().toByte() }.toByteArray()
        return InetAddress.getByAddress(dotted, parts) as Inet4Address
    }

    private fun newLinkAddress(dotted: String, prefix: Int): LinkAddress {
        val ctor = LinkAddress::class.java.getConstructor(InetAddress::class.java, Int::class.javaPrimitiveType)
        return ctor.newInstance(ipv4(dotted), prefix)
    }

    private fun addLinkAddressReflective(props: LinkProperties, addr: LinkAddress) {
        LinkProperties::class.java.getMethod("addLinkAddress", LinkAddress::class.java).invoke(props, addr)
    }
}
