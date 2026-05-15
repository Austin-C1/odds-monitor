package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.entity.OddsPlatformMatch
import com.wrbug.polymarketbot.entity.SystemConfig
import com.wrbug.polymarketbot.repository.OddsAlertRecordRepository
import com.wrbug.polymarketbot.repository.OddsCollectionLogRepository
import com.wrbug.polymarketbot.repository.OddsDataSourceConfigRepository
import com.wrbug.polymarketbot.repository.OddsPlatformMatchRepository
import com.wrbug.polymarketbot.repository.SystemConfigRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class OddsMonitorServiceLeagueFilterTest {
    @Test
    fun `default tracking list groups provided correspondence table only`() {
        val service = oddsMonitorServiceWithLeagueFilter(
            platformMatches = mapOf(
                "pinnacle" to listOf(
                    OddsPlatformMatch(sourceKey = "pinnacle", rawLeagueName = "英格兰 - 超级联赛"),
                    OddsPlatformMatch(sourceKey = "pinnacle", rawLeagueName = "芬兰 - 足球超级联赛A"),
                    OddsPlatformMatch(sourceKey = "pinnacle", rawLeagueName = "芬兰 - 全国联赛"),
                    OddsPlatformMatch(sourceKey = "pinnacle", rawLeagueName = "芬兰 - 杯赛"),
                    OddsPlatformMatch(sourceKey = "pinnacle", rawLeagueName = "阿尔及利亚 - 甲级联赛")
                ),
                "crown" to listOf(
                    OddsPlatformMatch(sourceKey = "crown", rawLeagueName = "英格兰超级联赛"),
                    OddsPlatformMatch(sourceKey = "crown", rawLeagueName = "芬兰超级联赛"),
                    OddsPlatformMatch(sourceKey = "crown", rawLeagueName = "芬兰甲组联赛"),
                    OddsPlatformMatch(sourceKey = "crown", rawLeagueName = "芬兰杯"),
                    OddsPlatformMatch(sourceKey = "crown", rawLeagueName = "阿尔及利亚甲组联赛")
                )
            )
        )

        val leagues = service.listLeagueFilter()

        assertTrue(leagues.availableLeagues.contains("英格兰 - 超级联赛/英格兰超级联赛"))
        assertTrue(leagues.availableLeagues.contains("芬兰 - 足球超级联赛A/芬兰超级联赛"))
        assertTrue(leagues.availableLeagues.contains("芬兰 - 全国联赛"))
        assertTrue(leagues.availableLeagues.contains("芬兰甲组联赛"))
        assertTrue(leagues.availableLeagues.contains("芬兰 - 杯赛"))
        assertTrue(leagues.availableLeagues.contains("芬兰杯"))
        assertTrue(leagues.availableLeagues.contains("阿尔及利亚 - 甲级联赛"))
        assertTrue(leagues.availableLeagues.contains("阿尔及利亚甲组联赛"))
        assertFalse(leagues.availableLeagues.contains("芬兰 - 杯赛/芬兰杯"))
        assertFalse(leagues.availableLeagues.contains("阿尔及利亚 - 甲级联赛/阿尔及利亚甲组联赛"))
        assertTrue(leagues.selectedLeagues.contains("英格兰 - 超级联赛/英格兰超级联赛"))
        assertFalse(leagues.availableLeagues.contains("英格兰超级联赛"))
    }

    @Test
    fun `default tracking selected leagues keeps singles before matched pairs without uncertain pairing`() {
        val systemConfigRepository = statefulSystemConfigRepository()
        val leagueFilterService = OddsLeagueFilterService(systemConfigRepository)
        leagueFilterService.saveSelectedLeagues(
            listOf("芬兰 - 全国联赛", "芬兰 - 杯赛", "英格兰 - 超级联赛", "俄罗斯超级联赛"),
            "pinnacle"
        )
        leagueFilterService.saveSelectedLeagues(
            listOf("芬兰甲组联赛", "卡塔尔甲组联赛", "英格兰超级联赛", "俄罗斯超级联赛"),
            "crown"
        )
        leagueFilterService.saveSelectedLeagues(emptyList(), null)
        val service = oddsMonitorServiceWithLeagueFilter(leagueFilterService = leagueFilterService)

        val leagues = service.listLeagueFilter()

        assertEquals(
            listOf(
                "芬兰 - 全国联赛",
                "芬兰 - 杯赛",
                "芬兰甲组联赛",
                "卡塔尔甲组联赛",
                "英格兰 - 超级联赛/英格兰超级联赛",
                "俄罗斯超级联赛"
            ),
            leagues.selectedLeagues
        )
    }

    @Test
    fun `default tracking list removes reversed duplicate grouped league names`() {
        val service = oddsMonitorServiceWithLeagueFilter()

        val leagues = service.listLeagueFilter()

        assertEquals(
            listOf("阿根廷 - 杯赛/阿根廷杯"),
            leagues.availableLeagues.filter {
                it == "阿根廷 - 杯赛/阿根廷杯" || it == "阿根廷杯/阿根廷 - 杯赛"
            }
        )
    }

    @Test
    fun `saving default tracking grouped league updates platform source filters`() {
        val systemConfigRepository = statefulSystemConfigRepository()
        val leagueFilterService = OddsLeagueFilterService(systemConfigRepository)
        val service = oddsMonitorServiceWithLeagueFilter(leagueFilterService = leagueFilterService)

        service.saveLeagueFilter(listOf("英格兰 - 超级联赛/英格兰超级联赛"), null)

        assertTrue(leagueFilterService.getSelectedLeagues("pinnacle").contains("英格兰 - 超级联赛"))
        assertTrue(leagueFilterService.getSelectedLeagues("crown").contains("英格兰超级联赛"))
        assertFalse(leagueFilterService.getSelectedLeagues("pinnacle").contains("欧足联 - 欧罗巴联赛"))
        assertFalse(leagueFilterService.getSelectedLeagues("crown").contains("欧洲联赛"))
    }

    private fun oddsMonitorServiceWithLeagueFilter(
        platformMatches: Map<String, List<OddsPlatformMatch>> = emptyMap(),
        leagueFilterService: OddsLeagueFilterService = OddsLeagueFilterService(statefulSystemConfigRepository())
    ): OddsMonitorService {
        val configRepository = mock(OddsDataSourceConfigRepository::class.java)
        val alertRepository = mock(OddsAlertRecordRepository::class.java)
        val logRepository = mock(OddsCollectionLogRepository::class.java)
        val platformRepository = mock(OddsPlatformMatchRepository::class.java)

        listOf("pinnacle", "crown").forEach { sourceKey ->
            `when`(platformRepository.findTop500BySourceKeyOrderByUpdatedAtDesc(sourceKey))
                .thenReturn(platformMatches[sourceKey].orEmpty())
        }

        return OddsMonitorService(
            dataSourceConfigRepository = configRepository,
            alertRecordRepository = alertRepository,
            collectionLogRepository = logRepository,
            platformMatchRepository = platformRepository,
            leagueFilterService = leagueFilterService
        )
    }

    private fun statefulSystemConfigRepository(): SystemConfigRepository {
        val repository = mock(SystemConfigRepository::class.java)
        val configs = linkedMapOf<String, SystemConfig>()

        `when`(repository.findByConfigKey(anyString())).thenAnswer { invocation ->
            configs[invocation.getArgument<String>(0)]
        }
        `when`(repository.save(any(SystemConfig::class.java))).thenAnswer { invocation ->
            val config = invocation.getArgument<SystemConfig>(0)
            configs[config.configKey] = config
            config
        }

        return repository
    }
}
