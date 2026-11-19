package com.example.domain

/**
 * Pure, testable counting rules for play and skip events.
 *
 * Play threshold:
 * - A track session counts as ONE PLAY when active playing time (excluding pauses and seeks)
 *   reaches `min(30_000ms, max(5_000ms, durationMs / 2))`, or when the track completes naturally.
 * - If `durationMs <= 0`, defaults to 10_000ms.
 *
 * Skip threshold:
 * - A track session counts as ONE SKIP when the user transitions away before qualifying as a PLAY,
 *   provided at least 1_500ms of active playback elapsed (prevents rapid multi-skip scrubbing
 *   from inflating skip counts).
 */
object ListeningRules {
    const val PLAY_CAP_MS = 30_000L
    const val PLAY_FLOOR_MS = 5_000L
    const val UNKNOWN_DURATION_PLAY_MS = 10_000L
    const val MIN_SKIP_ELAPSED_MS = 1_500L

    fun playQualificationThresholdMs(durationMs: Long): Long {
        if (durationMs <= 0L) return UNKNOWN_DURATION_PLAY_MS
        val halfDuration = durationMs / 2L
        return minOf(PLAY_CAP_MS, maxOf(PLAY_FLOOR_MS, halfDuration))
    }

    fun shouldRecordPlay(
        alreadyRecordedPlay: Boolean,
        accumulatedPlayMs: Long,
        durationMs: Long,
        trackEndedNaturally: Boolean = false
    ): Boolean {
        if (alreadyRecordedPlay) return false
        if (trackEndedNaturally && accumulatedPlayMs >= MIN_SKIP_ELAPSED_MS) return true
        return accumulatedPlayMs >= playQualificationThresholdMs(durationMs)
    }

    fun shouldRecordSkip(
        alreadyRecordedPlay: Boolean,
        alreadyRecordedSkip: Boolean,
        accumulatedPlayMs: Long,
        explicitUserTransition: Boolean
    ): Boolean {
        if (!explicitUserTransition) return false
        if (alreadyRecordedPlay || alreadyRecordedSkip) return false
        return accumulatedPlayMs >= MIN_SKIP_ELAPSED_MS
    }
}
