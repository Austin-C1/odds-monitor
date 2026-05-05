package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.dto.OddsAlertRecordDto
import com.wrbug.polymarketbot.dto.OddsCollectionLogDto
import com.wrbug.polymarketbot.dto.OddsDataSourceConfigDto
import com.wrbug.polymarketbot.dto.OddsDataSourceStatusDto
import com.wrbug.polymarketbot.dto.OddsHistoryPointDto
import com.wrbug.polymarketbot.dto.OddsLeagueFilterDto
import com.wrbug.polymarketbot.dto.OddsMetricDto
import com.wrbug.polymarketbot.dto.OddsMonitorDashboardDto
import com.wrbug.polymarketbot.dto.OddsMonitorMatchDetailDto
import com.wrbug.polymarketbot.dto.OddsMonitorMatchDto
import com.wrbug.polymarketbot.dto.OddsPlatformMatchDto
import com.wrbug.polymarketbot.entity.OddsDataSourceConfig
import com.wrbug.polymarketbot.entity.OddsMatch
import com.wrbug.polymarketbot.entity.OddsMatchLink
import com.wrbug.polymarketbot.entity.OddsPlatformMatch
import com.wrbug.polymarketbot.repository.OddsAlertRecordRepository
import com.wrbug.polymarketbot.repository.OddsCollectionLogRepository
import com.wrbug.polymarketbot.repository.OddsDataSourceConfigRepository
import com.wrbug.polymarketbot.repository.OddsMarketRepository
import com.wrbug.polymarketbot.repository.OddsMatchLinkRepository
import com.wrbug.polymarketbot.repository.OddsMatchRepository
import com.wrbug.polymarketbot.repository.OddsPlatformMatchRepository
import com.wrbug.polymarketbot.repository.OddsSnapshotRepository
import com.wrbug.polymarketbot.util.TextEncodingUtils
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.Locale

