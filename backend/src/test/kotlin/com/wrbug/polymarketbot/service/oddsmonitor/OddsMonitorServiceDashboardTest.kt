package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.entity.OddsDataSourceConfig
import com.wrbug.polymarketbot.entity.OddsMarket
import com.wrbug.polymarketbot.entity.OddsMatch
import com.wrbug.polymarketbot.entity.OddsMatchLink
import com.wrbug.polymarketbot.entity.OddsPlatformMatch
import com.wrbug.polymarketbot.entity.OddsSnapshot
import com.wrbug.polymarketbot.entity.SystemConfig
import com.wrbug.polymarketbot.repository.OddsAlertRecordRepository
import com.wrbug.polymarketbot.repository.OddsCollectionLogRepository
import com.wrbug.polymarketbot.repository.OddsDataSourceConfigRepository
import com.wrbug.polymarketbot.repository.OddsMarketRepository
import com.wrbug.polymarketbot.repository.OddsMatchLinkRepository
import com.wrbug.polymarketbot.repository.OddsMatchRepository
import com.wrbug.polymarketbot.repository.OddsPlatformMatchRepository
import com.wrbug.polymarketbot.repository.OddsSnapshotRepository
import com.wrbug.polymarketbot.repository.SystemConfigRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.math.BigDecimal

class OddsMonitorServiceDashboardTest {
    @Test
    fun `dashboard uses collected pinnacle matches when available`() {
        val configRepository = mock(OddsDataSourceConfigRepository::class.java)
        val alertRepository = mock(OddsAlertRecordRepository::class.java)
        val logRepository = mock(OddsCollectionLogRepository::class.java)
        val platformRepository = mock(OddsPlatformMatchRepository::class.java)
        val marketRepository = mock(OddsMarketRepository::class.java)
        val snapshotRepository = mock(OddsSnapshotRepository::class.java)

        `when`(platformRepository.findTop500BySourceKeyOrderByUpdatedAtDesc("pinnacle")).thenReturn(
            listOf(
                OddsPlatformMatch(
                    id = 12,
                    sourceKey = "pinnacle",
                    sourceMatchId = "9001",
                    rawLeagueName = "England - Premier League",
                    rawHomeTeam = "Arsenal",
                    rawAwayTeam = "Chelsea",
                    rawStartTime = 1893456000000L
                )
            )
        )
        `when`(platformRepository.findTop500BySourceKeyOrderByUpdatedAtDesc("crown")).thenReturn(emptyList())
        `when`(platformRepository.findTop500BySourceKeyOrderByUpdatedAtDesc("polymarket")).thenReturn(emptyList())
        `when`(marketRepository.findByMatchIdInAndSourceKey(listOf(12), "pinnacle")).thenReturn(
            listOf(
                OddsMarket(
                    id = 22,
                    matchId = 12,
                    sourceKey = "pinnacle",
                    marketType = "handicap",
                    lineValue = "-0.5",
                    selectionName = "home"
                )
            )
        )
        `when`(snapshotRepository.findTop1ByMarketIdOrderByCapturedAtDesc(22)).thenReturn(
            OddsSnapshot(
                marketId = 22,
                sourceKey = "pinnacle",
                oddsValue = BigDecimal("1.93"),
                capturedAt = 1000
            )
        )

        val dashboard = OddsMonitorService(
            configRepository,
            alertRepository,
            logRepository,
            platformRepository,
            marketRepository,
            snapshotRepository
        ).getDashboard()

        assertEquals(1, dashboard.matches.size)
        assertEquals("英格兰超级联赛", dashboard.matches.single().leagueName)
        assertEquals("阿森纳", dashboard.matches.single().homeTeam)
        assertEquals("切尔西", dashboard.matches.single().awayTeam)
        assertEquals(listOf("pinnacle"), dashboard.matches.single().matchedPlatforms)
        assertTrue(dashboard.selectedMatch?.metrics?.any { it.label == "handicap home -0.5" && it.value == "1.93" } == true)
    }

