package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.CopyOrderTracking
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.math.BigDecimal

@Repository
interface CopyOrderTrackingRepository : JpaRepository<CopyOrderTracking, Long> {
    
    fun findByCopyTradingId(copyTradingId: Long): List<CopyOrderTracking>

    fun findByCopyTradingIdIn(copyTradingIds: List<Long>): List<CopyOrderTracking>

    @Query(
        "SELECT t FROM CopyOrderTracking t " +
            "WHERE t.copyTradingId IN :copyTradingIds AND t.remainingQuantity > 0 " +
            "ORDER BY t.copyTradingId ASC, t.createdAt ASC"
    )
    fun findActiveOrdersByCopyTradingIdIn(copyTradingIds: List<Long>): List<CopyOrderTracking>

    @Query("SELECT DISTINCT t.copyTradingId FROM CopyOrderTracking t WHERE t.remainingQuantity > 0")
    fun findDistinctActiveCopyTradingIds(): List<Long>

    fun countByCopyTradingIdAndCreatedAtGreaterThanEqual(copyTradingId: Long, createdAt: Long): Int
    
    @Query("SELECT t FROM CopyOrderTracking t WHERE t.copyTradingId = :copyTradingId AND t.marketId = :marketId AND t.side = :side AND t.remainingQuantity > 0 ORDER BY t.createdAt ASC")
    fun findUnmatchedBuyOrders(copyTradingId: Long, marketId: String, side: String): List<CopyOrderTracking>
    
    @Query("SELECT t FROM CopyOrderTracking t WHERE t.copyTradingId = :copyTradingId AND t.marketId = :marketId AND t.outcomeIndex = :outcomeIndex AND t.remainingQuantity > 0 AND t.status <> 'pending_fill' ORDER BY t.createdAt ASC")
    fun findUnmatchedBuyOrdersByOutcomeIndex(copyTradingId: Long, marketId: String, outcomeIndex: Int): List<CopyOrderTracking>

    @Query(
        "SELECT t FROM CopyOrderTracking t " +
            "WHERE t.copyTradingId IN :copyTradingIds AND t.marketId = :marketId " +
            "AND t.outcomeIndex = :outcomeIndex AND t.remainingQuantity > 0 " +
            "AND t.status <> 'pending_fill' " +
            "ORDER BY t.copyTradingId ASC, t.createdAt ASC"
    )
    fun findUnmatchedBuyOrdersByOutcomeIndexBatch(
        copyTradingIds: List<Long>,
        marketId: String,
        outcomeIndex: Int
    ): List<CopyOrderTracking>
    
    fun findByCopyTradingIdAndStatus(copyTradingId: Long, status: String): List<CopyOrderTracking>
    
    fun findByCopyTradingIdAndMarketId(copyTradingId: Long, marketId: String): List<CopyOrderTracking>
    
    fun findByLeaderBuyTradeId(leaderBuyTradeId: String): CopyOrderTracking?
    
    fun findByBuyOrderId(buyOrderId: String): List<CopyOrderTracking>
    
    fun findByNotificationSentFalse(): List<CopyOrderTracking>
    
    @Query("SELECT t FROM CopyOrderTracking t WHERE t.createdAt <= :beforeTime")
    fun findByCreatedAtBefore(beforeTime: Long): List<CopyOrderTracking>

    fun findByCreatedAtBeforeAndStatus(beforeTime: Long, status: String): List<CopyOrderTracking>

    @Query("SELECT COUNT(DISTINCT CONCAT(t.marketId, '_', COALESCE(t.outcomeIndex, -1))) FROM CopyOrderTracking t WHERE t.copyTradingId = :copyTradingId AND t.remainingQuantity > 0")
    fun countActivePositions(copyTradingId: Long): Int

    @Query("SELECT SUM(t.remainingQuantity * t.price) FROM CopyOrderTracking t WHERE t.copyTradingId = :copyTradingId AND t.marketId = :marketId AND t.outcomeIndex = :outcomeIndex AND t.remainingQuantity > 0")
    fun sumCurrentPositionValueByMarketAndOutcomeIndex(copyTradingId: Long, marketId: String, outcomeIndex: Int): BigDecimal?

    @Query("SELECT t FROM CopyOrderTracking t WHERE t.copyTradingId = :copyTradingId AND t.marketId = :marketId AND t.outcomeIndex = :outcomeIndex AND t.remainingQuantity > 0 AND t.status <> 'pending_fill' AND t.createdAt < :thresholdTime ORDER BY t.createdAt ASC")
    fun findUnmatchedBuyOrdersByOutcomeIndexOlderThan(
        copyTradingId: Long,
        marketId: String,
        outcomeIndex: Int,
        thresholdTime: Long
    ): List<CopyOrderTracking>
}

