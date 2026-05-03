package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.dto.NotificationConfigData
import com.wrbug.polymarketbot.dto.NotificationConfigDto
import com.wrbug.polymarketbot.dto.TelegramConfigData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MonitorNotificationDeliveryTest {

    @Test
    fun `standard delivery should keep non monitor configs when monitor mode is enabled`() {
        val configs = listOf(
            telegramConfigDto(id = 1L, monitorModeEnabled = false),
            telegramConfigDto(id = 2L, monitorModeEnabled = true)
        )

        val filtered = filterTelegramConfigsForAudience(configs, TelegramNotificationAudience.STANDARD)

        assertEquals(listOf(1L), filtered.mapNotNull { it.id })
    }

    @Test
    fun `standard delivery should exclude market betting query bots`() {
        val configs = listOf(
            telegramConfigDto(id = 1L, monitorModeEnabled = false, marketBettingQueryEnabled = true),
            telegramConfigDto(id = 2L, monitorModeEnabled = false, marketBettingQueryEnabled = false)
        )

        val filtered = filterTelegramConfigsForAudience(configs, TelegramNotificationAudience.STANDARD)

        assertEquals(listOf(2L), filtered.mapNotNull { it.id })
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

    @Test
    fun `monitor delivery should exclude market betting query bots`() {
        val configs = listOf(
            telegramConfigDto(id = 1L, monitorModeEnabled = true, marketBettingQueryEnabled = true),
            telegramConfigDto(id = 2L, monitorModeEnabled = true, marketBettingQueryEnabled = false)
        )

        val filtered = filterTelegramConfigsForAudience(configs, TelegramNotificationAudience.MONITOR_ONLY)

        assertEquals(listOf(2L), filtered.mapNotNull { it.id })
    }

    @Test
    fun `standard delivery should fall back to monitor configs when no standard config exists`() {
        val configs = listOf(
            telegramConfigDto(id = 2L, monitorModeEnabled = true),
            telegramConfigDto(id = 3L, monitorModeEnabled = true)
        )

        val filtered = filterTelegramConfigsForAudience(configs, TelegramNotificationAudience.STANDARD)

        assertEquals(listOf(2L, 3L), filtered.mapNotNull { it.id })
    }

    @Test
    fun `standard fallback should still exclude query-only bots`() {
        val configs = listOf(
            telegramConfigDto(id = 1L, monitorModeEnabled = false, marketBettingQueryEnabled = true),
            telegramConfigDto(id = 2L, monitorModeEnabled = true, marketBettingQueryEnabled = false)
        )

        val filtered = filterTelegramConfigsForAudience(configs, TelegramNotificationAudience.STANDARD)

        assertEquals(listOf(2L), filtered.mapNotNull { it.id })
    }

    private fun telegramConfigDto(
        id: Long,
        monitorModeEnabled: Boolean,
        marketBettingQueryEnabled: Boolean = false
    ) = NotificationConfigDto(
        id = id,
        type = "telegram",
        name = "bot-$id",
        enabled = true,
        config = NotificationConfigData.Telegram(
            TelegramConfigData(
                botToken = "token-$id",
                chatIds = listOf("chat-$id"),
                monitorModeEnabled = monitorModeEnabled,
                marketBettingQueryEnabled = marketBettingQueryEnabled
            )
        )
    )
}
