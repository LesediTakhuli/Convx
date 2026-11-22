package com.music.bitchord.playback.concert

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Concert state shared between Compose and the playback service. */
object ConcertSession {
    private val _show = MutableStateFlow<ConcertShow?>(null)
    val show: StateFlow<ConcertShow?> = _show.asStateFlow()

    /** Read by each player's DSP sink, so narration ducking survives crossfade handoffs. */
    @Volatile var duckGain: Float = 1f
        private set

    fun begin(show: ConcertShow) { _show.value = show }
    fun end() { _show.value = null; duckGain = 1f }
    fun setDuck(gain: Float) { duckGain = gain.coerceIn(0f, 1f) }
}
