package com.wrbug.polymarketbot.service.oddsmonitor

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class OddsMonitorMaintenanceSuccessHistorySourceTest {
    @Test
    fun `manual cleanup includes settled betting history`() {
        val serviceSource = Files.readString(
            Path.of("src/main/kotlin/com/wrbug/polymarketbot/service/oddsmonitor/OddsMonitorMaintenanceService.kt")
        )
        val repositorySource = Files.readString(
            Path.of("src/main/kotlin/com/wrbug/polymarketbot/repository/AutoBettingRepository.kt")
        )

        assertTrue(serviceSource.contains("deletedVerifiedPlacedIntents"))
        assertTrue(serviceSource.contains("deleteBatchVerifiedPlacedIntents"))
        assertTrue(serviceSource.contains("deletedRejectedIntents"))
        assertTrue(serviceSource.contains("deleteBatchRejectedIntents"))
        assertTrue(repositorySource.contains("deleteBatchVerifiedPlacedIntents"))
        assertTrue(repositorySource.contains("deleteBatchRejectedIntents"))
        assertTrue(repositorySource.contains("status = 'placed'"))
        assertTrue(repositorySource.contains("crown_history_verified = 1"))
        assertTrue(repositorySource.contains("status = 'rejected'"))
    }
}
