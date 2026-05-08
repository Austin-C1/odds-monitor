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
    fun `missing pinnacle source config uses built in pinnacle defaults`() {
        val repository = mock(SystemConfigRepository::class.java)
        `when`(repository.findByConfigKey(OddsLeagueFilterService.PINNACLE_CONFIG_KEY)).thenReturn(null)

        val filter = OddsLeagueFilterService(repository)

        assertTrue(filter.getSelectedLeagues("pinnacle").contains("欧足联 - 欧罗巴联赛"))
        assertTrue(filter.shouldIncludeLeague("pinnacle", "欧足联 - 欧罗巴联赛"))
        assertTrue(filter.shouldIncludeLeague("pinnacle", "丹麦 - 超级联赛"))
        assertTrue(filter.shouldIncludeLeague("pinnacle", "丹麦- 超级联赛"))
        assertFalse(filter.shouldIncludeLeague("pinnacle", "冰岛 - 丁级联赛"))
    }

    @Test
    fun `missing crown source config uses built in crown defaults`() {
        val repository = mock(SystemConfigRepository::class.java)
        `when`(repository.findByConfigKey(OddsLeagueFilterService.CROWN_CONFIG_KEY)).thenReturn(null)
        `when`(repository.findByConfigKey(OddsLeagueFilterService.PINNACLE_CONFIG_KEY)).thenReturn(null)

        val filter = OddsLeagueFilterService(repository)

        assertTrue(filter.getSelectedLeagues("crown").contains("欧洲联赛"))
        assertTrue(filter.shouldIncludeLeague("crown", "欧洲联赛"))
        assertTrue(filter.shouldIncludeLeague("crown", "英格兰超级联赛"))
        assertFalse(filter.shouldIncludeLeague("crown", "冰岛丁组联赛"))
        assertTrue(filter.shouldIncludeLeague("pinnacle", "欧足联 - 欧罗巴联赛"))
    }

    @Test
    fun `default tracking display groups equivalent platform league names`() {
        val repository = mock(SystemConfigRepository::class.java)
        `when`(repository.findByConfigKey(OddsLeagueFilterService.CONFIG_KEY)).thenReturn(null)
        `when`(repository.findByConfigKey(OddsLeagueFilterService.CROWN_CONFIG_KEY)).thenReturn(null)
        `when`(repository.findByConfigKey(OddsLeagueFilterService.PINNACLE_CONFIG_KEY)).thenReturn(null)

        val filter = OddsLeagueFilterService(repository)
        val leagues = filter.getDefaultTrackingLeagues()

        assertTrue(leagues.contains("英格兰 - 超级联赛/英格兰超级联赛"))
        assertTrue(leagues.contains("欧足联 - 欧罗巴联赛/欧洲联赛"))
        assertTrue(leagues.contains("Japan - J2/J3 League/日本J2 J3百年构想联赛"))
        assertEquals(78, filter.getSelectedLeagues("pinnacle").size)
        assertEquals(118, filter.getSelectedLeagues("crown").size)
        assertEquals(120, leagues.size)
        expectedDefaultTrackingLabels.forEach { label ->
            assertTrue(leagues.contains(label), "Missing default tracking label: $label")
        }
        assertEquals(
            listOf("Japan - J2/J3 League", "日本J2 J3百年构想联赛"),
            filter.expandDefaultTrackingLeagueNames(listOf("Japan - J2/J3 League/日本J2 J3百年构想联赛"))
        )
    }

    @Test
    fun `default tracking display keeps uncertain Finland leagues separate`() {
        val repository = mock(SystemConfigRepository::class.java)
        `when`(repository.findByConfigKey(OddsLeagueFilterService.CONFIG_KEY)).thenReturn(null)
        `when`(repository.findByConfigKey(OddsLeagueFilterService.CROWN_CONFIG_KEY)).thenReturn(null)
        `when`(repository.findByConfigKey(OddsLeagueFilterService.PINNACLE_CONFIG_KEY)).thenReturn(null)

        val filter = OddsLeagueFilterService(repository)
        val leagues = filter.getDefaultTrackingLeagues()

        assertTrue(leagues.contains("芬兰 - 足球超级联赛A/芬兰超级联赛"))
        assertTrue(leagues.contains("芬兰 - 全国联赛"))
        assertTrue(leagues.contains("芬兰甲组联赛"))
        assertTrue(leagues.contains("芬兰 - 杯赛"))
        assertFalse(leagues.contains("芬兰 - 全国联赛/芬兰甲组联赛"))
        assertFalse(leagues.contains("芬兰 - 全国联赛/芬兰超级联赛"))
        assertFalse(leagues.contains("芬兰 - 足球超级联赛A/芬兰甲组联赛"))
    }

    @Test
    fun `canonicalizes only verified Finland pinnacle aliases to crown leagues`() {
        assertEquals("芬兰超级联赛", canonicalOddsLeagueName("芬兰 - 足球超级联赛A"))
        assertEquals("芬兰全国联赛", canonicalOddsLeagueName("芬兰 - 全国联赛"))
    }

    @Test
    fun `default tracking display does not group unlisted similar league names`() {
        val leagues = defaultTrackingLeagueDisplayNames(
            listOf("阿尔及利亚 - 甲级联赛", "阿尔及利亚甲组联赛", "哥斯达黎加 - 女子足球甲级联赛", "哥斯达黎加女子甲组联赛")
        )

        assertTrue(leagues.contains("阿尔及利亚 - 甲级联赛"))
        assertTrue(leagues.contains("阿尔及利亚甲组联赛"))
        assertTrue(leagues.contains("哥斯达黎加 - 女子足球甲级联赛"))
        assertTrue(leagues.contains("哥斯达黎加女子甲组联赛"))
        assertFalse(leagues.contains("阿尔及利亚 - 甲级联赛/阿尔及利亚甲组联赛"))
        assertFalse(leagues.contains("哥斯达黎加 - 女子足球甲级联赛/哥斯达黎加女子甲组联赛"))
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
    fun `league filter rejects fantasy events`() {
        val repository = mock(SystemConfigRepository::class.java)
        `when`(repository.findByConfigKey(OddsLeagueFilterService.CONFIG_KEY)).thenReturn(null)

        val filter = OddsLeagueFilterService(repository)

        assertFalse(filter.shouldIncludeLeague("奇幻赛事"))
        assertFalse(filter.shouldIncludeLeague("pinnacle", "Fantasy Matchups"))
        assertFalse(filter.shouldIncludeLeague("crown", "奇幻赛事"))
        assertEquals(
            emptyList<String>(),
            availableOddsLeagueNames(listOf(OddsPlatformMatch(rawLeagueName = "奇幻赛事")))
        )
    }

    @Test
    fun `default tracked leagues are always available even before collection`() {
        val leagues = availableOddsLeagueNames(emptyList())

        assertTrue(defaultTrackedLeagueNames().contains("世界杯2026(美加墨)"))
        assertTrue(leagues.isEmpty())
    }
}

