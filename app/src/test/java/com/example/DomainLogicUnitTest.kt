package com.example

import androidx.media3.common.C
import com.example.data.db.BuiltInSmartPlaylistType
import com.example.data.db.SmartPlaylistRuleEntity
import com.example.data.db.SmartSortOrder
import com.example.data.db.TrackEntity
import com.example.data.db.TrackSourceType
import com.example.data.db.TrackWithStats
import com.example.domain.ListeningRules
import com.example.domain.ShuffleEngine
import com.example.domain.SmartPlaylistEvaluator
import com.example.playback.DeterministicShuffleOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DomainLogicUnitTest {

    @Test
    fun `fisher yates shuffle produces valid permutation with anchor first`() {
        val indices = ShuffleEngine.fisherYatesIndices(size = 8, anchorIndex = 5, random = Random(42))
        assertEquals(8, indices.size)
        assertEquals(5, indices[0])
        assertEquals((0 until 8).toSet(), indices.toSet())
    }

    @Test
    fun `deterministic shuffle order traverses consistently forward and backward`() {
        val order = DeterministicShuffleOrder(intArrayOf(2, 0, 3, 1))
        assertEquals(2, order.firstIndex)
        assertEquals(1, order.lastIndex)
        assertEquals(0, order.getNextIndex(2))
        assertEquals(3, order.getNextIndex(0))
        assertEquals(1, order.getNextIndex(3))
        assertEquals(C.INDEX_UNSET, order.getNextIndex(1))

        assertEquals(3, order.getPreviousIndex(1))
        assertEquals(0, order.getPreviousIndex(3))
        assertEquals(2, order.getPreviousIndex(0))
        assertEquals(C.INDEX_UNSET, order.getPreviousIndex(2))
    }

    @Test
    fun `listening rules enforce play threshold and single counting`() {
        assertEquals(30_000L, ListeningRules.playQualificationThresholdMs(240_000L))
        assertEquals(10_000L, ListeningRules.playQualificationThresholdMs(20_000L))
        assertEquals(5_000L, ListeningRules.playQualificationThresholdMs(6_000L))

        assertFalse(ListeningRules.shouldRecordPlay(false, 25_000L, 240_000L))
        assertTrue(ListeningRules.shouldRecordPlay(false, 30_000L, 240_000L))
        assertFalse(ListeningRules.shouldRecordPlay(true, 60_000L, 240_000L))

        assertFalse(ListeningRules.shouldRecordSkip(false, false, 1_000L, explicitUserTransition = true))
        assertTrue(ListeningRules.shouldRecordSkip(false, false, 3_000L, explicitUserTransition = true))
        assertFalse(ListeningRules.shouldRecordSkip(true, false, 3_000L, explicitUserTransition = true))
    }

    @Test
    fun `preference ranking scores plays and skips transparently`() {
        val trackA = TrackWithStats(
            track = mkTrack("ms:a", "Track A"),
            playCount = 4,
            skipCount = 2 // score = 4.0 - 1.0 = 3.0
        )
        val trackB = TrackWithStats(
            track = mkTrack("ms:b", "Track B"),
            playCount = 4,
            skipCount = 0 // score = 4.0
        )
        val rule = SmartPlaylistRuleEntity(
            name = "Most Played",
            isBuiltIn = true,
            builtInType = BuiltInSmartPlaylistType.MOST_PLAYED,
            minPlayCount = 1,
            sortOrder = SmartSortOrder.PREFERENCE_RANKED.name
        )
        val ranked = SmartPlaylistEvaluator.evaluate(rule, listOf(trackA, trackB))
        assertEquals(listOf("Track B", "Track A"), ranked.map { it.track.title })
    }

    private fun mkTrack(key: String, title: String) = TrackEntity(
        trackKey = key,
        contentUri = "content://test/$key",
        sourceType = TrackSourceType.MEDIA_STORE,
        title = title,
        artist = "Artist",
        album = "Album",
        durationMs = 180_000L,
        dateAddedSec = 1000L
    )
}
