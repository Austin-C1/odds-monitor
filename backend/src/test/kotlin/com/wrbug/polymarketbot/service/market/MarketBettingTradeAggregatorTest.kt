package com.wrbug.polymarketbot.service.market

import com.wrbug.polymarketbot.api.UserActivityResponse
import com.wrbug.polymarketbot.dto.MarketBettingOutcomeDetail
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MarketBettingTradeAggregatorTest {

    @Test
    fun `summarizes traded shares by asset instead of current holder balance`() {
        val trades = listOf(
            trade(asset = "token-a", size = 100.0, price = 0.25),
            trade(asset = "token-a", size = 50.5, price = 0.30),
            trade(asset = "token-b", size = 20.25, price = 0.70),
            trade(asset = "token-b", size = null, price = 0.70)
        )

        val summary = MarketBettingTradeAggregator.summarizeByAsset(trades)

        assertEquals("150.5", summary["token-a"]?.tradedShares)
        assertEquals("40.15", summary["token-a"]?.tradedAmount)
        assertEquals("20.25", summary["token-b"]?.tradedShares)
        assertEquals("14.175", summary["token-b"]?.tradedAmount)
    }

    @Test
    fun `uses usdc trade amount when data api provides it`() {
        val trades = listOf(
            trade(asset = "token-a", size = 100.0, price = 0.25, usdcSize = 26.5),
            trade(asset = "token-a", size = 50.0, price = 0.30, usdcSize = 14.0)
        )

        val summary = MarketBettingTradeAggregator.summarizeByAsset(trades)

        assertEquals("150", summary["token-a"]?.tradedShares)
        assertEquals("40.5", summary["token-a"]?.tradedAmount)
    }

    @Test
    fun `scales outcome traded amounts when history page cap undershoots official volume`() {
        val outcomes = listOf(
            outcome("A", "100"),
            outcome("B", "300")
        )

        val normalized = normalizeOutcomeTradedAmounts(outcomes, marketVolume = 500.0)

        assertEquals("125", normalized[0].tradedAmount)
        assertEquals("375", normalized[1].tradedAmount)
    }

    private fun trade(asset: String, size: Double?, price: Double, usdcSize: Double? = null): UserActivityResponse =
        UserActivityResponse(
            proxyWallet = "0x0000000000000000000000000000000000000000",
            timestamp = 1,
            conditionId = "0x1",
            type = "TRADE",
            size = size,
            usdcSize = usdcSize,
            price = price,
            asset = asset
        )

    private fun outcome(name: String, amount: String): MarketBettingOutcomeDetail =
        MarketBettingOutcomeDetail(
            name = name,
            tokenId = name,
            odds = "0.5",
            tradedShares = "0",
            tradedAmount = amount,
            bidOrderAmount = "0",
            askOrderAmount = "0",
            topHolders = emptyList()
        )
}
