package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.dto.OddsDataSourceConfigDto
import com.wrbug.polymarketbot.dto.OddsLeagueFilterDto
import com.wrbug.polymarketbot.dto.OddsMonitorDashboardDto
import com.wrbug.polymarketbot.dto.OddsMonitorMatchDetailDto
import com.wrbug.polymarketbot.entity.OddsPlatformMatch
import com.wrbug.polymarketbot.repository.OddsAlertRecordRepository
import com.wrbug.polymarketbot.repository.OddsCollectionLogRepository
import com.wrbug.polymarketbot.repository.OddsDataSourceConfigRepository
import com.wrbug.polymarketbot.repository.OddsMarketRepository
import com.wrbug.polymarketbot.repository.OddsMatchLinkRepository
import com.wrbug.polymarketbot.repository.OddsMatchRepository
import com.wrbug.polymarketbot.repository.OddsPlatformMatchRepository
import com.wrbug.polymarketbot.repository.OddsSnapshotRepository
import org.springframework.stereotype.Service

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
    private val leagueFilterService: OddsLeagueFilterService? = null,
    private val oddsChangeNotificationService: OddsChangeNotificationService? = null,
    private val displayMapper: OddsMonitorDisplayMapper = OddsMonitorDisplayMapper(),
    private val dataSourceService: OddsDataSourceService? = null,
    private val collectionLogService: OddsCollectionLogService? = null,
    private val matchDetailService: OddsMatchDetailService? = null,
    private val dashboardService: OddsDashboardService? = null,
    private val alertRecordService: OddsAlertRecordService? = null
) {
    private val collectedSourceKeys = listOf("crown")
    private val dataSourceDelegate = dataSourceService ?: OddsDataSourceService(
        dataSourceConfigRepository,
        collectionLogRepository,
        oddsChangeNotificationService,
        displayMapper
    )
    private val collectionLogDelegate = collectionLogService ?: OddsCollectionLogService(collectionLogRepository)
    private val matchDetailDelegate = matchDetailService ?: OddsMatchDetailService(
        marketRepository,
        snapshotRepository,
        displayMapper
    )
    private val dashboardDelegate = dashboardService ?: OddsDashboardService(
        dataSourceConfigRepository = dataSourceConfigRepository,
        platformMatchRepository = platformMatchRepository,
        matchRepository = matchRepository,
        matchLinkRepository = matchLinkRepository,
        leagueFilterService = leagueFilterService,
        displayMapper = displayMapper,
        matchDetailService = matchDetailDelegate
    )
    private val alertRecordDelegate = alertRecordService ?: OddsAlertRecordService(alertRecordRepository, displayMapper)

    fun getDashboard(): OddsMonitorDashboardDto {
        return dashboardDelegate.getDashboard()
    }

    fun getMatchDetail(matchId: Long): OddsMonitorMatchDetailDto? {
        return dashboardDelegate.getMatchDetail(matchId)
    }

    fun listDataSourceConfigs(): List<OddsDataSourceConfigDto> {
        return dataSourceDelegate.listConfigs()
    }

    fun listLeagueFilter(sourceKey: String? = null): OddsLeagueFilterDto {
        val normalizedSourceKey = normalizeLeagueFilterSourceKey(sourceKey)
        val platformRepository = platformMatchRepository
        val selected = if (normalizedSourceKey == null) {
            leagueFilterService?.getDefaultTrackingLeagues().orEmpty()
        } else {
            leagueFilterService?.getSelectedLeagues(normalizedSourceKey).orEmpty()
        }
        val available = if (normalizedSourceKey == null) {
            val collectedRawLeagues = collectedSourceKeys.flatMap { collectedSourceKey ->
                platformRepository
                    ?.let { loadRecentPlatformMatches(it, collectedSourceKey) }
                    .orEmpty()
                    .map { it.rawLeagueName }
            }
            defaultTrackingLeagueDisplayNames(
                leagueFilterService?.expandDefaultTrackingLeagueNames(selected).orEmpty() +
                    leagueFilterService?.getSelectedLeagues("crown").orEmpty() +
                    defaultTrackedLeagueNames() +
                    collectedRawLeagues
            )
        } else {
            val collected = platformRepository
                ?.let { availableOddsLeagueNames(loadRecentPlatformMatches(it, normalizedSourceKey), normalizedSourceKey) }
                .orEmpty()
            (collected + selected)
                .distinct()
                .sortedWith(compareBy<String> { it.any { char -> char.code < 128 } }.thenBy { it })
        }
        return OddsLeagueFilterDto(
            availableLeagues = available,
            selectedLeagues = selected
        )
    }

    fun saveLeagueFilter(selectedLeagues: List<String>, sourceKey: String? = null): OddsLeagueFilterDto {
        val normalizedSourceKey = normalizeLeagueFilterSourceKey(sourceKey)
        val filterService = leagueFilterService
        if (normalizedSourceKey == null) {
            val selectedParts = filterService?.expandDefaultTrackingLeagueNames(selectedLeagues) ?: selectedLeagues
            val selectedRawNames = selectedParts.mapNotNull { rawOddsLeagueName(it) }.toSet()
            val selectedCanonicalNames = selectedParts.mapNotNull { canonicalOddsLeagueName(it) }.toSet()
            listOf("crown").forEach { source ->
                val retained = filterService?.getSelectedLeagues(source)
                    .orEmpty()
                    .filter { league ->
                        rawOddsLeagueName(league) in selectedRawNames ||
                            canonicalOddsLeagueName(league) in selectedCanonicalNames
                    }
                filterService?.saveSelectedLeagues(retained, source)
            }
            filterService?.saveSelectedLeagues(selectedCanonicalNames.toList(), null)
            return listLeagueFilter(null)
        }
        filterService?.saveSelectedLeagues(selectedLeagues, normalizedSourceKey)
        return listLeagueFilter(normalizedSourceKey)
    }

    fun saveDataSourceConfigs(configs: List<OddsDataSourceConfigDto>): List<OddsDataSourceConfigDto> {
        return dataSourceDelegate.saveConfigs(configs)
    }

    fun listDataSourceStatuses() = dataSourceDelegate.listStatuses()

    fun listAlertRecords() = alertRecordDelegate.listRecords()

    fun listCollectionLogs() = collectionLogDelegate.listLogs()

    private fun loadRecentPlatformMatches(
        platformRepository: OddsPlatformMatchRepository,
        sourceKey: String
    ): List<OddsPlatformMatch> {
        return platformRepository.findTop500BySourceKeyOrderByUpdatedAtDesc(sourceKey)
    }

}
