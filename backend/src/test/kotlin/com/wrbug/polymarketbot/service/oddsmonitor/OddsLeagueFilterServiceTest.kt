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
    fun `missing config uses built in default tracked leagues`() {
        val repository = mock(SystemConfigRepository::class.java)
        `when`(repository.findByConfigKey(OddsLeagueFilterService.CONFIG_KEY)).thenReturn(null)

        val filter = OddsLeagueFilterService(repository)

        assertTrue(filter.getSelectedLeagues().contains("英格兰超级联赛"))
        assertTrue(filter.shouldIncludeLeague("英格兰 - 超级联赛"))
        assertTrue(filter.shouldIncludeLeague("England Premier League"))
        assertFalse(filter.shouldIncludeLeague("英格兰 - 北部超级联赛"))
    }

    @Test
    fun `saved empty list tracks no leagues`() {
        val repository = mock(SystemConfigRepository::class.java)
        `when`(repository.findByConfigKey(OddsLeagueFilterService.CONFIG_KEY)).thenReturn(
            SystemConfig(configKey = OddsLeagueFilterService.CONFIG_KEY, configValue = "[]")
        )

        val filter = OddsLeagueFilterService(repository)

        assertEquals(emptyList<String>(), filter.getSelectedLeagues())
        assertFalse(filter.shouldIncludeLeague("英格兰超级联赛"))
    }

    @Test
    fun `saved selected league names are canonicalized`() {
        val repository = mock(SystemConfigRepository::class.java)
        `when`(repository.findByConfigKey(OddsLeagueFilterService.CONFIG_KEY)).thenReturn(
            SystemConfig(configKey = OddsLeagueFilterService.CONFIG_KEY, configValue = """["日本 - J联赛","英格兰 - 超级联赛"]""")
        )

        val filter = OddsLeagueFilterService(repository)

        assertEquals(listOf("日本J1百年构想联赛", "英格兰超级联赛"), filter.getSelectedLeagues())
        assertTrue(filter.shouldIncludeLeague("Japan - J1 League"))
        assertFalse(filter.shouldIncludeLeague("日本 - J联赛 - 特别投注"))
    }

    @Test
    fun `source selected league names keep platform raw names`() {
        val repository = mock(SystemConfigRepository::class.java)
        `when`(repository.findByConfigKey(OddsLeagueFilterService.PINNACLE_CONFIG_KEY)).thenReturn(
            SystemConfig(configKey = OddsLeagueFilterService.PINNACLE_CONFIG_KEY, configValue = """["韩国 - K联赛1","芬兰 - 全国联赛"]""")
        )
        `when`(repository.findByConfigKey(OddsLeagueFilterService.CROWN_CONFIG_KEY)).thenReturn(
            SystemConfig(configKey = OddsLeagueFilterService.CROWN_CONFIG_KEY, configValue = """["韩国K甲组联赛"]""")
        )

        val filter = OddsLeagueFilterService(repository)

        assertEquals(listOf("韩国 - K联赛1", "芬兰 - 全国联赛"), filter.getSelectedLeagues("pinnacle"))
        assertTrue(filter.shouldIncludeLeague("pinnacle", "韩国 - K联赛1"))
        assertFalse(filter.shouldIncludeLeague("crown", "韩国 - K联赛1"))
        assertTrue(filter.shouldIncludeLeague("crown", "韩国K甲组联赛"))
    }

    @Test
    fun `source filter falls back to default list before source list is saved`() {
        val repository = mock(SystemConfigRepository::class.java)
        `when`(repository.findByConfigKey(OddsLeagueFilterService.PINNACLE_CONFIG_KEY)).thenReturn(null)
        `when`(repository.findByConfigKey(OddsLeagueFilterService.CONFIG_KEY)).thenReturn(
            SystemConfig(configKey = OddsLeagueFilterService.CONFIG_KEY, configValue = """["英格兰超级联赛"]""")
        )

        val filter = OddsLeagueFilterService(repository)

        assertTrue(filter.shouldIncludeLeague("pinnacle", "英格兰 - 超级联赛"))
    }

    @Test
    fun `canonicalizes platform league names with trailing source marker`() {
        assertEquals("印尼超级联赛", canonicalOddsLeagueName("印度尼西亚 - 超级联赛 n"))
    }

    @Test
    fun `available leagues merge raw translation variants into canonical leagues`() {
        val leagues = availableOddsLeagueNames(
            listOf(
                OddsPlatformMatch(rawLeagueName = "英格兰 - 超级联赛"),
                OddsPlatformMatch(rawLeagueName = "英格兰超级联赛"),
                OddsPlatformMatch(rawLeagueName = "England Premier League"),
                OddsPlatformMatch(rawLeagueName = "Japan - J2/J3 League"),
                OddsPlatformMatch(rawLeagueName = "日本J2 J3百年构想联赛"),
                OddsPlatformMatch(rawLeagueName = "英格兰 - 北部超级联赛"),
                OddsPlatformMatch(rawLeagueName = "意大利甲组联赛-特别投注")
            )
        )

        assertEquals(
            listOf("英格兰北部超级联赛", "英格兰超级联赛", "日本J2 J3百年构想联赛"),
            leagues
        )
    }

    @Test
    fun `available source leagues keep raw platform names`() {
        val leagues = availableOddsLeagueNames(
            listOf(
                OddsPlatformMatch(sourceKey = "pinnacle", rawLeagueName = "韩国 - K联赛1"),
                OddsPlatformMatch(sourceKey = "crown", rawLeagueName = "韩国K甲组联赛"),
                OddsPlatformMatch(sourceKey = "pinnacle", rawLeagueName = "意大利甲组联赛-特别投注")
            ),
            sourceKey = "pinnacle"
        )

        assertEquals(listOf("韩国 - K联赛1"), leagues)
    }

    @Test
    fun `league filter rejects playoff and special betting leagues`() {
        val repository = mock(SystemConfigRepository::class.java)
        `when`(repository.findByConfigKey(OddsLeagueFilterService.CONFIG_KEY)).thenReturn(null)

        val filter = OddsLeagueFilterService(repository)

        assertFalse(filter.shouldIncludeLeague("埃及超级联赛-附加赛"))
        assertFalse(filter.shouldIncludeLeague("埃及超级联赛-特别投注"))
        assertFalse(filter.shouldIncludeLeague("pinnacle", "埃及超级联赛-附加赛"))
        assertFalse(filter.shouldIncludeLeague("pinnacle", "埃及超级联赛-特别投注"))
        assertFalse(filter.shouldIncludeLeague("crown", "埃及超级联赛-附加赛"))
        assertFalse(filter.shouldIncludeLeague("crown", "埃及超级联赛-特别投注"))
        assertEquals(
            emptyList<String>(),
            availableOddsLeagueNames(
                listOf(
                    OddsPlatformMatch(rawLeagueName = "埃及超级联赛-附加赛"),
                    OddsPlatformMatch(rawLeagueName = "埃及超级联赛-特别投注")
                )
            )
        )
    }

    @Test
    fun `default tracked leagues are always available even before collection`() {
        val leagues = availableOddsLeagueNames(emptyList())

        assertTrue(defaultTrackedLeagueNames().contains("世界杯2026(美加墨)"))
        assertTrue(leagues.isEmpty())
    }
}
