package com.wrbug.polymarketbot.service.copytrading.configs

import com.wrbug.polymarketbot.dto.LeaderGroupControlUpdateRequest
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.entity.LeaderCopyTradingControl
import com.wrbug.polymarketbot.entity.SellMatchDetail
import com.wrbug.polymarketbot.repository.CopyOrderTrackingRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.LeaderCopyTradingControlRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.repository.SellMatchDetailRepository
import com.wrbug.polymarketbot.service.accounts.AccountService
import com.wrbug.polymarketbot.service.copytrading.statistics.LeaderDrawdownEvaluator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.util.Optional

class LeaderGroupControlServiceTest {

    private val copyTradingRepository = mock(CopyTradingRepository::class.java)
    private val copyOrderTrackingRepository = mock(CopyOrderTrackingRepository::class.java)
    private val leaderRepository = mock(LeaderRepository::class.java)
    private val leaderCopyTradingControlRepository = mock(LeaderCopyTradingControlRepository::class.java)
    private val sellMatchDetailRepository = mock(SellMatchDetailRepository::class.java)
    private val leaderDrawdownEvaluator = LeaderDrawdownEvaluator()
    private val accountService = mock(AccountService::class.java)
    private val applicationEventPublisher = mock(ApplicationEventPublisher::class.java)

    private val service = LeaderGroupControlService(
        copyTradingRepository = copyTradingRepository,
        copyOrderTrackingRepository = copyOrderTrackingRepository,
        leaderRepository = leaderRepository,
        leaderCopyTradingControlRepository = leaderCopyTradingControlRepository,
        sellMatchDetailRepository = sellMatchDetailRepository,
        leaderDrawdownEvaluator = leaderDrawdownEvaluator,
        accountService = accountService,
        applicationEventPublisher = applicationEventPublisher
    )

    @Test
    fun `updateControl persists leader-specific drawdown threshold`() {
        val leader = leader()
        var storedControl: LeaderCopyTradingControl? = null

        `when`(leaderRepository.findById(leader.id!!)).thenReturn(Optional.of(leader))
        `when`(leaderCopyTradingControlRepository.findByLeaderIdForUpdate(leader.id!!)).thenAnswer { storedControl }
        `when`(leaderCopyTradingControlRepository.save(any(LeaderCopyTradingControl::class.java)))
            .thenAnswer {
                val saved = it.arguments[0] as LeaderCopyTradingControl
                storedControl = saved
                saved
            }

        val result = service.updateControl(
            LeaderGroupControlUpdateRequest(
                leaderId = leader.id!!,
                autoPauseEnabled = true,
                profitTakeEnabled = true,
                profitTakePrice = "0.99",
                drawdownThresholdPercent = "12.5"
            )
        )

        assertTrue(result.isSuccess)
        assertEquals("12.50", result.getOrNull()?.drawdownThresholdPercent)

        val captor = ArgumentCaptor.forClass(LeaderCopyTradingControl::class.java)
        verify(leaderCopyTradingControlRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture())
        assertEquals(BigDecimal("12.50"), captor.allValues.last().drawdownThresholdPercent)
    }

    @Test
    fun `evaluateAutoPause uses saved drawdown threshold instead of fixed 25 percent`() {
        val leader = leader()
        val copyTrading = CopyTrading(id = 7L, accountId = 3L, leaderId = leader.id!!)
        val control = LeaderCopyTradingControl(
            id = 11L,
            leaderId = leader.id!!,
            drawdownThresholdPercent = BigDecimal("12.50")
        )
        val details = listOf(
            SellMatchDetail(
                id = 1L,
                matchRecordId = 1L,
                trackingId = 1L,
                buyOrderId = "buy-1",
                matchedQuantity = BigDecimal.ONE,
                buyPrice = BigDecimal("0.10"),
                sellPrice = BigDecimal("1.10"),
                realizedPnl = BigDecimal("100"),
                createdAt = 1_000L
            ),
            SellMatchDetail(
                id = 2L,
                matchRecordId = 2L,
                trackingId = 2L,
                buyOrderId = "buy-2",
                matchedQuantity = BigDecimal.ONE,
                buyPrice = BigDecimal("0.10"),
                sellPrice = BigDecimal("0.90"),
                realizedPnl = BigDecimal("-20"),
                createdAt = 2_000L
            )
        )

        `when`(leaderRepository.findById(leader.id!!)).thenReturn(Optional.of(leader))
        `when`(copyTradingRepository.findByLeaderId(leader.id!!)).thenReturn(listOf(copyTrading))
        `when`(leaderCopyTradingControlRepository.findByLeaderIdForUpdate(leader.id!!)).thenReturn(control)
        `when`(sellMatchDetailRepository.findByCopyTradingIdInAndCreatedAtGreaterThanEqual(anyList(), anyLong()))
            .thenReturn(details)
        `when`(copyOrderTrackingRepository.findActiveOrdersByCopyTradingIdIn(anyList())).thenReturn(emptyList())
        `when`(copyTradingRepository.saveAll(anyList())).thenAnswer { it.arguments[0] as List<CopyTrading> }
        `when`(leaderCopyTradingControlRepository.save(any(LeaderCopyTradingControl::class.java)))
            .thenAnswer { it.arguments[0] as LeaderCopyTradingControl }

        val result = service.evaluateAutoPause(leader.id!!)

        assertTrue(result.isSuccess)
        assertEquals("12.50", result.getOrNull()?.drawdownThresholdPercent)
        assertEquals("20.00", result.getOrNull()?.currentDrawdownPercent)
        assertEquals("AUTO_PAUSED", result.getOrNull()?.status)
    }

