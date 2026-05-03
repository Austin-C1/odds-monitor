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
        return aliases[normalized] ?: normalized
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
