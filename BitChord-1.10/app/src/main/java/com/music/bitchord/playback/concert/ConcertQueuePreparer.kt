/*
 * Adapted from Convx Concert Mode (2026), GPL-3.0. See LICENSES/CONVX-NOTICE.md.
 * This BitChord adapter prepares the app's own MediaItems instead of Convx database rows.
 */
package com.music.bitchord.playback.concert

import android.content.Context
import androidx.media3.common.MediaItem
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.toSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Builds the short, hosted concert queue from BitChord's current playable queue. */
class ConcertQueuePreparer(private val context: Context) {
    data class Progress(val stage: Stage, val done: Int, val total: Int) {
        enum class Stage { PLANNING, NARRATING }
    }

    data class Prepared(
        val show: ConcertShow,
        val items: List<MediaItem>,
        val narrated: Boolean,
    )

    private data class Entry(
        val item: MediaItem,
        val title: String,
        val artists: List<String>,
        val durationMs: Long,
        val role: ConcertRole,
        val window: SnippetWindow,
    )

    suspend fun prepare(
        title: String,
        sourceItems: List<MediaItem>,
        includeHost: Boolean = true,
        sourceRoles: List<ConcertRole>? = null,
        onProgress: (Progress) -> Unit = {},
    ): Prepared? {
        if (sourceItems.isEmpty()) return null
        val entries = sourceItems.mapIndexedNotNull { index, item ->
            val song = item.toSong()
            val durationMs = TrackMatcher.secondsOf(song.durationText)?.times(1_000L)
                ?: item.mediaMetadata.extras?.getLong("duration_ms")?.takeIf { it > 0L }
                ?: 0L
            val role = sourceRoles?.getOrNull(index) ?: roleFor(index, sourceItems.size)
            val window = ConcertSnippetPlanner.plan(role, durationMs)
            Entry(
                item = item,
                title = song.title.ifBlank { item.mediaId },
                artists = song.artists.map { it.name }.ifEmpty { listOf(song.artist).filter { it.isNotBlank() } },
                durationMs = durationMs,
                role = role,
                window = window,
            )
        }
        if (entries.isEmpty()) return null

        val safeTitle = title.trim().ifBlank { "BitChord Live" }.take(80)
        val showId = entries.joinToString("|") { it.item.mediaId }.hashCode().toUInt().toString(16)
        val root = File(context.cacheDir, "concert")
        withContext(Dispatchers.IO) {
            root.listFiles()?.filter { it.name != showId }?.forEach { it.deleteRecursively() }
            File(root, showId).apply { deleteRecursively(); mkdirs() }
        }
        val showDirectory = File(root, showId)
        val intros = arrayOfNulls<NarrationClip>(entries.size)
        var outro: NarrationClip? = null
        var narrated = false

        if (includeHost) {
            onProgress(Progress(Progress.Stage.NARRATING, 0, entries.size + 1))
            val synthesizer = ConcertNarrationSynthesizer(context)
            try {
                val ready = withContext(Dispatchers.Main) { synthesizer.prepare() }
                if (ready) {
                    narrated = true
                    entries.forEachIndexed { index, entry ->
                        val cue = ConcertScriptWriter.SongCue(entry.title, entry.artists)
                        val line = if (index == 0) {
                            ConcertScriptWriter.opening(ConcertScriptWriter.showName(safeTitle), cue)
                        } else {
                            ConcertScriptWriter.intro(index, entry.role, cue)
                        }
                        intros[index] = synthesizer.synthesize(line, File(showDirectory, "intro_$index.wav"))
                        onProgress(Progress(Progress.Stage.NARRATING, index + 1, entries.size + 1))
                    }
                    outro = synthesizer.synthesize(
                        ConcertScriptWriter.closing(ConcertScriptWriter.showName(safeTitle)),
                        File(showDirectory, "outro.wav"),
                    )
                }
            } finally {
                synthesizer.close()
            }
            onProgress(Progress(Progress.Stage.NARRATING, entries.size + 1, entries.size + 1))
        }

        onProgress(Progress(Progress.Stage.PLANNING, 0, entries.size))
        val segments = entries.mapIndexed { index, entry ->
            onProgress(Progress(Progress.Stage.PLANNING, index + 1, entries.size))
            ConcertSegment(entry.item.mediaId, entry.role, entry.window, intros[index])
        }
        val items = entries.map { entry ->
            entry.item.buildUpon()
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(entry.window.startMs)
                        .setEndPositionMs(entry.window.endMs)
                        .build(),
                )
                .build()
        }
        return Prepared(ConcertShow(safeTitle, segments, outro), items, narrated)
    }

    private fun roleFor(index: Int, count: Int): ConcertRole = when {
        index == 0 -> ConcertRole.OPENER
        index == count - 1 -> ConcertRole.CLOSER
        index.toFloat() / count < 0.58f -> ConcertRole.RISING
        else -> ConcertRole.PEAK
    }


}
