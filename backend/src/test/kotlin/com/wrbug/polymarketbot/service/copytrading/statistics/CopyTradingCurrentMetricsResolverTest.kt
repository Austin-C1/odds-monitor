package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.dto.AccountPositionDto
import com.wrbug.polymarketbot.entity.CopyOrderTracking
import com.wrbug.polymarketbot.entity.CopyTrading
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CopyTradingCurrentMetricsResolverTest {

    @Test
    fun `resolveCopyTradingCurrentMetrics should assign current position pnl to a matching copy trading`() {
        val copyTrading = CopyTrading(id = 11L, accountId = 7L, leaderId = 3L)
        val metrics = resolveCopyTradingCurrentMetrics(
            copyTradings = listOf(copyTrading),
            activeOrdersByCopyTradingId = mapOf(
                11L to listOf(
                    activeOrder(
                        copyTradingId = 11L,
                        accountId = 7L,
                        marketId = "market-a",
                        outcomeIndex = 0,
                        remainingQuantity = "10",
                        price = "0.40"
                    )
                )
            ),
            currentPositionsByAccount = mapOf(
                7L to listOf(
                    currentPosition(
                        accountId = 7L,
                        marketId = "market-a",
                        outcomeIndex = 0,
                        currentValue = "120",
                        pnl = "40"
                    )
                )
            )
        )

        assertBigDecimalEquals("120", metrics.getValue(11L).currentPositionValue)
        assertBigDecimalEquals("40", metrics.getValue(11L).unrealizedPnl)
    }

    @Test
    fun `resolveCopyTradingCurrentMetrics should allocate merged account positions by remaining cost basis`() {
        val first = CopyTrading(id = 11L, accountId = 7L, leaderId = 3L)
        val second = CopyTrading(id = 12L, accountId = 7L, leaderId = 4L)

        val metrics = resolveCopyTradingCurrentMetrics(
            copyTradings = listOf(first, second),
            activeOrdersByCopyTradingId = mapOf(
                11L to listOf(
                    activeOrder(
                        copyTradingId = 11L,
                        accountId = 7L,
                        marketId = "market-a",
                        outcomeIndex = 0,
                        remainingQuantity = "2",
                        price = "10"
                    )
                ),
                12L to listOf(
                    activeOrder(
                        copyTradingId = 12L,
                        accountId = 7L,
                        marketId = "market-a",
                        outcomeIndex = 0,
                        remainingQuantity = "8",
                        price = "10"
                    )
                )
            ),
            currentPositionsByAccount = mapOf(
                7L to listOf(
                    currentPosition(
                        accountId = 7L,
                        marketId = "market-a",
                        outcomeIndex = 0,
                        currentValue = "150",
                        pnl = "50"
                    )
                )
            )
        )

        assertBigDecimalEquals("30", metrics.getValue(11L).currentPositionValue)
        assertBigDecimalEquals("10", metrics.getValue(11L).unrealizedPnl)
        assertBigDecimalEquals("120", metrics.getValue(12L).currentPositionValue)
        assertBigDecimalEquals("40", metrics.getValue(12L).unrealizedPnl)
    }

    private fun activeOrder(
        copyTradingId: Long,
        accountId: Long,
        marketId: String,
        outcomeIndex: Int,
        remainingQuantity: String,
        price: String,
    ) = CopyOrderTracking(
        id = copyTradingId,
        copyTradingId = copyTradingId,
        accountId = accountId,
        leaderId = 3L,
        marketId = marketId,
        side = outcomeIndex.toString(),
        outcomeIndex = outcomeIndex,
        buyOrderId = "buy-$copyTradingId",
        leaderBuyTradeId = "leader-$copyTradingId",
        quantity = BigDecimal(remainingQuantity),
        price = BigDecimal(price),
        remainingQuantity = BigDecimal(remainingQuantity),
        source = "test"
    )

    private fun currentPosition(
        accountId: Long,
        marketId: String,
        outcomeIndex: Int,
        currentValue: String,
        pnl: String,
    ) = AccountPositionDto(
        accountId = accountId,
        accountName = "demo",
        walletAddress = "0x1111111111111111111111111111111111111111",
        proxyAddress = "0x2222222222222222222222222222222222222222",
        marketId = marketId,
        marketTitle = "Demo Market",
        marketSlug = "demo-market",
        eventSlug = "demo-event",
        marketIcon = null,
        side = "Yes",
        outcomeIndex = outcomeIndex,
        quantity = "10",
        originalQuantity = "10",
        avgPrice = "0.4",
        currentPrice = "0.6",
        currentValue = currentValue,
        initialValue = "80",
        pnl = pnl,
        percentPnl = "50",
        realizedPnl = null,
        percentRealizedPnl = null,
        redeemable = false,
        mergeable = false,
        endDate = null,
        isCurrent = true
    )

    private fun assertBigDecimalEquals(expected: String, actual: BigDecimal) {
        assertTrue(
            actual.compareTo(BigDecimal(expected)) == 0,
            "Expected $expected but was ${actual.toPlainString()}"
        )
    }
}
