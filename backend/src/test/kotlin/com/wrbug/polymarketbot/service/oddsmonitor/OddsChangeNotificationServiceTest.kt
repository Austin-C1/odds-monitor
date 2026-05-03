package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.entity.OddsMarket
import com.wrbug.polymarketbot.entity.OddsPlatformMatch
import com.wrbug.polymarketbot.entity.OddsSnapshot
import com.wrbug.polymarketbot.repository.OddsAlertRecordRepository
import com.wrbug.polymarketbot.repository.OddsMarketRepository
import com.wrbug.polymarketbot.repository.OddsSnapshotRepository
import com.wrbug.polymarketbot.service.system.NotificationConfigService
import com.wrbug.polymarketbot.service.system.TelegramNotificationService
import com.wrbug.polymarketbot.dto.NotificationConfigData
import com.wrbug.polymarketbot.dto.NotificationConfigDto
import com.wrbug.polymarketbot.dto.TelegramConfigData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal

class OddsChangeNotificationServiceTest {
    @Test
    fun `detects only real odds value changes`() {
        assertFalse(hasOddsChanged(BigDecimal("1.9300"), BigDecimal("1.93")))
        assertTrue(hasOddsChanged(BigDecimal("1.93"), BigDecimal("1.95")))
    }

    @Test
    fun `builds odds change alert message from collected snapshot data`() {
        val match = OddsPlatformMatch(
            id = 10,
            sourceKey = "pinnacle",
            sourceMatchId = "m1",
            rawLeagueName = "Premier League",
            rawHomeTeam = "Arsenal",
            rawAwayTeam = "Chelsea",
            rawStartTime = 1893456000000L
        )
        val market = OddsMarket(
            id = 20,
            matchId = 10,
            sourceKey = "pinnacle",
            marketType = "handicap",
            lineValue = "-0.5",
            selectionName = "home"
        )

        val message = buildOddsChangeAlertMessage(match, market, BigDecimal("1.93"), BigDecimal("1.95"))

        assertEquals("赔率变动：Arsenal vs Chelsea", buildOddsChangeAlertTitle(match))
        assertTrue(message.contains("Premier League"))
        assertTrue(message.contains("Arsenal vs Chelsea"))
        assertTrue(message.contains("handicap home -0.5"))
        assertTrue(message.contains("1.93 -> 1.95"))
    }

    @Test
    fun `builds merged odds change message for same match and market`() {
        val message = buildMergedOddsChangeAlertMessage(
            matchName = "东京 vs 川崎前锋",
            leagueName = "日本J1",
            marketLabel = "让球 主队 0.5",
            changes = listOf(
                OddsChangeNotificationItem("pinnacle", BigDecimal("1.06"), BigDecimal("1.09")),
                OddsChangeNotificationItem("crown", BigDecimal("0.90"), BigDecimal("0.94"))
            ),
            expectedSources = listOf("pinnacle", "crown", "polymarket"),
            timestampText = "2026-05-03 15:07"
        )

        assertTrue(message.contains("赔率变动：东京 vs 川崎前锋"))
        assertTrue(message.contains("联赛：日本J1"))
        assertTrue(message.contains("盘口：让球 主队 0.5"))
        assertTrue(message.contains("平博：1.06 -> 1.09"))
        assertTrue(message.contains("皇冠：0.90 -> 0.94"))
        assertTrue(message.contains("Polymarket：无对应盘口"))
        assertTrue(message.contains("筛选：动水通过 / 合水通过"))
        assertTrue(message.contains("时间：2026-05-03 15:07"))
    }

    @Test
    fun `suppresses handicap alert when combined water is below configured limit`() {
        val alertRepository = mock(OddsAlertRecordRepository::class.java)
        val telegramNotificationService = mock(TelegramNotificationService::class.java)
        val notificationConfigService = mock(NotificationConfigService::class.java)
        val marketRepository = mock(OddsMarketRepository::class.java)
        val snapshotRepository = mock(OddsSnapshotRepository::class.java)
        val service = OddsChangeNotificationService(
            alertRepository,
            telegramNotificationService,
            notificationConfigService,
            marketRepository,
            snapshotRepository
        )
        val match = OddsPlatformMatch(
            id = 10,
            sourceKey = "crown",
            sourceMatchId = "m1",
            rawLeagueName = "Premier League",
            rawHomeTeam = "Arsenal",
            rawAwayTeam = "Chelsea"
        )
        val market = OddsMarket(
            id = 20,
            matchId = 10,
            sourceKey = "crown",
            marketType = "handicap",
            lineValue = "-0.5",
            selectionName = "home"
        )
        val pairedMarket = market.copy(id = 21, selectionName = "away")

        runBlocking {
            `when`(notificationConfigService.getEnabledConfigsByType("telegram")).thenReturn(
                listOf(telegramMonitorConfig(handicapCombinedWaterMin = "1.88"))
            )
        }
        `when`(
            marketRepository.findByMatchIdAndSourceKeyAndMarketTypeAndLineValueAndSelectionName(
                10,
                "crown",
                "handicap",
                "-0.5",
                "away"
            )
        ).thenReturn(pairedMarket)
        `when`(snapshotRepository.findTop1ByMarketIdOrderByCapturedAtDesc(21)).thenReturn(
            OddsSnapshot(marketId = 21, sourceKey = "crown", oddsValue = BigDecimal("0.95"))
        )

        service.notifyIfChanged(match, market, BigDecimal("0.88"), BigDecimal("0.90"))

        verify(alertRepository, never()).save(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `does not suppress handicap alert when combined water reaches configured limit`() {
        val configs = listOf(telegramMonitorConfig(handicapCombinedWaterMin = "1.88"))

        assertFalse(
            shouldSuppressOddsChangeByCombinedWater(
                marketType = "handicap",
                currentOdds = BigDecimal("0.93"),
                pairedOdds = BigDecimal("0.95"),
                configs = configs
            )
        )
    }

    @Test
    fun `suppresses odds alert when movement is below configured limit`() {
        val configs = listOf(telegramMonitorConfig(handicapOddsMoveMin = "0.05"))

        assertTrue(
            shouldSuppressOddsChangeByMove(
                marketType = "handicap",
                previousOdds = BigDecimal("0.88"),
                currentOdds = BigDecimal("0.92"),
                configs = configs
            )
        )
    }

    @Test
    fun `does not suppress odds alert when movement reaches configured limit`() {
        val configs = listOf(telegramMonitorConfig(handicapOddsMoveMin = "0.05"))

        assertFalse(
            shouldSuppressOddsChangeByMove(
                marketType = "handicap",
                previousOdds = BigDecimal("0.88"),
                currentOdds = BigDecimal("0.93"),
                configs = configs
            )
        )
    }

    private fun telegramMonitorConfig(
        handicapCombinedWaterMin: String? = null,
        totalCombinedWaterMin: String? = null,
        handicapOddsMoveMin: String? = null,
        totalOddsMoveMin: String? = null,
        moneylineOddsMoveMin: String? = null
    ) = NotificationConfigDto(
        id = 1,
        type = "telegram",
        name = "monitor",
        enabled = true,
        config = NotificationConfigData.Telegram(
            TelegramConfigData(
                botToken = "token",
                chatIds = listOf("chat"),
                monitorModeEnabled = true,
                handicapCombinedWaterMin = handicapCombinedWaterMin,
                totalCombinedWaterMin = totalCombinedWaterMin,
                handicapOddsMoveMin = handicapOddsMoveMin,
                totalOddsMoveMin = totalOddsMoveMin,
                moneylineOddsMoveMin = moneylineOddsMoveMin
            )
        )
    )
}
