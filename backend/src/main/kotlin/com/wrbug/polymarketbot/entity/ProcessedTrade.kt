package com.wrbug.polymarketbot.entity

import jakarta.persistence.*

@Entity
@Table(
    name = "processed_trade",
    uniqueConstraints = [
        UniqueConstraint(columnNames = ["leader_id", "leader_trade_id"])
    ]
)
data class ProcessedTrade(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "leader_id", nullable = false)
    val leaderId: Long,
    
    @Column(name = "leader_trade_id", nullable = false, length = 100)
    val leaderTradeId: String,
    
    @Column(name = "trade_type", nullable = false, length = 10)
    val tradeType: String,
    
    @Column(name = "source", nullable = false, length = 20)
    val source: String,
    
    @Column(name = "status", nullable = false, length = 20)
    val status: String = "SUCCESS",
    
    @Column(name = "processed_at", nullable = false)
    val processedAt: Long = System.currentTimeMillis(),
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis()
)

