package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.entity.OddsMarket
import com.wrbug.polymarketbot.entity.OddsMatch
import com.wrbug.polymarketbot.entity.OddsPlatformMatch
import com.wrbug.polymarketbot.util.DateUtils
import com.wrbug.polymarketbot.util.TextEncodingUtils
import java.math.BigDecimal

internal fun oddsMonitorTemplateType(matchPhase: OddsMonitorMatchPhase): String {
    return when (matchPhase) {
        OddsMonitorMatchPhase.LIVE -> "ODDS_LIVE_PUSH"
        OddsMonitorMatchPhase.PREMATCH -> "ODDS_PREMATCH_PUSH"
    }
}

internal fun buildOddsChangeTemplateVariables(
    matchName: String,
    leagueName: String,
    markets: List<OddsChangeNotificationMarketItem>,
    expectedSources: List<String>,
    timestampText: String,
    liveContext: OddsLiveMatchContext?
): Map<String, String> {
    return mapOf(
        "match_title" to escapeHtml(TextEncodingUtils.repairMojibake(matchName)),
        "league_name" to escapeHtml(TextEncodingUtils.repairMojibake(leagueName)),
        "market_lines" to buildOddsChangeMarketLines(markets, expectedSources),
        "filter_summary" to "动水通过 / 合水通过",
        "elapsed_minutes" to formatElapsedMinutes(liveContext?.elapsedMinutes),
        "score_text" to escapeHtml(liveContext?.scoreText?.takeIf { it.isNotBlank() } ?: "未知"),
        "time" to timestampText
    )
}

fun buildOddsChangeAlertTitle(match: OddsPlatformMatch): String {
    return "赔率变动：${TextEncodingUtils.repairMojibake(match.rawHomeTeam)} vs ${TextEncodingUtils.repairMojibake(match.rawAwayTeam)}"
}

fun buildOddsChangeAlertMessage(
    match: OddsPlatformMatch,
    market: OddsMarket,
    previousOdds: BigDecimal,
    currentOdds: BigDecimal
): String {
    val marketLabel = listOf(
        market.marketType,
        market.selectionName,
        OddsLineDisplayFormatter.format(market.marketType, market.lineValue)
    ).mapNotNull { it?.takeIf { value -> value.isNotBlank() } }.joinToString(" ")

    return """<b>${escapeHtml(buildOddsChangeAlertTitle(match))}</b>

联赛: ${escapeHtml(TextEncodingUtils.repairMojibake(match.rawLeagueName))}
比赛: ${escapeHtml(TextEncodingUtils.repairMojibake(match.rawHomeTeam))} vs ${escapeHtml(TextEncodingUtils.repairMojibake(match.rawAwayTeam))}
盘口: ${escapeHtml(marketLabel)}
平台: ${escapeHtml(platformLabel(market.sourceKey))}
变化: <code>${formatOdds(previousOdds, market.sourceKey, market.marketType)} -> ${formatOdds(currentOdds, market.sourceKey, market.marketType)}</code>
时间: <code>${DateUtils.formatDateTime()}</code>"""
}

fun buildMergedOddsChangeAlertMessage(
    matchName: String,
    leagueName: String,
    marketLabel: String,
    changes: List<OddsChangeNotificationItem>,
    expectedSources: List<String>,
    timestampText: String,
    liveContext: OddsLiveMatchContext? = null
): String {
    return buildMergedOddsChangeAlertMessage(
        matchName = matchName,
        leagueName = leagueName,
        markets = listOf(OddsChangeNotificationMarketItem(marketLabel = marketLabel, changes = changes)),
        expectedSources = expectedSources,
        timestampText = timestampText,
        liveContext = liveContext
    )
}

fun buildMergedOddsChangeAlertMessage(
    matchName: String,
    leagueName: String,
    markets: List<OddsChangeNotificationMarketItem>,
    expectedSources: List<String>,
    timestampText: String,
    liveContext: OddsLiveMatchContext? = null
): String {
    val displayMatchName = TextEncodingUtils.repairMojibake(matchName)
    val displayLeagueName = TextEncodingUtils.repairMojibake(leagueName)
    return buildString {
        appendLine("<b>${escapeHtml("赔率变动：$displayMatchName")}</b>")
        appendLine()
        appendLine("联赛：${escapeHtml(displayLeagueName)}")
        liveContext?.let { context ->
            appendLine("进行：${formatElapsedMinutes(context.elapsedMinutes)}")
            appendLine("比分：${escapeHtml(context.scoreText?.takeIf { it.isNotBlank() } ?: "未知")}")
        }
        appendLine()
        markets.forEachIndexed { index, market ->
            if (index > 0) {
                appendLine()
            }
            appendLine("盘口：${escapeHtml(TextEncodingUtils.repairMojibake(market.marketLabel))}")
            val changesBySource = market.changes.associateBy { it.sourceKey }
            expectedSources.forEach { sourceKey ->
                val change = changesBySource[sourceKey]
                if (change == null) {
                    appendLine("${platformLabel(sourceKey)}：无对应盘口")
                } else {
                    appendLine("${platformLabel(sourceKey)}：${formatMergedOdds(change.previousOdds, change.sourceKey, market.marketType)} -> ${formatMergedOdds(change.currentOdds, change.sourceKey, market.marketType)}")
                }
            }
        }
        appendLine()
        appendLine("筛选：动水通过 / 合水通过")
        append("时间：$timestampText")
    }
}