private val expectedDefaultTrackingLabels = listOf(
    "英格兰 - 超级联赛/英格兰超级联赛",
    "英格兰 - 冠军联赛/英格兰冠军联赛",
    "英格兰 - 甲级联赛/英格兰甲组联赛",
    "英格兰 - 乙级联赛/英格兰乙组联赛",
    "德国 - 德甲/德国甲组联赛",
    "德国 - 德乙/德国乙组联赛",
    "西班牙 - 西甲/西班牙甲组联赛",
    "西班牙 - 乙级联赛/西班牙乙组联赛",
    "意大利 - 甲级联赛/意大利甲组联赛",
    "意大利 - 乙级联赛/意大利乙组联赛",
    "法国 - 甲级联赛/法国甲组联赛",
    "法国 - 乙级联赛/法国乙组联赛",
    "荷兰 - 甲级联赛/荷兰甲组联赛",
    "荷兰 - 乙级联赛/荷兰乙组联赛",
    "葡萄牙 - 超级联赛/葡萄牙超级联赛",
    "葡萄牙 - 甲级联赛/葡萄牙甲组联赛",
    "俄罗斯超级联赛",
    "挪威 - 足球超级联赛/挪威超级联赛",
    "芬兰 - 足球超级联赛A/芬兰超级联赛",
    "芬兰 - 全国联赛",
    "芬兰甲组联赛",
    "芬兰 - 杯赛",
    "瑞典 - 超级联赛/瑞典超级联赛",
    "瑞典 - 甲级联赛/瑞典超级甲组联赛",
    "丹麦 - 超级联赛/丹麦超级联赛",
    "丹麦 - 甲级联赛/丹麦甲组联赛",
    "奥地利 - 甲级联赛/奥地利甲组联赛",
    "奥地利 - 乙级联赛/奥地利乙组联赛",
    "瑞士 - 超级联赛/瑞士超级联赛",
    "瑞士 - 挑战联赛/瑞士甲组联赛",
    "爱尔兰超级联赛",
    "爱尔兰 - 甲级联赛/爱尔兰甲组联赛",
    "比利时 - 职业联赛/比利时甲组联赛A",
    "土耳其 - 超级联赛/土耳其超级联赛",
    "希腊超级联赛/希腊超级联赛甲组",
    "苏格兰 - 足球超级联赛/苏格兰超级联赛",
    "波兰 - 超级联赛/波兰超级联赛",
    "罗马尼亚 - 甲级联赛/罗马尼亚甲组联赛",
    "捷克 - 甲级联赛/捷克甲组联赛",
    "乌克兰超级联赛",
    "冰岛超级联赛",
    "阿根廷 - 职业联赛/阿根廷职业联赛",
    "巴西 - 甲级联赛/巴西甲组联赛",
    "巴西 - 乙级联赛/巴西乙组联赛",
    "智利 - 甲级联赛/智利甲组联赛",
    "哥伦比亚 - 甲级联赛/哥伦比亚甲组联赛",
    "厄瓜多尔 - 甲级联赛/厄瓜多尔甲组联赛",
    "巴拉圭 - 职业联赛/巴拉圭甲组联赛",
    "秘鲁 - 足球甲级联赛/秘鲁甲组联赛",
    "美国 - 美国足球大联盟/美国职业大联盟",
    "美国 - USL锦标赛/美国足球冠军联赛",
    "墨西哥 - 足球甲级联赛/墨西哥超级联赛",
    "墨西哥 - 墨西哥足球拓展联赛/墨西哥甲组联赛",
    "日本 - J联赛/日本J1百年构想联赛",
    "Japan - J2/J3 League/日本J2 J3百年构想联赛",
    "澳大利亚 - 甲级联赛/澳大利亚甲组联赛",
    "澳大利亚 - NPL维多利亚州/澳大利亚维多利亚国家超级联赛",
    "澳大利亚 - 甲级联赛女子/澳大利亚女子甲组联赛",
    "韩国 - K联赛1/韩国K甲组联赛",
    "沙特阿拉伯 - 职业联赛/沙特超级联赛",
    "印度 - 超级联赛/印度超级联赛",
    "印度尼西亚 - 超级联赛/印尼超级联赛",
    "巴林 - 超级联赛/巴林超级联赛",
    "阿联酋 - 职业联赛/阿联酋超级联赛",
    "中国 - 超级联赛/中国超级联赛",
    "埃及 - 超级联赛/埃及超级联赛",
    "FIFA - 世界杯/世界杯2026(美加墨)",
    "欧足联 - 冠军联赛/欧洲冠军联赛",
    "欧足联 - 欧罗巴联赛/欧洲联赛",
    "欧足联 - 欧洲协会联赛/欧洲协会联赛",
    "英格兰足总杯",
    "德国杯赛/德国杯",
    "意大利 - 杯赛/意大利杯",
    "奥地利 - 杯赛/奥地利杯",
    "波兰 - 杯赛/波兰杯",
    "南美足联 - 解放者杯/南美自由杯",
    "南美足协 - 南美俱乐部杯/南美洲球会杯",
    "阿根廷 - 杯赛/阿根廷杯",
    "美国 - 公开杯/美国公开赛冠军杯"
)
