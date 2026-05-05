package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.util.TextEncodingUtils

object OddsFootballMatchFilter {
    private val ignoredTokens = listOf(
        "电竞",
        "esport",
        "esports",
        "esoccer",
        "e-soccer",
        "efootball",
        "e-football",
        "h2h gg",
        "gt体育",
        "特别投注",
        "特別投注",
        "附加赛",
        "附加賽",
        "playoff",
        "play-offs",
        "play off",
        "special betting",
        "specials"
    )

    fun shouldIgnore(leagueName: String?, homeTeam: String?, awayTeam: String?): Boolean {
        val normalizedText = listOfNotNull(leagueName, homeTeam, awayTeam)
            .flatMap { value -> listOf(value, TextEncodingUtils.repairMojibake(value)) }
            .joinToString(" ")
            .lowercase()
        return ignoredTokens.any { normalizedText.contains(it) }
    }
}
