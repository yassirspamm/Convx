/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.music.jam

import kotlin.math.max

/**
 * This device's offset from the party server's clock, measured the way NTP does it.
 *
 * Each `ping` carries the device clock; the `pong` echoes it with the server's. With round trip
 * `rtt` the offset is `serverMs - (t0 + t1) / 2`, wrong by at most half the round trip, so the
 * sample with the smallest round trip is the one to trust (it is the least wrong).
 */
class JamClock(private val maxSamples: Int = 12) {
    private data class Sample(val rtt: Long, val offset: Long)

    private val samples = ArrayDeque<Sample>()

    @Synchronized
    fun observe(clientSentMs: Long, serverMs: Long, receivedMs: Long) {
        val rtt = receivedMs - clientSentMs
        if (rtt < 0) return
        samples.addLast(Sample(rtt, serverMs - (clientSentMs + receivedMs) / 2))
        while (samples.size > maxSamples) samples.removeFirst()
    }

    /** `serverNow - deviceNow`; 0 until the first pong lands. */
    @Synchronized
    fun offsetMs(): Long = samples.minByOrNull { it.rtt }?.offset ?: 0L

    /** The server's current time according to the device clock plus the measured offset. */
    fun serverNow(deviceNow: Long): Long = deviceNow + offsetMs()

    @Synchronized
    fun reset() = samples.clear()
}

object JamSync {
    /**
     * Where the party is right now. [PartyPlayback.positionMs] is true at [PartyPlayback.anchorMs];
     * while playing, the time since then is added, so a late frame still lands in the right place.
     * A resume is anchored slightly in the future, and until then the position holds.
     */
    fun positionAt(playback: PartyPlayback, serverNow: Long): Long =
        if (playback.isPlaying) playback.positionMs + max(0L, serverNow - playback.anchorMs) else playback.positionMs

    /** How long to wait before a resume anchored at [PartyPlayback.anchorMs] should actually start. */
    fun startDelayMs(playback: PartyPlayback, serverNow: Long): Long =
        if (playback.isPlaying) max(0L, playback.anchorMs - serverNow) else 0L
}
