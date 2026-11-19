package com.example.playback

import androidx.media3.common.Player
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.Job
import java.util.concurrent.CancellationException

/**
 * Coordinates startup snapshot restoration and MediaSession playback resumption on the player
 * thread so delayed asynchronous loads can never overwrite newer user queue or playback intent.
 */
class PlaybackRestoreCoordinator {

    var userCommandGeneration: Long = 0L
        private set

    private var startupRestoreJob: Job? = null
    private var resumptionJob: Job? = null
    private var pendingResumptionFuture: SettableFuture<*>? = null

    fun registerStartupRestoreJob(job: Job): Long {
        startupRestoreJob?.cancel()
        startupRestoreJob = job
        return userCommandGeneration
    }

    fun registerResumption(job: Job, future: SettableFuture<*>): Long {
        resumptionJob?.cancel()
        pendingResumptionFuture?.let { prev ->
            if (!prev.isDone) {
                prev.setException(CancellationException("Superseded by newer playback resumption request"))
            }
        }
        resumptionJob = job
        pendingResumptionFuture = future
        return userCommandGeneration
    }

    /**
     * Called synchronously on the player thread whenever any user queue or playback command
     * arrives. Increments the generation token and cancels in-flight restore/resumption work.
     */
    fun invalidateForUserCommand() {
        userCommandGeneration++
        startupRestoreJob?.cancel()
        startupRestoreJob = null
        resumptionJob?.cancel()
        resumptionJob = null
        pendingResumptionFuture?.let { future ->
            if (!future.isDone) {
                future.setException(CancellationException("Playback restore cancelled by user command"))
            }
        }
        pendingResumptionFuture = null
    }

    fun clearCompletedStartupJob(job: Job) {
        if (startupRestoreJob === job) {
            startupRestoreJob = null
        }
    }

    fun clearCompletedResumption(job: Job) {
        if (resumptionJob === job) {
            resumptionJob = null
            pendingResumptionFuture = null
        }
    }

    fun hasPendingRestore(): Boolean {
        val startupActive = startupRestoreJob?.let { !it.isCompleted } == true
        val resumptionActive = resumptionJob?.let { !it.isCompleted } == true
        return startupActive || resumptionActive
    }

    /**
     * Synchronous check performed on the player thread with no suspension between validation
     * and player mutation.
     */
    fun canApplyRestore(
        capturedGeneration: Long,
        mediaItemCount: Int,
        playWhenReady: Boolean,
        isPlaying: Boolean,
        playbackState: Int
    ): Boolean {
        if (capturedGeneration != userCommandGeneration) return false
        if (mediaItemCount > 0) return false
        if (playWhenReady || isPlaying) return false
        if (playbackState != Player.STATE_IDLE) return false
        return true
    }
}
