package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.SellMatchRecord
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.math.BigDecimal

@Repository
interface SellMatchRecordRepository : JpaRepository<SellMatchRecord, Long> {
    
    fun findByCopyTradingId(copyTradingId: Long): List<SellMatchRecord>

    fun findByCopyTradingIdIn(copyTradingIds: List<Long>): List<SellMatchRecord>
    
    fun findBySellOrderId(sellOrderId: String): SellMatchRecord?
    
    fun findByLeaderSellTradeId(leaderSellTradeId: String): SellMatchRecord?
    
    fun findByPriceUpdatedFalse(): List<SellMatchRecord>

    @Query(
        "SELECT r FROM SellMatchRecord r " +
            "WHERE r.copyTradingId = :copyTradingId AND r.marketId = :marketId " +
            "AND r.outcomeIndex = :outcomeIndex AND r.priceUpdated = false " +
            "AND r.createdAt >= :createdAfter ORDER BY r.createdAt DESC"
    )
    fun findRecentPendingByCopyTradingIdAndMarketIdAndOutcomeIndex(
        copyTradingId: Long,
        marketId: String,
        outcomeIndex: Int,
        createdAfter: Long
    ): List<SellMatchRecord>

    @Query(
        "SELECT COALESCE(SUM(r.totalRealizedPnl), 0) FROM SellMatchRecord r " +
            "WHERE r.copyTradingId = :copyTradingId AND r.createdAt >= :createdAt AND r.priceUpdated = true"
    )
    fun sumSettledRealizedPnlByCopyTradingIdAndCreatedAtGreaterThanEqual(
        copyTradingId: Long,
        createdAt: Long
    ): BigDecimal?
}