    @Test
    fun `updateControl acquires the leader control with a write lock before saving`() {
        val leader = leader()
        val control = LeaderCopyTradingControl(
            id = 11L,
            leaderId = leader.id!!,
            lastPeakPnl = BigDecimal("8.8"),
            currentPnl = BigDecimal("4.4"),
            currentDrawdownPercent = BigDecimal("3.3")
        )

        `when`(leaderRepository.findById(leader.id!!)).thenReturn(Optional.of(leader))
        `when`(leaderCopyTradingControlRepository.findByLeaderIdForUpdate(leader.id!!)).thenReturn(control)
        `when`(leaderCopyTradingControlRepository.save(any(LeaderCopyTradingControl::class.java)))
            .thenAnswer { it.arguments[0] as LeaderCopyTradingControl }

        val result = service.updateControl(
            LeaderGroupControlUpdateRequest(
                leaderId = leader.id!!,
                autoPauseEnabled = true,
                profitTakeEnabled = true,
                profitTakePrice = "0.95",
                drawdownThresholdPercent = "18"
            )
        )

        assertTrue(result.isSuccess)
        verify(leaderCopyTradingControlRepository).findByLeaderIdForUpdate(leader.id!!)
        verify(leaderCopyTradingControlRepository, never()).findByLeaderId(leader.id!!)
    }

    @Test
    fun `evaluateAutoPause acquires the leader control with a write lock before mutating state`() {
        val leader = leader()
        val copyTrading = CopyTrading(id = 7L, accountId = 3L, leaderId = leader.id!!)
        val control = LeaderCopyTradingControl(
            id = 11L,
            leaderId = leader.id!!,
            drawdownThresholdPercent = BigDecimal("12.50")
        )
        val details = listOf(
            SellMatchDetail(
                id = 1L,
                matchRecordId = 1L,
                trackingId = 1L,
                buyOrderId = "buy-1",
                matchedQuantity = BigDecimal.ONE,
                buyPrice = BigDecimal("0.10"),
                sellPrice = BigDecimal("1.10"),
                realizedPnl = BigDecimal("100"),
                createdAt = 1_000L
            ),
            SellMatchDetail(
                id = 2L,
                matchRecordId = 2L,
                trackingId = 2L,
                buyOrderId = "buy-2",
                matchedQuantity = BigDecimal.ONE,
                buyPrice = BigDecimal("0.10"),
                sellPrice = BigDecimal("0.90"),
                realizedPnl = BigDecimal("-20"),
                createdAt = 2_000L
            )
        )

        `when`(leaderRepository.findById(leader.id!!)).thenReturn(Optional.of(leader))
        `when`(copyTradingRepository.findByLeaderId(leader.id!!)).thenReturn(listOf(copyTrading))
        `when`(leaderCopyTradingControlRepository.findByLeaderIdForUpdate(leader.id!!)).thenReturn(control)
        `when`(sellMatchDetailRepository.findByCopyTradingIdInAndCreatedAtGreaterThanEqual(anyList(), anyLong()))
            .thenReturn(details)
        `when`(copyOrderTrackingRepository.findActiveOrdersByCopyTradingIdIn(anyList())).thenReturn(emptyList())
        `when`(copyTradingRepository.saveAll(anyList())).thenAnswer { it.arguments[0] as List<CopyTrading> }
        `when`(leaderCopyTradingControlRepository.save(any(LeaderCopyTradingControl::class.java)))
            .thenAnswer { it.arguments[0] as LeaderCopyTradingControl }

        val result = service.evaluateAutoPause(leader.id!!)

        assertTrue(result.isSuccess)
        verify(leaderCopyTradingControlRepository).findByLeaderIdForUpdate(leader.id!!)
        verify(leaderCopyTradingControlRepository, never()).findByLeaderId(leader.id!!)
    }

    private fun leader(id: Long = 5L) = Leader(
        id = id,
        leaderAddress = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
        leaderName = "DemoLeader"
    )
}
