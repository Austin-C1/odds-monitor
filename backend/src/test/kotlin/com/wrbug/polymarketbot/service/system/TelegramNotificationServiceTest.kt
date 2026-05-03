package com.wrbug.polymarketbot.service.system

import com.fasterxml.jackson.databind.ObjectMapper
import com.wrbug.polymarketbot.dto.NotificationConfigData
import com.wrbug.polymarketbot.dto.NotificationConfigDto
import com.wrbug.polymarketbot.dto.TelegramConfigData
import com.wrbug.polymarketbot.repository.LargeBetMonitorConfigRepository
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.context.support.StaticMessageSource
import java.math.BigDecimal
import java.util.Locale

class TelegramNotificationServiceTest {

    @Test
    fun `monitor messages should exclude large bet assigned telegram config`() {
        val configs = listOf(
            telegramConfig(id = 1, name = "charlie", monitorModeEnabled = true),
            telegramConfig(id = 2, name = "william", monitorModeEnabled = true)
        )

        val filtered = filterTelegramConfigsForAudience(
            configs = configs,
            audience = TelegramNotificationAudience.MONITOR_ONLY,
            excludedConfigIds = setOf(2L)
        )

        assertEquals(listOf(1L), filtered.map { it.id })
    }

    @Test
    fun `copy trading route should match leader custom group only`() {
        val route = CopyTradingTelegramRoute(
            telegramConfigId = 1L,
            leaderGroups = listOf("体育组")
        )

        assertTrue(route.matches("体育组"))
        assertFalse(route.matches("政治组"))
        assertFalse(route.matches(null))
    }

    @Test
    fun `telegram config route filters should match leader custom group`() {
        val config = TelegramConfigData(
            botToken = "token",
            chatIds = listOf("chat"),
            monitorModeEnabled = true,
            copyTradingLeaderGroups = listOf("体育组")
        )

        assertTrue(hasCopyTradingRouteFilters(config))
        assertTrue(telegramConfigMatchesCopyTradingRoute(config, "体育组"))
        assertFalse(telegramConfigMatchesCopyTradingRoute(config, "政治组"))
    }

    @Test
    fun `telegram config route filters should treat empty filters as all`() {
        val config = TelegramConfigData(
            botToken = "token",
            chatIds = listOf("chat"),
            copyTradingLeaderGroups = emptyList()
        )

        assertFalse(hasCopyTradingRouteFilters(config))
        assertTrue(telegramConfigMatchesCopyTradingRoute(config, "体育组"))
        assertTrue(telegramConfigMatchesCopyTradingRoute(config, null))
    }

    @Test
    fun `old category and message type filters should not activate monitor leader group routing`() {
        val config = TelegramConfigData(
            botToken = "token",
            chatIds = listOf("chat"),
            copyTradingCategories = listOf("sports"),
            copyTradingNotificationTypes = listOf("monitor")
        )

        assertFalse(hasCopyTradingRouteFilters(config))
        assertTrue(telegramConfigMatchesCopyTradingRoute(config, "体育组"))
    }

