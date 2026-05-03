package com.wrbug.polymarketbot.service.copytrading.leaders

import com.wrbug.polymarketbot.dto.LeaderAddRequest
import com.wrbug.polymarketbot.dto.LeaderUpdateRequest
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.BacktestTaskRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.LeaderCopyTradingControlRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.service.common.BlockchainService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.util.Optional

class LeaderServiceCustomGroupTest {

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
    fun `addLeader persists custom group separately from category`() {
        `when`(leaderRepository.existsByLeaderAddress("0x1111111111111111111111111111111111111111")).thenReturn(false)
        `when`(accountRepository.existsByWalletAddress("0x1111111111111111111111111111111111111111")).thenReturn(false)
        `when`(leaderRepository.save(any(Leader::class.java))).thenAnswer { invocation ->
            (invocation.arguments[0] as Leader).copy(id = 1L)
        }

        val result = service.addLeader(
            LeaderAddRequest(
                leaderAddress = "0x1111111111111111111111111111111111111111",
                leaderName = "Demo",
                category = "sports",
                customGroup = "重点观察"
            )
        )

        val dto = result.getOrThrow()
        assertEquals("sports", dto.category)
        assertEquals("重点观察", dto.customGroup)
    }

    @Test
    fun `updateLeader keeps custom group editable`() {
        val leaderId = 9L
        val leader = Leader(
            id = leaderId,
            leaderAddress = "0x9999999999999999999999999999999999999999",
            leaderName = "Demo",
            category = "sports",
            customGroup = null
        )

        `when`(leaderRepository.findById(leaderId)).thenReturn(Optional.of(leader))
        `when`(leaderRepository.save(any(Leader::class.java))).thenAnswer { invocation ->
            invocation.arguments[0] as Leader
        }

        val result = service.updateLeader(
            LeaderUpdateRequest(
                leaderId = leaderId,
                customGroup = "未分组测试"
            )
        )

        assertEquals("未分组测试", result.getOrThrow().customGroup)
    }
}
