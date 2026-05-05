package com.wrbug.polymarketbot.service.oddsmonitor.collector.crown

import com.wrbug.polymarketbot.service.oddsmonitor.OddsFootballMatchFilter
import org.springframework.stereotype.Component
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.Year
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

@Component
class CrownResponseParser {
    fun parseLogin(xmlText: String): CrownLoginResponse {
        val root = parseRoot(xmlText)
        return CrownLoginResponse(
            status = root.childText("status").orEmpty(),
            uid = root.childText("uid")?.takeIf { it.isNotBlank() },
            messageCode = root.childText("msg")?.takeIf { it.isNotBlank() },
            message = root.childText("code_message")?.takeIf { it.isNotBlank() }
        )
    }

    fun parseGameList(xmlText: String): List<CrownGameListItem> {
        val root = parseRoot(xmlText)
        return root.elements("item").mapNotNull { item ->
            val lid = item.childText("lid")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val gidm = item.childText("gidm")
            val gid = item.childText("gid")
            val ecid = item.childText("ecid")?.takeIf { it.isNotBlank() }
            val detailId = ecid ?: gidm ?: gid ?: return@mapNotNull null
            val retimeset = item.childText("retimeset").orEmpty()
            CrownGameListItem(
                lid = lid,
                detailId = detailId,
                ecid = ecid,
                isLive = retimeset.isNotBlank() && retimeset != "0",
                isRb = item.childText("is_rb")?.takeIf { it.isNotBlank() }
            )
        }
    }

