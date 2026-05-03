package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.repository.CopyOrderTrackingRepository
import com.wrbug.polymarketbot.repository.SellMatchRecordRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import java.math.BigDecimal

class CopyTradingDailyMetricsServiceTest {

    private val copyOrderTrackingRepository = mock(CopyOrderTrackingRepository::class.java)
    private val sellMatchRecordRepository = mock(SellMatchRecordRepository::class.java)

    private val service = CopyTradingDailyMetricsService(
        copyOrderTrackingRepository = copyOrderTrackingRepository,
        sellMatchRecordRepository = sellMatchRecordRepository
    )

    @Test
    fun `loads daily order count and settled pnl directly from repositories`() {
        `when`(copyOrderTrackingRepository.countByCopyTradingIdAndCreatedAtGreaterThanEqual(9L, 1_000L))
            .thenReturn(6)
        `when`(
            sellMatchRecordRepository.sumSettledRealizedPnlByCopyTradingIdAndCreatedAtGreaterThanEqual(
                copyTradingId = 9L,
                createdAt = 1_000L
            )
        ).thenReturn(BigDecimal("-12.34"))

        val metrics = service.getMetrics(copyTradingId = 9L, dayStart = 1_000L)

        assertEquals(6, metrics.todayBuyOrderCount)
        assertEquals(BigDecimal("-12.34"), metrics.todaySettledRealizedPnl)
    }

    @Test
    fun `reuses cached metrics within ttl and refreshes after invalidation`() {
        `when`(copyOrderTrackingRepository.countByCopyTradingIdAndCreatedAtGreaterThanEqual(9L, 1_000L))
            .thenReturn(6, 7)
        `when`(
            sellMatchRecordRepository.sumSettledRealizedPnlByCopyTradingIdAndCreatedAtGreaterThanEqual(
                copyTradingId = 9L,
                createdAt = 1_000L
            )
        ).thenReturn(BigDecimal("-12.34"), BigDecimal("-20.00"))

        val first = service.getMetrics(copyTradingId = 9L, dayStart = 1_000L)
        val second = service.getMetrics(copyTradingId = 9L, dayStart = 1_000L)
        service.invalidate(9L)
        val refreshed = service.getMetrics(copyTradingId = 9L, dayStart = 1_000L)

        assertEquals(first, second)
        assertEquals(7, refreshed.todayBuyOrderCount)
        assertEquals(BigDecimal("-20.00"), refreshed.todaySettledRealizedPnl)
        verify(copyOrderTrackingRepository, times(2))
            .countByCopyTradingIdAndCreatedAtGreaterThanEqual(9L, 1_000L)
        verify(sellMatchRecordRepository, times(2))
            .sumSettledRealizedPnlByCopyTradingIdAndCreatedAtGreaterThanEqual(9L, 1_000L)
    }
}
