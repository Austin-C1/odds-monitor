package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.repository.ProcessedTradeRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ProcessedTradeCleanupService(
    private val processedTradeRepository: ProcessedTradeRepository
) {

    companion object {
        private val logger = LoggerFactory.getLogger(ProcessedTradeCleanupService::class.java)
        private const val RETENTION_MS = 600_000L
        private const val CLEANUP_INTERVAL_MS = 600_000L
    }

    @Scheduled(fixedDelay = CLEANUP_INTERVAL_MS)
    @Transactional
    fun cleanupExpiredProcessedTrades() {
        try {
            val expireTime = System.currentTimeMillis() - RETENTION_MS
            val deletedCount = processedTradeRepository.deleteByProcessedAtBefore(expireTime)

            if (deletedCount > 0) {
                logger.info("清理过期已处理交易记录: deletedCount=$deletedCount, expireTime=$expireTime")
            }
        } catch (e: Exception) {
            logger.error("清理过期已处理交易记录失败", e)
        }
    }
}

