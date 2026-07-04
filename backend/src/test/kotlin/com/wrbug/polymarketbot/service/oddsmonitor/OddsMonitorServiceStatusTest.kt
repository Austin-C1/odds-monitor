package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.dto.OddsDataSourceConfigDto
import com.wrbug.polymarketbot.entity.OddsAlertRecord
import com.wrbug.polymarketbot.entity.OddsCollectionLog
import com.wrbug.polymarketbot.entity.OddsDataSourceConfig
import com.wrbug.polymarketbot.repository.OddsAlertRecordRepository
import com.wrbug.polymarketbot.repository.OddsCollectionLogRepository
import com.wrbug.polymarketbot.repository.OddsDataSourceConfigRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class OddsMonitorServiceStatusTest {
    @Test
    fun `alert records hide legacy template code fragments and telegram tags`() {
        val fixtures = Fixtures()
        `when`(fixtures.alertRepository.findTop100ByOrderByCreatedAtDesc()).thenReturn(
            listOf(
                OddsAlertRecord(
                    alertType = "odds_change",
                    severity = "info",
                    title = "赔率变动：TextEncodingUtils.repairMojibake(pending.matchName)",
                    message = """
                    <b>滚球赔率变动</b>
                    联赛：苏格兰超级联赛
                    比赛：邓迪联 vs 利文斯顿 进行：第 2 分钟 比分：0-0
                    盘口：escapeHtml(TextEncodingUtils.repairMojibake(market.marketLabel))
                    皇冠：formatMergedOdds(change.previousOdds, change.sourceKey, market.marketType) -> 1.82
                    筛选：动水通过 / 合水通过
                    时间：<code>2026-05-12 21:38:07</code>
                    """.trimIndent(),
                    createdAt = 100
                )
            )
        )

        val record = fixtures.service().listAlertRecords().first()

        assertEquals("赔率变动：邓迪联 vs 利文斯顿", record.title)
        assertTrue(record.message.contains("滚球赔率变动"))
        assertTrue(record.message.contains("联赛：苏格兰超级联赛"))
        assertTrue(record.message.contains("筛选：动水通过 / 合水通过"))
        assertTrue(record.message.contains("时间：2026-05-12 21:38:07"))
        assertFalse(record.message.contains("TextEncodingUtils"))
        assertFalse(record.message.contains("formatMergedOdds"))
        assertFalse(record.message.contains("{"))
        assertFalse(record.message.contains("<b>"))
        assertFalse(record.message.contains("<code>"))
    }

    @Test
    fun `data source status reports typed crown failure`() {
        val fixtures = Fixtures()
        `when`(fixtures.configRepository.findAll()).thenReturn(
            listOf(OddsDataSourceConfig(sourceKey = "crown", displayName = "皇冠", enabled = true))
        )
        `when`(fixtures.logRepository.findTop1BySourceKeyOrderByStartedAtDesc("crown")).thenReturn(
            OddsCollectionLog(sourceKey = "crown", status = "failed_login", message = "login failed", startedAt = 100, finishedAt = 200)
        )
        `when`(fixtures.logRepository.findTop1BySourceKeyAndStatusOrderByStartedAtDesc("crown", "success")).thenReturn(null)
        `when`(fixtures.logRepository.findTop1FailureBySourceKey("crown")).thenReturn(
            OddsCollectionLog(sourceKey = "crown", status = "failed_login", message = "login failed", startedAt = 100, finishedAt = 200)
        )

        val status = fixtures.service().listDataSourceStatuses().single()

        assertEquals("crown", status.sourceKey)
        assertEquals("failed_login", status.currentStatus)
        assertEquals("login failed", status.failureReason)
        assertEquals(100, status.lastFailureTime)
    }

    @Test
    fun `data source status clears old failure reason after latest success`() {
        val fixtures = Fixtures()
        `when`(fixtures.configRepository.findAll()).thenReturn(
            listOf(OddsDataSourceConfig(sourceKey = "crown", displayName = "皇冠", enabled = true))
        )
        `when`(fixtures.logRepository.findTop1BySourceKeyOrderByStartedAtDesc("crown")).thenReturn(
            OddsCollectionLog(sourceKey = "crown", status = "success", message = "collected 336 crown odds rows", startedAt = 300, finishedAt = 400, recordsCount = 336)
        )
        `when`(fixtures.logRepository.findTop1BySourceKeyAndStatusOrderByStartedAtDesc("crown", "success")).thenReturn(
            OddsCollectionLog(sourceKey = "crown", status = "success", message = "collected 336 crown odds rows", startedAt = 300, finishedAt = 400, recordsCount = 336)
        )
        `when`(fixtures.logRepository.findTop1FailureBySourceKey("crown")).thenReturn(
            OddsCollectionLog(sourceKey = "crown", status = "failed_login", message = "old login failed", startedAt = 100, finishedAt = 200)
        )

        val status = fixtures.service().listDataSourceStatuses().single()

        assertEquals("success", status.currentStatus)
        assertEquals(null, status.failureReason)
        assertEquals(null, status.lastFailureTime)
    }

    @Test
    fun `data source configs and statuses only expose crown`() {
        val fixtures = Fixtures()
        `when`(fixtures.configRepository.findAll()).thenReturn(
            listOf(
                OddsDataSourceConfig(sourceKey = "crown", displayName = "皇冠", enabled = true),
                OddsDataSourceConfig(sourceKey = "polymarket", displayName = "Polymarket", enabled = true)
            )
        )
        `when`(fixtures.logRepository.findTop1BySourceKeyOrderByStartedAtDesc("crown")).thenReturn(null)
        `when`(fixtures.logRepository.findTop1BySourceKeyAndStatusOrderByStartedAtDesc("crown", "success")).thenReturn(null)
        `when`(fixtures.logRepository.findTop1FailureBySourceKey("crown")).thenReturn(null)

        val service = fixtures.service()

        assertEquals(listOf("crown"), service.listDataSourceConfigs().map { it.sourceKey })
        assertEquals(listOf("crown"), service.listDataSourceStatuses().map { it.sourceKey })
    }

    @Test
    fun `saving crown data source config preserves existing connection fields when form sends blanks`() {
        val fixtures = Fixtures()
        val existingCrown = OddsDataSourceConfig(
            id = 10,
            sourceKey = "crown",
            displayName = "crown",
            enabled = true,
            username = "crown-user",
            password = "crown-pass",
            queryKeyword = "https://crown.example/",
            intervalSeconds = 60,
            createdAt = 1,
            updatedAt = 1
        )

        `when`(fixtures.configRepository.findBySourceKey("crown")).thenReturn(existingCrown)
        `when`(fixtures.configRepository.findAll()).thenReturn(listOf(existingCrown))

        fixtures.service().saveDataSourceConfigs(
            listOf(
                OddsDataSourceConfigDto(
                    sourceKey = "crown",
                    displayName = "crown",
                    enabled = true,
                    username = "",
                    password = "",
                    queryKeyword = "",
                    intervalSeconds = 60,
                    updatedAt = 1
                )
            )
        )

        val captor = ArgumentCaptor.forClass(OddsDataSourceConfig::class.java)
        org.mockito.Mockito.verify(fixtures.configRepository).save(captor.capture())
        val savedCrown = captor.value
        assertEquals("crown-user", savedCrown.username)
        assertEquals("crown-pass", savedCrown.password)
        assertEquals("https://crown.example/", savedCrown.queryKeyword)
    }

    @Test
    fun `disabling crown data source clears crown notification state`() {
        val fixtures = Fixtures()
        val notificationService = mock(OddsChangeNotificationService::class.java)
        val existingCrown = OddsDataSourceConfig(
            id = 11,
            sourceKey = "crown",
            displayName = "crown",
            enabled = true,
            username = "crown-user",
            password = "crown-pass",
            intervalSeconds = 60,
            createdAt = 1,
            updatedAt = 1
        )

        `when`(fixtures.configRepository.findBySourceKey("crown")).thenReturn(existingCrown)
        `when`(fixtures.configRepository.findAll()).thenReturn(listOf(existingCrown.copy(enabled = false)))

        OddsMonitorService(
            fixtures.configRepository,
            fixtures.alertRepository,
            fixtures.logRepository,
            oddsChangeNotificationService = notificationService
        ).saveDataSourceConfigs(
            listOf(
                OddsDataSourceConfigDto(
                    sourceKey = "crown",
                    displayName = "crown",
                    enabled = false,
                    username = "crown-user",
                    password = "crown-pass",
                    intervalSeconds = 60,
                    updatedAt = 1
                )
            )
        )

        org.mockito.Mockito.verify(notificationService).clearSourceState("crown")
    }

    private class Fixtures {
        val configRepository: OddsDataSourceConfigRepository = mock(OddsDataSourceConfigRepository::class.java)
        val alertRepository: OddsAlertRecordRepository = mock(OddsAlertRecordRepository::class.java)
        val logRepository: OddsCollectionLogRepository = mock(OddsCollectionLogRepository::class.java)

        fun service(): OddsMonitorService {
            return OddsMonitorService(configRepository, alertRepository, logRepository)
        }
    }
}
