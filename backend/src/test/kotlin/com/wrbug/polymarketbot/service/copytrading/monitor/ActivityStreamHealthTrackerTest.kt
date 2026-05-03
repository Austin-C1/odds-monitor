package com.wrbug.polymarketbot.service.copytrading.monitor

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ActivityStreamHealthTrackerTest {

    @Test
    fun `marks stream healthy when recent data is received`() {
        val tracker = ActivityStreamHealthTracker(timeoutMillis = 90_000)

        tracker.markMessage(1_000L)

        assertFalse(tracker.shouldReconnect(80_000L))
        assertTrue(tracker.shouldReconnect(91_000L))
    }
}
