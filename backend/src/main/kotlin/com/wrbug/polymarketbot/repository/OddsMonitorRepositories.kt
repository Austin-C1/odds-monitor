package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.OddsAlertRecord
import com.wrbug.polymarketbot.entity.OddsCollectionLog
import com.wrbug.polymarketbot.entity.OddsDataSourceConfig
import com.wrbug.polymarketbot.entity.OddsMarket
import com.wrbug.polymarketbot.entity.OddsMatch
import com.wrbug.polymarketbot.entity.OddsMatchLink
import com.wrbug.polymarketbot.entity.OddsPlatformMatch
import com.wrbug.polymarketbot.entity.OddsSnapshot
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface OddsDataSourceConfigRepository : JpaRepository<OddsDataSourceConfig, Long> {
    fun findBySourceKey(sourceKey: String): OddsDataSourceConfig?
}

@Repository
interface OddsAlertRecordRepository : JpaRepository<OddsAlertRecord, Long> {
    fun findTop100ByOrderByCreatedAtDesc(): List<OddsAlertRecord>
}

@Repository
interface OddsCollectionLogRepository : JpaRepository<OddsCollectionLog, Long> {
    fun findTop200ByOrderByStartedAtDesc(): List<OddsCollectionLog>
    fun findTop1BySourceKeyOrderByStartedAtDesc(sourceKey: String): OddsCollectionLog?
    fun findTop1BySourceKeyAndStatusOrderByStartedAtDesc(sourceKey: String, status: String): OddsCollectionLog?

    @Query(
        """
        SELECT * FROM odds_collection_logs
        WHERE source_key = :sourceKey AND status <> 'success'
        ORDER BY started_at DESC
        LIMIT 1
        """,
        nativeQuery = true
    )
    fun findTop1FailureBySourceKey(@Param("sourceKey") sourceKey: String): OddsCollectionLog?
}

@Repository
interface OddsPlatformMatchRepository : JpaRepository<OddsPlatformMatch, Long> {
    fun findBySourceKeyAndSourceMatchId(sourceKey: String, sourceMatchId: String): OddsPlatformMatch?
    fun findTop100BySourceKeyOrderByRawStartTimeAsc(sourceKey: String): List<OddsPlatformMatch>
    fun findTop100BySourceKeyOrderByUpdatedAtDesc(sourceKey: String): List<OddsPlatformMatch>
    fun findTop500BySourceKeyOrderByUpdatedAtDesc(sourceKey: String): List<OddsPlatformMatch>
}

@Repository
interface OddsMatchRepository : JpaRepository<OddsMatch, Long> {
    fun findTop500BySportOrderByStartTimeAsc(sport: String): List<OddsMatch>
}

@Repository
interface OddsMatchLinkRepository : JpaRepository<OddsMatchLink, Long> {
    fun findByPlatformMatchId(platformMatchId: Long): OddsMatchLink?
    fun findByMatchId(matchId: Long): List<OddsMatchLink>
    fun findByMatchIdIn(matchIds: Collection<Long>): List<OddsMatchLink>
}

@Repository
interface OddsMarketRepository : JpaRepository<OddsMarket, Long> {
    fun findByMatchIdAndSourceKeyAndMarketTypeAndLineValueAndSelectionName(
        matchId: Long,
        sourceKey: String,
        marketType: String,
        lineValue: String?,
        selectionName: String
    ): OddsMarket?

    fun findByMatchIdInAndSourceKey(matchIds: Collection<Long>, sourceKey: String): List<OddsMarket>
    fun findByPlatformMatchIdInAndSourceKey(platformMatchIds: Collection<Long>, sourceKey: String): List<OddsMarket>
}

@Repository
interface OddsSnapshotRepository : JpaRepository<OddsSnapshot, Long> {
    fun findTop1ByMarketIdOrderByCapturedAtDesc(marketId: Long): OddsSnapshot?

    @Modifying
    @Query("delete from OddsSnapshot snapshot where snapshot.capturedAt < :before")
    fun deleteOlderThan(@Param("before") before: Long): Int
}
