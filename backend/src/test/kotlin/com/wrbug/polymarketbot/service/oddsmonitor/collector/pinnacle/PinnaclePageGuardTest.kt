package com.wrbug.polymarketbot.service.oddsmonitor.collector.pinnacle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PinnaclePageGuardTest {
    @Test
    fun `classifies cloudflare block as network failure`() {
        val guard = PinnaclePageGuard()
        val exception = assertThrows(PinnacleCollectionException::class.java) {
            guard.ensureUsableFootballHtml("<title>Attention Required! | Cloudflare</title><div id=\"cf-error-details\"></div>")
        }

        assertEquals("failed_network", exception.status)
    }
}
