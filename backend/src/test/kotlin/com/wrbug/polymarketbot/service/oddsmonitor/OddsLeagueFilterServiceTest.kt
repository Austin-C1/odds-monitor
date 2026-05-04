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

        assertEquals(listOf("日本J1", "英格兰超级联赛"), filter.getSelectedLeagues())
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

        assertEquals(listOf("英格兰超级联赛", "日本J1"), leagues)
    }

    @Test
    fun `available leagues merge raw variants into one canonical league`() {
        val leagues = availableOddsLeagueNames(
            listOf(
                OddsPlatformMatch(rawLeagueName = "英格兰 - 超级联赛"),
                OddsPlatformMatch(rawLeagueName = "英格兰超级联赛"),
                OddsPlatformMatch(rawLeagueName = "英格兰超级联赛-特别投注"),
                OddsPlatformMatch(rawLeagueName = "England Premier League"),
                OddsPlatformMatch(rawLeagueName = "英格兰 - 北部超级联赛")
            )
        )

        assertEquals(listOf("英格兰北部超级联赛", "英格兰超级联赛"), leagues)
    }

    @Test
    fun `selected canonical league includes raw variants`() {
        val repository = mock(SystemConfigRepository::class.java)
        `when`(repository.findByConfigKey("odds_monitor.selected_leagues")).thenReturn(
            SystemConfig(configKey = "odds_monitor.selected_leagues", configValue = """["英格兰 - 超级联赛"]""")
        )

        val filter = OddsLeagueFilterService(repository)

        assertEquals(listOf("英格兰超级联赛"), filter.getSelectedLeagues())
        assertTrue(filter.shouldIncludeLeague("英格兰超级联赛-特别投注"))
        assertTrue(filter.shouldIncludeLeague("England Premier League"))
        assertFalse(filter.shouldIncludeLeague("英格兰 - 北部超级联赛"))
    }
}
