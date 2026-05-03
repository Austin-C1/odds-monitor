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
import com.wrbug.polymarketbot.entity.OddsAlertRecord
import com.wrbug.polymarketbot.entity.OddsMatch
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
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Optional

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
        assertTrue(message.contains("0.93 -> 0.95"))
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
    fun `builds one merged message for multiple markets in same match`() {
        val message = buildMergedOddsChangeAlertMessage(
            matchName = "东京 vs 川崎前锋",
            leagueName = "日本J1",
            markets = listOf(
                OddsChangeNotificationMarketItem(
                    marketType = "handicap",
                    marketLabel = "让球 主队 0.5",
                    changes = listOf(
                        OddsChangeNotificationItem("pinnacle", BigDecimal("1.06"), BigDecimal("1.14")),
                        OddsChangeNotificationItem("crown", BigDecimal("0.90"), BigDecimal("0.99"))
                    )
                ),
                OddsChangeNotificationMarketItem(
                    marketType = "total",
                    marketLabel = "大小球 大球 2.5",
                    changes = listOf(
                        OddsChangeNotificationItem("crown", BigDecimal("0.88"), BigDecimal("0.98"))
                    )
                )
            ),
            expectedSources = listOf("pinnacle", "crown", "polymarket"),
            timestampText = "2026-05-03 15:07"
        )

        assertEquals(1, Regex("赔率变动：").findAll(message).count())
        assertTrue(message.contains("让球 主队 0.5"))
        assertTrue(message.contains("大小球 大球 2.5"))
        assertTrue(message.contains("平博：0.06 -> 0.14"))
        assertTrue(message.contains("皇冠：0.90 -> 0.99"))
        assertTrue(message.contains("皇冠：0.88 -> 0.98"))
        assertTrue(message.contains("Polymarket：无对应盘口"))
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
                    changes = listOf(
                        OddsChangeNotificationItem("pinnacle", BigDecimal("1.591"), BigDecimal("2.18"))
                    )
                ),
                OddsChangeNotificationMarketItem(
                    marketType = "total",
                    marketLabel = "大小球 大球 2.5",
                    changes = listOf(
                        OddsChangeNotificationItem("pinnacle", BigDecimal("1.833"), BigDecimal("2.02"))
                    )
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
    fun `records one alert when multiple markets change in same match window`() {
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
            rawLeagueName = "日本J1",
            rawHomeTeam = "东京",
            rawAwayTeam = "川崎前锋"
        )
        val handicap = OddsMarket(
            id = 20,
            matchId = 100,
            sourceKey = "crown",
            marketType = "handicap",
            lineValue = "0.5",
            selectionName = "home"
        )
        val total = OddsMarket(
            id = 21,
            matchId = 100,
            sourceKey = "crown",
            marketType = "total",
            lineValue = "2.5",
            selectionName = "over"
        )

        runBlocking {
            `when`(notificationConfigService.getEnabledConfigsByType("telegram")).thenReturn(
                listOf(telegramMonitorConfig(handicapOddsMoveMin = "0.08", totalOddsMoveMin = "0.08"))
            )
        }

        service.notifyIfChanged(match, handicap, BigDecimal("0.90"), BigDecimal("0.99"))
        service.notifyIfChanged(match, total, BigDecimal("0.88"), BigDecimal("0.98"))
        Thread.sleep(1_800)

        val captor = ArgumentCaptor.forClass(OddsAlertRecord::class.java)
        verify(alertRepository, times(1)).save(captor.capture())
        assertTrue(captor.value.message.contains("让球 主队 0.5"))
        assertTrue(captor.value.message.contains("大小球 大球 2.5"))
        assertTrue(captor.value.message.contains("皇冠：0.90 -> 0.99"))
        assertTrue(captor.value.message.contains("皇冠：0.88 -> 0.98"))
    }

    @Test
    fun `records one market section when multiple platforms change same match and market`() {
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
            sourceKey = "pinnacle",
            sourceMatchId = "m1",
            rawLeagueName = "英格兰 - 超级联赛",
            rawHomeTeam = "曼联",
            rawAwayTeam = "利物浦"
        )
        val pinnacleMarket = OddsMarket(
            id = 20,
            matchId = 100,
            sourceKey = "pinnacle",
            marketType = "handicap",
            lineValue = "0",
            selectionName = "away"
        )
        val crownMarket = pinnacleMarket.copy(id = 21, sourceKey = "crown")

        runBlocking {
            `when`(notificationConfigService.getEnabledConfigsByType("telegram")).thenReturn(
                listOf(telegramMonitorConfig(handicapOddsMoveMin = "0.08"))
            )
        }

        service.notifyIfChanged(match, pinnacleMarket, BigDecimal("1.91"), BigDecimal("2.03"))
        service.notifyIfChanged(match, crownMarket, BigDecimal("0.90"), BigDecimal("0.99"))
        Thread.sleep(1_800)

        val captor = ArgumentCaptor.forClass(OddsAlertRecord::class.java)
        verify(alertRepository, times(1)).save(captor.capture())
        assertEquals(1, Regex("盘口：").findAll(captor.value.message).count())
        assertTrue(captor.value.message.contains("平博：0.91 -> 1.03"))
        assertTrue(captor.value.message.contains("皇冠：0.90 -> 0.99"))
        assertTrue(captor.value.message.contains("Polymarket：无对应盘口"))
    }

    @Test
    fun `suppresses pinnacle combined water using asian water`() {
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
            sourceKey = "pinnacle",
            sourceMatchId = "m1",
            rawLeagueName = "塞浦路斯 - 甲级联赛",
            rawHomeTeam = "尼科西亚希腊人竞技",
            rawAwayTeam = "AEK拉纳卡"
        )
        val market = OddsMarket(
            id = 20,
            matchId = 100,
            sourceKey = "pinnacle",
            marketType = "handicap",
            lineValue = "0/0.5",
            selectionName = "away"
        )
        val pairMarket = market.copy(id = 21, selectionName = "home")

        runBlocking {
            `when`(notificationConfigService.getEnabledConfigsByType("telegram")).thenReturn(
                listOf(telegramMonitorConfig(handicapCombinedWaterMin = "1.88", handicapOddsMoveMin = "0.08"))
            )
        }
        `when`(
            marketRepository.findByMatchIdAndSourceKeyAndMarketTypeAndLineValueAndSelectionName(
                100,
                "pinnacle",
                "handicap",
                "0/0.5",
                "home"
            )
        ).thenReturn(pairMarket)
        `when`(snapshotRepository.findTop1ByMarketIdOrderByCapturedAtDesc(21)).thenReturn(
            OddsSnapshot(marketId = 21, sourceKey = "pinnacle", oddsValue = BigDecimal("1.70"))
        )

        service.notifyIfChanged(match, market, BigDecimal("1.591"), BigDecimal("2.00"))

        verify(alertRepository, never()).save(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `does not record alert when source returns to original odds in same merge window`() {
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
            sourceKey = "pinnacle",
            sourceMatchId = "m1",
            rawLeagueName = "美国 - USL锦标赛",
            rawHomeTeam = "蒙特雷湾",
            rawAwayTeam = "塔尔萨足球俱乐部"
        )
        val market = OddsMarket(
            id = 20,
            matchId = 100,
            sourceKey = "pinnacle",
            marketType = "handicap",
            lineValue = "0/0.5",
            selectionName = "home"
        )

        runBlocking {
            `when`(notificationConfigService.getEnabledConfigsByType("telegram")).thenReturn(
                listOf(telegramMonitorConfig(handicapOddsMoveMin = "0.08"))
            )
        }

        service.notifyIfChanged(match, market, BigDecimal("1.763"), BigDecimal("2.08"))
        service.notifyIfChanged(match, market, BigDecimal("2.08"), BigDecimal("1.763"))
        Thread.sleep(1_800)

        verify(alertRepository, never()).save(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `does not record alert when final merged movement is below configured limit`() {
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
            sourceKey = "pinnacle",
            sourceMatchId = "m1",
            rawLeagueName = "瑞典 - 甲级联赛",
            rawHomeTeam = "赫尔辛堡",
            rawAwayTeam = "布拉吉"
        )
        val market = OddsMarket(
            id = 20,
            matchId = 100,
            sourceKey = "pinnacle",
            marketType = "handicap",
            lineValue = "0/0.5",
            selectionName = "home"
        )

        runBlocking {
            `when`(notificationConfigService.getEnabledConfigsByType("telegram")).thenReturn(
                listOf(telegramMonitorConfig(handicapOddsMoveMin = "0.08"))
            )
        }

        service.notifyIfChanged(match, market, BigDecimal("1.60"), BigDecimal("1.80"))
        service.notifyIfChanged(match, market, BigDecimal("1.80"), BigDecimal("1.65"))
        Thread.sleep(1_800)

        verify(alertRepository, never()).save(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `repairs mojibake in odds change alert messages`() {
        val match = OddsPlatformMatch(
            id = 10,
            sourceKey = "crown",
            sourceMatchId = "m1",
            rawLeagueName = mojibake("英超"),
            rawHomeTeam = mojibake("平博"),
            rawAwayTeam = mojibake("皇冠")
        )
        val market = OddsMarket(
            id = 20,
            matchId = 10,
            sourceKey = "crown",
            marketType = "handicap",
            lineValue = "-0.5",
            selectionName = "home"
        )

        val singleMessage = buildOddsChangeAlertMessage(match, market, BigDecimal("0.88"), BigDecimal("0.96"))
        val mergedMessage = buildMergedOddsChangeAlertMessage(
            matchName = "${mojibake("平博")} vs ${mojibake("皇冠")}",
            leagueName = mojibake("英超"),
            marketLabel = "让球 主队 0.5",
            changes = listOf(OddsChangeNotificationItem("crown", BigDecimal("0.88"), BigDecimal("0.96"))),
            expectedSources = listOf("crown"),
            timestampText = "2026-05-03 15:07"
        )

        assertEquals("赔率变动：平博 vs 皇冠", buildOddsChangeAlertTitle(match))
        assertTrue(singleMessage.contains("联赛: 英超"))
        assertTrue(singleMessage.contains("比赛: 平博 vs 皇冠"))
        assertTrue(mergedMessage.contains("赔率变动：平博 vs 皇冠"))
        assertTrue(mergedMessage.contains("联赛：英超"))
        assertFalse(singleMessage.contains(mojibake("平博")))
        assertFalse(mergedMessage.contains(mojibake("英超")))
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

    @Test
    fun `does not record odds alert when monitor mode is disabled`() {
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

        runBlocking {
            `when`(notificationConfigService.getEnabledConfigsByType("telegram")).thenReturn(
                listOf(telegramMonitorConfig(monitorModeEnabled = false, handicapOddsMoveMin = "0.08"))
            )
        }

        service.notifyIfChanged(match, market, BigDecimal("0.88"), BigDecimal("0.98"))
        Thread.sleep(1_800)

        verify(alertRepository, never()).save(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `live only monitor suppresses prematch odds changes`() {
        val alertRepository = mock(OddsAlertRecordRepository::class.java)
        val telegramNotificationService = mock(TelegramNotificationService::class.java)
        val notificationConfigService = mock(NotificationConfigService::class.java)
        val marketRepository = mock(OddsMarketRepository::class.java)
        val snapshotRepository = mock(OddsSnapshotRepository::class.java)
        val matchRepository = mock(com.wrbug.polymarketbot.repository.OddsMatchRepository::class.java)
        val now = System.currentTimeMillis()
        val service = OddsChangeNotificationService(
            alertRepository,
            telegramNotificationService,
            notificationConfigService,
            marketRepository,
            snapshotRepository,
            matchRepository
        )
        val match = OddsPlatformMatch(
            id = 10,
            sourceKey = "crown",
            sourceMatchId = "m1",
            rawLeagueName = "Premier League",
            rawHomeTeam = "Arsenal",
            rawAwayTeam = "Chelsea",
            rawStartTime = now + 3_600_000
        )
        val market = OddsMarket(
            id = 20,
            matchId = 100,
            sourceKey = "crown",
            marketType = "handicap",
            lineValue = "-0.5",
            selectionName = "home"
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
                    leagueName = "Premier League",
                    homeTeam = "Arsenal",
                    awayTeam = "Chelsea",
                    startTime = now + 3_600_000,
                    status = "scheduled"
                )
            )
        )

        service.notifyIfChanged(match, market, BigDecimal("0.88"), BigDecimal("0.98"))
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
        val matchRepository = mock(com.wrbug.polymarketbot.repository.OddsMatchRepository::class.java)
        val now = System.currentTimeMillis()
        val service = OddsChangeNotificationService(
            alertRepository,
            telegramNotificationService,
            notificationConfigService,
            marketRepository,
            snapshotRepository,
            matchRepository
        )
        val match = OddsPlatformMatch(
            id = 10,
            sourceKey = "crown",
            sourceMatchId = "m1",
            rawLeagueName = "Premier League",
            rawHomeTeam = "Arsenal",
            rawAwayTeam = "Chelsea",
            rawStartTime = now - 60_000
        )
        val market = OddsMarket(
            id = 20,
            matchId = 100,
            sourceKey = "crown",
            marketType = "handicap",
            lineValue = "-0.5",
            selectionName = "home"
        )

        runBlocking {
            `when`(notificationConfigService.getEnabledConfigsByType("telegram")).thenReturn(
                listOf(telegramMonitorConfig(liveOnlyModeEnabled = false, handicapOddsMoveMin = "0.08"))
            )
        }
        `when`(matchRepository.findById(100)).thenReturn(
            Optional.of(
                OddsMatch(
                    id = 100,
                    leagueName = "Premier League",
                    homeTeam = "Arsenal",
                    awayTeam = "Chelsea",
                    startTime = now - 60_000,
                    status = "live"
                )
            )
        )

        service.notifyIfChanged(match, market, BigDecimal("0.88"), BigDecimal("0.98"))
        Thread.sleep(1_800)

        verify(alertRepository, never()).save(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `live only monitor records started odds changes`() {
        val alertRepository = mock(OddsAlertRecordRepository::class.java)
        val telegramNotificationService = mock(TelegramNotificationService::class.java)
        val notificationConfigService = mock(NotificationConfigService::class.java)
        val marketRepository = mock(OddsMarketRepository::class.java)
        val snapshotRepository = mock(OddsSnapshotRepository::class.java)
        val matchRepository = mock(com.wrbug.polymarketbot.repository.OddsMatchRepository::class.java)
        val now = System.currentTimeMillis()
        val service = OddsChangeNotificationService(
            alertRepository,
            telegramNotificationService,
            notificationConfigService,
            marketRepository,
            snapshotRepository,
            matchRepository
        )
        val match = OddsPlatformMatch(
            id = 10,
            sourceKey = "crown",
            sourceMatchId = "m1",
            rawLeagueName = "Premier League",
            rawHomeTeam = "Arsenal",
            rawAwayTeam = "Chelsea",
            rawStartTime = now - 60_000
        )
        val market = OddsMarket(
            id = 20,
            matchId = 100,
            sourceKey = "crown",
            marketType = "handicap",
            lineValue = "-0.5",
            selectionName = "home"
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
                    leagueName = "Premier League",
                    homeTeam = "Arsenal",
                    awayTeam = "Chelsea",
                    startTime = now - 60_000,
                    status = "live"
                )
            )
        )

        service.notifyIfChanged(match, market, BigDecimal("0.88"), BigDecimal("0.98"))
        Thread.sleep(1_800)

        verify(alertRepository, times(1)).save(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `does not suppress odds alert when movement reaches zero point zero eight`() {
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

    private fun mojibake(value: String): String {
        return String(value.toByteArray(StandardCharsets.UTF_8), Charset.forName("GBK"))
    }
}
