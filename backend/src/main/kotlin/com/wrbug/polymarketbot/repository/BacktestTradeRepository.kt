package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.BacktestTrade
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface BacktestTradeRepository : JpaRepository<BacktestTrade, Long> {

    fun findByBacktestTaskIdOrderByTradeTime(backtestTaskId: Long): List<BacktestTrade>

    @Query("SELECT t FROM BacktestTrade t WHERE t.backtestTaskId = :backtestTaskId ORDER BY t.tradeTime")
    fun findByBacktestTaskId(
        backtestTaskId: Long,
        pageable: org.springframework.data.domain.Pageable
    ): org.springframework.data.domain.Page<BacktestTrade>

    fun countByBacktestTaskId(backtestTaskId: Long): Long

    fun deleteByBacktestTaskId(backtestTaskId: Long)
}

