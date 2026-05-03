package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.api.OpenOrder
import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.entity.CopyOrderTracking
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.entity.SellMatchRecord
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyOrderTrackingRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.repository.SellMatchDetailRepository
import com.wrbug.polymarketbot.repository.SellMatchRecordRepository
import com.wrbug.polymarketbot.service.common.BlockchainService
import com.wrbug.polymarketbot.service.common.MarketService
import com.wrbug.polymarketbot.service.system.TelegramNotificationService
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.stubbing.Answer
import retrofit2.Response
import java.math.BigDecimal
import java.util.Optional

class OrderStatusUpdateServiceTest {

    private val sellMatchRecordRepository = mock(SellMatchRecordRepository::class.java)
    private val sellMatchDetailRepository = mock(SellMatchDetailRepository::class.java)
    private val copyTradingRepository = mock(CopyTradingRepository::class.java)
    private val accountRepository = mock(AccountRepository::class.java)
    private val copyOrderTrackingRepository = mock(CopyOrderTrackingRepository::class.java)
    private val leaderRepository = mock(LeaderRepository::class.java)
    private val retrofitFactory = mock(RetrofitFactory::class.java)
    private val cryptoUtils = mock(CryptoUtils::class.java)
    private val trackingService = mock(CopyOrderTrackingService::class.java)
    private val marketService = mock(MarketService::class.java)
    private val telegramNotificationService = mock(TelegramNotificationService::class.java)
    private val blockchainService = mock(BlockchainService::class.java)
    private val clobApi = mock(PolymarketClobApi::class.java)

    private val service = buildService()

    @Test
    fun `checkAndDeleteUnfilledOrders uses fast trading client`() = runTest {
        val order = buyOrder(id = 1L, status = "pending_fill")
        val account = account()

        `when`(copyOrderTrackingRepository.findByCreatedAtBeforeAndStatus(anyLong(), anyString()))
            .thenReturn(listOf(order))
        `when`(accountRepository.findById(7L)).thenReturn(Optional.of(account))
        `when`(cryptoUtils.decrypt(account.apiSecret!!)).thenReturn("decrypted")
        `when`(cryptoUtils.decrypt(account.apiPassphrase!!)).thenReturn("decrypted")
        `when`(
            retrofitFactory.createFastTradingClobApi(
                account.apiKey!!,
                "decrypted",
                "decrypted",
                account.walletAddress
            )
        ).thenReturn(clobApi)
        `when`(clobApi.getOrder(order.buyOrderId)).thenReturn(Response.success(openOrder(order.buyOrderId)))

        service.checkAndDeleteUnfilledOrders()

        verify(retrofitFactory).createFastTradingClobApi(
            account.apiKey!!,
            "decrypted",
            "decrypted",
            account.walletAddress
        )
        verify(retrofitFactory, never()).createClobApi(
            account.apiKey!!,
            "decrypted",
            "decrypted",
            account.walletAddress
        )
    }

    @Test
    fun `updatePendingSellOrderPrices recalculates drawdown once per leader after sell settlement`() = runTest {
        val record1 = sellRecord(id = 1L, copyTradingId = 11L)
        val record2 = sellRecord(id = 2L, copyTradingId = 12L, sellOrderId = "AUTO_FIFO_2")
        val copyTrading1 = copyTrading(id = 11L)
        val copyTrading2 = copyTrading(id = 12L)
        val account = account()

        `when`(sellMatchRecordRepository.findByPriceUpdatedFalse()).thenReturn(listOf(record1, record2))
        `when`(copyTradingRepository.findById(11L)).thenReturn(Optional.of(copyTrading1))
        `when`(copyTradingRepository.findById(12L)).thenReturn(Optional.of(copyTrading2))
        `when`(accountRepository.findById(7L)).thenReturn(Optional.of(account))
        `when`(cryptoUtils.decrypt(account.apiSecret!!)).thenReturn("decrypted")
        `when`(cryptoUtils.decrypt(account.apiPassphrase!!)).thenReturn("decrypted")
        `when`(
            retrofitFactory.createFastTradingClobApi(
                account.apiKey!!,
                "decrypted",
                "decrypted",
                account.walletAddress
            )
        ).thenReturn(clobApi)
        `when`(sellMatchRecordRepository.save(any(SellMatchRecord::class.java)))
            .thenAnswer { it.arguments[0] as SellMatchRecord }

        service.updatePendingSellOrderPrices()
        verify(retrofitFactory, never()).createClobApi(
            account.apiKey!!,
            "decrypted",
            "decrypted",
            account.walletAddress
        )
    }

