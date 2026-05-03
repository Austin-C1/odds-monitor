package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.entity.OddsMatch
import com.wrbug.polymarketbot.entity.OddsMatchLink
import com.wrbug.polymarketbot.entity.OddsPlatformMatch
import com.wrbug.polymarketbot.repository.OddsMatchLinkRepository
import com.wrbug.polymarketbot.repository.OddsMatchRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class OddsStandardMatchServiceTest {
    @Test
    fun `links translated platform match to existing standard match`() {
        val matchRepository = mock(OddsMatchRepository::class.java)
        val linkRepository = mock(OddsMatchLinkRepository::class.java)
        `when`(linkRepository.findByPlatformMatchId(20)).thenReturn(null)
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
        `when`(linkRepository.save(any(OddsMatchLink::class.java))).thenAnswer { it.arguments[0] }

        val standardMatch = OddsStandardMatchService(matchRepository, linkRepository).resolveStandardMatch(
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
        `when`(linkRepository.findByPlatformMatchId(21)).thenReturn(null)
        `when`(matchRepository.findTop500BySportOrderByStartTimeAsc("football")).thenReturn(emptyList())
        `when`(
            matchRepository.save(any(OddsMatch::class.java))
        ).thenAnswer {
            (it.arguments[0] as OddsMatch).copy(id = 30)
        }
        `when`(linkRepository.save(any(OddsMatchLink::class.java))).thenAnswer { it.arguments[0] }

        val standardMatch = OddsStandardMatchService(matchRepository, linkRepository).resolveStandardMatch(
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
}
