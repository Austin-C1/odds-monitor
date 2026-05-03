package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal
import com.wrbug.polymarketbot.util.toSafeBigDecimal

@Entity
@Table(name = "backtest_task")
data class BacktestTask(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "task_name", nullable = false, length = 100)
    val taskName: String,

    @Column(name = "leader_id", nullable = false)
    val leaderId: Long,
    @Column(name = "initial_balance", nullable = false, precision = 20, scale = 8)
    val initialBalance: BigDecimal,

    @Column(name = "final_balance", precision = 20, scale = 8)
    var finalBalance: BigDecimal? = null,

    @Column(name = "profit_amount", precision = 20, scale = 8)
    var profitAmount: BigDecimal? = null,

    @Column(name = "profit_rate", precision = 10, scale = 4)
    var profitRate: BigDecimal? = null,

    @Column(name = "backtest_days", nullable = false)
    val backtestDays: Int,

    @Column(name = "start_time", nullable = false)
    val startTime: Long,

    @Column(name = "end_time")
    var endTime: Long? = null,
    @Column(name = "copy_mode", nullable = false, length = 10)
    val copyMode: String = "RATIO",

    @Column(name = "copy_ratio", nullable = false, precision = 20, scale = 8)
    val copyRatio: BigDecimal = BigDecimal.ONE,

    @Column(name = "fixed_amount", precision = 20, scale = 8)
    val fixedAmount: BigDecimal? = null,

    @Column(name = "max_order_size", nullable = false, precision = 20, scale = 8)
    val maxOrderSize: BigDecimal = "1000".toSafeBigDecimal(),

    @Column(name = "min_order_size", nullable = false, precision = 20, scale = 8)
    val minOrderSize: BigDecimal = "1".toSafeBigDecimal(),

    @Column(name = "max_daily_loss", nullable = false, precision = 20, scale = 8)
    val maxDailyLoss: BigDecimal = "10000".toSafeBigDecimal(),

    @Column(name = "max_daily_orders", nullable = false)
    val maxDailyOrders: Int = 100,

    @Column(name = "support_sell", nullable = false)
    val supportSell: Boolean = true,

    @Column(name = "keyword_filter_mode", nullable = false, length = 20)
    val keywordFilterMode: String = "DISABLED",  // DISABLED/WHITELIST/BLACKLIST

    @Column(name = "keywords", columnDefinition = "JSON")
    val keywords: String? = null,

    @Column(name = "max_position_value", precision = 20, scale = 8)
    val maxPositionValue: BigDecimal? = null,

    @Column(name = "min_price", precision = 20, scale = 8)
    val minPrice: BigDecimal? = null,

    @Column(name = "max_price", precision = 20, scale = 8)
    val maxPrice: BigDecimal? = null,
    @Column(name = "avg_holding_time")
    var avgHoldingTime: Long? = null,

    @Column(name = "data_source", length = 50)
    var dataSource: String = "MIXED",  // INTERNAL/API/MIXED
    @Column(name = "status", nullable = false, length = 20)
    var status: String = "PENDING",  // PENDING/RUNNING/COMPLETED/STOPPED/FAILED

    @Column(name = "progress", nullable = false)
    var progress: Int = 0,

    @Column(name = "total_trades", nullable = false)
    var totalTrades: Int = 0,

    @Column(name = "buy_trades", nullable = false)
    var buyTrades: Int = 0,

    @Column(name = "sell_trades", nullable = false)
    var sellTrades: Int = 0,

    @Column(name = "win_trades", nullable = false)
    var winTrades: Int = 0,

    @Column(name = "loss_trades", nullable = false)
    var lossTrades: Int = 0,

    @Column(name = "win_rate", precision = 5, scale = 2)
    var winRate: BigDecimal? = null,

    @Column(name = "max_profit", precision = 20, scale = 8)
    var maxProfit: BigDecimal? = null,

    @Column(name = "max_loss", precision = 20, scale = 8)
    var maxLoss: BigDecimal? = null,

    @Column(name = "max_drawdown", precision = 20, scale = 8)
    var maxDrawdown: BigDecimal? = null,

    @Column(name = "error_message", columnDefinition = "TEXT")
    var errorMessage: String? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "execution_started_at")
    var executionStartedAt: Long? = null,

    @Column(name = "execution_finished_at")
    var executionFinishedAt: Long? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis(),

    @Column(name = "last_processed_trade_time")
    var lastProcessedTradeTime: Long? = null,

    @Column(name = "last_processed_trade_index")
    var lastProcessedTradeIndex: Int? = null,

    @Column(name = "processed_trade_count")
    var processedTradeCount: Int = 0
)