    @Test
    fun `dashboard uses collected crown matches when pinnacle has no data`() {
        val configRepository = mock(OddsDataSourceConfigRepository::class.java)
        val alertRepository = mock(OddsAlertRecordRepository::class.java)
        val logRepository = mock(OddsCollectionLogRepository::class.java)
        val platformRepository = mock(OddsPlatformMatchRepository::class.java)
        val marketRepository = mock(OddsMarketRepository::class.java)
        val snapshotRepository = mock(OddsSnapshotRepository::class.java)

        `when`(platformRepository.findTop500BySourceKeyOrderByUpdatedAtDesc("pinnacle")).thenReturn(emptyList())
        `when`(platformRepository.findTop500BySourceKeyOrderByUpdatedAtDesc("crown")).thenReturn(
            listOf(
                OddsPlatformMatch(
                    id = 32,
                    sourceKey = "crown",
                    sourceMatchId = "8733261",
                    rawLeagueName = "Japan J1 League",
                    rawHomeTeam = "Okayama",
                    rawAwayTeam = "Hiroshima",
                    rawStartTime = 1893456000000L
                )
            )
        )
        `when`(platformRepository.findTop500BySourceKeyOrderByUpdatedAtDesc("polymarket")).thenReturn(emptyList())
        `when`(marketRepository.findByMatchIdInAndSourceKey(listOf(32), "crown")).thenReturn(
            listOf(
                OddsMarket(
                    id = 42,
                    matchId = 32,
                    sourceKey = "crown",
                    marketType = "total",
                    lineValue = "2.5",
                    selectionName = "over"
                )
            )
        )
        `when`(snapshotRepository.findTop1ByMarketIdOrderByCapturedAtDesc(42)).thenReturn(
            OddsSnapshot(
                marketId = 42,
                sourceKey = "crown",
                oddsValue = BigDecimal("0.94"),
                capturedAt = 1000
            )
        )

        val dashboard = OddsMonitorService(
            configRepository,
            alertRepository,
            logRepository,
            platformRepository,
            marketRepository,
            snapshotRepository
        ).getDashboard()

        assertEquals(1, dashboard.matches.size)
        assertEquals("日本J1百年构想联赛", dashboard.matches.single().leagueName)
        assertEquals("冈山绿雉", dashboard.matches.single().homeTeam)
        assertEquals("广岛三箭", dashboard.matches.single().awayTeam)
        assertEquals(listOf("crown"), dashboard.matches.single().matchedPlatforms)
        assertTrue(dashboard.selectedMatch?.metrics?.any { it.label == "total over 2.5" && it.value == "0.94" } == true)
    }

