package com.wrbug.polymarketbot.service.accounts

import com.wrbug.polymarketbot.dto.AccountPositionDto
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyOrderTrackingRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.LeaderCopyTradingControlRepository
import com.wrbug.polymarketbot.service.common.MarketPriceService
import com.wrbug.polymarketbot.service.copytrading.statistics.LeaderProfitTakeEvaluator
import com.wrbug.polymarketbot.service.system.RelayClientService
import com.wrbug.polymarketbot.service.system.SystemConfigService
import com.wrbug.polymarketbot.service.system.TelegramNotificationService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.context.support.StaticMessageSource

class PositionCheckServiceTest {

    private val positionPollingService = mock(PositionPollingService::class.java)
    private val accountService = mock(AccountService::class.java)
    private val copyTradingRepository = mock(CopyTradingRepository::class.java)
    private val copyOrderTrackingRepository = mock(CopyOrderTrackingRepository::class.java)
    private val leaderCopyTradingControlRepository = mock(LeaderCopyTradingControlRepository::class.java)
    private val systemConfigService = mock(SystemConfigService::class.java)
    private val relayClientService = mock(RelayClientService::class.java)
    private val telegramNotificationService = mock(TelegramNotificationService::class.java)
    private val accountRepository = mock(AccountRepository::class.java)
    private val marketPriceService = mock(MarketPriceService::class.java)
    private val leaderProfitTakeEvaluator = mock(LeaderProfitTakeEvaluator::class.java)
    private val positionSettlementWriter = mock(PositionSettlementWriter::class.java)
    private val messageSource = StaticMessageSource()

    private val service = PositionCheckService(
        positionPollingService = positionPollingService,
        accountService = accountService,
        copyTradingRepository = copyTradingRepository,
        copyOrderTrackingRepository = copyOrderTrackingRepository,
        leaderCopyTradingControlRepository = leaderCopyTradingControlRepository,
        systemConfigService = systemConfigService,
        relayClientService = relayClientService,
        telegramNotificationService = telegramNotificationService,
        accountRepository = accountRepository,
        messageSource = messageSource,
        marketPriceService = marketPriceService,
        leaderProfitTakeEvaluator = leaderProfitTakeEvaluator,
        positionSettlementWriter = positionSettlementWriter
    )

    @Test
    fun `loadCurrentPositionsByAccountAndMarket only requests pending accounts and keeps successful results`() = runTest {
        val account1Position = accountPosition(accountId = 1L, marketId = "condition-1", outcomeIndex = 0)

        `when`(accountService.getCurrentPositionsForAccount(1L)).thenReturn(Result.success(listOf(account1Position)))
        `when`(accountService.getCurrentPositionsForAccount(2L)).thenReturn(
            Result.failure(IllegalStateException("network timeout"))
        )

        val result = service.loadCurrentPositionsByAccountAndMarket(setOf(1L, 2L))

        assertEquals(listOf(account1Position), result["1_condition-1_0"])
        assertNull(result["2_condition-1_0"])
        verify(accountService).getCurrentPositionsForAccount(1L)
        verify(accountService).getCurrentPositionsForAccount(2L)
        verify(accountService, never()).getAllPositions()
    }

    private fun accountPosition(accountId: Long, marketId: String, outcomeIndex: Int) = AccountPositionDto(
        accountId = accountId,
        accountName = "Demo-$accountId",
        walletAddress = "0x1234567890123456789012345678901234567890",
        proxyAddress = "0x1234567890123456789012345678901234567891",
        marketId = marketId,
        marketTitle = "Demo Market",
        marketSlug = "demo-market",
        eventSlug = "demo-event",
        marketIcon = null,
        side = "YES",
        outcomeIndex = outcomeIndex,
        quantity = "1",
        originalQuantity = "1",
        avgPrice = "0.55",
        currentPrice = "0.60",
        currentValue = "0.60",
        initialValue = "0.55",
        pnl = "0.05",
        percentPnl = "9.09",
        realizedPnl = null,
        percentRealizedPnl = null,
        redeemable = false,
        mergeable = false,
        endDate = null,
        isCurrent = true
    )
}
