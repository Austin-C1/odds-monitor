package com.wrbug.polymarketbot.service.oddsmonitor.collector.polymarket

import com.wrbug.polymarketbot.dto.MarketBettingEventDetail
import com.wrbug.polymarketbot.dto.MarketBettingMarketDetail
import com.wrbug.polymarketbot.dto.MarketBettingOutcomeDetail
import com.wrbug.polymarketbot.service.oddsmonitor.OddsLineDisplayFormatter
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

data class PolymarketFootballMatch(
    val sourceMatchId: String,
    val leagueName: String,
    val homeTeam: String,
    val awayTeam: String,
    val startTime: Long?,
    val rawPayload: Map<String, Any?>
)

data class PolymarketMappedOddsRow(
    val marketType: String,
    val lineValue: String?,
    val selectionName: String,
    val oddsValue: BigDecimal,
    val capturedAt: Long,
    val rawPayload: Map<String, Any?>
)

data class PolymarketMappedEvent(
    val match: PolymarketFootballMatch,
    val rows: List<PolymarketMappedOddsRow>
)

@Component
class PolymarketOddsMapper {
    fun map(detail: MarketBettingEventDetail, capturedAt: Long): PolymarketMappedEvent? {
        if (!isFootballEvent(detail)) {
            return null
        }

        val teams = parseTeams(detail.event.title) ?: return null
        val rows = detail.markets.flatMap { market ->
            mapMarket(market, teams.first, teams.second, capturedAt)
        }
        if (rows.isEmpty()) {
            return null
        }
        val startTime = parseEventTime(
            slug = detail.event.slug,
            title = detail.event.title,
            apiTime = detail.event.startDate ?: detail.event.endDate
        )
        return PolymarketMappedEvent(
            match = PolymarketFootballMatch(
                sourceMatchId = detail.event.slug.ifBlank { detail.event.id },
                leagueName = detail.event.category ?: "Polymarket",
                homeTeam = teams.first,
                awayTeam = teams.second,
                startTime = startTime,
                rawPayload = mapOf(
                    "event_id" to detail.event.id,
                    "slug" to detail.event.slug,
                    "title" to detail.event.title,
                    "category" to detail.event.category,
                    "url" to detail.event.url,
                    "is_live" to (startTime != null && startTime <= capturedAt)
                )
            ),
            rows = rows
        )
    }

    private fun mapMarket(
        market: MarketBettingMarketDetail,
        homeTeam: String,
        awayTeam: String,
        capturedAt: Long
    ): List<PolymarketMappedOddsRow> {
        val type = market.marketType.trim().lowercase()
        return when (type) {
            "moneyline" -> market.outcomes.mapNotNull { outcome ->
                selectionForMoneyline(outcome, market, homeTeam, awayTeam)?.let { selection ->
                    outcome.toRow("moneyline", null, selection, market, capturedAt)
                }
            }
            "totals" -> market.outcomes.mapNotNull { outcome ->
                when (outcome.name.trim().lowercase()) {
                    "over", "yes" -> outcome.toRow("total", market.line, "over", market, capturedAt)
                    "under", "no" -> outcome.toRow("total", market.line, "under", market, capturedAt)
                    else -> null
                }
            }
            "spreads" -> market.outcomes.mapNotNull { outcome ->
                val selection = selectionForTeamOutcome(outcome.name, homeTeam, awayTeam) ?: return@mapNotNull null
                outcome.toRow("handicap", market.line, selection, market, capturedAt)
            }
            else -> emptyList()
        }
    }

    private fun MarketBettingOutcomeDetail.toRow(
        marketType: String,
        lineValue: String?,
        selectionName: String,
        market: MarketBettingMarketDetail,
        capturedAt: Long
    ): PolymarketMappedOddsRow? {
        val odds = odds.toBigDecimalOrNull() ?: return null
        return PolymarketMappedOddsRow(
            marketType = marketType,
            lineValue = OddsLineDisplayFormatter.format(marketType, lineValue),
            selectionName = selectionName,
            oddsValue = odds,
            capturedAt = capturedAt,
            rawPayload = mapOf(
                "market_id" to market.id,
                "condition_id" to market.conditionId,
                "question" to market.question,
                "market_type" to market.marketType,
                "outcome" to name,
                "token_id" to tokenId
            )
        )
    }

