package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.dto.NotificationConfigData
import com.wrbug.polymarketbot.dto.NotificationConfigDto
import com.wrbug.polymarketbot.dto.TelegramConfigData
import com.wrbug.polymarketbot.entity.OddsAlertRecord
import com.wrbug.polymarketbot.entity.OddsMarket
import com.wrbug.polymarketbot.entity.OddsMatch
import com.wrbug.polymarketbot.entity.OddsPlatformMatch
import com.wrbug.polymarketbot.repository.OddsAlertRecordRepository
import com.wrbug.polymarketbot.repository.OddsMarketRepository
import com.wrbug.polymarketbot.repository.OddsMatchRepository
import com.wrbug.polymarketbot.repository.OddsSnapshotRepository
import com.wrbug.polymarketbot.repository.SystemConfigRepository
import com.wrbug.polymarketbot.service.system.NotificationConfigService
import com.wrbug.polymarketbot.service.system.TelegramNotificationService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.math.BigDecimal
import java.util.Optional

class OddsChangeNotificationServiceTest {
    @Test
    fun `detects only real odds value changes`() {
        assertFalse(hasOddsChanged(BigDecimal("1.9300"), BigDecimal("1.93")))
        assertTrue(hasOddsChanged(BigDecimal("1.93"), BigDecimal("1.95")))
    }