    @Test
    fun `dashboard merges same collected match across enabled platforms`() {
        val configRepository = mock(OddsDataSourceConfigRepository::class.java)
        val alertRepository = mock(OddsAlertRecordRepository::class.java)
        val logRepository = mock(OddsCollectionLogRepository::class.java)
        val platformRepository = mock(OddsPlatformMatchRepository::class.java)
        val marketRepository = mock(OddsMarketRepository::class.java)
        val snapshotRepository = mock(OddsSnapshotRepository::class.java)

        `when`(platformRepository.findTop500BySourceKeyOrderByUpdatedAtDesc("pinnacle")).thenReturn(
            listOf(
                OddsPlatformMatch(
                    id = 71,
                    sourceKey = "pinnacle",
                    sourceMatchId = "p1",
                    rawLeagueName = "Netherlands Eerste Divisie",
                    rawHomeTeam = "Volendam",
                    rawAwayTeam = "Roda JC\u200e",
                    rawStartTime = 1893456000000L
                )
            )
        )
        `when`(platformRepository.findTop500BySourceKeyOrderByUpdatedAtDesc("crown")).thenReturn(
            listOf(
                OddsPlatformMatch(
                    id = 72,
                    sourceKey = "crown",
                    sourceMatchId = "c1",
                    rawLeagueName = "Netherlands Eerste Divisie",
                    rawHomeTeam = "Volendam",
                    rawAwayTeam = "Roda-JC",
                    rawStartTime = null
                )
            )
        )
        `when`(platformRepository.findTop500BySourceKeyOrderByUpdatedAtDesc("polymarket")).thenReturn(
            listOf(
                OddsPlatformMatch(
                    id = 73,
                    sourceKey = "polymarket",
                    sourceMatchId = "pm1",
                    rawLeagueName = "Netherlands Eerste Divisie",
                    rawHomeTeam = "Volendam",
                    rawAwayTeam = "Roda JC",
                    rawStartTime = 1893456000000L
                )
            )
        )
        `when`(marketRepository.findByMatchIdInAndSourceKey(listOf(71), "pinnacle")).thenReturn(
            listOf(
                OddsMarket(
                    id = 81,
                    matchId = 71,
                    sourceKey = "pinnacle",
                    marketType = "handicap",
                    lineValue = "0.5",
                    selectionName = "home"
                )
            )
        )
        `when`(marketRepository.findByMatchIdInAndSourceKey(listOf(72), "crown")).thenReturn(
            listOf(
                OddsMarket(
                    id = 82,
                    matchId = 72,
                    sourceKey = "crown",
                    marketType = "handicap",
                    lineValue = "0.5",
                    selectionName = "home"
                )
            )
        )
        `when`(marketRepository.findByMatchIdInAndSourceKey(listOf(73), "polymarket")).thenReturn(emptyList())
        `when`(snapshotRepository.findTop1ByMarketIdOrderByCapturedAtDesc(81)).thenReturn(
            OddsSnapshot(marketId = 81, sourceKey = "pinnacle", oddsValue = BigDecimal("1.961"), capturedAt = 1000)
        )
        `when`(snapshotRepository.findTop1ByMarketIdOrderByCapturedAtDesc(82)).thenReturn(
            OddsSnapshot(marketId = 82, sourceKey = "crown", oddsValue = BigDecimal("0.940"), capturedAt = 1000)
        )

        val dashboard = OddsMonitorService(
            configRepository,
            alertRepository,
            logRepository,
            platformRepository,
            marketRepository,
            snapshotRepository
        ).getDashboard()

        assertEquals(1, dashboard.matches.size)
        assertEquals(2, dashboard.matches.single().sourceCount)
        assertEquals(listOf("pinnacle", "crown"), dashboard.matches.single().matchedPlatforms)
        assertTrue(dashboard.selectedMatch?.metrics?.any {
            it.sourceKey == "pinnacle" && it.label == "handicap home 0.5" && it.value == "1.961"
        } == true)
        assertTrue(dashboard.selectedMatch?.metrics?.any {
            it.sourceKey == "crown" && it.label == "handicap home 0.5" && it.value == "0.94"
        } == true)
    }

    @Test
    fun `dashboard includes crown only matches when pinnacle has different matches`() {
        val configRepository = mock(OddsDataSourceConfigRepository::class.java)
        val alertRepository = mock(OddsAlertRecordRepository::class.java)
        val logRepository = mock(OddsCollectionLogRepository::class.java)
        val platformRepository = mock(OddsPlatformMatchRepository::class.java)
        val marketRepository = mock(OddsMarketRepository::class.java)
        val snapshotRepository = mock(OddsSnapshotRepository::class.java)

        `when`(platformRepository.findTop500BySourceKeyOrderByUpdatedAtDesc("pinnacle")).thenReturn(
            listOf(
                OddsPlatformMatch(
                    id = 91,
                    sourceKey = "pinnacle",
                    sourceMatchId = "p1",
                    rawLeagueName = "England - Premier League",
                    rawHomeTeam = "Arsenal",
                    rawAwayTeam = "Chelsea",
                    rawStartTime = 1893456000000L,
                    updatedAt = 2000
                )
            )
        )
        `when`(platformRepository.findTop500BySourceKeyOrderByUpdatedAtDesc("crown")).thenReturn(
            listOf(
                OddsPlatformMatch(
                    id = 92,
                    sourceKey = "crown",
                    sourceMatchId = "c1",
                    rawLeagueName = "Canada Premier League",
                    rawHomeTeam = "HFX Wanderers",
                    rawAwayTeam = "Forge",
                    rawStartTime = null,
                    updatedAt = 3000
                )
            )
        )
        `when`(platformRepository.findTop500BySourceKeyOrderByUpdatedAtDesc("polymarket")).thenReturn(emptyList())
        `when`(marketRepository.findByMatchIdInAndSourceKey(listOf(92), "crown")).thenReturn(emptyList())

        val dashboard = OddsMonitorService(
            configRepository,
            alertRepository,
            logRepository,
            platformRepository,
            marketRepository,
            snapshotRepository
        ).getDashboard()

        assertEquals(2, dashboard.matches.size)
        assertTrue(dashboard.matches.any {
            it.homeTeam == "哈利法克斯流浪者" &&
                it.awayTeam == "弗尔格" &&
                it.matchedPlatforms == listOf("crown")
        })
    }

