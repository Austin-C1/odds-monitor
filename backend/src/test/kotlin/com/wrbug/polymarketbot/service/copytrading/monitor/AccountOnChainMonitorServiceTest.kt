package com.wrbug.polymarketbot.service.copytrading.monitor

import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.entity.CopyOrderTracking
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.entity.SellMatchDetail
import com.wrbug.polymarketbot.entity.SellMatchRecord
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyOrderTrackingRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.SellMatchDetailRepository
import com.wrbug.polymarketbot.repository.SellMatchRecordRepository
import com.wrbug.polymarketbot.util.RetrofitFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import java.math.BigDecimal
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible

class AccountOnChainMonitorServiceTest {

    private val unifiedOnChainWsService = mock(UnifiedOnChainWsService::class.java)
    private val retrofitFactory = mock(RetrofitFactory::class.java)
    private val accountRepository = mock(AccountRepository::class.java)
    private val copyTradingRepository = mock(CopyTradingRepository::class.java)
    private val copyOrderTrackingRepository = mock(CopyOrderTrackingRepository::class.java)
    private val sellMatchRecordRepository = mock(SellMatchRecordRepository::class.java)
    private val sellMatchDetailRepository = mock(SellMatchDetailRepository::class.java)

    private val service = AccountOnChainMonitorService(
        unifiedOnChainWsService = unifiedOnChainWsService,
        retrofitFactory = retrofitFactory,
        accountRepository = accountRepository,
        copyTradingRepository = copyTradingRepository,
        copyOrderTrackingRepository = copyOrderTrackingRepository,
        sellMatchRecordRepository = sellMatchRecordRepository,
        sellMatchDetailRepository = sellMatchDetailRepository
    )

    @Test
    fun `skips fallback sell matching when outcome index is missing`() = runTest {
        val account = account()

        `when`(copyTradingRepository.findByAccountId(account.id!!)).thenReturn(listOf(copyTrading()))

        invokeHandleAccountSellOrRedeem(
            account,
            trade(outcomeIndex = null)
        )

        verify(copyOrderTrackingRepository, never()).findByCopyTradingId(11L)
        verify(copyOrderTrackingRepository, never()).save(any(CopyOrderTracking::class.java))
        verify(sellMatchRecordRepository, never()).save(any(SellMatchRecord::class.java))
    }

    @Test
    fun `ignores buy orders that are still inside the on-chain protection window`() = runTest {
        val account = account()
        val recentOrder = order(createdAt = System.currentTimeMillis() - 30_000)

        `when`(copyTradingRepository.findByAccountId(account.id!!)).thenReturn(listOf(copyTrading()))
        `when`(copyOrderTrackingRepository.findByCopyTradingId(11L)).thenReturn(listOf(recentOrder))

        invokeHandleAccountSellOrRedeem(
            account,
            trade(outcomeIndex = 0)
        )

        verify(copyOrderTrackingRepository).findByCopyTradingId(11L)
        verify(copyOrderTrackingRepository, never()).save(any(CopyOrderTracking::class.java))
        verify(sellMatchRecordRepository, never()).save(any(SellMatchRecord::class.java))
        verify(sellMatchDetailRepository, never()).save(any(SellMatchDetail::class.java))
    }

    @Test
    fun `matches eligible older orders outside the protection window`() = runTest {
        val account = account()
        val oldOrder = order(createdAt = System.currentTimeMillis() - 180_000)

        `when`(copyTradingRepository.findByAccountId(account.id!!)).thenReturn(listOf(copyTrading()))
        `when`(copyOrderTrackingRepository.findByCopyTradingId(11L)).thenReturn(listOf(oldOrder))
        `when`(copyOrderTrackingRepository.save(any(CopyOrderTracking::class.java)))
            .thenAnswer { it.arguments[0] as CopyOrderTracking }
        `when`(sellMatchRecordRepository.save(any(SellMatchRecord::class.java)))
            .thenAnswer { (it.arguments[0] as SellMatchRecord).copy(id = 91L) }
        `when`(sellMatchDetailRepository.save(any(SellMatchDetail::class.java)))
            .thenAnswer { it.arguments[0] as SellMatchDetail }

        invokeHandleAccountSellOrRedeem(
            account,
            trade(outcomeIndex = 0)
        )

        verify(copyOrderTrackingRepository, times(1)).save(any(CopyOrderTracking::class.java))
        verify(sellMatchRecordRepository, times(1)).save(any(SellMatchRecord::class.java))
        verify(sellMatchDetailRepository, times(1)).save(any(SellMatchDetail::class.java))
    }

