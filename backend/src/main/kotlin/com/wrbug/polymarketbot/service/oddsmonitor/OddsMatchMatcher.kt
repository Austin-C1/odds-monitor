package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.util.TextEncodingUtils
import kotlin.math.abs
import kotlin.math.max

data class OddsMatchCandidate(
    val id: Long?,
    val leagueName: String,
    val homeTeam: String,
    val awayTeam: String,
    val startTime: Long?
)

data class OddsMatchScore(
    val score: Double,
    val reversed: Boolean,
    val matchMethod: String
)

object OddsMatchMatcher {
    private const val AUTO_THRESHOLD = 0.85
    private const val LOW_CONFIDENCE_THRESHOLD = 0.65

    private val aliases = mapOf(
        "kawasakifrontale" to "kawasakifrontale",
        "川崎前锋" to "kawasakifrontale",
        "川崎前鋒" to "kawasakifrontale",
        "fctokyo" to "fctokyo",
        "tokyo" to "fctokyo",
        "东京" to "fctokyo",
        "東京" to "fctokyo",
        "fagianookayama" to "fagianookayama",
        "okayama" to "fagianookayama",
        "冈山绿雉" to "fagianookayama",
        "岡山綠雉" to "fagianookayama",
        "sanfreccehiroshima" to "sanfreccehiroshima",
        "hiroshima" to "sanfreccehiroshima",
        "广岛三箭" to "sanfreccehiroshima",
        "廣島三箭" to "sanfreccehiroshima",
        "intermiamicf" to "intermiami",
        "intermiami" to "intermiami",
        "迈阿密国际" to "intermiami",
        "邁阿密國際" to "intermiami",
        "orlandocitysc" to "orlandocity",
        "orlandocity" to "orlandocity",
        "奥兰多城" to "orlandocity",
        "奧蘭多城" to "orlandocity",
        "philadelphiaunion" to "philadelphiaunion",
        "费城联" to "philadelphiaunion",
        "费城联合" to "philadelphiaunion",
        "費城聯" to "philadelphiaunion",
        "nashvillesc" to "nashville",
        "nashville" to "nashville",
        "纳什维尔" to "nashville",
        "納什維爾" to "nashville",
        "sandiegofc" to "sandiego",
        "sandiego" to "sandiego",
        "圣地牙哥" to "sandiego",
        "聖地牙哥" to "sandiego",
        "圣迭戈" to "sandiego",
        "losangelesfc" to "losangelesfc",
        "lafc" to "losangelesfc",
        "洛杉矶" to "losangelesfc",
        "洛杉磯" to "losangelesfc",
        "newyorkredbulls" to "newyorkredbulls",
        "纽约红牛" to "newyorkredbulls",
        "紐約紅牛" to "newyorkredbulls",
        "fcdallas" to "fcdallas",
        "dallas" to "fcdallas",
        "达拉斯" to "fcdallas",
        "達拉斯" to "fcdallas",
        "losangelesgalaxy" to "lagalaxy",
        "lagalaxy" to "lagalaxy",
        "洛杉矶银河" to "lagalaxy",
        "洛杉磯銀河" to "lagalaxy",
        "vancouverwhitecapsfc" to "vancouverwhitecaps",
        "vancouverwhitecaps" to "vancouverwhitecaps",
        "温哥华白帽" to "vancouverwhitecaps",
        "溫哥華白帽" to "vancouverwhitecaps",
        "manchesterunitedfc" to "manchesterunited",
        "manchesterunited" to "manchesterunited",
        "manutd" to "manchesterunited",
        "曼联" to "manchesterunited",
        "曼聯" to "manchesterunited",
        "liverpoolfc" to "liverpool",
        "liverpool" to "liverpool",
        "利物浦" to "liverpool",
        "evertonfc" to "everton",
        "everton" to "everton",
        "埃弗顿" to "everton",
        "埃弗頓" to "everton",
        "manchestercityfc" to "manchestercity",
        "manchestercity" to "manchestercity",
        "mancity" to "manchestercity",
        "曼城" to "manchestercity"
    )