    @Test
    fun `market betting query should use only enabled query bots`() {
        val configs = listOf(
            telegramConfig(id = 1, name = "query", monitorModeEnabled = false, marketBettingQueryEnabled = true),
            telegramConfig(id = 2, name = "normal", monitorModeEnabled = false, marketBettingQueryEnabled = false)
        )

        val filtered = filterMarketBettingQueryTelegramConfigs(configs)

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
    fun `market betting query update should match configured topic only`() {
        val update = TelegramIncomingUpdate(
            updateId = 1,
            chatId = "-1003968074764",
            text = "/market OK-01",
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

    @Test
    fun `signal source display should include config name before leader name`() {
        assertEquals("crocodile-main / 3crocodile3", buildSignalSourceDisplay("crocodile-main", "3crocodile3"))
    }

    @Test
    fun `signal source display should fall back to leader name`() {
        assertEquals("3crocodile3", buildSignalSourceDisplay(null, "3crocodile3"))
    }

    @Test
    fun `signal source display should remove duplicate values`() {
        assertEquals("3crocodile3", buildSignalSourceDisplay("3crocodile3", "3crocodile3"))
    }

    @Test
    fun `order success notification should suppress amounts below configured minimum`() {
        assertTrue(shouldSuppressOrderNotificationAmount("9.9999", BigDecimal("10")))
        assertFalse(shouldSuppressOrderNotificationAmount("10", BigDecimal("10")))
        assertFalse(shouldSuppressOrderNotificationAmount("12.5", BigDecimal("10")))
        assertFalse(shouldSuppressOrderNotificationAmount(null, BigDecimal("10")))
    }

    @Test
    fun `signal source details should include current position value on next line`() {
        assertEquals(
            "\u2022 \u4fe1\u53f7\u6e90: crocodile-main / 3crocodile3\n\u2022 \u5f53\u524d\u6301\u4ed3\u91d1\u989d: 20000u",
            buildSignalSourceDetails(
                configName = "crocodile-main",
                leaderName = "3crocodile3",
                signalSourceLabel = "\u4fe1\u53f7\u6e90",
                currentPositionValueLabel = "\u5f53\u524d\u6301\u4ed3\u91d1\u989d",
                currentPositionValue = "20000.0000"
            )
        )
    }

    @Test
    fun `signal source details should omit current position value when it is blank`() {
        assertEquals(
            "\u2022 \u4fe1\u53f7\u6e90: crocodile-main / 3crocodile3",
            buildSignalSourceDetails(
                configName = "crocodile-main",
                leaderName = "3crocodile3",
                signalSourceLabel = "\u4fe1\u53f7\u6e90",
                currentPositionValueLabel = "\u5f53\u524d\u6301\u4ed3\u91d1\u989d",
                currentPositionValue = "   "
            )
        )
    }

    @Test
    fun `order failure variables should include current position value under signal source`() {
        val messageSource = StaticMessageSource()
        val service = TelegramNotificationService(
            notificationConfigService = mock(NotificationConfigService::class.java),
            notificationTemplateService = mock(NotificationTemplateService::class.java),
            objectMapper = ObjectMapper(),
            messageSource = messageSource,
            largeBetMonitorConfigRepository = mock(LargeBetMonitorConfigRepository::class.java),
            systemConfigService = mock(SystemConfigService::class.java)
        )
        val method = TelegramNotificationService::class.java.getDeclaredMethod(
            "buildOrderFailureVariables",
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            Locale::class.java,
            String::class.java,
            String::class.java,
            String::class.java
        )
        method.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        val vars = method.invoke(
            service,
            "US-Iran nuclear deal by April 30?",
            null,
            "us-iran-nuclear-deal-by-april-30",
            "BUY",
            "No",
            "0.6693",
            "74.7",
            "49.99971",
            "code=403",
            "de",
            "0x1277000000004143",
            "debased",
            "\u8ddf\u5355-2026-04-19 12:19:49",
            Locale.SIMPLIFIED_CHINESE,
            "20000.0000",
            "\u672a\u77e5\u8d26\u6237",
            "\u8ba1\u7b97\u5931\u8d25"
        ) as Map<String, String>

        val accountInfo = vars["account_name"].orEmpty()
        assertTrue(accountInfo.contains("\u2022 \u4fe1\u53f7\u6e90: \u8ddf\u5355-2026-04-19 12:19:49 / debased"))
        assertTrue(accountInfo.contains("\u2022 \u5f53\u524d\u6301\u4ed3\u91d1\u989d: 20000u"))
    }

    @Test
    fun `order filtered variables should include account info`() {
        val messageSource = StaticMessageSource()
        val service = TelegramNotificationService(
            notificationConfigService = mock(NotificationConfigService::class.java),
            notificationTemplateService = mock(NotificationTemplateService::class.java),
            objectMapper = ObjectMapper(),
            messageSource = messageSource,
            largeBetMonitorConfigRepository = mock(LargeBetMonitorConfigRepository::class.java),
            systemConfigService = mock(SystemConfigService::class.java)
        )
        val method = TelegramNotificationService::class.java.getDeclaredMethod(
            "buildOrderFilteredVariables",
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            Locale::class.java,
            String::class.java,
            String::class.java
        )
        method.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        val vars = method.invoke(
            service,
            "US-Iran nuclear deal by April 30?",
            null,
            "us-iran-nuclear-deal-by-april-30",
            "BUY",
            "No",
            "0.6693",
            "74.7",
            "49.99971",
            "\u672a\u914d\u7f6e\u8ddf\u5355\u89c4\u5219\uff0c\u5df2\u8df3\u8fc7\u8ddf\u5355",
            "FOLLOW_RULE_MISSING",
            "de",
            "0x1277000000004143",
            Locale.SIMPLIFIED_CHINESE,
            "\u672a\u77e5\u8d26\u6237",
            "\u8ba1\u7b97\u5931\u8d25"
        ) as Map<String, String>

        val accountInfo = vars["account_name"].orEmpty()
        assertTrue(accountInfo.contains("de"))
        assertTrue(accountInfo.contains("0x1277...4143"))
    }

    private fun telegramConfig(
        id: Long,
        name: String,
        monitorModeEnabled: Boolean,
        marketBettingQueryEnabled: Boolean = false
    ) = NotificationConfigDto(
        id = id,
        type = "telegram",
        name = name,
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
