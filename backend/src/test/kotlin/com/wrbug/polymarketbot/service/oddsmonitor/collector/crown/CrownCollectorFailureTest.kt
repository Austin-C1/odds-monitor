package com.wrbug.polymarketbot.service.oddsmonitor.collector.crown

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.wrbug.polymarketbot.entity.OddsCollectionLog
import com.wrbug.polymarketbot.entity.OddsDataSourceConfig
import com.wrbug.polymarketbot.repository.OddsCollectionLogRepository
import com.wrbug.polymarketbot.repository.OddsDataSourceConfigRepository
import com.wrbug.polymarketbot.repository.OddsMarketRepository
import com.wrbug.polymarketbot.repository.OddsPlatformMatchRepository
import com.wrbug.polymarketbot.repository.OddsSnapshotRepository
import com.wrbug.polymarketbot.service.oddsmonitor.OddsChangeNotificationService
import com.wrbug.polymarketbot.service.oddsmonitor.OddsStandardMatchService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.net.SocketException
import java.nio.file.Path

class CrownCollectorFailureTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `records network failures instead of letting scheduled task keep stale success status`() {
        val config = OddsDataSourceConfig(
            sourceKey = "crown",
            displayName = "Crown",
            enabled = true,
            username = "alice",
            password = "secret",
            queryKeyword = "https://crown.example"
        )
        val configRepository = mock(OddsDataSourceConfigRepository::class.java)
        `when`(configRepository.findBySourceKey("crown")).thenReturn(config)
        val collectionLogRepository = mock(OddsCollectionLogRepository::class.java)
        val gateway = object : CrownMatchGateway {
            override fun login(config: OddsDataSourceConfig): CrownSession {
                return CrownSession(
                    uid = "fresh",
                    cookies = emptyMap(),
                    username = "alice",
                    baseUrl = "https://crown.example"
                )
            }

            override fun fetchMatchesWithSession(
                config: OddsDataSourceConfig,
                session: CrownSession
            ): CrownFetchResult {
                throw SocketException("Connection reset")
            }
        }

        val result = CrownCollector(
            dataSourceConfigRepository = configRepository,
            platformMatchRepository = mock(OddsPlatformMatchRepository::class.java),
            marketRepository = mock(OddsMarketRepository::class.java),
            snapshotRepository = mock(OddsSnapshotRepository::class.java),
            collectionLogRepository = collectionLogRepository,
            sessionManager = CrownSessionManager(CrownSessionStore(jacksonObjectMapper(), tempDir), gateway),
            mapper = CrownOddsMapper(),
            objectMapper = jacksonObjectMapper(),
            oddsChangeNotificationService = mock(OddsChangeNotificationService::class.java),
            standardMatchService = mock(OddsStandardMatchService::class.java)
        ).collectOnce()

        val captor = ArgumentCaptor.forClass(OddsCollectionLog::class.java)
        org.mockito.Mockito.verify(collectionLogRepository).save(captor.capture())
        val log = captor.value
        assertEquals("failed_network", result.status)
        assertEquals("failed_network", log.status)
        assertNotNull(log.startedAt)
        assertNotNull(log.finishedAt)
        assertEquals(0, log.recordsCount)
        assertEquals(log.message, log.failureReason)
    }
}
