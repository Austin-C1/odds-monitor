package com.wrbug.polymarketbot.service.oddsmonitor.collector.crown

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.wrbug.polymarketbot.entity.OddsDataSourceConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class CrownSessionManagerTest {
    @TempDir
    lateinit var tempDir: Path

    private val config = OddsDataSourceConfig(
        sourceKey = "crown",
        displayName = "Crown",
        enabled = true,
        username = "alice",
        password = "secret",
        queryKeyword = "https://crown.example"
    )

    @Test
    fun `uses cached crown session without logging in again`() {
        val store = CrownSessionStore(jacksonObjectMapper(), tempDir)
        val cachedSession = CrownSession(
            uid = "cached",
            cookies = mapOf("SESSION" to "old"),
            username = "alice",
            baseUrl = "https://crown.example",
            savedAt = 1000L
        )
        store.save(config, cachedSession)
        val gateway = FakeCrownGateway().apply {
            fetchResults += CrownFetchResult(listOf(sampleMatch()), cachedSession.copy(cookies = mapOf("SESSION" to "new")))
        }

        val matches = CrownSessionManager(store, gateway).fetchMatches(config)

        assertEquals(1, matches.size)
        assertEquals(0, gateway.loginCount)
        assertEquals(listOf("cached"), gateway.fetchedSessionUids)
        assertEquals(mapOf("SESSION" to "new"), store.load(config)?.cookies)
    }

    @Test
    fun `logs in once when cached crown session is expired`() {
        val store = CrownSessionStore(jacksonObjectMapper(), tempDir)
        val cachedSession = CrownSession(
            uid = "cached",
            cookies = mapOf("SESSION" to "old"),
            username = "alice",
            baseUrl = "https://crown.example",
            savedAt = 1000L
        )
        val freshSession = CrownSession(
            uid = "fresh",
            cookies = mapOf("SESSION" to "fresh"),
            username = "alice",
            baseUrl = "https://crown.example",
            savedAt = 2000L
        )
        store.save(config, cachedSession)
        val gateway = FakeCrownGateway().apply {
            loginSession = freshSession
            fetchExceptions += CrownCollectionException("failed_login", "expired")
            fetchResults += CrownFetchResult(listOf(sampleMatch()), freshSession)
        }

        val matches = CrownSessionManager(store, gateway).fetchMatches(config)

        assertEquals(1, matches.size)
        assertEquals(1, gateway.loginCount)
        assertEquals(listOf("cached", "fresh"), gateway.fetchedSessionUids)
        assertEquals("fresh", store.load(config)?.uid)
    }

    @Test
    fun `logs in once when no crown session is cached`() {
        val store = CrownSessionStore(jacksonObjectMapper(), tempDir)
        val freshSession = CrownSession(
            uid = "fresh",
            cookies = mapOf("SESSION" to "fresh"),
            username = "alice",
            baseUrl = "https://crown.example",
            savedAt = 2000L
        )
        val gateway = FakeCrownGateway().apply {
            loginSession = freshSession
            fetchResults += CrownFetchResult(listOf(sampleMatch()), freshSession)
        }

        val matches = CrownSessionManager(store, gateway).fetchMatches(config)

        assertEquals(1, matches.size)
        assertEquals(1, gateway.loginCount)
        assertEquals(listOf("fresh"), gateway.fetchedSessionUids)
        assertEquals("fresh", store.load(config)?.uid)
    }

    private class FakeCrownGateway : CrownMatchGateway {
        var loginCount = 0
        var loginSession = CrownSession(
            uid = "login",
            cookies = emptyMap(),
            username = "alice",
            baseUrl = "https://crown.example"
        )
        val fetchResults = ArrayDeque<CrownFetchResult>()
        val fetchExceptions = ArrayDeque<CrownCollectionException>()
        val fetchedSessionUids = mutableListOf<String>()

        override fun login(config: OddsDataSourceConfig): CrownSession {
            loginCount += 1
            return loginSession
        }

        override fun fetchMatchesWithSession(config: OddsDataSourceConfig, session: CrownSession): CrownFetchResult {
            fetchedSessionUids += session.uid
            if (fetchExceptions.isNotEmpty()) {
                throw fetchExceptions.removeFirst()
            }
            return fetchResults.removeFirst()
        }
    }

    private fun sampleMatch() = CrownFootballMatch(
        sourceMatchId = "41001",
        leagueName = "England Premier League",
        homeTeam = "Arsenal",
        awayTeam = "Chelsea",
        startTime = 1000L,
        isLive = false,
        handicaps = emptyList(),
        totals = emptyList(),
        moneyline = null,
        rawPayload = emptyMap()
    )
}
