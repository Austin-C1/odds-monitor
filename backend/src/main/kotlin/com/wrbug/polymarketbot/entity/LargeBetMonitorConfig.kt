package com.wrbug.polymarketbot.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal

@Entity
@Table(name = "large_bet_monitor_config")
data class LargeBetMonitorConfig(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "enabled", nullable = false)
    val enabled: Boolean = false,

    @Column(name = "football_enabled", nullable = false)
    val footballEnabled: Boolean = true,

    @Column(name = "basketball_enabled", nullable = false)
    val basketballEnabled: Boolean = true,

    @Column(name = "single_trade_threshold", nullable = false, precision = 20, scale = 8)
    val singleTradeThreshold: BigDecimal = BigDecimal("5000.00000000"),

    @Column(name = "cumulative_trade_threshold", nullable = false, precision = 20, scale = 8)
    val cumulativeTradeThreshold: BigDecimal = BigDecimal("15000.00000000"),

    @Column(name = "rolling_window_minutes", nullable = false)
    val rollingWindowMinutes: Int = 60,

    @Column(name = "check_interval_seconds", nullable = false)
    val checkIntervalSeconds: Int = 30,

    @Column(name = "telegram_config_id")
    val telegramConfigId: Long? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Long = System.currentTimeMillis()
)