    @Test
    fun `updatePendingBuyOrders uses fast trading client for polling`() = runTest {
        val order = buyOrder(id = 1L)
        val account = account()
        val copyTrading = copyTrading(id = 11L)

        `when`(copyOrderTrackingRepository.findByNotificationSentFalse()).thenReturn(listOf(order))
        `when`(copyTradingRepository.findById(11L)).thenReturn(Optional.of(copyTrading))
        `when`(accountRepository.findById(7L)).thenReturn(Optional.of(account))
        `when`(leaderRepository.findById(99L)).thenReturn(Optional.empty())
        `when`(cryptoUtils.decrypt(account.apiSecret!!)).thenReturn("decrypted")
        `when`(cryptoUtils.decrypt(account.apiPassphrase!!)).thenReturn("decrypted")
        `when`(
            retrofitFactory.createFastTradingClobApi(
                account.apiKey!!,
                "decrypted",
                "decrypted",
                account.walletAddress
            )
        ).thenReturn(clobApi)
        `when`(clobApi.getOrder(order.buyOrderId)).thenReturn(Response.success(openOrder(order.buyOrderId)))
        `when`(copyOrderTrackingRepository.save(any(CopyOrderTracking::class.java)))
            .thenAnswer { it.arguments[0] as CopyOrderTracking }
        `when`(blockchainService.getUsdcBalance(account.walletAddress, account.proxyAddress))
            .thenReturn(Result.success("0"))

        service.updatePendingBuyOrders()

        verify(retrofitFactory).createFastTradingClobApi(
            account.apiKey!!,
            "decrypted",
            "decrypted",
            account.walletAddress
        )
        verify(retrofitFactory, never()).createClobApi(
            account.apiKey!!,
            "decrypted",
            "decrypted",
            account.walletAddress
        )
    }

    @Test
    fun `updatePendingBuyOrders keeps pending fill orders waiting when nothing has matched`() = runTest {
        val order = buyOrder(id = 1L, status = "pending_fill")
        val account = account()
        val copyTrading = copyTrading(id = 11L)

        `when`(copyOrderTrackingRepository.findByNotificationSentFalse()).thenReturn(listOf(order))
        `when`(copyTradingRepository.findById(11L)).thenReturn(Optional.of(copyTrading))
        `when`(accountRepository.findById(7L)).thenReturn(Optional.of(account))
        `when`(cryptoUtils.decrypt(account.apiSecret!!)).thenReturn("decrypted")
        `when`(cryptoUtils.decrypt(account.apiPassphrase!!)).thenReturn("decrypted")
        `when`(
            retrofitFactory.createFastTradingClobApi(
                account.apiKey!!,
                "decrypted",
                "decrypted",
                account.walletAddress
            )
        ).thenReturn(clobApi)
        `when`(
            clobApi.getOrder(order.buyOrderId)
        ).thenReturn(Response.success(openOrder(order.buyOrderId, status = "LIVE", originalSize = "1", sizeMatched = "0")))

        service.updatePendingBuyOrders()

        verify(copyOrderTrackingRepository, never()).save(any(CopyOrderTracking::class.java))
    }

