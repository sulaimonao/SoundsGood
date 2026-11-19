package com.example.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.MainActivity
import com.example.R
import com.example.SoundsGoodApplication
import com.example.data.db.ListeningEventType
import com.example.data.repository.LibraryRepository
import com.example.domain.ListeningRules
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.CancellationException

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var repository: LibraryRepository
    private lateinit var exoPlayer: ExoPlayer
    private var mediaSession: MediaSession? = null

    private val restoreCoordinator = PlaybackRestoreCoordinator()
    private val errorRecovery = PlaybackErrorRecovery()

    private var currentShuffleOrder: DeterministicShuffleOrder =
        DeterministicShuffleOrder(IntArray(0))

    // Single-count listening session state
    private data class ActiveTrackSession(
        val trackKey: String,
        val sessionToken: String,
        val durationMs: Long,
        var accumulatedPlayMs: Long = 0L,
        var hasRecordedPlay: Boolean = false,
        var hasRecordedSkip: Boolean = false
    )

    private var activeSession: ActiveTrackSession? = null
    private var tickerJob: Job? = null
    private var lastTickWallMs: Long = 0L
    private var lastSnapshotSaveWallMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        repository = (application as SoundsGoodApplication).libraryRepository

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        exoPlayer.addListener(playerListener)

        val sessionPlayer = object : ForwardingPlayer(exoPlayer) {
            override fun play() {
                restoreCoordinator.invalidateForUserCommand()
                super.play()
            }

            override fun setPlayWhenReady(playWhenReady: Boolean) {
                if (playWhenReady) {
                    restoreCoordinator.invalidateForUserCommand()
                }
                super.setPlayWhenReady(playWhenReady)
            }

            override fun prepare() {
                restoreCoordinator.invalidateForUserCommand()
                super.prepare()
            }

            override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
                restoreCoordinator.invalidateForUserCommand()
                if (shuffleModeEnabled) {
                    applyFreshFisherYatesShuffleOrder()
                }
                super.setShuffleModeEnabled(shuffleModeEnabled)
                persistSnapshotAsync()
            }

            override fun seekTo(positionMs: Long) {
                restoreCoordinator.invalidateForUserCommand()
                super.seekTo(positionMs)
            }

            override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
                restoreCoordinator.invalidateForUserCommand()
                errorRecovery.reset()
                super.seekTo(mediaItemIndex, positionMs)
            }

            override fun seekToDefaultPosition() {
                restoreCoordinator.invalidateForUserCommand()
                super.seekToDefaultPosition()
            }

            override fun seekToDefaultPosition(mediaItemIndex: Int) {
                restoreCoordinator.invalidateForUserCommand()
                errorRecovery.reset()
                super.seekToDefaultPosition(mediaItemIndex)
            }

            override fun seekToNext() {
                restoreCoordinator.invalidateForUserCommand()
                errorRecovery.reset()
                PlaybackBus.markExplicitUserSkip()
                super.seekToNext()
            }

            override fun seekToNextMediaItem() {
                restoreCoordinator.invalidateForUserCommand()
                errorRecovery.reset()
                PlaybackBus.markExplicitUserSkip()
                super.seekToNextMediaItem()
            }

            override fun seekToPrevious() {
                restoreCoordinator.invalidateForUserCommand()
                errorRecovery.reset()
                PlaybackBus.markExplicitUserSkip()
                super.seekToPrevious()
            }

            override fun seekToPreviousMediaItem() {
                restoreCoordinator.invalidateForUserCommand()
                errorRecovery.reset()
                PlaybackBus.markExplicitUserSkip()
                super.seekToPreviousMediaItem()
            }

            override fun setMediaItem(mediaItem: MediaItem) {
                onUserReplacingQueue()
                super.setMediaItem(mediaItem)
                onAfterQueueSet(startIndex = 0)
            }

            override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
                onUserReplacingQueue()
                super.setMediaItem(mediaItem, startPositionMs)
                onAfterQueueSet(startIndex = 0)
            }

            override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
                onUserReplacingQueue()
                super.setMediaItem(mediaItem, resetPosition)
                onAfterQueueSet(startIndex = 0)
            }

            override fun setMediaItems(mediaItems: MutableList<MediaItem>) {
                onUserReplacingQueue()
                super.setMediaItems(mediaItems)
                onAfterQueueSet(startIndex = 0)
            }

            override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
                onUserReplacingQueue()
                super.setMediaItems(mediaItems, resetPosition)
                onAfterQueueSet(startIndex = 0)
            }

            override fun setMediaItems(
                mediaItems: MutableList<MediaItem>,
                startIndex: Int,
                startPositionMs: Long
            ) {
                onUserReplacingQueue()
                super.setMediaItems(mediaItems, startIndex, startPositionMs)
                onAfterQueueSet(startIndex = startIndex)
            }

            override fun addMediaItem(mediaItem: MediaItem) {
                restoreCoordinator.invalidateForUserCommand()
                super.addMediaItem(mediaItem)
                if (exoPlayer.shuffleModeEnabled) applyFreshFisherYatesShuffleOrder()
                persistSnapshotAsync()
            }

            override fun addMediaItem(index: Int, mediaItem: MediaItem) {
                restoreCoordinator.invalidateForUserCommand()
                super.addMediaItem(index, mediaItem)
                if (exoPlayer.shuffleModeEnabled) applyFreshFisherYatesShuffleOrder()
                persistSnapshotAsync()
            }

            override fun addMediaItems(mediaItems: MutableList<MediaItem>) {
                restoreCoordinator.invalidateForUserCommand()
                super.addMediaItems(mediaItems)
                if (exoPlayer.shuffleModeEnabled) applyFreshFisherYatesShuffleOrder()
                persistSnapshotAsync()
            }

            override fun addMediaItems(index: Int, mediaItems: MutableList<MediaItem>) {
                restoreCoordinator.invalidateForUserCommand()
                super.addMediaItems(index, mediaItems)
                if (exoPlayer.shuffleModeEnabled) applyFreshFisherYatesShuffleOrder()
                persistSnapshotAsync()
            }

            override fun removeMediaItem(index: Int) {
                restoreCoordinator.invalidateForUserCommand()
                errorRecovery.reset()
                super.removeMediaItem(index)
                persistSnapshotAsync()
            }

            override fun removeMediaItems(fromIndex: Int, toIndex: Int) {
                restoreCoordinator.invalidateForUserCommand()
                errorRecovery.reset()
                super.removeMediaItems(fromIndex, toIndex)
                persistSnapshotAsync()
            }

            override fun clearMediaItems() {
                restoreCoordinator.invalidateForUserCommand()
                errorRecovery.reset()
                super.clearMediaItems()
                persistSnapshotAsync()
            }

            override fun setRepeatMode(repeatMode: Int) {
                restoreCoordinator.invalidateForUserCommand()
                super.setRepeatMode(repeatMode)
                persistSnapshotAsync()
            }

            private fun onUserReplacingQueue() {
                restoreCoordinator.invalidateForUserCommand()
                errorRecovery.reset()
                if (exoPlayer.mediaItemCount > 0 && exoPlayer.isPlaying) {
                    PlaybackBus.markExplicitUserSkip()
                }
            }

            private fun onAfterQueueSet(startIndex: Int) {
                if (exoPlayer.shuffleModeEnabled) {
                    applyFreshFisherYatesShuffleOrder(anchorOverride = startIndex)
                }
                persistSnapshotAsync()
            }
        }

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelName(R.string.notification_channel_name)
                .build()
        )

        mediaSession = MediaSession.Builder(this, sessionPlayer)
            .setSessionActivity(sessionActivityPendingIntent)
            .setCallback(SessionCallback())
            .build()

        restoreSnapshotOnStartup()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            persistSnapshotBlockingFriendly()
            stopSelf()
        }
    }

    override fun onDestroy() {
        tickerJob?.cancel()
        finalizeActiveSession(explicitUserTransition = false, trackEndedNaturally = false)
        persistSnapshotBlockingFriendly()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    @VisibleForTesting
    internal fun getSessionPlayerForTest(): Player = mediaSession?.player ?: exoPlayer

    @VisibleForTesting
    internal fun getExoPlayerForTest(): ExoPlayer = exoPlayer

    @VisibleForTesting
    internal fun triggerPlaybackResumptionForTest(): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        return performPlaybackResumptionAsync()
    }

    @VisibleForTesting
    internal fun dispatchPlayerErrorForTest(error: PlaybackException) {
        playerListener.onPlayerError(error)
    }

    @VisibleForTesting
    internal fun hasPendingRestoreForTest(): Boolean = restoreCoordinator.hasPendingRestore()

    private inner class SessionCallback : MediaSession.Callback {
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            return performPlaybackResumptionAsync()
        }
    }

    private fun performPlaybackResumptionAsync(): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        // Synchronously reject if user/player already has active queue or playback intent
        if (exoPlayer.mediaItemCount > 0 || exoPlayer.playWhenReady || exoPlayer.isPlaying) {
            future.setException(CancellationException("Player already has active queue or user playback"))
            return future
        }

        lateinit var job: Job
        var capturedGeneration = 0L
        job = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                val restored = repository.loadPlaybackSnapshot()
                ensureActive()
                // Re-check on the player thread immediately before mutating or completing future
                if (!restoreCoordinator.canApplyRestore(
                        capturedGeneration = capturedGeneration,
                        mediaItemCount = exoPlayer.mediaItemCount,
                        playWhenReady = exoPlayer.playWhenReady,
                        isPlaying = exoPlayer.isPlaying,
                        playbackState = exoPlayer.playbackState
                    )
                ) {
                    future.setException(CancellationException("Stale playback resumption superseded by user intent"))
                    return@launch
                }

                if (restored != null && restored.tracks.isNotEmpty()) {
                    val items = restored.tracks.map { MediaItemMapper.toMediaItem(it) }
                    val startIndex = restored.currentTrackKey?.let { key ->
                        restored.tracks.indexOfFirst { it.trackKey == key }.takeIf { it >= 0 }
                    } ?: 0
                    exoPlayer.repeatMode = restored.repeatMode
                    if (restored.shuffleEnabled) {
                        val order = DeterministicShuffleOrder.fromOrderedTrackKeys(
                            queueKeys = restored.tracks.map { it.trackKey },
                            persistedShuffledKeys = restored.shuffledOrderKeys,
                            anchorIndex = startIndex
                        )
                        currentShuffleOrder = order
                        exoPlayer.setShuffleOrder(order)
                        exoPlayer.shuffleModeEnabled = true
                        publishShuffledKeys(restored.tracks.map { it.trackKey }, order)
                    }
                    PlaybackBus.setActivePlaylistLabel(restored.activePlaylistLabel ?: "Library")
                    future.set(
                        MediaSession.MediaItemsWithStartPosition(
                            items,
                            startIndex,
                            restored.positionMs
                        )
                    )
                } else {
                    future.setException(IllegalStateException("No persisted queue to resume"))
                }
            } catch (e: Exception) {
                if (!future.isDone) {
                    future.setException(e)
                }
            } finally {
                restoreCoordinator.clearCompletedResumption(job)
            }
        }
        capturedGeneration = restoreCoordinator.registerResumption(job, future)
        job.start()
        return future
    }

    private fun restoreSnapshotOnStartup() {
        if (exoPlayer.mediaItemCount > 0 || exoPlayer.playWhenReady || exoPlayer.isPlaying) {
            return
        }
        lateinit var job: Job
        var capturedGeneration = 0L
        job = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                val restored = repository.loadPlaybackSnapshot() ?: return@launch
                ensureActive()
                // Synchronous player-thread check with zero suspension between validation and mutation
                if (!restoreCoordinator.canApplyRestore(
                        capturedGeneration = capturedGeneration,
                        mediaItemCount = exoPlayer.mediaItemCount,
                        playWhenReady = exoPlayer.playWhenReady,
                        isPlaying = exoPlayer.isPlaying,
                        playbackState = exoPlayer.playbackState
                    )
                ) {
                    return@launch
                }
                if (restored.tracks.isEmpty()) return@launch

                val items = restored.tracks.map { MediaItemMapper.toMediaItem(it) }
                val startIndex = restored.currentTrackKey?.let { key ->
                    restored.tracks.indexOfFirst { it.trackKey == key }.takeIf { it >= 0 }
                } ?: 0

                exoPlayer.setMediaItems(items, startIndex, restored.positionMs)
                exoPlayer.repeatMode = restored.repeatMode
                if (restored.shuffleEnabled) {
                    val order = DeterministicShuffleOrder.fromOrderedTrackKeys(
                        queueKeys = restored.tracks.map { it.trackKey },
                        persistedShuffledKeys = restored.shuffledOrderKeys,
                        anchorIndex = startIndex
                    )
                    currentShuffleOrder = order
                    exoPlayer.setShuffleOrder(order)
                    exoPlayer.shuffleModeEnabled = true
                    publishShuffledKeys(restored.tracks.map { it.trackKey }, order)
                }
                exoPlayer.playWhenReady = false
                exoPlayer.prepare()
                PlaybackBus.setActivePlaylistLabel(restored.activePlaylistLabel ?: "Library")
                startFreshSessionForCurrentItem()
            } finally {
                restoreCoordinator.clearCompletedStartupJob(job)
            }
        }
        capturedGeneration = restoreCoordinator.registerStartupRestoreJob(job)
        job.start()
    }

    private fun applyFreshFisherYatesShuffleOrder(anchorOverride: Int? = null) {
        val count = exoPlayer.mediaItemCount
        if (count <= 0) {
            currentShuffleOrder = DeterministicShuffleOrder(IntArray(0))
            PlaybackBus.setShuffledOrderKeys(emptyList())
            return
        }
        val anchor = anchorOverride ?: exoPlayer.currentMediaItemIndex
        val order = DeterministicShuffleOrder.createFisherYates(count, anchor)
        currentShuffleOrder = order
        exoPlayer.setShuffleOrder(order)
        val keys = (0 until count).mapNotNull { MediaItemMapper.extractTrackKey(exoPlayer.getMediaItemAt(it)) }
        publishShuffledKeys(keys, order)
    }

    private fun publishShuffledKeys(queueKeys: List<String>, order: DeterministicShuffleOrder) {
        val orderedKeys = order.asList().mapNotNull { idx -> queueKeys.getOrNull(idx) }
        PlaybackBus.setShuffledOrderKeys(orderedKeys)
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val endedNaturally = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
            val explicitUserTransition =
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED ||
                    PlaybackBus.consumeExplicitUserSkip()

            finalizeActiveSession(
                explicitUserTransition = explicitUserTransition && !endedNaturally,
                trackEndedNaturally = endedNaturally
            )
            startFreshSessionForCurrentItem()
            persistSnapshotAsync()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                errorRecovery.reset()
                if (activeSession == null) {
                    startFreshSessionForCurrentItem()
                }
                startTicker()
            } else {
                accumulateSinceLastTick()
                stopTicker()
                persistSnapshotAsync()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                errorRecovery.reset()
            } else if (playbackState == Player.STATE_ENDED) {
                accumulateSinceLastTick()
                finalizeActiveSession(explicitUserTransition = false, trackEndedNaturally = true)
                stopTicker()
                persistSnapshotAsync()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val failedIndex = exoPlayer.currentMediaItemIndex
            val failedItem = exoPlayer.currentMediaItem
            val failedKey = MediaItemMapper.extractTrackKey(failedItem)
            val failedTitle = failedItem?.mediaMetadata?.title?.toString() ?: "Audio track"
            val wasPlayWhenReady = exoPlayer.playWhenReady
            val readableMessage = classifyPlaybackError(error, failedTitle)

            PlaybackBus.postError(readableMessage)
            if (failedKey != null) {
                serviceScope.launch {
                    repository.markTrackError(failedKey, readableMessage)
                }
            }
            // Clear active session without penalizing skip stats on codec/IO failure
            activeSession = null
            stopTicker()

            val decision = errorRecovery.recordFailureAndFindNextCandidate(
                failedIndex = failedIndex,
                mediaItemCount = exoPlayer.mediaItemCount,
                repeatMode = exoPlayer.repeatMode,
                shuffleEnabled = exoPlayer.shuffleModeEnabled
            ) { cursor, effectiveRepeat, shuffle ->
                val timeline = exoPlayer.currentTimeline
                if (timeline.isEmpty || cursor !in 0 until exoPlayer.mediaItemCount) {
                    C.INDEX_UNSET
                } else {
                    timeline.getNextWindowIndex(cursor, effectiveRepeat, shuffle)
                }
            }

            when (decision) {
                is PlaybackErrorRecovery.RecoveryDecision.AdvanceTo -> {
                    exoPlayer.seekToDefaultPosition(decision.targetIndex)
                    exoPlayer.playWhenReady = wasPlayWhenReady
                    exoPlayer.prepare()
                }
                is PlaybackErrorRecovery.RecoveryDecision.Terminate -> {
                    errorRecovery.reset()
                    exoPlayer.playWhenReady = false
                    exoPlayer.stop()
                    val summary = if (decision.failedOccurrenceCount > 1) {
                        "Playback stopped: unable to play any track in this queue (${decision.failedOccurrenceCount} failed)."
                    } else {
                        readableMessage
                    }
                    PlaybackBus.postError(summary)
                }
            }
        }
    }

    private fun classifyPlaybackError(error: PlaybackException, title: String): String {
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED ->
                "Unable to open \"$title\": file missing, moved, or access revoked."
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                "Cannot play \"$title\": unsupported audio codec or corrupt file."
            else ->
                "Playback error for \"$title\": ${error.localizedMessage ?: error.errorCodeName}"
        }
    }

    private fun startFreshSessionForCurrentItem() {
        val item = exoPlayer.currentMediaItem ?: run {
            activeSession = null
            return
        }
        val key = MediaItemMapper.extractTrackKey(item) ?: run {
            activeSession = null
            return
        }
        val duration = item.mediaMetadata.extras?.getLong(MediaItemMapper.EXTRA_DURATION_MS)
            ?.takeIf { it > 0L }
            ?: exoPlayer.duration.takeIf { it > 0L && it != C.TIME_UNSET }
            ?: 0L

        activeSession = ActiveTrackSession(
            trackKey = key,
            sessionToken = UUID.randomUUID().toString(),
            durationMs = duration
        )
        lastTickWallMs = System.currentTimeMillis()
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        lastTickWallMs = System.currentTimeMillis()
        lastSnapshotSaveWallMs = lastTickWallMs
        tickerJob = serviceScope.launch {
            while (isActive && exoPlayer.isPlaying) {
                delay(500L)
                accumulateSinceLastTick()
                evaluatePlayQualification()
                val now = System.currentTimeMillis()
                if (now - lastSnapshotSaveWallMs >= 5_000L) {
                    lastSnapshotSaveWallMs = now
                    persistSnapshotAsync()
                }
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun accumulateSinceLastTick() {
        val session = activeSession ?: return
        val now = System.currentTimeMillis()
        if (lastTickWallMs > 0L && exoPlayer.isPlaying) {
            val delta = (now - lastTickWallMs).coerceIn(0L, 2_000L)
            session.accumulatedPlayMs += delta
        }
        lastTickWallMs = now
    }

    private fun evaluatePlayQualification() {
        val session = activeSession ?: return
        val duration = exoPlayer.duration.takeIf { it > 0L && it != C.TIME_UNSET } ?: session.durationMs
        if (ListeningRules.shouldRecordPlay(
                alreadyRecordedPlay = session.hasRecordedPlay,
                accumulatedPlayMs = session.accumulatedPlayMs,
                durationMs = duration,
                trackEndedNaturally = false
            )
        ) {
            session.hasRecordedPlay = true
            val snapshotListened = session.accumulatedPlayMs
            serviceScope.launch {
                repository.recordListeningEventOnce(
                    trackKey = session.trackKey,
                    eventType = ListeningEventType.PLAY,
                    playbackSessionToken = session.sessionToken,
                    listenedMs = snapshotListened,
                    trackDurationMs = duration
                )
            }
        }
    }

    private fun finalizeActiveSession(
        explicitUserTransition: Boolean,
        trackEndedNaturally: Boolean
    ) {
        val session = activeSession ?: return
        val duration = session.durationMs
        if (ListeningRules.shouldRecordPlay(
                alreadyRecordedPlay = session.hasRecordedPlay,
                accumulatedPlayMs = session.accumulatedPlayMs,
                durationMs = duration,
                trackEndedNaturally = trackEndedNaturally
            )
        ) {
            session.hasRecordedPlay = true
            val listened = session.accumulatedPlayMs
            serviceScope.launch {
                repository.recordListeningEventOnce(
                    trackKey = session.trackKey,
                    eventType = ListeningEventType.PLAY,
                    playbackSessionToken = session.sessionToken,
                    listenedMs = listened,
                    trackDurationMs = duration
                )
            }
        } else if (ListeningRules.shouldRecordSkip(
                alreadyRecordedPlay = session.hasRecordedPlay,
                alreadyRecordedSkip = session.hasRecordedSkip,
                accumulatedPlayMs = session.accumulatedPlayMs,
                explicitUserTransition = explicitUserTransition
            )
        ) {
            session.hasRecordedSkip = true
            val listened = session.accumulatedPlayMs
            serviceScope.launch {
                repository.recordListeningEventOnce(
                    trackKey = session.trackKey,
                    eventType = ListeningEventType.SKIP,
                    playbackSessionToken = session.sessionToken,
                    listenedMs = listened,
                    trackDurationMs = duration
                )
            }
        }
        activeSession = null
    }

    private fun persistSnapshotAsync() {
        val snapshot = captureSnapshotData() ?: return
        serviceScope.launch {
            repository.savePlaybackSnapshot(
                currentTrackKey = snapshot.currentTrackKey,
                positionMs = snapshot.positionMs,
                repeatMode = snapshot.repeatMode,
                shuffleEnabled = snapshot.shuffleEnabled,
                queueTrackKeys = snapshot.queueKeys,
                shuffledTrackKeys = snapshot.shuffledKeys,
                activePlaylistLabel = snapshot.playlistLabel
            )
        }
    }

    private fun persistSnapshotBlockingFriendly() {
        val snapshot = captureSnapshotData() ?: return
        CoroutineScope(Dispatchers.IO).launch {
            repository.savePlaybackSnapshot(
                currentTrackKey = snapshot.currentTrackKey,
                positionMs = snapshot.positionMs,
                repeatMode = snapshot.repeatMode,
                shuffleEnabled = snapshot.shuffleEnabled,
                queueTrackKeys = snapshot.queueKeys,
                shuffledTrackKeys = snapshot.shuffledKeys,
                activePlaylistLabel = snapshot.playlistLabel
            )
        }
    }

    private data class SnapshotPayload(
        val currentTrackKey: String?,
        val positionMs: Long,
        val repeatMode: Int,
        val shuffleEnabled: Boolean,
        val queueKeys: List<String>,
        val shuffledKeys: List<String>,
        val playlistLabel: String?
    )

    private fun captureSnapshotData(): SnapshotPayload? {
        if (!::exoPlayer.isInitialized) return null
        val count = exoPlayer.mediaItemCount
        if (count == 0) return null
        val queueKeys = (0 until count).mapNotNull {
            MediaItemMapper.extractTrackKey(exoPlayer.getMediaItemAt(it))
        }
        val currentKey = MediaItemMapper.extractTrackKey(exoPlayer.currentMediaItem)
        val shuffledKeys = if (exoPlayer.shuffleModeEnabled && currentShuffleOrder.length == count) {
            currentShuffleOrder.asList().mapNotNull { idx -> queueKeys.getOrNull(idx) }
        } else {
            emptyList()
        }
        return SnapshotPayload(
            currentTrackKey = currentKey,
            positionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
            repeatMode = exoPlayer.repeatMode,
            shuffleEnabled = exoPlayer.shuffleModeEnabled,
            queueKeys = queueKeys,
            shuffledKeys = shuffledKeys,
            playlistLabel = PlaybackBus.activePlaylistLabel.value
        )
    }
}
