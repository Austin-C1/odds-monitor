package com.wrbug.polymarketbot.entity

import com.wrbug.polymarketbot.util.toSafeBigDecimal
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal

@Entity
@Table(name = "copy_trading")
data class CopyTrading(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "account_id", nullable = false)
    val accountId: Long,

    @Column(name = "leader_id", nullable = false)
    val leaderId: Long,

    @Column(name = "enabled", nullable = false)
    val enabled: Boolean = true,

    @Column(name = "follow_settings_enabled", nullable = false)
    val followSettingsEnabled: Boolean = false,

    @Column(name = "max_order_size", nullable = false, precision = 20, scale = 8)
    val maxOrderSize: BigDecimal = "1000".toSafeBigDecimal(),

    @Column(name = "min_order_size", nullable = false, precision = 20, scale = 8)
    val minOrderSize: BigDecimal = "1".toSafeBigDecimal(),

    @Column(name = "max_daily_loss", nullable = false, precision = 20, scale = 8)
    val maxDailyLoss: BigDecimal = "10000".toSafeBigDecimal(),

    @Column(name = "max_daily_orders", nullable = false)
    val maxDailyOrders: Int = 100,

    @Column(name = "price_tolerance", nullable = false, precision = 5, scale = 2)
    val priceTolerance: BigDecimal = "5".toSafeBigDecimal(),

    @Column(name = "delay_seconds", nullable = false)
    val delaySeconds: Int = 0,

    @Column(name = "poll_interval_seconds", nullable = false)
    val pollIntervalSeconds: Int = 5,

    @Column(name = "use_websocket", nullable = false)
    val useWebSocket: Boolean = true,

    @Column(name = "websocket_reconnect_interval", nullable = false)
    val websocketReconnectInterval: Int = 5000,

    @Column(name = "websocket_max_retries", nullable = false)
    val websocketMaxRetries: Int = 10,

    @Column(name = "support_sell", nullable = false)
    val supportSell: Boolean = true,

    @Column(name = "min_order_depth", precision = 20, scale = 8)
    val minOrderDepth: BigDecimal? = null,

    @Column(name = "max_spread", precision = 20, scale = 8)
    val maxSpread: BigDecimal? = null,

    @Column(name = "min_price", precision = 20, scale = 8)
    val minPrice: BigDecimal? = null,

    @Column(name = "max_price", precision = 20, scale = 8)
    val maxPrice: BigDecimal? = null,

    @Column(name = "max_position_value", precision = 20, scale = 8)
    val maxPositionValue: BigDecimal? = null,

    @Column(name = "keyword_filter_mode", nullable = false, length = 20)
    val keywordFilterMode: String = "DISABLED",

    @Column(name = "keywords", columnDefinition = "JSON")
    val keywords: String? = null,

    @Column(name = "config_name", length = 255)
    val configName: String? = null,

    @Column(name = "push_failed_orders", nullable = false)
    val pushFailedOrders: Boolean = false,

    @Column(name = "push_filtered_orders", nullable = false)
    val pushFilteredOrders: Boolean = false,

    @Column(name = "notification_routes", columnDefinition = "JSON")
    val notificationRoutes: String? = null,

    @Column(name = "max_market_end_date")
    val maxMarketEndDate: Long? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)
