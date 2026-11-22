/**
 * Adapted from Convx Concert Mode (2026), GPL-3.0. See LICENSES/CONVX-NOTICE.md
 */

package com.music.bitchord.playback.concert

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Records the host's lines to files with Android's built-in text-to-speech, so playback never waits
 * on the voice engine. Lines are synthesized one at a time; the engine reports on a single listener.
 */
class ConcertNarrationSynthesizer(private val context: Context) : AutoCloseable {

    private var engine: TextToSpeech? = null

    /**
     * Starts the voice engine. Returns false when the device has no usable English voice.
     * Call on the main thread so [engine] is assigned before the engine reports back.
     */
    suspend fun prepare(): Boolean = suspendCancellableCoroutine { continuation ->
        val created = TextToSpeech(context.applicationContext) { status ->
            val ready = status == TextToSpeech.SUCCESS && configure(engine)
            if (continuation.isActive) continuation.resume(ready)
        }
        engine = created
        continuation.invokeOnCancellation { created.shutdown() }
    }

    private fun configure(tts: TextToSpeech?): Boolean {
        tts ?: return false
        val hasVoice = listOf(Locale.US, Locale.ENGLISH).any {
            val availability = tts.setLanguage(it)
            availability != TextToSpeech.LANG_MISSING_DATA && availability != TextToSpeech.LANG_NOT_SUPPORTED
        }
        if (!hasVoice) return false
        tts.setSpeechRate(SPEECH_RATE)
        tts.setPitch(PITCH)
        return true
    }

    /** Writes [text] to [file] and returns the clip, or null when the engine could not produce it. */
    suspend fun synthesize(text: String, file: File): NarrationClip? {
        val tts = engine ?: return null
        file.parentFile?.mkdirs()
        val finished = suspendCancellableCoroutine<Boolean> { continuation ->
            val utteranceId = UUID.randomUUID().toString()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit

                override fun onDone(id: String?) {
                    if (id == utteranceId && continuation.isActive) continuation.resume(true)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    if (id == utteranceId && continuation.isActive) continuation.resume(false)
                }

                override fun onError(id: String?, errorCode: Int) {
                    if (id == utteranceId && continuation.isActive) continuation.resume(false)
                }
            })
            continuation.invokeOnCancellation { tts.stop() }
            val queued = tts.synthesizeToFile(text, Bundle(), file, utteranceId)
            if (queued != TextToSpeech.SUCCESS && continuation.isActive) continuation.resume(false)
        }
        if (!finished) return null
        val durationMs = withContext(Dispatchers.IO) { readDurationMs(file) }
        return durationMs?.let { NarrationClip(file.absolutePath, it) }
    }

    private fun readDurationMs(file: File): Long? {
        if (!file.exists() || file.length() == 0L) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?.takeIf { it > 0L }
        } catch (_: RuntimeException) {
            null
        } finally {
            retriever.release()
        }
    }

    override fun close() {
        engine?.shutdown()
        engine = null
    }

    private companion object {
        // A touch slower and lower than the default reads as an announcer.
        const val SPEECH_RATE = 0.95f
        const val PITCH = 0.88f
    }
}
