package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.api.PositionResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LeaderCurrentPositionValueResolverTest {

    @Test
    fun `extractLeaderCurrentPositionValue should match by market and outcome index`() {
        val positions = listOf(
            PositionResponse(
                proxyWallet = "0xleader",
                conditionId = "market-a",
                outcome = "No",
                outcomeIndex = 1,
                currentValue = 1645.9456
            ),
            PositionResponse(
                proxyWallet = "0xleader",
                conditionId = "market-a",
                outcome = "Yes",
                outcomeIndex = 0,
                currentValue = 2003.2101
            ),
            PositionResponse(
                proxyWallet = "0xleader",
                conditionId = "market-b",
                outcome = "Yes",
                outcomeIndex = 0,
                currentValue = 9999.0
            )
        )

        assertEquals(
            "2003.2101",
            extractLeaderCurrentPositionValue(
                positions = positions,
                marketId = "market-a",
                outcomeIndex = 0,
                outcome = "Yes"
            )
        )
    }

    @Test
    fun `extractLeaderCurrentPositionValue should fall back to outcome name when outcome index is missing`() {
        val positions = listOf(
            PositionResponse(
                proxyWallet = "0xleader",
                conditionId = "market-a",
                outcome = "Yes",
                outcomeIndex = null,
                currentValue = 2003.2101
            )
        )

        assertEquals(
            "2003.2101",
            extractLeaderCurrentPositionValue(
                positions = positions,
                marketId = "market-a",
                outcomeIndex = 0,
                outcome = "Yes"
            )
        )
    }
}
