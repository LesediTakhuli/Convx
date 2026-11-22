package com.music.bitchord.playback.concert

import android.content.Context
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import org.json.JSONArray

/** Builds an ordered concert set from YouTube Music candidates and, when configured, OpenRouter. */
object ConcertSetBuilder {
    data class Track(val song: Song, val role: ConcertRole)
    data class ResultSet(val title: String, val tracks: List<Track>, val unmatched: List<String>, val aiDesigned: Boolean)

    suspend fun build(
        context: Context,
        concept: String,
        seed: Song?,
        count: Int,
        onProgress: suspend (String) -> Unit = {},
    ): ResultSet? {
        val query = concept.trim().ifBlank { seed?.artist?.takeIf { it.isNotBlank() } ?: seed?.title.orEmpty() }
        if (query.isBlank()) {
            onProgress("Enter an artist or concert theme to start.")
            return null
        }
        onProgress("Finding songs for $query…")
        val candidates = LinkedHashMap<String, Song>()
        if (seed != null) candidates[seed.videoId] = seed
        val searches = listOf(query, seed?.artist?.takeIf { it.isNotBlank() && !it.equals(query, true) })
            .filterNotNull().distinct()
        for (term in searches) {
            val response = YtMusicRepository.search(term, SearchFilter.SONGS).getOrNull().orEmpty()
            response.forEach { row ->
                val song = when (row) {
                    is SearchResult.Track -> row.song
                    is SearchResult.TopTrack -> row.song
                    else -> null
                }
                if (song != null && candidates.size < 50) candidates.putIfAbsent(song.videoId, song)
            }
        }
        if (candidates.isEmpty()) {
            onProgress("No songs were found for that search.")
            return null
        }

        onProgress(if (ConcertAiSettings.apiKey(context).isBlank()) {
            "OpenRouter is not configured; arranging the search results into a concert arc."
        } else "Designing the concert running order…")

        val aiText = ConcertAiClient.designSet(
            context,
            query,
            candidates.values.map { it.title to it.artist }.take(40),
            count.coerceIn(4, 30),
        )
        val chosen = parse(aiText)
        val selected = mutableListOf<Track>()
        val unmatched = mutableListOf<String>()

        if (chosen.isNotEmpty()) {
            for ((index, choice) in chosen.take(count.coerceIn(4, 30)).withIndex()) {
                val candidate = candidates.values.firstOrNull { song ->
                    song.title.equals(choice.title, ignoreCase = true) &&
                        (choice.artist.isBlank() || song.artist.contains(choice.artist, ignoreCase = true) || choice.artist.contains(song.artist, ignoreCase = true))
                } ?: candidates.values.firstOrNull { it.title.equals(choice.title, ignoreCase = true) }
                    ?: searchOne(choice.title, choice.artist)
                if (candidate == null) {
                    unmatched += choice.title
                } else if (selected.none { it.song.videoId == candidate.videoId }) {
                    selected += Track(candidate, if (index == 0) ConcertRole.OPENER else choice.role)
                }
            }
        }

        val aiDesigned = selected.isNotEmpty()
        if (selected.isEmpty()) {
            val fallback = candidates.values.take(count.coerceIn(4, 30)).toList()
            fallback.forEachIndexed { index, song -> selected += Track(song, roleFor(index, fallback.size)) }
        } else {
            // The setlist itself keeps the AI's order. Normalize the bookends so the show always opens and closes cleanly.
            if (selected.size > 1) {
                selected[0] = selected.first().copy(role = ConcertRole.OPENER)
                selected[selected.lastIndex] = selected.last().copy(role = ConcertRole.CLOSER)
            }
        }
        if (seed != null && selected.none { it.song.videoId == seed.videoId }) {
            selected.add(0, Track(seed, ConcertRole.OPENER))
            if (selected.size > 1) selected[1] = selected[1].copy(role = ConcertRole.RISING)
        }
        val limited = selected.take(count.coerceIn(4, 30))
        val finalTracks = limited.mapIndexed { index, track ->
            // If the current song was pinned to the front, truncation can otherwise
            // discard the original closer. Reassert the bookends after the final cut.
            val role = when {
                index == 0 -> ConcertRole.OPENER
                index == limited.lastIndex -> ConcertRole.CLOSER
                else -> track.role
            }
            track.copy(role = role)
        }
        if (finalTracks.isEmpty()) return null
        onProgress("Concert set ready — ${finalTracks.size} songs.")
        return ResultSet("Concert: $query", finalTracks, unmatched, aiDesigned)
    }

    private suspend fun searchOne(title: String, artist: String): Song? {
        val query = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isBlank()) return null
        return YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull().orEmpty()
            .mapNotNull { row -> when (row) {
                is SearchResult.Track -> row.song
                is SearchResult.TopTrack -> row.song
                else -> null
            } }.firstOrNull()
    }

    private data class Choice(val title: String, val artist: String, val role: ConcertRole)

    private fun parse(text: String?): List<Choice> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching {
            val clean = text.substring(text.indexOf('[').coerceAtLeast(0), text.lastIndexOf(']') + 1)
            val array = JSONArray(clean)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val title = item.optString("title").trim()
                if (title.isBlank()) return@mapNotNull null
                val role = when (item.optString("role").lowercase()) {
                    "opener" -> ConcertRole.OPENER
                    "rising" -> ConcertRole.RISING
                    "closer" -> ConcertRole.CLOSER
                    else -> ConcertRole.PEAK
                }
                Choice(title, item.optString("artist").trim(), role)
            }
        }.getOrDefault(emptyList())
    }

    private fun roleFor(index: Int, count: Int): ConcertRole = when {
        index == 0 -> ConcertRole.OPENER
        index == count - 1 -> ConcertRole.CLOSER
        index.toFloat() / count < 0.58f -> ConcertRole.RISING
        else -> ConcertRole.PEAK
    }
}
