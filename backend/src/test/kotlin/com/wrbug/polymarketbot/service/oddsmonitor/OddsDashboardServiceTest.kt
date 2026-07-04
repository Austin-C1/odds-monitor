package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.repository.OddsDataSourceConfigRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class OddsDashboardServiceTest {
    @Test
    fun `fallback dashboard is crown only when no platform repository is available`() {
        val service = OddsDashboardService(
            dataSourceConfigRepository = mock(OddsDataSourceConfigRepository::class.java),
            platformMatchRepository = null,
            matchRepository = null,
            matchLinkRepository = null,
            leagueFilterService = null,
            displayMapper = OddsMonitorDisplayMapper(),
            matchDetailService = OddsMatchDetailService()
        )

        val dashboard = service.getDashboard()

        assertEquals(3, dashboard.matches.size)
        assertTrue(dashboard.matches.all { it.matchedPlatforms == listOf("crown") })
        assertEquals(listOf("crown"), dashboard.selectedMatch?.metrics?.mapNotNull { it.sourceKey }?.distinct())
        assertTrue(dashboard.selectedMatch?.oddsHistory.orEmpty().all { it.crown > 0.0 })
    }
}
