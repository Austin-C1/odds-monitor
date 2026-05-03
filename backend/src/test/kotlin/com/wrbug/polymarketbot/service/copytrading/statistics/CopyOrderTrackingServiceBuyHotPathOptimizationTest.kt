package com.wrbug.polymarketbot.service.copytrading.statistics

import com.google.gson.Gson
import com.wrbug.polymarketbot.api.NewOrderResponse
import com.wrbug.polymarketbot.api.OrderbookEntry
import com.wrbug.polymarketbot.api.OrderbookResponse
import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.api.PositionResponse
import com.wrbug.polymarketbot.api.SignedOrderObject
import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.dto.PositionListResponse
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.entity.CopyTradingFollowRule
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import retrofit2.Response
import java.math.BigDecimal

class CopyOrderTrackingServiceBuyHotPathOptimizationTest {

    private val copyOrderTrackingRepository = mock(CopyOrderTrackingRepository::class.java)
    private val sellMatchRecordRepository = mock(SellMatchRecordRepository::class.java)
    private val sellMatchDetailRepository = mock(SellMatchDetailRepository::class.java)
    private val processedTradeRepository = mock(ProcessedTradeRepository::class.java)
    private val filteredOrderRepository = mock(FilteredOrderRepository::class.java)
    private val copyTradingRepository = mock(CopyTradingRepository::class.java)
    private val copyTradingFollowRuleRepository = mock(CopyTradingFollowRuleRepository::class.java)
    private val accountRepository = mock(AccountRepository::class.java)
    private val leaderRepository = mock(LeaderRepository::class.java)    private val orderSigningService = mock(OrderSigningService::class.java)
    private val blockchainService = mock(BlockchainService::class.java)
    private val clobService = mock(PolymarketClobService::class.java)
    private val retrofitFactory = mock(RetrofitFactory::class.java)
    private val cryptoUtils = mock(CryptoUtils::class.java)
    private val marketService = mock(MarketService::class.java)
    private val copyTradingDailyMetricsService = mock(CopyTradingDailyMetricsService::class.java)
    private val telegramNotificationService = mock(TelegramNotificationService::class.java)
    private val clobApi = mock(PolymarketClobApi::class.java)

