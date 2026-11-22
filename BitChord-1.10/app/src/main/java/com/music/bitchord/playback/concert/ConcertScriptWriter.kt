/**
 * Adapted from Convx Concert Mode (2026), GPL-3.0. See LICENSES/CONVX-NOTICE.md
 */

package com.music.bitchord.playback.concert

/** Writes the host's spoken lines. Short on purpose: each one must fit over a song intro. */
object ConcertScriptWriter {

    data class SongCue(val title: String, val artists: List<String>)

    private val BRACKETED = Regex("""\s*[(\[][^)\]]*[)\]]""")
    private val FEATURED = Regex(
        """[(\[]\s*(?:feat\.?|ft\.?|featuring)\s+([^)\]]+)[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private const val SHOW_PREFIX = "Concert: "

    private val RISING_LINES = listOf(
        "Keep it going! Here's %s.",
        "The energy is building. This is %s.",
        "Stay with us. Next up, %s.",
        "You're warmed up now. Give it up for %s.",
    )
    private val PEAK_LINES = listOf(
        "Make some noise! It's %s!",
        "This is the moment you've been waiting for. %s!",
        "Hands up, everybody! Here comes %s!",
    )
    private val CLOSER_LINES = listOf(
        "One last song tonight. %s. Let's finish this together.",
        "This is the final song of the night. %s. Make it count!",
    )

    fun showName(playlistName: String): String =
        playlistName.removePrefix(SHOW_PREFIX).trim().ifEmpty { playlistName.trim() }

    fun spokenTitle(title: String): String = title.replace(BRACKETED, "").trim().ifEmpty { title.trim() }

    fun featuredGuests(title: String): String? =
        FEATURED.find(title)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    fun opening(show: String, first: SongCue): String =
        "Ladies and gentlemen, welcome to $show! Tonight we open with ${songPhrase(first)}. Let's go!" +
            guestSentence(first)

    fun intro(index: Int, role: ConcertRole, cue: SongCue): String {
        val lines = when (role) {
            ConcertRole.OPENER, ConcertRole.RISING -> RISING_LINES
            ConcertRole.PEAK -> PEAK_LINES
            ConcertRole.CLOSER -> CLOSER_LINES
        }
        return lines[index % lines.size].format(songPhrase(cue)) + guestSentence(cue)
    }

    fun closing(show: String): String =
        "Thank you, everybody! That was $show. You've been amazing. Goodnight!"

    private fun songPhrase(cue: SongCue): String {
        val title = spokenTitle(cue.title)
        val artist = cue.artists.firstOrNull()?.takeIf { it.isNotBlank() } ?: return title
        return "$title, by $artist"
    }

    private fun guestSentence(cue: SongCue): String {
        val guests = cue.artists.drop(1).filter { it.isNotBlank() } + listOfNotNull(featuredGuests(cue.title))
        if (guests.isEmpty()) return ""
        return " With special guest ${joinNames(guests.distinct())}!"
    }

    private fun joinNames(names: List<String>): String = when (names.size) {
        1 -> names[0]
        2 -> "${names[0]} and ${names[1]}"
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }
}