@Service
class OddsMonitorService(
    private val dataSourceConfigRepository: OddsDataSourceConfigRepository,
    private val alertRecordRepository: OddsAlertRecordRepository,
    private val collectionLogRepository: OddsCollectionLogRepository,
    private val platformMatchRepository: OddsPlatformMatchRepository? = null,
    private val marketRepository: OddsMarketRepository? = null,
    private val snapshotRepository: OddsSnapshotRepository? = null,
    private val matchRepository: OddsMatchRepository? = null,
    private val matchLinkRepository: OddsMatchLinkRepository? = null,
    private val leagueFilterService: OddsLeagueFilterService? = null
) {
    private val collectedSourceKeys = listOf("pinnacle", "crown", "polymarket")

    private val defaultSources = listOf(
        OddsDataSourceConfigDto("pinnacle", "平博", false, intervalSeconds = 60, updatedAt = 0),
        OddsDataSourceConfigDto("crown", "皇冠", false, intervalSeconds = 60, updatedAt = 0),
        OddsDataSourceConfigDto("polymarket", "Polymarket", true, queryKeyword = "soccer", intervalSeconds = 120, updatedAt = 0)
    )

    fun getDashboard(): OddsMonitorDashboardDto {
        collectedDashboard(collectedSourceKeys)?.let { return it }

        val now = System.currentTimeMillis()
        val matches = listOf(
            OddsMonitorMatchDto(1, "英格兰超级联赛", "阿森纳", "切尔西", now + 3_600_000, "模拟", 3, 0, listOf("pinnacle", "crown", "polymarket")),
            OddsMonitorMatchDto(2, "西班牙甲组联赛", "皇家马德里", "巴塞罗那", now + 7_200_000, "模拟", 3, 0, listOf("pinnacle", "crown", "polymarket")),
            OddsMonitorMatchDto(3, "欧洲冠军联赛", "国际米兰", "拜仁慕尼黑", now + 10_800_000, "模拟", 2, 0, listOf("pinnacle", "crown"))
        )
        val selected = matches.first()
        val history = (0..11).map { index ->
            val timestamp = now - (11 - index) * 300_000L
            OddsHistoryPointDto(
                timestamp = timestamp,
                pinnacle = 1.82 + index * 0.01,
                crown = 1.79 + index * 0.008,
                polymarket = 0.53 + index * 0.004
            )
        }

        return OddsMonitorDashboardDto(
            matches = matches,
            selectedMatch = OddsMonitorMatchDetailDto(
                match = selected,
                metrics = listOf(
                    OddsMetricDto("handicap home 0.5", "1.93", "+0.04", "pinnacle"),
                    OddsMetricDto("total over 2.5", "1.87", "-0.02", "crown"),
                    OddsMetricDto("Polymarket", "57.4%", "+1.6%", "polymarket")
                ),
                oddsHistory = history
            )
        )
    }

    fun getMatchDetail(matchId: Long): OddsMonitorMatchDetailDto? {
        val snapshot = collectedSnapshot(collectedSourceKeys) ?: return null
        return snapshot.rows
            .firstOrNull { row -> row.match.id == matchId || row.sourceMatches.values.any { it.id == matchId } }
            ?.let { buildMatchDetail(it) }
    }

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
                    leagueName = localizeLeagueName(match.rawLeagueName),
                    homeTeam = localizeTeamName(match.rawHomeTeam),
                    awayTeam = localizeTeamName(match.rawAwayTeam),
                    startTime = match.rawStartTime ?: match.updatedAt,
                    status = localizeMatchStatus("scheduled"),
                    sourceCount = matchedPlatforms.size,
                    alertCount = 0,
                    matchedPlatforms = matchedPlatforms
                ),
                sourceMatches = matchedPlatforms.associateWith { sourceMap.getValue(it) }
            )
        }.sortedBy { it.match.startTime }

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

        val standardMatches = matchRepo.findTop500BySportOrderByStartTimeAsc("football")
        if (standardMatches.isEmpty()) {
            return null
        }
        val linksByMatchId = linkRepo.findByMatchIdIn(standardMatches.mapNotNull { it.id })
            .filter { platformMatchesById.containsKey(it.platformMatchId) }
            .groupBy { it.matchId }

        val rows = standardMatches.mapNotNull { standardMatch ->
            val standardMatchId = standardMatch.id ?: return@mapNotNull null
            val links = linksByMatchId[standardMatchId].orEmpty()
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
                    leagueName = localizeLeagueName(standardMatch.leagueName),
                    homeTeam = localizeTeamName(standardMatch.homeTeam),
                    awayTeam = localizeTeamName(standardMatch.awayTeam),
                    startTime = standardMatch.startTime ?: sourceMap.values.first().updatedAt,
                    status = localizeMatchStatus(standardMatch.status),
                    sourceCount = matchedPlatforms.size,
                    alertCount = 0,
                    matchedPlatforms = matchedPlatforms
                ),
                sourceMatches = matchedPlatforms.associateWith { sourceMap.getValue(it) },
                matchLinks = links
            )
        }.sortedBy { it.match.startTime }

        return rows.takeIf { it.isNotEmpty() }?.let { CollectedDashboardSnapshot(it) }
    }

    private fun buildMatchDetail(row: CollectedMatchRow): OddsMonitorMatchDetailDto {
        val marketRepo = marketRepository ?: return OddsMonitorMatchDetailDto(row.match, emptyList(), emptyList())
        val snapshotRepo = snapshotRepository ?: return OddsMonitorMatchDetailDto(row.match, emptyList(), emptyList())
        val metrics = row.match.matchedPlatforms.flatMap { sourceKey ->
            val platformMatchId = row.sourceMatches[sourceKey]?.id ?: return@flatMap emptyList()
            val markets = marketRepo.findByMatchIdInAndSourceKey(listOf(row.match.id), sourceKey)
                .ifEmpty { marketRepo.findByMatchIdInAndSourceKey(listOf(platformMatchId), sourceKey) }
                .ifEmpty { marketRepo.findByPlatformMatchIdInAndSourceKey(listOf(platformMatchId), sourceKey) }
                .filter { market -> shouldDisplayMarket(sourceKey, market.marketType) }
            markets.mapNotNull { market ->
                val snapshot = market.id?.let { snapshotRepo.findTop1ByMarketIdOrderByCapturedAtDesc(it) }
                snapshot?.let {
                    val line = OddsLineDisplayFormatter.format(market.marketType, market.lineValue)
                        ?.let { value -> " $value" }
                        .orEmpty()
                    OddsMetricDto(
                        label = "${market.marketType} ${market.selectionName}$line",
                        value = it.oddsValue.stripTrailingZeros().toPlainString(),
                        trend = "",
                        sourceKey = sourceKey
                    )
                }
            }
        }
        return OddsMonitorMatchDetailDto(
            match = row.match,
            metrics = metrics,
            oddsHistory = emptyList(),
            platformMatches = row.sourceMatches.values.map { it.toDto() }
        )
    }

    fun listDataSourceConfigs(): List<OddsDataSourceConfigDto> {
        val existing = dataSourceConfigRepository.findAll().associateBy { it.sourceKey }
        return defaultSources.map { default ->
            existing[default.sourceKey]?.toDto() ?: default.copy(updatedAt = System.currentTimeMillis())
        }
    }

    fun listLeagueFilter(sourceKey: String? = null): OddsLeagueFilterDto {
        val normalizedSourceKey = normalizeLeagueFilterSourceKey(sourceKey)
        val platformRepository = platformMatchRepository
        val collected = if (platformRepository == null) {
            emptyList()
        } else if (normalizedSourceKey != null) {
            availableOddsLeagueNames(loadRecentPlatformMatches(platformRepository, normalizedSourceKey), normalizedSourceKey)
        } else {
            availableOddsLeagueNames(
                collectedSourceKeys.flatMap { collectedSourceKey ->
                    loadRecentPlatformMatches(platformRepository, collectedSourceKey)
                }
            )
        }
        val selected = if (normalizedSourceKey == null) {
            leagueFilterService?.getDefaultTrackingLeagues().orEmpty()
        } else {
            leagueFilterService?.getSelectedLeagues(normalizedSourceKey).orEmpty()
        }
        val defaultLeagues = if (normalizedSourceKey == null) defaultTrackedLeagueNames() else emptyList()
        val available = (defaultLeagues + collected + selected)
            .distinct()
            .sortedWith(compareBy<String> { it.any { char -> char.code < 128 } }.thenBy { it })
        return OddsLeagueFilterDto(
            availableLeagues = available,
            selectedLeagues = selected
        )
    }

    fun saveLeagueFilter(selectedLeagues: List<String>, sourceKey: String? = null): OddsLeagueFilterDto {
        val normalizedSourceKey = normalizeLeagueFilterSourceKey(sourceKey)
        val filterService = leagueFilterService
        if (normalizedSourceKey == null) {
            val selectedRawNames = selectedLeagues.mapNotNull { rawOddsLeagueName(it) }.toSet()
            listOf("pinnacle", "crown").forEach { source ->
                if (filterService?.hasSelectedLeaguesConfig(source) != true) {
                    return@forEach
                }
                val retained = filterService.getSelectedLeagues(source)
                    .orEmpty()
                    .filter { rawOddsLeagueName(it) in selectedRawNames }
                filterService.saveSelectedLeagues(retained, source)
            }
        }
        filterService?.saveSelectedLeagues(selectedLeagues, normalizedSourceKey)
        return listLeagueFilter(normalizedSourceKey)
    }

    @Transactional
    fun saveDataSourceConfigs(configs: List<OddsDataSourceConfigDto>): List<OddsDataSourceConfigDto> {
        configs.forEach { incoming ->
            val normalized = normalizeConfig(incoming)
            val existing = dataSourceConfigRepository.findBySourceKey(normalized.sourceKey)
            dataSourceConfigRepository.save(
                OddsDataSourceConfig(
                    id = existing?.id,
                    sourceKey = normalized.sourceKey,
                    displayName = normalized.displayName,
                    enabled = normalized.enabled,
                    username = normalized.username?.takeIf { it.isNotBlank() },
                    password = passwordValue(normalized.password),
                    queryKeyword = normalized.queryKeyword?.takeIf { it.isNotBlank() },
                    intervalSeconds = normalized.intervalSeconds.coerceAtLeast(10),
                    createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        return listDataSourceConfigs()
    }

    fun listDataSourceStatuses(): List<OddsDataSourceStatusDto> {
        return listDataSourceConfigs().map { config ->
            val lastLog = collectionLogRepository.findTop1BySourceKeyOrderByStartedAtDesc(config.sourceKey)
            val lastSuccess = collectionLogRepository.findTop1BySourceKeyAndStatusOrderByStartedAtDesc(config.sourceKey, "success")
            val lastFailure = collectionLogRepository.findTop1FailureBySourceKey(config.sourceKey)
                ?.takeIf { lastLog?.status != "success" }
            OddsDataSourceStatusDto(
                sourceKey = config.sourceKey,
                displayName = oddsSourceDisplayName(config.sourceKey, config.displayName),
                enabled = config.enabled,
                currentStatus = if (!config.enabled) "disabled" else lastLog?.status ?: "waiting",
                lastCollectTime = lastLog?.startedAt,
                lastSuccessTime = lastSuccess?.startedAt,
                lastFailureTime = lastFailure?.startedAt,
                failureReason = lastFailure?.message
            )
        }
    }

    fun listAlertRecords(): List<OddsAlertRecordDto> {
        return alertRecordRepository.findTop100ByOrderByCreatedAtDesc().map {
            OddsAlertRecordDto(
                id = it.id ?: 0,
                alertType = it.alertType,
                severity = it.severity,
                matchName = it.matchId?.toString(),
                sourceKey = it.sourceKey,
                title = TextEncodingUtils.repairMojibake(it.title),
                message = TextEncodingUtils.repairMojibake(it.message),
                createdAt = it.createdAt,
                acknowledged = it.acknowledged
            )
        }
    }

    fun listCollectionLogs(): List<OddsCollectionLogDto> {
        return collectionLogRepository.findTop200ByOrderByStartedAtDesc().map {
            OddsCollectionLogDto(
                id = it.id ?: 0,
                sourceKey = it.sourceKey,
                status = it.status,
                message = it.message?.let(TextEncodingUtils::repairMojibake),
                startedAt = it.startedAt,
                finishedAt = it.finishedAt,
                recordsCount = it.recordsCount,
                matchCount = it.matchCount,
                marketCount = it.marketCount,
                emptyMarketCount = it.emptyMarketCount,
                failureReason = it.failureReason?.let(TextEncodingUtils::repairMojibake)
            )
        }
    }

    private fun normalizeConfig(config: OddsDataSourceConfigDto): OddsDataSourceConfigDto {
        val default = defaultSources.firstOrNull { it.sourceKey == config.sourceKey }
        return config.copy(
            displayName = oddsSourceDisplayName(config.sourceKey, config.displayName.ifBlank { default?.displayName ?: config.sourceKey }),
            intervalSeconds = config.intervalSeconds.coerceAtLeast(10)
        )
    }

    private fun oddsSourceDisplayName(sourceKey: String, displayName: String): String {
        return when (sourceKey.lowercase(Locale.ROOT)) {
            "pinnacle" -> "平博"
            "crown" -> "皇冠"
            "polymarket" -> "Polymarket"
            else -> TextEncodingUtils.repairMojibake(displayName).ifBlank { sourceKey }
        }
    }

    private fun passwordValue(value: String?): String? {
        return value?.takeIf { it.isNotBlank() }
    }

    private fun OddsDataSourceConfig.toDto(): OddsDataSourceConfigDto {
        return OddsDataSourceConfigDto(
            sourceKey = sourceKey,
            displayName = oddsSourceDisplayName(sourceKey, displayName),
            enabled = enabled,
            username = username,
            password = password,
            queryKeyword = queryKeyword,
            intervalSeconds = intervalSeconds,
            updatedAt = updatedAt
        )
    }

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

    private fun shouldDisplayMarket(sourceKey: String, marketType: String): Boolean {
        return marketType.lowercase(Locale.ROOT) != "moneyline" || sourceKey == "polymarket"
    }

    private fun OddsPlatformMatch.toDto(): OddsPlatformMatchDto {
        return OddsPlatformMatchDto(
            sourceKey = sourceKey,
            sourceMatchId = sourceMatchId,
            rawLeagueName = localizeLeagueName(rawLeagueName),
            rawHomeTeam = localizeTeamName(rawHomeTeam),
            rawAwayTeam = localizeTeamName(rawAwayTeam),
            rawStartTime = rawStartTime
        )
    }

    private fun localizeLeagueName(value: String): String {
        val repaired = TextEncodingUtils.repairMojibake(value).trim()
        return leagueNameAliases[repaired.lowercase(Locale.ROOT)]
            ?: canonicalOddsLeagueName(repaired)
            ?: repaired
    }

    private fun localizeTeamName(value: String): String {
        val repaired = TextEncodingUtils.repairMojibake(value).trim()
        return teamNameAliases[repaired.lowercase(Locale.ROOT)] ?: repaired
    }

    private fun localizeMatchStatus(value: String): String {
        return when (TextEncodingUtils.repairMojibake(value).lowercase(Locale.ROOT)) {
            "scheduled", "prematch", "not_started" -> "赛前"
            "live", "inplay", "in_play" -> "滚球"
            "finished", "closed" -> "完场"
            "cancelled", "canceled" -> "取消"
            else -> TextEncodingUtils.repairMojibake(value)
        }
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
        "soccer" to "足球"
    )
}