    @Test
    fun `skips on-chain fallback when a recent pending sell record already matches the trade`() = runTest {
        val account = account()
        val oldOrder = order(createdAt = System.currentTimeMillis() - 180_000)
        val recentSellRecord = SellMatchRecord(
            id = 91L,
            copyTradingId = 11L,
            sellOrderId = "0xsell",
            leaderSellTradeId = "leader-sell-1",
            marketId = "condition-1",
            side = "0",
            outcomeIndex = 0,
            totalMatchedQuantity = BigDecimal.ONE,
            sellPrice = BigDecimal("0.70"),
            totalRealizedPnl = BigDecimal("0.15"),
            priceUpdated = false,
            createdAt = System.currentTimeMillis() - 5_000
        )

        `when`(copyTradingRepository.findByAccountId(account.id!!)).thenReturn(listOf(copyTrading()))
        `when`(copyOrderTrackingRepository.findByCopyTradingId(11L)).thenReturn(listOf(oldOrder))
        `when`(
            sellMatchRecordRepository.findRecentPendingByCopyTradingIdAndMarketIdAndOutcomeIndex(
                anyLong(),
                anyString(),
                anyInt(),
                anyLong()
            )
        ).thenAnswer {
            val copyTradingId = it.arguments[0] as Long
            val marketId = it.arguments[1] as String
            val outcomeIndex = it.arguments[2] as Int
            if (copyTradingId == 11L && marketId == "condition-1" && outcomeIndex == 0) {
                listOf(recentSellRecord)
            } else {
                emptyList()
            }
        }

        invokeHandleAccountSellOrRedeem(
            account,
            trade(outcomeIndex = 0)
        )

        verify(copyOrderTrackingRepository, never()).save(any(CopyOrderTracking::class.java))
        verify(sellMatchRecordRepository, never()).save(any(SellMatchRecord::class.java))
        verify(sellMatchDetailRepository, never()).save(any(SellMatchDetail::class.java))
    }

    private suspend fun invokeHandleAccountSellOrRedeem(account: Account, trade: TradeResponse) {
        val method = service::class.declaredFunctions.first { it.name == "handleAccountSellOrRedeem" }
        method.isAccessible = true
        method.callSuspend(service, account, trade)
    }

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

    private fun copyTrading() = CopyTrading(
        id = 11L,
        accountId = 7L,
        leaderId = 99L,
        enabled = true
    )

    private fun order(createdAt: Long) = CopyOrderTracking(
        id = 21L,
        copyTradingId = 11L,
        accountId = 7L,
        leaderId = 99L,
        marketId = "condition-1",
        side = "0",
        outcomeIndex = 0,
        buyOrderId = "0xorder-21",
        leaderBuyTradeId = "leader-buy-21",
        quantity = BigDecimal.ONE,
        price = BigDecimal("0.55"),
        matchedQuantity = BigDecimal.ZERO,
        remainingQuantity = BigDecimal.ONE,
        status = "filled",
        notificationSent = false,
        source = "activity-ws",
        createdAt = createdAt,
        updatedAt = createdAt
    )

    private fun trade(outcomeIndex: Int?) = TradeResponse(
        id = "0xtx",
        market = "condition-1",
        side = "SELL",
        price = "0.70",
        size = "1",
        timestamp = "2026-04-18T00:00:00Z",
        user = "0xleader",
        outcomeIndex = outcomeIndex
    )
}
