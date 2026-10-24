package com.music.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineModeTest {
    @Test
    fun reasonFollowsPrecedence() {
        OfflineMode.setManual(false); OfflineMode.setNoSession(false); OfflineMode.setSlow(false)
        assertFalse(OfflineMode.active.value)

        OfflineMode.setSlow(true)
        assertEquals(OfflineMode.Reason.SLOW, OfflineMode.reason.value)
        OfflineMode.setNoSession(true)
        assertEquals(OfflineMode.Reason.NO_SESSION, OfflineMode.reason.value)
        OfflineMode.setManual(true)
        assertEquals(OfflineMode.Reason.MANUAL, OfflineMode.reason.value)
        assertTrue(OfflineMode.active.value)

        OfflineMode.setManual(false); OfflineMode.setNoSession(false); OfflineMode.setSlow(false)
        assertFalse(OfflineMode.active.value)
    }
}
