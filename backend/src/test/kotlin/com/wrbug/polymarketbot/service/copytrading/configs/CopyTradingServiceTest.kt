package com.wrbug.polymarketbot.service.copytrading.configs

import com.google.gson.Gson
import com.wrbug.polymarketbot.dto.CopyTradingCreateRequest
import com.wrbug.polymarketbot.dto.CopyTradingUpdateRequest
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.entity.CopyTradingFollowRule
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyTradingFollowRuleRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.CopyTradingTemplateRepository
import com.wrbug.polymarketbot.repository.LeaderCopyTradingControlRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.service.copytrading.monitor.CopyTradingMonitoringRefreshEvent
import com.wrbug.polymarketbot.util.JsonUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.util.Optional

class CopyTradingServiceTest {

    private val copyTradingRepository = mock(CopyTradingRepository::class.java)
    private val copyTradingFollowRuleRepository = mock(CopyTradingFollowRuleRepository::class.java)
    private val accountRepository = mock(AccountRepository::class.java)
    private val templateRepository = mock(CopyTradingTemplateRepository::class.java)
    private val leaderRepository = mock(LeaderRepository::class.java)
    private val leaderCopyTradingControlRepository = mock(LeaderCopyTradingControlRepository::class.java)
    private val applicationEventPublisher = mock(ApplicationEventPublisher::class.java)
    private val gson = Gson()
    private val jsonUtils = JsonUtils(gson).apply { init() }

    private val service = CopyTradingService(
        copyTradingRepository = copyTradingRepository,
        copyTradingFollowRuleRepository = copyTradingFollowRuleRepository,
        accountRepository = accountRepository,
        templateRepository = templateRepository,
        leaderRepository = leaderRepository,
        leaderCopyTradingControlRepository = leaderCopyTradingControlRepository,
        applicationEventPublisher = applicationEventPublisher,
        jsonUtils = jsonUtils,
        gson = gson
    )

    @Test
    fun `createCopyTrading publishes monitoring refresh event when config is enabled`() {
        val account = account()
        val leader = leader()
        val saved = copyTrading(id = 9L, accountId = account.id!!, leaderId = leader.id!!, enabled = true)

        `when`(accountRepository.findById(account.id!!)).thenReturn(Optional.of(account))
        `when`(leaderRepository.findById(leader.id!!)).thenReturn(Optional.of(leader))
        `when`(copyTradingRepository.save(org.mockito.ArgumentMatchers.any(CopyTrading::class.java))).thenReturn(saved)
        `when`(copyTradingFollowRuleRepository.findByCopyTradingIdOrderBySortOrderAsc(saved.id!!)).thenReturn(emptyList())

        val result = service.createCopyTrading(
            CopyTradingCreateRequest(
                accountId = account.id!!,
                leaderId = leader.id!!,
                configName = "Demo"
            )
        )

        assertTrue(result.isSuccess)
        verifyMonitoringEvent(saved.leaderId, listOf(saved.accountId))
    }

    @Test
    fun `updateCopyTrading publishes monitoring refresh event for the saved config`() {
        val existing = copyTrading(id = 11L)
        val account = account(id = existing.accountId)
        val leader = leader(id = existing.leaderId)

        `when`(copyTradingRepository.findById(existing.id!!)).thenReturn(Optional.of(existing))
        `when`(copyTradingRepository.save(org.mockito.ArgumentMatchers.any(CopyTrading::class.java)))
            .thenAnswer { it.arguments[0] as CopyTrading }
        `when`(accountRepository.findById(existing.accountId)).thenReturn(Optional.of(account))
        `when`(leaderRepository.findById(existing.leaderId)).thenReturn(Optional.of(leader))
        `when`(copyTradingFollowRuleRepository.findByCopyTradingIdOrderBySortOrderAsc(existing.id!!)).thenReturn(emptyList())

        val result = service.updateCopyTrading(
            CopyTradingUpdateRequest(
                copyTradingId = existing.id!!,
                configName = "Updated Demo"
            )
        )

        assertTrue(result.isSuccess)
        verifyMonitoringEvent(existing.leaderId, listOf(existing.accountId))
    }

    @Test
    fun `deleteCopyTrading publishes monitoring refresh event instead of refreshing inline`() {
        val existing = copyTrading(id = 13L, accountId = 7L, leaderId = 5L)

        `when`(copyTradingRepository.findById(existing.id!!)).thenReturn(Optional.of(existing))

        val result = service.deleteCopyTrading(existing.id!!)

        assertTrue(result.isSuccess)
        verify(copyTradingRepository).delete(existing)
        verifyMonitoringEvent(existing.leaderId, listOf(existing.accountId))
    }

