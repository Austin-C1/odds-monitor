package com.wrbug.polymarketbot.service.oddsmonitor.collector.crown

import com.wrbug.polymarketbot.repository.OddsDataSourceConfigRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

@Component
class CrownCollectionScheduler(
    private val dataSourceConfigRepository: OddsDataSourceConfigRepository,
    private val collector: CrownCollector
) {
    private val logger = LoggerFactory.getLogger(CrownCollectionScheduler::class.java)
    private val running = AtomicBoolean(false)
    private var lastRunAt = 0L

    @Scheduled(fixedDelay = 10_000)
    fun tick() {
        val config = dataSourceConfigRepository.findBySourceKey(CrownCollector.SOURCE_KEY) ?: return
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
                logger.warn("Crown collection failed: {}", result.status)
            }
        } finally {
            running.set(false)
        }
    }
}
