package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.dto.OddsHistoryPointDto
import com.wrbug.polymarketbot.dto.OddsMetricDto
import com.wrbug.polymarketbot.dto.OddsMonitorDashboardDto
import com.wrbug.polymarketbot.dto.OddsMonitorMatchDto
import com.wrbug.polymarketbot.entity.OddsMatch
import com.wrbug.polymarketbot.entity.OddsMatchLink
import com.wrbug.polymarketbot.entity.OddsPlatformMatch
import com.wrbug.polymarketbot.repository.OddsDataSourceConfigRepository
import com.wrbug.polymarketbot.repository.OddsMatchLinkRepository
import com.wrbug.polymarketbot.repository.OddsMatchRepository
import com.wrbug.polymarketbot.repository.OddsPlatformMatchRepository
import com.wrbug.polymarketbot.util.TextEncodingUtils
import org.springframework.stereotype.Service
import java.util.Locale

@Service
class OddsDashboardService(
    private val dataSourceConfigRepository: OddsDataSourceConfigRepository,
    private val platformMatchRepository: OddsPlatformMatchRepository? = null,
    private val matchRepository: OddsMatchRepository? = null,
    private val matchLinkRepository: OddsMatchLinkRepository? = null,
    private val leagueFilterService: OddsLeagueFilterService? = null,
    private val displayMapper: OddsMonitorDisplayMapper = OddsMonitorDisplayMapper(),
    private val matchDetailService: OddsMatchDetailService = OddsMatchDetailService(displayMapper = displayMapper)
) {
    private val collectedSourceKeys = listOf("crown")

    fun getDashboard(): OddsMonitorDashboardDto {
        collectedDashboard(collectedSourceKeys)?.let { return it }
        if (platformMatchRepository != null) {
            return OddsMonitorDashboardDto(
                matches = emptyList(),
                selectedMatch = null
            )
        }

        val now = System.currentTimeMillis()
        val matches = listOf(
            OddsMonitorMatchDto(1, "英格兰超级联赛", "阿森纳", "切尔西", now + 3_600_000, "模拟", 1, 0, listOf("crown")),
            OddsMonitorMatchDto(2, "西班牙甲级联赛", "皇家马德里", "巴塞罗那", now + 7_200_000, "模拟", 1, 0, listOf("crown")),
            OddsMonitorMatchDto(3, "欧洲冠军联赛", "国际米兰", "拜仁慕尼黑", now + 10_800_000, "模拟", 1, 0, listOf("crown"))
        )
        val selected = matches.first()
        val history = (0..11).map { index ->
            val timestamp = now - (11 - index) * 300_000L
            OddsHistoryPointDto(
                timestamp = timestamp,
                crown = 1.79 + index * 0.008
            )
        }

        return OddsMonitorDashboardDto(
            matches = matches,
            selectedMatch = com.wrbug.polymarketbot.dto.OddsMonitorMatchDetailDto(
                match = selected,
                metrics = listOf(
                    OddsMetricDto("total over 2.5", "1.87", "-0.02", "crown")
                ),
                oddsHistory = history
            )
        )
    }

    fun getMatchDetail(matchId: Long) = collectedSnapshot(collectedSourceKeys)
        ?.rows
        ?.firstOrNull { row -> row.match.id == matchId || row.sourceMatches.values.any { it.id == matchId } }
        ?.let { buildMatchDetail(it) }

    private fun collectedDashboard(sourceKeys: List<String>): OddsMonitorDashboardDto? {
        val snapshot = collectedSnapshot(sourceKeys) ?: return null
        return OddsMonitorDashboardDto(
            matches = snapshot.rows.map { it.match },
            selectedMatch = buildMatchDetail(snapshot.rows.first())
        )
    }

    private fun collectedSnapshot(sourceKeys: List<String>): CollectedDashboardSnapshot? {
        val platformRepository = platformMatchRepository ?: return null
        val sourceMatchesBySource = sourceKeys.associateWith { sourceKey ->
            val config = dataSourceConfigRepository.findBySourceKey(sourceKey)
            if (config != null && !config.enabled) {
                return@associateWith emptyList()
            }
            loadRecentPlatformMatches(platformRepository, sourceKey)
                .filter { it.id != null }
                .filterNot {
                    OddsFootballMatchFilter.shouldIgnore(
                        it.rawLeagueName,
                        it.rawHomeTeam,
                        it.rawAwayTeam
                    )
                }
                .filter { match -> leagueFilterService?.shouldIncludeLeague(sourceKey, match.rawLeagueName) ?: true }
        }
        standardCollectedSnapshot(sourceKeys, sourceMatchesBySource)?.let { return it }

        val buckets = linkedMapOf<CollectedMatchKey, MutableMap<String, OddsPlatformMatch>>()
        sourceKeys.forEach { sourceKey ->
            sourceMatchesBySource[sourceKey].orEmpty().forEach { match ->
                val key = match.collectedMatchKey()
                val bucket = buckets.getOrPut(key) { linkedMapOf() }
                bucket.putIfAbsent(sourceKey, match)
            }
        }
        if (buckets.isEmpty()) {
            return null
        }

        val rows = buckets.values.map { sourceMap ->
            val matchedPlatforms = sourceKeys.filter { sourceMap.containsKey(it) }
            val match = matchedPlatforms.firstNotNullOf { sourceMap[it] }
            CollectedMatchRow(
                match = OddsMonitorMatchDto(
                    id = match.id ?: 0,
                    leagueName = displayMapper.leagueName(match.rawLeagueName),
                    homeTeam = displayMapper.teamName(match.rawHomeTeam),
                    awayTeam = displayMapper.teamName(match.rawAwayTeam),
                    startTime = match.rawStartTime ?: match.updatedAt,
                    status = displayMatchStatus(null, sourceMap.values),
                    sourceCount = matchedPlatforms.size,
                    alertCount = 0,
                    matchedPlatforms = matchedPlatforms
                ),
                sourceMatches = matchedPlatforms.associateWith { sourceMap.getValue(it) }
            )
        }.sortCollectedRows()

        return CollectedDashboardSnapshot(rows)
    }

    private fun standardCollectedSnapshot(
        sourceKeys: List<String>,
        sourceMatchesBySource: Map<String, List<OddsPlatformMatch>>
    ): CollectedDashboardSnapshot? {
        val matchRepo = matchRepository ?: return null
        val linkRepo = matchLinkRepository ?: return null
        val platformMatchesById = sourceMatchesBySource.values
            .flatten()
            .filter { it.id != null }
            .associateBy { it.id ?: 0L }
        if (platformMatchesById.isEmpty()) {
            return null
        }

        val platformMatchIds = platformMatchesById.keys.toList()
        val links = linkRepo.findByPlatformMatchIdIn(platformMatchIds)
        if (links.isEmpty()) {
            return null
        }
        val standardMatchesById = matchRepo.findAllById(links.map { it.matchId }.distinct())
            .mapNotNull { match -> match.id?.let { id -> id to match } }
            .toMap()

        val rows = links.groupBy { it.matchId }.mapNotNull { (standardMatchId, links) ->
            val standardMatch = standardMatchesById[standardMatchId] ?: return@mapNotNull null
            val sourceMap = links.mapNotNull { link -> platformMatchesById[link.platformMatchId] }
                .groupBy { it.sourceKey }
                .mapValues { (_, matches) -> matches.maxBy { it.updatedAt } }
                .filterKeys { it in sourceKeys }
                .filterValues { match -> leagueFilterService?.shouldIncludeLeague(match.sourceKey, match.rawLeagueName) ?: true }
            if (sourceMap.isEmpty()) {
                return@mapNotNull null
            }
            val matchedPlatforms = sourceKeys.filter { sourceMap.containsKey(it) }
            CollectedMatchRow(
                match = OddsMonitorMatchDto(
                    id = standardMatchId,
                    leagueName = displayMapper.leagueName(standardMatch.leagueName),
                    homeTeam = displayMapper.teamName(standardMatch.homeTeam),
                    awayTeam = displayMapper.teamName(standardMatch.awayTeam),
                    startTime = standardMatch.startTime ?: sourceMap.values.first().updatedAt,
                    status = displayMatchStatus(standardMatch.status, sourceMap.values),
                    sourceCount = matchedPlatforms.size,
                    alertCount = 0,
                    matchedPlatforms = matchedPlatforms
                ),
                sourceMatches = matchedPlatforms.associateWith { sourceMap.getValue(it) },
                matchLinks = links
            )
        }.sortCollectedRows()

        return rows.takeIf { it.isNotEmpty() }?.let { CollectedDashboardSnapshot(it) }
    }

    private fun buildMatchDetail(row: CollectedMatchRow) =
        matchDetailService.buildDetail(row.match, row.sourceMatches)

    private fun OddsPlatformMatch.collectedMatchKey(): CollectedMatchKey {
        return CollectedMatchKey(
            homeTeam = normalizeMatchName(rawHomeTeam),
            awayTeam = normalizeMatchName(rawAwayTeam)
        )
    }

    private fun normalizeMatchName(value: String): String {
        return TextEncodingUtils.repairMojibake(value)
            .lowercase(Locale.ROOT)
            .replace(Regex("""[\s\p{Punct}\p{C}]+"""), "")
            .trim()
    }

    private fun loadRecentPlatformMatches(
        platformRepository: OddsPlatformMatchRepository,
        sourceKey: String
    ): List<OddsPlatformMatch> {
        return platformRepository.findTop500BySourceKeyOrderByUpdatedAtDesc(sourceKey)
    }

    private fun displayMatchStatus(
        standardStatus: String?,
        sourceMatches: Collection<OddsPlatformMatch>
    ): String {
        return if (
            sourceMatches.any { determineOddsMonitorMatchPhase(it) == OddsMonitorMatchPhase.LIVE } ||
            isLiveStatus(standardStatus)
        ) {
            displayMapper.matchStatus("live")
        } else {
            displayMapper.matchStatus(standardStatus ?: "scheduled")
        }
    }

    private fun isLiveStatus(value: String?): Boolean {
        return TextEncodingUtils.repairMojibake(value.orEmpty()).trim().lowercase(Locale.ROOT) in setOf(
            "live",
            "inplay",
            "in-play",
            "in_play",
            "滚球"
        )
    }

    private fun List<CollectedMatchRow>.sortCollectedRows(): List<CollectedMatchRow> {
        return sortedWith(
            compareByDescending<CollectedMatchRow> { row ->
                row.sourceMatches.values.any { determineOddsMonitorMatchPhase(it) == OddsMonitorMatchPhase.LIVE } ||
                    isLiveStatus(row.match.status)
            }
                .thenByDescending { row -> row.sourceMatches.values.maxOfOrNull { it.updatedAt } ?: 0L }
                .thenBy { row -> row.match.startTime }
                .thenBy { row -> row.match.id }
        )
    }

    private data class CollectedMatchKey(
        val homeTeam: String,
        val awayTeam: String
    )

    private data class CollectedMatchRow(
        val match: OddsMonitorMatchDto,
        val sourceMatches: Map<String, OddsPlatformMatch>,
        val matchLinks: List<OddsMatchLink> = emptyList()
    )

    private data class CollectedDashboardSnapshot(
        val rows: List<CollectedMatchRow>
    )
}
