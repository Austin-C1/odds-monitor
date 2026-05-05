package com.wrbug.polymarketbot.dto

data class OddsMonitorDashboardDto(
    val matches: List<OddsMonitorMatchDto>,
    val selectedMatch: OddsMonitorMatchDetailDto?
)

data class OddsMonitorMatchDto(
    val id: Long,
    val leagueName: String,
    val homeTeam: String,
    val awayTeam: String,
    val startTime: Long,
    val status: String,
    val sourceCount: Int,
    val alertCount: Int,
    val matchedPlatforms: List<String> = emptyList()
)

data class OddsMonitorMatchDetailDto(
    val match: OddsMonitorMatchDto,
    val metrics: List<OddsMetricDto>,
    val oddsHistory: List<OddsHistoryPointDto>,
    val platformMatches: List<OddsPlatformMatchDto> = emptyList()
)

data class OddsPlatformMatchDto(
    val sourceKey: String,
    val sourceMatchId: String,
    val rawLeagueName: String,
    val rawHomeTeam: String,
    val rawAwayTeam: String,
    val rawStartTime: Long?
)

data class OddsMetricDto(
    val label: String,
    val value: String,
    val trend: String,
    val sourceKey: String? = null
)

data class OddsMonitorMatchDetailRequest(
    val matchId: Long
)

data class OddsHistoryPointDto(
    val timestamp: Long,
    val pinnacle: Double,
    val crown: Double,
    val polymarket: Double
)

data class OddsDataSourceConfigDto(
    val sourceKey: String,
    val displayName: String,
    val enabled: Boolean,
    val username: String? = null,
    val password: String? = null,
    val queryKeyword: String? = null,
    val intervalSeconds: Int,
    val updatedAt: Long
)

data class SaveOddsDataSourceConfigsRequest(
    val configs: List<OddsDataSourceConfigDto>
)

data class OddsLeagueFilterDto(
    val availableLeagues: List<String>,
    val selectedLeagues: List<String>
)

data class SaveOddsLeagueFilterRequest(
    val selectedLeagues: List<String>,
    val sourceKey: String? = null
)

data class ListOddsLeagueFilterRequest(
    val sourceKey: String? = null
)

data class OddsDataSourceStatusDto(
    val sourceKey: String,
    val displayName: String,
    val enabled: Boolean,
    val currentStatus: String,
    val lastCollectTime: Long? = null,
    val lastSuccessTime: Long? = null,
    val lastFailureTime: Long? = null,
    val failureReason: String? = null
)

data class OddsAlertRecordDto(
    val id: Long,
    val alertType: String,
    val severity: String,
    val matchName: String?,
    val sourceKey: String?,
    val title: String,
    val message: String,
    val createdAt: Long,
    val acknowledged: Boolean
)

data class OddsCollectionLogDto(
    val id: Long,
    val sourceKey: String,
    val status: String,
    val message: String?,
    val startedAt: Long,
    val finishedAt: Long?,
    val recordsCount: Int,
    val matchCount: Int = 0,
    val marketCount: Int = 0,
    val emptyMarketCount: Int = 0,
    val failureReason: String? = null
)
