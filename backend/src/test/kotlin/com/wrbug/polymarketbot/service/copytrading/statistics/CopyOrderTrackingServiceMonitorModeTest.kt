package com.wrbug.polymarketbot.service.copytrading.statistics

import com.google.gson.Gson
import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyOrderTrackingRepository
import com.wrbug.polymarketbot.repository.CopyTradingFollowRuleRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.FilteredOrderRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.repository.ProcessedTradeRepository
import com.wrbug.polymarketbot.repository.SellMatchDetailRepository
import com.wrbug.polymarketbot.repository.SellMatchRecordRepository
import com.wrbug.polymarketbot.service.accounts.AccountService
import com.wrbug.polymarketbot.service.common.BlockchainService
import com.wrbug.polymarketbot.service.common.MarketService
import com.wrbug.polymarketbot.service.common.PolymarketClobService
import com.wrbug.polymarketbot.service.copytrading.configs.CopyTradingFilterService
import com.wrbug.polymarketbot.service.copytrading.orders.OrderSigningService
import com.wrbug.polymarketbot.service.system.TelegramNotificationService
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.util.JsonUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

class CopyOrderTrackingServiceMonitorModeTest {

    private val copyOrderTrackingRepository = mock(CopyOrderTrackingRepository::class.java)
    private val sellMatchRecordRepository = mock(SellMatchRecordRepository::class.java)
    private val sellMatchDetailRepository = mock(SellMatchDetailRepository::class.java)
    private val processedTradeRepository = mock(ProcessedTradeRepository::class.java)
    private val filteredOrderRepository = mock(FilteredOrderRepository::class.java)
    private val copyTradingRepository = mock(CopyTradingRepository::class.java)
    private val copyTradingFollowRuleRepository = mock(CopyTradingFollowRuleRepository::class.java)
    private val accountRepository = mock(AccountRepository::class.java)
    private val leaderRepository = mock(LeaderRepository::class.java)
    private val orderSigningService = mock(OrderSigningService::class.java)
    private val blockchainService = mock(BlockchainService::class.java)
    private val clobService = mock(PolymarketClobService::class.java)
    private val retrofitFactory = mock(RetrofitFactory::class.java)
    private val cryptoUtils = mock(CryptoUtils::class.java)
    private val marketService = mock(MarketService::class.java)
    private val copyTradingDailyMetricsService = mock(CopyTradingDailyMetricsService::class.java)
    private val telegramNotificationService = mock(TelegramNotificationService::class.java)
    private val leaderMonitorAlertService = mock(LeaderMonitorAlertService::class.java)

    @Test
    fun `processTrade should skip auto order flow when monitor mode is enabled`() = runTest {
        val service = buildService()
        val trade = TradeResponse(
            id = "monitor-trade-1",
            market = "condition-1",
            side = "BUY",
            price = "0.55",
            size = "50",
            timestamp = "2026-04-23T19:30:00Z",
            user = "0xleader",
            outcomeIndex = 0,
            tokenId = "token-1"
        )

        `when`(processedTradeRepository.findByLeaderIdAndLeaderTradeId(7L, trade.id)).thenReturn(null)
        `when`(processedTradeRepository.save(any())).thenAnswer { it.arguments[0] }
        `when`(telegramNotificationService.isMonitorModeEnabled()).thenReturn(true)

        val result = service.processTrade(7L, trade, "activity-ws")

        assertTrue(result.isSuccess)
        verify(leaderMonitorAlertService).processTrade(7L, trade)
        verify(copyTradingRepository, never()).findByLeaderIdAndEnabledTrue(anyLong())
        verify(orderSigningService, never()).getSignatureTypeForWalletType(anyString())
    }

    private fun buildService(): CopyOrderTrackingService {
        val accountService = mock(AccountService::class.java)
        val jsonUtils = JsonUtils(Gson())
        jsonUtils.init()
        val filterService = CopyTradingFilterService(
            clobService = clobService,
            accountService = accountService,
            copyOrderTrackingRepository = copyOrderTrackingRepository,
            jsonUtils = jsonUtils
        )

        return CopyOrderTrackingService(
            copyOrderTrackingRepository = copyOrderTrackingRepository,
            sellMatchRecordRepository = sellMatchRecordRepository,
            sellMatchDetailRepository = sellMatchDetailRepository,
            processedTradeRepository = processedTradeRepository,
            filteredOrderRepository = filteredOrderRepository,
            copyTradingRepository = copyTradingRepository,
            copyTradingFollowRuleRepository = copyTradingFollowRuleRepository,
            accountRepository = accountRepository,
            filterService = filterService,
            leaderRepository = leaderRepository,
            orderSigningService = orderSigningService,
            blockchainService = blockchainService,
            clobService = clobService,
            retrofitFactory = retrofitFactory,
            cryptoUtils = cryptoUtils,
            marketService = marketService,
            copyTradingDailyMetricsService = copyTradingDailyMetricsService,
            telegramNotificationService = telegramNotificationService,
            leaderMonitorAlertService = leaderMonitorAlertService
        )
    }
}
