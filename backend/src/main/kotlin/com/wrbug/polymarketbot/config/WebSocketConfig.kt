package com.wrbug.polymarketbot.config

import com.wrbug.polymarketbot.websocket.PolymarketWebSocketHandler
import com.wrbug.polymarketbot.websocket.UnifiedWebSocketHandler
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

@Configuration
@EnableWebSocket
class WebSocketConfig(
    private val polymarketWebSocketHandler: PolymarketWebSocketHandler,
    private val unifiedWebSocketHandler: UnifiedWebSocketHandler,
    private val webSocketAuthInterceptor: WebSocketAuthInterceptor,
    @Value("\${websocket.allowed-origins:}") private val allowedOriginsConfig: String
) : WebSocketConfigurer {

    private fun getAllowedOrigins(): Array<String> {
        return if (allowedOriginsConfig.isNotBlank()) {
            allowedOriginsConfig.split(",").map { it.trim() }.toTypedArray()
        } else {
            emptyArray()
        }
    }

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        val origins = getAllowedOrigins()
        val polymarketHandler = registry.addHandler(polymarketWebSocketHandler, "/ws/polymarket")
        if (origins.isNotEmpty()) {
            polymarketHandler.setAllowedOrigins(*origins)
        } else {
            polymarketHandler.setAllowedOriginPatterns("*")
        }
        val unifiedHandler = registry.addHandler(unifiedWebSocketHandler, "/ws")
            .addInterceptors(webSocketAuthInterceptor)
        if (origins.isNotEmpty()) {
            unifiedHandler.setAllowedOrigins(*origins)
        } else {
            unifiedHandler.setAllowedOriginPatterns("*")
        }
    }
}

