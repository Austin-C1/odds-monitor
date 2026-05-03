package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal

@Entity
@Table(name = "sell_match_record")
data class SellMatchRecord(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "copy_trading_id", nullable = false)
    val copyTradingId: Long,
    
    @Column(name = "sell_order_id", nullable = false, length = 100)
    val sellOrderId: String,
    
    @Column(name = "leader_sell_trade_id", nullable = false, length = 100)
    val leaderSellTradeId: String,
    
    @Column(name = "market_id", nullable = false, length = 100)
    val marketId: String,
    
    @Column(name = "side", nullable = false, length = 10)
    val side: String,
    
    @Column(name = "outcome_index", nullable = true)
    val outcomeIndex: Int? = null,
    
    @Column(name = "total_matched_quantity", nullable = false, precision = 20, scale = 8)
    val totalMatchedQuantity: BigDecimal,
    
    @Column(name = "sell_price", nullable = false, precision = 20, scale = 8)
    val sellPrice: BigDecimal,
    
    @Column(name = "total_realized_pnl", nullable = false, precision = 20, scale = 8)
    val totalRealizedPnl: BigDecimal,
    
    @Column(name = "price_updated", nullable = false)
    var priceUpdated: Boolean = false,
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis()
)