    @Test
    fun `processBuyTrade skips auto order work while execution is sealed`() = runTest {
        val account = demoAccount()
        val first = demoCopyTrading(id = 1L, accountId = account.id!!)
        val second = demoCopyTrading(id = 2L, accountId = account.id!!)
        val accountService = mock(AccountService::class.java)
        val service = buildService(realFilterService(accountService))

        stubHotPathHappyCase(account, listOf(first, second))
        `when`(accountService.getAllPositions()).thenReturn(Result.failure(IllegalStateException("position snapshot should be preloaded")))
        `when`(copyOrderTrackingRepository.sumCurrentPositionValueByMarketAndOutcomeIndex(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(BigDecimal.ZERO)
        `when`(blockchainService.getPositions(account.proxyAddress)).thenReturn(
            Result.success(
                listOf(
                    PositionResponse(
                        proxyWallet = account.proxyAddress,
                        conditionId = "condition-1",
                        size = 7.0,
                        curPrice = 0.64,
                        currentValue = 4.48,
                        initialValue = 4.2,
                        cashPnl = 0.28,
                        percentPnl = 6.7,
                        title = "Demo market",
                        outcome = "YES",
                        outcomeIndex = 0
                    )
                )
            )
        )

        val result = service.processBuyTrade(leaderId = 3L, trade = trade(), source = "activity-ws")

        assertTrue(result.isSuccess)
        verify(accountService, never()).getAllPositions()
        verify(blockchainService, never()).getPositions(account.proxyAddress)
    }

    @Test
    fun `processBuyTrade hot path should not use the standard authenticated CLOB client`() = runTest {
        val account = demoAccount()
        val copyTrading = demoCopyTrading(id = 1L, accountId = account.id!!)
        val accountService = mock(AccountService::class.java)
        val service = buildService(realFilterService(accountService))

        stubHotPathHappyCase(account, listOf(copyTrading))
        `when`(accountService.getAllPositions()).thenReturn(
            Result.success(
                PositionListResponse(
                    currentPositions = emptyList(),
                    historyPositions = emptyList()
                )
            )
        )
        `when`(copyOrderTrackingRepository.sumCurrentPositionValueByMarketAndOutcomeIndex(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(BigDecimal.ZERO)
        `when`(blockchainService.getPositions(account.proxyAddress)).thenReturn(Result.success(emptyList()))
        val result = service.processBuyTrade(leaderId = 3L, trade = trade(), source = "activity-ws")

        assertTrue(result.isSuccess)
        verify(retrofitFactory, never()).createClobApi(
            account.apiKey!!,
            "api-secret",
            "api-passphrase",
            account.walletAddress
        )
    }

    private fun buildService(filterService: CopyTradingFilterService) = CopyOrderTrackingService(
        copyOrderTrackingRepository = copyOrderTrackingRepository,
        sellMatchRecordRepository = sellMatchRecordRepository,
        sellMatchDetailRepository = sellMatchDetailRepository,
        processedTradeRepository = processedTradeRepository,
        filteredOrderRepository = filteredOrderRepository,
        copyTradingRepository = copyTradingRepository,
        copyTradingFollowRuleRepository = copyTradingFollowRuleRepository,
        accountRepository = accountRepository,
        filterService = filterService,
        leaderRepository = leaderRepository,        orderSigningService = orderSigningService,
        blockchainService = blockchainService,
        clobService = clobService,
        retrofitFactory = retrofitFactory,
        cryptoUtils = cryptoUtils,
        marketService = marketService,
        copyTradingDailyMetricsService = copyTradingDailyMetricsService,
        telegramNotificationService = telegramNotificationService
    )

    private fun realFilterService(accountService: AccountService): CopyTradingFilterService {
        val jsonUtils = JsonUtils(Gson())
        jsonUtils.init()
        return CopyTradingFilterService(
            clobService = clobService,
            accountService = accountService,
            copyOrderTrackingRepository = copyOrderTrackingRepository,
            jsonUtils = jsonUtils
        )
    }

    private suspend fun stubHotPathHappyCase(account: Account, copyTradings: List<CopyTrading>) {
        val followRules = copyTradings.map {
            CopyTradingFollowRule(
                id = it.id,
                copyTradingId = it.id!!,
                minLeaderAmount = BigDecimal.ZERO,
                maxLeaderAmount = null,
                followAmount = BigDecimal("10"),
                followMaxAmount = BigDecimal("10"),
                sortOrder = 0
            )
        }

        `when`(copyTradingRepository.findByLeaderIdAndEnabledTrue(3L)).thenReturn(copyTradings)
        `when`(accountRepository.findAllById(listOf(account.id!!))).thenReturn(listOf(account))
        `when`(copyTradingFollowRuleRepository.findByCopyTradingIdIn(copyTradings.map { it.id!! })).thenReturn(followRules)
        `when`(clobService.getFastOrderbookByTokenId("token-1")).thenReturn(Result.success(orderbook()))
        `when`(clobService.getFastFeeRate("token-1")).thenReturn(Result.success(0))
        `when`(marketService.getNegRiskByConditionId("condition-1")).thenReturn(false)
        `when`(copyTradingDailyMetricsService.getMetrics(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong()))
            .thenReturn(CopyTradingDailyMetrics(todayBuyOrderCount = 0, todaySettledRealizedPnl = BigDecimal.ZERO))
        `when`(cryptoUtils.decrypt("encrypted-private-key")).thenReturn("private-key")
        `when`(cryptoUtils.decrypt("encrypted-secret")).thenReturn("api-secret")
        `when`(cryptoUtils.decrypt("encrypted-passphrase")).thenReturn("api-passphrase")
        `when`(orderSigningService.getExchangeContract(false)).thenReturn("exchange-contract")
        `when`(orderSigningService.getSignatureTypeForWalletType(account.walletType)).thenReturn(0)
        `when`(retrofitFactory.createFastTradingClobApi(
            account.apiKey!!,
            "api-secret",
            "api-passphrase",
            account.walletAddress
        )).thenReturn(clobApi)
        `when`(
            orderSigningService.createAndSignOrder(
                privateKey = "private-key",
                makerAddress = account.proxyAddress,
                tokenId = "token-1",
                side = "BUY",
                price = "0.58",
                size = "17.24137931",
                signatureType = 0,
                expiration = "0",
                exchangeContract = "exchange-contract"
            )
        ).thenReturn(
            SignedOrderObject(
                salt = 1L,
                maker = account.proxyAddress,
                signer = account.walletAddress,
                taker = "0x0000000000000000000000000000000000000000",
                tokenId = "token-1",
                makerAmount = "1000",
                takerAmount = "1000",
                expiration = "0",
                side = "BUY",
                signatureType = 0,
                timestamp = "1713420000000",
                metadata = "0x0000000000000000000000000000000000000000000000000000000000000000",
                builder = "0x0000000000000000000000000000000000000000000000000000000000000000",
                signature = "0xsignature"
            )
        )
        `when`(clobApi.createOrder(anyNonNull())).thenReturn(
            Response.success(
                NewOrderResponse(
                    success = true,
                    orderId = "0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
                )
            )
        )
        `when`(copyOrderTrackingRepository.save(org.mockito.ArgumentMatchers.any())).thenAnswer { it.arguments[0] }
    }

    private fun demoAccount() = Account(
        id = 1L,
        privateKey = "encrypted-private-key",
        walletAddress = "0x0000000000000000000000000000000000000001",
        proxyAddress = "0x0000000000000000000000000000000000000011",
        apiKey = "api-key",
        apiSecret = "encrypted-secret",
        apiPassphrase = "encrypted-passphrase"
    )

    private fun demoCopyTrading(id: Long, accountId: Long) = CopyTrading(
        id = id,
        accountId = accountId,
        leaderId = 3L,
        followSettingsEnabled = true,
        maxPositionValue = BigDecimal("20")
    )

    private fun trade() = TradeResponse(
        id = "trade-1",
        market = "condition-1",
        side = "BUY",
        price = "0.55",
        size = "100",
        timestamp = "2026-04-17T10:00:00Z",
        user = "0xleader",
        outcomeIndex = 0,
        tokenId = "token-1"
    )

    private fun orderbook() = OrderbookResponse(
        bids = listOf(OrderbookEntry(price = "0.54", size = "100")),
        asks = listOf(OrderbookEntry(price = "0.55", size = "100"))
    )

    private fun <T> anyNonNull(): T {
        org.mockito.ArgumentMatchers.any<T>()
        return null as T
    }
}
