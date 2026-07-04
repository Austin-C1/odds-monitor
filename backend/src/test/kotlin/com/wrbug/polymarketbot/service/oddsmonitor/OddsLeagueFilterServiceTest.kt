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
        assertTrue(filter.shouldIncludeLeague("England Premier League"))
        assertFalse(filter.shouldIncludeLeague("England Northern Premier League"))
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
            SystemConfig(configKey = OddsLeagueFilterService.CONFIG_KEY, configValue = """["Japan - J1 League","England Premier League"]""")
        )

        val filter = OddsLeagueFilterService(repository)

        assertEquals(listOf("日本J1百年构想联赛", "英格兰超级联赛"), filter.getSelectedLeagues())
        assertTrue(filter.shouldIncludeLeague("Japan - J1 League"))
        assertFalse(filter.shouldIncludeLeague("Japan - J1 League - Special Betting"))
    }

    @Test
    fun `crown selected league names keep raw platform names`() {
        val repository = mock(SystemConfigRepository::class.java)
        `when`(repository.findByConfigKey(OddsLeagueFilterService.CROWN_CONFIG_KEY)).thenReturn(
            SystemConfig(configKey = OddsLeagueFilterService.CROWN_CONFIG_KEY, configValue = """["韩国K甲组联赛"]""")
        )

        val filter = OddsLeagueFilterService(repository)

        assertEquals(listOf("韩国K甲组联赛"), filter.getSelectedLeagues("crown"))
        assertTrue(filter.shouldIncludeLeague("crown", "韩国K甲组联赛"))
        assertFalse(filter.shouldIncludeLeague("crown", "韩国K甲组联赛-特别投注"))
    }

    @Test
    fun `missing crown source config uses built in crown defaults`() {
        val repository = mock(SystemConfigRepository::class.java)
        `when`(repository.findByConfigKey(OddsLeagueFilterService.CROWN_CONFIG_KEY)).thenReturn(null)

        val filter = OddsLeagueFilterService(repository)

        assertTrue(filter.getSelectedLeagues("crown").contains("欧洲联赛"))
        assertTrue(filter.shouldIncludeLeague("crown", "欧洲联赛"))
        assertTrue(filter.shouldIncludeLeague("crown", "英格兰超级联赛"))
        assertFalse(filter.shouldIncludeLeague("crown", "冰岛丁组联赛"))
    }

    @Test
    fun `default tracking display uses crown and common league names`() {
        val repository = mock(SystemConfigRepository::class.java)
        `when`(repository.findByConfigKey(OddsLeagueFilterService.CONFIG_KEY)).thenReturn(null)
        `when`(repository.findByConfigKey(OddsLeagueFilterService.CROWN_CONFIG_KEY)).thenReturn(null)

        val filter = OddsLeagueFilterService(repository)
        val leagues = filter.getDefaultTrackingLeagues()

        assertTrue(leagues.contains("英格兰超级联赛"))
        assertTrue(leagues.contains("欧洲联赛"))
        assertTrue(leagues.contains("美国公开赛冠军杯"))
        assertEquals(listOf("英格兰超级联赛"), filter.expandDefaultTrackingLeagueNames(listOf("英格兰超级联赛")))
    }

    @Test
    fun `available leagues merge raw translation variants into canonical leagues`() {
        val leagues = availableOddsLeagueNames(
            listOf(
                OddsPlatformMatch(rawLeagueName = "England Premier League"),
                OddsPlatformMatch(rawLeagueName = "英格兰超级联赛"),
                OddsPlatformMatch(rawLeagueName = "Japan - J2/J3 League"),
                OddsPlatformMatch(rawLeagueName = "日本J2 J3百年构想联赛"),
                OddsPlatformMatch(rawLeagueName = "England Northern Premier League"),
                OddsPlatformMatch(rawLeagueName = "意大利甲组联赛-特别投注")
            )
        )

        assertEquals(
            listOf("英格兰北部超级联赛", "英格兰超级联赛", "日本J2 J3百年构想联赛"),
            leagues
        )
    }

    @Test
    fun `available crown leagues keep raw platform names`() {
        val leagues = availableOddsLeagueNames(
            listOf(
                OddsPlatformMatch(sourceKey = "crown", rawLeagueName = "韩国K甲组联赛"),
                OddsPlatformMatch(sourceKey = "crown", rawLeagueName = "意大利甲组联赛-特别投注")
            ),
            sourceKey = "crown"
        )

        assertEquals(listOf("韩国K甲组联赛"), leagues)
    }

    @Test
    fun `league filter rejects playoff special betting and fantasy leagues`() {
        val repository = mock(SystemConfigRepository::class.java)
        `when`(repository.findByConfigKey(OddsLeagueFilterService.CONFIG_KEY)).thenReturn(null)

        val filter = OddsLeagueFilterService(repository)

        assertFalse(filter.shouldIncludeLeague("埃及超级联赛-附加赛"))
        assertFalse(filter.shouldIncludeLeague("埃及超级联赛-特别投注"))
        assertFalse(filter.shouldIncludeLeague("crown", "埃及超级联赛-附加赛"))
        assertFalse(filter.shouldIncludeLeague("crown", "埃及超级联赛-特别投注"))
        assertFalse(filter.shouldIncludeLeague("crown", "Fantasy Matchups"))
        assertEquals(
            emptyList<String>(),
            availableOddsLeagueNames(
                listOf(
                    OddsPlatformMatch(rawLeagueName = "埃及超级联赛-附加赛"),
                    OddsPlatformMatch(rawLeagueName = "埃及超级联赛-特别投注"),
                    OddsPlatformMatch(rawLeagueName = "Fantasy Matchups")
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
