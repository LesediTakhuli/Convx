/**
 * Adapted from Convx Concert Mode (2026), GPL-3.0. See LICENSES/CONVX-NOTICE.md
 */

package com.music.bitchord.playback.concert

import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.IOException

/** Plays one recorded host line at a time next to the music. Main thread only. */
class ConcertNarrator {

    private var player: MediaPlayer? = null
    private var paused = false

    val isActive: Boolean get() = player != null

    /** [onStarted] fires when the voice is audible; [onFinished] when it ends or fails, but not after [stop]. */
    fun play(clip: NarrationClip, onStarted: () -> Unit, onFinished: () -> Unit) {
        stop()
        val line = MediaPlayer()
        player = line
        paused = false
        try {
            line.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            line.setDataSource(clip.path)
            line.setOnPreparedListener {
                if (player !== it) return@setOnPreparedListener
                if (!paused) it.start()
                onStarted()
            }
            line.setOnCompletionListener {
                if (player !== it) return@setOnCompletionListener
                release()
                onFinished()
            }
            line.setOnErrorListener { failed, _, _ ->
                if (player === failed) {
                    release()
                    onFinished()
                }
                true
            }
            line.prepareAsync()
        } catch (_: IOException) {
            release()
            onFinished()
        } catch (_: IllegalStateException) {
            release()
            onFinished()
        }
    }

    fun pause() {
        val line = player ?: return
        paused = true
        try {
            if (line.isPlaying) line.pause()
        } catch (_: IllegalStateException) {
            // The line is not in a state that can pause; the completion or error callback ends it.
        }
    }

    fun resume() {
        val line = player ?: return
        if (!paused) return
        paused = false
        try {
            line.start()
        } catch (_: IllegalStateException) {
            // Still preparing: the prepared callback starts it because paused is now false.
        }
    }

    fun stop() {
        release()
    }

    private fun release() {
        val line = player ?: return
        player = null
        paused = false
        try {
            line.release()
        } catch (_: IllegalStateException) {
            // Already released by the platform after an error.
        }
    }
}
