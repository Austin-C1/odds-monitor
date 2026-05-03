package com.wrbug.polymarketbot.config

import com.wrbug.polymarketbot.service.common.WebSocketSubscriptionService
import com.wrbug.polymarketbot.service.cryptotail.CryptoTailMonitorService
import jakarta.annotation.PostConstruct
import org.springframework.context.annotation.Configuration

@Configuration
class MonitorServiceConfig(
    private val webSocketSubscriptionService: WebSocketSubscriptionService,
    private val cryptoTailMonitorService: CryptoTailMonitorService
) {
    
    @PostConstruct
    fun init() {
        webSocketSubscriptionService.setCryptoTailMonitorService(cryptoTailMonitorService)
    }
}
