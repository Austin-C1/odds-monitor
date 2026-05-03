package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal

@Entity
@Table(name = "sell_match_detail")
data class SellMatchDetail(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "match_record_id", nullable = false)
    val matchRecordId: Long,
    
    @Column(name = "tracking_id", nullable = false)
    val trackingId: Long,
    
    @Column(name = "buy_order_id", nullable = false, length = 100)
    val buyOrderId: String,
    
    @Column(name = "matched_quantity", nullable = false, precision = 20, scale = 8)
    val matchedQuantity: BigDecimal,
    
    @Column(name = "buy_price", nullable = false, precision = 20, scale = 8)
    val buyPrice: BigDecimal,
    
    @Column(name = "sell_price", nullable = false, precision = 20, scale = 8)
    val sellPrice: BigDecimal,
    
    @Column(name = "realized_pnl", nullable = false, precision = 20, scale = 8)
    val realizedPnl: BigDecimal,
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis()
)

