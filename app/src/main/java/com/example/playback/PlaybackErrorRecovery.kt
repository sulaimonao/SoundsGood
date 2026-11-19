package com.example.playback

import androidx.media3.common.C
import androidx.media3.common.Player

/**
 * Bounded per-pass error recovery coordinator for queue playback failures.
 *
 * - Tracks failed queue occurrence indices (`0 until mediaItemCount`) within the current pass
 *   so no failed occurrence is retried in the same pass, even with duplicate track keys.
 * - Walks the active traversal order (normal or shuffled) via [nextIndexProvider].
 * - Normalizes `REPEAT_MODE_ONE` to `REPEAT_MODE_OFF` during recovery so a broken track never
 *   loops onto itself, and prevents `REPEAT_MODE_ALL` from cycling infinitely across an all-broken queue.
 */
class PlaybackErrorRecovery {

    sealed interface RecoveryDecision {
        data class AdvanceTo(val targetIndex: Int) : RecoveryDecision
        data class Terminate(val failedOccurrenceCount: Int) : RecoveryDecision
    }

    private val failedOccurrenceIndices = mutableSetOf<Int>()
    private var attemptsInPass = 0

    fun failedCountInPass(): Int = failedOccurrenceIndices.size

    fun reset() {
        failedOccurrenceIndices.clear()
        attemptsInPass = 0
    }

    fun recordFailureAndFindNextCandidate(
        failedIndex: Int,
        mediaItemCount: Int,
        repeatMode: Int,
        shuffleEnabled: Boolean,
        nextIndexProvider: (cursorIndex: Int, effectiveRepeatMode: Int, shuffle: Boolean) -> Int
    ): RecoveryDecision {
        if (mediaItemCount <= 0) {
            reset()
            return RecoveryDecision.Terminate(0)
        }

        if (failedIndex in 0 until mediaItemCount) {
            failedOccurrenceIndices.add(failedIndex)
        }

        // Hard bound: never try more transitions than the number of items in the queue
        if (failedOccurrenceIndices.size >= mediaItemCount || attemptsInPass >= mediaItemCount) {
            val count = failedOccurrenceIndices.size
            return RecoveryDecision.Terminate(count)
        }

        val effectiveRepeatMode = if (repeatMode == Player.REPEAT_MODE_ONE) {
            Player.REPEAT_MODE_OFF
        } else {
            repeatMode
        }

        var cursor = if (failedIndex in 0 until mediaItemCount) failedIndex else 0
        val visitedInWalk = HashSet<Int>(mediaItemCount)
        if (failedIndex in 0 until mediaItemCount) {
            visitedInWalk.add(failedIndex)
        } else if (0 !in failedOccurrenceIndices) {
            attemptsInPass++
            return RecoveryDecision.AdvanceTo(0)
        }

        for (step in 0 until mediaItemCount) {
            val candidate = nextIndexProvider(cursor, effectiveRepeatMode, shuffleEnabled)
            if (candidate == C.INDEX_UNSET || candidate !in 0 until mediaItemCount) {
                return RecoveryDecision.Terminate(failedOccurrenceIndices.size)
            }
            if (candidate !in failedOccurrenceIndices) {
                attemptsInPass++
                return RecoveryDecision.AdvanceTo(candidate)
            }
            if (!visitedInWalk.add(candidate)) {
                // Cycled back onto already-visited failed items (e.g., REPEAT_MODE_ALL)
                return RecoveryDecision.Terminate(failedOccurrenceIndices.size)
            }
            cursor = candidate
        }

        return RecoveryDecision.Terminate(failedOccurrenceIndices.size)
    }
}
