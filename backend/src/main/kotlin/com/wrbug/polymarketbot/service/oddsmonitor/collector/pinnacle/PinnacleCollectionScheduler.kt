package com.wrbug.polymarketbot.service.oddsmonitor.collector.pinnacle

import com.wrbug.polymarketbot.repository.OddsDataSourceConfigRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

@Component
class PinnacleCollectionScheduler(
    private val dataSourceConfigRepository: OddsDataSourceConfigRepository,
    private val collector: PinnacleCollector
) {
    private val logger = LoggerFactory.getLogger(PinnacleCollectionScheduler::class.java)
    private val running = AtomicBoolean(false)
    private var lastRunAt = 0L

    @Scheduled(fixedDelay = 10_000)
    fun tick() {
        val config = dataSourceConfigRepository.findBySourceKey(PinnacleCollector.SOURCE_KEY) ?: return
        if (!config.enabled) {
            return
        }

        val now = System.currentTimeMillis()
        val intervalMillis = config.intervalSeconds.coerceAtLeast(10) * 1000L
        if (now - lastRunAt < intervalMillis) {
            return
        }
        if (!running.compareAndSet(false, true)) {
            return
        }

        try {
            lastRunAt = now
            val result = collector.collectOnce()
            if (result.status != "success" && result.status != "disabled") {
                logger.warn("Pinnacle collection failed: {}", result.status)
            }
        } finally {
            running.set(false)
        }
    }
}
