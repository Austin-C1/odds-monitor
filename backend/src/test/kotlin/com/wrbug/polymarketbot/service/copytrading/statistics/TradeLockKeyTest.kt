package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.api.TradeResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class TradeLockKeyTest {

    @Test
    fun `sell trades for the same market and outcome share the same lock key`() {
        val first = buildTradeProcessingKey(
            leaderId = 7L,
            trade = trade(id = "sell-1", side = "SELL")
        )
        val second = buildTradeProcessingKey(
            leaderId = 7L,
            trade = trade(id = "sell-2", side = "SELL")
        )

        assertEquals(first, second)
    }

    @Test
    fun `buy trades stay isolated by leader trade id`() {
        val first = buildTradeProcessingKey(
            leaderId = 7L,
            trade = trade(id = "buy-1", side = "BUY")
        )
        val second = buildTradeProcessingKey(
            leaderId = 7L,
            trade = trade(id = "buy-2", side = "BUY")
        )

        assertNotEquals(first, second)
    }

    private fun trade(id: String, side: String) = TradeResponse(
        id = id,
        market = "condition-1",
        side = side,
        price = "0.55",
        size = "10",
        timestamp = "2026-04-17T10:00:00Z",
        user = "0xleader",
        outcomeIndex = 0,
        tokenId = "token-1"
    )
}