    @Test
    fun `dashboard hides collected esports football matches`() {
        val configRepository = mock(OddsDataSourceConfigRepository::class.java)
        val alertRepository = mock(OddsAlertRecordRepository::class.java)
        val logRepository = mock(OddsCollectionLogRepository::class.java)
        val platformRepository = mock(OddsPlatformMatchRepository::class.java)
        val marketRepository = mock(OddsMarketRepository::class.java)
        val snapshotRepository = mock(OddsSnapshotRepository::class.java)

        `when`(platformRepository.findTop500BySourceKeyOrderByUpdatedAtDesc("pinnacle")).thenReturn(emptyList())
        `when`(platformRepository.findTop500BySourceKeyOrderByUpdatedAtDesc("crown")).thenReturn(
            listOf(
                OddsPlatformMatch(
                    id = 61,
                    sourceKey = "crown",
                    sourceMatchId = "esports-1",
                    rawLeagueName = "Esports Football-H2H GG League",
                    rawHomeTeam = "Portugal (Hollywood) Esports",
                    rawAwayTeam = "France (Emperor) Esports",
                    rawStartTime = 1893456000000L
                ),
                OddsPlatformMatch(
                    id = 62,
                    sourceKey = "crown",
                    sourceMatchId = "real-1",
                    rawLeagueName = "Japan J1 League",
                    rawHomeTeam = "Okayama",
                    rawAwayTeam = "Hiroshima",
                    rawStartTime = 1893456000000L
                )
            )
        )
        `when`(platformRepository.findTop500BySourceKeyOrderByUpdatedAtDesc("polymarket")).thenReturn(emptyList())
        `when`(marketRepository.findByMatchIdInAndSourceKey(listOf(62), "crown")).thenReturn(emptyList())

        val dashboard = OddsMonitorService(
            configRepository,
            alertRepository,
            logRepository,
            platformRepository,
            marketRepository,
            snapshotRepository
        ).getDashboard()

        assertEquals(1, dashboard.matches.size)
        assertEquals("日本J1百年构想联赛", dashboard.matches.single().leagueName)
        assertEquals("冈山绿雉", dashboard.matches.single().homeTeam)
        assertEquals("广岛三箭", dashboard.matches.single().awayTeam)
    }

