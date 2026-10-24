package com.music.lrclib.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackMatchingTest {
    @Test
    fun syncedLyricsBonusCanBreakACloseTitleMatchTie() {
        val exactTrack = track(title = "Creep", artist = "Radiohead")
        val syncedTrack = track(title = "Creep", artist = "Radiohed", syncedLyrics = "[00:01.00]line")

        val result = listOf(exactTrack, syncedTrack).bestMatchingFor(
            duration = -1,
            trackName = "Creep",
            artistName = "Radiohead",
        )

        assertEquals(syncedTrack, result)
    }

    @Test
    fun unrelatedTrackDoesNotPassTheSimilarityThreshold() {
        val result = listOf(track(title = "xxxxxxxx", artist = "yyyyyyyy")).bestMatchingFor(
            duration = -1,
            trackName = "Creep",
            artistName = "Radiohead",
        )

        assertNull(result)
    }

    private fun track(
        title: String,
        artist: String,
        syncedLyrics: String? = null,
    ) = Track(
        id = 1,
        trackName = title,
        artistName = artist,
        duration = 180.0,
        plainLyrics = null,
        syncedLyrics = syncedLyrics,
    )
}
