package com.wrbug.polymarketbot.service.copytrading.statistics

import com.google.gson.Gson
import com.wrbug.polymarketbot.api.OrderbookEntry
import com.wrbug.polymarketbot.api.OrderbookResponse
import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.entity.CopyTradingFollowRule
import com.wrbug.polymarketbot.entity.FilteredOrder
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
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.argThat
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

class CopyOrderTrackingServiceFollowRuleSkipTest {

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

    @Test
    fun `processBuyTrade records skipped result when follow rules are missing`() = runTest {
        val account = demoAccount()
        val copyTrading = demoCopyTrading()
        val service = buildService()

        stubSharedContext(account, copyTrading)
        `when`(copyTradingFollowRuleRepository.findByCopyTradingIdIn(listOf(copyTrading.id!!))).thenReturn(emptyList())

        val result = service.processBuyTrade(leaderId = copyTrading.leaderId, trade = trade(), source = "activity-ws")

        assertTrue(result.isSuccess)
        verify(filteredOrderRepository, never()).save(any())
    }

    @Test
    fun `processBuyTrade records skipped result when leader amount matches no follow rule`() = runTest {
        val account = demoAccount()
        val copyTrading = demoCopyTrading()
        val service = buildService()

        stubSharedContext(account, copyTrading)
        `when`(copyTradingFollowRuleRepository.findByCopyTradingIdIn(listOf(copyTrading.id!!))).thenReturn(
            listOf(
                CopyTradingFollowRule(
                    id = 901L,
                    copyTradingId = copyTrading.id!!,
                    minLeaderAmount = BigDecimal("100"),
                    maxLeaderAmount = null,
                    followAmount = BigDecimal("20"),
                    followMaxAmount = BigDecimal("20"),
                    sortOrder = 1
                )
            )
        )

        val result = service.processBuyTrade(leaderId = copyTrading.leaderId, trade = trade(), source = "activity-ws")

        assertTrue(result.isSuccess)
        verify(filteredOrderRepository, never()).save(any())
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
            telegramNotificationService = telegramNotificationService
        )
    }

    private fun stubSharedContext(account: Account, copyTrading: CopyTrading) {
        `when`(copyTradingRepository.findByLeaderIdAndEnabledTrue(copyTrading.leaderId)).thenReturn(listOf(copyTrading))
        `when`(accountRepository.findAllById(listOf(account.id!!))).thenReturn(listOf(account))
        runBlocking {
            `when`(clobService.getFastOrderbookByTokenId("token-1")).thenReturn(Result.success(orderbook()))
            `when`(clobService.getFastFeeRate("token-1")).thenReturn(Result.success(0))
            `when`(marketService.getNegRiskByConditionId("condition-1")).thenReturn(false)
            `when`(blockchainService.getPositions(account.proxyAddress)).thenReturn(Result.success(emptyList()))
        }
        `when`(orderSigningService.getExchangeContract(false)).thenReturn("0x4bfb41d5b3570defd03c39a9a4d8de6bd8b8982e")
        `when`(filteredOrderRepository.save(any())).thenAnswer { it.arguments[0] }
    }

    private fun demoAccount() = Account(
        id = 6L,
        privateKey = "encrypted-private-key",
        walletAddress = "0x0000000000000000000000000000000000000006",
        proxyAddress = "0x0000000000000000000000000000000000000066",
        apiKey = "api-key",
        apiSecret = "encrypted-secret",
        apiPassphrase = "encrypted-passphrase",
        accountName = "DemoAccount"
    )

    private fun demoCopyTrading() = CopyTrading(
        id = 20L,
        accountId = 6L,
        leaderId = 7L,
        enabled = true,
        followSettingsEnabled = true,
        pushFilteredOrders = true,
        configName = "rule-missing-demo"
    )

    private fun trade() = TradeResponse(
        id = "trade-skip-1",
        market = "condition-1",
        side = "BUY",
        price = "0.55",
        size = "50",
        timestamp = "2026-04-23T18:54:00Z",
        user = "0xleader",
        outcomeIndex = 0,
        tokenId = "token-1"
    )

    private fun orderbook() = OrderbookResponse(
        bids = listOf(OrderbookEntry(price = "0.54", size = "100")),
        asks = listOf(OrderbookEntry(price = "0.55", size = "100"))
    )
}
