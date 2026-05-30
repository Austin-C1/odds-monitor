package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.AutoBettingIntent
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Repository
interface AutoBettingIntentRepository : JpaRepository<AutoBettingIntent, Long> {
    fun existsByDedupeKeyAndStatusIn(dedupeKey: String, statuses: Collection<String>): Boolean
    fun findTopByDedupeKeyAndStatusInOrderByCreatedAtDesc(
        dedupeKey: String,
        statuses: Collection<String>
    ): AutoBettingIntent?

    fun findTop100ByOrderByCreatedAtDesc(): List<AutoBettingIntent>
    fun findTop100ByStatusAndCrownHistoryVerifiedTrueOrderByCreatedAtDesc(status: String): List<AutoBettingIntent>

    @Query(
        """
        SELECT COALESCE(SUM(intent.stakeAmount), 0)
        FROM AutoBettingIntent intent
        WHERE intent.accountKey = :accountKey
          AND intent.status IN :statuses
        """
    )
    fun sumStakeAmountByAccountKeyAndStatusIn(
        @Param("accountKey") accountKey: String,
        @Param("statuses") statuses: Collection<String>
    ): BigDecimal?

    @Modifying
    @Transactional
    @Query(
        """
        UPDATE AutoBettingIntent intent
        SET intent.status = :rejectedStatus,
            intent.rejectReason = :rejectReason,
            intent.activeDedupeKey = null,
            intent.updatedAt = :updatedAt
        WHERE intent.dedupeKey = :dedupeKey
          AND intent.status = :readyStatus
          AND intent.capturedAt < :capturedBefore
        """
    )
    fun rejectStaleReadyIntentByDedupeKey(
        @Param("dedupeKey") dedupeKey: String,
        @Param("readyStatus") readyStatus: String,
        @Param("capturedBefore") capturedBefore: Long,
        @Param("rejectedStatus") rejectedStatus: String,
        @Param("rejectReason") rejectReason: String,
        @Param("updatedAt") updatedAt: Long
    ): Int

    @Modifying
    @Transactional
    @Query(
        """
        UPDATE AutoBettingIntent intent
        SET intent.status = :rejectedStatus,
            intent.rejectReason = :rejectReason,
            intent.activeDedupeKey = null,
            intent.updatedAt = :updatedAt
        WHERE intent.status = :readyStatus
          AND intent.updatedAt < :updatedBefore
        """
    )
    fun rejectStaleReadyIntents(
        @Param("readyStatus") readyStatus: String,
        @Param("updatedBefore") updatedBefore: Long,
        @Param("rejectedStatus") rejectedStatus: String,
        @Param("rejectReason") rejectReason: String,
        @Param("updatedAt") updatedAt: Long
    ): Int

    @Modifying
    @Transactional
    @Query(
        """
        UPDATE AutoBettingIntent intent
        SET intent.status = :placingStatus,
            intent.updatedAt = :updatedAt
        WHERE intent.id = :intentId
          AND intent.status = :readyStatus
        """
    )
    fun markReadyIntentPlacingById(
        @Param("intentId") intentId: Long,
        @Param("readyStatus") readyStatus: String,
        @Param("placingStatus") placingStatus: String,
        @Param("updatedAt") updatedAt: Long
    ): Int

    @Modifying
    @Transactional
    @Query(
        """
        UPDATE AutoBettingIntent intent
        SET intent.status = :rejectedStatus,
            intent.rejectReason = :rejectReason,
            intent.activeDedupeKey = null,
            intent.updatedAt = :updatedAt
        WHERE intent.status = :placingStatus
          AND intent.updatedAt < :updatedBefore
        """
    )
    fun rejectStalePlacingIntents(
        @Param("placingStatus") placingStatus: String,
        @Param("updatedBefore") updatedBefore: Long,
        @Param("rejectedStatus") rejectedStatus: String,
        @Param("rejectReason") rejectReason: String,
        @Param("updatedAt") updatedAt: Long
    ): Int

    @Modifying
    @Transactional
    @Query(
        value = """
        DELETE FROM auto_betting_intents
        WHERE status = 'placed'
          AND crown_history_verified = 1
        LIMIT :limit
        """,
        nativeQuery = true
    )
    fun deleteBatchVerifiedPlacedIntents(@Param("limit") limit: Int): Int

    @Modifying
    @Transactional
    @Query(
        value = """
        DELETE FROM auto_betting_intents
        WHERE status = 'rejected'
        LIMIT :limit
        """,
        nativeQuery = true
    )
    fun deleteBatchRejectedIntents(@Param("limit") limit: Int): Int
}
