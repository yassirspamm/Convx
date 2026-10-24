package com.convx.music.jam

import org.junit.Assert.assertEquals
import org.junit.Test

class JamSyncTest {
    @Test
    fun clockTrustsTheSampleWithTheSmallestRoundTrip() {
        val clock = JamClock()
        // Phone is 2 s behind the server. A slow sample (rtt 400) and a fast one (rtt 20).
        clock.observe(clientSentMs = 1_000, serverMs = 3_200, receivedMs = 1_400)
        clock.observe(clientSentMs = 5_000, serverMs = 7_010, receivedMs = 5_020)
        assertEquals(2_000L, clock.offsetMs())
        assertEquals(12_000L, clock.serverNow(10_000))
    }

    @Test
    fun playingPositionAdvancesFromTheAnchor() {
        val playback = PartyPlayback(isPlaying = true, positionMs = 42_000, anchorMs = 100_000)
        assertEquals(42_000L + 300, JamSync.positionAt(playback, serverNow = 100_300))
    }

    @Test
    fun pausedPositionDoesNotMove() {
        val playback = PartyPlayback(isPlaying = false, positionMs = 42_000, anchorMs = 100_000)
        assertEquals(42_000L, JamSync.positionAt(playback, serverNow = 160_000))
    }

    @Test
    fun aResumeScheduledAheadHoldsUntilItsAnchor() {
        val playback = PartyPlayback(isPlaying = true, positionMs = 10_000, anchorMs = 100_350)
        assertEquals(10_000L, JamSync.positionAt(playback, serverNow = 100_000))
        assertEquals(350L, JamSync.startDelayMs(playback, serverNow = 100_000))
        assertEquals(0L, JamSync.startDelayMs(playback, serverNow = 100_400))
    }
}
