package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.LargeBetWatchRecord
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface LargeBetWatchRecordRepository : JpaRepository<LargeBetWatchRecord, Long> {
    fun findByTraderAddressAndMarketIdAndOutcome(
        traderAddress: String,
        marketId: String,
        outcome: String
    ): LargeBetWatchRecord?

    fun findAllByOrderByLastTriggeredAtDesc(): List<LargeBetWatchRecord>
}
