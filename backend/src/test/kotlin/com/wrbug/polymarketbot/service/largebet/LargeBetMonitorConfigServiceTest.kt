package com.wrbug.polymarketbot.service.largebet

import com.wrbug.polymarketbot.dto.LargeBetMonitorConfigUpdateRequest
import com.wrbug.polymarketbot.entity.LargeBetMonitorConfig
import com.wrbug.polymarketbot.repository.LargeBetMonitorConfigRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.math.BigDecimal

class LargeBetMonitorConfigServiceTest {

    private val repository = mock(LargeBetMonitorConfigRepository::class.java)
    private val service = LargeBetMonitorConfigService(repository)

    @Test
    fun `creates default config when none exists`() = runBlocking {
        `when`(repository.findAll()).thenReturn(emptyList())
        `when`(repository.save(any())).thenAnswer { invocation -> invocation.arguments[0] as LargeBetMonitorConfig }

        val config = service.getConfig()

        assertEquals(false, config.enabled)
        assertEquals(true, config.footballEnabled)
        assertEquals(true, config.basketballEnabled)
        assertEquals("5000.00000000", config.singleTradeThreshold)
        assertEquals("15000.00000000", config.cumulativeTradeThreshold)
        assertEquals(60, config.rollingWindowMinutes)
    }

    @Test
    fun `updates configurable thresholds and window`() = runBlocking {
        val existing = LargeBetMonitorConfig(id = 1L)
        `when`(repository.findAll()).thenReturn(listOf(existing))
        `when`(repository.save(any())).thenAnswer { invocation -> invocation.arguments[0] as LargeBetMonitorConfig }

        val result = service.updateConfig(
            LargeBetMonitorConfigUpdateRequest(
                enabled = true,
                footballEnabled = true,
                basketballEnabled = false,
                singleTradeThreshold = "7000",
                cumulativeTradeThreshold = "21000",
                rollingWindowMinutes = 120,
                checkIntervalSeconds = 15,
                telegramConfigId = 3L
            )
        )

        assertTrue(result.isSuccess)
        val config = result.getOrThrow()
        assertEquals("7000.00000000", config.singleTradeThreshold)
        assertEquals("21000.00000000", config.cumulativeTradeThreshold)
        assertEquals(120, config.rollingWindowMinutes)
        assertEquals(15, config.checkIntervalSeconds)
        assertEquals(3L, config.telegramConfigId)
    }

    @Test
    fun `rejects enabled config without any sport`() = runBlocking {
        `when`(repository.findAll()).thenReturn(listOf(LargeBetMonitorConfig(id = 1L)))

        val result = service.updateConfig(
            LargeBetMonitorConfigUpdateRequest(
                enabled = true,
                footballEnabled = false,
                basketballEnabled = false,
                singleTradeThreshold = "5000",
                cumulativeTradeThreshold = "15000",
                rollingWindowMinutes = 60,
                checkIntervalSeconds = 30,
                telegramConfigId = null
            )
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun `rejects non positive thresholds`() = runBlocking {
        `when`(repository.findAll()).thenReturn(listOf(LargeBetMonitorConfig(id = 1L)))

        val result = service.updateConfig(
            LargeBetMonitorConfigUpdateRequest(
                enabled = false,
                footballEnabled = true,
                basketballEnabled = true,
                singleTradeThreshold = "0",
                cumulativeTradeThreshold = BigDecimal("-1").toPlainString(),
                rollingWindowMinutes = 60,
                checkIntervalSeconds = 30,
                telegramConfigId = null
            )
        )

        assertTrue(result.isFailure)
    }
}
