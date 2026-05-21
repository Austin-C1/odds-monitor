package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.entity.OddsMatch
import com.wrbug.polymarketbot.entity.OddsMatchLink
import com.wrbug.polymarketbot.entity.OddsPlatformMatch
import com.wrbug.polymarketbot.repository.OddsMatchLinkRepository
import com.wrbug.polymarketbot.repository.OddsMatchRepository
import com.wrbug.polymarketbot.repository.OddsPlatformMatchRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.Optional

class OddsStandardMatchServiceTest {
    @Test
    fun `links translated platform match to existing standard match`() {
        val matchRepository = mock(OddsMatchRepository::class.java)
        val linkRepository = mock(OddsMatchLinkRepository::class.java)
        val platformMatchRepository = mock(OddsPlatformMatchRepository::class.java)
        `when`(linkRepository.findByPlatformMatchId(20)).thenReturn(null)
        `when`(linkRepository.findByMatchId(10)).thenReturn(emptyList())
        `when`(matchRepository.findTop500BySportOrderByStartTimeAsc("football")).thenReturn(
            listOf(
                OddsMatch(
                    id = 10,
                    leagueName = "Japan J1 League",
                    homeTeam = "Kawasaki Frontale",
                    awayTeam = "FC Tokyo",
                    startTime = 1893456000000L
                )
            )
        )
        `when`(matchRepository.save(any(OddsMatch::class.java))).thenAnswer { it.arguments[0] }
        `when`(linkRepository.save(any(OddsMatchLink::class.java))).thenAnswer { it.arguments[0] }

        val standardMatch = OddsStandardMatchService(
            matchRepository,
            linkRepository,
            platformMatchRepository
        ).resolveStandardMatch(
            OddsPlatformMatch(
                id = 20,
                sourceKey = "crown",
                sourceMatchId = "c1",
                rawLeagueName = "日本J1",
                rawHomeTeam = "川崎前锋",
                rawAwayTeam = "东京",
                rawStartTime = 1893456120000L
            )
        )

        assertEquals(10, standardMatch.id)
        val captor = ArgumentCaptor.forClass(OddsMatchLink::class.java)
        verify(linkRepository).save(captor.capture())
        assertEquals(10L, captor.value.matchId)
        assertEquals(20L, captor.value.platformMatchId)
        assertEquals("auto", captor.value.matchMethod)
        assertEquals(true, captor.value.confidence.toDouble() >= 0.85)
    }

    @Test
    fun `creates standard match when no candidate is close enough`() {
        val matchRepository = mock(OddsMatchRepository::class.java)
        val linkRepository = mock(OddsMatchLinkRepository::class.java)
        val platformMatchRepository = mock(OddsPlatformMatchRepository::class.java)
        `when`(linkRepository.findByPlatformMatchId(21)).thenReturn(null)
        `when`(matchRepository.findTop500BySportOrderByStartTimeAsc("football")).thenReturn(emptyList())
        `when`(
            matchRepository.save(any(OddsMatch::class.java))
        ).thenAnswer {
            (it.arguments[0] as OddsMatch).copy(id = 30)
        }
        `when`(linkRepository.save(any(OddsMatchLink::class.java))).thenAnswer { it.arguments[0] }

        val standardMatch = OddsStandardMatchService(
            matchRepository,
            linkRepository,
            platformMatchRepository
        ).resolveStandardMatch(
            OddsPlatformMatch(
                id = 21,
                sourceKey = "pinnacle",
                sourceMatchId = "p1",
                rawLeagueName = "England Premier League",
                rawHomeTeam = "Arsenal",
                rawAwayTeam = "Chelsea",
                rawStartTime = 1893542400000L
            )
        )

        assertEquals(30, standardMatch.id)
        assertEquals("Arsenal", standardMatch.homeTeam)
        assertEquals("Chelsea", standardMatch.awayTeam)
        val captor = ArgumentCaptor.forClass(OddsMatchLink::class.java)
        verify(linkRepository).save(captor.capture())
        assertEquals(30L, captor.value.matchId)
        assertEquals(21L, captor.value.platformMatchId)
        assertEquals("new", captor.value.matchMethod)
    }

