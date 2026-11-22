/**
 * Adapted from Convx Concert Mode (2026), GPL-3.0. See LICENSES/CONVX-NOTICE.md
 */

package com.music.bitchord.playback.concert

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Cues the host during a concert. The snippets and their overlaps are handled by the normal queue
 * and crossfade; this class only talks over the start of each song, turns the music down while the
 * host speaks, and closes the show. Runs on the main thread with the playback service.
 */
class ConcertDirector(
    private val scope: CoroutineScope,
    private val activePlayer: () -> Player,
    private val isTransitioning: () -> Boolean,
    private val narrator: ConcertNarrator = ConcertNarrator(),
) {

    private var job: Job? = null
    private var rampJob: Job? = null

    private var armedAt = 0L
    private var sawShow = false
    private var introDoneFor: String? = null
    private var outroStarted = false
    private var narrationOwner: String? = null
    private var duck = 1f

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            ConcertSession.show.collectLatest { show ->
                reset()
                if (show == null) return@collectLatest
                try {
                    while (true) {
                        tick(show)
                        delay(TICK_MS)
                    }
                } finally {
                    stopNarration()
                }
            }
        }
    }

    fun release() {
        job?.cancel()
        job = null
        rampJob?.cancel()
        narrator.stop()
    }

    private fun reset() {
        armedAt = SystemClock.elapsedRealtime()
        sawShow = false
        introDoneFor = null
        outroStarted = false
        narrationOwner = null
    }

    private fun tick(show: ConcertShow) {
        val player = activePlayer()
        val item = player.currentMediaItem
        val segment = item?.let { show.segmentFor(it.mediaId) }
        if (item == null || segment == null) {
            // The queue is replaced a moment after the show begins, so wait before assuming it was left.
            if (sawShow || SystemClock.elapsedRealtime() - armedAt > JOIN_TIMEOUT_MS) ConcertSession.end()
            return
        }
        sawShow = true
        val isLast = segment === show.segments.last()
        if (!isLast) outroStarted = false

        if (isTransitioning()) {
            // The crossfade owns the volume now; the voice would only fight it.
            if (narrator.isActive) abandonNarration()
            return
        }
        if (narrationOwner != null && narrationOwner != item.mediaId) stopNarration()

        if (player.playWhenReady) narrator.resume() else narrator.pause()

        playIntroIfDue(segment, item.mediaId, player)
        if (isLast) playOutroIfDue(show, segment, player)
        if (isLast && player.playbackState == Player.STATE_ENDED && !narrator.isActive) ConcertSession.end()
    }

    private fun playIntroIfDue(segment: ConcertSegment, mediaId: String, player: Player) {
        if (introDoneFor == mediaId || !player.isPlaying) return
        introDoneFor = mediaId
        // Scrubbing into the middle of a snippet should not replay the introduction.
        if (player.currentPosition > INTRO_WINDOW_MS) return
        segment.intro?.let { speak(it, mediaId) }
    }

    private fun playOutroIfDue(show: ConcertShow, segment: ConcertSegment, player: Player) {
        val clip = show.outro ?: return
        if (outroStarted || !player.isPlaying) return
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0L) return
        if (duration - player.currentPosition > clip.durationMs + OUTRO_LEAD_MS) return
        outroStarted = true
        speak(clip, segment.mediaId)
    }

    private fun speak(clip: NarrationClip, mediaId: String) {
        narrationOwner = mediaId
        narrator.play(
            clip = clip,
            onStarted = { rampDuck(DUCKED_GAIN, DUCK_DOWN_MS) },
            onFinished = {
                narrationOwner = null
                rampDuck(1f, DUCK_UP_MS)
            },
        )
    }

    /** Ends the line and brings the music back up. */
    private fun stopNarration() {
        narrator.stop()
        narrationOwner = null
        rampJob?.cancel()
        if (duck != 1f) {
            duck = 1f
            if (!isTransitioning()) ConcertSession.setDuck(1f)
        }
    }

    /** Ends the line without touching the volume, because a crossfade is driving it. */
    private fun abandonNarration() {
        narrator.stop()
        narrationOwner = null
        rampJob?.cancel()
        duck = 1f
    }

    private fun rampDuck(target: Float, durationMs: Long) {
        rampJob?.cancel()
        val from = duck
        val steps = (durationMs / RAMP_STEP_MS).toInt().coerceAtLeast(1)
        rampJob = scope.launch {
            for (step in 1..steps) {
                if (isTransitioning()) {
                    duck = 1f
                    return@launch
                }
                duck = from + (target - from) * step / steps
                ConcertSession.setDuck(duck)
                delay(RAMP_STEP_MS)
            }
        }
    }

    private companion object {
        const val TICK_MS = 200L
        const val JOIN_TIMEOUT_MS = 45_000L
        const val INTRO_WINDOW_MS = 8_000L
        const val OUTRO_LEAD_MS = 1_500L
        const val DUCKED_GAIN = 0.28f
        const val DUCK_DOWN_MS = 160L
        const val DUCK_UP_MS = 600L
        const val RAMP_STEP_MS = 40L
    }
}
