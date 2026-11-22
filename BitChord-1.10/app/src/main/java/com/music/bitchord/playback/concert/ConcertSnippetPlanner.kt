/*
 * Adapted from Convx Concert Mode (2026), GPL-3.0. See LICENSES/CONVX-NOTICE.md.
 * BitChord uses a duration-aware fallback planner because Convx's beat-grid
 * and energy-profile database types are not part of BitChord.
 */
package com.music.bitchord.playback.concert

/** Chooses a representative part of a track for a short concert performance. */
object ConcertSnippetPlanner {
    fun lengthFor(role: ConcertRole): Long = when (role) {
        ConcertRole.OPENER -> 70_000L
        ConcertRole.RISING -> 75_000L
        ConcertRole.PEAK -> 95_000L
        ConcertRole.CLOSER -> 110_000L
    }

    fun plan(role: ConcertRole, durationMs: Long): SnippetWindow {
        val length = lengthFor(role)
        if (durationMs <= 0L) return SnippetWindow(0L, length)
        if (durationMs <= length + 6_000L) return SnippetWindow(0L, durationMs)
        val fraction = when (role) {
            ConcertRole.OPENER -> 0.12
            ConcertRole.RISING -> 0.24
            ConcertRole.PEAK -> 0.36
            ConcertRole.CLOSER -> 0.22
        }
        val latest = (durationMs - length - 6_000L).coerceAtLeast(0L)
        val start = (durationMs * fraction).toLong().coerceIn(0L, latest)
        return SnippetWindow(start, (start + length).coerceAtMost(durationMs))
    }
}
