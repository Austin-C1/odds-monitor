package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.repository.AutoBettingIntentRepository
import com.wrbug.polymarketbot.repository.OddsAlertRecordRepository
import com.wrbug.polymarketbot.repository.OddsCollectionLogRepository
import com.wrbug.polymarketbot.repository.OddsSnapshotRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class OddsMonitorMaintenanceServiceTest {
    @Test
    fun `cleanup prunes cache and broken alert data in bounded batches`() {
        val snapshotRepository = mock(OddsSnapshotRepository::class.java)
        val collectionLogRepository = mock(OddsCollectionLogRepository::class.java)
        val alertRecordRepository = mock(OddsAlertRecordRepository::class.java)
        val autoBettingIntentRepository = mock(AutoBettingIntentRepository::class.java)
        val now = 1_779_203_200_000L
        val batchSize = 5_000

        `when`(
            snapshotRepository.deleteBatchOlderThan(
                now - OddsMonitorMaintenanceService.SNAPSHOT_RETENTION_MILLIS,
                batchSize
            )
        ).thenReturn(batchSize, 12)
        `when`(
            collectionLogRepository.deleteBatchOlderThan(
                now - OddsMonitorMaintenanceService.COLLECTION_LOG_RETENTION_MILLIS,
                batchSize
            )
        ).thenReturn(8)
        `when`(alertRecordRepository.deleteLegacyBrokenTemplateRecords()).thenReturn(7)

        val result = OddsMonitorMaintenanceService(
            snapshotRepository,
            collectionLogRepository,
            alertRecordRepository,
            autoBettingIntentRepository
        ).cleanup(now = now, batchSize = batchSize, maxBatches = 10)

        assertEquals(5_012, result.deletedSnapshots)
        assertEquals(8, result.deletedCollectionLogs)
        assertEquals(7, result.deletedBrokenAlertRecords)
        assertEquals(0, result.deletedVerifiedPlacedIntents)
        assertEquals(0, result.deletedRejectedIntents)
        verify(snapshotRepository, times(2)).deleteBatchOlderThan(
            now - OddsMonitorMaintenanceService.SNAPSHOT_RETENTION_MILLIS,
            batchSize
        )
        verify(collectionLogRepository).deleteBatchOlderThan(
            now - OddsMonitorMaintenanceService.COLLECTION_LOG_RETENTION_MILLIS,
            batchSize
        )
        verify(alertRecordRepository).deleteLegacyBrokenTemplateRecords()
        verify(autoBettingIntentRepository, never()).deleteBatchVerifiedPlacedIntents(batchSize)
        verify(autoBettingIntentRepository, never()).deleteBatchRejectedIntents(batchSize)
    }

    @Test
    fun `cleanup stops when max batches is reached`() {
        val snapshotRepository = mock(OddsSnapshotRepository::class.java)
        val collectionLogRepository = mock(OddsCollectionLogRepository::class.java)
        val alertRecordRepository = mock(OddsAlertRecordRepository::class.java)
        val autoBettingIntentRepository = mock(AutoBettingIntentRepository::class.java)
        val now = 1_779_203_200_000L
        val batchSize = 5_000

        `when`(
            snapshotRepository.deleteBatchOlderThan(
                now - OddsMonitorMaintenanceService.SNAPSHOT_RETENTION_MILLIS,
                batchSize
            )
        ).thenReturn(batchSize, batchSize, batchSize, 1)
        `when`(
            collectionLogRepository.deleteBatchOlderThan(
                now - OddsMonitorMaintenanceService.COLLECTION_LOG_RETENTION_MILLIS,
                batchSize
            )
        ).thenReturn(0)
        `when`(alertRecordRepository.deleteLegacyBrokenTemplateRecords()).thenReturn(0)

        val result = OddsMonitorMaintenanceService(
            snapshotRepository,
            collectionLogRepository,
            alertRecordRepository,
            autoBettingIntentRepository
        ).cleanup(now = now, batchSize = batchSize, maxBatches = 3)

        assertEquals(15_000, result.deletedSnapshots)
        assertEquals(0, result.deletedCollectionLogs)
        assertEquals(0, result.deletedBrokenAlertRecords)
        assertEquals(0, result.deletedVerifiedPlacedIntents)
        assertEquals(0, result.deletedRejectedIntents)
        verify(snapshotRepository, times(3)).deleteBatchOlderThan(
            now - OddsMonitorMaintenanceService.SNAPSHOT_RETENTION_MILLIS,
            batchSize
        )
        verify(autoBettingIntentRepository, never()).deleteBatchVerifiedPlacedIntents(batchSize)
        verify(autoBettingIntentRepository, never()).deleteBatchRejectedIntents(batchSize)
    }

    @Test
    fun `manual cleanup allows a larger bounded cleanup pass`() {
        val snapshotRepository = mock(OddsSnapshotRepository::class.java)
        val collectionLogRepository = mock(OddsCollectionLogRepository::class.java)
        val alertRecordRepository = mock(OddsAlertRecordRepository::class.java)
        val autoBettingIntentRepository = mock(AutoBettingIntentRepository::class.java)
        val now = 1_779_203_200_000L
        val batchSize = 5_000

        `when`(
            snapshotRepository.deleteBatchOlderThan(
                now - OddsMonitorMaintenanceService.SNAPSHOT_RETENTION_MILLIS,
                batchSize
            )
        ).thenReturn(batchSize, batchSize, 0)
        `when`(
            collectionLogRepository.deleteBatchOlderThan(
                now - OddsMonitorMaintenanceService.COLLECTION_LOG_RETENTION_MILLIS,
                batchSize
            )
        ).thenReturn(0)
        `when`(alertRecordRepository.deleteLegacyBrokenTemplateRecords()).thenReturn(0)
        `when`(autoBettingIntentRepository.deleteBatchVerifiedPlacedIntents(batchSize)).thenReturn(0)
        `when`(autoBettingIntentRepository.deleteBatchRejectedIntents(batchSize)).thenReturn(0)

        val result = OddsMonitorMaintenanceService(
            snapshotRepository,
            collectionLogRepository,
            alertRecordRepository,
            autoBettingIntentRepository
        ).manualCleanup(now = now)

        assertEquals(10_000, result.deletedSnapshots)
        assertEquals(0, result.deletedVerifiedPlacedIntents)
        assertEquals(0, result.deletedRejectedIntents)
        verify(snapshotRepository, times(3)).deleteBatchOlderThan(
            now - OddsMonitorMaintenanceService.SNAPSHOT_RETENTION_MILLIS,
            batchSize
        )
        verify(autoBettingIntentRepository).deleteBatchVerifiedPlacedIntents(batchSize)
        verify(autoBettingIntentRepository).deleteBatchRejectedIntents(batchSize)
    }

    @Test
    fun `manual cleanup clears visible alert records and runtime logs`() {
        val snapshotRepository = mock(OddsSnapshotRepository::class.java)
        val collectionLogRepository = mock(OddsCollectionLogRepository::class.java)
        val alertRecordRepository = mock(OddsAlertRecordRepository::class.java)
        val autoBettingIntentRepository = mock(AutoBettingIntentRepository::class.java)
        val now = 1_779_203_200_000L
        val batchSize = 5_000

        `when`(
            snapshotRepository.deleteBatchOlderThan(
                now - OddsMonitorMaintenanceService.SNAPSHOT_RETENTION_MILLIS,
                batchSize
            )
        ).thenReturn(0)
        `when`(collectionLogRepository.deleteBatchAll(batchSize)).thenReturn(batchSize, 17)
        `when`(alertRecordRepository.deleteBatchAll(batchSize)).thenReturn(23)
        `when`(autoBettingIntentRepository.deleteBatchVerifiedPlacedIntents(batchSize)).thenReturn(0)
        `when`(autoBettingIntentRepository.deleteBatchRejectedIntents(batchSize)).thenReturn(0)

        val result = OddsMonitorMaintenanceService(
            snapshotRepository,
            collectionLogRepository,
            alertRecordRepository,
            autoBettingIntentRepository
        ).manualCleanup(now = now)

        assertEquals(5_017, result.deletedCollectionLogs)
        assertEquals(23, result.deletedAlertRecords)
        assertEquals(0, result.deletedBrokenAlertRecords)
        assertEquals(0, result.deletedVerifiedPlacedIntents)
        assertEquals(0, result.deletedRejectedIntents)
        verify(collectionLogRepository, times(2)).deleteBatchAll(batchSize)
        verify(alertRecordRepository).deleteBatchAll(batchSize)
    }

    @Test
    fun `manual cleanup clears verified placed betting history`() {
        val snapshotRepository = mock(OddsSnapshotRepository::class.java)
        val collectionLogRepository = mock(OddsCollectionLogRepository::class.java)
        val alertRecordRepository = mock(OddsAlertRecordRepository::class.java)
        val autoBettingIntentRepository = mock(AutoBettingIntentRepository::class.java)
        val now = 1_779_203_200_000L
        val batchSize = 5_000

        `when`(
            snapshotRepository.deleteBatchOlderThan(
                now - OddsMonitorMaintenanceService.SNAPSHOT_RETENTION_MILLIS,
                batchSize
            )
        ).thenReturn(0)
        `when`(collectionLogRepository.deleteBatchAll(batchSize)).thenReturn(0)
        `when`(alertRecordRepository.deleteBatchAll(batchSize)).thenReturn(0)
        `when`(autoBettingIntentRepository.deleteBatchVerifiedPlacedIntents(batchSize)).thenReturn(batchSize, 14)
        `when`(autoBettingIntentRepository.deleteBatchRejectedIntents(batchSize)).thenReturn(0)

        val result = OddsMonitorMaintenanceService(
            snapshotRepository,
            collectionLogRepository,
            alertRecordRepository,
            autoBettingIntentRepository
        ).manualCleanup(now = now)

        assertEquals(5_014, result.deletedVerifiedPlacedIntents)
        assertEquals(0, result.deletedRejectedIntents)
        verify(autoBettingIntentRepository, times(2)).deleteBatchVerifiedPlacedIntents(batchSize)
    }

    @Test
    fun `manual cleanup clears rejected betting history`() {
        val snapshotRepository = mock(OddsSnapshotRepository::class.java)
        val collectionLogRepository = mock(OddsCollectionLogRepository::class.java)
        val alertRecordRepository = mock(OddsAlertRecordRepository::class.java)
        val autoBettingIntentRepository = mock(AutoBettingIntentRepository::class.java)
        val now = 1_779_203_200_000L
        val batchSize = 5_000

        `when`(
            snapshotRepository.deleteBatchOlderThan(
                now - OddsMonitorMaintenanceService.SNAPSHOT_RETENTION_MILLIS,
                batchSize
            )
        ).thenReturn(0)
        `when`(collectionLogRepository.deleteBatchAll(batchSize)).thenReturn(0)
        `when`(alertRecordRepository.deleteBatchAll(batchSize)).thenReturn(0)
        `when`(autoBettingIntentRepository.deleteBatchVerifiedPlacedIntents(batchSize)).thenReturn(0)
        `when`(autoBettingIntentRepository.deleteBatchRejectedIntents(batchSize)).thenReturn(batchSize, 1465)

        val result = OddsMonitorMaintenanceService(
            snapshotRepository,
            collectionLogRepository,
            alertRecordRepository,
            autoBettingIntentRepository
        ).manualCleanup(now = now)

        assertEquals(6_465, result.deletedRejectedIntents)
        verify(autoBettingIntentRepository, times(2)).deleteBatchRejectedIntents(batchSize)
    }
}
