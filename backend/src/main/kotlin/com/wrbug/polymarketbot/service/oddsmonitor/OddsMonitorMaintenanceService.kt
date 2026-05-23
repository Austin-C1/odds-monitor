package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.repository.OddsAlertRecordRepository
import com.wrbug.polymarketbot.repository.OddsCollectionLogRepository
import com.wrbug.polymarketbot.repository.OddsSnapshotRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicBoolean

data class OddsMonitorCleanupResult(
    val deletedSnapshots: Int,
    val deletedCollectionLogs: Int,
    val deletedBrokenAlertRecords: Int,
    val deletedAlertRecords: Int = 0
) {
    val hasDeletes: Boolean
        get() = deletedSnapshots > 0 ||
            deletedCollectionLogs > 0 ||
            deletedBrokenAlertRecords > 0 ||
            deletedAlertRecords > 0
}

@Service
class OddsMonitorMaintenanceService(
    private val snapshotRepository: OddsSnapshotRepository,
    private val collectionLogRepository: OddsCollectionLogRepository,
    private val alertRecordRepository: OddsAlertRecordRepository
) {
    private val logger = LoggerFactory.getLogger(OddsMonitorMaintenanceService::class.java)
    private val running = AtomicBoolean(false)

    @Scheduled(
        initialDelay = CLEANUP_INITIAL_DELAY_MILLIS,
        fixedDelay = CLEANUP_FIXED_DELAY_MILLIS
    )
    fun scheduledCleanup() {
        if (!running.compareAndSet(false, true)) {
            return
        }

        try {
            val result = cleanup()
            if (result.hasDeletes) {
                logger.info(
                    "Odds monitor cleanup deleted snapshots={}, collectionLogs={}, brokenAlertRecords={}, alertRecords={}",
                    result.deletedSnapshots,
                    result.deletedCollectionLogs,
                    result.deletedBrokenAlertRecords,
                    result.deletedAlertRecords
                )
            }
        } catch (ex: Exception) {
            logger.warn("Odds monitor cleanup failed", ex)
        } finally {
            running.set(false)
        }
    }

    fun cleanup(
        now: Long = System.currentTimeMillis(),
        batchSize: Int = DEFAULT_BATCH_SIZE,
        maxBatches: Int = DEFAULT_MAX_BATCHES,
        includeVisibleHistory: Boolean = false
    ): OddsMonitorCleanupResult {
        val safeBatchSize = batchSize.coerceIn(1, DEFAULT_BATCH_SIZE)
        val safeMaxBatches = maxBatches.coerceAtLeast(1)
        val snapshotCutoff = now - SNAPSHOT_RETENTION_MILLIS
        val collectionLogCutoff = now - COLLECTION_LOG_RETENTION_MILLIS

        val deletedSnapshots = deleteInBatches(safeMaxBatches, safeBatchSize) {
            snapshotRepository.deleteBatchOlderThan(snapshotCutoff, safeBatchSize)
        }
        val deletedCollectionLogs = if (includeVisibleHistory) {
            deleteInBatches(safeMaxBatches, safeBatchSize) {
                collectionLogRepository.deleteBatchAll(safeBatchSize)
            }
        } else {
            deleteInBatches(safeMaxBatches, safeBatchSize) {
                collectionLogRepository.deleteBatchOlderThan(collectionLogCutoff, safeBatchSize)
            }
        }
        val deletedAlertRecords = if (includeVisibleHistory) {
            deleteInBatches(safeMaxBatches, safeBatchSize) {
                alertRecordRepository.deleteBatchAll(safeBatchSize)
            }
        } else {
            0
        }
        val deletedBrokenAlertRecords = if (includeVisibleHistory) {
            0
        } else {
            alertRecordRepository.deleteLegacyBrokenTemplateRecords()
        }

        return OddsMonitorCleanupResult(
            deletedSnapshots = deletedSnapshots,
            deletedCollectionLogs = deletedCollectionLogs,
            deletedBrokenAlertRecords = deletedBrokenAlertRecords,
            deletedAlertRecords = deletedAlertRecords
        )
    }

    fun manualCleanup(): OddsMonitorCleanupResult {
        return manualCleanup(System.currentTimeMillis())
    }

    fun manualCleanup(now: Long): OddsMonitorCleanupResult {
        return cleanup(now = now, maxBatches = MANUAL_CLEANUP_MAX_BATCHES, includeVisibleHistory = true)
    }

    private fun deleteInBatches(maxBatches: Int, batchSize: Int, deleteBatch: () -> Int): Int {
        var total = 0
        repeat(maxBatches) {
            val deleted = deleteBatch()
            total += deleted
            if (deleted < batchSize) {
                return total
            }
        }
        return total
    }

    companion object {
        const val SNAPSHOT_RETENTION_MILLIS = 24 * 60 * 60 * 1000L
        const val COLLECTION_LOG_RETENTION_MILLIS = 7 * 24 * 60 * 60 * 1000L
        private const val DEFAULT_BATCH_SIZE = 5_000
        private const val DEFAULT_MAX_BATCHES = 20
        const val MANUAL_CLEANUP_MAX_BATCHES = 250
        private const val CLEANUP_INITIAL_DELAY_MILLIS = 5 * 60 * 1000L
        private const val CLEANUP_FIXED_DELAY_MILLIS = 60 * 60 * 1000L
    }
}
