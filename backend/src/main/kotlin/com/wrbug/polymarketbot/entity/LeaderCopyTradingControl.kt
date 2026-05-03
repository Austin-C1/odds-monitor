package com.wrbug.polymarketbot.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal

@Entity
@Table(name = "leader_copy_trading_control")
data class LeaderCopyTradingControl(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "leader_id", nullable = false)
    val leaderId: Long,

    @Column(name = "auto_pause_enabled", nullable = false)
    val autoPauseEnabled: Boolean = true,

    @Column(name = "profit_take_enabled", nullable = false)
    val profitTakeEnabled: Boolean = true,

    @Column(name = "profit_take_price", nullable = false, precision = 10, scale = 4)
    val profitTakePrice: BigDecimal = BigDecimal("0.99"),

    @Column(name = "status", nullable = false, length = 20)
    val status: String = "ACTIVE",

    @Column(name = "paused_reason", length = 255)
    val pausedReason: String? = null,

    @Column(name = "last_peak_pnl", nullable = false, precision = 20, scale = 8)
    val lastPeakPnl: BigDecimal = BigDecimal.ZERO,

    @Column(name = "current_pnl", nullable = false, precision = 20, scale = 8)
    val currentPnl: BigDecimal = BigDecimal.ZERO,

    @Column(name = "current_drawdown_percent", nullable = false, precision = 10, scale = 2)
    val currentDrawdownPercent: BigDecimal = BigDecimal.ZERO,

    @Column(name = "drawdown_threshold_percent", nullable = false, precision = 10, scale = 2)
    val drawdownThresholdPercent: BigDecimal = BigDecimal("25.00"),

    @Column(name = "auto_paused_at")
    val autoPausedAt: Long? = null,

    @Column(name = "last_evaluated_at")
    val lastEvaluatedAt: Long? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Long = System.currentTimeMillis()
)
