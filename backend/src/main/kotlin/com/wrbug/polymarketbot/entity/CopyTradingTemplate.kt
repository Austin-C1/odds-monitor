package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal
import com.wrbug.polymarketbot.util.toSafeBigDecimal

@Entity
@Table(name = "copy_trading_templates")
data class CopyTradingTemplate(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "template_name", unique = true, nullable = false, length = 100)
    val templateName: String,
    
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
    
    @Column(name = "push_filtered_orders", nullable = false)
    val pushFilteredOrders: Boolean = false,
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)

