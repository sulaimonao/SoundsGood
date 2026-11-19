package com.example.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-local event & auxiliary playback metadata bus shared between PlaybackService and UI.
 * MediaController remains the primary control/state channel for playback state, queue, and position.
 */
object PlaybackBus {

    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    private val _activePlaylistLabel = MutableStateFlow<String?>("Library")
    val activePlaylistLabel: StateFlow<String?> = _activePlaylistLabel.asStateFlow()

    private val _shuffledOrderKeys = MutableStateFlow<List<String>>(emptyList())
    val shuffledOrderKeys: StateFlow<List<String>> = _shuffledOrderKeys.asStateFlow()

    private val explicitSkipPending = AtomicBoolean(false)

    fun postError(message: String?) {
        _playbackError.value = message
    }

    fun clearError() {
        _playbackError.value = null
    }

    fun setActivePlaylistLabel(label: String?) {
        _activePlaylistLabel.value = label
    }

    fun setShuffledOrderKeys(keys: List<String>) {
        _shuffledOrderKeys.value = keys
    }

    fun markExplicitUserSkip() {
        explicitSkipPending.set(true)
    }

    fun consumeExplicitUserSkip(): Boolean {
        return explicitSkipPending.getAndSet(false)
    }
}