    fun parseDetailGames(xmlText: String, isLive: Boolean): List<CrownFootballMatch> {
        val root = parseRoot(xmlText)
        return root.elements("game").mapNotNull { game ->
            val fields = game.childTextMap()
            val detailIsLive = isLive || fields.indicatesLiveGame()
            val sourceMatchId = fields["gid"]?.takeIf { it.isNotBlank() } ?: fields["gidm"] ?: return@mapNotNull null
            val homeTeam = fields["team_h"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val awayTeam = fields["team_c"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val leagueName = fields["league"].orEmpty()
            if (OddsFootballMatchFilter.shouldIgnore(leagueName, homeTeam, awayTeam)) {
                return@mapNotNull null
            }
            CrownFootballMatch(
                sourceMatchId = sourceMatchId,
                leagueName = leagueName,
                homeTeam = homeTeam,
                awayTeam = awayTeam,
                startTime = fields["datetime"]?.let { parseMatchTime(it) },
                isLive = detailIsLive,
                handicaps = parseHandicaps(fields, detailIsLive),
                totals = parseTotals(fields, detailIsLive),
                moneyline = parseMoneyline(fields, detailIsLive),
                rawPayload = fields + mapOf("is_live" to detailIsLive)
            )
        }
    }

    private fun parseHandicaps(fields: Map<String, String>, isLive: Boolean): List<CrownHandicapMarket> {
        val candidates = if (isLive) {
            parseHandicapCandidates(fields, "ratio_re", "ior_reh", "ior_rec")
        } else {
            parseHandicapCandidates(fields, "ratio", "ior_rh", "ior_rc") +
                parseHandicapCandidates(fields, "ratio_r", "ior_rh", "ior_rc")
        }
        return candidates.distinctBy { listOf(it.line, it.homeOdds.stripTrailingZeros(), it.awayOdds.stripTrailingZeros()) }
    }

    private fun parseTotals(fields: Map<String, String>, isLive: Boolean): List<CrownTotalMarket> {
        val candidates = if (isLive) {
            parseTotalCandidates(fields, "ratio_rouo", "ior_rouc", "ior_rouh")
        } else {
            parseTotalCandidates(fields, "ratio_o", "ior_ouc", "ior_ouh") +
                parseTotalCandidates(fields, "ratio_ouo", "ior_ouc", "ior_ouh") +
                parseTotalCandidates(fields, "ratio_ouu", "ior_ouc", "ior_ouh")
        }
        return candidates.distinctBy { listOf(it.line, it.overOdds.stripTrailingZeros(), it.underOdds.stripTrailingZeros()) }
    }

    private fun parseHandicapCandidates(
        fields: Map<String, String>,
        ratioPrefix: String,
        homeOddsPrefix: String,
        awayOddsPrefix: String
    ): List<CrownHandicapMarket> {
        return fields.keys
            .filter { it == ratioPrefix || it.startsWith("${ratioPrefix}_") }
            .sorted()
            .mapNotNull { ratioKey ->
                val suffix = ratioKey.removePrefix(ratioPrefix)
                val line = fields[ratioKey]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val homeOdds = fields["$homeOddsPrefix$suffix"].toDecimalOrNull() ?: return@mapNotNull null
                val awayOdds = fields["$awayOddsPrefix$suffix"].toDecimalOrNull() ?: return@mapNotNull null
                CrownHandicapMarket(line, homeOdds, awayOdds)
            }
    }

    private fun parseTotalCandidates(
        fields: Map<String, String>,
        ratioPrefix: String,
        overOddsPrefix: String,
        underOddsPrefix: String
    ): List<CrownTotalMarket> {
        return fields.keys
            .filter { it == ratioPrefix || it.startsWith("${ratioPrefix}_") }
            .sorted()
            .mapNotNull { ratioKey ->
                val suffix = ratioKey.removePrefix(ratioPrefix)
                val line = fields[ratioKey]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val overOdds = fields["$overOddsPrefix$suffix"].toDecimalOrNull() ?: return@mapNotNull null
                val underOdds = fields["$underOddsPrefix$suffix"].toDecimalOrNull() ?: return@mapNotNull null
                CrownTotalMarket(line, overOdds, underOdds)
            }
    }

    private fun parseMoneyline(fields: Map<String, String>, isLive: Boolean): CrownMoneylineMarket? {
        val homeOdds = fields[if (isLive) "ior_rmh" else "ior_mh"].toDecimalOrNull() ?: return null
        val drawOdds = fields[if (isLive) "ior_rmn" else "ior_mn"].toDecimalOrNull() ?: return null
        val awayOdds = fields[if (isLive) "ior_rmc" else "ior_mc"].toDecimalOrNull() ?: return null
        return CrownMoneylineMarket(homeOdds, drawOdds, awayOdds)
    }

    private fun parseRoot(xmlText: String): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xmlText)))
        return document.documentElement
    }

    private fun parseMatchTime(value: String): Long? {
        val normalizedValue = value.trim().replace(
            Regex("""(?i)(\d{1,2}:\d{2})([ap])$""")
        ) { matchResult ->
            val suffix = if (matchResult.groupValues[2].equals("p", ignoreCase = true)) "PM" else "AM"
            "${matchResult.groupValues[1]} $suffix"
        }
        val normalized = "${Year.now().value}-$normalizedValue"
        val formatters = listOf(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("yyyy-MM-dd h:mm a", Locale.ENGLISH)
        )
        return formatters.firstNotNullOfOrNull { formatter ->
            runCatching {
                LocalDateTime.parse(normalized, formatter)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull()
        }
    }

    private fun Element.elements(tagName: String): List<Element> {
        val nodes = getElementsByTagName(tagName)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun Element.childText(name: String): String? {
        val nodes = getElementsByTagName(name)
        return (nodes.item(0) as? Element)?.textContent?.trim()
    }

    private fun Element.childTextMap(): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val children = childNodes
        for (index in 0 until children.length) {
            val child = children.item(index) as? Element ?: continue
            result[child.tagName.lowercase()] = child.textContent.trim()
        }
        return result
    }

    private fun Map<String, String>.firstPresent(vararg names: String): String? {
        return names.firstNotNullOfOrNull { name -> this[name]?.takeIf { it.isNotBlank() } }
    }

    private fun Map<String, String>.indicatesLiveGame(): Boolean {
        return firstPresent("showtype")?.equals("rb", ignoreCase = true) == true ||
            firstPresent("is_rb")?.equals("Y", ignoreCase = true) == true ||
            firstPresent("retimeset").orEmpty().let { it.isNotBlank() && it != "0" } ||
            firstPresent("ratio_re", "ratio_rouo").orEmpty().isNotBlank()
    }

    private fun String?.toDecimalOrNull(): BigDecimal? {
        val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { BigDecimal(value) }.getOrNull()
    }
}