    @Test
    fun `dashboard skips disabled source even when it has old collected data`() {
        val configRepository = mock(OddsDataSourceConfigRepository::class.java)
        val alertRepository = mock(OddsAlertRecordRepository::class.java)
        val logRepository = mock(OddsCollectionLogRepository::class.java)
        val platformRepository = mock(OddsPlatformMatchRepository::class.java)
        val marketRepository = mock(OddsMarketRepository::class.java)
        val snapshotRepository = mock(OddsSnapshotRepository::class.java)

        `when`(configRepository.findBySourceKey("pinnacle")).thenReturn(
            OddsDataSourceConfig(sourceKey = "pinnacle", displayName = "Pinnacle", enabled = false)
        )
        `when`(configRepository.findBySourceKey("crown")).thenReturn(
            OddsDataSourceConfig(sourceKey = "crown", displayName = "Crown", enabled = true)
        )
        `when`(configRepository.findBySourceKey("polymarket")).thenReturn(
            OddsDataSourceConfig(sourceKey = "polymarket", displayName = "Polymarket", enabled = false)
        )
        `when`(platformRepository.findTop500BySourceKeyOrderByUpdatedAtDesc("pinnacle")).thenReturn(
            listOf(
                OddsPlatformMatch(
                    id = 51,
                    sourceKey = "pinnacle",
                    sourceMatchId = "old-pinnacle",
                    rawLeagueName = "Old League",
                    rawHomeTeam = "Old Home",
                    rawAwayTeam = "Old Away",
                    rawStartTime = 1893456000000L
                )
            )
        )
        `when`(platformRepository.findTop500BySourceKeyOrderByUpdatedAtDesc("crown")).thenReturn(
            listOf(
                OddsPlatformMatch(
                    id = 52,
                    sourceKey = "crown",
                    sourceMatchId = "8733261",
                    rawLeagueName = "Canada Premier League",
                    rawHomeTeam = "Toronto International",
                    rawAwayTeam = "Vancouver FC",
                    rawStartTime = 1893456000000L
                )
            )
        )
        `when`(platformRepository.findTop500BySourceKeyOrderByUpdatedAtDesc("polymarket")).thenReturn(emptyList())
        `when`(marketRepository.findByMatchIdInAndSourceKey(listOf(52), "crown")).thenReturn(emptyList())

        val dashboard = OddsMonitorService(
            configRepository,
            alertRepository,
            logRepository,
            platformRepository,
            marketRepository,
            snapshotRepository
        ).getDashboard()

        assertEquals("加拿大超级联赛", dashboard.matches.single().leagueName)
        assertEquals("多伦多国际", dashboard.matches.single().homeTeam)
        assertEquals("温哥华FC", dashboard.matches.single().awayTeam)
        assertEquals(listOf("crown"), dashboard.matches.single().matchedPlatforms)
    }

    @Test
    fun `dashboard uses recent linked platform matches instead of earliest standard match page`() {
        val configRepository = mock(OddsDataSourceConfigRepository::class.java)
        val alertRepository = mock(OddsAlertRecordRepository::class.java)
        val logRepository = mock(OddsCollectionLogRepository::class.java)
        val platformRepository = mock(OddsPlatformMatchRepository::class.java)
        val marketRepository = mock(OddsMarketRepository::class.java)
        val snapshotRepository = mock(OddsSnapshotRepository::class.java)
        val matchRepository = mock(OddsMatchRepository::class.java)
        val linkRepository = mock(OddsMatchLinkRepository::class.java)
        val recentPlatformMatch = OddsPlatformMatch(
            id = 5001,
            sourceKey = "crown",
            sourceMatchId = "live-crown",
            rawLeagueName = "Japan J1 League",
            rawHomeTeam = "FC Tokyo",
            rawAwayTeam = "Kawasaki Frontale",
            rawStartTime = 1893456000000L,
            rawPayloadJson = """{"showtype":"rb","retimeset":"1H^12:34"}""",
            updatedAt = 9000L
        )

        `when`(platformRepository.findTop500BySourceKeyOrderByUpdatedAtDesc("pinnacle")).thenReturn(emptyList())
        `when`(platformRepository.findTop500BySourceKeyOrderByUpdatedAtDesc("crown")).thenReturn(listOf(recentPlatformMatch))
        `when`(linkRepository.findByPlatformMatchIdIn(listOf(5001L))).thenReturn(
            listOf(OddsMatchLink(matchId = 9001, platformMatchId = 5001))
        )
        `when`(matchRepository.findAllById(listOf(9001L))).thenReturn(
            listOf(
                OddsMatch(
                    id = 9001,
                    leagueName = "Japan J1 League",
                    homeTeam = "FC Tokyo",
                    awayTeam = "Kawasaki Frontale",
                    startTime = 1893456000000L,
                    status = "live",
                    updatedAt = 9000L
                )
            )
        )
        `when`(marketRepository.findByMatchIdInAndSourceKey(listOf(9001L), "crown")).thenReturn(emptyList())

        val dashboard = OddsMonitorService(
            configRepository,
            alertRepository,
            logRepository,
            platformRepository,
            marketRepository,
            snapshotRepository,
            matchRepository,
            linkRepository
        ).getDashboard()

        assertEquals(1, dashboard.matches.size)
        assertEquals(9001L, dashboard.matches.single().id)
        assertEquals("滚球", dashboard.matches.single().status)
        assertEquals(listOf("crown"), dashboard.matches.single().matchedPlatforms)
    }

