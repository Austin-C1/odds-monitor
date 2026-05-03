package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.api.OrderbookEntry
import com.wrbug.polymarketbot.api.OrderbookResponse
import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.entity.Account
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import com.wrbug.polymarketbot.api.PolymarketClobApi

class CopyOrderTrackingServiceSellIsolationTest {

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
    private val publicClobApi = mock(PolymarketClobApi::class.java)

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
    fun `processSellTrade skips sell matching while execution is sealed`() = runTest {
        val leaderId = 3L
        val accountId = 8L
        val first = CopyTrading(id = 1L, accountId = accountId, leaderId = leaderId, supportSell = true)
        val second = CopyTrading(id = 2L, accountId = accountId, leaderId = leaderId, supportSell = true)
        val account = Account(
            id = accountId,
            privateKey = "encrypted-private-key",
            walletAddress = "0x0000000000000000000000000000000000000001",
            proxyAddress = "0x0000000000000000000000000000000000000002",
            apiKey = "api-key",
            apiSecret = "encrypted-secret",
            apiPassphrase = "encrypted-passphrase"
        )

        `when`(copyTradingRepository.findByLeaderIdAndEnabledTrue(leaderId)).thenReturn(listOf(first, second))
        `when`(accountRepository.findAllById(listOf(accountId))).thenReturn(listOf(account))
        `when`(clobService.getFastOrderbookByTokenId("token-1")).thenReturn(
            Result.success(
                OrderbookResponse(
                    bids = listOf(OrderbookEntry(price = "0.60", size = "10")),
                    asks = listOf(OrderbookEntry(price = "0.61", size = "10"))
                )
            )
        )
        `when`(clobService.getFastFeeRate("token-1")).thenReturn(Result.success(0))
        `when`(orderSigningService.getExchangeContract(false)).thenReturn("exchange-contract")
        `when`(retrofitFactory.createFastTradingClobApiWithoutAuth()).thenReturn(publicClobApi)
        `when`(
            copyOrderTrackingRepository.findUnmatchedBuyOrdersByOutcomeIndexBatch(
                listOf(1L, 2L),
                "condition-1",
                0
            )
        ).thenReturn(emptyList())

        val result = service.processSellTrade(leaderId, trade())

        assertTrue(result.isSuccess)
        verify(copyOrderTrackingRepository, never())
            .findUnmatchedBuyOrdersByOutcomeIndexBatch(listOf(1L, 2L), "condition-1", 0)
        verify(copyOrderTrackingRepository, never()).findUnmatchedBuyOrdersByOutcomeIndex(anyLong(), anyString(), anyInt())
    }

    @Test
    fun `processSellTrade does not retry sell matching while execution is sealed`() = runTest {
        val leaderId = 3L
        val accountId = 8L
        val copyTrading = CopyTrading(id = 1L, accountId = accountId, leaderId = leaderId, supportSell = true)
        val account = Account(
            id = accountId,
            privateKey = "encrypted-private-key",
            walletAddress = "0x0000000000000000000000000000000000000001",
            proxyAddress = "0x0000000000000000000000000000000000000002",
            apiKey = "api-key",
            apiSecret = "encrypted-secret",
            apiPassphrase = "encrypted-passphrase"
        )

        `when`(copyTradingRepository.findByLeaderIdAndEnabledTrue(leaderId)).thenReturn(listOf(copyTrading))
        `when`(accountRepository.findAllById(listOf(accountId))).thenReturn(listOf(account))
        `when`(clobService.getFastOrderbookByTokenId("token-1")).thenReturn(
            Result.success(
                OrderbookResponse(
                    bids = listOf(OrderbookEntry(price = "0.60", size = "10")),
                    asks = listOf(OrderbookEntry(price = "0.61", size = "10"))
                )
            )
        )
        `when`(clobService.getFastFeeRate("token-1")).thenReturn(Result.success(0))
        `when`(orderSigningService.getExchangeContract(false)).thenReturn("exchange-contract")
        `when`(retrofitFactory.createFastTradingClobApiWithoutAuth()).thenReturn(publicClobApi)
        `when`(
            copyOrderTrackingRepository.findUnmatchedBuyOrdersByOutcomeIndexBatch(
                listOf(1L),
                "condition-1",
                0
            )
        ).thenReturn(emptyList(), emptyList())

        val result = service.processSellTrade(leaderId, trade())

        assertTrue(result.isSuccess)
        verify(copyOrderTrackingRepository, never())
            .findUnmatchedBuyOrdersByOutcomeIndexBatch(listOf(1L), "condition-1", 0)
    }

    private fun trade() = TradeResponse(
        id = "trade-1",
        market = "condition-1",
        side = "SELL",
        price = "0.55",
        size = "10",
        timestamp = "2026-04-17T10:00:00Z",
        user = "0xleader",
        outcomeIndex = 0,
        tokenId = "token-1"
    )
}