internal fun OddsMarket.displayLabel(): String {
    val marketLabel = when (marketType.lowercase()) {
        "handicap" -> "让球"
        "total" -> "大小球"
        "moneyline" -> "胜平负"
        else -> marketType
    }
    val selectionLabel = when (selectionName.lowercase()) {
        "home" -> "主队"
        "away" -> "客队"
        "over" -> "大球"
        "under" -> "小球"
        "draw" -> "平"
        else -> selectionName
    }
    return listOf(marketLabel, selectionLabel, OddsLineDisplayFormatter.format(marketType, lineValue))
        .mapNotNull { it?.takeIf { value -> value.isNotBlank() } }
        .joinToString(" ")
}

internal fun OddsMarket.normalizedLineValue(): String? {
    return OddsLineDisplayFormatter.format(marketType, lineValue)
        ?.takeIf { it.isNotBlank() }
}

internal fun notificationMatchName(platformMatch: OddsPlatformMatch, standardMatch: OddsMatch?): String {
    val standardName = standardMatch?.let { "${it.homeTeam} vs ${it.awayTeam}" }
    val platformName = "${platformMatch.rawHomeTeam} vs ${platformMatch.rawAwayTeam}"
    return bestNotificationText(platformName, standardName)
}

internal fun notificationMergeCandidate(platformMatch: OddsPlatformMatch, standardMatch: OddsMatch?): OddsMatchCandidate {
    return OddsMatchCandidate(
        id = standardMatch?.id,
        leagueName = notificationLeagueName(platformMatch, standardMatch),
        homeTeam = TextEncodingUtils.repairMojibake(platformMatch.rawHomeTeam),
        awayTeam = TextEncodingUtils.repairMojibake(platformMatch.rawAwayTeam),
        startTime = standardMatch?.startTime ?: platformMatch.rawStartTime
    )
}

internal fun notificationLeagueName(platformMatch: OddsPlatformMatch, standardMatch: OddsMatch?): String {
    return bestNotificationText(platformMatch.rawLeagueName, standardMatch?.leagueName)
}

internal fun bestNotificationText(current: String, candidate: String?): String {
    val normalizedCurrent = TextEncodingUtils.repairMojibake(current).trim()
    val normalizedCandidate = TextEncodingUtils.repairMojibake(candidate.orEmpty()).trim()
    return when {
        normalizedCandidate.isBlank() -> normalizedCurrent
        normalizedCurrent.isBlank() -> normalizedCandidate
        isGenericMatchName(normalizedCurrent) -> normalizedCandidate
        normalizedCurrent.contains("特别投注") && !normalizedCandidate.contains("特别投注") -> normalizedCandidate
        else -> normalizedCurrent
    }
}

@Suppress("UNUSED_PARAMETER")
internal fun asianWaterOdds(sourceKey: String, marketType: String?, value: BigDecimal): BigDecimal {
    return value
}

private fun buildOddsChangeMarketLines(
    markets: List<OddsChangeNotificationMarketItem>,
    expectedSources: List<String>
): String {
    return buildString {
        markets.forEachIndexed { index, market ->
            if (index > 0) {
                appendLine()
            }
            appendLine("盘口：${escapeHtml(TextEncodingUtils.repairMojibake(market.marketLabel))}")
            val changesBySource = market.changes.associateBy { it.sourceKey }
            expectedSources.forEach { sourceKey ->
                val change = changesBySource[sourceKey]
                if (change == null) {
                    appendLine("${platformLabel(sourceKey)}：无对应盘口")
                } else {
                    appendLine("${platformLabel(sourceKey)}：${formatMergedOdds(change.previousOdds, change.sourceKey, market.marketType)} -> ${formatMergedOdds(change.currentOdds, change.sourceKey, market.marketType)}")
                }
            }
        }
    }.trimEnd()
}

private fun formatElapsedMinutes(elapsedMinutes: Int?): String {
    return elapsedMinutes?.let { "第 $it 分钟" } ?: "未知"
}

private fun platformLabel(sourceKey: String): String {
    return when (sourceKey) {
        "crown" -> "皇冠"
        else -> sourceKey
    }
}

private fun isGenericMatchName(value: String): Boolean {
    val normalized = value.replace(" ", "")
    return normalized in setOf("主场vs客场", "主队vs客队", "homevsaway", "hometeamvsawayteam")
}

private fun formatMergedOdds(value: BigDecimal, sourceKey: String, marketType: String?): String {
    val displayValue = asianWaterOdds(sourceKey, marketType, value)
    val normalized = displayValue.stripTrailingZeros()
    return if (normalized.scale() < 2) {
        displayValue.setScale(2).toPlainString()
    } else {
        normalized.toPlainString()
    }
}

private fun formatOdds(value: BigDecimal, sourceKey: String, marketType: String): String {
    return asianWaterOdds(sourceKey, marketType, value).stripTrailingZeros().toPlainString()
}

private fun escapeHtml(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
