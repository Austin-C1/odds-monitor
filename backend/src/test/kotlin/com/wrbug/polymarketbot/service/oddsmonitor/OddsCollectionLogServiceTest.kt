package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.entity.OddsCollectionLog
import com.wrbug.polymarketbot.repository.OddsCollectionLogRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class OddsCollectionLogServiceTest {
    @Test
    fun `lists latest collection logs with repaired readable text`() {
        val repository = mock(OddsCollectionLogRepository::class.java)
        `when`(repository.findTop200ByOrderByStartedAtDesc()).thenReturn(
            listOf(
                OddsCollectionLog(
                    id = 5,
                    sourceKey = "crown",
                    status = "failed_runtime",
                    message = "璧旂巼鍙樺姩",
                    startedAt = 100,
                    finishedAt = 200,
                    recordsCount = 3,
                    matchCount = 2,
                    marketCount = 1,
                    emptyMarketCount = 0,
                    failureReason = "鐩樺彛"
                )
            )
        )

        val log = OddsCollectionLogService(repository).listLogs().single()

        assertEquals(5, log.id)
        assertEquals("crown", log.sourceKey)
        assertEquals("赔率变动", log.message)
        assertEquals("盘口", log.failureReason)
        assertEquals(3, log.recordsCount)
    }
}
