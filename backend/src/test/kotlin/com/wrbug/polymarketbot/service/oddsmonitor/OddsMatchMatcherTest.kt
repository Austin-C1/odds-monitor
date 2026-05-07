package com.wrbug.polymarketbot.service.oddsmonitor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class OddsMatchMatcherTest {
    @Test
    fun `matches translated team names with close start times`() {
        val candidate = OddsMatchCandidate(
            id = 1,
            leagueName = "Japan J1 League",
            homeTeam = "Kawasaki Frontale",
            awayTeam = "FC Tokyo",
            startTime = 1893456000000L
        )
        val incoming = OddsMatchCandidate(
            id = null,
            leagueName = "日本J1",
            homeTeam = "川崎前锋",
            awayTeam = "东京",
            startTime = 1893456120000L
        )

        val result = OddsMatchMatcher.score(candidate, incoming)

        assertTrue(result.score >= 0.85, "score was ${result.score}")
        assertFalse(result.reversed)
        assertEquals("auto", result.matchMethod)
    }

    @Test
    fun `recognizes reversed home and away teams`() {
        val candidate = OddsMatchCandidate(
            id = 2,
            leagueName = "Japan J1 League",
            homeTeam = "Fagiano Okayama",
            awayTeam = "Sanfrecce Hiroshima",
            startTime = 1893456000000L
        )
        val incoming = OddsMatchCandidate(
            id = null,
            leagueName = "日本J1",
            homeTeam = "广岛三箭",
            awayTeam = "冈山绿雉",
            startTime = 1893456300000L
        )

        val result = OddsMatchMatcher.score(candidate, incoming)

        assertTrue(result.score >= 0.85, "score was ${result.score}")
        assertTrue(result.reversed)
        assertEquals("reverse_auto", result.matchMethod)
    }

    @Test
    fun `matches MLS Chinese and English team names within the same match day`() {
        val candidate = OddsMatchCandidate(
            id = 4,
            leagueName = "美国职业大联盟",
            homeTeam = "迈阿密国际",
            awayTeam = "奥兰多城",
            startTime = Instant.parse("2026-05-03T02:24:00Z").toEpochMilli()
        )
        val incoming = OddsMatchCandidate(
            id = null,
            leagueName = "Polymarket",
            homeTeam = "Inter Miami CF",
            awayTeam = "Orlando City SC",
            startTime = Instant.parse("2026-05-02T16:00:00Z").toEpochMilli()
        )

        val result = OddsMatchMatcher.score(candidate, incoming)

        assertTrue(result.score >= 0.65, "score was ${result.score}")
        assertFalse(result.reversed)
    }

    @Test
    fun `matches pinnacle and crown names from the same latvia match`() {
        val crown = OddsMatchCandidate(
            id = 5,
            leagueName = "拉脱维亚超级联赛",
            homeTeam = "图库姆斯",
            awayTeam = "奥格雷联",
            startTime = Instant.parse("2026-05-07T15:00:00Z").toEpochMilli()
        )
        val pinnacle = OddsMatchCandidate(
            id = null,
            leagueName = "拉脱维亚 - 超级联赛",
            homeTeam = "图库姆斯2000‎",
            awayTeam = "Ogre United‎",
            startTime = Instant.parse("2026-05-07T15:00:00Z").toEpochMilli()
        )

        val result = OddsMatchMatcher.score(crown, pinnacle)

        assertTrue(result.score >= 0.85, "score was ${result.score}")
        assertFalse(result.reversed)
    }

    @Test
    fun `matches pinnacle and crown translated reserve team names`() {
        val crown = OddsMatchCandidate(
            id = 6,
            leagueName = "挪威丙组联赛",
            homeTeam = "海於格松B队",
            awayTeam = "奥特B队",
            startTime = Instant.parse("2026-05-07T15:00:00Z").toEpochMilli()
        )
        val pinnacle = OddsMatchCandidate(
            id = null,
            leagueName = "挪威 - 丙级联赛第4组",
            homeTeam = "豪格松二队‎",
            awayTeam = "Odd BK II‎",
            startTime = Instant.parse("2026-05-07T15:00:00Z").toEpochMilli()
        )

        val result = OddsMatchMatcher.score(crown, pinnacle)

        assertTrue(result.score >= 0.65, "score was ${result.score}")
        assertFalse(result.reversed)
    }

    @Test
    fun `matches observed pinnacle and crown translated aliases`() {
        data class MatchCase(
            val name: String,
            val crownLeague: String,
            val crownHome: String,
            val crownAway: String,
            val pinnacleLeague: String,
            val pinnacleHome: String,
            val pinnacleAway: String
        )

        val startTime = Instant.parse("2026-05-07T16:00:00Z").toEpochMilli()
        val cases = listOf(
            MatchCase("巴林", "巴林乙组联赛", "泰达姆恩", "伊萨城", "巴林 - 甲级联赛", "阿尔塔达蒙‎", "伊萨城‎"),
            MatchCase("瑞典女足", "瑞典女子甲组联赛", "埃尔夫斯堡(女)", "吉特斯(女)", "瑞典 - 女子精英联赛", "埃尔夫斯堡‎", "吉泰克斯BK‎"),
            MatchCase("厄瓜多尔", "厄瓜多尔乙组联赛", "库恩卡青年", "昆巴亚", "厄瓜多尔 - 乙级联赛", "昆卡青年队‎", "坎巴亚金融‎"),
            MatchCase("南美俱乐部杯", "南美洲球会杯", "波士顿河", "米伦拿列奥", "南美足协 - 南美俱乐部杯", "波士顿河‎", "百万富翁队‎"),
            MatchCase("巴西东北杯", "巴西东北杯", "康菲安卡SE", "福塔雷萨CE", "巴西 - 诺尔德斯特杯", "孔菲昂萨‎", "福塔雷萨‎"),
            MatchCase("波兰", "波兰甲组联赛", "LKS罗兹", "波贡马佐维克", "波兰- 甲级联赛", "LKS洛迪兹‎", "马佐夫舍地区格罗济斯克波贡‎"),
            MatchCase("阿尔及利亚", "阿尔及利亚甲组联赛", "巴拉特", "CS康桑汰", "阿尔及利亚 - 甲级联赛", "AC帕拉杜‎", "CS康士坦丁‎"),
            MatchCase("卡塔尔U23", "卡塔尔奥林匹克联赛U23", "阿尔艾利多哈U23", "夏马尔U23", "卡塔尔 - 预备队联赛", "多哈阿尔阿赫利‎", "舒马尔‎"),
            MatchCase("阿根廷预备队", "阿根廷后备联赛", "班菲特(后备)", "圣塔菲联(后备)", "阿根廷 - 职业联赛预备队", "班菲尔德‎", "圣菲联合‎"),
            MatchCase("沙特", "沙特超级联赛", "沙巴柏利雅德", "纳撒利雅德", "沙特阿拉伯 - 职业联赛", "利雅得青年‎", "利雅得胜利‎"),
            MatchCase("南美自由杯", "南美自由杯", "迈拉索尔SP", "奎托", "南美足联 - 解放者杯", "米拉索尔‎", "基多大学体育联盟‎"),
            MatchCase("格鲁吉亚", "格鲁吉亚甲组联赛", "提比利锡戴拿模", "斯帕尔", "格鲁吉亚 - 足球超级联赛", "第比利斯迪纳摩‎", "Spaeri‎")
        )

        cases.forEach { case ->
            val result = OddsMatchMatcher.score(
                OddsMatchCandidate(
                    id = 10,
                    leagueName = case.crownLeague,
                    homeTeam = case.crownHome,
                    awayTeam = case.crownAway,
                    startTime = startTime
                ),
                OddsMatchCandidate(
                    id = null,
                    leagueName = case.pinnacleLeague,
                    homeTeam = case.pinnacleHome,
                    awayTeam = case.pinnacleAway,
                    startTime = startTime
                )
            )

            assertTrue(result.score >= 0.65, "${case.name} score was ${result.score}")
            assertFalse(result.reversed, case.name)
        }
    }

    @Test
    fun `keeps unrelated matches separate`() {
        val candidate = OddsMatchCandidate(
            id = 3,
            leagueName = "Japan J1 League",
            homeTeam = "Kawasaki Frontale",
            awayTeam = "FC Tokyo",
            startTime = 1893456000000L
        )
        val incoming = OddsMatchCandidate(
            id = null,
            leagueName = "England Premier League",
            homeTeam = "Arsenal",
            awayTeam = "Chelsea",
            startTime = 1893542400000L
        )

        val result = OddsMatchMatcher.score(candidate, incoming)

        assertTrue(result.score < 0.65, "score was ${result.score}")
        assertEquals("new", result.matchMethod)
    }
}