    @Test
    fun `getCopyTradingDetail returns one config with its follow rules`() {
        val existing = copyTrading(id = 15L)
        val account = account(id = existing.accountId)
        val leader = leader(id = existing.leaderId)
        val followRule = CopyTradingFollowRule(
            id = 1L,
            copyTradingId = existing.id!!,
            minLeaderAmount = BigDecimal.ZERO,
            maxLeaderAmount = BigDecimal("100"),
            followAmount = BigDecimal("10"),
            followMaxAmount = BigDecimal("20"),
            sortOrder = 1
        )

        `when`(copyTradingRepository.findById(existing.id!!)).thenReturn(Optional.of(existing))
        `when`(accountRepository.findById(existing.accountId)).thenReturn(Optional.of(account))
        `when`(leaderRepository.findById(existing.leaderId)).thenReturn(Optional.of(leader))
        `when`(copyTradingFollowRuleRepository.findByCopyTradingIdOrderBySortOrderAsc(existing.id!!))
            .thenReturn(listOf(followRule))

        val result = service.getCopyTradingDetail(existing.id!!)

        assertTrue(result.isSuccess)
        val dto = result.getOrNull()
        assertNotNull(dto)
        assertEquals(existing.id, dto?.id)
        assertEquals(1, dto?.followRules?.size)
        assertEquals("10", dto?.followRules?.first()?.followAmount)
    }

    @Test
    fun `createCopyTrading preserves explicit false flags`() {
        val account = account()
        val leader = leader()

        `when`(accountRepository.findById(account.id!!)).thenReturn(Optional.of(account))
        `when`(leaderRepository.findById(leader.id!!)).thenReturn(Optional.of(leader))
        `when`(copyTradingRepository.save(org.mockito.ArgumentMatchers.any(CopyTrading::class.java)))
            .thenAnswer { (it.arguments[0] as CopyTrading).copy(id = 31L) }
        `when`(copyTradingFollowRuleRepository.findByCopyTradingIdOrderBySortOrderAsc(31L))
            .thenReturn(emptyList())

        val result = service.createCopyTrading(
            CopyTradingCreateRequest(
                accountId = account.id!!,
                leaderId = leader.id!!,
                configName = "Flags Off",
                followSettingsEnabled = false,
                useWebSocket = false,
                supportSell = false,
                pushFilteredOrders = false
            )
        )

        assertTrue(result.isSuccess)
        val dto = result.getOrNull()
        assertNotNull(dto)
        assertFalse(dto!!.followSettingsEnabled)
        assertFalse(dto.useWebSocket)
        assertFalse(dto.supportSell)
        assertFalse(dto.pushFilteredOrders)
    }

    @Test
    fun `updateCopyTrading preserves explicit false flags`() {
        val existing = copyTrading(
            id = 21L,
            followSettingsEnabled = true,
            useWebSocket = true,
            supportSell = true,
            pushFailedOrders = true,
            pushFilteredOrders = true
        )
        val account = account(id = existing.accountId)
        val leader = leader(id = existing.leaderId)

        `when`(copyTradingRepository.findById(existing.id!!)).thenReturn(Optional.of(existing))
        `when`(copyTradingRepository.save(org.mockito.ArgumentMatchers.any(CopyTrading::class.java)))
            .thenAnswer { it.arguments[0] as CopyTrading }
        `when`(accountRepository.findById(existing.accountId)).thenReturn(Optional.of(account))
        `when`(leaderRepository.findById(existing.leaderId)).thenReturn(Optional.of(leader))
        `when`(copyTradingFollowRuleRepository.findByCopyTradingIdOrderBySortOrderAsc(existing.id!!)).thenReturn(emptyList())

        val result = service.updateCopyTrading(
            CopyTradingUpdateRequest(
                copyTradingId = existing.id!!,
                followSettingsEnabled = false,
                useWebSocket = false,
                supportSell = false,
                pushFailedOrders = false,
                pushFilteredOrders = false
            )
        )

        assertTrue(result.isSuccess)
        val dto = result.getOrNull()
        assertNotNull(dto)
        assertFalse(dto!!.followSettingsEnabled)
        assertFalse(dto.useWebSocket)
        assertFalse(dto.supportSell)
        assertFalse(dto.pushFailedOrders)
        assertFalse(dto.pushFilteredOrders)
    }

    private fun verifyMonitoringEvent(expectedLeaderId: Long, expectedAccountIds: List<Long>) {
        val captor = ArgumentCaptor.forClass(CopyTradingMonitoringRefreshEvent::class.java)
        verify(applicationEventPublisher).publishEvent(captor.capture())
        assertEquals(expectedLeaderId, captor.value.leaderId)
        assertEquals(expectedAccountIds, captor.value.accountIds)
    }

    private fun copyTrading(
        id: Long? = 1L,
        accountId: Long = 2L,
        leaderId: Long = 3L,
        enabled: Boolean = true,
        followSettingsEnabled: Boolean = false,
        useWebSocket: Boolean = true,
        supportSell: Boolean = true,
        pushFailedOrders: Boolean = false,
        pushFilteredOrders: Boolean = false
    ) = CopyTrading(
        id = id,
        accountId = accountId,
        leaderId = leaderId,
        enabled = enabled,
        followSettingsEnabled = followSettingsEnabled,
        useWebSocket = useWebSocket,
        supportSell = supportSell,
        pushFailedOrders = pushFailedOrders,
        pushFilteredOrders = pushFilteredOrders,
        configName = "Demo Config"
    )

    private fun account(id: Long = 2L) = Account(
        id = id,
        privateKey = "encrypted",
        walletAddress = "0x1234567890123456789012345678901234567890",
        proxyAddress = "0x1234567890123456789012345678901234567891",
        accountName = "DemoAccount"
    )

    private fun leader(id: Long = 3L) = Leader(
        id = id,
        leaderAddress = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
        leaderName = "DemoLeader"
    )
}
