package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.Market
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface MarketRepository : JpaRepository<Market, Long> {
    fun findByMarketId(marketId: String): Market?
    fun findByMarketIdIn(marketIds: List<String>): List<Market>
}

