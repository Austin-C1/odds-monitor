package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.dto.SystemConfigDto
import com.wrbug.polymarketbot.entity.SystemConfig
import com.wrbug.polymarketbot.repository.SystemConfigRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class SystemConfigServiceTest {

    private val systemConfigRepository = mock(SystemConfigRepository::class.java)
    private val service = SystemConfigService(systemConfigRepository)

    @Test
    fun `system config dto should only expose current odds monitor settings`() {
        val fields = SystemConfigDto::class.java.declaredFields.map { it.name }.toSet()

        assertEquals(setOf("liveObservationMinutes"), fields)
    }

    @Test
    fun `system config service should not keep old trading config keys`() {
        val currentKeys = SystemConfigService.configKeys()

        assertEquals(setOf(SystemConfigService.CONFIG_KEY_LIVE_OBSERVATION_MINUTES), currentKeys)
        assertFalse(currentKeys.any { it.contains("builder") || it.contains("auto_redeem") || it.contains("order_notification") })
    }

    @Test
    fun `live observation minutes should be unrestricted when not configured`() {
        `when`(systemConfigRepository.findByConfigKey(SystemConfigService.CONFIG_KEY_LIVE_OBSERVATION_MINUTES))
            .thenReturn(null)

        assertEquals(null, service.getLiveObservationMinutes())
        assertEquals(null, service.getSystemConfig().liveObservationMinutes)
    }

    @Test
    fun `update live observation minutes should persist blank as unrestricted`() {
        `when`(systemConfigRepository.findByConfigKey(SystemConfigService.CONFIG_KEY_LIVE_OBSERVATION_MINUTES))
            .thenReturn(null)
        val captor = ArgumentCaptor.forClass(SystemConfig::class.java)

        service.updateLiveObservationMinutes(null)

        verify(systemConfigRepository).save(captor.capture())
        assertEquals(SystemConfigService.CONFIG_KEY_LIVE_OBSERVATION_MINUTES, captor.value.configKey)
        assertEquals(null, captor.value.configValue)
    }

    @Test
    fun `update live observation minutes should persist positive minutes`() {
        `when`(systemConfigRepository.findByConfigKey(SystemConfigService.CONFIG_KEY_LIVE_OBSERVATION_MINUTES))
            .thenReturn(null)
        val captor = ArgumentCaptor.forClass(SystemConfig::class.java)

        service.updateLiveObservationMinutes(75)

        verify(systemConfigRepository).save(captor.capture())
        assertEquals(SystemConfigService.CONFIG_KEY_LIVE_OBSERVATION_MINUTES, captor.value.configKey)
        assertEquals("75", captor.value.configValue)
    }
}
