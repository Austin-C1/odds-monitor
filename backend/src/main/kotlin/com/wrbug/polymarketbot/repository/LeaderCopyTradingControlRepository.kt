package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.LeaderCopyTradingControl
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface LeaderCopyTradingControlRepository : JpaRepository<LeaderCopyTradingControl, Long> {
    fun findByLeaderId(leaderId: Long): LeaderCopyTradingControl?
    fun findByLeaderIdIn(leaderIds: List<Long>): List<LeaderCopyTradingControl>
    fun deleteByLeaderId(leaderId: Long): Long

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM LeaderCopyTradingControl c WHERE c.leaderId = :leaderId")
    fun findByLeaderIdForUpdate(leaderId: Long): LeaderCopyTradingControl?
}