    @Test
    fun `relinks existing platform match when corrected source data matches a better standard match`() {
        val matchRepository = mock(OddsMatchRepository::class.java)
        val linkRepository = mock(OddsMatchLinkRepository::class.java)
        val platformMatchRepository = mock(OddsPlatformMatchRepository::class.java)
        val staleCrownMatch = OddsMatch(
            id = 10,
            leagueName = "拉脱维亚超级联赛",
            homeTeam = "图库姆斯",
            awayTeam = "奥格雷联",
            startTime = 1893438000000L
        )
        val pinnacleMatch = OddsMatch(
            id = 11,
            leagueName = "拉脱维亚 - 超级联赛",
            homeTeam = "图库姆斯2000",
            awayTeam = "Ogre United",
            startTime = 1893456000000L
        )
        `when`(linkRepository.findByPlatformMatchId(20)).thenReturn(
            OddsMatchLink(matchId = 10, platformMatchId = 20)
        )
        `when`(linkRepository.findByMatchId(11)).thenReturn(emptyList())
        `when`(matchRepository.findById(10)).thenReturn(Optional.of(staleCrownMatch))
        `when`(matchRepository.findTop500BySportOrderByStartTimeAsc("football")).thenReturn(
            listOf(staleCrownMatch, pinnacleMatch)
        )
        `when`(matchRepository.save(any(OddsMatch::class.java))).thenAnswer { it.arguments[0] }
        `when`(linkRepository.save(any(OddsMatchLink::class.java))).thenAnswer { it.arguments[0] }

        val standardMatch = OddsStandardMatchService(
            matchRepository,
            linkRepository,
            platformMatchRepository
        ).resolveStandardMatch(
            OddsPlatformMatch(
                id = 20,
                sourceKey = "crown",
                sourceMatchId = "c1",
                rawLeagueName = "拉脱维亚超级联赛",
                rawHomeTeam = "图库姆斯",
                rawAwayTeam = "奥格雷联",
                rawStartTime = 1893456000000L
            )
        )

        assertEquals(11, standardMatch.id)
        val captor = ArgumentCaptor.forClass(OddsMatchLink::class.java)
        verify(linkRepository).save(captor.capture())
        assertEquals(11L, captor.value.matchId)
        assertEquals(20L, captor.value.platformMatchId)
        assertEquals(true, captor.value.confidence.toDouble() >= 0.85)
    }

    @Test
    fun `refreshes stale standard match start time from corrected platform data`() {
        val matchRepository = mock(OddsMatchRepository::class.java)
        val linkRepository = mock(OddsMatchLinkRepository::class.java)
        val platformMatchRepository = mock(OddsPlatformMatchRepository::class.java)
        val staleMatch = OddsMatch(
            id = 10,
            leagueName = "拉脱维亚超级联赛",
            homeTeam = "图库姆斯",
            awayTeam = "奥格雷联",
            startTime = 1893438000000L,
            status = "scheduled"
        )
        `when`(linkRepository.findByPlatformMatchId(20)).thenReturn(
            OddsMatchLink(matchId = 10, platformMatchId = 20)
        )
        `when`(linkRepository.findByMatchId(10)).thenReturn(
            listOf(OddsMatchLink(matchId = 10, platformMatchId = 20))
        )
        `when`(matchRepository.findById(10)).thenReturn(Optional.of(staleMatch))
        `when`(matchRepository.findTop500BySportOrderByStartTimeAsc("football")).thenReturn(listOf(staleMatch))
        `when`(matchRepository.save(any(OddsMatch::class.java))).thenAnswer { it.arguments[0] }

        val standardMatch = OddsStandardMatchService(
            matchRepository,
            linkRepository,
            platformMatchRepository
        ).resolveStandardMatch(
            OddsPlatformMatch(
                id = 20,
                sourceKey = "crown",
                sourceMatchId = "c1",
                rawLeagueName = "拉脱维亚超级联赛",
                rawHomeTeam = "图库姆斯",
                rawAwayTeam = "奥格雷联",
                rawStartTime = 1893456000000L
            )
        )

        assertEquals(1893456000000L, standardMatch.startTime)
        val captor = ArgumentCaptor.forClass(OddsMatch::class.java)
        verify(matchRepository).save(captor.capture())
        assertEquals(1893456000000L, captor.value.startTime)
    }

