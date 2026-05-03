package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal

@Entity
@Table(name = "filtered_order")
data class FilteredOrder(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "copy_trading_id", nullable = false)
    val copyTradingId: Long,
    
    @Column(name = "account_id", nullable = false)
    val accountId: Long,
    
    @Column(name = "leader_id", nullable = false)
    val leaderId: Long,
    
    @Column(name = "leader_trade_id", nullable = false, length = 100)
    val leaderTradeId: String,
    
    @Column(name = "market_id", nullable = false, length = 100)
    val marketId: String,
    
    @Column(name = "market_title", length = 500)
    val marketTitle: String? = null,
    
    @Column(name = "market_slug", length = 200)
    val marketSlug: String? = null,
    
    @Column(name = "side", nullable = false, length = 10)
    val side: String,
    
    @Column(name = "outcome_index", nullable = true)
    val outcomeIndex: Int? = null,
    
    @Column(name = "outcome", length = 50)
    val outcome: String? = null,
    
    @Column(name = "price", nullable = false, precision = 20, scale = 8)
    val price: BigDecimal,
    
    @Column(name = "size", nullable = false, precision = 20, scale = 8)
    val size: BigDecimal,
    
    @Column(name = "calculated_quantity", precision = 20, scale = 8)
    val calculatedQuantity: BigDecimal? = null,
    
    @Column(name = "filter_reason", nullable = false, columnDefinition = "TEXT")
    val filterReason: String,
    
    @Column(name = "filter_type", nullable = false, length = 50)
    val filterType: String,
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis()
)

