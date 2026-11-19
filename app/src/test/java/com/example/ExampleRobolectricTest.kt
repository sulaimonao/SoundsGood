package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.ListeningEventType
import com.example.data.db.SmartPlaylistRuleEntity
import com.example.data.db.SmartSortOrder
import com.example.data.db.SoundsGoodDatabase
import com.example.data.db.TrackEntity
import com.example.data.db.TrackSourceType
import com.example.data.repository.LibraryRepository
import com.example.data.scanner.MediaStoreScanner
import com.example.domain.SmartPlaylistEvaluator
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    private lateinit var context: Context
    private lateinit var db: SoundsGoodDatabase
    private lateinit var repository: LibraryRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, SoundsGoodDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LibraryRepository(
            trackDao = db.trackDao(),
            statsDao = db.statsDao(),
            smartPlaylistDao = db.smartPlaylistDao(),
            playbackSnapshotDao = db.playbackSnapshotDao(),
            syncedPlaylistDao = db.syncedPlaylistDao(),
            scanner = MediaStoreScanner(context)
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `app name is SoundsGood`() {
        val appName = context.getString(R.string.app_name)
        assertEquals("SoundsGood", appName)
    }

    @Test
    fun `rescan updates tracks without duplicating or erasing listening stats`() = runTest {
        val initial = listOf(
            sampleTrack("ms:1", "Alpha", durationMs = 180_000L, dateAddedSec = 100L),
            sampleTrack("ms:2", "Beta", durationMs = 240_000L, dateAddedSec = 200L)
        )
        db.trackDao().syncMediaStoreTracks(initial)

        // Record play & skip stats for ms:1
        val recordedPlay = repository.recordListeningEventOnce(
            trackKey = "ms:1",
            eventType = ListeningEventType.PLAY,
            playbackSessionToken = "session-1",
            listenedMs = 32_000L,
            trackDurationMs = 180_000L
        )
        assertTrue(recordedPlay)

        // Duplicate callback with same session token must be ignored
        val duplicatePlay = repository.recordListeningEventOnce(
            trackKey = "ms:1",
            eventType = ListeningEventType.PLAY,
            playbackSessionToken = "session-1",
            listenedMs = 45_000L,
            trackDurationMs = 180_000L
        )
        assertFalse(duplicatePlay)

        // Rescan: ms:1 title updated, ms:2 removed, ms:3 added
        val rescanned = listOf(
            sampleTrack("ms:1", "Alpha (Remastered)", durationMs = 180_000L, dateAddedSec = 100L),
            sampleTrack("ms:3", "Gamma", durationMs = 200_000L, dateAddedSec = 300L)
        )
        db.trackDao().syncMediaStoreTracks(rescanned)

        val tracks = db.trackDao().getAllTracksWithStatsOnce()
        assertEquals(2, tracks.size)
        val alpha = tracks.first { it.track.trackKey == "ms:1" }
        assertEquals("Alpha (Remastered)", alpha.track.title)
        assertEquals(1, alpha.playCount)
        assertTrue(tracks.none { it.track.trackKey == "ms:2" })
    }

    @Test
    fun `playback snapshot persists and restores queue, position, repeat, and shuffle order`() = runTest {
        val tracks = listOf(
            sampleTrack("ms:10", "First", 120_000L, 10L),
            sampleTrack("ms:20", "Second", 150_000L, 20L),
            sampleTrack("ms:30", "Third", 190_000L, 30L)
        )
        db.trackDao().upsertTracks(tracks)

        repository.savePlaybackSnapshot(
            currentTrackKey = "ms:20",
            positionMs = 42_500L,
            repeatMode = 2,
            shuffleEnabled = true,
            queueTrackKeys = listOf("ms:10", "ms:20", "ms:30"),
            shuffledTrackKeys = listOf("ms:20", "ms:30", "ms:10"),
            activePlaylistLabel = "Deep Focus Cuts"
        )

        val restored = repository.loadPlaybackSnapshot()
        assertNotNull(restored)
        assertEquals("ms:20", restored!!.currentTrackKey)
        assertEquals(42_500L, restored.positionMs)
        assertEquals(2, restored.repeatMode)
        assertTrue(restored.shuffleEnabled)
        assertEquals(listOf("ms:20", "ms:30", "ms:10"), restored.shuffledOrderKeys)
        assertEquals("Deep Focus Cuts", restored.activePlaylistLabel)
    }

    @Test
    fun `smart playlists seed and evaluate editable rules accurately`() = runTest {
        repository.ensureDefaultSmartPlaylists()
        val count = db.smartPlaylistDao().countRules()
        assertTrue(count >= 4)

        val tracks = listOf(
            sampleTrack("ms:1", "Short Ambient", 90_000L, 10L, moodTag = "Focus"),
            sampleTrack("ms:2", "Long Symphony", 600_000L, 20L, moodTag = "Chill"),
            sampleTrack("ms:3", "Mid Groove", 210_000L, 30L, moodTag = "Focus")
        )
        db.trackDao().upsertTracks(tracks)

        val customRule = SmartPlaylistRuleEntity(
            name = "Focus 1-5m",
            minDurationSec = 60L,
            maxDurationSec = 300L,
            moodTagFilter = "Focus",
            sortOrder = SmartSortOrder.ALPHABETICAL.name
        )
        val withStats = db.trackDao().getAllTracksWithStatsOnce()
        val matched = SmartPlaylistEvaluator.evaluate(customRule, withStats)
        assertEquals(listOf("Mid Groove", "Short Ambient"), matched.map { it.track.title })
    }

    private fun sampleTrack(
        key: String,
        title: String,
        durationMs: Long,
        dateAddedSec: Long,
        moodTag: String? = null
    ) = TrackEntity(
        trackKey = key,
        mediaStoreId = key.removePrefix("ms:").toLongOrNull(),
        contentUri = "content://media/external/audio/media/${key.removePrefix("ms:")}",
        sourceType = TrackSourceType.MEDIA_STORE,
        title = title,
        artist = "Test Artist",
        album = "Test Album",
        genre = "Electronic",
        durationMs = durationMs,
        dateAddedSec = dateAddedSec,
        mimeType = "audio/mpeg",
        sizeBytes = 1_024_000L,
        moodTag = moodTag
    )
}
