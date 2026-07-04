package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.util.TextEncodingUtils
import org.springframework.stereotype.Component
import java.util.Locale

@Component
class OddsMonitorDisplayMapper {
    fun sourceDisplayName(sourceKey: String, displayName: String): String {
        return when (sourceKey.lowercase(Locale.ROOT)) {
            "crown" -> "皇冠"
            else -> TextEncodingUtils.repairMojibake(displayName).ifBlank { sourceKey }
        }
    }

    fun alertTitle(title: String, message: String): String {
        val repairedTitle = telegramHtmlToPlainText(TextEncodingUtils.repairMojibake(title))
        if (!containsLegacyTemplateCode(repairedTitle)) {
            return repairedTitle
        }

        val matchName = extractMatchNameFromAlertMessage(message)
        return matchName?.takeIf { it.isNotBlank() }?.let { "赔率变动：$it" } ?: "赔率变动"
    }

    fun alertMessage(message: String): String {
        val repaired = TextEncodingUtils.repairMojibake(message)
        val cleaned = if (containsLegacyTemplateCode(repaired)) {
            repaired.lines()
                .filterNot { line -> containsLegacyTemplateCode(line) }
                .joinToString("\n")
        } else {
            repaired
        }
        return telegramHtmlToPlainText(cleaned)
    }

    fun leagueName(value: String): String {
        val repaired = TextEncodingUtils.repairMojibake(value).trim()
        return leagueNameAliases[repaired.lowercase(Locale.ROOT)]
            ?: canonicalOddsLeagueName(repaired)
            ?: repaired
    }

    fun teamName(value: String): String {
        val repaired = TextEncodingUtils.repairMojibake(value).trim()
        return teamNameAliases[repaired.lowercase(Locale.ROOT)] ?: repaired
    }

    fun matchStatus(value: String): String {
        return when (TextEncodingUtils.repairMojibake(value).lowercase(Locale.ROOT)) {
            "scheduled", "prematch", "not_started" -> "赛前"
            "live", "inplay", "in_play" -> "滚球"
            "finished", "closed" -> "完场"
            "cancelled", "canceled" -> "取消"
            else -> TextEncodingUtils.repairMojibake(value)
        }
    }

    private fun extractMatchNameFromAlertMessage(message: String): String? {
        val plainText = telegramHtmlToPlainText(TextEncodingUtils.repairMojibake(message))
        return Regex("""比赛[:：]\s*(.*?)(?=\s+(?:进行|比分|盘口|筛选|时间)[:：]|\r?\n|$)""")
            .find(plainText)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
    }

    private fun containsLegacyTemplateCode(value: String): Boolean {
        return value.contains("TextEncodingUtils.repairMojibake") ||
            value.contains("formatMergedOdds(") ||
            value.contains("escapeHtml(")
    }

    private fun telegramHtmlToPlainText(value: String): String {
        var text = value
        text = Regex("""<a\s+href="[^"]*">([^<]*)</a>""", RegexOption.IGNORE_CASE)
            .replace(text) { match -> match.groupValues[1] }
        text = Regex("""</?(?:b|i|code)>""", RegexOption.IGNORE_CASE).replace(text, "")
        return text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .trim()
    }

    private val teamNameAliases = mapOf(
        "arsenal" to "阿森纳",
        "chelsea" to "切尔西",
        "real madrid" to "皇家马德里",
        "barcelona" to "巴塞罗那",
        "inter" to "国际米兰",
        "inter milan" to "国际米兰",
        "bayern munich" to "拜仁慕尼黑",
        "fc bayern munich" to "拜仁慕尼黑",
        "fc tokyo" to "东京FC",
        "kawasaki frontale" to "川崎前锋",
        "okayama" to "冈山绿雉",
        "hiroshima" to "广岛三箭",
        "volendam" to "沃伦丹",
        "roda jc" to "罗达JC",
        "roda-jc" to "罗达JC",
        "toronto international" to "多伦多国际",
        "vancouver fc" to "温哥华FC",
        "hfx wanderers" to "哈利法克斯流浪者",
        "forge" to "弗尔格"
    )

    private val leagueNameAliases = mapOf(
        "canada premier league" to "加拿大超级联赛",
        "netherlands eerste divisie" to "荷兰乙组联赛",
        "japan j1 league" to "日本J1百年构想联赛",
        "england premier league" to "英格兰超级联赛",
        "soccer" to "足球"
    )
}
