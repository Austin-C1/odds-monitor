package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.dto.NotificationConfigData
import com.wrbug.polymarketbot.dto.NotificationConfigDto
import com.wrbug.polymarketbot.dto.TelegramConfigData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MonitorNotificationDeliveryTest {

    @Test
    fun `all delivery should keep every telegram config`() {
        val configs = listOf(
            telegramConfigDto(id = 1L, monitorModeEnabled = false),
            telegramConfigDto(id = 2L, monitorModeEnabled = true)
        )

        val filtered = filterTelegramConfigsForAudience(configs, TelegramNotificationAudience.ALL)

        assertEquals(listOf(1L, 2L), filtered.mapNotNull { it.id })
    }

    @Test
    fun `monitor delivery should only keep monitor mode configs`() {
        val configs = listOf(
            telegramConfigDto(id = 1L, monitorModeEnabled = false),
            telegramConfigDto(id = 2L, monitorModeEnabled = true),
            telegramConfigDto(id = 3L, monitorModeEnabled = true)
        )

        val filtered = filterTelegramConfigsForAudience(configs, TelegramNotificationAudience.MONITOR_ONLY)

        assertEquals(listOf(2L, 3L), filtered.mapNotNull { it.id })
    }

    private fun telegramConfigDto(
        id: Long,
        monitorModeEnabled: Boolean
    ) = NotificationConfigDto(
        id = id,
        type = "telegram",
        name = "bot-$id",
        enabled = true,
        config = NotificationConfigData.Telegram(
            TelegramConfigData(
                botToken = "token-$id",
                chatIds = listOf("chat-$id"),
                monitorModeEnabled = monitorModeEnabled
            )
        )
    )
}
