package com.wrbug.polymarketbot.service.oddsmonitor.collector.pinnacle

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.springframework.stereotype.Component
import java.math.BigDecimal

@Component
class PinnacleFootballParser {
    fun parse(html: String): List<PinnacleFootballMatch> {
        if (html.isBlank()) {
            return emptyList()
        }

        val document = Jsoup.parse(html)
        val leagueElements = document.select("div.league")
        if (leagueElements.isEmpty()) {
            return document.select("table.events")
                .mapNotNull { parseTable(it, "unknown", "") }
                .filter { !it.isLive }
        }

        return leagueElements.flatMap { league ->
            val leagueName = parseLeagueName(league)
            val leagueId = league.id().removePrefix("lg")
            val tables = findEventTablesAfter(league)
            tables.mapNotNull { parseTable(it, leagueName, leagueId) }
        }.filter { !it.isLive }
    }

    private fun parseLeagueName(league: Element): String {
        val spanText = league.select("span")
            .map { it.text().trim() }
            .firstOrNull { isLeagueNameCandidate(it) }

        return (spanText ?: cleanLeagueText(league.text())).replace(Regex("\\s+"), " ").trim()
            .ifBlank { "unknown" }
    }

    private fun isLeagueNameCandidate(text: String): Boolean {
        if (text.isBlank()) {
            return false
        }
        val rejectedFragments = listOf(
            "已达到",
            "可添加",
            "最爱",
            "tooltip",
            "favorite",
            "limit"
        )
        return rejectedFragments.none { text.contains(it, ignoreCase = true) }
    }

    private fun cleanLeagueText(text: String): String {
        return text
            .replace("您已达到可添加最爱的上限。", "")
            .replace(Regex("您已达到[^。]*。"), "")
            .trim()
    }

    private fun findEventTablesAfter(league: Element): List<Element> {
        val tables = mutableListOf<Element>()
        var cursor = league.nextElementSibling()
        while (cursor != null && !cursor.hasClass("league")) {
            when {
                cursor.tagName().equals("table", ignoreCase = true) && cursor.hasClass("events") -> tables += cursor
                else -> cursor.selectFirst("table.events")?.let { tables += it }
            }
            if (tables.isNotEmpty()) {
                break
            }
            cursor = cursor.nextElementSibling()
        }
        return tables
    }

    private fun parseTable(table: Element, leagueName: String, leagueId: String): PinnacleFootballMatch? {
        val sourceMatchId = table.id().removePrefix("e")
            .ifBlank { table.selectFirst("tr[data-eid]")?.attr("data-eid").orEmpty() }
        if (sourceMatchId.isBlank()) {
            return null
        }

        val teams = table.select("span.sel, span.favSel")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (teams.size < 2) {
            return null
        }

        val firstEventRow = table.selectFirst("tr[data-eid]")
        val score = firstEventRow?.attr("data-score").orEmpty()
        val isLive = score.isNotBlank()
        val startTime = table.selectFirst("a.odds[data-date]")?.attr("data-date")?.toLongOrNull()
        val handicaps = parseHandicaps(table)
        val totals = parseTotals(table)
        val moneyline = parseMoneyline(table)

        if (handicaps.isEmpty() && totals.isEmpty() && moneyline == null) {
            return null
        }

        return PinnacleFootballMatch(
            sourceMatchId = sourceMatchId,
            leagueName = leagueName,
            homeTeam = teams[0],
            awayTeam = teams[1],
            startTime = startTime,
            isLive = isLive,
            handicaps = handicaps,
            totals = totals,
            moneyline = moneyline,
            rawPayload = mapOf(
                "event_id" to sourceMatchId,
                "league_id" to leagueId,
                "league_name" to leagueName,
                "home_team" to teams[0],
                "away_team" to teams[1],
                "start_time" to startTime,
                "is_live" to isLive,
                "score" to score,
                "handicaps" to handicaps,
                "totals" to totals,
                "moneyline" to moneyline
            )
        )
    }

    private fun parseHandicaps(table: Element): List<PinnacleHandicapMarket> {
        return table.select("td.col-hdp[data-period=0]")
            .mapNotNull { parseHandicapCell(it) }
    }

    private fun parseTotals(table: Element): List<PinnacleTotalMarket> {
        return table.select("td.col-ou[data-period=0]")
            .mapNotNull { parseTotalCell(it) }
    }

    private fun parseHandicapCell(cell: Element): PinnacleHandicapMarket? {
        val links = cell.select("a.odds")
        if (links.size < 2) {
            return null
        }
        val line = links.asSequence()
            .map { it.selectFirst("p.prefix-hdp")?.text()?.trim().orEmpty() }
            .firstOrNull { it.isNotBlank() }
            ?: return null
        val homeOdds = parseOdds(links[0]) ?: return null
        val awayOdds = parseOdds(links[1]) ?: return null
        return PinnacleHandicapMarket(line, homeOdds, awayOdds)
    }

    private fun parseTotalCell(cell: Element): PinnacleTotalMarket? {
        val links = cell.select("a.odds")
        if (links.size < 2) {
            return null
        }
        val line = links.asSequence()
            .map { it.selectFirst("p.prefix-ou")?.text()?.trim().orEmpty() }
            .firstOrNull { it.isNotBlank() }
            ?: return null
        val overOdds = parseOdds(links[0]) ?: return null
        val underOdds = parseOdds(links[1]) ?: return null
        return PinnacleTotalMarket(line, overOdds, underOdds)
    }

    private fun parseMoneyline(table: Element): PinnacleMoneylineMarket? {
        val links = table.select("td.col-ml[data-period=0] a.odds")
        if (links.size < 3) {
            return null
        }
        val homeOdds = parseOdds(links[0]) ?: return null
        val drawOdds = parseOdds(links[1]) ?: return null
        val awayOdds = parseOdds(links[2]) ?: return null
        return PinnacleMoneylineMarket(homeOdds, drawOdds, awayOdds)
    }

    private fun parseOdds(link: Element): BigDecimal? {
        val text = link.selectFirst("span")?.text()?.trim().orEmpty()
        return text.takeIf { it.isNotBlank() }?.let { runCatching { BigDecimal(it) }.getOrNull() }
    }
}
