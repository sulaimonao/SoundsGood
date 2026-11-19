package com.example

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.PlaybackSnapshotDao
import com.example.data.db.PlaybackSnapshotEntity
import com.example.data.db.SoundsGoodDatabase
import com.example.data.db.TrackEntity
import com.example.data.db.TrackSourceType
import com.example.data.repository.LibraryRepository
import com.example.data.scanner.MediaStoreScanner
import com.example.playback.MediaItemMapper
import com.example.playback.PlaybackBus
import com.example.playback.PlaybackService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaybackRegressionRobolectricTest {

    private lateinit var app: SoundsGoodApplication
    private lateinit var db: SoundsGoodDatabase

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, SoundsGoodDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        PlaybackBus.clearError()
    }

    @After
    fun tearDown() {
        app.libraryRepositoryOverride = null
        db.close()
        PlaybackBus.clearError()
    }

    private class GatedSnapshotDao(
        private val delegate: PlaybackSnapshotDao,
        private val gate: CompletableDeferred<Unit>
    ) : PlaybackSnapshotDao {
        override suspend fun getSnapshot(): PlaybackSnapshotEntity? {
            gate.await()
            return delegate.getSnapshot()
        }

        override fun observeSnapshot(): Flow<PlaybackSnapshotEntity?> = delegate.observeSnapshot()

        override suspend fun saveSnapshot(snapshot: PlaybackSnapshotEntity) = delegate.saveSnapshot(snapshot)
    }

    @Test
    fun `manifest disables allowBackup and backup XML rules exclude all local domains`() {
        val allowBackupFlag = app.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP
        assertEquals("android:allowBackup must be false", 0, allowBackupFlag)

        val legacyParser = app.resources.getXml(R.xml.backup_rules)
        val legacyDomains = extractExcludedDomains(legacyParser)
        val expectedDomains = setOf(
            "database", "file", "sharedpref", "external", "root",
            "device_database", "device_file", "device_sharedpref", "device_root"
        )
        assertTrue(
            "backup_rules.xml missing domains: ${expectedDomains - legacyDomains}",
            legacyDomains.containsAll(expectedDomains)
        )

        val extractionParser = app.resources.getXml(R.xml.data_extraction_rules)
        val cloudDomains = mutableSetOf<String>()
        val transferDomains = mutableSetOf<String>()
        var section: String? = null
        var event = extractionParser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                when (extractionParser.name) {
                    "cloud-backup" -> section = "cloud"
                    "device-transfer" -> section = "transfer"
                    "exclude" -> {
                        val domain = extractionParser.getAttributeValue(null, "domain")
                        if (domain != null) {
                            if (section == "cloud") cloudDomains.add(domain)
                            if (section == "transfer") transferDomains.add(domain)
                        }
                    }
                }
            }
            event = extractionParser.next()
        }
        assertTrue("cloud-backup missing exclusions", cloudDomains.containsAll(expectedDomains))
        assertTrue("device-transfer missing exclusions", transferDomains.containsAll(expectedDomains))
    }

    @Test
    fun `delayed snapshot A does not overwrite user playback of track B`(): Unit = runBlocking {
        val trackA = testTrack("ms:1", "Snapshot A")
        val trackB = testTrack("ms:2", "User Track B")
        db.trackDao().upsertTracks(listOf(trackA, trackB))
        db.playbackSnapshotDao().saveSnapshot(
            PlaybackSnapshotEntity(
                id = 1,
                currentTrackKey = trackA.trackKey,
                positionMs = 15_000L,
                repeatMode = Player.REPEAT_MODE_ALL,
                shuffleEnabled = false,
                queueTrackKeysJson = "[\"ms:1\"]",
                shuffledTrackKeysJson = "[]",
                activePlaylistLabel = "Old Mix"
            )
        )

        val gate = CompletableDeferred<Unit>()
        app.libraryRepositoryOverride = LibraryRepository(
            trackDao = db.trackDao(),
            statsDao = db.statsDao(),
            smartPlaylistDao = db.smartPlaylistDao(),
            playbackSnapshotDao = GatedSnapshotDao(db.playbackSnapshotDao(), gate),
            syncedPlaylistDao = db.syncedPlaylistDao(),
            scanner = MediaStoreScanner(app)
        )

        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            val service = controller.get()
            shadowOf(Looper.getMainLooper()).idle()

            // While snapshot A load is suspended behind gate, user selects and plays Track B
            val sessionPlayer = service.getSessionPlayerForTest()
            sessionPlayer.setMediaItems(mutableListOf(MediaItemMapper.toMediaItem(trackB)), 0, 0L)
            sessionPlayer.prepare()
            sessionPlayer.play()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals("ms:2", sessionPlayer.currentMediaItem?.mediaId)
            assertTrue(sessionPlayer.playWhenReady)

            // Now allow delayed snapshot A to finish loading and run its completion on main looper
            gate.complete(Unit)
            drainRestoreAndMainLooper(service)

            // Track B must remain selected and playing
            assertEquals(1, sessionPlayer.mediaItemCount)
            assertEquals("ms:2", sessionPlayer.currentMediaItem?.mediaId)
            assertTrue(sessionPlayer.playWhenReady)
        } finally {
            controller.destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
        Unit
    }

    @Test
    fun `delayed playback resumption does not overwrite user playback of track B`(): Unit = runBlocking {
        val trackA = testTrack("ms:1", "Snapshot A")
        val trackB = testTrack("ms:2", "User Track B")
        db.trackDao().upsertTracks(listOf(trackA, trackB))
        db.playbackSnapshotDao().saveSnapshot(
            PlaybackSnapshotEntity(
                id = 1,
                currentTrackKey = trackA.trackKey,
                positionMs = 10_000L,
                repeatMode = Player.REPEAT_MODE_OFF,
                shuffleEnabled = false,
                queueTrackKeysJson = "[\"ms:1\"]",
                shuffledTrackKeysJson = "[]"
            )
        )

        val gate = CompletableDeferred<Unit>()
        app.libraryRepositoryOverride = LibraryRepository(
            trackDao = db.trackDao(),
            statsDao = db.statsDao(),
            smartPlaylistDao = db.smartPlaylistDao(),
            playbackSnapshotDao = GatedSnapshotDao(db.playbackSnapshotDao(), gate),
            syncedPlaylistDao = db.syncedPlaylistDao(),
            scanner = MediaStoreScanner(app)
        )

        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            val service = controller.get()
            shadowOf(Looper.getMainLooper()).idle()

            // Trigger onPlaybackResumption while player is idle and snapshot is gated
            val resumptionFuture = service.triggerPlaybackResumptionForTest()
            shadowOf(Looper.getMainLooper()).idle()

            // User starts Track B before resumption completes
            val sessionPlayer = service.getSessionPlayerForTest()
            sessionPlayer.setMediaItems(mutableListOf(MediaItemMapper.toMediaItem(trackB)), 0, 0L)
            sessionPlayer.prepare()
            sessionPlayer.play()
            shadowOf(Looper.getMainLooper()).idle()

            // Resumption future should have been cancelled immediately by user command
            assertTrue(resumptionFuture.isDone)
            var failedWithCancel = false
            try {
                resumptionFuture.get()
            } catch (_: Exception) {
                failedWithCancel = true
            }
            assertTrue(failedWithCancel)

            // Release gate, drain looper, and confirm Track B stays active and playing
            gate.complete(Unit)
            drainRestoreAndMainLooper(service)
            assertEquals("ms:2", sessionPlayer.currentMediaItem?.mediaId)
            assertTrue(sessionPlayer.playWhenReady)
        } finally {
            controller.destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
        Unit
    }

    @Test
    fun `genuinely idle startup restores snapshot paused at saved position`(): Unit = runBlocking {
        val trackA = testTrack("ms:1", "Saved A")
        val trackB = testTrack("ms:2", "Saved B")
        db.trackDao().upsertTracks(listOf(trackA, trackB))
        db.playbackSnapshotDao().saveSnapshot(
            PlaybackSnapshotEntity(
                id = 1,
                currentTrackKey = trackB.trackKey,
                positionMs = 22_000L,
                repeatMode = Player.REPEAT_MODE_ALL,
                shuffleEnabled = false,
                queueTrackKeysJson = "[\"ms:1\",\"ms:2\"]",
                shuffledTrackKeysJson = "[]",
                activePlaylistLabel = "Evening"
            )
        )

        app.libraryRepositoryOverride = LibraryRepository(
            trackDao = db.trackDao(),
            statsDao = db.statsDao(),
            smartPlaylistDao = db.smartPlaylistDao(),
            playbackSnapshotDao = db.playbackSnapshotDao(),
            syncedPlaylistDao = db.syncedPlaylistDao(),
            scanner = MediaStoreScanner(app)
        )

        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            val service = controller.get()
            drainRestoreAndMainLooper(service)

            val exoPlayer = service.getExoPlayerForTest()
            assertEquals(2, exoPlayer.mediaItemCount)
            assertEquals("ms:2", exoPlayer.currentMediaItem?.mediaId)
            assertEquals(Player.REPEAT_MODE_ALL, exoPlayer.repeatMode)
            assertFalse(exoPlayer.playWhenReady)
        } finally {
            controller.destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
        Unit
    }

    @Test
    fun `broken to valid recovery skips broken item without counting skip`(): Unit = runBlocking {
        val broken = testTrack("ms:broken", "Broken Audio")
        val valid = testTrack("ms:valid", "Valid Audio")
        db.trackDao().upsertTracks(listOf(broken, valid))
        app.libraryRepositoryOverride = LibraryRepository(
            trackDao = db.trackDao(),
            statsDao = db.statsDao(),
            smartPlaylistDao = db.smartPlaylistDao(),
            playbackSnapshotDao = db.playbackSnapshotDao(),
            syncedPlaylistDao = db.syncedPlaylistDao(),
            scanner = MediaStoreScanner(app)
        )

        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            val service = controller.get()
            val sessionPlayer = service.getSessionPlayerForTest()
            val exoPlayer = service.getExoPlayerForTest()

            sessionPlayer.setMediaItems(
                mutableListOf(MediaItemMapper.toMediaItem(broken), MediaItemMapper.toMediaItem(valid)),
                0,
                0L
            )
            sessionPlayer.playWhenReady = true
            shadowOf(Looper.getMainLooper()).idle()

            // Simulate Media3 decoding error on index 0 ("Broken Audio")
            service.dispatchPlayerErrorForTest(
                PlaybackException(
                    "Corrupt container",
                    null,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                )
            )
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(1, exoPlayer.currentMediaItemIndex)
            assertEquals("ms:valid", exoPlayer.currentMediaItem?.mediaId)
            assertTrue(exoPlayer.playWhenReady)

            // Broken track error must NOT count as a user skip
            val brokenStats = db.statsDao().getStatsForTrack("ms:broken")
            assertEquals(0, brokenStats?.skipCount ?: 0)
            val brokenEntity = db.trackDao().getTrackByKey("ms:broken")
            assertTrue(brokenEntity?.isMissingOrUnreadable == true)
        } finally {
            controller.destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
        Unit
    }

    @Test
    fun `all broken repeat all queue terminates after bounded pass`(): Unit = runBlocking {
        val b1 = testTrack("ms:b1", "Broken 1")
        val b2 = testTrack("ms:b2", "Broken 2")
        val b3 = testTrack("ms:b3", "Broken 3")
        db.trackDao().upsertTracks(listOf(b1, b2, b3))
        app.libraryRepositoryOverride = LibraryRepository(
            trackDao = db.trackDao(),
            statsDao = db.statsDao(),
            smartPlaylistDao = db.smartPlaylistDao(),
            playbackSnapshotDao = db.playbackSnapshotDao(),
            syncedPlaylistDao = db.syncedPlaylistDao(),
            scanner = MediaStoreScanner(app)
        )

        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            val service = controller.get()
            val sessionPlayer = service.getSessionPlayerForTest()
            val exoPlayer = service.getExoPlayerForTest()

            sessionPlayer.setMediaItems(
                mutableListOf(
                    MediaItemMapper.toMediaItem(b1),
                    MediaItemMapper.toMediaItem(b2),
                    MediaItemMapper.toMediaItem(b3)
                ),
                0,
                0L
            )
            sessionPlayer.repeatMode = Player.REPEAT_MODE_ALL
            sessionPlayer.playWhenReady = true
            shadowOf(Looper.getMainLooper()).idle()

            var errorDispatches = 0
            while (exoPlayer.playWhenReady && errorDispatches < 10) {
                errorDispatches++
                service.dispatchPlayerErrorForTest(
                    PlaybackException(
                        "File not found",
                        null,
                        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                    )
                )
                shadowOf(Looper.getMainLooper()).idle()
            }

            // Must terminate in exactly 3 failures, never cycling forever under REPEAT_MODE_ALL
            assertEquals(3, errorDispatches)
            assertFalse(exoPlayer.playWhenReady)
            assertEquals(Player.STATE_IDLE, exoPlayer.playbackState)
            assertNotNull(PlaybackBus.playbackError.value)
            assertTrue(PlaybackBus.playbackError.value!!.contains("3 failed"))

            // None of the failed tracks should have skipCount incremented
            assertEquals(0, db.statsDao().getStatsForTrack("ms:b1")?.skipCount ?: 0)
            assertEquals(0, db.statsDao().getStatsForTrack("ms:b2")?.skipCount ?: 0)
            assertEquals(0, db.statsDao().getStatsForTrack("ms:b3")?.skipCount ?: 0)
        } finally {
            controller.destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
        Unit
    }

    private fun drainRestoreAndMainLooper(service: PlaybackService, timeoutMs: Long = 5_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (service.hasPendingRestoreForTest() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (service.hasPendingRestoreForTest()) {
                Thread.sleep(10L)
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun extractExcludedDomains(parser: android.content.res.XmlResourceParser): Set<String> {
        val result = mutableSetOf<String>()
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "exclude") {
                parser.getAttributeValue(null, "domain")?.let { result.add(it) }
            }
            event = parser.next()
        }
        return result
    }

    private fun testTrack(key: String, title: String) = TrackEntity(
        trackKey = key,
        mediaStoreId = null,
        contentUri = "content://soundsgood.test/audio/$key",
        sourceType = TrackSourceType.MEDIA_STORE,
        title = title,
        artist = "Test Artist",
        album = "Test Album",
        durationMs = 120_000L,
        dateAddedSec = 1000L,
        mimeType = "audio/mpeg",
        sizeBytes = 512_000L
    )
}
