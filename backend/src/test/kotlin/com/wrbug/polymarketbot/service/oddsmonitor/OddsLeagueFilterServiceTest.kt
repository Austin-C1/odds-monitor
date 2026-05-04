package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.entity.OddsPlatformMatch
import com.wrbug.polymarketbot.entity.SystemConfig
import com.wrbug.polymarketbot.repository.SystemConfigRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class OddsLeagueFilterServiceTest {
    @Test
    fun `league filter stores selected league names and filters dashboard rows`() {
        val repository = mock(SystemConfigRepository::class.java)
        `when`(repository.findByConfigKey("odds_monitor.selected_leagues")).thenReturn(
            SystemConfig(configKey = "odds_monitor.selected_leagues", configValue = """["日本J1","英超"]""")
        )

        val filter = OddsLeagueFilterService(repository)

        assertEquals(listOf("日本J1", "英超"), filter.getSelectedLeagues())
        assertTrue(filter.shouldIncludeLeague("日本J1"))
        assertFalse(filter.shouldIncludeLeague("西甲"))
    }

    @Test
    fun `empty league filter includes every league`() {
        val repository = mock(SystemConfigRepository::class.java)
        `when`(repository.findByConfigKey("odds_monitor.selected_leagues")).thenReturn(null)

        val filter = OddsLeagueFilterService(repository)

        assertTrue(filter.shouldIncludeLeague("日本J1"))
        assertTrue(filter.shouldIncludeLeague("英超"))
    }

    @Test
    fun `available leagues are normalized and sorted from collected matches`() {
        val leagues = availableOddsLeagueNames(
            listOf(
                OddsPlatformMatch(rawLeagueName = "日本J1"),
                OddsPlatformMatch(rawLeagueName = " 英超 "),
                OddsPlatformMatch(rawLeagueName = "日本J1"),
                OddsPlatformMatch(rawLeagueName = "")
            )
        )

        assertEquals(listOf("英超", "日本J1"), leagues)
    }
}
