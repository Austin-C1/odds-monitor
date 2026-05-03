package com.wrbug.polymarketbot.service.oddsmonitor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OddsLineDisplayFormatterTest {
    @Test
    fun `formats quarter handicap ranges for display`() {
        assertEquals("0/0.5", OddsLineDisplayFormatter.format("handicap", "0-0.5"))
        assertEquals("0.5/1", OddsLineDisplayFormatter.format("handicap", "0.5-1"))
        assertEquals("0", OddsLineDisplayFormatter.format("handicap", "0.0"))
    }

    @Test
    fun `keeps totals readable without changing market meaning`() {
        assertEquals("2.5", OddsLineDisplayFormatter.format("total", "2.5"))
        assertEquals("2.5/3", OddsLineDisplayFormatter.format("total", "2.5-3"))
    }

    @Test
    fun `keeps unknown line text unchanged`() {
        assertEquals("PK", OddsLineDisplayFormatter.format("handicap", "PK"))
    }
}
