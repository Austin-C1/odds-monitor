package com.wrbug.polymarketbot.service.oddsmonitor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class OddsMatchMatcherTest {
    @Test
    fun `matches translated team names with close start times`() {
        val candidate = OddsMatchCandidate(
            id = 1,
            leagueName = "Japan J1 League",
            homeTeam = "Kawasaki Frontale",
            awayTeam = "FC Tokyo",
            startTime = 1893456000000L
        )
        val incoming = OddsMatchCandidate(
            id = null,
            leagueName = "日本J1",
            homeTeam = "川崎前锋",
            awayTeam = "东京",
            startTime = 1893456120000L
        )

        val result = OddsMatchMatcher.score(candidate, incoming)

        assertTrue(result.score >= 0.85, "score was ${result.score}")
        assertFalse(result.reversed)
        assertEquals("auto", result.matchMethod)
    }

    @Test
    fun `recognizes reversed home and away teams`() {
        val candidate = OddsMatchCandidate(
            id = 2,
            leagueName = "Japan J1 League",
            homeTeam = "Fagiano Okayama",
            awayTeam = "Sanfrecce Hiroshima",
            startTime = 1893456000000L
        )
        val incoming = OddsMatchCandidate(
            id = null,
            leagueName = "日本J1",
            homeTeam = "广岛三箭",
            awayTeam = "冈山绿雉",
            startTime = 1893456300000L
        )

        val result = OddsMatchMatcher.score(candidate, incoming)

        assertTrue(result.score >= 0.85, "score was ${result.score}")
        assertTrue(result.reversed)
        assertEquals("reverse_auto", result.matchMethod)
    }

    @Test
    fun `matches MLS Chinese and English team names within the same match day`() {
        val candidate = OddsMatchCandidate(
            id = 4,
            leagueName = "美国职业大联盟",
            homeTeam = "迈阿密国际",
            awayTeam = "奥兰多城",
            startTime = Instant.parse("2026-05-03T02:24:00Z").toEpochMilli()
        )
        val incoming = OddsMatchCandidate(
            id = null,
            leagueName = "Polymarket",
            homeTeam = "Inter Miami CF",
            awayTeam = "Orlando City SC",
            startTime = Instant.parse("2026-05-02T16:00:00Z").toEpochMilli()
        )

        val result = OddsMatchMatcher.score(candidate, incoming)

        assertTrue(result.score >= 0.65, "score was ${result.score}")
        assertFalse(result.reversed)
    }

    @Test
    fun `keeps unrelated matches separate`() {
        val candidate = OddsMatchCandidate(
            id = 3,
            leagueName = "Japan J1 League",
            homeTeam = "Kawasaki Frontale",
            awayTeam = "FC Tokyo",
            startTime = 1893456000000L
        )
        val incoming = OddsMatchCandidate(
            id = null,
            leagueName = "England Premier League",
            homeTeam = "Arsenal",
            awayTeam = "Chelsea",
            startTime = 1893542400000L
        )

        val result = OddsMatchMatcher.score(candidate, incoming)

        assertTrue(result.score < 0.65, "score was ${result.score}")
        assertEquals("new", result.matchMethod)
    }
}
