package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.entity.OddsCollectionLog
import com.wrbug.polymarketbot.entity.OddsDataSourceConfig
import com.wrbug.polymarketbot.entity.OddsAlertRecord
import com.wrbug.polymarketbot.dto.OddsDataSourceConfigDto
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
        val configRepository = mock(OddsDataSourceConfigRepository::class.java)
        val alertRepository = mock(OddsAlertRecordRepository::class.java)
        val logRepository = mock(OddsCollectionLogRepository::class.java)
        `when`(alertRepository.findTop100ByOrderByCreatedAtDesc()).thenReturn(
            listOf(
                OddsAlertRecord(
                    alertType = "odds_change",
                    severity = "info",
                    title = "赔率变动：{TextEncodingUtils.repairMojibake(pending.matchName)}",
                    message = """
                    <b>滚球赔率变动</b>
                    联赛：苏格兰超级联赛
                    比赛：邓迪联 vs 利文斯顿 进行：第 2 分钟 比分：0-0
                    盘口：{escapeHtml(TextEncodingUtils.repairMojibake(market.marketLabel))}
                    皇冠：{formatMergedOdds(change.previousOdds, change.sourceKey, market.marketType)} -> 1.82
                    筛选：动水通过 / 合水通过
                    时间：<code>2026-05-12 21:38:07</code>
                    """.trimIndent(),
                    createdAt = 100
                )
            )
        )

        val record = OddsMonitorService(configRepository, alertRepository, logRepository)
            .listAlertRecords()
            .first()

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
    fun `data source status reports typed pinnacle failure`() {
        val configRepository = mock(OddsDataSourceConfigRepository::class.java)
        val alertRepository = mock(OddsAlertRecordRepository::class.java)
        val logRepository = mock(OddsCollectionLogRepository::class.java)

        `when`(configRepository.findAll()).thenReturn(
            listOf(
                OddsDataSourceConfig(
                    sourceKey = "pinnacle",
                    displayName = "平博",
                    enabled = true,
                    intervalSeconds = 60,
                    createdAt = 1,
                    updatedAt = 1
                )
            )
        )
        `when`(logRepository.findTop1BySourceKeyOrderByStartedAtDesc("pinnacle")).thenReturn(
            OddsCollectionLog(
                sourceKey = "pinnacle",
                status = "failed_login",
                message = "login failed",
                startedAt = 100,
                finishedAt = 200,
                recordsCount = 0
            )
        )
        `when`(logRepository.findTop1BySourceKeyAndStatusOrderByStartedAtDesc("pinnacle", "success")).thenReturn(null)
        `when`(logRepository.findTop1FailureBySourceKey("pinnacle")).thenReturn(
            OddsCollectionLog(
                sourceKey = "pinnacle",
                status = "failed_login",
                message = "login failed",
                startedAt = 100,
                finishedAt = 200,
                recordsCount = 0
            )
        )

        val status = OddsMonitorService(configRepository, alertRepository, logRepository)
            .listDataSourceStatuses()
            .first { it.sourceKey == "pinnacle" }

        assertEquals("failed_login", status.currentStatus)
        assertEquals("login failed", status.failureReason)
        assertEquals(100, status.lastFailureTime)
    }

    @Test
    fun `data source status clears old failure reason after latest success`() {
        val configRepository = mock(OddsDataSourceConfigRepository::class.java)
        val alertRepository = mock(OddsAlertRecordRepository::class.java)
        val logRepository = mock(OddsCollectionLogRepository::class.java)

        `when`(configRepository.findAll()).thenReturn(
            listOf(
                OddsDataSourceConfig(
                    sourceKey = "pinnacle",
                    displayName = "平博",
                    enabled = true,
                    intervalSeconds = 60,
                    createdAt = 1,
                    updatedAt = 1
                )
            )
        )
        `when`(logRepository.findTop1BySourceKeyOrderByStartedAtDesc("pinnacle")).thenReturn(
            OddsCollectionLog(
                sourceKey = "pinnacle",
                status = "success",
                message = "collected 336 pinnacle odds rows",
                startedAt = 300,
                finishedAt = 400,
                recordsCount = 336
            )
        )
        `when`(logRepository.findTop1BySourceKeyAndStatusOrderByStartedAtDesc("pinnacle", "success")).thenReturn(
            OddsCollectionLog(
                sourceKey = "pinnacle",
                status = "success",
                message = "collected 336 pinnacle odds rows",
                startedAt = 300,
                finishedAt = 400,
                recordsCount = 336
            )
        )
        `when`(logRepository.findTop1FailureBySourceKey("pinnacle")).thenReturn(
            OddsCollectionLog(
                sourceKey = "pinnacle",
                status = "failed_login",
                message = "old login failed",
                startedAt = 100,
                finishedAt = 200,
                recordsCount = 0
            )
        )

        val status = OddsMonitorService(configRepository, alertRepository, logRepository)
            .listDataSourceStatuses()
            .first { it.sourceKey == "pinnacle" }

        assertEquals("success", status.currentStatus)
        assertEquals(null, status.failureReason)
        assertEquals(null, status.lastFailureTime)
    }

    @Test
    fun `data source configs and statuses repair mojibake display names`() {
        val configRepository = mock(OddsDataSourceConfigRepository::class.java)
        val alertRepository = mock(OddsAlertRecordRepository::class.java)
        val logRepository = mock(OddsCollectionLogRepository::class.java)

        `when`(configRepository.findAll()).thenReturn(
            listOf(
                OddsDataSourceConfig(
                    sourceKey = "crown",
                    displayName = "\u9428\u56E7\u555D",
                    enabled = true,
                    intervalSeconds = 60,
                    createdAt = 1,
                    updatedAt = 1
                )
            )
        )
        `when`(logRepository.findTop1BySourceKeyOrderByStartedAtDesc("crown")).thenReturn(null)
        `when`(logRepository.findTop1BySourceKeyAndStatusOrderByStartedAtDesc("crown", "success")).thenReturn(null)
        `when`(logRepository.findTop1FailureBySourceKey("crown")).thenReturn(null)

        val service = OddsMonitorService(configRepository, alertRepository, logRepository)

        assertEquals("皇冠", service.listDataSourceConfigs().first { it.sourceKey == "crown" }.displayName)
        assertEquals("皇冠", service.listDataSourceStatuses().first { it.sourceKey == "crown" }.displayName)
    }

    @Test
    fun `data source configs and statuses only expose pinnacle and crown`() {
        val configRepository = mock(OddsDataSourceConfigRepository::class.java)
        val alertRepository = mock(OddsAlertRecordRepository::class.java)
        val logRepository = mock(OddsCollectionLogRepository::class.java)

        `when`(configRepository.findAll()).thenReturn(
            listOf(
                OddsDataSourceConfig(sourceKey = "pinnacle", displayName = "平博", enabled = true),
                OddsDataSourceConfig(sourceKey = "crown", displayName = "皇冠", enabled = true),
                OddsDataSourceConfig(sourceKey = "polymarket", displayName = "Polymarket", enabled = true)
            )
        )
        listOf("pinnacle", "crown").forEach { sourceKey ->
            `when`(logRepository.findTop1BySourceKeyOrderByStartedAtDesc(sourceKey)).thenReturn(null)
            `when`(logRepository.findTop1BySourceKeyAndStatusOrderByStartedAtDesc(sourceKey, "success")).thenReturn(null)
            `when`(logRepository.findTop1FailureBySourceKey(sourceKey)).thenReturn(null)
        }

        val service = OddsMonitorService(configRepository, alertRepository, logRepository)

        assertEquals(listOf("pinnacle", "crown"), service.listDataSourceConfigs().map { it.sourceKey })
        assertEquals(listOf("pinnacle", "crown"), service.listDataSourceStatuses().map { it.sourceKey })
    }

    @Test
    fun `saving all data source configs preserves existing crown connection fields when form sends blanks`() {
        val configRepository = mock(OddsDataSourceConfigRepository::class.java)
        val alertRepository = mock(OddsAlertRecordRepository::class.java)
        val logRepository = mock(OddsCollectionLogRepository::class.java)
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
        val existingPinnacle = OddsDataSourceConfig(
            id = 11,
            sourceKey = "pinnacle",
            displayName = "pinnacle",
            enabled = true,
            username = "pin-user",
            password = "pin-pass",
            intervalSeconds = 60,
            createdAt = 1,
            updatedAt = 1
        )

        `when`(configRepository.findBySourceKey("crown")).thenReturn(existingCrown)
        `when`(configRepository.findBySourceKey("pinnacle")).thenReturn(existingPinnacle)
        `when`(configRepository.findAll()).thenReturn(listOf(existingCrown, existingPinnacle))

        OddsMonitorService(configRepository, alertRepository, logRepository).saveDataSourceConfigs(
            listOf(
                OddsDataSourceConfigDto(
                    sourceKey = "pinnacle",
                    displayName = "pinnacle",
                    enabled = false,
                    username = "pin-user",
                    password = "pin-pass",
                    intervalSeconds = 60,
                    updatedAt = 1
                ),
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
        org.mockito.Mockito.verify(configRepository, org.mockito.Mockito.times(2)).save(captor.capture())
        val savedCrown = captor.allValues.first { it.sourceKey == "crown" }
        assertEquals("crown-user", savedCrown.username)
        assertEquals("crown-pass", savedCrown.password)
        assertEquals("https://crown.example/", savedCrown.queryKeyword)
    }

    @Test
    fun `disabling a data source clears only that source notification state`() {
        val configRepository = mock(OddsDataSourceConfigRepository::class.java)
        val alertRepository = mock(OddsAlertRecordRepository::class.java)
        val logRepository = mock(OddsCollectionLogRepository::class.java)
        val notificationService = mock(OddsChangeNotificationService::class.java)
        val existingPinnacle = OddsDataSourceConfig(
            id = 11,
            sourceKey = "pinnacle",
            displayName = "pinnacle",
            enabled = true,
            username = "pin-user",
            password = "pin-pass",
            intervalSeconds = 60,
            createdAt = 1,
            updatedAt = 1
        )

        `when`(configRepository.findBySourceKey("pinnacle")).thenReturn(existingPinnacle)
        `when`(configRepository.findAll()).thenReturn(listOf(existingPinnacle.copy(enabled = false)))

        OddsMonitorService(
            configRepository,
            alertRepository,
            logRepository,
            oddsChangeNotificationService = notificationService
        ).saveDataSourceConfigs(
            listOf(
                OddsDataSourceConfigDto(
                    sourceKey = "pinnacle",
                    displayName = "pinnacle",
                    enabled = false,
                    username = "pin-user",
                    password = "pin-pass",
                    intervalSeconds = 60,
                    updatedAt = 1
                )
            )
        )

        org.mockito.Mockito.verify(notificationService).clearSourceState("pinnacle")
    }
}
