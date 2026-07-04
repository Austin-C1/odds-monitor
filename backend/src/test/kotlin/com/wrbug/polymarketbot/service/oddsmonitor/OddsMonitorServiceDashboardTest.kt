package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.entity.OddsDataSourceConfig
import com.wrbug.polymarketbot.entity.OddsMarket
import com.wrbug.polymarketbot.entity.OddsMatch
import com.wrbug.polymarketbot.entity.OddsMatchLink
import com.wrbug.polymarketbot.entity.OddsPlatformMatch
import com.wrbug.polymarketbot.entity.OddsSnapshot
import com.wrbug.polymarketbot.repository.OddsAlertRecordRepository
import com.wrbug.polymarketbot.repository.OddsCollectionLogRepository
import com.wrbug.polymarketbot.repository.OddsDataSourceConfigRepository
import com.wrbug.polymarketbot.repository.OddsMarketRepository
import com.wrbug.polymarketbot.repository.OddsMatchLinkRepository
import com.wrbug.polymarketbot.repository.OddsMatchRepository
import com.wrbug.polymarketbot.repository.OddsPlatformMatchRepository
import com.wrbug.polymarketbot.repository.OddsSnapshotRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.math.BigDecimal

class OddsMonitorServiceDashboardTest {
    @Test
    fun `dashboard uses collected crown matches when available`() {
        val fixtures = Fixtures()
        val crownMatch = OddsPlatformMatch(
            id = 32,
            sourceKey = "crown",
            sourceMatchId = "8733261",
            rawLeagueName = "Japan J1 League",
            rawHomeTeam = "Okayama",
            rawAwayTeam = "Hiroshima",
            rawStartTime = 1893456000000L
        )
        `when`(fixtures.platformRepository.findTop500BySourceKeyOrderByUpdatedAtDesc("crown")).thenReturn(listOf(crownMatch))
        `when`(fixtures.marketRepository.findByMatchIdInAndSourceKey(listOf(32), "crown")).thenReturn(
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
        `when`(fixtures.snapshotRepository.findTop1ByMarketIdOrderByCapturedAtDesc(42)).thenReturn(
            OddsSnapshot(
                marketId = 42,
                sourceKey = "crown",
                oddsValue = BigDecimal("0.94"),
                capturedAt = 1000
            )
        )

        val dashboard = fixtures.service().getDashboard()

        assertEquals(1, dashboard.matches.size)
        assertEquals("日本J1百年构想联赛", dashboard.matches.single().leagueName)
        assertEquals("冈山绿雉", dashboard.matches.single().homeTeam)
        assertEquals("广岛三箭", dashboard.matches.single().awayTeam)
        assertEquals(listOf("crown"), dashboard.matches.single().matchedPlatforms)
        assertTrue(dashboard.selectedMatch?.metrics?.any {
            it.sourceKey == "crown" && it.label == "total over 2.5" && it.value == "0.94"
        } == true)
    }

    @Test
    fun `dashboard skips disabled crown source even when it has collected data`() {
        val fixtures = Fixtures()
        `when`(fixtures.configRepository.findBySourceKey("crown")).thenReturn(
            OddsDataSourceConfig(sourceKey = "crown", displayName = "Crown", enabled = false)
        )
        `when`(fixtures.platformRepository.findTop500BySourceKeyOrderByUpdatedAtDesc("crown")).thenReturn(
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

        val dashboard = fixtures.service().getDashboard()

        assertTrue(dashboard.matches.isEmpty())
    }

    @Test
    fun `dashboard uses recent linked crown platform matches instead of stale standard page order`() {
        val fixtures = Fixtures()
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

        `when`(fixtures.platformRepository.findTop500BySourceKeyOrderByUpdatedAtDesc("crown")).thenReturn(listOf(recentPlatformMatch))
        `when`(fixtures.linkRepository.findByPlatformMatchIdIn(listOf(5001L))).thenReturn(
            listOf(OddsMatchLink(matchId = 9001, platformMatchId = 5001))
        )
        `when`(fixtures.matchRepository.findAllById(listOf(9001L))).thenReturn(
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
        `when`(fixtures.marketRepository.findByMatchIdInAndSourceKey(listOf(9001L), "crown")).thenReturn(emptyList())

        val dashboard = fixtures.service().getDashboard()

        assertEquals(1, dashboard.matches.size)
        assertEquals(9001L, dashboard.matches.single().id)
        assertEquals("滚球", dashboard.matches.single().status)
        assertEquals(listOf("crown"), dashboard.matches.single().matchedPlatforms)
    }

    @Test
    fun `dashboard orders live and recently updated collected matches first`() {
        val fixtures = Fixtures()
        `when`(fixtures.platformRepository.findTop500BySourceKeyOrderByUpdatedAtDesc("crown")).thenReturn(
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
                    rawLeagueName = "England Premier League",
                    rawHomeTeam = "Arsenal",
                    rawAwayTeam = "Chelsea",
                    rawStartTime = 1893455000000L,
                    updatedAt = 1000L
                )
            )
        )
        `when`(fixtures.marketRepository.findByMatchIdInAndSourceKey(listOf(701), "crown")).thenReturn(emptyList())

        val dashboard = fixtures.service().getDashboard()

        assertEquals("东京FC", dashboard.matches.first().homeTeam)
        assertEquals("滚球", dashboard.matches.first().status)
    }

    @Test
    fun `dashboard hides moneyline markets from collected monitor source`() {
        val fixtures = Fixtures()
        `when`(fixtures.platformRepository.findTop500BySourceKeyOrderByUpdatedAtDesc("crown")).thenReturn(
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
        `when`(fixtures.marketRepository.findByMatchIdInAndSourceKey(listOf(102), "crown")).thenReturn(
            listOf(
                OddsMarket(id = 202, matchId = 102, sourceKey = "crown", marketType = "moneyline", selectionName = "home")
            )
        )
        `when`(fixtures.snapshotRepository.findTop1ByMarketIdOrderByCapturedAtDesc(202)).thenReturn(
            OddsSnapshot(marketId = 202, sourceKey = "crown", oddsValue = BigDecimal("2.10"), capturedAt = 1000)
        )

        val dashboard = fixtures.service().getDashboard()

        val metrics = dashboard.selectedMatch?.metrics.orEmpty()
        assertTrue(metrics.none { it.sourceKey == "crown" && it.label.startsWith("moneyline") })
    }

    private class Fixtures {
        val configRepository: OddsDataSourceConfigRepository = mock(OddsDataSourceConfigRepository::class.java)
        val alertRepository: OddsAlertRecordRepository = mock(OddsAlertRecordRepository::class.java)
        val logRepository: OddsCollectionLogRepository = mock(OddsCollectionLogRepository::class.java)
        val platformRepository: OddsPlatformMatchRepository = mock(OddsPlatformMatchRepository::class.java)
        val marketRepository: OddsMarketRepository = mock(OddsMarketRepository::class.java)
        val snapshotRepository: OddsSnapshotRepository = mock(OddsSnapshotRepository::class.java)
        val matchRepository: OddsMatchRepository = mock(OddsMatchRepository::class.java)
        val linkRepository: OddsMatchLinkRepository = mock(OddsMatchLinkRepository::class.java)

        fun service(): OddsMonitorService {
            return OddsMonitorService(
                configRepository,
                alertRepository,
                logRepository,
                platformRepository,
                marketRepository,
                snapshotRepository,
                matchRepository,
                linkRepository
            )
        }
    }
}
