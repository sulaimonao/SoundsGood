package com.example

import androidx.media3.common.C
import androidx.media3.common.Player
import com.example.playback.PlaybackErrorRecovery
import com.example.playback.PlaybackRestoreCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackErrorRecoveryUnitTest {

    @Test
    fun `broken to valid advances once and resets on ready`() {
        val recovery = PlaybackErrorRecovery()
        // Queue: [0=broken, 1=valid]
        val decision = recovery.recordFailureAndFindNextCandidate(
            failedIndex = 0,
            mediaItemCount = 2,
            repeatMode = Player.REPEAT_MODE_OFF,
            shuffleEnabled = false
        ) { cursor, _, _ -> if (cursor == 0) 1 else C.INDEX_UNSET }

        assertEquals(PlaybackErrorRecovery.RecoveryDecision.AdvanceTo(1), decision)
        assertEquals(1, recovery.failedCountInPass())

        recovery.reset()
        assertEquals(0, recovery.failedCountInPass())
    }

    @Test
    fun `all broken queue with repeat all terminates cleanly after one pass`() {
        val recovery = PlaybackErrorRecovery()
        val count = 3
        val nextProvider: (Int, Int, Boolean) -> Int = { cursor, repeat, _ ->
            if (repeat == Player.REPEAT_MODE_ALL) (cursor + 1) % count
            else if (cursor + 1 < count) cursor + 1 else C.INDEX_UNSET
        }

        val d0 = recovery.recordFailureAndFindNextCandidate(0, count, Player.REPEAT_MODE_ALL, false, nextProvider)
        assertEquals(PlaybackErrorRecovery.RecoveryDecision.AdvanceTo(1), d0)

        val d1 = recovery.recordFailureAndFindNextCandidate(1, count, Player.REPEAT_MODE_ALL, false, nextProvider)
        assertEquals(PlaybackErrorRecovery.RecoveryDecision.AdvanceTo(2), d1)

        val d2 = recovery.recordFailureAndFindNextCandidate(2, count, Player.REPEAT_MODE_ALL, false, nextProvider)
        assertEquals(PlaybackErrorRecovery.RecoveryDecision.Terminate(3), d2)
    }

    @Test
    fun `shuffle order skips failed occurrences and finds valid candidate across wrap`() {
        val recovery = PlaybackErrorRecovery()
        // Shuffle order: [2, 0, 3, 1]; item 1 is valid, others broken; starts at index 2
        val shuffleOrder = listOf(2, 0, 3, 1)
        val nextProvider: (Int, Int, Boolean) -> Int = { cursor, repeat, _ ->
            val pos = shuffleOrder.indexOf(cursor)
            if (pos + 1 < shuffleOrder.size) shuffleOrder[pos + 1]
            else if (repeat == Player.REPEAT_MODE_ALL) shuffleOrder[0]
            else C.INDEX_UNSET
        }

        val d1 = recovery.recordFailureAndFindNextCandidate(2, 4, Player.REPEAT_MODE_ALL, true, nextProvider)
        assertEquals(PlaybackErrorRecovery.RecoveryDecision.AdvanceTo(0), d1)

        val d2 = recovery.recordFailureAndFindNextCandidate(0, 4, Player.REPEAT_MODE_ALL, true, nextProvider)
        assertEquals(PlaybackErrorRecovery.RecoveryDecision.AdvanceTo(3), d2)

        val d3 = recovery.recordFailureAndFindNextCandidate(3, 4, Player.REPEAT_MODE_ALL, true, nextProvider)
        assertEquals(PlaybackErrorRecovery.RecoveryDecision.AdvanceTo(1), d3)
    }

    @Test
    fun `duplicate queue occurrences are tracked by index and never retried in same pass`() {
        val recovery = PlaybackErrorRecovery()
        // Suppose queue has duplicate occurrences of same track at indices 0 and 2: [A, B, A]
        val nextProvider: (Int, Int, Boolean) -> Int = { cursor, repeat, _ ->
            if (repeat == Player.REPEAT_MODE_ALL) (cursor + 1) % 3
            else if (cursor + 1 < 3) cursor + 1 else C.INDEX_UNSET
        }

        val d0 = recovery.recordFailureAndFindNextCandidate(0, 3, Player.REPEAT_MODE_ALL, false, nextProvider)
        assertEquals(PlaybackErrorRecovery.RecoveryDecision.AdvanceTo(1), d0)

        val d1 = recovery.recordFailureAndFindNextCandidate(1, 3, Player.REPEAT_MODE_ALL, false, nextProvider)
        assertEquals(PlaybackErrorRecovery.RecoveryDecision.AdvanceTo(2), d1)

        val d2 = recovery.recordFailureAndFindNextCandidate(2, 3, Player.REPEAT_MODE_ALL, false, nextProvider)
        assertEquals(PlaybackErrorRecovery.RecoveryDecision.Terminate(3), d2)
    }

    @Test
    fun `repeat one normalizes during recovery instead of looping on broken item`() {
        val recovery = PlaybackErrorRecovery()
        val decision = recovery.recordFailureAndFindNextCandidate(
            failedIndex = 0,
            mediaItemCount = 2,
            repeatMode = Player.REPEAT_MODE_ONE,
            shuffleEnabled = false
        ) { cursor, effectiveRepeat, _ ->
            // Must receive REPEAT_MODE_OFF rather than REPEAT_MODE_ONE
            assertEquals(Player.REPEAT_MODE_OFF, effectiveRepeat)
            if (cursor == 0) 1 else C.INDEX_UNSET
        }
        assertEquals(PlaybackErrorRecovery.RecoveryDecision.AdvanceTo(1), decision)
    }

    @Test
    fun `restore coordinator rejects stale generation after user command`() {
        val coordinator = PlaybackRestoreCoordinator()
        val gen = coordinator.userCommandGeneration
        assertTrue(
            coordinator.canApplyRestore(
                capturedGeneration = gen,
                mediaItemCount = 0,
                playWhenReady = false,
                isPlaying = false,
                playbackState = Player.STATE_IDLE
            )
        )

        coordinator.invalidateForUserCommand()
        assertFalse(
            coordinator.canApplyRestore(
                capturedGeneration = gen,
                mediaItemCount = 0,
                playWhenReady = false,
                isPlaying = false,
                playbackState = Player.STATE_IDLE
            )
        )
    }
}