    @Test
    fun `builds readable merged odds change message`() {
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
    fun `formats pinnacle handicap and total odds as asian water in merged alerts`() {
        val message = buildMergedOddsChangeAlertMessage(
            matchName = "尼科西亚希腊人竞技 vs AEK拉纳卡",
            leagueName = "塞浦路斯 - 甲级联赛",
            markets = listOf(
                OddsChangeNotificationMarketItem(
                    marketType = "handicap",
                    marketLabel = "让球 客队 0/0.5",
                    changes = listOf(OddsChangeNotificationItem("pinnacle", BigDecimal("1.591"), BigDecimal("2.18")))
                ),
                OddsChangeNotificationMarketItem(
                    marketType = "total",
                    marketLabel = "大小球 大球 2.5",
                    changes = listOf(OddsChangeNotificationItem("pinnacle", BigDecimal("1.833"), BigDecimal("2.02")))
                )
            ),
            expectedSources = listOf("pinnacle", "crown", "polymarket"),
            timestampText = "2026-05-03 13:21:28"
        )

        assertTrue(message.contains("平博：0.591 -> 1.18"))
        assertTrue(message.contains("平博：0.833 -> 1.02"))
        assertFalse(message.contains("平博：1.591 -> 2.18"))
    }

    @Test
    fun `movement filter suppresses odds alert below configured limit`() {
        val configs = listOf(telegramMonitorConfig(handicapOddsMoveMin = "0.08"))

        assertTrue(
            shouldSuppressOddsChangeByMove(
                marketType = "handicap",
                previousOdds = BigDecimal("0.88"),
                currentOdds = BigDecimal("0.95"),
                configs = configs
            )
        )
        assertFalse(
            shouldSuppressOddsChangeByMove(
                marketType = "handicap",
                previousOdds = BigDecimal("0.88"),
                currentOdds = BigDecimal("0.96"),
                configs = configs
            )
        )
    }

    @Test
    fun `combined water filter suppresses odds alert below configured limit`() {
        val configs = listOf(telegramMonitorConfig(handicapCombinedWaterMin = "1.88"))

        assertTrue(
            shouldSuppressOddsChangeByCombinedWater(
                marketType = "handicap",
                currentOdds = BigDecimal("0.90"),
                pairedOdds = BigDecimal("0.95"),
                configs = configs
            )
        )
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
    fun `live only monitor suppresses prematch odds changes`() {
        val alertRepository = mock(OddsAlertRecordRepository::class.java)
        val telegramNotificationService = mock(TelegramNotificationService::class.java)
        val notificationConfigService = mock(NotificationConfigService::class.java)
        val marketRepository = mock(OddsMarketRepository::class.java)
        val snapshotRepository = mock(OddsSnapshotRepository::class.java)
        val matchRepository = mock(OddsMatchRepository::class.java)
        val now = System.currentTimeMillis()
        val service = OddsChangeNotificationService(
            alertRepository,
            telegramNotificationService,
            notificationConfigService,
            marketRepository,
            snapshotRepository,
            matchRepository
        )

        runBlocking {
            `when`(notificationConfigService.getEnabledConfigsByType("telegram")).thenReturn(
                listOf(telegramMonitorConfig(liveOnlyModeEnabled = true, handicapOddsMoveMin = "0.08"))
            )
        }
        `when`(matchRepository.findById(100)).thenReturn(
            Optional.of(OddsMatch(id = 100, startTime = now + 3_600_000, status = "scheduled"))
        )

        service.notifyIfChanged(platformMatch(startTime = now + 3_600_000), oddsMarket(), BigDecimal("0.88"), BigDecimal("0.98"))
        Thread.sleep(1_800)

        verify(alertRepository, never()).save(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `prematch monitor suppresses live odds changes`() {
        val alertRepository = mock(OddsAlertRecordRepository::class.java)
        val telegramNotificationService = mock(TelegramNotificationService::class.java)
        val notificationConfigService = mock(NotificationConfigService::class.java)
        val marketRepository = mock(OddsMarketRepository::class.java)
        val snapshotRepository = mock(OddsSnapshotRepository::class.java)
        val matchRepository = mock(OddsMatchRepository::class.java)
        val now = System.currentTimeMillis()
        val service = OddsChangeNotificationService(
            alertRepository,
            telegramNotificationService,
            notificationConfigService,
            marketRepository,
            snapshotRepository,
            matchRepository
        )

        runBlocking {
            `when`(notificationConfigService.getEnabledConfigsByType("telegram")).thenReturn(
                listOf(telegramMonitorConfig(liveOnlyModeEnabled = false, handicapOddsMoveMin = "0.08"))
            )
        }
        `when`(matchRepository.findById(100)).thenReturn(
            Optional.of(OddsMatch(id = 100, startTime = now - 60_000, status = "live"))
        )

        service.notifyIfChanged(platformMatch(startTime = now - 60_000), oddsMarket(), BigDecimal("0.88"), BigDecimal("0.98"))
        Thread.sleep(1_800)

        verify(alertRepository, never()).save(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `league filter suppresses telegram odds changes outside selected leagues`() {
        val alertRepository = mock(OddsAlertRecordRepository::class.java)
        val telegramNotificationService = mock(TelegramNotificationService::class.java)
        val notificationConfigService = mock(NotificationConfigService::class.java)
        val marketRepository = mock(OddsMarketRepository::class.java)
        val snapshotRepository = mock(OddsSnapshotRepository::class.java)
        val leagueConfigRepository = mock(SystemConfigRepository::class.java)
        val leagueFilterService = OddsLeagueFilterService(leagueConfigRepository)
        val service = OddsChangeNotificationService(
            alertRepository,
            telegramNotificationService,
            notificationConfigService,
            marketRepository,
            snapshotRepository,
            leagueFilterService = leagueFilterService
        )

        `when`(leagueConfigRepository.findByConfigKey(OddsLeagueFilterService.CONFIG_KEY)).thenReturn(
            com.wrbug.polymarketbot.entity.SystemConfig(
                configKey = OddsLeagueFilterService.CONFIG_KEY,
                configValue = """["日本J1百年构想联赛"]"""
            )
        )

        service.notifyIfChanged(
            platformMatch(rawLeagueName = "英格兰 - 北部超级联赛"),
            oddsMarket(),
            BigDecimal("0.88"),
            BigDecimal("0.98")
        )
        Thread.sleep(1_800)

        verify(alertRepository, never()).save(org.mockito.ArgumentMatchers.any())
        runBlocking {
            verify(notificationConfigService, never()).getEnabledConfigsByType("telegram")
        }
    }

    @Test
    fun `records one merged alert when multiple platforms change same match and market`() {
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

        runBlocking {
            `when`(notificationConfigService.getEnabledConfigsByType("telegram")).thenReturn(
                listOf(telegramMonitorConfig(handicapOddsMoveMin = "0.08"))
            )
        }

        service.notifyIfChanged(platformMatch(sourceKey = "pinnacle"), oddsMarket(sourceKey = "pinnacle"), BigDecimal("1.91"), BigDecimal("2.03"))
        service.notifyIfChanged(platformMatch(sourceKey = "crown"), oddsMarket(sourceKey = "crown"), BigDecimal("0.90"), BigDecimal("0.99"))
        Thread.sleep(1_800)

        val captor = ArgumentCaptor.forClass(OddsAlertRecord::class.java)
        verify(alertRepository, times(1)).save(captor.capture())
        assertEquals(1, Regex("盘口：").findAll(captor.value.message).count())
        assertTrue(captor.value.message.contains("平博：0.91 -> 1.03"))
        assertTrue(captor.value.message.contains("皇冠：0.90 -> 0.99"))
        assertTrue(captor.value.message.contains("Polymarket：无对应盘口"))
    }

    @Test
    fun `uses standard match name when platform special bet names are generic`() {
        val alertRepository = mock(OddsAlertRecordRepository::class.java)
        val telegramNotificationService = mock(TelegramNotificationService::class.java)
        val notificationConfigService = mock(NotificationConfigService::class.java)
        val marketRepository = mock(OddsMarketRepository::class.java)
        val snapshotRepository = mock(OddsSnapshotRepository::class.java)
        val matchRepository = mock(OddsMatchRepository::class.java)
        val service = OddsChangeNotificationService(
            alertRepository,
            telegramNotificationService,
            notificationConfigService,
            marketRepository,
            snapshotRepository,
            matchRepository
        )

        runBlocking {
            `when`(notificationConfigService.getEnabledConfigsByType("telegram")).thenReturn(
                listOf(telegramMonitorConfig(liveOnlyModeEnabled = true, handicapOddsMoveMin = "0.08"))
            )
        }
        `when`(matchRepository.findById(100)).thenReturn(
            Optional.of(
                OddsMatch(
                    id = 100,
                    leagueName = "意大利甲组联赛",
                    homeTeam = "国际米兰",
                    awayTeam = "尤文图斯",
                    status = "live"
                )
            )
        )

        service.notifyIfChanged(
            platformMatch(
                rawLeagueName = "意大利甲组联赛-特别投注",
                rawHomeTeam = "主场",
                rawAwayTeam = "客场"
            ),
            oddsMarket(),
            BigDecimal("0.88"),
            BigDecimal("0.96")
        )
        Thread.sleep(1_800)

        val captor = ArgumentCaptor.forClass(OddsAlertRecord::class.java)
        verify(alertRepository, times(1)).save(captor.capture())
        assertTrue(captor.value.message.contains("赔率变动：国际米兰 vs 尤文图斯"))
        assertFalse(captor.value.message.contains("赔率变动：主场 vs 客场"))
        assertTrue(captor.value.message.contains("联赛：意大利甲组联赛"))
    }

    private fun telegramMonitorConfig(
        monitorModeEnabled: Boolean = true,
        liveOnlyModeEnabled: Boolean = false,
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
                monitorModeEnabled = monitorModeEnabled,
                liveOnlyModeEnabled = liveOnlyModeEnabled,
                handicapCombinedWaterMin = handicapCombinedWaterMin,
                totalCombinedWaterMin = totalCombinedWaterMin,
                handicapOddsMoveMin = handicapOddsMoveMin,
                totalOddsMoveMin = totalOddsMoveMin,
                moneylineOddsMoveMin = moneylineOddsMoveMin
            )
        )
    )

    private fun platformMatch(
        sourceKey: String = "crown",
        startTime: Long? = null,
        rawLeagueName: String = "日本J1",
        rawHomeTeam: String = "东京",
        rawAwayTeam: String = "川崎前锋"
    ) = OddsPlatformMatch(
        id = 10,
        sourceKey = sourceKey,
        sourceMatchId = "m1",
        rawLeagueName = rawLeagueName,
        rawHomeTeam = rawHomeTeam,
        rawAwayTeam = rawAwayTeam,
        rawStartTime = startTime
    )

    private fun oddsMarket(sourceKey: String = "crown") = OddsMarket(
        id = 20,
        matchId = 100,
        sourceKey = sourceKey,
        marketType = "handicap",
        lineValue = "0",
        selectionName = "away"
    )
}
