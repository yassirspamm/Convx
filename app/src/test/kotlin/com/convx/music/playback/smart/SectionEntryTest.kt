package com.convx.music.playback.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SectionEntryTest {

    private fun analysis(energy: (Int) -> Double) = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        duration = 200.0,
        bpm = 128.0,
        phraseBoundaries = (1..11).map { it * 16.0 },
        energyCurve = (0..199).map { EnergySample(it.toDouble(), energy(it)) },
    )

    @Test
    fun `enters where the energy steps up, not at the intro`() {
        val track = analysis { if (it < 64) 0.2 else 1.0 }

        assertEquals(64.0, sectionEntryCandidate(track)?.time ?: -1.0, 0.0)
        assertEquals(64.0, track.withSectionEntry().mixInTime, 0.0)
    }

    @Test
    fun `a flat track keeps the native intro candidates`() {
        val track = analysis { 0.8 }

        assertNull(sectionEntryCandidate(track))
        assertEquals(track, track.withSectionEntry())
    }
}
