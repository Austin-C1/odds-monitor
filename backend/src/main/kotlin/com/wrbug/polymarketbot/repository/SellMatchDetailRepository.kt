package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.SellMatchDetail
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface SellMatchDetailRepository : JpaRepository<SellMatchDetail, Long> {
    
    fun findByMatchRecordId(matchRecordId: Long): List<SellMatchDetail>
    
    fun findByTrackingId(trackingId: Long): List<SellMatchDetail>
    
    fun findByBuyOrderId(buyOrderId: String): List<SellMatchDetail>
    
    @Query("SELECT d FROM SellMatchDetail d JOIN SellMatchRecord r ON d.matchRecordId = r.id WHERE r.sellOrderId = :sellOrderId")
    fun findBySellOrderId(sellOrderId: String): List<SellMatchDetail>
    
    @Query("SELECT d FROM SellMatchDetail d JOIN SellMatchRecord r ON d.matchRecordId = r.id WHERE r.copyTradingId = :copyTradingId")
    fun findByCopyTradingId(copyTradingId: Long): List<SellMatchDetail>

    @Query("SELECT d FROM SellMatchDetail d JOIN SellMatchRecord r ON d.matchRecordId = r.id WHERE r.copyTradingId IN :copyTradingIds")
    fun findByCopyTradingIdIn(copyTradingIds: List<Long>): List<SellMatchDetail>

    @Query(
        "SELECT d FROM SellMatchDetail d JOIN SellMatchRecord r ON d.matchRecordId = r.id " +
            "WHERE r.copyTradingId IN :copyTradingIds AND d.createdAt >= :since"
    )
    fun findByCopyTradingIdInAndCreatedAtGreaterThanEqual(copyTradingIds: List<Long>, since: Long): List<SellMatchDetail>
}

