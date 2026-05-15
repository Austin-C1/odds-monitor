package com.wrbug.polymarketbot.service.system

import com.fasterxml.jackson.databind.ObjectMapper
import com.wrbug.polymarketbot.dto.NotificationConfigData
import com.wrbug.polymarketbot.dto.NotificationConfigDto
import com.wrbug.polymarketbot.dto.TelegramConfigData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TelegramNotificationServiceTest {

    @Test
    fun `monitor audience keeps only monitor-enabled telegram configs`() {
        val configs = listOf(
            telegramConfig(id = 1, name = "monitor", monitorModeEnabled = true),
            telegramConfig(id = 2, name = "standard", monitorModeEnabled = false)
        )

        val filtered = filterTelegramConfigsForAudience(
            configs = configs,
            audience = TelegramNotificationAudience.MONITOR_ONLY
        )

        assertEquals(listOf(1L), filtered.map { it.id })
    }

    @Test
    fun `telegram config filter can exclude selected ids`() {
        val configs = listOf(
            telegramConfig(id = 1, name = "primary", monitorModeEnabled = true),
            telegramConfig(id = 2, name = "secondary", monitorModeEnabled = true)
        )

        val filtered = filterTelegramConfigsForAudience(
            configs = configs,
            audience = TelegramNotificationAudience.MONITOR_ONLY,
            excludedConfigIds = setOf(2L)
        )

        assertEquals(listOf(1L), filtered.map { it.id })
    }

    @Test
    fun `telegram chat target should parse forum topic path`() {
        val target = parseTelegramChatTarget("-1003968074764:5")

        assertEquals("-1003968074764", target.chatId)
        assertEquals(5, target.messageThreadId)
    }

    @Test
    fun `telegram chat target should keep plain group path`() {
        val target = parseTelegramChatTarget("-1003968074764")

        assertEquals("-1003968074764", target.chatId)
        assertEquals(null, target.messageThreadId)
    }

    @Test
    fun `telegram chat ids should include group membership updates before private chats`() {
        val updates = ObjectMapper().readTree(
            """
            [
              {
                "message": {
                  "chat": {"id": 6369496282, "type": "private"},
                  "text": "start"
                }
              },
              {
                "my_chat_member": {
                  "chat": {"id": -1003968074764, "title": "全平台赔率监控", "type": "supergroup", "is_forum": true}
                }
              },
              {
                "message": {
                  "chat": {"id": -1003968074764, "title": "全平台赔率监控", "type": "supergroup", "is_forum": true},
                  "message_thread_id": 9,
                  "text": "/start"
                }
              }
            ]
            """.trimIndent()
        )

        assertEquals(
            listOf("-1003968074764", "-1003968074764:9", "6369496282"),
            extractTelegramChatIdsFromUpdates(updates)
        )
    }

    @Test
    fun `telegram display text should localize market terms and teams`() {
        assertEquals("让分：骑士 (-9.5)", translateTelegramDisplayText("Spread: Cavaliers (-9.5)"))
        assertEquals("骑士 54146.584u / 猛龙 465.1807u", translateTelegramDisplayText("Cavaliers 54146.584u / Raptors 465.1807u"))
        assertEquals("是 100u / 否 200u", translateTelegramDisplayText("Yes 100u / No 200u"))
    }

    @Test
    fun `telegram update should match configured topic only`() {
        val update = TelegramIncomingUpdate(
            updateId = 1,
            chatId = "-1003968074764",
            text = "/monitor",
            messageThreadId = 4
        )

        assertTrue(telegramUpdateMatchesConfiguredTarget(update, listOf("-1003968074764:4")))
        assertFalse(telegramUpdateMatchesConfiguredTarget(update, listOf("-1003968074764:3")))
        assertFalse(telegramUpdateMatchesConfiguredTarget(update.copy(messageThreadId = null), listOf("-1003968074764:4")))
        assertEquals("-1003968074764:4", telegramUpdateTargetPath(update))
    }

    @Test
    fun `monitor phase control command should open mode buttons`() {
        assertTrue(isMonitorPhaseControlCommand("/monitor"))
        assertTrue(isMonitorPhaseControlCommand("/mode"))
        assertTrue(isMonitorPhaseControlCommand("/start"))
        assertTrue(isMonitorPhaseControlCommand("滚球"))
        assertFalse(isMonitorPhaseControlCommand("/market arsenal"))
    }

    @Test
    fun `monitor phase callback should parse live and prematch buttons`() {
        assertEquals(true, monitorPhaseCallbackMode("odds-monitor:phase:live"))
        assertEquals(false, monitorPhaseCallbackMode("odds-monitor:phase:prematch"))
        assertEquals(null, monitorPhaseCallbackMode("unknown"))
    }

    @Test
    fun `monitor phase control message should show current mode`() {
        val liveMessage = buildMonitorPhaseControlMessage(liveOnlyModeEnabled = true)
        val prematchMessage = buildMonitorPhaseControlMessage(liveOnlyModeEnabled = false)

        assertTrue(liveMessage.contains("当前模式：滚球"))
        assertTrue(prematchMessage.contains("当前模式：赛前"))
    }

    private fun telegramConfig(
        id: Long,
        name: String,
        monitorModeEnabled: Boolean
    ) = NotificationConfigDto(
        id = id,
        type = "telegram",
        name = name,
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
