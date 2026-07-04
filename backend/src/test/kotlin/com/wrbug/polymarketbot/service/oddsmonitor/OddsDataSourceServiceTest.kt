package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.dto.OddsDataSourceConfigDto
import com.wrbug.polymarketbot.entity.OddsCollectionLog
import com.wrbug.polymarketbot.entity.OddsDataSourceConfig
import com.wrbug.polymarketbot.repository.OddsCollectionLogRepository
import com.wrbug.polymarketbot.repository.OddsDataSourceConfigRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class OddsDataSourceServiceTest {
    @Test
    fun `lists crown config and ignores non collector sources`() {
        val fixtures = Fixtures()
        `when`(fixtures.configRepository.findAll()).thenReturn(
            listOf(
                OddsDataSourceConfig(sourceKey = "crown", displayName = "crown", enabled = true),
                OddsDataSourceConfig(sourceKey = "polymarket", displayName = "Polymarket", enabled = true)
            )
        )

        val configs = fixtures.service().listConfigs()

        assertEquals(listOf("crown"), configs.map { it.sourceKey })
        assertEquals("皇冠", configs.single().displayName)
    }

    @Test
    fun `preserves existing crown credentials when saved form sends blank fields`() {
        val fixtures = Fixtures()
        val existing = OddsDataSourceConfig(
            id = 7,
            sourceKey = "crown",
            displayName = "crown",
            enabled = true,
            username = "saved-user",
            password = "saved-pass",
            queryKeyword = "https://crown.example/",
            intervalSeconds = 60,
            createdAt = 10,
            updatedAt = 20
        )
        `when`(fixtures.configRepository.findBySourceKey("crown")).thenReturn(existing)
        `when`(fixtures.configRepository.findAll()).thenReturn(listOf(existing))

        fixtures.service().saveConfigs(
            listOf(
                OddsDataSourceConfigDto(
                    sourceKey = "crown",
                    displayName = "crown",
                    enabled = true,
                    username = "",
                    password = "",
                    queryKeyword = "",
                    intervalSeconds = 5,
                    updatedAt = 0
                )
            )
        )

        val captor = ArgumentCaptor.forClass(OddsDataSourceConfig::class.java)
        verify(fixtures.configRepository).save(captor.capture())
        assertEquals("saved-user", captor.value.username)
        assertEquals("saved-pass", captor.value.password)
        assertEquals("https://crown.example/", captor.value.queryKeyword)
        assertEquals(10, captor.value.intervalSeconds)
    }

    @Test
    fun `reports latest crown status without stale failure after success`() {
        val fixtures = Fixtures()
        `when`(fixtures.configRepository.findAll()).thenReturn(
            listOf(OddsDataSourceConfig(sourceKey = "crown", displayName = "crown", enabled = true))
        )
        `when`(fixtures.logRepository.findTop1BySourceKeyOrderByStartedAtDesc("crown")).thenReturn(
            OddsCollectionLog(sourceKey = "crown", status = "success", message = "ok", startedAt = 300, finishedAt = 350)
        )
        `when`(fixtures.logRepository.findTop1BySourceKeyAndStatusOrderByStartedAtDesc("crown", "success")).thenReturn(
            OddsCollectionLog(sourceKey = "crown", status = "success", message = "ok", startedAt = 300, finishedAt = 350)
        )
        `when`(fixtures.logRepository.findTop1FailureBySourceKey("crown")).thenReturn(
            OddsCollectionLog(sourceKey = "crown", status = "failed_login", message = "old", startedAt = 100, finishedAt = 120)
        )

        val status = fixtures.service().listStatuses().single()

        assertEquals("success", status.currentStatus)
        assertEquals(300, status.lastSuccessTime)
        assertEquals(null, status.lastFailureTime)
        assertEquals(null, status.failureReason)
    }

    private class Fixtures {
        val configRepository: OddsDataSourceConfigRepository = mock(OddsDataSourceConfigRepository::class.java)
        val logRepository: OddsCollectionLogRepository = mock(OddsCollectionLogRepository::class.java)
        val notificationService: OddsChangeNotificationService = mock(OddsChangeNotificationService::class.java)
        val displayMapper = OddsMonitorDisplayMapper()

        fun service(): OddsDataSourceService {
            return OddsDataSourceService(configRepository, logRepository, notificationService, displayMapper)
        }
    }
}
