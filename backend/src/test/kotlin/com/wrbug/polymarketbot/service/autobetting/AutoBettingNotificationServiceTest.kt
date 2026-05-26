package com.wrbug.polymarketbot.service.autobetting

import com.wrbug.polymarketbot.entity.AutoBettingIntent
import com.wrbug.polymarketbot.service.system.NotificationTemplateService
import com.wrbug.polymarketbot.service.system.TelegramNotificationService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.math.BigDecimal

class AutoBettingNotificationServiceTest {
    @Test
    fun `placed intent is sent only through betting success telegram channel`() {
        val notificationTemplateService = mock(NotificationTemplateService::class.java)
        val telegramNotificationService = mock(TelegramNotificationService::class.java)
        val service = AutoBettingNotificationService(notificationTemplateService, telegramNotificationService)
        val intent = bettingIntent()
        val variables = buildBettingSuccessTemplateVariables(intent, now = 1_002_000)
        `when`(notificationTemplateService.renderTemplate("BETTING_TEMPLATE", variables)).thenReturn("<b>placed</b>")

        service.sendPlacedIntent(intent, now = 1_002_000)

        runBlocking {
            verify(telegramNotificationService).sendBettingSuccessMessage("<b>placed</b>")
            verify(telegramNotificationService, never()).sendMessage("<b>placed</b>")
        }
    }

    @Test
    fun `betting success template variables include account market odds and amount`() {
        val variables = buildBettingSuccessTemplateVariables(bettingIntent(), now = 1_002_000)

        assertEquals("皇冠一号", variables["account_name"])
        assertEquals("crown-account-1", variables["account_key"])
        assertEquals("让球 0.5/1", variables["market_title"])
        assertEquals("主队", variables["selection_name"])
        assertEquals("0.930", variables["odds"])
        assertEquals("50.00", variables["amount"])
        assertTrue(variables["time"]!!.isNotBlank())
    }

    private fun bettingIntent() = AutoBettingIntent(
        accountKey = "crown-account-1",
        accountDisplayName = "皇冠一号",
        leagueName = "Premier League",
        matchTitle = "Arsenal vs Chelsea",
        marketType = "handicap",
        lineValue = "0.5/1",
        selectionName = "home",
        targetOdds = BigDecimal("0.93000000"),
        stakeAmount = BigDecimal("50.0000"),
        status = "placed",
        capturedAt = 1_000_000,
        createdAt = 1_001_000,
        updatedAt = 1_002_000
    )
}