    private fun selectionForMoneyline(
        outcome: MarketBettingOutcomeDetail,
        market: MarketBettingMarketDetail,
        homeTeam: String,
        awayTeam: String
    ): String? {
        val name = outcome.name.trim()
        return when {
            name.equals("yes", ignoreCase = true) -> selectionForMoneylineQuestion(market.question, homeTeam, awayTeam)
            name.equals("no", ignoreCase = true) -> null
            name.equals("draw", ignoreCase = true) -> "draw"
            selectionForTeamOutcome(name, homeTeam, awayTeam) != null -> selectionForTeamOutcome(name, homeTeam, awayTeam)
            else -> null
        }
    }

    private fun selectionForMoneylineQuestion(question: String, homeTeam: String, awayTeam: String): String? {
        val normalized = normalize(question)
        return when {
            "draw" in normalized -> "draw"
            normalized.contains(normalize(homeTeam)) -> "home"
            normalized.contains(normalize(awayTeam)) -> "away"
            else -> null
        }
    }

    private fun selectionForTeamOutcome(name: String, homeTeam: String, awayTeam: String): String? {
        val normalized = normalize(name)
        return when {
            normalized == normalize(homeTeam) -> "home"
            normalized == normalize(awayTeam) -> "away"
            normalized.contains(normalize(homeTeam)) -> "home"
            normalized.contains(normalize(awayTeam)) -> "away"
            else -> null
        }
    }

    private fun parseTeams(title: String): Pair<String, String>? {
        val normalized = title.replace(" vs. ", " vs ", ignoreCase = true)
        val separators = listOf(" vs ", " v ", " at ")
        separators.forEach { separator ->
            val index = normalized.indexOf(separator, ignoreCase = true)
            if (index > 0) {
                val home = normalized.substring(0, index).trim()
                val away = normalized.substring(index + separator.length).trim()
                if (home.isNotBlank() && away.isNotBlank()) {
                    return home to away
                }
            }
        }
        return null
    }

    private fun isFootballEvent(detail: MarketBettingEventDetail): Boolean {
        val text = buildString {
            append(detail.event.title)
            append(' ')
            append(detail.event.slug)
            append(' ')
            append(detail.event.category.orEmpty())
            detail.markets.forEach { market ->
                append(' ')
                append(market.slug)
                append(' ')
                append(market.question)
                append(' ')
                append(market.marketType)
            }
        }.lowercase()

        val nonFootballTerms = listOf(
            "basketball",
            "nba",
            "wnba",
            "ncaa basketball",
            "cbb",
            "euroleague",
            "nfl",
            "american football",
            "college football",
            "super bowl"
        )
        if (nonFootballTerms.any { it in text }) {
            return false
        }

        val footballTerms = listOf(
            "soccer",
            "football",
            "fifa",
            "uefa",
            "champions league",
            "premier league",
            "epl",
            "la liga",
            "serie a",
            "bundesliga",
            "ligue 1",
            "mls",
            "j-league",
            "j league",
            "copa",
            "libertadores",
            "europa",
            "concacaf",
            "afc champions"
        )
        return footballTerms.any { it in text }
    }

    private fun parseEventTime(slug: String, title: String, apiTime: String?): Long? {
        val apiTimestamp = parseTime(apiTime)
        val textDate = parseDateFromText("$slug $title")
        if (textDate == null) {
            return apiTimestamp
        }

        val textTimestamp = textDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val apiDate = apiTimestamp?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
        return if (apiDate == null || apiDate != textDate) {
            textTimestamp
        } else {
            apiTimestamp
        }
    }

    private fun parseTime(value: String?): Long? {
        return value?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    }

    private fun parseDateFromText(value: String): LocalDate? {
        val match = Regex("""\b(20\d{2})[-_](\d{2})[-_](\d{2})\b""").find(value) ?: return null
        return runCatching {
            LocalDate.of(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt()
            )
        }.getOrNull()
    }

    private fun normalize(value: String): String {
        return value.lowercase().replace(Regex("""[\s\p{Punct}\p{C}]+"""), "")
    }
}
