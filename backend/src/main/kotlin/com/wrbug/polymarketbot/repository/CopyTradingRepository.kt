package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.CopyTrading
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface CopyTradingRepository : JpaRepository<CopyTrading, Long> {
    
    fun findByAccountId(accountId: Long): List<CopyTrading>
    
    fun findByLeaderId(leaderId: Long): List<CopyTrading>

    fun findByLeaderIdIn(leaderIds: List<Long>): List<CopyTrading>
    
    fun findByAccountIdAndLeaderId(
        accountId: Long,
        leaderId: Long
    ): List<CopyTrading>
    
    fun findByEnabledTrue(): List<CopyTrading>
    
    fun findByAccountIdAndEnabledTrue(accountId: Long): List<CopyTrading>
    
    fun findByLeaderIdAndEnabledTrue(leaderId: Long): List<CopyTrading>
    
    fun existsByLeaderIdAndEnabledTrue(leaderId: Long): Boolean

    fun countByLeaderId(leaderId: Long): Long
}

