package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.CopyTradingFollowRule
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface CopyTradingFollowRuleRepository : JpaRepository<CopyTradingFollowRule, Long> {
    fun findByCopyTradingIdOrderBySortOrderAsc(copyTradingId: Long): List<CopyTradingFollowRule>
    fun findByCopyTradingIdIn(copyTradingIds: List<Long>): List<CopyTradingFollowRule>
    fun deleteByCopyTradingId(copyTradingId: Long)
}
