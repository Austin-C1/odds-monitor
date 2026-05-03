package com.wrbug.polymarketbot.service.copytrading.statistics

import com.github.benmanes.caffeine.cache.Caffeine
import com.wrbug.polymarketbot.repository.CopyOrderTrackingRepository
import com.wrbug.polymarketbot.repository.SellMatchRecordRepository
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

data class CopyTradingDailyMetrics(
    val todayBuyOrderCount: Int,
    val todaySettledRealizedPnl: BigDecimal
)

@Service
class CopyTradingDailyMetricsService(
    private val copyOrderTrackingRepository: CopyOrderTrackingRepository,
    private val sellMatchRecordRepository: SellMatchRecordRepository
) {
    private data class MetricsCacheKey(
        val copyTradingId: Long,
        val dayStart: Long
    )

    private val metricsCache = Caffeine.newBuilder()
        .maximumSize(2_000)
        .expireAfterWrite(10, TimeUnit.SECONDS)
        .build<MetricsCacheKey, CopyTradingDailyMetrics>()

    fun getMetrics(copyTradingId: Long, dayStart: Long): CopyTradingDailyMetrics {
        val cacheKey = MetricsCacheKey(copyTradingId = copyTradingId, dayStart = dayStart)
        return metricsCache.get(cacheKey) {
            loadMetrics(copyTradingId = copyTradingId, dayStart = dayStart)
        } ?: loadMetrics(copyTradingId = copyTradingId, dayStart = dayStart)
    }

    fun invalidate(copyTradingId: Long) {
        metricsCache.asMap().keys.removeIf { it.copyTradingId == copyTradingId }
    }

    private fun loadMetrics(copyTradingId: Long, dayStart: Long): CopyTradingDailyMetrics {
        return CopyTradingDailyMetrics(
            todayBuyOrderCount = copyOrderTrackingRepository.countByCopyTradingIdAndCreatedAtGreaterThanEqual(
                copyTradingId,
                dayStart
            ),
            todaySettledRealizedPnl = sellMatchRecordRepository
                .sumSettledRealizedPnlByCopyTradingIdAndCreatedAtGreaterThanEqual(copyTradingId, dayStart)
                ?: BigDecimal.ZERO
        )
    }
}
