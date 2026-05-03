package com.wrbug.polymarketbot.service.copytrading.monitor

import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

class CopyTradingMonitorServiceTest {

    private val copyTradingRepository = mock(CopyTradingRepository::class.java)
    private val leaderRepository = mock(LeaderRepository::class.java)
    private val accountRepository = mock(AccountRepository::class.java)
    private val activityWsService = mock(PolymarketActivityWsService::class.java)
    private val onChainWsService = mock(OnChainWsService::class.java)
    private val accountOnChainMonitorService = mock(AccountOnChainMonitorService::class.java)

    private val service = CopyTradingMonitorService(
        copyTradingRepository = copyTradingRepository,
        leaderRepository = leaderRepository,
        accountRepository = accountRepository,
        activityWsService = activityWsService,
        onChainWsService = onChainWsService,
        accountOnChainMonitorService = accountOnChainMonitorService
    )

    @Test
    fun `start monitoring skips broken copy trading rows without valid leader account pairs`() = runTest {
        val brokenCopyTrading = CopyTrading(id = 1L, accountId = 99L, leaderId = 7L, enabled = true)
        val leader = leader(id = 7L)

        `when`(copyTradingRepository.findByEnabledTrue()).thenReturn(listOf(brokenCopyTrading))
        `when`(leaderRepository.findAllById(listOf(7L))).thenReturn(listOf(leader))
        `when`(accountRepository.findAllById(listOf(99L))).thenReturn(emptyList())

        service.startMonitoring()

        verify(activityWsService, never()).start(org.mockito.ArgumentMatchers.anyList())
        verify(onChainWsService, never()).start(org.mockito.ArgumentMatchers.anyList())
        verify(accountOnChainMonitorService, never()).start(org.mockito.ArgumentMatchers.anyList())
        verify(activityWsService).stop()
        verify(onChainWsService).stop()
        verify(accountOnChainMonitorService).stop()
    }

    @Test
    fun `start monitoring only uses valid copy trading rows`() = runTest {
        val validCopyTrading = CopyTrading(id = 1L, accountId = 10L, leaderId = 7L, enabled = true)
        val brokenCopyTrading = CopyTrading(id = 2L, accountId = 99L, leaderId = 8L, enabled = true)
        val validLeader = leader(id = 7L)
        val brokenLeader = leader(id = 8L)
        val validAccount = account(id = 10L)

        `when`(copyTradingRepository.findByEnabledTrue()).thenReturn(listOf(validCopyTrading, brokenCopyTrading))
        `when`(leaderRepository.findAllById(listOf(7L, 8L))).thenReturn(listOf(validLeader, brokenLeader))
        `when`(accountRepository.findAllById(listOf(10L, 99L))).thenReturn(listOf(validAccount))

        service.startMonitoring()

        verify(activityWsService).start(listOf(validLeader))
        verify(onChainWsService).start(listOf(validLeader))
        verify(accountOnChainMonitorService).start(listOf(validAccount))
    }

    private fun leader(id: Long) = Leader(
        id = id,
        leaderAddress = "0x${id.toString().padStart(40, '0')}",
        leaderName = "leader-$id"
    )

    private fun account(id: Long) = Account(
        id = id,
        privateKey = "encrypted-private-key",
        walletAddress = "0x${id.toString().padStart(40, '1')}",
        proxyAddress = "0x${id.toString().padStart(40, '2')}",
        apiKey = "api-key-$id",
        apiSecret = "encrypted-secret",
        apiPassphrase = "encrypted-passphrase",
        accountName = "account-$id"
    )
}
