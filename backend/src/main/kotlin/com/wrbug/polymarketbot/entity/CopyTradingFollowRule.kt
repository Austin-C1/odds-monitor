package com.wrbug.polymarketbot.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal

@Entity
@Table(name = "copy_trading_follow_rule")
data class CopyTradingFollowRule(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "copy_trading_id", nullable = false)
    val copyTradingId: Long,

    @Column(name = "min_leader_amount", nullable = false, precision = 20, scale = 8)
    val minLeaderAmount: BigDecimal,

    @Column(name = "max_leader_amount", precision = 20, scale = 8)
    val maxLeaderAmount: BigDecimal? = null,

    @Column(name = "follow_amount", nullable = false, precision = 20, scale = 8)
    val followAmount: BigDecimal,

    @Column(name = "follow_max_amount", nullable = false, precision = 20, scale = 8)
    val followMaxAmount: BigDecimal,

    @Column(name = "sort_order", nullable = false)
    val sortOrder: Int = 0,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Long = System.currentTimeMillis()
)