    @Test
    fun `dashboard orders live and recently updated collected matches first`() {
        val configRepository = mock(OddsDataSourceConfigRepository::class.java)
        val alertRepository = mock(OddsAlertRecordRepository::class.java)
        val logRepository = mock(OddsCollectionLogRepository::class.java)
        val platformRepository = mock(OddsPlatformMatchRepository::class.java)
        val marketRepository = mock(OddsMarketRepository::class.java)
        val snapshotRepository = mock(OddsSnapshotRepository::class.java)

        `when`(platformRepository.findTop500BySourceKeyOrderByUpdatedAtDesc("pinnacle")).thenReturn(emptyList())
        `when`(platformRepository.findTop500BySourceKeyOrderByUpdatedAtDesc("crown")).thenReturn(
            listOf(
                OddsPlatformMatch(
                    id = 701,
                    sourceKey = "crown",
                    sourceMatchId = "new-live",
                    rawLeagueName = "Japan J1 League",
                    rawHomeTeam = "FC Tokyo",
                    rawAwayTeam = "Kawasaki Frontale",
                    rawStartTime = 1893456000000L,
                    rawPayloadJson = """{"showtype":"rb","retimeset":"1H^12:34"}""",
                    updatedAt = 9000L
                ),
                OddsPlatformMatch(
                    id = 702,
                    sourceKey = "crown",
                    sourceMatchId = "old-prematch",
                    rawLeagueName = "England - Premier League",
                    rawHomeTeam = "Arsenal",
                    rawAwayTeam = "Chelsea",
                    rawStartTime = 1893455000000L,
                    updatedAt = 1000L
                )
            )
        )
        `when`(marketRepository.findByMatchIdInAndSourceKey(listOf(701), "crown")).thenReturn(emptyList())

        val dashboard = OddsMonitorService(
            configRepository,
            alertRepository,
            logRepository,
            platformRepository,
            marketRepository,
            snapshotRepository
        ).getDashboard()

        assertEquals("东京FC", dashboard.matches.first().homeTeam)
        assertEquals("滚球", dashboard.matches.first().status)
    }

    @Test
    fun `dashboard uses built in pinnacle defaults before platform filter list is saved`() {
        val configRepository = mock(OddsDataSourceConfigRepository::class.java)
        val alertRepository = mock(OddsAlertRecordRepository::class.java)
        val logRepository = mock(OddsCollectionLogRepository::class.java)
        val platformRepository = mock(OddsPlatformMatchRepository::class.java)
        val marketRepository = mock(OddsMarketRepository::class.java)
        val snapshotRepository = mock(OddsSnapshotRepository::class.java)
        val systemConfigRepository = mock(SystemConfigRepository::class.java)

        `when`(systemConfigRepository.findByConfigKey(OddsLeagueFilterService.CONFIG_KEY)).thenReturn(
            SystemConfig(configKey = OddsLeagueFilterService.CONFIG_KEY, configValue = """["英格兰超级联赛"]""")
        )
        `when`(systemConfigRepository.findByConfigKey(OddsLeagueFilterService.PINNACLE_CONFIG_KEY)).thenReturn(null)
        `when`(platformRepository.findTop500BySourceKeyOrderByUpdatedAtDesc("pinnacle")).thenReturn(
            listOf(
                OddsPlatformMatch(
                    id = 601,
                    sourceKey = "pinnacle",
                    sourceMatchId = "p1",
                    rawLeagueName = "英格兰 - 超级联赛",
                    rawHomeTeam = "Arsenal",
                    rawAwayTeam = "Chelsea",
                    rawStartTime = 1893456000000L
                )
            )
        )
        `when`(platformRepository.findTop500BySourceKeyOrderByUpdatedAtDesc("crown")).thenReturn(emptyList())
        `when`(platformRepository.findTop500BySourceKeyOrderByUpdatedAtDesc("polymarket")).thenReturn(emptyList())

        val dashboard = OddsMonitorService(
            configRepository,
            alertRepository,
            logRepository,
            platformRepository,
            marketRepository,
            snapshotRepository,
            leagueFilterService = OddsLeagueFilterService(systemConfigRepository)
        ).getDashboard()

        assertEquals(1, dashboard.matches.size)
        assertEquals("英格兰超级联赛", dashboard.matches.single().leagueName)
        assertEquals("阿森纳", dashboard.matches.single().homeTeam)
        assertEquals("切尔西", dashboard.matches.single().awayTeam)
        assertEquals(listOf("pinnacle"), dashboard.matches.single().matchedPlatforms)
    }

