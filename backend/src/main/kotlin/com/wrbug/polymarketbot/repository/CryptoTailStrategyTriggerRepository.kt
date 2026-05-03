package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.CryptoTailStrategyTrigger
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal

interface CryptoTailStrategyTriggerRepository : JpaRepository<CryptoTailStrategyTrigger, Long> {

    fun findByStrategyIdAndPeriodStartUnix(strategyId: Long, periodStartUnix: Long): CryptoTailStrategyTrigger?
    fun findAllByStrategyIdOrderByCreatedAtDesc(strategyId: Long, pageable: Pageable): Page<CryptoTailStrategyTrigger>
    fun findAllByStrategyIdAndStatusOrderByCreatedAtDesc(strategyId: Long, status: String, pageable: Pageable): Page<CryptoTailStrategyTrigger>
    fun countByStrategyIdAndStatus(strategyId: Long, status: String): Long

    fun findAllByStrategyIdAndCreatedAtBetweenOrderByCreatedAtDesc(strategyId: Long, startInclusive: Long, endInclusive: Long, pageable: Pageable): Page<CryptoTailStrategyTrigger>
    fun findAllByStrategyIdAndStatusAndCreatedAtBetweenOrderByCreatedAtDesc(strategyId: Long, status: String, startInclusive: Long, endInclusive: Long, pageable: Pageable): Page<CryptoTailStrategyTrigger>
    fun countByStrategyIdAndCreatedAtBetween(strategyId: Long, startInclusive: Long, endInclusive: Long): Long
    fun countByStrategyIdAndStatusAndCreatedAtBetween(strategyId: Long, status: String, startInclusive: Long, endInclusive: Long): Long

    fun findByStatusAndResolvedAndOrderIdIsNotNullOrderByCreatedAtAsc(status: String, resolved: Boolean): List<CryptoTailStrategyTrigger>

    fun findByOrderId(orderId: String): CryptoTailStrategyTrigger?

    fun findByStatusAndOrderIdIsNotNullAndNotificationSentFalseOrderByCreatedAtAsc(status: String): List<CryptoTailStrategyTrigger>

    @Query("SELECT COALESCE(SUM(t.realizedPnl), 0) FROM CryptoTailStrategyTrigger t WHERE t.strategyId = :strategyId AND t.resolved = true")
    fun sumRealizedPnlByStrategyId(@Param("strategyId") strategyId: Long): BigDecimal?

    @Query("SELECT COUNT(t) FROM CryptoTailStrategyTrigger t WHERE t.strategyId = :strategyId AND t.resolved = true")
    fun countResolvedByStrategyId(@Param("strategyId") strategyId: Long): Long

    @Query("SELECT COUNT(t) FROM CryptoTailStrategyTrigger t WHERE t.strategyId = :strategyId AND t.resolved = true AND t.outcomeIndex = t.winnerOutcomeIndex")
    fun countWinsByStrategyId(@Param("strategyId") strategyId: Long): Long

    @Query(
        "SELECT t FROM CryptoTailStrategyTrigger t WHERE t.strategyId = :strategyId AND t.resolved = true " +
            "AND COALESCE(t.settledAt, t.createdAt) >= :start AND COALESCE(t.settledAt, t.createdAt) <= :end " +
            "ORDER BY COALESCE(t.settledAt, t.createdAt) ASC"
    )
    fun findResolvedByStrategyIdAndTimeRangeOrderBySettledAsc(
        @Param("strategyId") strategyId: Long,
        @Param("start") start: Long,
        @Param("end") end: Long
    ): List<CryptoTailStrategyTrigger>
}
