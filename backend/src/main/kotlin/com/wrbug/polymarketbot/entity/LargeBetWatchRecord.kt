package com.wrbug.polymarketbot.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal

@Entity
@Table(name = "large_bet_watch_records")
data class LargeBetWatchRecord(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "trader_address", nullable = false, length = 42)
    val traderAddress: String,

    @Column(name = "trader_name", length = 255)
    val traderName: String? = null,

    @Column(name = "profile_url", nullable = false, length = 500)
    val profileUrl: String,

    @Column(name = "market_id", nullable = false, length = 100)
    val marketId: String,

    @Column(name = "market_slug", length = 255)
    val marketSlug: String? = null,

    @Column(name = "market_title", nullable = false, length = 500)
    val marketTitle: String,

    @Column(name = "sport_type", nullable = false, length = 30)
    val sportType: String,

    @Column(name = "outcome", nullable = false, length = 100)
    val outcome: String,

    @Column(name = "trigger_reason", nullable = false, length = 30)
    val triggerReason: String,

    @Column(name = "last_single_amount", nullable = false, precision = 20, scale = 8)
    val lastSingleAmount: BigDecimal,

    @Column(name = "last_cumulative_amount", nullable = false, precision = 20, scale = 8)
    val lastCumulativeAmount: BigDecimal,

    @Column(name = "first_triggered_at", nullable = false)
    val firstTriggeredAt: Long,

    @Column(name = "last_triggered_at", nullable = false)
    val lastTriggeredAt: Long,

    @Column(name = "trigger_count", nullable = false)
    val triggerCount: Int = 1,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Long = System.currentTimeMillis()
)
