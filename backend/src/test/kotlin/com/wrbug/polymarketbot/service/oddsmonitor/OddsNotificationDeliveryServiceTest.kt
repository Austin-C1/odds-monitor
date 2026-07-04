package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.dto.NotificationConfigData
import com.wrbug.polymarketbot.dto.NotificationConfigDto
import com.wrbug.polymarketbot.dto.TelegramConfigData
import com.wrbug.polymarketbot.entity.OddsAlertRecord
import com.wrbug.polymarketbot.repository.OddsAlertRecordRepository
import com.wrbug.polymarketbot.repository.OddsMarketRepository
import com.wrbug.polymarketbot.repository.OddsSnapshotRepository
import com.wrbug.polymarketbot.service.system.NotificationTemplateService
import com.wrbug.polymarketbot.service.system.TelegramNotificationService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.math.BigDecimal

class OddsNotificationDeliveryServiceTest {
    private val alertRecordRepository = mock(OddsAlertRecordRepository::class.java)
    private val telegramNotificationService = mock(TelegramNotificationService::class.java)
    private val templateService = mock(NotificationTemplateService::class.java)
    private val eligibilityService = OddsNotificationEligibilityService(
        mock(OddsMarketRepository::class.java),
        mock(OddsSnapshotRepository::class.java)
    )

    @Test
    fun `template rendering is preferred and alert record is saved before telegram send`() {
        `when`(templateService.renderTemplate(anyString(), anyMap())).thenReturn("templated message")
        val service = deliveryService(templateService)

        service.flushNotification(pendingNotification())

        val captor = ArgumentCaptor.forClass(OddsAlertRecord::class.java)
        verify(alertRecordRepository).save(captor.capture())
        assertEquals("templated message", captor.value.message)
        runBlocking {
            verify(telegramNotificationService, times(1)).sendMonitorMessageToConfigs(anyString(), anyList())
        }
    }

    @Test
    fun `fallback message is saved even when telegram send fails`() {
        `when`(templateService.renderTemplate(anyString(), anyMap())).thenReturn("")
        runBlocking {
            `when`(telegramNotificationService.sendMonitorMessageToConfigs(anyString(), anyList()))
                .thenThrow(RuntimeException("telegram down"))
        }
        val service = deliveryService(templateService)

        service.flushNotification(pendingNotification())

        val captor = ArgumentCaptor.forClass(OddsAlertRecord::class.java)
        verify(alertRecordRepository).save(captor.capture())
        assertTrue(captor.value.message.contains("赔率变动：东京 vs 川崎前锋"))
    }

    private fun deliveryService(templateService: NotificationTemplateService) = OddsNotificationDeliveryService(
        alertRecordRepository = alertRecordRepository,
        telegramNotificationService = telegramNotificationService,
        dataSourceConfigRepository = null,
        notificationTemplateService = templateService,
        eligibilityService = eligibilityService
    )

    private fun pendingNotification(): PendingOddsChangeNotification {
        val config = NotificationConfigDto(
            id = 1,
            type = "telegram",
            name = "telegram",
            enabled = true,
            config = NotificationConfigData.Telegram(
                TelegramConfigData(
                    botToken = "token",
                    chatIds = listOf("1"),
                    monitorModeEnabled = true
                )
            )
        )
        return PendingOddsChangeNotification(
            matchName = "东京 vs 川崎前锋",
            leagueName = "日本J1",
            matchId = 100,
            candidate = OddsMatchCandidate(
                id = 100,
                leagueName = "日本J1",
                homeTeam = "东京",
                awayTeam = "川崎前锋",
                startTime = 2_000_000L
            ),
            configs = listOf(config),
            markets = linkedMapOf(
                OddsChangeNotificationMarketKey(
                    marketType = "handicap",
                    lineValue = "0.5",
                    selectionName = "home"
                ) to PendingOddsChangeMarketNotification(
                    marketType = "handicap",
                    marketLabel = "让球 主队 0.5",
                    changes = linkedMapOf(
                        "crown" to OddsChangeNotificationItem(
                            sourceKey = "crown",
                            previousOdds = BigDecimal("0.90"),
                            currentOdds = BigDecimal("1.00"),
                            lineValue = "0.5"
                        )
                    )
                )
            )
        )
    }
}
