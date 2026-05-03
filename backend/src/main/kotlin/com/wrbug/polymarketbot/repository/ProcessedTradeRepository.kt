package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.ProcessedTrade
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface ProcessedTradeRepository : JpaRepository<ProcessedTrade, Long> {
    
    fun existsByLeaderIdAndLeaderTradeId(leaderId: Long, leaderTradeId: String): Boolean
    
    fun findByLeaderIdAndLeaderTradeId(leaderId: Long, leaderTradeId: String): ProcessedTrade?
    
    @Modifying
    @Query("DELETE FROM ProcessedTrade p WHERE p.processedAt < :expireTime")
    fun deleteByProcessedAtBefore(expireTime: Long): Int
}

