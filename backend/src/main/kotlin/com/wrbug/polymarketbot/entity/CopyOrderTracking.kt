package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal

@Entity
@Table(name = "copy_order_tracking")
data class CopyOrderTracking(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "copy_trading_id", nullable = false)
    val copyTradingId: Long,
    
    @Column(name = "account_id", nullable = false)
    val accountId: Long,
    
    @Column(name = "leader_id", nullable = false)
    val leaderId: Long,
    
    @Column(name = "market_id", nullable = false, length = 100)
    val marketId: String,
    
    @Column(name = "side", nullable = false, length = 10)
    val side: String,
    
    @Column(name = "outcome_index", nullable = true)
    val outcomeIndex: Int? = null,
    
    @Column(name = "buy_order_id", nullable = false, length = 100)
    val buyOrderId: String,
    
    @Column(name = "leader_buy_trade_id", nullable = false, length = 100)
    val leaderBuyTradeId: String,
    
    @Column(name = "leader_buy_quantity", nullable = true, precision = 20, scale = 8)
    val leaderBuyQuantity: BigDecimal? = null,
    
    @Column(name = "quantity", nullable = false, precision = 20, scale = 8)
    val quantity: BigDecimal,
    
    @Column(name = "price", nullable = false, precision = 20, scale = 8)
    val price: BigDecimal,
    
    @Column(name = "matched_quantity", nullable = false, precision = 20, scale = 8)
    var matchedQuantity: BigDecimal = BigDecimal.ZERO,
    
    @Column(name = "remaining_quantity", nullable = false, precision = 20, scale = 8)
    var remainingQuantity: BigDecimal,
    
    @Column(name = "status", nullable = false, length = 20)
    var status: String = "filled",  // filled, fully_matched, partially_matched
    
    @Column(name = "notification_sent", nullable = false)
    var notificationSent: Boolean = false,

    @Column(name = "source", nullable = false, length = 20)
    val source: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)

