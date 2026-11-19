package com.example.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.data.db.TrackEntity
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class QueueEntryUi(
    val mediaIndex: Int,
    val trackKey: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUri: String?,
    val durationMs: Long,
    val isCurrent: Boolean
)

data class PlaybackUiState(
    val isConnected: Boolean = false,
    val currentTrackKey: String? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val genre: String = "",
    val artworkUri: String? = null,
    val moodTag: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<QueueEntryUi> = emptyList(),
    val currentMediaIndex: Int = -1
) {
    val hasActiveTrack: Boolean
        get() = currentTrackKey != null
}

/**
 * Lifecycle-aware MediaController client managed by MainActivity (connect in onStart, release in onStop).
 */
class PlaybackClient(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var progressJob: Job? = null

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncFromPlayer(player)
        }
    }

    fun connect() {
        if (controllerFuture != null) return
        val sessionToken = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java)
        )
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                val mc = future.get()
                controller = mc
                mc.addListener(listener)
                syncFromPlayer(mc)
                startProgressLoop()
            } catch (_: Exception) {
                _state.update { it.copy(isConnected = false) }
            }
        }, MoreExecutors.directExecutor())
    }

    fun disconnect() {
        progressJob?.cancel()
        progressJob = null
        controller?.removeListener(listener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
    }

    fun playQueue(
        tracks: List<TrackEntity>,
        startIndex: Int = 0,
        playlistLabel: String = "Library",
        shuffle: Boolean? = null
    ) {
        val playable = tracks.filterNot { it.isMissingOrUnreadable }
        if (playable.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, playable.lastIndex)
        val mc = controller ?: return

        PlaybackBus.clearError()
        PlaybackBus.setActivePlaylistLabel(playlistLabel)
        if (mc.isPlaying) PlaybackBus.markExplicitUserSkip()

        val mediaItems = playable.map { MediaItemMapper.toMediaItem(it) }
        mc.setMediaItems(mediaItems, safeIndex, 0L)
        if (shuffle != null) {
            mc.shuffleModeEnabled = shuffle
        }
        mc.prepare()
        mc.play()
        syncFromPlayer(mc)
    }

    fun playNext(track: TrackEntity) {
        if (track.isMissingOrUnreadable) return
        val mc = controller ?: return
        val item = MediaItemMapper.toMediaItem(track)
        if (mc.mediaItemCount == 0) {
            playQueue(listOf(track), 0, "Queue")
        } else {
            val insertIdx = (mc.currentMediaItemIndex + 1).coerceAtMost(mc.mediaItemCount)
            mc.addMediaItem(insertIdx, item)
            syncFromPlayer(mc)
        }
    }

    fun addToQueue(track: TrackEntity) {
        if (track.isMissingOrUnreadable) return
        val mc = controller ?: return
        val item = MediaItemMapper.toMediaItem(track)
        if (mc.mediaItemCount == 0) {
            playQueue(listOf(track), 0, "Queue")
        } else {
            mc.addMediaItem(item)
            syncFromPlayer(mc)
        }
    }

    fun togglePlayPause() {
        val mc = controller ?: return
        if (mc.isPlaying) {
            mc.pause()
        } else {
            if (mc.playbackState == Player.STATE_IDLE && mc.mediaItemCount > 0) {
                mc.prepare()
            }
            mc.play()
        }
    }

    fun seekTo(positionMs: Long) {
        val mc = controller ?: return
        mc.seekTo(positionMs.coerceAtLeast(0L))
        _state.update { it.copy(positionMs = positionMs.coerceAtLeast(0L)) }
    }

    fun seekToNext() {
        val mc = controller ?: return
        if (mc.hasNextMediaItem()) {
            PlaybackBus.markExplicitUserSkip()
            mc.seekToNextMediaItem()
            mc.play()
        }
    }

    fun seekToPrevious() {
        val mc = controller ?: return
        if (mc.currentPosition > 3_500L) {
            mc.seekTo(0L)
        } else if (mc.hasPreviousMediaItem()) {
            PlaybackBus.markExplicitUserSkip()
            mc.seekToPreviousMediaItem()
            mc.play()
        } else {
            mc.seekTo(0L)
        }
    }

    fun jumpToQueueIndex(mediaIndex: Int) {
        val mc = controller ?: return
        if (mediaIndex in 0 until mc.mediaItemCount) {
            if (mediaIndex != mc.currentMediaItemIndex) {
                PlaybackBus.markExplicitUserSkip()
            }
            mc.seekToDefaultPosition(mediaIndex)
            mc.prepare()
            mc.play()
        }
    }

    fun removeFromQueue(mediaIndex: Int) {
        val mc = controller ?: return
        if (mediaIndex in 0 until mc.mediaItemCount) {
            mc.removeMediaItem(mediaIndex)
            syncFromPlayer(mc)
        }
    }

    fun toggleShuffle() {
        val mc = controller ?: return
        mc.shuffleModeEnabled = !mc.shuffleModeEnabled
    }

    fun cycleRepeatMode() {
        val mc = controller ?: return
        mc.repeatMode = when (mc.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                val mc = controller
                if (mc != null && mc.isPlaying) {
                    _state.update {
                        it.copy(
                            positionMs = mc.currentPosition.coerceAtLeast(0L),
                            durationMs = resolveDuration(mc)
                        )
                    }
                }
                delay(400L)
            }
        }
    }

    private fun syncFromPlayer(player: Player) {
        val current = player.currentMediaItem
        val meta = current?.mediaMetadata
        val extras = meta?.extras
        val currentKey = MediaItemMapper.extractTrackKey(current)
        val currentIndex = player.currentMediaItemIndex

        val queueEntries = buildDisplayQueue(player, currentIndex)

        _state.value = PlaybackUiState(
            isConnected = true,
            currentTrackKey = currentKey,
            title = meta?.title?.toString().orEmpty(),
            artist = meta?.artist?.toString().orEmpty(),
            album = meta?.albumTitle?.toString().orEmpty(),
            genre = extras?.getString(MediaItemMapper.EXTRA_GENRE) ?: meta?.genre?.toString().orEmpty(),
            artworkUri = meta?.artworkUri?.toString(),
            moodTag = extras?.getString(MediaItemMapper.EXTRA_MOOD_TAG),
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = resolveDuration(player),
            shuffleEnabled = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            queue = queueEntries,
            currentMediaIndex = currentIndex
        )
    }

    private fun buildDisplayQueue(player: Player, currentIndex: Int): List<QueueEntryUi> {
        val count = player.mediaItemCount
        if (count == 0) return emptyList()

        // Traverse using Timeline in playback order (respecting shuffleModeEnabled!)
        val timeline = player.currentTimeline
        val orderedIndices = mutableListOf<Int>()
        if (!timeline.isEmpty && currentIndex in 0 until count) {
            var idx = if (player.shuffleModeEnabled) {
                timeline.getFirstWindowIndex(true)
            } else {
                0
            }
            val visited = HashSet<Int>(count)
            while (idx != C.INDEX_UNSET && idx in 0 until count && visited.add(idx)) {
                orderedIndices.add(idx)
                idx = timeline.getNextWindowIndex(idx, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            }
        }
        // Fallback if timeline traversal missed any items
        if (orderedIndices.size < count) {
            for (i in 0 until count) {
                if (i !in orderedIndices) orderedIndices.add(i)
            }
        }

        return orderedIndices.map { mediaIdx ->
            val item = player.getMediaItemAt(mediaIdx)
            val m = item.mediaMetadata
            val key = MediaItemMapper.extractTrackKey(item) ?: "idx:$mediaIdx"
            val dur = m.extras?.getLong(MediaItemMapper.EXTRA_DURATION_MS) ?: 0L
            QueueEntryUi(
                mediaIndex = mediaIdx,
                trackKey = key,
                title = m.title?.toString() ?: "Untitled",
                artist = m.artist?.toString() ?: "Unknown Artist",
                album = m.albumTitle?.toString() ?: "Unknown Album",
                artworkUri = m.artworkUri?.toString(),
                durationMs = dur,
                isCurrent = mediaIdx == currentIndex
            )
        }
    }

    private fun resolveDuration(player: Player): Long {
        val playerDur = player.duration
        if (playerDur > 0L && playerDur != C.TIME_UNSET) return playerDur
        return player.currentMediaItem?.mediaMetadata?.extras
            ?.getLong(MediaItemMapper.EXTRA_DURATION_MS)
            ?.takeIf { it > 0L }
            ?: 0L
    }
}
