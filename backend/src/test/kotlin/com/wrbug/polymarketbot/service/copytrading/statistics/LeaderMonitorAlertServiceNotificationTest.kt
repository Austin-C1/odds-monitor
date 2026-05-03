package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.api.PositionResponse
import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.service.common.BlockchainService
import com.wrbug.polymarketbot.service.system.TelegramNotificationService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

class LeaderMonitorAlertServiceNotificationTest {

    private val copyTradingRepository = mock(CopyTradingRepository::class.java)
    private val leaderRepository = mock(LeaderRepository::class.java)
    private val blockchainService = mock(BlockchainService::class.java)
    private val telegramNotificationService = mock(TelegramNotificationService::class.java)

    private val service = LeaderMonitorAlertService(
        copyTradingRepository = copyTradingRepository,
        leaderRepository = leaderRepository,
        blockchainService = blockchainService,
        telegramNotificationService = telegramNotificationService
    )

    @Test
    fun `processTrade sends monitor push notification with current market position summary`() = runTest {
        val leader = leader()
        `when`(copyTradingRepository.findByEnabledTrue()).thenReturn(listOf(copyTrading()))
        `when`(leaderRepository.findAllById(listOf(leader.id!!))).thenReturn(listOf(leader))
        `when`(blockchainService.getPositions(leader.leaderAddress)).thenReturn(
            Result.success(
                listOf(
                    positionResponse(outcomeIndex = 0, outcome = "YES", currentValue = 320.0, avgPrice = 0.61),
                    positionResponse(outcomeIndex = 1, outcome = "NO", currentValue = 95.0, avgPrice = 0.41)
                )
            )
        )

        service.processTrade(
            leaderId = leader.id!!,
            trade = TradeResponse(
                id = "trade-1",
                market = "condition-1",
                side = "BUY",
                price = "0.61",
                size = "50",
                timestamp = "2026-04-24T11:00:00Z",
                user = leader.leaderAddress,
                outcomeIndex = 0,
                outcome = "YES"
            )
        )

        verify(telegramNotificationService).sendMonitorPushNotification(
            marketTitle = "Will Austin ship monitor mode?",
            marketLink = "https://polymarket.com/event/will-austin-ship-monitor-mode",
            leaderName = "Austin",
            side = "BUY",
            outcome = "YES",
            price = "0.61",
            size = "50",
            currentPositionSummary = "YES 320u / NO 95u"
        )
    }

    @Test
    fun `processTrade keeps monitor push notification when trade closes the monitored market position`() = runTest {
        val leader = leader()
        `when`(copyTradingRepository.findByEnabledTrue()).thenReturn(listOf(copyTrading()))
        `when`(leaderRepository.findAllById(listOf(leader.id!!))).thenReturn(listOf(leader))
        `when`(blockchainService.getPositions(leader.leaderAddress)).thenReturn(
            Result.success(
                listOf(
                    positionResponse(outcomeIndex = 0, outcome = "YES", currentValue = 80.0, avgPrice = 0.59)
                )
            ),
            Result.success(emptyList())
        )

        service.processTrade(
            leaderId = leader.id!!,
            trade = TradeResponse(
                id = "trade-2",
                market = "condition-1",
                side = "SELL",
                price = "0.40",
                size = "80",
                timestamp = "2026-04-24T12:00:00Z",
                user = leader.leaderAddress,
                outcomeIndex = 0,
                outcome = "YES"
            )
        )

        verify(telegramNotificationService).sendMonitorPushNotification(
            marketTitle = "Will Austin ship monitor mode?",
            marketLink = "https://polymarket.com/event/will-austin-ship-monitor-mode",
            leaderName = "Austin",
            side = "SELL",
            outcome = "YES",
            price = "0.40",
            size = "80",
            currentPositionSummary = "无持仓"
        )
    }

    private fun copyTrading() = CopyTrading(
        id = 1L,
        accountId = 9L,
        leaderId = 7L
    )

    private fun leader() = Leader(
        id = 7L,
        leaderAddress = "0xleader",
        leaderName = "Austin"
    )

    private fun positionResponse(
        outcomeIndex: Int,
        outcome: String,
        currentValue: Double,
        avgPrice: Double
    ) = PositionResponse(
        proxyWallet = "0xleader",
        conditionId = "condition-1",
        avgPrice = avgPrice,
        currentValue = currentValue,
        title = "Will Austin ship monitor mode?",
        eventSlug = "will-austin-ship-monitor-mode",
        outcome = outcome,
        outcomeIndex = outcomeIndex
    )
}
