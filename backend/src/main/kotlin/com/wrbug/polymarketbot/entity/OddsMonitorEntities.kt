package com.wrbug.polymarketbot.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal

@Entity
@Table(name = "odds_matches")
data class OddsMatch(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "sport", nullable = false, length = 32)
    val sport: String = "football",
    @Column(name = "league_name", nullable = false, length = 128)
    val leagueName: String = "",
    @Column(name = "home_team", nullable = false, length = 128)
    val homeTeam: String = "",
    @Column(name = "away_team", nullable = false, length = 128)
    val awayTeam: String = "",
    @Column(name = "start_time")
    val startTime: Long? = null,
    @Column(name = "status", nullable = false, length = 32)
    val status: String = "scheduled",
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity
@Table(name = "odds_platform_matches")
data class OddsPlatformMatch(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "source_key", nullable = false, length = 32)
    val sourceKey: String = "",
    @Column(name = "source_match_id", nullable = false, length = 128)
    val sourceMatchId: String = "",
    @Column(name = "raw_league_name", nullable = false, length = 128)
    val rawLeagueName: String = "",
    @Column(name = "raw_home_team", nullable = false, length = 128)
    val rawHomeTeam: String = "",
    @Column(name = "raw_away_team", nullable = false, length = 128)
    val rawAwayTeam: String = "",
    @Column(name = "raw_start_time")
    val rawStartTime: Long? = null,
    @Column(name = "raw_payload_json", columnDefinition = "TEXT")
    val rawPayloadJson: String? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity
@Table(name = "odds_match_links")
data class OddsMatchLink(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "match_id", nullable = false)
    val matchId: Long = 0,
    @Column(name = "platform_match_id", nullable = false)
    val platformMatchId: Long = 0,
    @Column(name = "confidence", nullable = false)
    val confidence: BigDecimal = BigDecimal.ZERO,
    @Column(name = "match_method", nullable = false, length = 32)
    val matchMethod: String = "manual",
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity
@Table(name = "odds_markets")
data class OddsMarket(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "match_id", nullable = false)
    val matchId: Long = 0,
    @Column(name = "platform_match_id")
    val platformMatchId: Long? = null,
    @Column(name = "source_key", nullable = false, length = 32)
    val sourceKey: String = "",
    @Column(name = "market_type", nullable = false, length = 32)
    val marketType: String = "",
    @Column(name = "line_value", length = 32)
    val lineValue: String? = null,
    @Column(name = "selection_name", nullable = false, length = 64)
    val selectionName: String = "",
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity
@Table(name = "odds_snapshots")
data class OddsSnapshot(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "market_id", nullable = false)
    val marketId: Long = 0,
    @Column(name = "source_key", nullable = false, length = 32)
    val sourceKey: String = "",
    @Column(name = "odds_value", nullable = false)
    val oddsValue: BigDecimal = BigDecimal.ZERO,
    @Column(name = "implied_probability")
    val impliedProbability: BigDecimal? = null,
    @Column(name = "captured_at", nullable = false)
    val capturedAt: Long = System.currentTimeMillis(),
    @Column(name = "raw_payload_json", columnDefinition = "TEXT")
    val rawPayloadJson: String? = null
)

@Entity
@Table(name = "odds_alert_records")
data class OddsAlertRecord(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "alert_type", nullable = false, length = 64)
    val alertType: String = "",
    @Column(name = "severity", nullable = false, length = 16)
    val severity: String = "info",
    @Column(name = "match_id")
    val matchId: Long? = null,
    @Column(name = "source_key", length = 32)
    val sourceKey: String? = null,
    @Column(name = "title", nullable = false, length = 200)
    val title: String = "",
    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    val message: String = "",
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    @Column(name = "acknowledged", nullable = false)
    val acknowledged: Boolean = false
)

@Entity
@Table(name = "odds_collection_logs")
data class OddsCollectionLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "source_key", nullable = false, length = 32)
    val sourceKey: String = "",
    @Column(name = "status", nullable = false, length = 32)
    val status: String = "",
    @Column(name = "message", columnDefinition = "TEXT")
    val message: String? = null,
    @Column(name = "started_at", nullable = false)
    val startedAt: Long = System.currentTimeMillis(),
    @Column(name = "finished_at")
    val finishedAt: Long? = null,
    @Column(name = "records_count", nullable = false)
    val recordsCount: Int = 0,
    @Column(name = "match_count", nullable = false)
    val matchCount: Int = 0,
    @Column(name = "market_count", nullable = false)
    val marketCount: Int = 0,
    @Column(name = "empty_market_count", nullable = false)
    val emptyMarketCount: Int = 0,
    @Column(name = "failure_reason", columnDefinition = "TEXT")
    val failureReason: String? = null
)

@Entity
@Table(name = "odds_data_source_configs")
data class OddsDataSourceConfig(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "source_key", nullable = false, length = 32)
    val sourceKey: String = "",
    @Column(name = "display_name", nullable = false, length = 64)
    val displayName: String = "",
    @Column(name = "enabled", nullable = false)
    val enabled: Boolean = false,
    @Column(name = "username", length = 128)
    val username: String? = null,
    @Column(name = "password", length = 256)
    val password: String? = null,
    @Column(name = "query_keyword", length = 128)
    val queryKeyword: String? = null,
    @Column(name = "interval_seconds", nullable = false)
    val intervalSeconds: Int = 60,
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Long = System.currentTimeMillis()
)