    @Test
    fun `keeps existing standard start time when pinnacle has timezone offset`() {
        val matchRepository = mock(OddsMatchRepository::class.java)
        val linkRepository = mock(OddsMatchLinkRepository::class.java)
        val platformMatchRepository = mock(OddsPlatformMatchRepository::class.java)
        val crownStartTime = 1893456000000L
        val pinnacleOffsetStartTime = crownStartTime + 8 * 60 * 60 * 1000
        val standardMatch = OddsMatch(
            id = 10,
            leagueName = "UEFA Europa League",
            homeTeam = "Aston Villa",
            awayTeam = "Nottingham Forest",
            startTime = crownStartTime,
            status = "scheduled"
        )
        `when`(linkRepository.findByPlatformMatchId(21)).thenReturn(
            OddsMatchLink(matchId = 10, platformMatchId = 21)
        )
        `when`(linkRepository.findByMatchId(10)).thenReturn(
            listOf(
                OddsMatchLink(matchId = 10, platformMatchId = 20),
                OddsMatchLink(matchId = 10, platformMatchId = 21)
            )
        )
        `when`(platformMatchRepository.findAllById(listOf(20L))).thenReturn(
            listOf(
                OddsPlatformMatch(
                    id = 20,
                    sourceKey = "crown",
                    sourceMatchId = "c1",
                    rawLeagueName = "UEFA Europa League",
                    rawHomeTeam = "Aston Villa",
                    rawAwayTeam = "Nottingham Forest",
                    rawStartTime = crownStartTime
                )
            )
        )
        `when`(matchRepository.findById(10)).thenReturn(Optional.of(standardMatch))
        `when`(matchRepository.findTop500BySportOrderByStartTimeAsc("football")).thenReturn(listOf(standardMatch))
        `when`(matchRepository.save(any(OddsMatch::class.java))).thenAnswer { it.arguments[0] }

        val resolvedMatch = OddsStandardMatchService(
            matchRepository,
            linkRepository,
            platformMatchRepository
        ).resolveStandardMatch(
            OddsPlatformMatch(
                id = 21,
                sourceKey = "pinnacle",
                sourceMatchId = "p1",
                rawLeagueName = "UEFA Europa League",
                rawHomeTeam = "Aston Villa",
                rawAwayTeam = "Nottingham Forest",
                rawStartTime = pinnacleOffsetStartTime
            )
        )

        assertEquals(crownStartTime, resolvedMatch.startTime)
    }

    @Test
    fun `refreshes standard match updated time when linked platform match is collected again`() {
        val matchRepository = mock(OddsMatchRepository::class.java)
        val linkRepository = mock(OddsMatchLinkRepository::class.java)
        val platformMatchRepository = mock(OddsPlatformMatchRepository::class.java)
        val standardMatch = OddsMatch(
            id = 10,
            leagueName = "Japan J1 League",
            homeTeam = "FC Tokyo",
            awayTeam = "Kawasaki Frontale",
            startTime = 1893456000000L,
            status = "live",
            updatedAt = 1000L
        )
        `when`(linkRepository.findByPlatformMatchId(20)).thenReturn(
            OddsMatchLink(matchId = 10, platformMatchId = 20)
        )
        `when`(linkRepository.findByMatchId(10)).thenReturn(
            listOf(OddsMatchLink(matchId = 10, platformMatchId = 20))
        )
        `when`(matchRepository.findById(10)).thenReturn(Optional.of(standardMatch))
        `when`(matchRepository.findTop500BySportOrderByStartTimeAsc("football")).thenReturn(listOf(standardMatch))
        `when`(matchRepository.save(any(OddsMatch::class.java))).thenAnswer { it.arguments[0] }

        val resolvedMatch = OddsStandardMatchService(
            matchRepository,
            linkRepository,
            platformMatchRepository
        ).resolveStandardMatch(
            OddsPlatformMatch(
                id = 20,
                sourceKey = "crown",
                sourceMatchId = "c1",
                rawLeagueName = "Japan J1 League",
                rawHomeTeam = "FC Tokyo",
                rawAwayTeam = "Kawasaki Frontale",
                rawStartTime = 1893456000000L,
                rawPayloadJson = """{"showtype":"rb","retimeset":"1H^12:34"}""",
                updatedAt = 5000L
            )
        )

        assertEquals(10, resolvedMatch.id)
        val captor = ArgumentCaptor.forClass(OddsMatch::class.java)
        verify(matchRepository).save(captor.capture())
        assertEquals("live", captor.value.status)
        assertEquals(1893456000000L, captor.value.startTime)
        assertTrue(captor.value.updatedAt > 1000L)
    }
}
