package com.wrbug.polymarketbot.controller.system

import com.wrbug.polymarketbot.service.oddsmonitor.OddsMonitorCleanupResult
import com.wrbug.polymarketbot.service.oddsmonitor.OddsMonitorMaintenanceService
import com.wrbug.polymarketbot.service.system.GitHubUpdateService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class UpdateControllerTest {
    @Test
    fun `cleanup endpoint runs odds monitor maintenance and returns deleted counts`() {
        val updateService = mock(GitHubUpdateService::class.java)
        val maintenanceService = mock(OddsMonitorMaintenanceService::class.java)
        `when`(maintenanceService.manualCleanup()).thenReturn(
            OddsMonitorCleanupResult(
                deletedSnapshots = 123,
                deletedCollectionLogs = 4,
                deletedBrokenAlertRecords = 5,
                deletedAlertRecords = 6,
                deletedVerifiedPlacedIntents = 7,
                deletedRejectedIntents = 8
            )
        )
        val controller = UpdateController(updateService, maintenanceService)

        val response = controller.cleanupHistory()

        assertEquals(0, response.body?.code)
        assertEquals(123, response.body?.data?.deletedSnapshots)
        assertEquals(4, response.body?.data?.deletedCollectionLogs)
        assertEquals(5, response.body?.data?.deletedBrokenAlertRecords)
        assertEquals(6, response.body?.data?.deletedAlertRecords)
        assertEquals(7, response.body?.data?.deletedVerifiedPlacedIntents)
        assertEquals(8, response.body?.data?.deletedRejectedIntents)
        verify(maintenanceService).manualCleanup()
    }
}