    @Test
    fun `updatePendingBuyOrders should keep notification pending when telegram delivery fails`() = runTest {
        val failingTelegramNotificationService = mock(
            TelegramNotificationService::class.java,
            Answer { invocation ->
                if (invocation.method.name == "sendOrderSuccessNotification") {
                    throw IllegalStateException("telegram unavailable")
                }
                RETURNS_DEFAULTS.answer(invocation)
            }
        )
        val localService = buildService(failingTelegramNotificationService)
        val savedOrders = mutableListOf<CopyOrderTracking>()
        val order = buyOrder(id = 1L)
        val account = account()
        val copyTrading = copyTrading(id = 11L)

        `when`(copyOrderTrackingRepository.findByNotificationSentFalse()).thenReturn(listOf(order))
        `when`(copyTradingRepository.findById(11L)).thenReturn(Optional.of(copyTrading))
        `when`(accountRepository.findById(7L)).thenReturn(Optional.of(account))
        `when`(leaderRepository.findById(99L)).thenReturn(Optional.empty())
        `when`(cryptoUtils.decrypt(account.apiSecret!!)).thenReturn("decrypted")
        `when`(cryptoUtils.decrypt(account.apiPassphrase!!)).thenReturn("decrypted")
        `when`(
            retrofitFactory.createFastTradingClobApi(
                account.apiKey!!,
                "decrypted",
                "decrypted",
                account.walletAddress
            )
        ).thenReturn(clobApi)
        `when`(clobApi.getOrder(order.buyOrderId)).thenReturn(Response.success(openOrder(order.buyOrderId)))
        `when`(blockchainService.getUsdcBalance(account.walletAddress, account.proxyAddress))
            .thenReturn(Result.success("0"))
        `when`(copyOrderTrackingRepository.save(any(CopyOrderTracking::class.java)))
            .thenAnswer {
                (it.arguments[0] as CopyOrderTracking).also(savedOrders::add)
            }

        localService.updatePendingBuyOrders()

        verify(copyOrderTrackingRepository).save(any(CopyOrderTracking::class.java))
        assertTrue(savedOrders.isNotEmpty())
        assertFalse(savedOrders.last().notificationSent)
    }

    @Test
    fun `checkAndDeleteUnfilledOrders confirms matched pending fill orders instead of deleting them`() = runTest {
        val order = buyOrder(id = 1L, status = "pending_fill")
        val account = account()

        `when`(copyOrderTrackingRepository.findByCreatedAtBeforeAndStatus(anyLong(), anyString()))
            .thenReturn(listOf(order))
        `when`(accountRepository.findById(7L)).thenReturn(Optional.of(account))
        `when`(cryptoUtils.decrypt(account.apiSecret!!)).thenReturn("decrypted")
        `when`(cryptoUtils.decrypt(account.apiPassphrase!!)).thenReturn("decrypted")
        `when`(
            retrofitFactory.createFastTradingClobApi(
                account.apiKey!!,
                "decrypted",
                "decrypted",
                account.walletAddress
            )
        ).thenReturn(clobApi)
        `when`(copyOrderTrackingRepository.save(any(CopyOrderTracking::class.java)))
            .thenAnswer { it.arguments[0] as CopyOrderTracking }
        `when`(
            clobApi.getOrder(order.buyOrderId)
        ).thenReturn(Response.success(openOrder(order.buyOrderId, status = "CANCELLED", originalSize = "1", sizeMatched = "0.40")))

        service.checkAndDeleteUnfilledOrders()

        verify(copyOrderTrackingRepository).save(any(CopyOrderTracking::class.java))
        verify(copyOrderTrackingRepository, never()).deleteById(order.id!!)
    }

    @Test
    fun `cleanupDeletedAccountOrders rethrows failures so the transaction can rollback`() {
        val record = sellRecord(id = 1L, copyTradingId = 11L)

        `when`(sellMatchRecordRepository.findAll()).thenReturn(listOf(record))
        `when`(accountRepository.findAll()).thenReturn(emptyList())
        `when`(copyTradingRepository.findAll()).thenReturn(emptyList())
        `when`(copyTradingRepository.findById(11L)).thenReturn(Optional.empty())
        `when`(sellMatchDetailRepository.findByMatchRecordId(1L)).thenReturn(emptyList())
        `when`(sellMatchDetailRepository.deleteAll(emptyList())).thenThrow(IllegalStateException("delete failed"))

        assertThrows<IllegalStateException> {
            service.cleanupDeletedAccountOrders()
        }
    }

