package com.wrbug.polymarketbot.service.copytrading.leaders

import com.wrbug.polymarketbot.dto.LeaderListRequest
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.BacktestTaskRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.LeaderCopyTradingControlRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.service.common.BlockchainService
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.util.Optional

class LeaderServiceMonitoringStatusTest {

    private val leaderRepository = mock(LeaderRepository::class.java)
    private val accountRepository = mock(AccountRepository::class.java)
    private val copyTradingRepository = mock(CopyTradingRepository::class.java)
    private val leaderCopyTradingControlRepository = mock(LeaderCopyTradingControlRepository::class.java)
    private val backtestTaskRepository = mock(BacktestTaskRepository::class.java)
    private val blockchainService = mock(BlockchainService::class.java)

    private val service = LeaderService(
        leaderRepository = leaderRepository,
        accountRepository = accountRepository,
        copyTradingRepository = copyTradingRepository,
        leaderCopyTradingControlRepository = leaderCopyTradingControlRepository,
        backtestTaskRepository = backtestTaskRepository,
        blockchainService = blockchainService
    )

    @Test
    fun `getLeaderList marks leader as monitored only when enabled copy trading exists`() {
        val monitoredLeader = Leader(id = 1L, leaderAddress = "0x1111111111111111111111111111111111111111", leaderName = "Monitored")
        val idleLeader = Leader(id = 2L, leaderAddress = "0x2222222222222222222222222222222222222222", leaderName = "Idle")

        `when`(leaderRepository.findAllByOrderByCreatedAtAsc()).thenReturn(listOf(monitoredLeader, idleLeader))
        `when`(copyTradingRepository.countByLeaderId(1L)).thenReturn(1L)
        `when`(copyTradingRepository.countByLeaderId(2L)).thenReturn(0L)
        `when`(copyTradingRepository.existsByLeaderIdAndEnabledTrue(1L)).thenReturn(true)
        `when`(copyTradingRepository.existsByLeaderIdAndEnabledTrue(2L)).thenReturn(false)
        `when`(backtestTaskRepository.findByLeaderId(1L)).thenReturn(emptyList())
        `when`(backtestTaskRepository.findByLeaderId(2L)).thenReturn(emptyList())

        val result = service.getLeaderList(LeaderListRequest())
        val list = result.getOrThrow().list

        assertTrue(list.first { it.id == 1L }.monitoringEnabled)
        assertFalse(list.first { it.id == 2L }.monitoringEnabled)
    }

    @Test
    fun `getLeaderDetail carries monitoring state`() {
        val leaderId = 9L
        val leader = Leader(id = leaderId, leaderAddress = "0x9999999999999999999999999999999999999999", leaderName = "Demo")

        `when`(leaderRepository.findById(leaderId)).thenReturn(Optional.of(leader))
        `when`(copyTradingRepository.countByLeaderId(leaderId)).thenReturn(3L)
        `when`(copyTradingRepository.existsByLeaderIdAndEnabledTrue(leaderId)).thenReturn(false)
        `when`(backtestTaskRepository.findByLeaderId(leaderId)).thenReturn(emptyList())

        val detail = service.getLeaderDetail(leaderId).getOrThrow()

        assertFalse(detail.monitoringEnabled)
    }
}
