package com.wrbug.polymarketbot.service.copytrading.configs

import com.google.gson.Gson
import com.wrbug.polymarketbot.api.OrderbookEntry
import com.wrbug.polymarketbot.api.OrderbookResponse
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.repository.CopyOrderTrackingRepository
import com.wrbug.polymarketbot.service.accounts.AccountService
import com.wrbug.polymarketbot.service.common.PolymarketClobService
import com.wrbug.polymarketbot.util.JsonUtils
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.math.BigDecimal

class CopyTradingFilterServiceHotPathOptimizationTest {

    private val clobService = mock(PolymarketClobService::class.java)
    private val accountService = mock(AccountService::class.java)
    private val copyOrderTrackingRepository = mock(CopyOrderTrackingRepository::class.java)
    private val jsonUtils = JsonUtils(Gson()).apply { init() }
    private val service = CopyTradingFilterService(
        clobService = clobService,
        accountService = accountService,
        copyOrderTrackingRepository = copyOrderTrackingRepository,
        jsonUtils = jsonUtils
    )

    @Test
    fun `checkFilters loads positions for a single account when snapshots are missing`() = runTest {
        val copyTrading = CopyTrading(
            id = 1L,
            accountId = 2L,
            leaderId = 3L,
            maxPositionValue = BigDecimal("20")
        )
        `when`(accountService.getCurrentPositionsForAccount(2L)).thenReturn(Result.success(emptyList()))
        `when`(
            copyOrderTrackingRepository.sumCurrentPositionValueByMarketAndOutcomeIndex(
                1L,
                "condition-1",
                0
            )
        ).thenReturn(BigDecimal.ZERO)

        val result = service.checkFilters(
            copyTrading = copyTrading,
            tokenId = "token-1",
            copyOrderAmount = BigDecimal("10"),
            marketId = "condition-1",
            outcomeIndex = 0
        )

        assertTrue(result.isPassed)
        verify(accountService).getCurrentPositionsForAccount(2L)
        verify(accountService, never()).getAllPositions()
    }

    @Test
    fun `checkFilters uses fast orderbook when shared data is absent`() = runTest {
        val copyTrading = CopyTrading(
            id = 1L,
            accountId = 2L,
            leaderId = 3L,
            maxSpread = BigDecimal("0.05")
        )
        `when`(clobService.getFastOrderbookByTokenId("token-1")).thenReturn(
            Result.success(
                OrderbookResponse(
                    bids = listOf(OrderbookEntry(price = "0.51", size = "100")),
                    asks = listOf(OrderbookEntry(price = "0.53", size = "100"))
                )
            )
        )

        val result = service.checkFilters(
            copyTrading = copyTrading,
            tokenId = "token-1"
        )

        assertTrue(result.isPassed)
        verify(clobService).getFastOrderbookByTokenId("token-1")
        verify(clobService, never()).getOrderbookByTokenId("token-1")
    }
}
