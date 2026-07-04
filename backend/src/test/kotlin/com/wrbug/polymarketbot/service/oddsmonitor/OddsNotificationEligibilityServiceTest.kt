package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.dto.NotificationConfigData
import com.wrbug.polymarketbot.dto.NotificationConfigDto
import com.wrbug.polymarketbot.dto.TelegramConfigData
import com.wrbug.polymarketbot.entity.OddsMarket
import com.wrbug.polymarketbot.entity.OddsPlatformMatch
import com.wrbug.polymarketbot.entity.OddsSnapshot
import com.wrbug.polymarketbot.repository.OddsMarketRepository
import com.wrbug.polymarketbot.repository.OddsSnapshotRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.math.BigDecimal

class OddsNotificationEligibilityServiceTest {
    private val marketRepository = mock(OddsMarketRepository::class.java)
    private val snapshotRepository = mock(OddsSnapshotRepository::class.java)
    private val service = OddsNotificationEligibilityService(marketRepository, snapshotRepository)

    @Test
    fun `source and football ignore rules reject unsupported matches`() {
        assertFalse(service.shouldProcessSource(platformMatch(sourceKey = "legacy"), listOf("crown")))
        assertTrue(service.shouldProcessSource(platformMatch(sourceKey = "crown"), listOf("crown")))
        assertFalse(service.shouldProcessFootballMatch(platformMatch(rawLeagueName = "意大利甲组联赛-特别投注")))
        assertTrue(service.shouldProcessFootballMatch(platformMatch(rawLeagueName = "日本J1")))
    }

    @Test
    fun `phase and league filters keep only eligible configs`() {
        val now = 1_000_000L
        val prematch = telegramConfig(id = 1, liveOnlyModeEnabled = false, prematchWindowMinutes = 30)
        val live = telegramConfig(id = 2, liveOnlyModeEnabled = true)
        val testMode = telegramConfig(id = 3, testModeEnabled = true, prematchWindowMinutes = 1)

        val phaseConfigs = service.configsQualifiedByPhase(
            configs = listOf(prematch, live, testMode),
            matchPhase = OddsMonitorMatchPhase.PREMATCH,
            startTime = now + 20 * 60_000,
            now = now,
            liveObservationMinutes = null
        )

        assertEquals(listOf(1L, 3L), phaseConfigs.map { it.id })
        assertEquals(listOf(3L), service.configsQualifiedByLeague(phaseConfigs, leagueMatched = false).map { it.id })
    }

    @Test
    fun `odds move and combined water thresholds filter configs`() {
        val config = telegramConfig(
            id = 1,
            handicapOddsMoveMin = "0.08",
            handicapCombinedWaterMin = "1.80"
        )
        val market = oddsMarket(selectionName = "home")
        val pairMarket = oddsMarket(id = 20, selectionName = "away")
        `when`(
            marketRepository.findTopByMatchIdAndSourceKeyAndMarketTypeAndLineValueAndSelectionNameOrderByUpdatedAtDesc(
                matchId = 100,
                sourceKey = "crown",
                marketType = "handicap",
                lineValue = "0.5",
                selectionName = "away"
            )
        ).thenReturn(pairMarket)

        `when`(snapshotRepository.findTop1ByMarketIdOrderByCapturedAtDesc(20))
            .thenReturn(OddsSnapshot(marketId = 20, oddsValue = BigDecimal("0.90")))

        assertTrue(
            service.configsQualifiedBySelectedLeagueRules(
                market = market,
                previousOdds = BigDecimal("0.90"),
                currentOdds = BigDecimal("0.95"),
                configs = listOf(config)
            ).isEmpty()
        )
        assertEquals(
            listOf(1L),
            service.configsQualifiedBySelectedLeagueRules(
                market = market,
                previousOdds = BigDecimal("0.90"),
                currentOdds = BigDecimal("1.00"),
                configs = listOf(config)
            ).map { it.id }
        )
    }

    private fun platformMatch(
        sourceKey: String = "crown",
        rawLeagueName: String = "日本J1"
    ) = OddsPlatformMatch(
        sourceKey = sourceKey,
        rawLeagueName = rawLeagueName,
        rawHomeTeam = "东京",
        rawAwayTeam = "川崎前锋",
        rawStartTime = 2_000_000L
    )

    private fun oddsMarket(
        id: Long? = 10,
        selectionName: String
    ) = OddsMarket(
        id = id,
        matchId = 100,
        sourceKey = "crown",
        marketType = "handicap",
        lineValue = "0.5",
        selectionName = selectionName
    )

    private fun telegramConfig(
        id: Long,
        liveOnlyModeEnabled: Boolean = false,
        testModeEnabled: Boolean = false,
        prematchWindowMinutes: Int? = null,
        handicapCombinedWaterMin: String? = null,
        handicapOddsMoveMin: String? = null
    ) = NotificationConfigDto(
        id = id,
        type = "telegram",
        name = "telegram-$id",
        enabled = true,
        config = NotificationConfigData.Telegram(
            TelegramConfigData(
                botToken = "token",
                chatIds = listOf("1"),
                monitorModeEnabled = true,
                liveOnlyModeEnabled = liveOnlyModeEnabled,
                testModeEnabled = testModeEnabled,
                prematchWindowMinutes = prematchWindowMinutes,
                handicapCombinedWaterMin = handicapCombinedWaterMin,
                handicapOddsMoveMin = handicapOddsMoveMin
            )
        )
    )
}
