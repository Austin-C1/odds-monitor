package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyOrderTrackingRepository
import com.wrbug.polymarketbot.repository.CopyTradingFollowRuleRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.FilteredOrderRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.repository.ProcessedTradeRepository
import com.wrbug.polymarketbot.repository.SellMatchDetailRepository
import com.wrbug.polymarketbot.repository.SellMatchRecordRepository
import com.wrbug.polymarketbot.service.common.BlockchainService
import com.wrbug.polymarketbot.service.common.MarketService
import com.wrbug.polymarketbot.service.common.PolymarketClobService
import com.wrbug.polymarketbot.service.copytrading.configs.CopyTradingFilterService
import com.wrbug.polymarketbot.service.copytrading.orders.OrderSigningService
import com.wrbug.polymarketbot.service.system.TelegramNotificationService
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions

class CopyOrderTrackingServiceAutoPauseTriggerTest {

    private val copyOrderTrackingRepository = mock(CopyOrderTrackingRepository::class.java)
    private val sellMatchRecordRepository = mock(SellMatchRecordRepository::class.java)
    private val sellMatchDetailRepository = mock(SellMatchDetailRepository::class.java)
    private val processedTradeRepository = mock(ProcessedTradeRepository::class.java)
    private val filteredOrderRepository = mock(FilteredOrderRepository::class.java)
    private val copyTradingRepository = mock(CopyTradingRepository::class.java)
    private val copyTradingFollowRuleRepository = mock(CopyTradingFollowRuleRepository::class.java)
    private val accountRepository = mock(AccountRepository::class.java)
    private val filterService = mock(CopyTradingFilterService::class.java)
    private val leaderRepository = mock(LeaderRepository::class.java)
    private val orderSigningService = mock(OrderSigningService::class.java)
    private val blockchainService = mock(BlockchainService::class.java)
    private val clobService = mock(PolymarketClobService::class.java)
    private val retrofitFactory = mock(RetrofitFactory::class.java)
    private val cryptoUtils = mock(CryptoUtils::class.java)
    private val marketService = mock(MarketService::class.java)
    private val copyTradingDailyMetricsService = mock(CopyTradingDailyMetricsService::class.java)
    private val telegramNotificationService = mock(TelegramNotificationService::class.java)

    private val service = CopyOrderTrackingService(
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
        telegramNotificationService = telegramNotificationService
    )

    @Test
    fun `processBuyTrade does not recalculate leader drawdown before settlement`() = runTest {
        `when`(copyTradingRepository.findByLeaderIdAndEnabledTrue(3L))
            .thenReturn(listOf(CopyTrading(id = 1L, accountId = 8L, leaderId = 3L)))
        `when`(accountRepository.findById(8L)).thenReturn(java.util.Optional.empty())

        service.processBuyTrade(
            leaderId = 3L,
            trade = trade(side = "BUY"),
            source = "activity-ws"
        )
    }

    @Test
    fun `processSellTrade does not recalculate leader drawdown before settlement completes`() = runTest {
        `when`(copyTradingRepository.findByLeaderIdAndEnabledTrue(3L))
            .thenReturn(listOf(CopyTrading(id = 1L, accountId = 8L, leaderId = 3L, supportSell = false)))

        service.processSellTrade(
            leaderId = 3L,
            trade = trade(side = "SELL")
        )
    }

    private fun trade(side: String) = TradeResponse(
        id = "trade-1",
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
