package com.wrbug.polymarketbot.service.copytrading.leaders

import com.wrbug.polymarketbot.entity.BacktestTask
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.BacktestTaskRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.LeaderCopyTradingControlRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.service.common.BlockchainService
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import java.math.BigDecimal
import java.util.Optional

class LeaderServiceDeleteTest {

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
    fun `deleteLeader removes dependent records before deleting leader`() {
        val leaderId = 9L
        val leader = Leader(id = leaderId, leaderAddress = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd", leaderName = "Demo")
        val copyTradings = listOf(CopyTrading(id = 101L, accountId = 1L, leaderId = leaderId))
        val backtests = listOf(
            BacktestTask(
                id = 201L,
                taskName = "leader-backtest",
                leaderId = leaderId,
                initialBalance = BigDecimal("100"),
                backtestDays = 7,
                startTime = 1_000L
            )
        )

        `when`(leaderRepository.findById(leaderId)).thenReturn(Optional.of(leader))
        `when`(copyTradingRepository.findByLeaderId(leaderId)).thenReturn(copyTradings)
        `when`(backtestTaskRepository.findByLeaderId(leaderId)).thenReturn(backtests)

        val result = service.deleteLeader(leaderId)

        assertTrue(result.isSuccess)
        verify(copyTradingRepository).findByLeaderId(leaderId)
        verify(copyTradingRepository).deleteAll(copyTradings)
        verify(copyTradingRepository).flush()
        verify(leaderCopyTradingControlRepository).deleteByLeaderId(leaderId)
        verify(leaderCopyTradingControlRepository).flush()
        verify(backtestTaskRepository).findByLeaderId(leaderId)
        verify(backtestTaskRepository).deleteAll(backtests)
        verify(backtestTaskRepository).flush()
        verify(leaderRepository).delete(leader)
        verify(leaderRepository).flush()
        verifyNoMoreInteractions(accountRepository, blockchainService)
    }
}