    private val observedPlatformAliases = mapOf(
        "图库姆斯" to "tukums",
        "图库姆斯2000" to "tukums",
        "ogreunited" to "ogreunited",
        "奥格雷联" to "ogreunited",
        "海於格松b队" to "haugesundb",
        "豪格松二队" to "haugesundb",
        "oddbkii" to "oddb",
        "奥特b队" to "oddb",
        "pk35赫尔辛基" to "pk35",
        "pk35" to "pk35",
        "mp米克力" to "mpmikkeli",
        "米克利" to "mpmikkeli",
        "泰达姆恩" to "altadamun",
        "阿尔塔达蒙" to "altadamun",
        "吉特斯女" to "jitex",
        "吉泰克斯bk" to "jitex",
        "库恩卡青年" to "cuencayouth",
        "昆卡青年队" to "cuencayouth",
        "昆巴亚" to "cumbaya",
        "坎巴亚金融" to "cumbaya",
        "米伦拿列奥" to "millonarios",
        "百万富翁队" to "millonarios",
        "康菲安卡se" to "confianca",
        "孔菲昂萨" to "confianca",
        "福塔雷萨ce" to "fortaleza",
        "福塔雷萨" to "fortaleza",
        "lks罗兹" to "lkslodz",
        "lks洛迪兹" to "lkslodz",
        "波贡马佐维克" to "pogonmazowiecki",
        "马佐夫舍地区格罗济斯克波贡" to "pogonmazowiecki",
        "巴拉特" to "paradou",
        "ac帕拉杜" to "paradou",
        "cs康桑汰" to "csconstantine",
        "cs康士坦丁" to "csconstantine",
        "阿尔艾利多哈u23" to "alahlidoha",
        "多哈阿尔阿赫利" to "alahlidoha",
        "夏马尔u23" to "alshamal",
        "舒马尔" to "alshamal",
        "班菲特后备" to "banfield",
        "班菲尔德" to "banfield",
        "圣塔菲联后备" to "unionsantafe",
        "圣菲联合" to "unionsantafe",
        "沙巴柏利雅德" to "alshabab",
        "利雅得青年" to "alshabab",
        "纳撒利雅德" to "alnassr",
        "利雅得胜利" to "alnassr",
        "迈拉索尔sp" to "mirassol",
        "米拉索尔" to "mirassol",
        "奎托" to "lduquito",
        "基多大学体育联盟" to "lduquito",
        "司雷普纳" to "sleipner",
        "斯莱坡尼尔" to "sleipner",
        "西里安斯卡" to "syrianska",
        "叙利扬斯卡" to "syrianska",
        "提比利锡戴拿模" to "dinamotbilisi",
        "第比利斯迪纳摩" to "dinamotbilisi",
        "斯帕尔" to "spaeri",
        "spaeri" to "spaeri",
        "dhj哈斯沙尼亚" to "difaaeljadida",
        "贾迪达迪法亚" to "difaaeljadida",
        "哈斯沙尼亚" to "hassaniaagadir",
        "阿加迪尔" to "hassaniaagadir",
        "sjk阿卡泰米阿ii队" to "sjkakatemia2",
        "阿克特米亚二队" to "sjkakatemia2",
        "修玛乌尔禾" to "huimaurho",
        "huimaurho" to "huimaurho"
    )

    fun score(existing: OddsMatchCandidate, incoming: OddsMatchCandidate): OddsMatchScore {
        val normalScore = combinedScore(
            existing = existing,
            incoming = incoming,
            incomingHome = incoming.homeTeam,
            incomingAway = incoming.awayTeam
        )
        val reversedScore = combinedScore(
            existing = existing,
            incoming = incoming,
            incomingHome = incoming.awayTeam,
            incomingAway = incoming.homeTeam
        )
        val reversed = reversedScore > normalScore
        val bestScore = max(normalScore, reversedScore)
        return OddsMatchScore(
            score = bestScore,
            reversed = reversed,
            matchMethod = when {
                bestScore >= AUTO_THRESHOLD && reversed -> "reverse_auto"
                bestScore >= AUTO_THRESHOLD -> "auto"
                bestScore >= LOW_CONFIDENCE_THRESHOLD && reversed -> "reverse_low_confidence"
                bestScore >= LOW_CONFIDENCE_THRESHOLD -> "low_confidence"
                else -> "new"
            }
        )
    }

    fun shouldMerge(score: OddsMatchScore): Boolean = score.score >= LOW_CONFIDENCE_THRESHOLD

    private fun combinedScore(
        existing: OddsMatchCandidate,
        incoming: OddsMatchCandidate,
        incomingHome: String,
        incomingAway: String
    ): Double {
        val homeScore = nameSimilarity(existing.homeTeam, incomingHome)
        val awayScore = nameSimilarity(existing.awayTeam, incomingAway)
        val timeScore = timeSimilarity(existing.startTime, incoming.startTime)
        val leagueScore = nameSimilarity(existing.leagueName, incoming.leagueName)
        return homeScore * 0.325 + awayScore * 0.325 + timeScore * 0.25 + leagueScore * 0.10
    }

    private fun timeSimilarity(left: Long?, right: Long?): Double {
        if (left == null || right == null) {
            return 0.55
        }
        val diffMinutes = abs(left - right) / 60_000.0
        return when {
            diffMinutes <= 5 -> 1.0
            diffMinutes >= 24 * 60 -> 0.0
            diffMinutes <= 60 -> 1.0 - ((diffMinutes - 5) / 55.0) * 0.25
            else -> max(0.0, 0.75 - ((diffMinutes - 60) / (23 * 60.0)) * 0.75)
        }
    }

    private fun nameSimilarity(left: String, right: String): Double {
        val normalizedLeft = canonical(left)
        val normalizedRight = canonical(right)
        if (normalizedLeft.isBlank() || normalizedRight.isBlank()) {
            return 0.0
        }
        if (normalizedLeft == normalizedRight) {
            return 1.0
        }
        if (normalizedLeft.contains(normalizedRight) || normalizedRight.contains(normalizedLeft)) {
            return 0.88
        }
        return diceCoefficient(normalizedLeft, normalizedRight)
    }

    private fun canonical(value: String): String {
        val normalized = TextEncodingUtils.repairMojibake(value)
            .lowercase()
            .replace("&", "and")
            .replace(Regex("""[\s\p{Punct}\p{C}]+"""), "")
            .trim()
        return aliases[normalized] ?: observedPlatformAliases[normalized] ?: normalized
    }

    private fun diceCoefficient(left: String, right: String): Double {
        if (left == right) return 1.0
        if (left.length < 2 || right.length < 2) return 0.0

        val leftBigrams = left.windowed(2).groupingBy { it }.eachCount().toMutableMap()
        var intersection = 0
        right.windowed(2).forEach { bigram ->
            val count = leftBigrams[bigram] ?: 0
            if (count > 0) {
                intersection += 1
                leftBigrams[bigram] = count - 1
            }
        }
        return (2.0 * intersection) / (left.length - 1 + right.length - 1)
    }
}
