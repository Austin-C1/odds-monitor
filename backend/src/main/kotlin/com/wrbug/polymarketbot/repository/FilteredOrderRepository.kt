package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.FilteredOrder
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface FilteredOrderRepository : JpaRepository<FilteredOrder, Long> {
    
    fun findByCopyTradingIdOrderByCreatedAtDesc(
        copyTradingId: Long,
        pageable: Pageable
    ): Page<FilteredOrder>
    
    fun findByCopyTradingIdAndFilterTypeOrderByCreatedAtDesc(
        copyTradingId: Long,
        filterType: String,
        pageable: Pageable
    ): Page<FilteredOrder>
    
    @Query("SELECT f FROM FilteredOrder f WHERE f.copyTradingId = :copyTradingId AND f.createdAt >= :startTime AND f.createdAt <= :endTime ORDER BY f.createdAt DESC")
    fun findByCopyTradingIdAndTimeRange(
        @Param("copyTradingId") copyTradingId: Long,
        @Param("startTime") startTime: Long,
        @Param("endTime") endTime: Long,
        pageable: Pageable
    ): Page<FilteredOrder>
    
    fun countByCopyTradingId(copyTradingId: Long): Long
    
    fun countByCopyTradingIdAndFilterType(copyTradingId: Long, filterType: String): Long
}

