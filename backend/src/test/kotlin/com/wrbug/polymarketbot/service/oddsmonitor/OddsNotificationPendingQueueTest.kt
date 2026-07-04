package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.dto.NotificationConfigData
import com.wrbug.polymarketbot.dto.NotificationConfigDto
import com.wrbug.polymarketbot.dto.TelegramConfigData
import com.wrbug.polymarketbot.entity.OddsMarket
import com.wrbug.polymarketbot.entity.OddsPlatformMatch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class OddsNotificationPendingQueueTest {
    @Test
    fun `same match market updates one pending change`() {
        val queue = OddsNotificationPendingQueue()
        val match = platformMatch()
        val market = oddsMarket()
        val config = telegramConfig()

        val key = queue.enqueueMergedNotification(
            match = match,
            standardMatch = null,
            matchName = "东京 vs 川崎前锋",
            leagueName = "日本J1",
            matchId = 100,
            market = market,
            previousOdds = BigDecimal("0.90"),
            currentOdds = BigDecimal("0.95"),
            configs = listOf(config)
        )
        val updatedKey = queue.enqueueMergedNotification(
            match = match,
            standardMatch = null,
            matchName = "东京 vs 川崎前锋",
            leagueName = "日本J1",
            matchId = 100,
            market = market,
            previousOdds = BigDecimal("0.90"),
            currentOdds = BigDecimal("1.00"),
            configs = listOf(config)
        )

        assertEquals(key, updatedKey)
        val pending = queue.remove(key)!!
        val change = pending.markets.values.single().changes["crown"]!!
        assertEquals(BigDecimal("0.90"), change.previousOdds)
        assertEquals(BigDecimal("1.00"), change.currentOdds)
    }

    @Test
    fun `empty changes are removed from pending queue`() {
        val queue = OddsNotificationPendingQueue()
        val key = queue.enqueueMergedNotification(
            match = platformMatch(),
            standardMatch = null,
            matchName = "东京 vs 川崎前锋",
            leagueName = "日本J1",
            matchId = 100,
            market = oddsMarket(),
            previousOdds = BigDecimal("0.90"),
            currentOdds = BigDecimal("0.91"),
            configs = listOf(telegramConfig(handicapOddsMoveMin = "0.08"))
        )

        assertNull(queue.remove(key))
    }

    private fun platformMatch() = OddsPlatformMatch(
        sourceKey = "crown",
        rawLeagueName = "日本J1",
        rawHomeTeam = "东京",
        rawAwayTeam = "川崎前锋",
        rawStartTime = 2_000_000L
    )

    private fun oddsMarket() = OddsMarket(
        id = 10,
        matchId = 100,
        sourceKey = "crown",
        marketType = "handicap",
        lineValue = "0.5",
        selectionName = "home"
    )

    private fun telegramConfig(
        handicapOddsMoveMin: String? = null
    ) = NotificationConfigDto(
        id = 1,
        type = "telegram",
        name = "telegram",
        enabled = true,
        config = NotificationConfigData.Telegram(
            TelegramConfigData(
                botToken = "token",
                chatIds = listOf("1"),
                monitorModeEnabled = true,
                handicapOddsMoveMin = handicapOddsMoveMin
            )
        )
    )
}
