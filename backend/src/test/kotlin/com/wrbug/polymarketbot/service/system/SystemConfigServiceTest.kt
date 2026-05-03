package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.entity.SystemConfig
import com.wrbug.polymarketbot.repository.SystemConfigRepository
import com.wrbug.polymarketbot.util.CryptoUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.math.BigDecimal

class SystemConfigServiceTest {

    private val systemConfigRepository = mock(SystemConfigRepository::class.java)
    private val cryptoUtils = mock(CryptoUtils::class.java)
    private val service = SystemConfigService(
        systemConfigRepository = systemConfigRepository,
        cryptoUtils = cryptoUtils,
        defaultOrderNotificationMinAmount = BigDecimal("10")
    )

    @Test
    fun `order notification minimum should default to configured value`() {
        `when`(systemConfigRepository.findByConfigKey(SystemConfigService.CONFIG_KEY_ORDER_NOTIFICATION_MIN_AMOUNT))
            .thenReturn(null)

        assertEquals(BigDecimal("10"), service.getOrderNotificationMinAmount())
    }

    @Test
    fun `order notification minimum should read saved value`() {
        `when`(systemConfigRepository.findByConfigKey(SystemConfigService.CONFIG_KEY_ORDER_NOTIFICATION_MIN_AMOUNT))
            .thenReturn(
                SystemConfig(
                    configKey = SystemConfigService.CONFIG_KEY_ORDER_NOTIFICATION_MIN_AMOUNT,
                    configValue = "25.5"
                )
            )

        assertEquals(BigDecimal("25.5"), service.getOrderNotificationMinAmount())
    }

    @Test
    fun `update order notification minimum should persist normalized amount`() {
        `when`(systemConfigRepository.findByConfigKey(SystemConfigService.CONFIG_KEY_ORDER_NOTIFICATION_MIN_AMOUNT))
            .thenReturn(null)
        val captor = ArgumentCaptor.forClass(SystemConfig::class.java)

        service.updateOrderNotificationMinAmount(BigDecimal("10.00"))

        verify(systemConfigRepository).save(captor.capture())
        assertEquals(SystemConfigService.CONFIG_KEY_ORDER_NOTIFICATION_MIN_AMOUNT, captor.value.configKey)
        assertEquals("10", captor.value.configValue)
    }
}