    private fun sellRecord(
        id: Long,
        copyTradingId: Long,
        sellOrderId: String = "AUTO_1"
    ) = SellMatchRecord(
        id = id,
        copyTradingId = copyTradingId,
        sellOrderId = sellOrderId,
        leaderSellTradeId = "leader-sell-$id",
        marketId = "condition-1",
        side = "0",
        outcomeIndex = 0,
        totalMatchedQuantity = BigDecimal("5"),
        sellPrice = BigDecimal("0.70"),
        totalRealizedPnl = BigDecimal("1.50"),
        priceUpdated = false
    )

    private fun buyOrder(id: Long) = CopyOrderTracking(
        id = id,
        copyTradingId = 11L,
        accountId = 7L,
        leaderId = 99L,
        marketId = "condition-1",
        side = "0",
        outcomeIndex = 0,
        buyOrderId = "0xabcde${id}",
        leaderBuyTradeId = "leader-buy-$id",
        quantity = BigDecimal.ONE,
        price = BigDecimal("0.55"),
        matchedQuantity = BigDecimal.ZERO,
        remainingQuantity = BigDecimal.ONE,
        status = "filled",
        notificationSent = false,
        source = "activity-ws",
        createdAt = System.currentTimeMillis() - 60_000,
        updatedAt = System.currentTimeMillis() - 60_000
    )

    private fun buyOrder(id: Long, status: String) = CopyOrderTracking(
        id = id,
        copyTradingId = 11L,
        accountId = 7L,
        leaderId = 99L,
        marketId = "condition-1",
        side = "0",
        outcomeIndex = 0,
        buyOrderId = "0xabcde${id}",
        leaderBuyTradeId = "leader-buy-$id",
        quantity = BigDecimal.ONE,
        price = BigDecimal("0.55"),
        matchedQuantity = BigDecimal.ZERO,
        remainingQuantity = BigDecimal.ONE,
        status = status,
        notificationSent = false,
        source = "activity-ws",
        createdAt = System.currentTimeMillis() - 60_000,
        updatedAt = System.currentTimeMillis() - 60_000
    )

    private fun copyTrading(id: Long) = CopyTrading(
        id = id,
        accountId = 7L,
        leaderId = 99L
    )

    private fun buildService(
        telegramNotificationService: TelegramNotificationService = this.telegramNotificationService
    ) = OrderStatusUpdateService(
        sellMatchRecordRepository = sellMatchRecordRepository,
        sellMatchDetailRepository = sellMatchDetailRepository,
        copyTradingRepository = copyTradingRepository,
        accountRepository = accountRepository,
        copyOrderTrackingRepository = copyOrderTrackingRepository,
        leaderRepository = leaderRepository,
        retrofitFactory = retrofitFactory,
        cryptoUtils = cryptoUtils,
        trackingService = trackingService,
        marketService = marketService,
        telegramNotificationService = telegramNotificationService,
        blockchainService = blockchainService
    )

    private fun openOrder(
        id: String,
        status: String = "FILLED",
        originalSize: String = "1",
        sizeMatched: String = "1"
    ) = OpenOrder(
        id = id,
        status = status,
        owner = "api-key",
        makerAddress = "0x1234567890123456789012345678901234567890",
        market = "condition-1",
        assetId = "asset-1",
        side = "BUY",
        originalSize = originalSize,
        sizeMatched = sizeMatched,
        price = "0.55",
        outcome = "YES",
        expiration = "0",
        orderType = "GTC",
        createdAt = System.currentTimeMillis()
    )

    private fun account() = Account(
        id = 7L,
        privateKey = "encrypted-private-key",
        walletAddress = "0x1234567890123456789012345678901234567890",
        proxyAddress = "0x1234567890123456789012345678901234567891",
        apiKey = "api-key",
        apiSecret = "encrypted-secret",
        apiPassphrase = "encrypted-passphrase",
        accountName = "DemoAccount"
    )
}