    @Test
    fun `dashboard hides moneyline markets from collected monitor sources`() {
        val configRepository = mock(OddsDataSourceConfigRepository::class.java)
        val alertRepository = mock(OddsAlertRecordRepository::class.java)
        val logRepository = mock(OddsCollectionLogRepository::class.java)
        val platformRepository = mock(OddsPlatformMatchRepository::class.java)
        val marketRepository = mock(OddsMarketRepository::class.java)
        val snapshotRepository = mock(OddsSnapshotRepository::class.java)

        `when`(platformRepository.findTop500BySourceKeyOrderByUpdatedAtDesc("pinnacle")).thenReturn(
            listOf(
                OddsPlatformMatch(
                    id = 101,
                    sourceKey = "pinnacle",
                    sourceMatchId = "p1",
                    rawLeagueName = "Japan J1 League",
                    rawHomeTeam = "FC Tokyo",
                    rawAwayTeam = "Kawasaki Frontale"
                )
            )
        )
        `when`(platformRepository.findTop500BySourceKeyOrderByUpdatedAtDesc("crown")).thenReturn(
            listOf(
                OddsPlatformMatch(
                    id = 102,
                    sourceKey = "crown",
                    sourceMatchId = "c1",
                    rawLeagueName = "Japan J1 League",
                    rawHomeTeam = "FC Tokyo",
                    rawAwayTeam = "Kawasaki Frontale"
                )
            )
        )
        `when`(marketRepository.findByMatchIdInAndSourceKey(listOf(101), "pinnacle")).thenReturn(
            listOf(
                OddsMarket(id = 201, matchId = 101, sourceKey = "pinnacle", marketType = "moneyline", selectionName = "home")
            )
        )
        `when`(marketRepository.findByMatchIdInAndSourceKey(listOf(102), "crown")).thenReturn(
            listOf(
                OddsMarket(id = 202, matchId = 102, sourceKey = "crown", marketType = "moneyline", selectionName = "home")
            )
        )
        `when`(snapshotRepository.findTop1ByMarketIdOrderByCapturedAtDesc(201)).thenReturn(
            OddsSnapshot(marketId = 201, sourceKey = "pinnacle", oddsValue = BigDecimal("2.20"), capturedAt = 1000)
        )
        `when`(snapshotRepository.findTop1ByMarketIdOrderByCapturedAtDesc(202)).thenReturn(
            OddsSnapshot(marketId = 202, sourceKey = "crown", oddsValue = BigDecimal("2.10"), capturedAt = 1000)
        )

        val dashboard = OddsMonitorService(
            configRepository,
            alertRepository,
            logRepository,
            platformRepository,
            marketRepository,
            snapshotRepository
        ).getDashboard()

        val metrics = dashboard.selectedMatch?.metrics.orEmpty()
        assertTrue(metrics.none { it.sourceKey == "pinnacle" && it.label.startsWith("moneyline") })
        assertTrue(metrics.none { it.sourceKey == "crown" && it.label.startsWith("moneyline") })
    }
}

