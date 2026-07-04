package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.entity.OddsAlertRecord
import com.wrbug.polymarketbot.repository.OddsAlertRecordRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class OddsAlertRecordServiceTest {
    @Test
    fun `list records repairs legacy template fragments for display`() {
        val repository = mock(OddsAlertRecordRepository::class.java)
        `when`(repository.findTop100ByOrderByCreatedAtDesc()).thenReturn(
            listOf(
                OddsAlertRecord(
                    id = 7,
                    alertType = "odds_change",
                    severity = "info",
                    matchId = 99,
                    sourceKey = "crown",
                    title = "赔率变动：TextEncodingUtils.repairMojibake(pending.matchName)",
                    message = """
                    <b>滚球赔率变动</b>
                    比赛：邓迪联 vs 利文斯顿
                    盘口：escapeHtml(TextEncodingUtils.repairMojibake(market.marketLabel))
                    时间：<code>2026-05-12 21:38:07</code>
                    """.trimIndent(),
                    createdAt = 100,
                    acknowledged = true
                )
            )
        )

        val record = OddsAlertRecordService(repository, OddsMonitorDisplayMapper()).listRecords().single()

        assertEquals(7, record.id)
        assertEquals("99", record.matchName)
        assertEquals("crown", record.sourceKey)
        assertEquals("赔率变动：邓迪联 vs 利文斯顿", record.title)
        assertEquals(true, record.acknowledged)
        assertFalse(record.message.contains("TextEncodingUtils"))
        assertFalse(record.message.contains("<code>"))
    }
}
