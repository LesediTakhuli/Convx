/**
 * Adapted from Convx Concert Mode (2026), GPL-3.0. See LICENSES/CONVX-NOTICE.md
 */

package com.music.bitchord.playback.concert

import androidx.media3.common.C
import androidx.media3.common.MediaItem

/** How long neighbouring concert snippets overlap. */
const val CONCERT_CROSSFADE_MS = 2_500L

enum class ConcertRole { OPENER, RISING, PEAK, CLOSER }

/** The slice of a song that is played, in song time. */
data class SnippetWindow(val startMs: Long, val endMs: Long) {
    val lengthMs: Long get() = endMs - startMs
}

/** A narrator line recorded ahead of time and stored on disk. */
data class NarrationClip(val path: String, val durationMs: Long)

data class ConcertSegment(
    val mediaId: String,
    val role: ConcertRole,
    val window: SnippetWindow,
    /** Spoken over the start of this snippet, or null when no voice is available. */
    val intro: NarrationClip?,
)

data class ConcertShow(
    val title: String,
    val segments: List<ConcertSegment>,
    /** Spoken over the end of the last snippet, or null when no voice is available. */
    val outro: NarrationClip?,
) {
    fun segmentFor(mediaId: String): ConcertSegment? = segments.firstOrNull { it.mediaId == mediaId }
}

/** Concert snippets are the only queue items with a finite clip end. */
fun MediaItem?.isConcertSnippet(): Boolean =
    this != null && clippingConfiguration.endPositionMs != C.TIME_END_OF_SOURCE
